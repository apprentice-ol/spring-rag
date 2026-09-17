package com.jjx.customer.platform.agent.framework.model;

import java.util.List;

/**
 * 一轮结构化对话历史（原生 function calling 的回喂载体）。
 *
 * <p>JSON 协议端口用 {@link ModelRequest#observations()} 的文本观测即可；
 * 原生端口需要 assistant 工具调用与工具结果的成对结构（tool_call_id 关联），
 * 由循环节点按序追加：ASSISTANT(toolCalls) 后紧跟 TOOL(results)，一一对应。</p>
 *
 * @param kind         轮次类型（ASSISTANT 文本/工具调用声明；TOOL 工具执行结果）
 * @param text         ASSISTANT 文本轮的文本（可空）
 * @param toolCalls    ASSISTANT 轮声明的工具调用（文本轮为空）
 * @param observations TOOL 轮的工具结果（与上一 ASSISTANT 轮的 toolCalls 一一对应）
 */
public record ModelTurn(Kind kind, String text, List<ToolCall> toolCalls,
                        List<ToolObservation> observations) {

    public enum Kind {
        ASSISTANT, TOOL
    }

    public ModelTurn {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    /** 模型给出的工具调用声明（引擎随后执行并追加 {@link #toolResults}）。 */
    public static ModelTurn assistantToolCalls(List<ToolCall> calls) {
        return new ModelTurn(Kind.ASSISTANT, null, calls, List.of());
    }

    /** 模型的纯文本输出。 */
    public static ModelTurn assistantText(String text) {
        return new ModelTurn(Kind.ASSISTANT, text, List.of(), List.of());
    }

    /** 工具执行结果（与最近一次 assistantToolCalls 一一对应）。 */
    public static ModelTurn toolResults(List<ToolObservation> results) {
        return new ModelTurn(Kind.TOOL, null, List.of(), results);
    }
}
