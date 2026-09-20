package com.jjx.customer.platform.business.engine;

import com.agentframework.crosscutting.trace.Span;
import com.agentframework.crosscutting.trace.SpanEvent;
import com.agentframework.crosscutting.trace.SpanKind;
import com.agentframework.crosscutting.trace.TraceContext;
import com.agentframework.crosscutting.trace.Tracer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 内核 Tracer 的 OTel 桥：把引擎的 span（{@code agent:<id>} 根 + {@code node:<id>} 子）镜像成
 * OTel span 进现有观测链（OpenObserve / Langfuse）。
 *
 * <p><b>为什么值得激活</b>：此前内核 span 体系整装休眠（EngineBuilder 回退 SimpleTracer 且无人消费
 * exporter），ops 线引擎内 LLM 调用在 OTel 侧零可见——RAG 线靠 17 处手工 @TelemetryStep，ops 线一处没有。
 * 桥接后引擎 span 对所有业务线自动生效，埋点不再是每条线各自的税。</p>
 *
 * <p><b>父子关系策略</b>：根 span 优先挂到 ambient 当前 OTel span（请求链上下文已由
 * {@code TaskPropagation.install(ContextPropagator::wrap)} 搬进引擎线程）——引擎 span 成为请求
 * span 的真子 span，traceId 天然对齐；无 ambient 且调用方给了 32 位 hex 的 traceId
 * （{@code StartOptions.withTraceId} 接入的 chainTraceId）时，以非记录 SpanContext 强制该
 * traceId 作根——引擎 span 落进业务链而不是自立门户。</p>
 *
 * <p><b>镜像模型</b>：core Span 是内核内部的串联载体（parentId 逻辑），OTel span 是导出载体；
 * 两者经 {@code coreSpanId -> otelSpan} 映射关联，endSpan 时用 core 的时间戳回填 OTel
 * （setStartTimestamp / end(Instant)），映射随之移除。内核的 {@code flush/finishedSpans}
 * 语义（缓冲批量导出）在此为 no-op——OTel 侧自行导出。</p>
 *
 * <p>未配置 OTel 导出（本地最小起動等）时 OTel SDK 是 no-op，本桥自然降级为只维护 core 镜像，
 * 无副作用。core SpanKind 是语义分类（AGENT/NODE/LLM…），OTel SpanKind 一律 INTERNAL、
 * 原值进属性 {@code agentframework.kind}，无损。</p>
 */
public final class OtelSpanTracer implements Tracer {

    private static final Logger LOG = Logger.getLogger(OtelSpanTracer.class.getName());

    /** 属性键：内核语义 span 类型（OTel kind 全 INTERNAL，原值保真在此）。 */
    static final String KIND_ATTRIBUTE = "agentframework.kind";

    private final io.opentelemetry.api.trace.Tracer otelTracer;
    private final Map<String, io.opentelemetry.api.trace.Span> otelByCoreId = new ConcurrentHashMap<>();

    public OtelSpanTracer() {
        this(GlobalOpenTelemetry.get());
    }

