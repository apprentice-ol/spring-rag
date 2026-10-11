package com.jjx.customer.platform.business.engine;

import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.crosscutting.trace.Span;
import com.agentframework.crosscutting.trace.SpanKind;
import com.agentframework.crosscutting.trace.TraceContext;
import com.agentframework.crosscutting.trace.Tracer;
import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import com.jjx.ai.llmobservability.observation.span.TelemetrySpan;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内核 Tracer SPI 的 micrometer 实现：引擎 span（agent:/workflow:/node:/tool:）不再镜像 OTel，
 * 而是直接走与 {@code @TelemetryStep} 完全相同的观测管线（TelemetryTemplate → 事件管线 →
 * 摘要/截断 processor → span 属性/结构化日志 exporter）。
 *
 * <p><b>为什么这是终态而非又一层桥</b>：此前 {@code OtelSpanTracer} 在两套体系（内核 Tracer ↔
 * micrometer Observation）之间镜像，接缝处的问题只能逐个打补丁——幽灵父（续链造随机 spanId）、
 * 引擎节点无内容（SPI 不传数据）、input/output 格式不统一（toString vs JSON）、会话身份回填、
 * event 被 Langfuse 丢弃。统一管线后这些问题在结构上不存在：</p>
 * <ul>
 *   <li>父子 = 观测 scope 栈，引擎根自然挂在请求链（rag.chat）下，节点内发起的 LLM 调用
 *       （ChatModel observation）自然挂在 node span 下——树形即真实调用序，无强绑定</li>
 *   <li>input/output 走 STEP_INPUT/STEP_OUTPUT 事件，与 rag.* 同一套摘要、截断与 JSON 格式</li>
 *   <li>会话/用户身份、step 标签由管线（MDC / ConversationContext / ObservationFilter）统一处理</li>
 *   <li>跨轮续链（ops chainTraceId）沿用调用方既有的 chainScope 模式，adapter 无需感知 traceId</li>
 * </ul>
 *
 * <p>内核侧逻辑模型（core Span/TraceContext）照常构造——引擎内部还有消费者；观测导出全权交给
 * micrometer 管线，{@code flush/finishedSpans} 相应 no-op。</p>
 */
public final class MicrometerTracerAdapter implements Tracer {

    private final TelemetryTemplate template;

    /** 内核 span id → 观测句柄；观测侧没有可供内核回读的稳定 id，映射必须在 adapter 内维护 */
    private final Map<String, TelemetrySpan> handles = new ConcurrentHashMap<>();

    public MicrometerTracerAdapter(TelemetryTemplate template) {
        this.template = template;
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
        // traceId 是业务关联 id（会话事件用），观测续链由调用方 chainScope 负责，这里不消费。
        // 观测 traceId 取当前真实 OTel trace（无链路径为空，生成占位维持内核模型完整）
        io.opentelemetry.api.trace.SpanContext current =
                io.opentelemetry.api.trace.Span.current().getSpanContext();
        String effectiveTraceId = current.isValid() ? current.getTraceId()
                : UUID.randomUUID().toString().replace("-", "");
        Span core = new Span(effectiveTraceId, null, name, kind);
        applyAttributes(open(core, name), core, attributes);
        return new TraceContext(effectiveTraceId, core);
    }

    @Override
    public Span startSpan(TraceContext trace, Span parent, String name, SpanKind kind,
            Map<String, Object> attributes) {
        if (trace == null || !trace.active()) {
            return null;
        }
        Span core = new Span(trace.traceId(), parent == null ? null : parent.id(), name, kind);
        applyAttributes(open(core, name), core, attributes);
        return core;
    }

    @Override
    public void endSpan(Span span) {
        TelemetrySpan handle = close(span);
        if (handle != null) {
            handle.close();
        }
    }

    @Override
    public void endSpan(Span span, Throwable cause) {
        TelemetrySpan handle = close(span);
        if (handle == null) {
            return;
        }
        handle.error(cause);
        handle.close();
    }

    @Override
    public void recordEvent(Span span, String name, Map<String, Object> attributes) {
        TelemetrySpan handle = span == null ? null : handles.get(span.id());
        if (handle != null) {
            handle.tag("event." + name, attributes == null ? "" : attributes.toString());
        }
        if (span != null) {
            span.addEvent(new com.agentframework.crosscutting.trace.SpanEvent(name, null, attributes));
        }
    }

    @Override
    public void setAttribute(Span span, String key, Object value) {
        if (span == null) {
            return;
        }
        TelemetrySpan handle = handles.get(span.id());
        // 载荷语义键走 step 的 input/output 通道：与 @TelemetryStep 同一套摘要、截断与格式，
        // Langfuse 里 node:* 与 rag.* 的观感完全一致
        if (InterceptorAttributes.PAYLOAD_INPUT.equals(key)) {
            span.setAttribute(key, value);
            if (handle != null && value != null) {
                handle.input(value);
            }
            return;
        }
        if (InterceptorAttributes.PAYLOAD_OUTPUT.equals(key)) {
            span.setAttribute(key, value);
            if (handle != null && value != null) {
                handle.output(value);
            }
            return;
        }
        span.setAttribute(key, value);
        if (handle != null && value != null) {
            handle.tag(key, value);
        }
    }

    @Override
    public void flush() {
        // 观测导出由 micrometer 管线自行管理，无「缓冲批量导出」一层
    }

    @Override
    public List<Span> finishedSpans() {
        return List.of();
    }

    /** 开观测 step 并登记映射；scope 的开闭即父子关系的全部来源 */
    private TelemetrySpan open(Span core, String name) {
        TelemetrySpan handle = template.openStep(name);
        handles.put(core.id(), handle);
        return handle;
    }

    private TelemetrySpan close(Span span) {
        return span == null ? null : handles.remove(span.id());
    }

    private void applyAttributes(TelemetrySpan handle, Span core, Map<String, Object> attributes) {
        if (attributes == null) {
            return;
        }
        attributes.forEach((key, value) -> {
            core.setAttribute(key, value);
            if (value == null) {
                return;
            }
            // 建 span 时随带的输入载荷（runtime 直建的 node span 走这条路）与
            // setAttribute 路径同语义：进 input 通道而不是普通标签
            if (InterceptorAttributes.PAYLOAD_INPUT.equals(key)) {
                handle.input(value);
                return;
            }
            if (InterceptorAttributes.PAYLOAD_OUTPUT.equals(key)) {
                handle.output(value);
                return;
            }
            handle.tag(key, value);
        });
    }
}
