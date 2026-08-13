package com.nageoffer.ai.rag.config.telemetry;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * RAG 步骤可观测入口。开挂在当前 trace 下的 child span（micrometer Observation → OTel span），spanId 写入
 * MDC（rag_step/step_id，由 OpenObserveAppender 平铺为字段）；也可开无父的 trace 根 span（{@link #startRoot}）。
 *
 * <p><b>声明式优先</b>：业务方法加 {@link TraceStep} 注解即可（由 {@link TraceStepAspect} 自动埋点），
 * 手动入口仅用于 AOP 盲区（静态方法 / 私有方法 / 跨线程流式）：
 * <ul>
 *   <li>{@link #step(String, Object, Supplier)} / {@link #stream(String, Object, Flux)} —— 同步 / 流式 step。</li>
 *   <li>{@link #startRoot(String)} —— 强制开新 trace 根（评测每 item 独立 trace）。</li>
 * </ul>
 */
@Component
public class RagTelemetry {

    private static final Logger log = LoggerFactory.getLogger(RagTelemetry.class);
    private static volatile RagTelemetry INSTANCE;
    private final ObservationRegistry registry;
    private final OpenTelemetry openTelemetry;

    public RagTelemetry(ObservationRegistry registry, OpenTelemetry openTelemetry) {
        this.registry = registry;
        this.openTelemetry = openTelemetry;
        INSTANCE = this;
    }

    /** 单例引用，供 {@link RagLogger} 等静态入口委托（Spring 启动后非空）。 */
    static RagTelemetry getInstance() {
        return INSTANCE;
    }

    /**
     * 开一个步骤 span 句柄（挂当前 ambient 父下；手动管理 input/output/close/finish）。能用 lambda 重载就别用这个。
     * 已 {@code start()} + {@code openScope()}，spanId 已入 MDC。
     */
    public StepSpan step(String name) {
        Observation observation = Observation.createNotStarted(name, registry)
                .lowCardinalityKeyValue("rag.step", name)
                .start();
        Observation.Scope scope = observation.openScope();
        Span span = Span.current();
        String spanId = span.getSpanContext().getSpanId();
        MDC.put("rag_step", name);
        MDC.put("step_id", spanId);
        return new StepSpan(observation, scope, spanId, name, span);
    }

    /**
     * 开一条<b>无父的 trace 根 span</b>（OTel {@code setNoParent} + {@code makeCurrent}），不经 micrometer
     * Observation。用于「已 in-trace 需脱钩」或后台任务（评测每 item 独立 trace）。已把 spanId + traceId 写入
     * MDC，后续子 span 由 Context Propagation 挂到该根下。
     *
     * <p>调用方用 try-with-resources 管理（{@link StepSpan#close()} 会 end span + 还原 context + 清理 MDC）。</p>
     */
    public StepSpan startRoot(String name) {
        Span span = openTelemetry.getTracer("springai-rag")
                .spanBuilder(name)
                .setNoParent()
                .startSpan();
        io.opentelemetry.context.Scope scope = span.makeCurrent();
        String spanId = span.getSpanContext().getSpanId();
        MDC.put("rag_step", name);
        MDC.put("step_id", spanId);
        MDC.put("traceId", span.getSpanContext().getTraceId());
        return new StepSpan(name, spanId, span, scope);
    }

    /** 同步步骤：开 span → 执行 body → close。不记 input/output，纯计时 + span。异常自动记 error。 */
    public <T> T step(String name, Supplier<T> body) {
        try (StepSpan s = step(name)) {
            try {
                return body.get();
            } catch (RuntimeException e) {
                s.error(e);
                throw e;
            }
        }
    }

    /** 同步步骤：开 span → input(in) → body → output(返回值) → close。手动埋点的推荐姿势，异常自动记 error。 */
    public <T> T step(String name, Object input, Supplier<T> body) {
        try (StepSpan s = step(name)) {
            s.input(input);
            try {
                T result = body.get();
                s.output(result);
                return result;
            } catch (RuntimeException e) {
                s.error(e);
                throw e;
            }
        }
    }

    /** 同步步骤（无返回值）：开 span → input(in) → body → close。异常自动记 error。 */
    public void step(String name, Object input, Runnable body) {
        try (StepSpan s = step(name)) {
            s.input(input);
            try {
                body.run();
            } catch (RuntimeException e) {
                s.error(e);
                throw e;
            }
        }
    }

    /** 流式步骤：开 span → input(in) → 装饰 Flux（doOnError 记 error、doFinally finish）。不记 output（向后兼容）。
     *  调用方照常 subscribe，span 在流终止时自动关闭。 */
    public <T> Flux<T> stream(String name, Object input, Flux<T> flux) {
        return stream(name, input, flux, false);
    }

    /** 流式步骤（可选输出捕获）：开 span → input(in) → 装饰 Flux。
     *  captureOutput=true 时，doOnNext 把每个元素累积进 StringBuilder，doFinally 时以
     *  {@link StepSpan#outputRaw} 原样记为 span output（完整 LLM 回答落 OpenObserve Output 面板，
     *  受 MAX_SPAN_IO 字符上限兜底）；doOnError 记 error，doFinally finish 关 span。 */
    public <T> Flux<T> stream(String name, Object input, Flux<T> flux, boolean captureOutput) {
        StepSpan s = step(name);
        s.input(input);
        return decorateFlux(s, flux, captureOutput);
    }

    /**
     * 装饰一个 Flux：doOnNext 累积（captureOutput 时）→ doOnError 记 error → doFinally finish 关 span。
     * 供 {@link TraceStepAspect}（流式方法）与 {@link #stream} 共用，避免两处重复。
     */
    public <T> Flux<T> decorateFlux(StepSpan span, Flux<T> flux, boolean captureOutput) {
        log.warn("[telemetry-diagnose] decorateFlux: HOLDER={}, thread={}",
                ConversationTraceAccessor.HOLDER.get() == null ? "null" : "present", Thread.currentThread().getName());
        StringBuilder acc = captureOutput ? new StringBuilder() : null;
        return flux
                .doOnNext(t -> { if (acc != null) acc.append(t); })
                .doOnError(span::error)
                .doFinally(sig -> {
                    log.warn("[telemetry-diagnose] decorateFlux doFinally: HOLDER={}, thread={}",
                            ConversationTraceAccessor.HOLDER.get() == null ? "null" : "present", Thread.currentThread().getName());
                    if (acc != null) {
                        span.outputRaw(acc.toString());
                    }
                    span.finish();
                });
    }

    /**
     * 开启一次对话的 trace 作用域：捕获 HTTP 根 span 引用 + MDC(conversation_id) + ambient {@link ConversationTrace}，
     * 并在入口直接设 input（用户问题）。
     *
     * <p>在 web 边界（{@link ConversationTraceAdvisor}，请求线程，server span 为 current）调用；ambient scope 经
     * micrometer context-propagation 透传到虚拟线程（{@code ContextSnapshot.captureAll()}）与 Reactor 回调。
     * output（assistant 回答）由业务在回答完成点通过 {@link #currentConversationTrace()} 捕获引用后显式设置。
     * 调用方（Advisor）无需感知 trace 细节。
     */
    public void startConversation(String conversationId, String question) {
        Span current = Span.current();
        log.warn("[telemetry-diagnose] startConversation: currentSpanId={}, traceId={}, valid={}",
                current.getSpanContext().getSpanId(), current.getSpanContext().getTraceId(),
                current.getSpanContext().isValid());
        ConversationTrace trace = new ConversationTrace(current);
        if (conversationId != null) {
            MDC.put("conversation_id", conversationId);
        }
        ConversationTraceAccessor.HOLDER.set(trace);
        // 入口直接设 input（用户问题）：早、可靠，不依赖 saveMessage(user) 的调用顺序
        trace.input(question);
    }

    public ConversationTrace currentConversationTrace() {
        return ConversationTraceAccessor.HOLDER.get();
    }

    /**
     * 给当前 ambient span（HTTP 请求场景下即 server span = trace 根）写低基数属性。
     * 供 eval 等同步 HTTP 请求在「不开独立 root trace」时给根 span 打标。
     */
    public void rootAttr(String key, Object value) {
        try {
            Span.current().setAttribute(key, value == null ? "" : value.toString());
        } catch (Exception ignored) {
            // 无当前 span 或设置失败，忽略
        }
    }

    /**
     * 给当前 ambient 根 span 写 trace 级 input（{@code rag.trace.input}，Collector 映射为
     * {@code langfuse.observation.input}）。供 eval 单条重评等同步 HTTP 请求：把 IO 写到 HTTP 根 span，
     * 一个请求一个 trace、列表 IO 稳定显示，区别于批量 eval 的 {@link #startRoot} 独立 trace。
     * 仅在请求线程同步调用有效（{@code Span.current()} = HTTP server span）。
     */
    public void rootTraceInput(Object value) {
        setRootTraceIo("rag.trace.input", value);
    }

    /** 给当前 ambient 根 span 写 trace 级 output（{@code rag.trace.output}）。语义同 {@link #rootTraceInput}。 */
    public void rootTraceOutput(Object value) {
        setRootTraceIo("rag.trace.output", value);
    }

    /** root trace IO 落当前 span：String 原样 + MAX_SPAN_IO 截断（与 {@link ConversationTrace} / {@link StepSpan#traceInput} 一致）。 */
    private void setRootTraceIo(String key, Object value) {
        if (value == null) {
            return;
        }
        try {
            String s = value.toString();
            Span.current().setAttribute(key, s.length() > StepSpan.MAX_SPAN_IO
                    ? s.substring(0, StepSpan.MAX_SPAN_IO) : s);
        } catch (Exception ignored) {
            // 无当前 span 或设置失败，忽略
        }
    }
}
