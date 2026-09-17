package com.jjx.customer.platform.ingestion.engine.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ChunkJunkFilter 碎块过滤测试（含分语言阈值与结构块豁免的防误杀用例）。
 * 背景：liveRag 4141 块中 36 块 <50 字符英文残片（trix. / the flu. / # # / OCR for page 141）。
 */
class ChunkJunkFilterTest {

    private final ChunkJunkFilter filter = new ChunkJunkFilter();

    private static VectorChunk chunk(String type, String content) {
        return VectorChunk.builder()
                .chunkId("c-" + System.identityHashCode(content))
                .index(0)
                .content(content)
                .blockType(type)
                .build();
    }

    @Test
    void englishFragments_dropped() {
        // 实况样本：切分边界残渣 / 句子残尾 / MinerU 页码水印
        List<VectorChunk> kept = filter.filter(List.of(
                chunk("PARAGRAPH", "trix."),
                chunk("PARAGRAPH", "the flu."),
                chunk("HEADING", "# #"),
                chunk("PARAGRAPH", "OCR for page 141"),
                chunk("PARAGRAPH", "What to Know")));
        assertEquals(0, kept.size(), "英文短残片应全部丢弃");
    }

    @Test
    void pureSymbolicBlock_dropped_evenIfContainsCjkPunctuation() {
        // 纯符号块无论是否含 CJK 标点都丢弃（"＃ ＃"全角符号）
        List<VectorChunk> kept = filter.filter(List.of(chunk("HEADING", "＃ ＃ 。 ．")));
        assertEquals(0, kept.size());
    }

    @Test
    void shortCjkBusinessRule_kept() {
        // 防误杀：中文短块承载完整业务规则（实况样本 26 字），不按长度丢
        String rule = "销售方开具红字信息表的，不可以部分冲红。";
        List<VectorChunk> kept = filter.filter(List.of(chunk("PARAGRAPH", rule)));
        assertEquals(1, kept.size());
        assertEquals(rule, kept.get(0).getContent());
    }

    @Test
    void shortCodeAndTableBlocks_kept() {
        // 结构块豁免：代码/表格短但结构完整
        List<VectorChunk> kept = filter.filter(List.of(
                chunk("CODE", "int a = 1;"),
                chunk("TABLE", "| a | b |\n| 1 | 2 |")));
        assertEquals(2, kept.size());
    }

    @Test
    void normalEnglishChunk_kept() {
        List<VectorChunk> kept = filter.filter(List.of(chunk("PARAGRAPH",
                "The mintage of Capped Bust quarters in 1820 was 127,827 pieces struck at the Philadelphia mint.")));
        assertEquals(1, kept.size());
    }

    @Test
    void indexResequencedAfterDrop() {
        List<VectorChunk> kept = filter.filter(List.of(
                chunk("PARAGRAPH", "trix."),
                chunk("PARAGRAPH", "第一段正常内容，长度足够不会被过滤掉。"),
                chunk("PARAGRAPH", "第二段正常内容，长度同样足够通过过滤规则。")));
        assertEquals(2, kept.size());
        assertEquals(0, kept.get(0).getIndex());
        assertEquals(1, kept.get(1).getIndex());
    }

    @Test
    void nullContent_dropped() {
        List<VectorChunk> kept = filter.filter(List.of(
                chunk("PARAGRAPH", null),
                chunk("PARAGRAPH", "   \n  ")));
        assertEquals(0, kept.size());
    }

    @Test
    void emptyInput_noop() {
        assertEquals(0, filter.filter(List.of()).size());
        assertEquals(0, filter.filter(null).size());
    }
}
