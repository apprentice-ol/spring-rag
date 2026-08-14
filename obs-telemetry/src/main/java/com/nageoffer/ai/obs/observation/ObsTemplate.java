package com.nageoffer.ai.obs.observation;

import com.nageoffer.ai.obs.observation.span.ObservationSpan;
import com.nageoffer.ai.obs.observation.span.RootSpan;
import com.nageoffer.ai.obs.observation.span.ObsSpan;
import com.nageoffer.ai.obs.observation.span.SpanWriter;
import com.nageoffer.ai.obs.observation.context.ObsConversation;
import com.nageoffer.ai.obs.observation.event.ObsEvent;
import com.nageoffer.ai.obs.observation.support.SpanIoLimits;
import com.nageoffer.ai.obs.observation.ObservationPipeline;
import com.nageoffer.ai.obs.observation.propagation.ObsConversationAccessor;
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
 * 落地的两个出口——span attribute 与结构化日志——统一由 {@link ObservationPipeline}（processor + exporter）负责。</p>
 */
public class ObsTemplate {

    private static volatile ObsTemplate INSTANCE;

    private static final Logger log = LoggerFactory.getLogger(ObsTemplate.class);

    private final ObservationRegistry registry;
    private final OpenTelemetry openTelemetry;
    private final ObservationPipeline pipeline;
    private final String tracerName;

    public ObsTemplate(ObservationRegistry registry, OpenTelemetry openTelemetry,
                       ObservationPipeline pipeline, String tracerName) {
        this.registry = registry;
        this.openTelemetry = openTelemetry;
        this.pipeline = pipeline;
        this.tracerName = tracerName;
        INSTANCE = this;
    }

    public static ObsTemplate getInstance() {
        return INSTANCE;
    }

    // ==================== 步骤（挂当前 ambient 父 span）====================

    public ObsSpan openStep(String name) {
        return new ObsSpan(name, ObservationSpan.createAndOpen(name, registry), pipeline);
    }

    public <T> T step(String name, Supplier<T> body) {
        try (ObsSpan h = openStep(name)) {
            try {
                return body.get();
            } catch (RuntimeException e) {
                h.error(e);
                throw e;
            }
        }
    }

    public <T> T step(String name, Object input, Supplier<T> body) {
        try (ObsSpan h = openStep(name)) {
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
        try (ObsSpan h = openStep(name)) {
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
        ObsSpan handle = openStep(name);
        handle.input(input);
        return decorateFlux(handle, flux, captureOutput);
    }

    public <T> Flux<T> decorateFlux(ObsSpan handle, Flux<T> flux, boolean captureOutput) {
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

    public ObsSpan openTrace(String name) {
        return new ObsSpan(name,
                RootSpan.createAndOpen(name, openTelemetry, tracerName),
                pipeline);
    }

    // ==================== 会话上下文 ====================

    public void beginConversation(String conversationId, String question) {
        Span current = Span.current();
        ObsConversation ctx = new ObsConversation(current, pipeline);
        if (conversationId != null) {
            MDC.put("conversation_id", conversationId);
        }
        ObsConversationAccessor.HOLDER.set(ctx);
        ctx.input(question);
    }

    public void conversationOutput(Object value) {
        ObsConversation ctx = ObsConversationAccessor.HOLDER.get();
        if (ctx != null) {
            ctx.output(value);
        }
    }

    public Consumer<Object> conversationSink() {
        ObsConversation ctx = ObsConversationAccessor.HOLDER.get();
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
        ObsEvent event = new ObsEvent(ObsEvent.EventType.ATTRIBUTE, null, null, value);
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
        ObsEvent event = new ObsEvent(ObsEvent.EventType.TRACE_IO, null, null, value);
        event.setIoKey(key);
        pipeline.emit(event, SpanWriter.current());
    }

    // ==================== 自定义事件 ====================

    public void emit(String eventName, Object data) {
        pipeline.emit(new ObsEvent(ObsEvent.EventType.CUSTOM, eventName, null, data),
                SpanWriter.current());
    }
}
