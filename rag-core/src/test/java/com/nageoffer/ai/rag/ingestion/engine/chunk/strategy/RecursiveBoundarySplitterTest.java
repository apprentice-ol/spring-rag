package com.nageoffer.ai.rag.ingestion.engine.chunk.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 递归降级边界切分器单测（纯算法，无 Spring 上下文）。
 * <p>覆盖：空白/短文本、超长中文按句号切、URL 点号不误切、空行段落优先、无标点兜底硬切、overlap。
 */
class RecursiveBoundarySplitterTest {

    private final BoundaryAwareSplitter splitter = new RecursiveBoundarySplitter();

    @Test
    void blankReturnsEmpty() {
        assertTrue(splitter.split(null, 50, 200, 500, 0).isEmpty());
        assertTrue(splitter.split("   \n\n  ", 50, 200, 500, 0).isEmpty());
    }

    @Test
    void shortTextUntouched() {
        String text = "短文本，无需切分。";
        List<String> r = splitter.split(text, 50, 200, 500, 0);
        assertEquals(1, r.size());
        assertEquals(text, r.get(0));
    }

    @Test
    void longChineseParagraphSplitsAtSentenceEnd() {
        // 10 句，每句 ~21 字，总 ~210；max=80 迫使其必须切，但每句 21 < 80 不会硬切
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("这是第").append(i).append("个用于验证切分逻辑的中文句子内容。");
        }
        String text = sb.toString();
        List<String> r = splitter.split(text, 40, 60, 80, 0);
        assertTrue(r.size() >= 3, "应切成多片，实际 " + r.size());
        for (String p : r) {
            assertTrue(p.length() <= 80, "片长超 max: " + p.length());
            // 断点必落在句末标点 → 每片应以"。"结尾（证明没有从句子中间硬切）
            assertTrue(p.trim().endsWith("。"), "片未在句末标点处断开，尾部=" + tail(p, 12));
        }
    }

    @Test
    void urlDotsNotSplit() {
        String url = "https://example.com/path/to/resource?query=value&sort=asc";
        // URL 前后各放英文句子，总长 > max；padding 段补足长度
        String text = "Lead sentence one is long enough. " + url
                + " trailing sentence two is long enough. "
                + "padding padding padding padding padding padding padding padding.";
        List<String> r = splitter.split(text, 30, 60, 90, 0);
        // .com / .path 的点号后接字母（非空白）→ 不构成英文句末断点，URL 必须完整留在某片内
        assertTrue(r.stream().anyMatch(p -> p.contains(url)), "URL 被从点号处误切: " + r);
    }

    @Test
    void paragraphBoundaryHasPriority() {
        String para1 = "段落一".repeat(20) + "。"; // ~61 字
        String para2 = "段落二".repeat(20) + "。";
        String text = para1 + "\n\n" + para2;     // 跨空行，总 ~124 字
        List<String> r = splitter.split(text, 40, 60, 90, 0);
        // 空行是最高优先级边界：不应有任何一片同时含两段标记
        for (String p : r) {
            assertFalse(p.contains("段落一") && p.contains("段落二"),
                    "空行段落边界未生效，同一片含两段: " + p);
        }
    }

    @Test
    void noPunctuationFallsBackToHardSplit() {
        String text = "a".repeat(1000);
        List<String> r = splitter.split(text, 80, 200, 300, 0);
        assertTrue(r.size() >= 3);
        for (String p : r) {
            assertTrue(p.length() <= 300, "片长超 max: " + p.length());
        }
        // 硬切不丢字符、不产生空白片段 → 拼接回原文
        assertEquals(text, String.join("", r));
    }

    @Test
    void overlapPrependsPrevTail() {
        String text = "a".repeat(1000);
        List<String> r = splitter.split(text, 80, 200, 300, 50);
        assertTrue(r.size() >= 2);
        String prevTail = r.get(0).substring(r.get(0).length() - 50);
        assertTrue(r.get(1).startsWith(prevTail), "第二片未以第一片尾部 overlap 开头");
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
