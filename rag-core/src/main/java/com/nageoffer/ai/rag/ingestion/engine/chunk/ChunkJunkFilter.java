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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块后的碎块（junk chunk）过滤器：拦截"无检索价值"的残片块，防止其带噪声进入向量库与 BM25。
 * <p>
 * 2026-08 诊断实况：liveRag 数据 4141 块中 36 块 &lt;50 字符（{@code trix.}、{@code the flu.}、
 * {@code # #}——切分边界残渣 / 标题符号残留 / MinerU 页码水印 {@code OCR for page 141}）。
 * <p>
 * 判定规则（分语言 + 结构豁免，防误杀）：
 * <ul>
 *   <li><b>纯符号碎块</b>：去空白后 &lt;100 字符，且字母/数字/CJK 占比 &lt;30% → 丢
 *       （{@code # #}、{@code .}）；</li>
 *   <li><b>无 CJK 的短残片</b>：&lt;50 字符且不含 CJK → 丢（{@code trix.}＝8 字符、
 *       {@code OCR for page 141}＝16 字符）。英文 50 字符 ≈ 8 个词，独立成块无检索价值；</li>
 *   <li><b>CJK 短块保留</b>：中文 50 字符 ≈ 50 个汉字，可承载完整业务规则
 *       （"销售方开具红字信息表不可部分冲红"＝17 字），<b>不按长度丢</b>——中文块只受
 *       纯符号规则约束（中文标点/符号为主时同样命中第一条）；</li>
 *   <li><b>结构块豁免</b>：CODE / TABLE 不参与长度过滤（代码片段与表格行短但结构完整，
 *       交给 TableChunker/CodeChunker 与 packer 的专门逻辑）。</li>
 * </ul>
 * 过滤后重排 index（单调递增，保证 chunk_index 连续）。
 */
@Slf4j
@Component
public class ChunkJunkFilter {

    /** 空白剥离（碎块判定用；每个 chunk 都走，预编译） */
    private static final java.util.regex.Pattern WS_PATTERN = java.util.regex.Pattern.compile("\\s+");

    /** 纯符号碎块判定：长度上限 */
    private static final int SYMBOLIC_MAX_CHARS = 100;

    /** 纯符号碎块判定：字母/数字/CJK 最低占比 */
    private static final double MIN_ALPHANUMERIC_RATIO = 0.30;

    /** 无 CJK 短残片判定：长度上限（约 8 个英文词） */
    private static final int NON_CJK_MAX_CHARS = 50;

    /**
     * 过滤碎块并重排 index。
     *
     * @param chunks 分块（含 packer 合并后）产物
     * @return 过滤后的新列表（index 已重排）；入参为空原样返回
     */
    public List<VectorChunk> filter(List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<VectorChunk> kept = new ArrayList<>(chunks.size());
        int dropped = 0;
        for (VectorChunk chunk : chunks) {
            if (isJunk(chunk)) {
                dropped++;
                continue;
            }
            kept.add(chunk);
        }
        if (dropped > 0) {
            log.info("[ChunkJunkFilter] 碎块过滤: {} → {} 块（丢弃 {}）", chunks.size(), kept.size(), dropped);
            for (int i = 0; i < kept.size(); i++) {
                kept.get(i).setIndex(i);
            }
        }
        return kept;
    }

    /** 是否为碎块：结构块豁免 → 纯符号判定 → 无 CJK 短残片判定。 */
    private boolean isJunk(VectorChunk chunk) {
        String type = chunk.getBlockType();
        if ("CODE".equals(type) || "TABLE".equals(type)) {
            return false;
        }
        String content = chunk.getContent();
        if (content == null) {
            return true;
        }
        String compact = WS_PATTERN.matcher(content).replaceAll("");
        if (compact.isEmpty()) {
            return true;
        }
        // 纯符号碎块（任何语言）：短且几乎没有字母/数字/CJK
        if (compact.length() < SYMBOLIC_MAX_CHARS
                && alphanumericRatio(compact) < MIN_ALPHANUMERIC_RATIO) {
            return true;
        }
        // 英文/无 CJK 残片：短且不含 CJK（中文块不受长度规则约束）
        return compact.length() < NON_CJK_MAX_CHARS && !containsCjk(compact);
    }

    /** 字母/数字/CJK 字符占完整长度的比例。 */
    private static double alphanumericRatio(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                n++;
            }
        }
        return (double) n / s.length();
    }

    /** 是否含 CJK 表意字符（中日韩统一表意 + 扩展A）。 */
    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3400 && c <= 0x4DBF) {
                return true;
            }
        }
        return false;
    }
}
