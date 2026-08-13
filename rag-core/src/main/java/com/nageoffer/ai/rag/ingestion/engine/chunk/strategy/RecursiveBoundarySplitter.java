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

package com.nageoffer.ai.rag.ingestion.engine.chunk.strategy;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * 递归降级边界切分器（{@link BoundaryAwareSplitter} 默认实现）。
 * <p>
 * 思路参考 LangChain {@code RecursiveCharacterTextSplitter}：定义一组按"自然程度"从高到低排列的
 * 边界级别，先用最高级尝试切；某一片段仍超 {@code maxChars} 时，对该片段用下一级递归再切；
 * 所有级别都切不动且仍超长时，才退回字符硬切兜底。
 *
 * <h3>边界优先级（高 → 低）</h3>
 * <ol>
 *   <li>空行段落（{@code \n\n} 之后）</li>
 *   <li>单换行（{@code \n} 之后）</li>
 *   <li>中文句末标点（{@code 。！？…} 之后）</li>
 *   <li>英文句末标点（{@code .!?} 之后，且后接空白/结尾 —— 避免切断 URL 域名点号）</li>
 *   <li>分号（{@code ；;} 之后）</li>
 *   <li>逗号（{@code ，,} 之后）</li>
 *   <li>字符硬切（最后兜底）</li>
 * </ol>
 *
 * <p>本实现脱胎于 {@code FixedSizeTextChunker.adjustToBoundary} 的边界回退逻辑，统一收口后
 * 供 {@code ParagraphChunker}（block-aware 长段落）与 {@code StructureAwareTextChunker}（legacy 超长块）复用。
 *
 * <p>复杂度：每级 O(n)，共 6 级 + 字符兜底；递归深度受边界级别数约束（最深 6 层）。
 */
@Component
public class RecursiveBoundarySplitter implements BoundaryAwareSplitter {

    /**
     * 各级断点判定（按优先级）。
     * <p>{@code test(text, i)} 为真表示 {@code i} 是一个合法的片段 end 下标，
     * 即可在 {@code i} 处切断、分隔符/标点保留在前一片尾部（{@code [.., i)} 内）。
     */
    private static final List<BiPredicate<String, Integer>> LEVELS = List.of(
            // 1) 空行段落：\n\n 之后
            (t, i) -> i >= 2 && t.charAt(i - 1) == '\n' && t.charAt(i - 2) == '\n',
            // 2) 单换行之后
            (t, i) -> i >= 1 && t.charAt(i - 1) == '\n',
            // 3) 中文句末标点之后
            (t, i) -> i >= 1 && isCjkSentenceEnd(t.charAt(i - 1)),
            // 4) 英文句末标点之后（后接空白/结尾，避免切 URL 域名点号）
            (t, i) -> i >= 1 && isEnSentenceEnd(t.charAt(i - 1))
                    && (i >= t.length() || Character.isWhitespace(t.charAt(i))),
            // 5) 分号之后
            (t, i) -> i >= 1 && (t.charAt(i - 1) == '；' || t.charAt(i - 1) == ';'),
            // 6) 逗号之后
            (t, i) -> i >= 1 && (t.charAt(i - 1) == '，' || t.charAt(i - 1) == ',')
    );

