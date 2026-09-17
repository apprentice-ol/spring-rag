package com.jjx.customer.platform.agent.framework.workflow;

import com.jjx.customer.platform.agent.framework.node.NodeKind;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 阶段声明：(何时跑, 用什么 prompt, 能用哪些扩展工具, 产出守什么约束)。
 *
 * @param name             阶段名（trace action 与策略定位用）
 * @param nodeKind         节点形态（LOOP / DETERMINISTIC / AGENT_CALL / 自定义）
 * @param systemPromptKey  阶段 prompt 的 key（workflow 层命名空间）
 * @param extensionTools   扩展工具白名单（只允许 ExtensionTool；BaseTool 由引擎注入，写了即装配错误）
 * @param outputGuardSchema 产出护栏的 JSON Schema（null = 不校验）
 * @param maxSteps         LOOP 形态的最大步数
 * @param when             条件跳过（输入为槽位视图；null = 总执行）
 * @param workfolwErrorPolicy      失败处置（null = AS_IS）
 * @param targetAgentId    AGENT_CALL 形态的目标子 Agent id（其余形态为 null）
 * @param budgetShare      AGENT_CALL 的预算切分上限（LLM 次数；0 = 不切分，子受自身 Workflow 限额约束）
 * @param inputMapping     AGENT_CALL 输入映射（父上下文键 → 子属性键；特殊父键 "input" = 用户原文。
 *                         null/空 = 默认：原文 input + prefill + 槽位全传）
 * @param outputMapping    AGENT_CALL 输出映射（子产出键 → 父槽位键；当前支持子产出键 "text" = 子结论文本。
 *                         null/空 = 默认：仅文本与证据上卷，不回写父槽位）
 */
public record WorkflowStageSpec(String name,
                                NodeKind nodeKind,
                                String systemPromptKey,
                                List<String> extensionTools,
                                String outputGuardSchema,
                                int maxSteps,
                                Predicate<Map<String, Object>> when,
                                WorkflowErrorPolicy workfolwErrorPolicy,
                                String targetAgentId,
                                int budgetShare,
                                Map<String, String> inputMapping,
                                Map<String, String> outputMapping) {

    public WorkflowStageSpec {
        extensionTools = extensionTools == null ? List.of() : List.copyOf(extensionTools);
        workfolwErrorPolicy = workfolwErrorPolicy == null ? WorkflowErrorPolicy.AS_IS : workfolwErrorPolicy;
        inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        outputMapping = outputMapping == null ? Map.of() : Map.copyOf(outputMapping);
    }

    /** 兼容形状（无 when / 默认策略 / 非 AGENT_CALL、无映射与切分）。 */
    public WorkflowStageSpec(String name, NodeKind nodeKind, String systemPromptKey,
                             List<String> extensionTools, String outputGuardSchema,
                             int maxSteps, Predicate<Map<String, Object>> when,
                             WorkflowErrorPolicy workfolwErrorPolicy, String targetAgentId) {
        this(name, nodeKind, systemPromptKey, extensionTools, outputGuardSchema,
                maxSteps, when, workfolwErrorPolicy, targetAgentId, 0, null, null);
    }

    /** 常用形状（无 when / 默认策略 / 非 AGENT_CALL）。 */
    public WorkflowStageSpec(String name, NodeKind nodeKind, String systemPromptKey,
                             List<String> extensionTools, String outputGuardSchema) {
        this(name, nodeKind, systemPromptKey, extensionTools, outputGuardSchema, 4, null, null, null);
    }

    /** AGENT_CALL 形状。 */
    public static WorkflowStageSpec agentCall(String name, String targetAgentId, Predicate<Map<String, Object>> when) {
        return new WorkflowStageSpec(name, NodeKind.AGENT_CALL, null, List.of(), null, 1, when, null, targetAgentId);
    }
}
