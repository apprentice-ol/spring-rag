package com.jjx.customer.platform.knowledge.tools;

import com.jjx.customer.platform.agent.framework.result.ContextArtifact;
import com.jjx.customer.platform.agent.framework.tool.ExtensionTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.knowledge.retrieval.MultiChannelRetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalBudget;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import com.jjx.customer.platform.common.util.TextPreviews;
import com.jjx.customer.platform.config.properties.ChatProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识检索扩展工具（框架 {@link ExtensionTool}，数据面能力）。
 *
 * <p>框架主线里"检索"是工具而不是范式：命中片段转成 {@link ContextArtifact}（带 ref 编号），
 * 由引擎负责上下文/引用索引的装配与元数据流出。</p>
 */
@Component
@RequiredArgsConstructor
public class RetrievalExtensionTool implements ExtensionTool {

    public static final String NAME = "retrieve_knowledge";
    /** 调用方（编排层）在请求属性里传检索上下文 SearchContext 的键（本能力域定义，业务侧引用）。 */
    public static final String ATTR_SEARCH_CONTEXT = "searchContext";

    private final RetrievalEngine retrievalEngine;
    private final ChatProperties chatProperties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在知识库中检索操作手册、接口文档、报文样例等资料。参数 query 为检索查询词，"
                + "可选 topN 指定返回条数；命中结果带 [ref=N] 编号供后续引用。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"检索查询词"},
                  "topN":{"type":"integer","description":"返回条数上限，缺省用配置默认值"}
                },"required":["query"]}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String query = invocation.str("query", invocation.str("input", ""));
        if (query == null || query.isBlank()) {
            return ToolResult.error("检索词为空，无法执行知识检索");
        }
        SearchContext context = baseContext(invocation, query, invocation.intOr("topN", chatProperties.getTopK()));

        MultiChannelRetrievalEngine.RetrievalResult result = retrievalEngine.retrieve(context);
        StringBuilder content = new StringBuilder("命中 " + result.getFinalChunks().size() + " 条：\n");
        List<ContextArtifact> artifacts = new ArrayList<>();
        int ref = 1;
        for (RetrievedChunk chunk : result.getFinalChunks()) {
            artifacts.add(new ContextArtifact(ref, chunk.getContent(), docName(chunk), chunk.getScore(),
                    chunk.getChannelType() == null ? null : chunk.getChannelType().name(),
                    chunk.getMetadata()));
            content.append("[ref=").append(ref).append("] ")
                    .append(TextPreviews.preview(chunk.getContent(), 160)).append('\n');
            ref++;
        }
        return ToolResult.ok(content.toString(), artifacts);
    }

    /**
     * 检索上下文：优先沿用调用方（管线）算好的 {@link SearchContext}（集合/受限文档/topK 等口径一致），
     * 缺省则按配置构造。
     */
    private SearchContext baseContext(ToolInvocation invocation, String query, int topN) {
        SearchContext origin = null;
        if (invocation.context() != null) {
            Object attr = invocation.context().plan().request()
                    .attributes().get(ATTR_SEARCH_CONTEXT);
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
            builder.threshold(chatProperties.getSimilarityThreshold())
                    .budget(RetrievalBudget.builder()
                            .recallBudget(chatProperties.getRecallBudget())
                            .candidateLimit(chatProperties.getCandidateLimit())
                            .contextTopK(chatProperties.getContextTopK())
                            .build());
        }
        return builder.query(query)
                .rewrittenQuery(query)
                .topK(topN)
                .metadata(Map.of("intent", "knowledge"))
                .build();
    }

    private static String docName(RetrievedChunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        Object name = metadata == null ? null : metadata.get("doc_name");
        return name == null ? "?" : String.valueOf(name);
    }
}