    private static boolean isCjkSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '…';
    }

    private static boolean isEnSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?';
    }

    @Override
    public List<String> split(String text, int minChars, int targetChars, int maxChars, int overlapChars) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        if (maxChars <= 0) {
            return List.of(text);
        }
        text = normalize(text);
        if (text.length() <= maxChars) {
            return List.of(text);
        }

        int safeMax = Math.max(1, maxChars);
        int safeTarget = Math.min(Math.max(1, targetChars), safeMax);
        int safeMin = Math.min(Math.max(1, minChars), safeTarget);

        // 1) 递归降级切成叶子片段（每片 <= maxChars）
        List<String> leaves = recursiveSplit(text, safeMax);
        // 2) 贪心合并叶子到 [min, target]，超 max 发出
        List<String> merged = greedyMerge(leaves, safeMin, safeTarget, safeMax);
        // 3) 可选 overlap（在边界点对齐的尾部复制）
        if (overlapChars > 0) {
            return applyOverlap(merged, overlapChars);
        }
        return merged;
    }

    // ----------- 1) 递归降级：先用最高级边界，切不动就降一级 -----------

    private List<String> recursiveSplit(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }

        for (BiPredicate<String, Integer> level : LEVELS) {
            List<Integer> boundaries = collectBoundaries(text, level);
            List<String> parts = splitAtBoundaries(text, boundaries);
            if (parts.size() < 2) {
                continue; // 该级无断点 / 切不动，降级到下一级
            }
            // 仍超 max 的片段用下一级递归再切；空白片段丢弃
            List<String> result = new ArrayList<>();
            for (String part : parts) {
                if (part.length() > maxChars) {
                    result.addAll(recursiveSplit(part, maxChars));
                } else if (!part.strip().isEmpty()) {
                    result.add(part);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
            // 该级切出的非空片全被归并/丢弃（极端），继续试下一级
        }

        // 所有边界级别都切不动且仍超长：字符硬切兜底
        return hardSplit(text, maxChars);
    }

    private List<Integer> collectBoundaries(String text, BiPredicate<String, Integer> level) {
        List<Integer> bs = new ArrayList<>();
        int n = text.length();
        for (int i = 1; i <= n; i++) {
            if (level.test(text, i)) {
                bs.add(i);
            }
        }
        return bs;
    }

    private List<String> splitAtBoundaries(String text, List<Integer> boundaries) {
        List<String> parts = new ArrayList<>(boundaries.size() + 1);
        int prev = 0;
        for (int b : boundaries) {
            if (b > prev) {
                parts.add(text.substring(prev, b));
            }
            prev = b;
        }
        if (prev < text.length()) {
            parts.add(text.substring(prev));
        }
        return parts;
    }

    // ----------- 2) 贪心合并叶子片段 -----------

    private List<String> greedyMerge(List<String> leaves, int minChars, int targetChars, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String leaf : leaves) {
            if (leaf.strip().isEmpty()) {
                continue;
            }
            if (cur.length() == 0) {
                cur.append(leaf);
            } else if (cur.length() + leaf.length() <= maxChars) {
                cur.append(leaf);
            } else {
                // 当前块已满：先发出，再用 leaf 开新块
                chunks.add(cur.toString());
                cur = new StringBuilder(leaf);
            }
            // 累加到 target 附近即倾向于断开，避免一直顶到 max
            if (cur.length() >= targetChars) {
                chunks.add(cur.toString());
                cur = new StringBuilder();
            }
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString());
        }
        return mergeTinyTail(chunks, minChars, maxChars);
    }

    /**
     * 尾块过小（{@code < minChars}）时尝试并入前一块；合并后不得超过 {@code maxChars}，否则保留碎尾。
     */
    private List<String> mergeTinyTail(List<String> chunks, int minChars, int maxChars) {
        while (chunks.size() >= 2) {
            int last = chunks.size() - 1;
            String tail = chunks.get(last);
            String prev = chunks.get(last - 1);
            if (tail.length() < minChars && prev.length() + tail.length() <= maxChars) {
                chunks.set(last - 1, prev + tail);
                chunks.remove(last);
            } else {
                break;
            }
        }
        return chunks;
    }

    // ----------- 3) overlap：前一片尾部拼到下一片开头 -----------

    private List<String> applyOverlap(List<String> chunks, int overlapChars) {
        if (overlapChars <= 0 || chunks.size() < 2) {
            return chunks;
        }
        List<String> out = new ArrayList<>(chunks.size());
        out.add(chunks.get(0));
        for (int k = 1; k < chunks.size(); k++) {
            String prevTail = tail(chunks.get(k - 1), overlapChars);
            out.add(prevTail + chunks.get(k));
        }
        return out;
    }

    // ----------- 兜底与小工具 -----------

    private List<String> hardSplit(String text, int maxChars) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += maxChars) {
            parts.add(text.substring(i, Math.min(i + maxChars, text.length())));
        }
        return parts;
    }

    private static String normalize(String text) {
        // 统一行尾：\r\n → \n，老 Mac \r → \n。与 StructureAwareTextChunker 入口归一一致，重复调用幂等无害
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
