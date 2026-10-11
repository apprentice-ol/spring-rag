package com.jjx.ai.llmobservability.observation.llm;

import io.micrometer.observation.Observation;

/**
 * LLM 调用观测的扩展点（telemetry 的接口，框架无关）。
 *
 * <p><b>所属维度</b>：telemetry 扩展点（LLM 观测的"记录器"接口）。</p>
 *
 * <p><b>职责</b>：把一次 LLM 调用（{@link LlmCall}）记录到 observation context。
 * 由具体实现决定记成什么语义——默认 {@link GenAiLlmTraceHandler} 记 OTel GenAI 官方属性（gen_ai.*），
 * 应用可提供自定义实现（如记 langfuse.* 或同时发 metrics）覆盖默认。</p>
 *
 * <p><b>协作</b>：LLM 框架集成（如某 LLM SDK 的 {@code ObservationFilter} 适配器，实现 Spring {@code ObservationFilter}
 * 做适配）从框架 Context 提取信息为 {@link LlmCall}，调本接口。telemetry 不依赖任何 LLM 框架。</p>
 *
 * <p><b>为何是 telemetry 的接口而非直接 ObservationFilter</b>：解耦——LLM 框架集成只管"提取 + 适配 observation"，
 * 记录语义（gen_ai.* / langfuse.* / 自定义）收敛在本接口实现，可替换、可多实现。</p>
 */
public interface LlmTraceHandler {

    /** 记一次 LLM 调用到 observation context（context 来自 micrometer，框架无关）。 */
    void trace(Observation.Context context, LlmCall call);
}
