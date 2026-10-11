package com.jjx.ai.llmobservability.observation;

import com.jjx.ai.llmobservability.observation.context.ConversationContext;
import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import com.jjx.ai.llmobservability.observation.propagation.ConversationContextAccessor;
import com.jjx.ai.llmobservability.observation.span.TelemetrySpan;
import com.jjx.ai.llmobservability.observation.span.ObservationSpan;
import com.jjx.ai.llmobservability.observation.span.RootSpan;
import com.jjx.ai.llmobservability.observation.span.SpanWriter;
import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 观测门面：业务 / AOP 的统一入口，把“开 span / 记 IO / 记异常 / 跨线程传播”收敛到语义化方法。
 *
 * <p>依赖方向：本类只依赖 Micrometer Observation + OTel API + 内部事件管线，不感知任何 LLM 框架。
 * 落地的两个出口——span attribute 与结构化日志——统一由 {@link ObservationPipeline}（processor + exporter）负责。</p>
 */
public class TelemetryTemplate {

    private static volatile TelemetryTemplate INSTANCE;

    private static final Logger log = LoggerFactory.getLogger(TelemetryTemplate.class);
    /** 切面执行顺序不固定时的兜底：Step 先开、Conversation 后建时暂存待绑定的入口 step writer。 */
    private static final ThreadLocal<SpanWriter> PENDING_STEP_WRITER = new ThreadLocal<>();

    private final ObservationRegistry registry;
    private final OpenTelemetry openTelemetry;
    private final ObservationPipeline pipeline;
    private final String tracerName;
    /** step 返回值 → trace 维度 的提取器注册表（启动期注册，CopyOnWrite 只读并发读）。 */
    private final List<OutputDimensionExtractor<?>> outputExtractors = new CopyOnWriteArrayList<>();

    public TelemetryTemplate(ObservationRegistry registry, OpenTelemetry openTelemetry,
                       ObservationPipeline pipeline, String tracerName) {
        this.registry = registry;
        this.openTelemetry = openTelemetry;
        this.pipeline = pipeline;
        this.tracerName = tracerName;
        INSTANCE = this;
    }

    public static TelemetryTemplate getInstance() {
        return INSTANCE;
    }

    // ==================== 步骤（挂当前 ambient 父 span）====================

    public TelemetrySpan openStep(String name) {
        return new TelemetrySpan(name, ObservationSpan.createAndOpen(name, registry), pipeline);
    }

    public <T> T step(String name, Supplier<T> body) {
        try (TelemetrySpan h = openStep(name)) {
            try {
                return body.get();
            } catch (RuntimeException | Error e) {
                h.error(e);
                throw e;
            }
        }
    }

    public <T> T step(String name, Object input, Supplier<T> body) {
        try (TelemetrySpan h = openStep(name)) {
            h.input(input);
            try {
                T result = body.get();
                h.output(result);
                extractDimensions(result);
                return result;
            } catch (RuntimeException | Error e) {
                h.error(e);
                throw e;
            }
        }
    }

    public void step(String name, Object input, Runnable body) {
        try (TelemetrySpan h = openStep(name)) {
            h.input(input);
            try {
                body.run();
            } catch (RuntimeException | Error e) {
                h.error(e);
                throw e;
            }
        }
    }

    // ==================== 流式 ====================

    /** 立即开 span 并装饰 Flux（调用方保证订阅）。可能被短路、不保证订阅的场景用 {@link #deferStep}。 */
    public <T> Flux<T> stream(String name, Object input, Flux<T> flux) {
        return stream(name, input, flux, false);
    }

    public <T> Flux<T> stream(String name, Object input, Flux<T> flux, boolean captureOutput) {
        TelemetrySpan handle = openStep(name);
        handle.input(input);
        return decorateFlux(handle, flux, captureOutput);
    }

    /**
     * 延迟版 {@link #stream}：首次订阅时才开 span。
     *
     * <p>解决"开完 span 但 Flux 被分支短路、从未订阅"导致的悬空 span/trace；重复订阅每次各开一个 step。</p>
     */
    public <T> Flux<T> deferStep(String name, Object input, Supplier<Flux<T>> fluxSupplier, boolean captureOutput) {
        return Flux.defer(() -> stream(name, input, fluxSupplier.get(), captureOutput));
    }

