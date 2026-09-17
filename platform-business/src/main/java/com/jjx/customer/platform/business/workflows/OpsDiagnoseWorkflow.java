package com.jjx.customer.platform.business.workflows;
import com.jjx.customer.platform.knowledge.tools.RetrievalExtensionTool;

import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.node.NodeKind;
import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.observe.tools.OpsQueryLogsTool;
import com.jjx.customer.platform.business.tools.ops.OpsSlotSpecs;
import com.jjx.customer.platform.business.tools.ops.OpsValidateRequestTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 运维诊断流程（R2 重建）：查日志定位 → 检索文档/生成或纠正报文 → 确定性校验收尾。
 *
 * <p>骨架全部由框架引擎提供（槽位一次问齐、阶段迭代、预算、错误策略、护栏、trace、
 * 追问/升级处置）；本类只声明"做什么"。</p>
 */
@Component
public class OpsDiagnoseWorkflow implements Workflow {

    public static final String ID = "ops_diagnose_v2";
    public static final String INVESTIGATE = "investigate_logs";
    public static final String RESOLVE = "resolve_request";
    public static final String VERIFY = "verify";

    private static final String INVESTIGATE_PROMPT = "agent/ops/investigate";
    private static final String RESOLVE_PROMPT = "agent/ops/resolve";
    private static final String VERIFY_PROMPT = "agent/ops/verify";
    /** 抽槽与 replan 已迁 workflow 命名空间（四层分域）；阶段 prompt 迁移随后续批次。 */
    public static final String SLOT_EXTRACT_PROMPT = "workflow/ops_diagnose_v2/slot-extract";
    public static final String REPLAN_PROMPT = "workflow/ops_diagnose_v2/replan";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<SlotSpec> slots() {
        return OpsSlotSpecs.ALL;
    }

    @Override
    public List<WorkflowStageSpec> stages() {
        return List.of(
                new WorkflowStageSpec(INVESTIGATE, NodeKind.LOOP, INVESTIGATE_PROMPT,
                        List.of(OpsQueryLogsTool.NAME, RetrievalExtensionTool.NAME), null, 4, null, null, null),
                new WorkflowStageSpec(RESOLVE, NodeKind.LOOP, RESOLVE_PROMPT,
                        List.of(RetrievalExtensionTool.NAME, OpsValidateRequestTool.NAME),
                        null, 5, null, null, null),
                new WorkflowStageSpec(VERIFY, NodeKind.LOOP, VERIFY_PROMPT,
                        List.of(OpsValidateRequestTool.NAME), null, 3, null, null, null));
    }

    @Override
    public Set<AgentCapability> suppliedCapabilities() {
        return Set.of();
    }

    @Override
    public List<String> promptKeys() {
        return List.of(INVESTIGATE_PROMPT, RESOLVE_PROMPT, VERIFY_PROMPT,
                SLOT_EXTRACT_PROMPT, REPLAN_PROMPT);
    }

    /** 抽槽（O1/O2 闭环：补充轮从用户消息抽取空缺槽位，已确认值优先）。 */
    @Override
    public String slotExtractPromptKey() {
        return SLOT_EXTRACT_PROMPT;
    }

    /** 阶段间 replan 检查点（O6：continue / adjust 重跑 / escalate 追问）。 */
    @Override
    public String replanPromptKey() {
        return REPLAN_PROMPT;
    }
}
