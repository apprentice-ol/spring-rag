/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.rag.ingestion.engine.chunk.blockaware;

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.rag.ingestion.engine.chunk.VectorChunk;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.AssetRef;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chunk 打包器（block-aware 分块后处理）
 * <p>
 * 各类型 chunker 只负责"单个 block 内"的切分, 天然是"只拆不并": 一个短段落、一个短列表都会各自成块,
 * 512 体量预算只当上限、从不当目标, 于是结构清晰的小文档被切成一堆碎块
 * <p>
 * 本打包器在 dispatch 产出的有序 chunk 上做一次贪心合并：
 * <ul>
 *   <li><b>段落/列表/图片/标题</b>：按标准 targetChars(1400) 累加，达标附近断开
 *       （不足 minChars 时忍一次吸入，上限 maxChars(1800) 不超）</li>
 *   <li><b>CODE/TABLE</b>：<b>上限内合并、超限才独立</b>——合并后 ≤1800 则并入当前 buffer
 *       （"说明+代码/表格"同块，检索命中一块即得完整接口）；合并后 &gt;1800 则不合并、
 *       自身独立落块（不截断，代码/表格对完整性敏感）</li>
 * </ul>
 */
@Component
public class ChunkPacker {

    /**
     * 可合并的块类型: 文本流与图片, 相邻小块可拼到同一 chunk; 表格与代码保持原子。
     * <p>
     * CODE 虽在此集合中但已被 pack 循环提前短路（单独落块），不参与任何合并——避免代码块与周围
     * 段落/图片合并后被撑超 maxChars 而从中截断，参见 pack() 循环开头的 CODE 优先处理分支。
     * MinerU 按分页切出的多个相邻围栏代码块（各自括号完整）各自成块互不合并。
     * <p>
     * HEADING 纳入可合并且"向下合并"(见 pack 循环): 标题与其后内容并入同一 chunk, 不与上方粘连
     */
    // CODE 不在可合并集合：代码块走"≤1800 条件合并、>1800 独立"分支（见 pack 的 atomic 判断），
    // 若在集合内会走段落 1400 标准分支——buffer 超 1400 即被 flush，代码块被 1400 卡住拆碎
    // （实测五步法代码被拆成"说明+代码1+图"与"代码2"两块）
    private static final Set<String> MERGEABLE_TYPES = Set.of("PARAGRAPH", "LIST", "IMAGE", "HEADING");
    /**
     * 合并时块间分隔符, 保留段落 / 列表边界
     */
    private static final String SEPARATOR = "\n\n";

