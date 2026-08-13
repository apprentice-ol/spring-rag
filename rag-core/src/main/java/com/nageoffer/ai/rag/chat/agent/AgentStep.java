package com.nageoffer.ai.rag.chat.agent;

/**
 * Agent 执行轨迹的单步记录。
 * <p>供前端对照面板渲染 trace 时间线树（Live/Eval 复用），也用于落库 agent_trace。
 *
 * @param stepIndex     步序号（从 0 递增）
 * @param action        动作类型：retrieve / grade / rewrite / decompose / web_search / rerank / plan / reflect / route / think / finish
 * @param thought       决策理由（如 CRAG"全部相关"、ReAct 模型 thought、Plan-Exec 计划摘要）
 * @param inputSummary  入参摘要（如 query 或 chunkRefs），限长
 * @param outputSummary 输出摘要（如 "命中 8 条" / "relevant=3 irrelevant=1"）
 * @param latencyMs     该步耗时
 * @param detail        结构化产物（可选，null 则前端不展开）。ReAct 三步用它暴露命中的 chunks /
 *                      逐条评分理由 / 重排顺序变化，见 {@link AgentStepDetails}；其他范式留 null。
 */
public record AgentStep(
        int stepIndex,
        String action,
        String thought,
        String inputSummary,
        String outputSummary,
        long latencyMs,
        Object detail
) {
}
