package com.jjx.customer.platform.ingestion.engine.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jjx.customer.platform.ingestion.engine.parser.model.Block;
import com.jjx.customer.platform.ingestion.engine.parser.model.HeadingBlock;
import com.jjx.customer.platform.ingestion.engine.parser.model.ParagraphBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BlockSanitizer 解析噪声清洗测试（含防误杀用例——清洗规则宁漏勿错）。
 */
class BlockSanitizerTest {

    private final BlockSanitizer sanitizer = new BlockSanitizer();

    private static HeadingBlock heading(int level, String text) {
        return new HeadingBlock("h-" + text.hashCode(), null, List.of(), level, text);
    }

    private static ParagraphBlock para(String text) {
        return new ParagraphBlock("p-" + text.hashCode(), null, List.of(), text);
    }

    private static String headingText(Block b) {
        return ((HeadingBlock) b).text();
    }

    @Test
    void replacementChar_stripped() {
        // 实况样本：MinerU 字体解码失败的 "## � 相关"
        BlockSanitizer.SanitizeResult r = sanitizer.sanitize(List.of(heading(2, "� 相关")));
        assertEquals(1, r.blocks().size());
        assertEquals("相关", headingText(r.blocks().get(0)));
    }

    @Test
    void adjacentDuplicateHeadings_secondDropped() {
        // 实况样本：PDF 版式导致 "## 去重（order=1）" 连续两次
        BlockSanitizer.SanitizeResult r = sanitizer.sanitize(List.of(
                heading(2, "去重（order=1）"),
                heading(2, "去重（order=1）")));
        assertEquals(1, r.blocks().size());
        assertEquals(1, r.droppedBlocks());
    }

    @Test
    void sameNameHeadingWithContentBetween_bothKept() {
        // 防误杀：不同章节同名标题（中间有正文）必须都保留
        BlockSanitizer.SanitizeResult r = sanitizer.sanitize(List.of(
                heading(2, "小结"),
                para("本章总结了三种方案。"),
                heading(2, "小结")));
        assertEquals(3, r.blocks().size(), "隔内容的同名标题不得去重");
        assertEquals(0, r.droppedBlocks());
    }

    @Test
    void threeAdjacentDuplicates_onlyFirstKept() {
        BlockSanitizer.SanitizeResult r = sanitizer.sanitize(List.of(
                heading(1, "附录"),
                heading(1, "附录"),
                heading(1, "附录")));
        assertEquals(1, r.blocks().size());
        assertEquals(2, r.droppedBlocks());
    }

    @Test
    void navLines_strippedFromParagraph() {
        // 实况样本：PDF 导航尾巴（"返回 RAG核心专题" + 相对路径行）
        String text = "返回 RAG核心专题\n\n并行架构：../02-高并发/04-三层并行检索架构\n\n正文第一段，说明检索架构的设计。";
        BlockSanitizer.SanitizeResult r = sanitizer.sanitize(List.of(para(text)));
        assertEquals(1, r.blocks().size());
        String cleaned = ((ParagraphBlock) r.blocks().get(0)).text();
        assertTrue(!cleaned.contains("返回 RAG核心专题"), "导航行应被剔除");
        assertTrue(!cleaned.contains("../02-高并发"), "相对路径行应被剔除");
        assertTrue(cleaned.contains("正文第一段"), "正文必须保留");
        assertEquals(2, r.strippedLines());
    }

    @Test
    void paragraphBecomesEmptyAfterStrip_blockDropped() {
        BlockSanitizer.SanitizeResult r = sanitizer.sanitize(List.of(para("返回 RAG核心专题")));
        assertEquals(0, r.blocks().size());
        assertEquals(1, r.droppedBlocks());
    }

    @Test
    void sentenceWithRelativePath_kept() {
        // 防误杀：正文句子里提到相对路径，但整行有句末标点、且行内是完整句子 → 保留
        String text = "具体配置可参考仓库内 ../docs/guide.md 文件中的说明，按章节逐步执行即可。";
        BlockSanitizer.SanitizeResult r = sanitizer.sanitize(List.of(para(text)));
        assertEquals(1, r.blocks().size());
        assertTrue(((ParagraphBlock) r.blocks().get(0)).text().contains("../docs/guide.md"));
    }

    @Test
    void longLineWithPathLikeText_kept() {
        // 防误杀：长行（≥80 字符）不参与导航判定
        String text = "部署路径 ../opt/app/releases/current 与回滚路径 ../opt/app/releases/previous 的关系需要额外说明清楚后再操作";
        BlockSanitizer.SanitizeResult r = sanitizer.sanitize(List.of(para(text)));
        assertEquals(1, r.blocks().size());
    }

    @Test
    void listAndTableBlocks_passThrough() {
        var listBlock = new com.jjx.customer.platform.ingestion.engine.parser.model.ListBlock(
                "l1", null, List.of(), false, List.of("返回 RAG核心专题"));
        BlockSanitizer.SanitizeResult r = sanitizer.sanitize(List.of(listBlock));
        assertEquals(1, r.blocks().size());
        assertEquals(listBlock, r.blocks().get(0));
    }

    @Test
    void emptyListInput_noop() {
        assertEquals(0, sanitizer.sanitize(List.of()).blocks().size());
        assertEquals(0, sanitizer.sanitize(null).blocks().size());
    }
}