    /**
     * @param openTelemetry 宿主 OTel 实例（优先注入 Spring 容器的 {@link OpenTelemetry} bean——
     *                      micrometer-tracing 桥装配的那个；缺省回退全局）
     */
    public OtelSpanTracer(OpenTelemetry openTelemetry) {
        this.otelTracer = openTelemetry.getTracer("agent-core");
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public TraceContext startTrace(String name, SpanKind kind, Map<String, Object> attributes) {
        return startTrace(null, name, kind, attributes);
    }

    @Override
    public TraceContext startTrace(String traceId, String name, SpanKind kind, Map<String, Object> attributes) {
        String effectiveTraceId = traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : traceId;
        Span core = new Span(effectiveTraceId, null, name, kind);
        applyAttributes(core, attributes);
        io.opentelemetry.api.trace.Span ambient = io.opentelemetry.api.trace.Span.current();
        SpanBuilder builder = otelTracer.spanBuilder(name)
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .setStartTimestamp(core.startTime());
        attachAttributes(builder, core);
        if (!ambient.getSpanContext().isValid() && isOtelTraceId(effectiveTraceId)) {
            // 无请求链可挂时才强制 traceId：Span.wrap(SpanContext) 造非记录 span 作隐式父，
            // 根 span 的 traceId 即业务链 id，后续子 span 经映射自然同链
            SpanContext forced = SpanContext.create(effectiveTraceId, randomSpanId(),
                    TraceFlags.getSampled(), TraceState.getDefault());
            builder.setParent(Context.root().with(io.opentelemetry.api.trace.Span.wrap(forced)));
        }
        otelByCoreId.put(core.id(), builder.startSpan());
        return new TraceContext(effectiveTraceId, core);
    }

    @Override
    public Span startSpan(TraceContext trace, Span parent, String name, SpanKind kind,
            Map<String, Object> attributes) {
        if (trace == null || !trace.active()) {
            return null;
        }
        Span core = new Span(trace.traceId(), parent == null ? null : parent.id(), name, kind);
        applyAttributes(core, attributes);
        SpanBuilder builder = otelTracer.spanBuilder(name)
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .setStartTimestamp(core.startTime());
        attachAttributes(builder, core);
        io.opentelemetry.api.trace.Span otelParent = parent == null ? null : otelByCoreId.get(parent.id());
        if (otelParent != null) {
            builder.setParent(Context.root().with(otelParent));
        }
        otelByCoreId.put(core.id(), builder.startSpan());
        return core;
    }

    @Override
    public void endSpan(Span span) {
        end(span, null);
    }

    @Override
    public void endSpan(Span span, Throwable cause) {
        end(span, cause);
    }

    @Override
    public void recordEvent(Span span, String name, Map<String, Object> attributes) {
        if (span == null) {
            return;
        }
        span.addEvent(new SpanEvent(name, null, attributes));
        io.opentelemetry.api.trace.Span otel = otelByCoreId.get(span.id());
        if (otel != null) {
            otel.addEvent(name, toAttributes(attributes));
        }
    }

    @Override
    public void setAttribute(Span span, String key, Object value) {
        if (span == null) {
            return;
        }
        span.setAttribute(key, value);
        io.opentelemetry.api.trace.Span otel = otelByCoreId.get(span.id());
        if (otel != null) {
            Object converted = toAttributeValue(value);
            if (converted instanceof String s) {
                otel.setAttribute(key, s);
            } else if (converted instanceof Long l) {
                otel.setAttribute(key, l);
            } else if (converted instanceof Double d) {
                otel.setAttribute(key, d);
            } else if (converted instanceof Boolean b) {
                otel.setAttribute(key, b);
            }
        }
    }

    @Override
    public void flush() {
        // no-op：OTel span 建立即入其自身导出管线，没有「缓冲批量导出」一层
    }

    @Override
    public List<Span> finishedSpans() {
        return List.of();
    }

    private void end(Span core, Throwable cause) {
        if (core == null) {
            return;
        }
        if (cause == null) {
            core.end();
        } else {
            core.endWithError(cause);
        }
        io.opentelemetry.api.trace.Span otel = otelByCoreId.remove(core.id());
        if (otel == null) {
            return;
        }
        try {
            if (cause != null) {
                otel.setStatus(StatusCode.ERROR,
                        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            }
            otel.end(core.endTime());
        } catch (RuntimeException e) {
            // 结束失败只丢这一个 span 的镜像，不能影响执行链
            LOG.warning("otel span end failed: " + e.getMessage());
        }
    }

    private static void applyAttributes(Span core, Map<String, Object> attributes) {
        if (attributes != null) {
            attributes.forEach(core::setAttribute);
        }
        core.setAttribute(KIND_ATTRIBUTE, core.kind().name());
    }

    private static void attachAttributes(SpanBuilder builder, Span core) {
        builder.setAllAttributes(toAttributes(core.attributes()));
    }

    private static Attributes toAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Attributes.empty();
        }
        AttributesBuilder builder = Attributes.builder();
        attributes.forEach((key, value) -> {
            Object converted = toAttributeValue(value);
            if (converted instanceof String s) {
                builder.put(key, s);
            } else if (converted instanceof Long l) {
                builder.put(key, l);
            } else if (converted instanceof Double d) {
                builder.put(key, d);
            } else if (converted instanceof Boolean b) {
                builder.put(key, b);
            } else if (converted != null) {
                builder.put(key, String.valueOf(converted));
            }
        });
        return builder.build();
    }

    /** OTel 属性只认 String/long/double/boolean；其余类型转字符串，null 丢弃。 */
    private static Object toAttributeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if (number instanceof Double || number instanceof Float) {
                return number.doubleValue();
            }
            return number.longValue();
        }
        return String.valueOf(value);
    }

    /** OTel traceId 恰为 32 位 hex；不满足时放弃强制对齐（照常生成新链）。 */
    private static boolean isOtelTraceId(String value) {
        return value.length() == 32 && value.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    private static String randomSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