    public <T> Flux<T> decorateFlux(TelemetrySpan handle, Flux<T> flux, boolean captureOutput) {
        StringBuilder acc = captureOutput ? new StringBuilder() : null;
        long startAt = System.currentTimeMillis();
        // 首 token 时间（TTFT）：第一个元素到达时记，finish 时落属性
        //（标准 TTFT 秒数 + first_token_at 时间戳供映射为 Langfuse completion_start_time）
        long[] firstTokenAt = {0L};
        return flux
                .doOnNext(t -> {
                    if (firstTokenAt[0] == 0L) {
                        firstTokenAt[0] = System.currentTimeMillis();
                    }
                    if (acc != null) {
                        acc.append(t);
                    }
                })
                .doOnError(handle::error)
                .doFinally(sig -> {
                    if (firstTokenAt[0] > 0L) {
                        handle.tag(AttributeKeys.TTFT_SECONDS, String.valueOf((firstTokenAt[0] - startAt) / 1000.0));
                        handle.tag(AttributeKeys.firstTokenAt(), Instant.ofEpochMilli(firstTokenAt[0]).toString());
                    }
                    if (acc != null) {
                        handle.outputRaw(acc.toString());
                    }
                    handle.finish();
                });
    }

    // ==================== 独立 trace 根（无父 span）====================

    public TelemetrySpan openTrace(String name) {
        return new TelemetrySpan(name,
                RootSpan.createAndOpen(name, openTelemetry, tracerName),
                pipeline);
    }

    // ==================== 会话上下文 ====================

    /**
     * 写请求级 OTel Baggage（返回 scope，try-with-resources 关闭恢复）。
     *
     * <p>Baggage 随 OTel Context 传播（含跨线程），经 {@code BaggageAttributeSpanProcessor}
     * 自动落为 trace 内<b>所有</b> span 的属性——会话/用户等 trace 级字段在入口写一次即可。
     * 默认 W3C propagator 不外发 baggage，值不会泄漏给下游第三方 API。</p>
     */
    public io.opentelemetry.context.Scope baggage(String key, String value) {
        Baggage updated = Baggage.current().toBuilder().put(key, value).build();
        return updated.makeCurrent();
    }

    /**
     * 写用户标识 baggage（OTel 标准 key {@code user.id}，Langfuse 原生识别；业务不必记 key）。
     * 登录态恢复后入口调用一次即可。
     */
    public io.opentelemetry.context.Scope user(String userId) {
        return baggage(AttributeKeys.USER_ID, userId);
    }

    /** 多条 baggage 的批量重载，语义同 {@link #baggage(String, String)}。 */
    public io.opentelemetry.context.Scope baggage(Map<String, String> entries) {
        io.opentelemetry.api.baggage.BaggageBuilder builder = Baggage.current().toBuilder();
        entries.forEach(builder::put);
        return builder.build().makeCurrent();
    }

    public void beginConversation(String conversationId, String question) {
        Span current = Span.current();
        ConversationContext ctx = new ConversationContext(current, pipeline);
        if (conversationId != null) {
            MDC.put(AttributeKeys.SESSION_ID, conversationId);
        }
        ConversationContextAccessor.HOLDER.set(ctx);
        SpanWriter pendingStep = PENDING_STEP_WRITER.get();
        if (pendingStep != null) {
            ctx.bindStepWriter(pendingStep);
            PENDING_STEP_WRITER.remove();
        }
        ctx.input(question);
    }