    /**
     * 贪心打包: 相邻可合并 chunk 累加至 targetChars, 不足 minChars 时允许忍一次超限吸入(不超过 maxChars),
     * 之后强制断开, 兼顾合并饱满度与硬上限
     *
     * @param chunks         dispatch 产出的有序 chunk
     * @param targetChars    合并目标: 累加到该值附近即倾向于断开
     * @param minChars       合并下限: 当前 chunk 不足此值时允许多吃一块
     * @param maxChars       合并硬上限: 单 chunk 不可超过此值
     * @param overlapChars   块级重叠预算 (0 表示不重叠)
     * @return 打包后的 chunk, index 从 0 单调递增
     */
    public List<VectorChunk> pack(List<VectorChunk> chunks, int targetChars, int minChars, int maxChars, int overlapChars) {
        if (chunks == null || chunks.size() <= 1) {
            return chunks == null ? List.of() : chunks;
        }

        List<VectorChunk> result = new ArrayList<>();
        List<VectorChunk> buffer = new ArrayList<>();
        int bufferLen = 0;

        for (VectorChunk c : chunks) {
            // CODE/TABLE 或已超上限的大块，或内容含 ``` 围栏的段落（MinerU 把图片隔断的代码后半
            // 误判为文本段落，如五步法代码被拆成"步骤1-2 + 图 + 步骤3-5"）——都按原子处理（≤1800 合并）

            // 是否是可以合并的
            boolean atomic = !isMergeable(c, maxChars) || looksLikeCode(c);

            // 计算文本块长度
            int addLen = contentLength(c);


            int sepLen = buffer.isEmpty() ? 0 : SEPARATOR.length();
            int afterAdd = bufferLen + sepLen + addLen;


            // 缓冲是空的直接进入
            if (buffer.isEmpty()) {
                bufferLen = afterAdd;
                buffer.add(c);
                continue;
            }

            // 章节边界（标题语义感知）：与 buffer 首块不同章节（outline_path 非前缀关系）时——
            // 章节内容足够（≥ minChars）才尊重边界独立成块；内容不足（如"为什么需要多通道"195 字符）
            // 则跨章节合并（与后续章节拼到标准），避免小块浪费。
            // atomic 块（代码/表格）不受章节边界限制：MinerU 把连续代码拆到不同子章节
            // （如五步法代码1 在"Excel 模块"、代码2 在"规范化五步法"），章节边界会拆断代码连续性，
            // 代码/表格按 1800 上限合并优先（后随段落仍受边界保护）。


            // 如果不是跨章节 且没有达到最小的块的大小 并且不是原子的块则进行合并
            if (!sameSection(buffer.get(0), c) && bufferLen >= minChars && !atomic) {
                flush(buffer, result);
                buffer.clear();
                bufferLen = 0;
                // flush 后 buffer 已空：分隔符与累加值必须重置，否则沿用旧值
                // 会让 bufferLen 虚高（如 937 而非 235），后续章节边界立刻误触发、块被孤立
                sepLen = 0;
                afterAdd = addLen;
            }

            // 合并后超上限(1800)：不合并——flush 当前 buffer；
            // 原子块（代码/表格）自身独立落块（不截断），可合并块进新 buffer 起点
            if (afterAdd > maxChars) {
                flush(buffer, result);
                buffer.clear();
                bufferLen = 0;
                if (atomic) {
                    result.add(c);
                } else {
                    bufferLen = addLen;
                    buffer.add(c);
                }
                continue;
            }

            if (atomic) {
                // 代码/表格块：合并后 ≤1800 则并入当前 buffer（与前面段落一体，
                // "说明+代码/表格"同块，检索命中一块即得完整接口；上限内合并、超限才独立）
                bufferLen = afterAdd;
                buffer.add(c);
                continue;
            }

            // 段落/列表/图片/标题：按标准(1400)累加
            boolean forceFlush = false;
            if (afterAdd <= targetChars) {
                // 标准内 → 继续累加
            } else if (bufferLen < minChars) {
                // 已达标准但未到下限 → 忍一次吸入, 然后强制 flush
                forceFlush = true;
            } else {
                // 已达标准且已满足下限 → 冲刷当前 buffer, c 进新 buffer
                flush(buffer, result);
                buffer.clear();
                bufferLen = 0;
                afterAdd = addLen;
            }

            bufferLen = afterAdd;
            buffer.add(c);

            if (forceFlush) {
                flush(buffer, result);
                buffer.clear();
                bufferLen = 0;
            }
        }
        flush(buffer, result);

        for (int i = 0; i < result.size(); i++) {
            result.get(i).setIndex(i);
        }
        return result;
    }

    /**
     * 取缓冲区尾部若干完整块作为下一块的重叠起点: 从后往前累加, 累计字符不超 budget, 保持原顺序
     *
     * @return 可变新列表(可能为空); 元素为原 chunk 引用(内容在下一块中被复现)
     */
    private static List<VectorChunk> overlapTail(List<VectorChunk> buffer, int budget) {
        List<VectorChunk> carry = new ArrayList<>();
        if (budget <= 0) {
            return carry;
        }
        int len = 0;
        for (int i = buffer.size() - 1; i >= 0; i--) {
            int sep = carry.isEmpty() ? 0 : SEPARATOR.length();
            int next = len + sep + contentLength(buffer.get(i));
            if (next > budget) {
                break;
            }
            carry.add(0, buffer.get(i));
            len = next;
        }
        return carry;
    }

    /**
     * 缓冲区当前拼接长度(含块间分隔符)
     */
    private static int bufferedLength(List<VectorChunk> buffer) {
        int len = 0;
        for (int i = 0; i < buffer.size(); i++) {
            len += (i == 0 ? 0 : SEPARATOR.length()) + contentLength(buffer.get(i));
        }
        return len;
    }

    /**
     * 可合并: 类型属于可合并块(文本 / 图片 / 代码), 且自身未达体量上限(超限大块是切分产物, 视为原子, 不再粘连)
     * <p>
     * 图片一律并入相邻上下文(不分有无描述): 图与它的前导语 / 解释文字同块, 检索命中即带图, 也不割裂正文
     */
    private static boolean isMergeable(VectorChunk c, int maxChars) {
        return MERGEABLE_TYPES.contains(c.getBlockType()) && contentLength(c) < maxChars;
    }

    /** 内容含 ``` 围栏的块视为代码（MinerU 把图片/其他元素隔断的代码后半误判为文本段落） */
    private static boolean looksLikeCode(VectorChunk c) {
        String s = c.getContent();
        return s != null && s.contains("```");
    }

