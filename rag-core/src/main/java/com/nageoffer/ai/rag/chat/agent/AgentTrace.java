package com.nageoffer.ai.rag.chat.agent;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行轨迹（可变累加器）。
 * <p>agent 在编排过程中逐步 {@link #step} 累加，最终随 {@link AgentRetrievalResult} 返回。
 * 供前端对照面板渲染 trace 时间线（Live/Eval 复用），也可序列化落 agent_trace。
 * <p>设计为应用内结构化轨迹，与 OpenObserve 的 OTel span 并行而非依赖——保证对照面板不依赖 OO 在线。
 */
@Getter
public class AgentTrace {

    /** 范式标识（对齐 {@link RagParadigm#getCode()}） */
    private final String paradigm;

    /** 执行步骤（按时间顺序） */
    private final List<AgentStep> steps = new ArrayList<>();

    /** 编排内的 LLM 调用次数（grade/decompose/plan/react 循环），最终回答不算 */
    private int llmCallCount = 0;

    /** 轨迹开始时间（算总耗时用） */
    private final long startTimeMs;

    public AgentTrace(String paradigm) {
        this.paradigm = paradigm;
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * 记录一步（不带结构化产物，detail=null）。旧入口，Naive/CRAG/Self-RAG/Plan-Execute 复用。
     *
     * @param stepStartMs 该步开始时间（System.currentTimeMillis()），用于算单步耗时
     */
    public AgentStep step(String action, String thought, String inputSummary, String outputSummary, long stepStartMs) {
        return stepDetail(action, thought, inputSummary, outputSummary, stepStartMs, null);
    }

    /**
     * 记录一步并附带结构化产物（如 ReAct 的命中 chunks / 逐条评分理由 / 重排顺序）。
     * detail 随 trace 一起 SSE 推送与落库，前端按 action 类型展开渲染。
     *
     * @param stepStartMs 该步开始时间（System.currentTimeMillis()），用于算单步耗时
     * @param detail      结构化产物对象（见 {@link AgentStepDetails}），可为 null
     */
    public AgentStep stepDetail(String action, String thought, String inputSummary, String outputSummary,
                                long stepStartMs, Object detail) {
        AgentStep s = new AgentStep(
                steps.size(), action, thought,
                truncate(inputSummary), truncate(outputSummary),
                System.currentTimeMillis() - stepStartMs,
                detail);
        steps.add(s);
        return s;
    }

    /** 标记一次编排内 LLM 调用（grade/decompose/plan/reflect/react-thought）。 */
    public void incrementLlmCall() {
        llmCallCount++;
    }

    public long getTotalLatencyMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
