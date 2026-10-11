package com.jjx.ai.llmobservability.observation.exporter;

/**
 * span attribute key 的后端映射器：把应用侧统一写的<b>通用 key</b>（OTel 标准 key 或命名空间前缀 key）映射为
 * 展示后端识别的专属 key（如 {@code langfuse.*}）。
 *
 * <p><b>为什么在应用侧而不是 collector</b>：collector 是可选组件（{@code telemetry.collector.enabled=false}
 * 时应用直连后端），映射放 collector 会导致两种模式行为不一致。映射器注册为 bean 即在应用内生效，
 * 与出口（collector / 直连）无关。</p>
 *
 * <p><b>协作</b>：由各后端适配模块（如 llm-observability-backends 的 Langfuse 映射器）注册 bean；
 * {@link SpanAttributeExporter}（写事件属性时）与 {@code BaggageAttributeSpanProcessor}
 * （baggage 落 span 时）对每个写入 key 逐一查询映射，映射命中则<b>追加</b>写目标 key（原 key 保留）。</p>
 *
 * <p><b>不做什么</b>：不改值、不过滤——只做 key 改名；值加工是 processor 层的事。</p>
 */
public interface SpanAttributeKeyMapper {

    /**
     * 把通用 key 映射为后端专属 key。
     *
     * @param key 应用侧写的通用 key（OTel 标准 {@code gen_ai.input.messages} 或命名空间前缀 key）
     * @return 后端专属 key（如 {@code langfuse.observation.input}）；无映射返回 null（不追加写入）
     */
    String map(String key);
}