    /**
     * 同章节判定（标题语义感知）：outline_path 互为前缀（较短者是较长者的前缀）视为同章节，
     * 允许合并（子章节内容可与父章节内容同块）；路径为空/不同前缀则视为章节边界，不跨章节合并。
     */
    private static boolean sameSection(VectorChunk a, VectorChunk b) {
        List<String> pa = a.getOutlinePath();
        List<String> pb = b.getOutlinePath();
        if (pa == null || pa.isEmpty() || pb == null || pb.isEmpty()) {
            return true;
        }
        int n = Math.min(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            if (!pa.get(i).equals(pb.get(i))) {
                return false;
            }
        }
        return true;
    }


    /**
     * 冲刷缓冲区: 空则跳过, 单块原样搬运, 多块合并成一个 chunk
     */
    private static void flush(List<VectorChunk> buffer, List<VectorChunk> result) {
        if (buffer.isEmpty()) {
            return;
        }
        if (buffer.size() == 1) {
            result.add(buffer.get(0));
            return;
        }
        result.add(merge(buffer));
    }

    /**
     * 合并多块: 内容按 SEPARATOR 拼接, outlinePath 取最长公共前缀(退化到共同祖先章节),
     * sourceBlockIds / assets 去重并集(无描述图片并入正文后其 AssetRef 仍随块留存),
     * blockType 同质则保留、异质归为 PARAGRAPH(仍是纯文本流)
     */
    private static VectorChunk merge(List<VectorChunk> buffer) {
        StringBuilder content = new StringBuilder();
        StringBuilder embeddingText = new StringBuilder();
        boolean hasExplicitEmbeddingText = false;
        String sectionContext = null;
        Set<String> sourceBlockIds = new LinkedHashSet<>();
        List<AssetRef> assets = new ArrayList<>();
        String blockType = buffer.get(0).getBlockType();
        boolean homogeneous = true;
        for (VectorChunk c : buffer) {
            if (!content.isEmpty()) {
                content.append(SEPARATOR);
            }
            content.append(c.getContent() == null ? "" : c.getContent());
            // embeddingText 不能在合并时丢弃：图片块特意用「无 URL 噪声」的描述文本做向量化，
            // 丢弃后 embedding 会退化为携带原始 URL 的 content。任一块显式提供 embeddingText
            // 时，合并块按「显式值优先、否则回退 content」逐块拼接
            String effectiveEmbedding = c.getEmbeddingText() != null && !c.getEmbeddingText().isBlank()
                    ? c.getEmbeddingText()
                    : c.getContent();
            if (c.getEmbeddingText() != null && !c.getEmbeddingText().isBlank()) {
                hasExplicitEmbeddingText = true;
            }
            if (effectiveEmbedding != null && !effectiveEmbedding.isBlank()) {
                if (!embeddingText.isEmpty()) {
                    embeddingText.append(SEPARATOR);
                }
                embeddingText.append(effectiveEmbedding);
            }
            // sectionContext 取首个非空值（合并块与 outlinePath 一样归属共同上级章节）
            if (sectionContext == null && c.getSectionContext() != null && !c.getSectionContext().isBlank()) {
                sectionContext = c.getSectionContext();
            }
            if (c.getSourceBlockIds() != null) {
                sourceBlockIds.addAll(c.getSourceBlockIds());
            }
            if (c.getAssets() != null) {
                assets.addAll(c.getAssets());
            }
            if (!java.util.Objects.equals(blockType, c.getBlockType())) {
                homogeneous = false;
            }
        }
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .content(content.toString())
                .embeddingText(hasExplicitEmbeddingText ? embeddingText.toString() : null)
                .sectionContext(sectionContext)
                .blockType(homogeneous ? blockType : "PARAGRAPH")
                .outlinePath(commonPrefix(buffer))
                .sourceBlockIds(new ArrayList<>(sourceBlockIds))
                .assets(assets)
                .build();
    }

    /**
     * 多块 outlinePath 的最长公共前缀: 合并块横跨若干小节时, 归属其共同上级章节
     */
    private static List<String> commonPrefix(List<VectorChunk> buffer) {
        List<String> prefix = new ArrayList<>(safePath(buffer.get(0)));
        for (int i = 1; i < buffer.size() && !prefix.isEmpty(); i++) {
            List<String> path = safePath(buffer.get(i));
            int keep = 0;
            while (keep < prefix.size() && keep < path.size()
                    && prefix.get(keep).equals(path.get(keep))) {
                keep++;
            }
            prefix.subList(keep, prefix.size()).clear();
        }
        return prefix;
    }

    private static List<String> safePath(VectorChunk c) {
        return c.getOutlinePath() == null ? List.of() : c.getOutlinePath();
    }

    private static int contentLength(VectorChunk c) {
        return StringUtils.hasText(c.getContent()) ? c.getContent().length() : 0;
    }
}
