package com.jjx.ai.llmobservability.observation.propagation;

import com.jjx.ai.llmobservability.observation.exporter.SpanAttributeKeyMapper;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.List;

/**
 * 把 OTel Baggage 条目自动落为 trace 内<b>所有</b> span 的属性（含 Spring AI 等 framework 建的 span）。
 *
 * <p><b>为什么用 baggage</b>：会话/用户等 trace 级字段（OTel 标准 {@code session.id}/{@code user.id}）在入口写一次 baggage，
 * 随 OTel Context 传播（含跨线程，见 {@link OpenTelemetryContextAccessor}），本 processor 在每个 span
 * start 时统一落属性——后端（Langfuse 官方建议）按这些字段做 trace 级过滤/聚合才可靠。</p>
 *
 * <p><b>协作</b>：由 {@code TelemetryAutoConfiguration} 注册为 bean（Spring Boot 自动收进 TracerProvider，
 * 与 collector 开关无关）；入口经 {@code TelemetryTemplate.baggage} / {@code TelemetryConversationAspect} 写入；
 * key 经 {@link SpanAttributeKeyMapper} 追加后端专属 key（如 {@code langfuse.observation.input}）。</p>
 *
 * <p><b>安全边界</b>：默认 W3C propagator 只外发 tracecontext 不外发 baggage，条目不会泄漏给下游
 * 第三方 API；仍应遵守"不往 baggage 塞敏感信息"。</p>
 *
 * <p><b>开关</b>：{@code telemetry.propagation.baggage-span-attributes=false} 可整体停用。</p>
 */
public final class BaggageAttributeSpanProcessor implements SpanProcessor {

    private final List<SpanAttributeKeyMapper> keyMappers;

    public BaggageAttributeSpanProcessor(List<SpanAttributeKeyMapper> keyMappers) {
        this.keyMappers = keyMappers == null ? List.of() : keyMappers;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        Baggage baggage = Baggage.fromContext(parentContext);
        if (baggage.isEmpty()) {
            return;
        }
        baggage.forEach((key, entry) -> {
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
                return;
            }
            span.setAttribute(key, value);
            for (SpanAttributeKeyMapper mapper : keyMappers) {
                String mapped = mapper.map(key);
                if (mapped != null) {
                    span.setAttribute(mapped, value);
                }
            }
        });
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    @Override
    public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {
        // no-op
    }
}
