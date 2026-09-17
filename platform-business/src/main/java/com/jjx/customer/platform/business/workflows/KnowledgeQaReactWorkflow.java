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
 * 工具循环版知识问答流程（react_loop 轴）：模型在检索工具白名单内自主决定查什么、何时停。
 *
 * <p>与 {@link KnowledgeQaWorkflow} 的差异只在"节点形态"：这里是 LOOP（多步决策），
 * 那里是 DETERMINISTIC（单次检索）——范式差异 = 换流程，不是换形态。</p>
 */
@Component
public class KnowledgeQaReactWorkflow implements Workflow {

    public static final String ID = "knowledge_qa_react";
    public static final String RETRIEVE_STAGE = "tool_loop";
    private static final String STAGE_PROMPT_KEY = "agent/react-loop";

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
        return List.of(new WorkflowStageSpec(RETRIEVE_STAGE, NodeKind.LOOP, STAGE_PROMPT_KEY,
                List.of(RetrievalExtensionTool.NAME), null, 4, null, null, null));
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
        return KnowledgeQaWorkflow.ANSWER_PROMPT_KEY;
    }

    @Override
    public List<String> promptKeys() {
        // 阶段 prompt 必须声明（进三层快照，否则 LOOP 节点取不到即装配错误）
        return List.of(STAGE_PROMPT_KEY);
    }
}
