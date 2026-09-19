package com.jjx.customer.platform.knowledge.postprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelType;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 父块聚合后处理器单测：同父折叠（max 分语义 + 父块原文替换）/ 存量块直通 /
 * 父块原文缺失回退子块内容 / 分数降序合流。
 */
class ParentAggregationPostProcessorTest {

    private static RetrievedChunk child(String parentKey, String content, double score) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("doc_id", "doc-1");
        if (parentKey != null) {
            meta.put("parent_key", parentKey);
        }
        RetrievedChunk c = new RetrievedChunk(content, score, meta, SearchChannelType.VECTOR);
        c.setOriginalScore(score - 0.1);
        return c;
    }

    private static ParentAggregationPostProcessor processor(JdbcTemplate jdbc) {
        ParentAggregationPostProcessor p = new ParentAggregationPostProcessor(jdbc);
        ReflectionTestUtils.setField(p, "enabled", true);
        ReflectionTestUtils.setField(p, "maxChildrenCited", 3);
        return p;
    }

    /** JdbcTemplate mock：parent_key → content 映射经 RowCallbackHandler 回放。 */
    private static JdbcTemplate jdbcReturning(Map<String, String> parents) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (Map.Entry<String, String> e : parents.entrySet()) {
                ResultSet row = mock(ResultSet.class);
                when(row.next()).thenReturn(true, false); // 聚合器的 handler 自驱动 while(rs.next())
                when(row.getString("parent_key")).thenReturn(e.getKey());
                when(row.getString("content")).thenReturn(e.getValue());
                handler.processRow(row);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        return jdbc;
    }

    @Test
    void 同父子块折叠成单席位_取父块原文与最高分() {
        JdbcTemplate jdbc = jdbcReturning(Map.of("doc-1:0", "父块原文全文"));
        ParentAggregationPostProcessor p = processor(jdbc);

        List<RetrievedChunk> out = p.process(
                List.of(child("doc-1:0", "子块A", 0.42), child("doc-1:0", "子块B", 0.51)),
                List.of(), SearchContext.builder().query("q").build());

        assertEquals(1, out.size(), "两块同父应折叠为一条");
        assertEquals("父块原文全文", out.get(0).getContent());
        assertEquals(0.51, out.get(0).getScore(), "父分 = max(子块分)");
        assertEquals(2, out.get(0).getMetadata().get("child_hits"));
        assertEquals(Boolean.TRUE, out.get(0).getMetadata().get("parent"));
    }

    @Test
    void 存量块无parentKey直通_零行为变化() {
        ParentAggregationPostProcessor p = processor(mock(JdbcTemplate.class));
        RetrievedChunk legacy = child(null, "旧块", 0.3);
        RetrievedChunk legacy2 = child(null, "旧块2", 0.4);

        List<RetrievedChunk> out = p.process(List.of(legacy, legacy2), List.of(),
                SearchContext.builder().query("q").build());

        assertEquals(2, out.size());
        assertSame(legacy, out.get(0), "无子块时早退直通：原对象、原顺序，零行为变化");
        assertSame(legacy2, out.get(1));
    }

    @Test
    void 单条命中也要换成父块内容_不可早退() {
        // 回归：早先 `size() <= 1` 早退，单条命中时 LLM 只拿到 400 字子块片段而非父块上下文
        JdbcTemplate jdbc = jdbcReturning(Map.of("doc-1:0", "父块上下文全文"));
        ParentAggregationPostProcessor p = processor(jdbc);

        List<RetrievedChunk> out = p.process(List.of(child("doc-1:0", "单独命中的子块", 0.42)),
                List.of(), SearchContext.builder().query("q").build());

        assertEquals(1, out.size());
        assertEquals("父块上下文全文", out.get(0).getContent(), "单条命中必须做父块内容替换");
        assertEquals(Boolean.TRUE, out.get(0).getMetadata().get("parent"));
    }

    @Test
    void 父块原文缺失时回退子块内容_绝不丢命中() {
        JdbcTemplate jdbc = jdbcReturning(Map.of());
        ParentAggregationPostProcessor p = processor(jdbc);

        List<RetrievedChunk> out = p.process(List.of(child("doc-9:0", "唯一子块", 0.4)), List.of(),
                SearchContext.builder().query("q").build());

        assertEquals(1, out.size());
        assertEquals("唯一子块", out.get(0).getContent(), "父块查不到时回退子块内容");
    }

    @Test
    void 父块与直通块按分数降序合流() {
        JdbcTemplate jdbc = jdbcReturning(Map.of("doc-1:0", "P1"));
        ParentAggregationPostProcessor p = processor(jdbc);

        List<RetrievedChunk> out = p.process(
                List.of(child("doc-1:0", "c1", 0.45), child(null, "legacy", 0.30), child("doc-1:0", "c2", 0.60)),
                List.of(), SearchContext.builder().query("q").build());

        assertEquals(2, out.size());
        assertEquals(0.60, out.get(0).getScore(), "父块(0.6) 应排在直通块(0.3) 前");
        assertTrue(out.get(0).getContent().equals("P1"));
    }
}
