package com.jjx.customer.platform.business.workflows;
import com.jjx.customer.platform.knowledge.tools.RetrievalExtensionTool;

import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.node.NodeKind;
import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 知识问答流程（RAG = agent + workflow 中的流程侧）。
 *
 * <p>骨架：确定性检索节点（零 LLM 决策，等价单次检索延迟）→ 产出上下文与引用，
 * 由管线做流式生成（{@code STREAMING} 能力位）。"检索"是扩展工具，不是范式。</p>
 */
@Component
public class KnowledgeQaWorkflow implements Workflow {

    public static final String ID = "knowledge_qa";
    public static final String RETRIEVE_STAGE = "retrieve";
    public static final String ANSWER_PROMPT_KEY = "chat/pipeline/rag-answer-kb";
    public static final String BASE_SYSTEM_PROMPT_KEY = "chat/pipeline/rag-answer-system";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<SlotSpec> slots() {
        return List.of();
    }

    @Override
    public List<WorkflowStageSpec> stages() {
        return List.of(new WorkflowStageSpec(RETRIEVE_STAGE, NodeKind.DETERMINISTIC,
                "workflow/knowledge_qa/retrieve", List.of(RetrievalExtensionTool.NAME), null));
    }

    @Override
    public Set<AgentCapability> suppliedCapabilities() {
        // 五档供给：执行指纹（缓存两档的依赖元数据）随每次执行产出
        return Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS,
                AgentCapability.ANSWER_CACHE, AgentCapability.SEMANTIC_CACHE,
                AgentCapability.RETRIEVAL_METRICS);
    }

    @Override
    public String answerPromptKey() {
        return ANSWER_PROMPT_KEY;
    }

    @Override
    /**
     * 流程层 prompt：只声明"属于本流程"的部分。
     *
     * <p>{@link #BASE_SYSTEM_PROMPT_KEY} 属链路级常驻段（框架层 link layer 装配），
     * 不在此重复声明——跨层同 key 会被三层增强的装配校验直接拒绝。</p>
     */
    public List<String> promptKeys() {
        return List.of();
    }
}
