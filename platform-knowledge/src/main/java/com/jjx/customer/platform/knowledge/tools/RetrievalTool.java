package com.jjx.customer.platform.knowledge.tools;

import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.jjx.customer.platform.knowledge.retrieval.MultiChannelRetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalBudget;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelResult;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识检索工具（新内核 {@link Tool} 契约，替代旧 {@code RetrievalExtensionTool}）。
 *
 * <p>"检索"是工具而不是范式：命中片段带 {@code [ref=N]} 编号进观测文本（回喂模型），
 * 结构化明细（content/score/channel/docName/metadata）经 {@code ToolResult.data} 流出——
 * 工具节点自动写 {@code {outputSlot}_data} 派生槽（knowledge 线出口），react 线由
 * Act 执行器累积消费。</p>
 *
 * <p>检索口径：优先沿用调用方（编排层）经 {@code Input.slots} 预置的
 * {@link SearchContext}（键 {@link #SEARCH_CONTEXT_SLOT}，集合/受限文档/预算一致），
 * 缺省按配置构造。</p>
 */
public class RetrievalTool implements Tool {

    /** 工具 id（阶段白名单与 ToolNodeDefinition.toolRef 引用）。 */
    public static final String TOOL_ID = "retrieve_knowledge";

    /** 调用方在引擎槽位里预置 SearchContext 的键（编排层经 Input.slots 传入）。 */
    public static final String SEARCH_CONTEXT_SLOT = "search_context";

    private static final Logger log = LoggerFactory.getLogger(RetrievalTool.class);

    private final RetrievalEngine retrievalEngine;
    private final int defaultTopK;
    private final double similarityThreshold;
    private final int recallBudget;
    private final int candidateLimit;
    private final int contextTopK;
    /** Rerank 相关度下限（仅用于观测文本说明，过滤行为在 BaiLianRerankClient，不在本工具） */
    private final double minRelevanceScore;

    /**
     * 全参构造（配置由装配方从 {@code ChatProperties} 解出注入）。
     */
    public RetrievalTool(RetrievalEngine retrievalEngine, int defaultTopK, double similarityThreshold,
                         int recallBudget, int candidateLimit, int contextTopK) {
        this(retrievalEngine, defaultTopK, similarityThreshold, recallBudget, candidateLimit,
                contextTopK, 0.0);
    }

    /**
     * 全参构造（含 rerank 相关度下限，用于空召回时的观测文本归因）。
     */
    public RetrievalTool(RetrievalEngine retrievalEngine, int defaultTopK, double similarityThreshold,
                         int recallBudget, int candidateLimit, int contextTopK, double minRelevanceScore) {
        this.retrievalEngine = retrievalEngine;
        this.defaultTopK = defaultTopK;
        this.similarityThreshold = similarityThreshold;
        this.recallBudget = recallBudget;
        this.candidateLimit = candidateLimit;
        this.contextTopK = contextTopK;
        this.minRelevanceScore = minRelevanceScore;
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(TOOL_ID, "在知识库中检索操作手册、接口文档、报文样例等资料，"
                        + "命中结果带 [ref=N] 编号供后续引用",
                ToolParameter.required("query", "string"),
                ToolParameter.of("topN", "number", defaultTopK));
    }

    @Override
    public ToolResult invoke(ToolInput input, ToolContext context) {
        String query = input.string("query", "");
        if (query == null || query.isBlank()) {
            return ToolResult.failed("检索词为空，无法执行知识检索");
        }
        int topN = input.integer("topN", defaultTopK);
        SearchContext searchContext = baseContext(context, query, topN);

        MultiChannelRetrievalEngine.RetrievalResult result = retrievalEngine.retrieve(searchContext);
        List<RetrievedChunk> chunks = result.getFinalChunks();
        if (chunks.isEmpty()) {
            // 空召回的观测文本必须带「召回情况」：通道明明召回了候选却 0 条达标，
            // 与「语料里根本没有相关内容」是两件事——回喂模型与轨迹读者都需要这个区分
            //（实测案例：rerank 把期望文档排第 1，但整批分数被 0.3 下限清零）。
            return ToolResult.ok(emptyObservation(result), Map.of(
                    "chunks", List.of(), "retrieval", retrievalSummary(result)));
        }
        StringBuilder content = new StringBuilder("命中 " + chunks.size() + " 条：\n");
        List<Map<String, Object>> chunkDocs = new ArrayList<>(chunks.size());
        int ref = 1;
        for (RetrievedChunk chunk : chunks) {
            Map<String, Object> metadata = chunk.getMetadata() == null
                    ? Map.of() : chunk.getMetadata();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("ref", ref);
            doc.put("content", chunk.getContent());
            // 融合分（RRF/精排后）与通道原始分是两件事：前者决定排序，后者说明"这一条本身有多像"。
            // 轨迹的命中表要并排展示它们（对齐 agent-framework 的命中表口径），只给融合分看不出
            // 一条结果是"两路都命中"还是"单路硬捞上来的"。
            doc.put("score", chunk.getScore());
            doc.put("originalScore", chunk.getOriginalScore());
            doc.put("channel", chunk.getChannelType() == null ? null : chunk.getChannelType().name());
            doc.put("docName", metadata.get("doc_name"));
            doc.put("heading", headingOf(metadata));
            doc.put("metadata", metadata);
            chunkDocs.add(doc);
            content.append("[ref=").append(ref).append("] ").append(preview(chunk.getContent())).append('\n');
            ref++;
        }
        // query 随结构化数据下发：轨迹「in」行与命中表的数据源（观测文本只做回喂模型用）
        return ToolResult.ok(content.toString(), Map.of(
                "query", query, "chunks", chunkDocs, "retrieval", retrievalSummary(result)));
    }

    /**
     * 空召回的观测文本：按「通道有候选但被精排过滤」与「通道就无召回」两种情况分别归因。
     *
     * @param result 检索结果（含各通道明细）
     * @return 带召回情况的观测文本（回喂模型 + 轨迹 out 行）
     */
    private String emptyObservation(MultiChannelRetrievalEngine.RetrievalResult result) {
        String channels = channelLine(result);
        int candidates = totalChannelHits(result);
        if (candidates > 0) {
            return "未检索到相关资料（各通道召回 " + channels + "，共 " + candidates
                    + " 条候选，融合精排后 0 条达标"
                    + (minRelevanceScore > 0 ? "——相关度低于下限 " + minRelevanceScore : "") + "）。";
        }
        return "未检索到相关资料（各通道均无召回：" + channels + "）。";
    }

    /** 通道召回明细（如「向量 20 条 / 关键词 0 条」）。 */
    private static String channelLine(MultiChannelRetrievalEngine.RetrievalResult result) {
        if (result.getChannelResults() == null || result.getChannelResults().isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (SearchChannelResult r : result.getChannelResults()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(labelOf(r.getChannelName()))
                    .append(' ')
                    .append(r.getChunks() == null ? 0 : r.getChunks().size())
                    .append(" 条");
        }
        return sb.toString();
    }

    /** 通道名 → 中文标签（观测文本给人读；未知通道名原样保留）。 */
    private static String labelOf(String channelName) {
        return switch (channelName == null ? "" : channelName) {
            case "vector" -> "向量";
            case "keyword" -> "关键词";
            case "web-search" -> "联网";
            default -> channelName == null ? "未知" : channelName;
        };
    }

    /** 通道命中合计（去重前口径——判「有没有候选」用）。 */
    private static int totalChannelHits(MultiChannelRetrievalEngine.RetrievalResult result) {
        if (result.getChannelResults() == null) {
            return 0;
        }
        return result.getChannelResults().stream()
                .mapToInt(r -> r.getChunks() == null ? 0 : r.getChunks().size())
                .sum();
    }

    /** 结构化召回摘要（进 data 通道 → {@code kb_chunks_data.retrieval}，轨迹/前端可读）。 */
    private static Map<String, Object> retrievalSummary(MultiChannelRetrievalEngine.RetrievalResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (result.getChannelResults() != null) {
            List<Map<String, Object>> channels = new ArrayList<>();
            for (SearchChannelResult r : result.getChannelResults()) {
                Map<String, Object> ch = new LinkedHashMap<>();
                ch.put("name", r.getChannelName());
                ch.put("hits", r.getChunks() == null ? 0 : r.getChunks().size());
                ch.put("latencyMs", r.getLatencyMs());
                channels.add(ch);
            }
            summary.put("channels", channels);
        }
        summary.put("finalHits", result.getFinalChunks() == null ? 0 : result.getFinalChunks().size());
        return summary;
    }

    /**
     * 检索上下文：优先沿用编排层算好的 {@link SearchContext}（集合/受限文档/topK 口径一致），
     * 缺省按配置构造。
     */
    private SearchContext baseContext(ToolContext context, String query, int topN) {
        SearchContext origin = null;
        if (context.slots() != null) {
            Object attr = context.slots().get(SEARCH_CONTEXT_SLOT);
            if (attr instanceof SearchContext ctx) {
                origin = ctx;
            }
        }
        SearchContext.SearchContextBuilder builder = SearchContext.builder();
        if (origin != null) {
            builder.collectionId(origin.getCollectionId())
                    .restrictedDocIds(origin.getRestrictedDocIds())
                    .threshold(origin.getThreshold())
                    .budget(origin.getBudget());
        } else {
            builder.threshold(similarityThreshold)
                    .budget(RetrievalBudget.builder()
                            .recallBudget(recallBudget)
                            .candidateLimit(candidateLimit)
                            .contextTopK(contextTopK)
                            .build());
        }
        return builder.query(query)
                .rewrittenQuery(query)
                .topK(topN)
                .metadata(Map.of("intent", "knowledge"))
                .build();
    }

    /**
     * 命中片段所属章节。
     *
     * <p>本仓的切片元数据里章节是 {@code outline_path}（如「文档 &gt; 冲红流程 &gt; 专票」），
     * agent-framework 那边叫 {@code heading}——轨道要并排看，字段名取两边都好认的那个，
     * 值仍来自本仓真实写入的键。</p>
     */
    private static String headingOf(Map<String, Object> metadata) {
        Object heading = metadata.get("heading");
        if (heading != null && !String.valueOf(heading).isBlank()) {
            return String.valueOf(heading);
        }
        Object outline = metadata.get("outline_path");
        return outline == null || String.valueOf(outline).isBlank() ? null : String.valueOf(outline);
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
}
