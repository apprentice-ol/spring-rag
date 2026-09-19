package com.jjx.customer.platform.knowledge.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.jjx.customer.platform.knowledge.retrieval.MultiChannelRetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelResult;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 空召回观测文本的归因口径：通道有候选却被精排下限清零时，文本必须说清
 * 「召回情况」——「38 条候选 0 条达标」与「语料里没有」是两件事，回喂模型与
 * 轨迹读者都需要这个区分（实测案例：rerank 榜首就是期望文档，整批被 0.3 下限砍光）。
 */
class RetrievalToolTest {

    /** 恒返回指定通道结果的假引擎（不触库）。 */
    private static RetrievalEngine engineWith(List<SearchChannelResult> channelResults,
                                               List<com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk> finalChunks) {
        return context -> new MultiChannelRetrievalEngine.RetrievalResult(channelResults, finalChunks, 1);
    }

    private static SearchChannelResult channel(String name, int hits) {
        return SearchChannelResult.builder()
                .channelType(hits > 0 && "keyword".equals(name)
                        ? com.jjx.customer.platform.knowledge.retrieval.SearchChannelType.KEYWORD
                        : com.jjx.customer.platform.knowledge.retrieval.SearchChannelType.VECTOR)
                .channelName(name)
                .chunks(java.util.Collections.nCopies(hits, null))
                .latencyMs(1)
                .build();
    }

    @Test
    void 空召回且有通道候选时_观测文本须归因到精排下限() {
        RetrievalTool tool = new RetrievalTool(
                engineWith(List.of(channel("vector", 20), channel("keyword", 18)), List.of()),
                10, 0.0, 20, 40, 10, 0.3);

        ToolResult result = tool.invoke(
                ToolInput.of(Map.of("query", "fire triangle")), ToolContext.of("s1", "n1"));

        assertTrue(result.success());
        assertTrue(result.output().contains("向量 20 条"), "实际=" + result.output());
        assertTrue(result.output().contains("关键词 18 条"), "实际=" + result.output());
        assertTrue(result.output().contains("共 38 条候选"), "实际=" + result.output());
        assertTrue(result.output().contains("0 条达标"), "实际=" + result.output());
        assertTrue(result.output().contains("下限 0.3"), "实际=" + result.output());
    }

    @Test
    void 通道本身就无召回时_不提精排下限() {
        RetrievalTool tool = new RetrievalTool(
                engineWith(List.of(channel("vector", 0), channel("keyword", 0)), List.of()),
                10, 0.0, 20, 40, 10, 0.3);

        ToolResult result = tool.invoke(
                ToolInput.of(Map.of("query", "fire triangle")), ToolContext.of("s1", "n1"));

        assertTrue(result.output().contains("均无召回"), "实际=" + result.output());
        assertTrue(!result.output().contains("达标"), "通道零召回时不应归因到精排。实际=" + result.output());
    }
}
