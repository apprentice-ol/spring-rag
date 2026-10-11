package com.jjx.ai.llmobservability.observation.llm;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

/**
 * LLM 框架 → telemetry 的 ObservationFilter 适配基类（模板方法）。
 *
 * <p><b>所属维度</b>：telemetry 扩展点（适配基类，屏蔽 micrometer {@link ObservationFilter} 细节）。</p>
 *
 * <p><b>职责</b>：实现 Spring 的 {@link ObservationFilter}（接入 observation 链），在 {@link #map} 里判断 context 类型，
 * 委托子类 {@link #extract} 提取 LLM 调用信息为 {@link LlmCall}，交给 {@link LlmTraceHandler} 记录。</p>
 *
 * <p><b>为何提供这个基类</b>：让具体 LLM 框架集成（如某 LLM SDK 的 ObservationFilter 适配器）<b>不直接 implements
 * ObservationFilter、不直接 import micrometer</b>——只继承本类、实现 {@link #extract}（从框架 Context → {@link LlmCall}）。
 * micrometer 的 ObservationFilter/Context 类型收敛在 telemetry，业务侧零 micrometer 依赖。</p>
 *
 * @param <C> 具体 LLM 框架的 observation context 类型（如 Spring AI 的 ChatModelObservationContext）
 */
public abstract class LlmObservationFilterAdapter<C extends Observation.Context> implements ObservationFilter {

    private final Class<C> contextType;
    private final LlmTraceHandler handler;

    protected LlmObservationFilterAdapter(Class<C> contextType, LlmTraceHandler handler) {
        this.contextType = contextType;
        this.handler = handler;
    }

    @Override
    public final Observation.Context map(Observation.Context context) {
        if (contextType.isInstance(context)) {
            LlmCall call = extract(contextType.cast(context));
            if (call != null) {
                handler.trace(context, call);
            }
        }
        return context;
    }

    /** 从具体框架的 observation context 提取 LLM 调用信息 → 框架无关的 {@link LlmCall}。返回 null 则不记录。 */
    protected abstract LlmCall extract(C context);
}
