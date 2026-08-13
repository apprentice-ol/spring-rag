package com.nageoffer.ai.rag.chat.retrieval;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 查询拆词工具（SQL 关键词通道与 pg_bm25 通道共用）。
 *
 * <p>中文无分词器时的零依赖拆词策略：</p>
 * <ul>
 *   <li><b>整词</b>：按标点/空白切出的 ≥2 字词，保留原样——承担 doc_name 文档路由与"包含匹配"兜底</li>
 *   <li><b>2 字滑窗子词</b>：对 ≥5 字的无标点长词（如"增值税发票冲红流程"）追加全部 2 字连续子串
 *       （增值/值税/税发/发票/票冲/冲红/红流/流程），走精确匹配拉开块级区分度；
 *       不适用标点拆词与长词滑窗的常见中文检索场景</li>
 * </ul>
 */
public final class QueryTermSplitter {

    /** 长于此长度的整词才做 2 字滑窗（4 字以下滑窗会引入噪声子词） */
    private static final int SLIDING_WINDOW_MIN_LENGTH = 5;
    /** 滑窗大小（2 = 双字词，中文最小语义粒度） */
    private static final int WINDOW_SIZE = 2;

    private QueryTermSplitter() {
    }

    /**
     * 拆词结果。
     *
     * @param terms      有序去重的全部 term（整词在前，滑窗子词在后）
     * @param wholeTerms 整词集合（仅整词承担 doc_name 路由，滑窗子词不参与）
     */
    public record SplitResult(String[] terms, Set<String> wholeTerms) {
    }

    /**
     * 把 query 拆成关键词集合。
     *
     * @param query 用户查询（可为空）
     * @return 拆词结果；query 无有效词时 terms 为空数组
     */
    public static SplitResult split(String query) {
        Set<String> termSet = new LinkedHashSet<>();
        Set<String> wholeTerms = new HashSet<>();
        if (query == null) {
            return new SplitResult(new String[0], wholeTerms);
        }
        // 去标点、去空、去单字
        String[] rawTerms = query.toLowerCase()
                .split("[\\s,，。、；：！？!?\"'()（）\\[\\]【】/\\\\]+");
        for (String t : rawTerms) {
            String trimmed = t.trim();
            if (trimmed.length() < WINDOW_SIZE) {
                continue;
            }
            termSet.add(trimmed);
            wholeTerms.add(trimmed);
            // 含 CJK 的长词：仅追加 CJK 双字滑窗子词（"增值/值税/冲红"等可精确命中 chunk keywords）；
            // 纯英文/数字词不滑窗——英文词本身是完整语义单元，2 字滑窗只产生 er/re/om 字母对噪声；
            // 连写中英混合（SpringAI框架）只滑出 CJK 部分（框架）。
            if (trimmed.length() >= SLIDING_WINDOW_MIN_LENGTH) {
                for (int i = 0; i + WINDOW_SIZE <= trimmed.length(); i++) {
                    if (isCjk(trimmed.charAt(i)) && isCjk(trimmed.charAt(i + 1))) {
                        termSet.add(trimmed.substring(i, i + WINDOW_SIZE));
                    }
                }
            }
        }
        return new SplitResult(termSet.toArray(String[]::new), wholeTerms);
    }

    /** 是否 CJK 统一表意文字（含扩展 A/B + 兼容表意）—— 滑窗只对 CJK 双字有意义。 */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}
