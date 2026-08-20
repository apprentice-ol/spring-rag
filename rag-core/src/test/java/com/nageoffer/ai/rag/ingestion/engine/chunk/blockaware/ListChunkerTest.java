package com.nageoffer.ai.rag.ingestion.engine.chunk.blockaware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nageoffer.ai.rag.ingestion.engine.chunk.VectorChunk;
import com.nageoffer.ai.rag.ingestion.engine.chunk.strategy.RecursiveBoundarySplitter;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ListBlock;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * ListChunker 字符预算分组的回归测试。
 * 背景：旧版只按条数分组（≤15 atomic / 每 10 条一组），liveRag 英文 md 产出 144 个 avg 7482 字符巨块。
 */
class ListChunkerTest {

    private static final BlockChunkConfig CFG = new BlockChunkConfig(1800, 0, 1400, 600, 1800, 5, 15, 10);

    private final ListChunker chunker = new ListChunker(new RecursiveBoundarySplitter());

    private static ListBlock block(boolean ordered, List<String> items) {
        return new ListBlock("b1", null, List.of(), ordered, items);
    }

    @Test
    void shortList_staysAtomic() {
        List<String> items = List.of("alpha", "beta", "gamma");
        List<VectorChunk> chunks = chunker.chunk(block(false, items), ChunkContext.of(List.of(), CFG));
        assertEquals(1, chunks.size());
        assertEquals("LIST", chunks.get(0).getBlockType());
        assertTrue(chunks.get(0).getContent().startsWith("- alpha"));
    }

    @Test
    void shortItemCount_butOverCharBudget_splits() {
        // 10 条（≤15 条 atomic 阈值）× 每条 300 字符 = 渲染约 3040 > 1800：必须切，旧版这里产出巨块
        List<String> items = IntStream.range(0, 10)
                .mapToObj(i -> "item-" + i + " ".repeat(290))
                .toList();
        List<VectorChunk> chunks = chunker.chunk(block(false, items), ChunkContext.of(List.of(), CFG));
        assertTrue(chunks.size() >= 2, "字符超限的短列表必须切分，实际块数=" + chunks.size());
        for (VectorChunk c : chunks) {
            assertTrue(c.getContent().length() <= CFG.packMaxChars(),
                    "存在超限块: " + c.getContent().length());
        }
    }

    @Test
    void longList_charBudgetGroups_allUnderMax() {
        // 50 条 × 200 字符 ≈ 10200：按 1400 预算约 8 组，旧版每 10 条一组 = 5 组 × 2040 字符（超限）
        List<String> items = IntStream.range(0, 50)
                .mapToObj(i -> "list item number " + i + " " + "x".repeat(180))
                .toList();
        List<VectorChunk> chunks = chunker.chunk(block(false, items), ChunkContext.of(List.of(), CFG));
        assertTrue(chunks.size() >= 5);
        for (VectorChunk c : chunks) {
            assertTrue(c.getContent().length() <= CFG.packMaxChars(),
                    "存在超限块: " + c.getContent().length());
        }
        // 内容不丢条目：所有 50 条都应出现在块里
        int totalItems = 0;
        for (VectorChunk c : chunks) {
            for (String line : c.getContent().split("\n")) {
                if (line.startsWith("- ")) {
                    totalItems++;
                }
            }
        }
        assertEquals(50, totalItems, "分组不得丢条目");
    }

    @Test
    void orderedList_numberingContinuousAcrossGroups() {
        List<String> items = IntStream.range(0, 40)
                .mapToObj(i -> "ordered point " + i + " " + "y".repeat(180))
                .toList();
        List<VectorChunk> chunks = chunker.chunk(block(true, items), ChunkContext.of(List.of(), CFG));
        assertTrue(chunks.size() >= 2);
        // 各组起始编号连续递增：第一组从 1 开始
        String firstLine = chunks.get(0).getContent().split("\n")[0];
        assertTrue(firstLine.startsWith("1. "), "首组应从 1 开始编号: " + firstLine);
        // 相邻组的首行编号 = 前组末行编号 + 1
        for (int g = 1; g < chunks.size(); g++) {
            int prevLast = lastNumber(chunks.get(g - 1).getContent());
            int currFirst = firstNumber(chunks.get(g).getContent());
            assertEquals(prevLast + 1, currFirst, "ordered 编号跨组必须连续");
        }
        // 最后一组的末条编号 = 40
        assertEquals(40, lastNumber(chunks.get(chunks.size() - 1).getContent()));
    }

    @Test
    void singleOverlongItem_splitByBoundaryAwareSplitter() {
        // 单条 3000 字符 > 1800：该条独占组后必须过 splitter 兜底
        List<String> items = List.of("z".repeat(3000), "tail item");
        List<VectorChunk> chunks = chunker.chunk(block(false, items), ChunkContext.of(List.of(), CFG));
        for (VectorChunk c : chunks) {
            assertTrue(c.getContent().length() <= CFG.maxChars(),
                    "单条超长兜底切分后仍超限: " + c.getContent().length());
        }
    }

    private static int firstNumber(String content) {
        return Integer.parseInt(content.split("\n")[0].split("\\.")[0].trim());
    }

    private static int lastNumber(String content) {
        String[] lines = content.split("\n");
        return Integer.parseInt(lines[lines.length - 1].split("\\.")[0].trim());
    }
}
