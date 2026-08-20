/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.rag.ingestion.engine.chunk;

import com.nageoffer.ai.rag.ingestion.engine.parser.model.Block;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.HeadingBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ParagraphBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 分块前的块级内容清洗：清理解析产物里的"解析噪声"，防止其进入 embedding 与 BM25。
 * <p>
 * 2026-08 诊断发现（MinerU 解析中文 PDF 后的实况）：
 * <ul>
 *   <li>U+FFFD 乱码残片（字体解析失败）出现在标题/段落中，如 {@code ## � 相关}；</li>
 *   <li>PDF 版式噪声：相邻重复标题（{@code ## 去重（order=1)} 连续两次）、
 *       导航行（{@code 返回 RAG核心专题}、{@code 并行架构：../02-高并发/...}）混入正文。</li>
 * </ul>
 * 清洗规则刻意保守（防误杀正文），只命中"结构性噪声"特征：
 * <ul>
 *   <li><b>乱码字符</b>：删除 U+FFFD 字符（可多个），清洗后文本为空的块丢弃；</li>
 *   <li><b>相邻重复标题</b>：仅当两个 HeadingBlock <b>紧邻</b>且归一化文本相同时丢弃后者
 *       （不同章节的同名标题中间必有内容，不受影响）；</li>
 *   <li><b>导航行</b>：段落内按行过滤，命中以下全部条件才删行——
 *       整行较短（&lt;80 字符）、无句末标点、且匹配导航特征（以"返回"开头，
 *       或含 {@code ../}、{@code ./} 开头的相对路径）。过滤后为空的段落块丢弃。</li>
 * </ul>
 * 只处理 HEADING / PARAGRAPH 文本块；LIST/TABLE/CODE/IMAGE 原样透传。
 */
@Slf4j
@Component
public class BlockSanitizer {

    /** U+FFFD 替换字符（字体解码失败的产物） */
    private static final char REPLACEMENT_CHAR = '\uFFFD';

    /** 导航行长度上限：超长行视为正文，不做导航判定 */
    private static final int NAV_LINE_MAX_CHARS = 80;

    /** 中文导航前缀（整行以"返回"开头且很短，如"返回 RAG核心专题"） */
    private static final Pattern NAV_BACK_PATTERN = Pattern.compile("^返回[\\s:：]?.{0,30}$");

    /** 空白剥离（标题归一化用；逐块调用，预编译） */
    private static final Pattern WS_PATTERN = Pattern.compile("\\s+");
    /** 相对路径特征：../xxx 或 ./xxx（文档内导航链接的裸文本形式） */
    private static final Pattern RELATIVE_PATH_PATTERN = Pattern.compile("\\.{1,2}/[\\w\\-./%#?=&]+");

    /** 句末标点字符集：行以其中任一结尾才视为正文句子（不看行中——路径 ../ 的句点会误判） */
    private static final String SENTENCE_END_CHARS = "。．.!?！？；;";

    /**
     * 清洗结果：清洗后的块列表 + 统计（用于节点日志观测清洗强度）。
     */
    public record SanitizeResult(List<Block> blocks, int droppedBlocks, int strippedLines) {
    }

    /**
     * 清洗块列表：乱码剔除 → 导航行过滤 → 相邻重复标题去重 → 空块丢弃。
     * 入参为空时原样返回；返回新列表，不修改入参。
     */
    public SanitizeResult sanitize(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return new SanitizeResult(List.of(), 0, 0);
        }
        int dropped = 0;
        int strippedLines = 0;
        List<Block> cleaned = new ArrayList<>(blocks.size());
        String prevHeadingText = null;

        for (Block block : blocks) {
            if (block instanceof HeadingBlock h) {
                String text = stripReplacementChars(h.text());
                List<String> kept = filterNavLines(splitLines(text));
                strippedLines += splitLines(text).size() - kept.size();
                text = String.join("\n", kept).trim();
                // 相邻重复标题：与前一个紧邻 Heading 归一化文本相同 → 丢弃本块
                if (prevHeadingText != null && !prevHeadingText.isBlank()
                        && normalize(text).equals(prevHeadingText)) {
                    dropped++;
                    continue; // prevHeadingText 保持不变，连续 3+ 个同名标题也会被逐个去重
                }
                if (text.isBlank()) {
                    dropped++;
                    continue;
                }
                cleaned.add(new HeadingBlock(h.id(), h.provenance(), h.outlinePath(), h.level(), text));
                prevHeadingText = normalize(text);
                continue;
            }
            if (block instanceof ParagraphBlock p) {
                String text = stripReplacementChars(p.text());
                List<String> lines = splitLines(text);
                List<String> kept = filterNavLines(lines);
                strippedLines += lines.size() - kept.size();
                text = String.join("\n", kept).trim();
                if (text.isBlank()) {
                    dropped++;
                    continue;
                }
                cleaned.add(new ParagraphBlock(p.id(), p.provenance(), p.outlinePath(), text));
                prevHeadingText = null; // 两个标题之间隔了内容 → 不再视为"相邻重复"
                continue;
            }
            // LIST/CODE：不做行级导航过滤（语义不同），但 U+FFFD 乱码字符零信息量，字符级删除安全
            if (block instanceof com.nageoffer.ai.rag.ingestion.engine.parser.model.ListBlock l
                    && l.items() != null) {
                List<String> items = l.items().stream()
                        .map(BlockSanitizer::stripReplacementChars)
                        .filter(s -> !s.isBlank())
                        .toList();
                if (!items.isEmpty()) {
                    cleaned.add(new com.nageoffer.ai.rag.ingestion.engine.parser.model.ListBlock(
                            l.id(), l.provenance(), l.outlinePath(), l.ordered(), items));
                } else {
                    dropped++;
                    continue;
                }
                prevHeadingText = null;
                continue;
            }
            if (block instanceof com.nageoffer.ai.rag.ingestion.engine.parser.model.CodeBlock c
                    && c.code() != null) {
                String code = stripReplacementChars(c.code());
                if (code.isBlank()) {
                    dropped++;
                    continue;
                }
                cleaned.add(new com.nageoffer.ai.rag.ingestion.engine.parser.model.CodeBlock(
                        c.id(), c.provenance(), c.outlinePath(), c.language(), code));
                prevHeadingText = null;
                continue;
            }
            // TABLE/IMAGE 原样透传
            cleaned.add(block);
            prevHeadingText = null; // 任何非标题块都打断"相邻"链
        }
        if (dropped > 0 || strippedLines > 0) {
            log.info("[BlockSanitizer] 清洗完成: 丢块={}, 剔除导航行={}", dropped, strippedLines);
        }
        return new SanitizeResult(List.copyOf(cleaned), dropped, strippedLines);
    }

    /** 删除所有 U+FFFD 字符。 */
    private static String stripReplacementChars(String text) {
        if (text == null) {
            return "";
        }
        return text.indexOf(REPLACEMENT_CHAR) >= 0
                ? text.replace(String.valueOf(REPLACEMENT_CHAR), "")
                : text;
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.split("\n", -1));
    }

    /**
     * 导航行判定（三条件同时满足才删，宁可漏杀不可误杀）：
     * 1) 行短（&lt;80 字符）；2) 无句末标点；3) 命中导航特征（"返回"开头 或 含相对路径）。
     */
    private static List<String> filterNavLines(List<String> lines) {
        List<String> kept = new ArrayList<>(lines.size());
        for (String raw : lines) {
            String line = raw.strip();
            if (!line.isEmpty() && isNavLine(line)) {
                continue;
            }
            kept.add(raw);
        }
        return kept;
    }

    private static boolean isNavLine(String line) {
        // 行超长或以句末标点收尾 → 视为正文，不做导航判定
        // （句末判定只看行尾：../相对路径中的句点不是句子结束）
        if (line.length() >= NAV_LINE_MAX_CHARS || endsWithSentencePunctuation(line)) {
            return false;
        }
        return NAV_BACK_PATTERN.matcher(line).matches()
                || RELATIVE_PATH_PATTERN.matcher(line).find();
    }

    private static boolean endsWithSentencePunctuation(String line) {
        char c = line.charAt(line.length() - 1);
        return SENTENCE_END_CHARS.indexOf(c) >= 0;
    }

    /** 标题归一化：去全部空白 + 小写，用于重复判定。 */
    private static String normalize(String text) {
        return text == null ? "" : WS_PATTERN.matcher(text).replaceAll("").toLowerCase();
    }
}
