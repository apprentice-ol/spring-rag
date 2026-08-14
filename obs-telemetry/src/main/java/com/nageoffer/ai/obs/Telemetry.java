package com.nageoffer.ai.obs;

import com.nageoffer.ai.obs.backend.ObservationBackend;
import com.nageoffer.ai.obs.backend.OtelRootBackend;
import com.nageoffer.ai.obs.backend.SpanWriter;
import com.nageoffer.ai.obs.config.ObsProperties;
import com.nageoffer.ai.obs.event.ConversationContext;
import com.nageoffer.ai.obs.event.TraceEvent;
import com.nageoffer.ai.obs.event.TraceHandle;
import com.nageoffer.ai.obs.processor.SpanIoLimits;
import com.nageoffer.ai.obs.processor.TracePipeline;
import com.nageoffer.ai.obs.propagation.ConversationContextAccessor;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;

/**
 * 观测门面：业务 / AOP 的统一入口，把“开 span / 记 IO / 记异常 / 跨线程传播”收敛到语义化方法。
 *
 * <p>依赖方向：本类只依赖 Micrometer Observation + OTel API + 内部事件管线，不感知任何 LLM 框架。
 * 落地的两个出口——span attribute 与结构化日志——统一由 {@link TracePipeline}（processor + exporter）负责。</p>
 */
public class Telemetry {

    private static volatile Telemetry INSTANCE;

    private static final Logger log = LoggerFactory.getLogger(Telemetry.class);

    private final ObservationRegistry registry;
    private final OpenTelemetry openTelemetry;
    private final TracePipeline pipeline;
    private final ObsProperties properties;

    public Telemetry(ObservationRegistry registry, OpenTelemetry openTelemetry,
                     TracePipeline pipeline, ObsProperties properties) {
        this.registry = registry;
        this.openTelemetry = openTelemetry;
        this.pipeline = pipeline;
        this.properties = properties;
        INSTANCE = this;
    }

    static Telemetry getInstance() {
        return INSTANCE;
    }

    // ==================== 步骤（挂当前 ambient 父 span）====================

    public TraceHandle openStep(String name) {
        return new TraceHandle(name, ObservationBackend.createAndOpen(name, registry), pipeline);
    }

    public <T> T step(String name, Supplier<T> body) {
        try (TraceHandle h = openStep(name)) {
            try {
                return body.get();
            } catch (RuntimeException e) {
                h.error(e);
                throw e;
            }
        }
    }

    public <T> T step(String name, Object input, Supplier<T> body) {
        try (TraceHandle h = openStep(name)) {
            h.input(input);
            try {
                T result = body.get();
                h.output(result);
                return result;
            } catch (RuntimeException e) {
                h.error(e);
                throw e;
            }
        }
    }

    public void step(String name, Object input, Runnable body) {
        try (TraceHandle h = openStep(name)) {
            h.input(input);
            try {
                body.run();
            } catch (RuntimeException e) {
                h.error(e);
                throw e;
            }
        }
    }

    // ==================== 流式 ====================

    public <T> Flux<T> stream(String name, Object input, Flux<T> flux) {
        return stream(name, input, flux, false);
    }

    public <T> Flux<T> stream(String name, Object input, Flux<T> flux, boolean captureOutput) {
        TraceHandle handle = openStep(name);
        handle.input(input);
        return decorateFlux(handle, flux, captureOutput);
    }

    public <T> Flux<T> decorateFlux(TraceHandle handle, Flux<T> flux, boolean captureOutput) {
        StringBuilder acc = captureOutput ? new StringBuilder() : null;
        return flux
                .doOnNext(t -> {
                    if (acc != null) {
                        acc.append(t);
                    }
                })
                .doOnError(handle::error)
                .doFinally(sig -> {
                    if (acc != null) {
                        handle.outputRaw(acc.toString());
                    }
                    handle.finish();
                });
    }

    // ==================== 独立 trace 根（无父 span）====================

    public TraceHandle openTrace(String name) {
        return new TraceHandle(name,
                OtelRootBackend.createAndOpen(name, openTelemetry, properties.getTracerName()),
                pipeline);
    }

    // ==================== 会话上下文 ====================

    public void beginConversation(String conversationId, String question) {
        Span current = Span.current();
        ConversationContext ctx = new ConversationContext(current, pipeline);
        if (conversationId != null) {
            MDC.put("conversation_id", conversationId);
        }
        ConversationContextAccessor.HOLDER.set(ctx);
        ctx.input(question);
    }

    public void conversationOutput(Object value) {
        ConversationContext ctx = ConversationContextAccessor.HOLDER.get();
        if (ctx != null) {
            ctx.output(value);
        }
    }

    public Consumer<Object> conversationSink() {
        ConversationContext ctx = ConversationContextAccessor.HOLDER.get();
        if (ctx == null) {
            log.warn("[conv-trace] conversationSink 捕获时 HOLDER 为 null"
                            + "（beginConversation 未调用或未传播到此线程）, thread={}",
                    Thread.currentThread().getName());
            return v -> { };
        }
        return ctx::output;
    }

    // ==================== ambient（写当前 server span，同步请求线程）====================

    public void tag(String key, Object value) {
        TraceEvent event = new TraceEvent(TraceEvent.EventType.ATTRIBUTE, null, null, value);
        event.setIoKey(key);
        pipeline.emit(event, SpanWriter.current());
    }

    public void traceInput(Object value) {
        if (value == null) {
            return;
        }
        emitTraceIo(SpanIoLimits.KEY_TRACE_INPUT, value);
    }

    public void traceOutput(Object value) {
        if (value == null) {
            return;
        }
        emitTraceIo(SpanIoLimits.KEY_TRACE_OUTPUT, value);
    }

    private void emitTraceIo(String key, Object value) {
        TraceEvent event = new TraceEvent(TraceEvent.EventType.TRACE_IO, null, null, value);
        event.setIoKey(key);
        pipeline.emit(event, SpanWriter.current());
    }

    // ==================== 自定义事件 ====================

    public void emit(String eventName, Object data) {
        pipeline.emit(new TraceEvent(TraceEvent.EventType.CUSTOM, eventName, null, data),
                SpanWriter.current());
    }
}