    /**
     * 把当前入口步骤 span 绑定到会话上下文（仅首个生效），使 {@link #conversationOutput} 同时写根 span 与入口 step span。
     * 供 {@code @TelemetryStep} 切面在开启入口 step 后调用，普通业务无需感知。
     */
    public void bindStepWriter(SpanWriter writer) {
        ConversationContext ctx = ConversationContextAccessor.HOLDER.get();
        if (ctx != null) {
            ctx.bindStepWriter(writer);
        } else {
            // Conversation 切面尚未执行（切面顺序不固定），先暂存，beginConversation 时绑定。
            PENDING_STEP_WRITER.set(writer);
        }
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

    /**
     * 记 trace 级维度（低基数键值，如 intent / agent 范式），供后端按维度过滤/聚合 trace。
     *
     * <p><b>业务不感知落键与后端映射</b>：telemetry 内部决定写成哪些属性（metadata 逐键 + 汇总 tags），
     * 后端专属 key（如 langfuse.trace.metadata.*）由 SpanAttributeKeyMapper 映射追加。
     * 有会话上下文时累积在对话上下文并落根 span；无（后台任务/eval）时直接写当前 span。</p>
     */
    public void traceDimension(String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        ConversationContext ctx = ConversationContextAccessor.HOLDER.get();
        if (ctx != null) {
            ctx.dimension(key, value);
            return;
        }
        tag(AttributeKeys.traceMetadata(key), value);
        tag(AttributeKeys.traceTags(), key + ":" + value);
    }

    /**
     * 标注本次模型调用的模型名（通用 LLM 概念，任何用本组件的项目语义一致）。
     *
     * <p>rerank / embedding / 自研模型 HTTP 调用在被 {@code @TelemetryStep} 标注的方法内调用本方法，
     * 落在当前步骤 span 上（OTel GenAI 语义 key 由本方法收口，调用方无需记）；
     * ChatClient 走的 LLM 调用 Spring AI 已自动标注，无需再调。</p>
     */
    public void model(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            tag(AttributeKeys.GEN_AI_REQUEST_MODEL, modelName);
        }
    }

    /**
     * 注册维度提取器：任何 step 的<b>返回值</b>是该类型（含子类）时，自动提取 trace 级维度。
     *
     * <p><b>设计动机</b>：维度（intent / agent 范式等）本来就在 step 边界的数据流里（意图分类的返回值
     * 就是意图），由观测层自动提取，业务管道零维度调用。类型用类字面量、取值用方法引用——
     * 领域对象重构时此处<b>编译报错</b>，不会静默失效。启动期注册一次（配置类构造器里）。</p>
     *
     * @param type      step 返回值类型（类字面量）
     * @param extractor 返回值 → 维度键值；返回 null/空 map 表示不提取
     */
    public <T> void dimensionOnOutput(Class<T> type, Function<T, Map<String, String>> extractor) {
        outputExtractors.add(new OutputDimensionExtractor<>(type, extractor));
    }

    /** step 产出后按返回值类型提取维度（aspect 与 {@code step()} 内部调用；异常绝不影响业务）。 */
    @SuppressWarnings("unchecked")
    public void extractDimensions(Object result) {
        if (result == null || outputExtractors.isEmpty()) {
            return;
        }
        try {
            for (OutputDimensionExtractor<?> extractor : outputExtractors) {
                if (extractor.type().isInstance(result)) {
                    Map<String, String> dims = ((OutputDimensionExtractor<Object>) extractor).extract(result);
                    if (dims != null) {
                        dims.forEach(this::traceDimension);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("[telemetry] 维度提取失败（不影响业务）: {}", e.getMessage());
        }
    }

    /** {@link #dimensionOnOutput} 的注册项。 */
    private record OutputDimensionExtractor<T>(Class<T> type, Function<T, Map<String, String>> extractor) {

        Map<String, String> extract(Object value) {
            return extractor.apply(type.cast(value));
        }
    }

    // ==================== ambient（写当前 server span，同步请求线程）====================

    public void tag(String key, Object value) {
        TelemetryEvent event = new TelemetryEvent(TelemetryEvent.EventType.ATTRIBUTE, null, null, value);
        event.setIoKey(key);
        pipeline.emit(event, SpanWriter.current());
    }

    public void traceInput(Object value) {
        if (value == null) {
            return;
        }
        emitTraceIo(AttributeKeys.TRACE_INPUT, value);
    }

    public void traceOutput(Object value) {
        if (value == null) {
            return;
        }
        emitTraceIo(AttributeKeys.TRACE_OUTPUT, value);
    }

    private void emitTraceIo(String key, Object value) {
        TelemetryEvent event = new TelemetryEvent(TelemetryEvent.EventType.TRACE_IO, null, null, value);
        event.setIoKey(key);
        pipeline.emit(event, SpanWriter.current());
    }

    // ==================== 自定义事件 ====================

    public void emit(String eventName, Object data) {
        pipeline.emit(new TelemetryEvent(TelemetryEvent.EventType.CUSTOM, eventName, null, data),
                SpanWriter.current());
    }
}
