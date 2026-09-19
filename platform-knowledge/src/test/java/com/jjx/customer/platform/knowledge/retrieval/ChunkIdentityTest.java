package com.jjx.customer.platform.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 分片去重键单测：键必须区分"同内容不同文档"，否则期望文档会被孪生副本合并掉。 */
class ChunkIdentityTest {

    /** LiveRAG 语料形态：一篇文章同时含多幅名画条目，被多道题引用后导入成多个 doc_id、内容逐字节相同。 */
    private static final String SAME_TEXT =
            "2. The Scream\nEdvard Munch's 'The Scream'...\n3. The Last Supper\nDa Vinci's 'The Last Supper'...";

    private static final Map<String, Object> DOC_A = Map.of("doc_id", "872eb6b0-8a68-439b-8e04-2b3934ce06fd");
    private static final Map<String, Object> DOC_B = Map.of("doc_id", "5ef1e05f-39a2-4e92-9c1f-2b0e4d7a8c33");

    @Test
    void sameContentDifferentDocsMustDiffer() {
        // 纯内容哈希会把两者合并，留哪份取决于通道顺序 —— 评测按 doc_id 打分，合并掉期望那份即 0 分。
        assertNotEquals(ChunkIdentity.of(DOC_A, SAME_TEXT), ChunkIdentity.of(DOC_B, SAME_TEXT));
    }

    @Test
    void sameDocSameContentMustMatch() {
        // 多通道（向量 / 关键词）命中同一分片时要能合并
        assertEquals(ChunkIdentity.of(DOC_A, SAME_TEXT), ChunkIdentity.of(DOC_A, SAME_TEXT));
    }

    @Test
    void blankDocIdFallsBackToContentHash() {
        // doc_id 缺失/空白 → 退化为纯内容哈希（与修复前行为一致，保守兜底），且不抛异常
        Map<String, Object> noDocId = new HashMap<>();
        assertEquals(ChunkIdentity.of(noDocId, SAME_TEXT), ChunkIdentity.of(noDocId, SAME_TEXT));
        assertEquals(ChunkIdentity.of(null, SAME_TEXT), ChunkIdentity.of(noDocId, SAME_TEXT));
        assertEquals(ChunkIdentity.of(Map.of("doc_id", "  "), SAME_TEXT), ChunkIdentity.of(noDocId, SAME_TEXT));
    }

    @Test
    void nullContentIsSafe() {
        assertEquals(ChunkIdentity.of(DOC_A, null), ChunkIdentity.of(DOC_A, ""));
        assertEquals(ChunkIdentity.of((RetrievedChunk) null), ChunkIdentity.of(null, null));
    }
}
