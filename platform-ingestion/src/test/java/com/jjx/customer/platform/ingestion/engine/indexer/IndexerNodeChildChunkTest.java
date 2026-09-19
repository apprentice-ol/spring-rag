package com.jjx.customer.platform.ingestion.engine.indexer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.config.properties.IngestionProperties;
import com.jjx.customer.platform.ingestion.engine.IngestionContext;
import com.jjx.customer.platform.ingestion.engine.NodeConfig;
import com.jjx.customer.platform.ingestion.engine.chunk.VectorChunk;
import com.jjx.customer.platform.ingestion.engine.chunk.strategy.BoundaryAwareSplitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * IndexerNode 小块检索展开单测：子块进向量表（带 parent_key）、父块存 sa_chunk_parent、
 * 小父块不展开、开关关闭回到旧行为。
 */
class IndexerNodeChildChunkTest {

    /** 固定 3 片切分器（避开真实边界算法，测试只关心展开行为）。 */
    private static BoundaryAwareSplitter splitterOf3() {
        return (text, min, target, max, overlap) -> {
            if (text.length() <= max) {
                return List.of(text);
            }
            int third = text.length() / 3 + 1;
            return List.of(
                    text.substring(0, third),
                    text.substring(third, Math.min(2 * third, text.length())),
                    text.substring(Math.min(2 * third, text.length())));
        };
    }

    private static EmbeddingModel embeddingModel() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new float[]{0.1f});
            }
            return vectors;
        });
        when(model.embed(any(String.class))).thenReturn(new float[]{0.1f});
        return model;
    }

    private static IndexerNode node(JdbcTemplate jdbc, boolean childEnabled) {
        IngestionProperties props = new IngestionProperties();
        props.getChildChunk().setEnabled(childEnabled);
        return new IndexerNode(new ObjectMapper(), jdbc, embeddingModel(), splitterOf3(), props);
    }

    private static IngestionContext context(List<VectorChunk> chunks) {
        return IngestionContext.builder()
                .taskId("task-1")
                .docId("doc-1")
                .chunks(chunks)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static NodeConfig config() {
        NodeConfig c = mock(NodeConfig.class);
        when(c.getSettings()).thenReturn(null);
        return c;
    }

    @Test
    void 大父块展开为子块_父块存档_子块带parentKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        String longText = "a".repeat(1200);
        VectorChunk parent = VectorChunk.builder().index(0).content(longText).build();

        node(jdbc, true).execute(context(List.of(parent)), config());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .update(sql.capture(), params.capture());
        List<String> allSql = sql.getAllValues();
        assertTrue(allSql.stream().anyMatch(s -> s.startsWith("INSERT INTO sa_chunk_parent")),
                "父块应写入 sa_chunk_parent；实际=" + allSql);
        assertTrue(allSql.stream().anyMatch(s -> s.startsWith("INSERT INTO spring_ai_store_vector")),
                "子块应写入向量表");
        // 子块 metadata 必须带 parent_key（聚合器的反查键）。
        // 向量 INSERT 是 multi-row（每行 4 参：uuid/content/metaJson/vector），故扫描所有 String 参数而非按固定长度取。
        String allParams = params.getAllValues().stream()
                .flatMap(p -> java.util.Arrays.stream(p))
                .filter(v -> v instanceof String)
                .map(String::valueOf)
                .reduce("", (a, b) -> a + "|" + b);
        assertTrue(allParams.contains("\"parent_key\":\"doc-1:0\""),
                "子块 metadata 缺 parent_key；实际参数=" + allParams);
    }

    @Test
    void 小父块不展开_无父块存档() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        VectorChunk small = VectorChunk.builder().index(0).content("短内容").build();

        node(jdbc, true).execute(context(List.of(small)), config());

        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
                .update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO sa_chunk_parent"),
                        any(Object[].class));
    }

    @Test
    void 子块开关关闭_父块原样进向量表() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        VectorChunk parent = VectorChunk.builder().index(0).content("a".repeat(1200)).build();

        node(jdbc, false).execute(context(List.of(parent)), config());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().stream().noneMatch(s -> s.startsWith("INSERT INTO sa_chunk_parent")),
                "关闭子块时不应写父块表");
    }
}
