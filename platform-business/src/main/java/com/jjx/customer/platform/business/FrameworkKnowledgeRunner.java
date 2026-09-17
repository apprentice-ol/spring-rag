package com.jjx.customer.platform.business;

import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.agent.WorkflowEngine;
import com.jjx.customer.platform.agent.framework.result.ContextArtifact;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.business.agents.KnowledgeFrameworkAgent;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelType;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import com.jjx.customer.platform.knowledge.tools.RetrievalExtensionTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识问答框架主线执行器（业务 → 框架引擎的适配层）。
 *
 * <p>职责最小化：把管线已经算好的 {@link SearchContext} 作为属性交给框架，调用引擎执行
 * "知识问答 Agent 持有 Workflow（确定性检索节点）"，再把框架的
 * {@code ContextArtifact / TraceView} 无损映射回管线既有的 {@code RetrievedChunk / TraceView}，
 * 使流式生成、引用渲染、答案缓存、eval 全部沿用既有实现（行为零变化，执行权已换到框架）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrameworkKnowledgeRunner {

    /** 管线检索参数（SearchContext）在框架请求属性里的键。 */
    public static final String ATTR_SEARCH_CONTEXT = RetrievalExtensionTool.ATTR_SEARCH_CONTEXT;

    private final WorkflowEngine frameworkWorkflowEngine;

    /** 一次知识问答检索的执行产物。 */
    public record KnowledgeAnswer(List<RetrievedChunk> chunks, TraceView trace) {

        public boolean isEmpty() {
            return chunks.isEmpty();
        }
    }

    /** 执行知识问答主线（缺省 knowledge 轴）。 */
    public KnowledgeAnswer retrieve(String question, SearchContext searchContext) {
        return retrieve(question, searchContext, KnowledgeFrameworkAgent.INTENT_DOMAIN);
    }

    /** 按范式轴执行（knowledge / react_loop 都是框架流程，差异只在节点形态）。 */
    public KnowledgeAnswer retrieve(String question, SearchContext searchContext, String paradigm) {
        String domain = paradigm == null || paradigm.isBlank()
                ? KnowledgeFrameworkAgent.INTENT_DOMAIN : paradigm;
        AgentRequest request = new AgentRequest(question,
                searchContext == null
                        ? Map.of(AgentRequest.ATTR_INTENT_DOMAIN, domain)
                        : Map.of(ATTR_SEARCH_CONTEXT, searchContext,
                                AgentRequest.ATTR_INTENT_DOMAIN, domain));
        ExecutionResult result = frameworkWorkflowEngine.execute(request);

        List<RetrievedChunk> chunks = result.context().artifacts().stream()
                .map(FrameworkKnowledgeRunner::toChunk)
                .toList();
        return new KnowledgeAnswer(chunks, FrameworkTraceMapper.toBusinessTrace(result));
    }

    private static RetrievedChunk toChunk(ContextArtifact artifact) {
        Map<String, Object> metadata = new LinkedHashMap<>(artifact.metadata());
        if (artifact.source() != null) {
            metadata.putIfAbsent("doc_name", artifact.source());
        }
        return new RetrievedChunk(artifact.content(), artifact.score(), metadata, toChannel(artifact.channel()));
    }

    private static SearchChannelType toChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        try {
            return SearchChannelType.valueOf(channel);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
