package com.agentframework.crosscutting.interceptor;

import com.agentframework.crosscutting.cache.CacheEntry;
import com.agentframework.crosscutting.cache.CacheKeyBuilder;
import com.agentframework.crosscutting.cache.CacheStore;
import com.agentframework.crosscutting.metrics.Metrics;
import com.agentframework.crosscutting.trace.Span;
import com.agentframework.crosscutting.trace.SpanKind;
import com.agentframework.crosscutting.trace.TraceContext;
import com.agentframework.crosscutting.trace.Tracer;
import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.policy.RetryPolicy;
import com.agentframework.definition.policy.TimeoutPolicy;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.event.Topics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置拦截器集合：追踪、指标、缓存、重试、超时、熔断、日志。
 *
 * <p>执行顺序由 {@code order()} 决定，数值小的在外层：重试(5) → 追踪(10) → 超时(15) →
 * 指标(20) → 熔断(25) → 缓存(30) → 日志(1000)。</p>
 */
public final class Interceptors {

    private Interceptors() {
    }

    /** 追踪拦截器：把一次调用包成 span，失败时记录错误。 */
    public static final class Trace implements Interceptor {

        private final Tracer tracer;

        /** @param tracer 追踪器 */
        public Trace(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public String name() {
            return "trace";
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean supports(InterceptorContext context) {
            return tracer != null && tracer.enabled() && context.attribute(InterceptorAttributes.TRACE) != null;
        }

        @Override
        public <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception {
            TraceContext trace = context.attribute(InterceptorAttributes.TRACE);
            Span parent = context.attribute(InterceptorAttributes.TRACE_PARENT);
            SpanKind kind = context.attribute(InterceptorAttributes.SPAN_KIND, SpanKind.class);
            String spanName = context.attribute(InterceptorAttributes.SPAN_NAME, String.class);
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("operation", context.operation());
            if (context.nodeId() != null) {
                attributes.put("nodeId", context.nodeId());
            }
            Span span = tracer.startSpan(trace, parent, spanName == null ? context.operation() : spanName,
                    kind == null ? SpanKind.INTERNAL : kind, attributes);
            try {
                T result = invocation.proceed();
                tracer.endSpan(span);
                return result;
            } catch (Exception e) {
                tracer.endSpan(span, e);
                throw e;
            }
        }
    }

    /** 指标拦截器：统计调用次数与耗时分布。 */
    public static final class MetricsInterceptor implements Interceptor {

        private final Metrics metrics;
        private final String prefix;

        /**
         * @param metrics 指标采集器
         * @param prefix  指标前缀，例如 {@code agent}
         */
        public MetricsInterceptor(Metrics metrics, String prefix) {
            this.metrics = metrics;
            this.prefix = prefix == null || prefix.isBlank() ? "agent" : prefix;
        }

        @Override
        public String name() {
            return "metrics";
        }

        @Override
        public int order() {
            return 20;
        }

        @Override
        public boolean supports(InterceptorContext context) {
            return metrics != null;
        }

        @Override
        public <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception {
            long start = System.nanoTime();
            Map<String, Object> tags = new LinkedHashMap<>();
            tags.put("operation", context.operation());
            tags.put("phase", context.phase().name());
            try {
                T result = invocation.proceed();
                metrics.counter(prefix + ".calls", 1L, tags);
                return result;
            } catch (Exception e) {
                metrics.counter(prefix + ".errors", 1L, tags);
                throw e;
            } finally {
                double millis = (System.nanoTime() - start) / 1_000_000.0;
                metrics.histogram(prefix + ".duration_ms", millis, tags);
            }
        }
    }

    /** 缓存拦截器：命中则直接返回，未命中则执行并回填。 */
    public static final class Cache implements Interceptor {

        private final CacheStore store;
        private final CacheKeyBuilder keyBuilder;
        private final EventBus events;
        private final Metrics metrics;

        /**
         * @param store      缓存存储
         * @param keyBuilder 缓存键构建器
         * @param events     事件总线，可为 null
         * @param metrics    指标采集器，可为 null
         */
        public Cache(CacheStore store, CacheKeyBuilder keyBuilder, EventBus events, Metrics metrics) {
            this.store = store;
            this.keyBuilder = keyBuilder;
            this.events = events;
            this.metrics = metrics;
        }

        @Override
        public String name() {
            return "cache";
        }

        @Override
        public int order() {
            return 30;
        }

        @Override
        public boolean supports(InterceptorContext context) {
            CachePolicy policy = context.attribute(InterceptorAttributes.CACHE_POLICY, CachePolicy.class);
            return store != null && keyBuilder != null && policy != null && policy.enabled();
        }

        @Override
        public <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception {
            CachePolicy policy = context.attribute(InterceptorAttributes.CACHE_POLICY, CachePolicy.class);
            String namespace = String.valueOf(context.attributes()
                    .getOrDefault(InterceptorAttributes.CACHE_NAMESPACE, "default"));
            String operation = String.valueOf(context.attributes()
                    .getOrDefault(InterceptorAttributes.CACHE_OPERATION, context.operation()));
            Object payload = context.attributes().getOrDefault(InterceptorAttributes.CACHE_INPUT, context.operation());
            String key = keyBuilder.build(namespace, operation, payload);

            Optional<CacheEntry> hit = store.get(key);
            if (hit.isPresent()) {
                if (metrics != null) {
                    metrics.counter("cache.hits", 1L, Map.of("operation", operation));
                }
                if (events != null) {
                    events.publish(Event.of(Topics.CACHE_HIT, context.sessionId(),
                            Map.of("operation", operation, "key", key)));
                }
                @SuppressWarnings("unchecked")
                T cached = (T) hit.get().value();
                return cached;
            }

            T result = invocation.proceed();
            if (result != null) {
                store.put(CacheEntry.of(key, namespace, result, policy.ttl()));
            }
            return result;
        }
    }

    /** 重试拦截器：按指数退避重试失败的调用。 */
    public static final class Retry implements Interceptor {

        @Override
        public String name() {
            return "retry";
        }

        @Override
        public int order() {
            return 5;
        }

        @Override
        public boolean supports(InterceptorContext context) {
            RetryPolicy policy = context.attribute(InterceptorAttributes.RETRY_POLICY, RetryPolicy.class);
            return policy != null && policy.enabled();
        }

        @Override
        public <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception {
            RetryPolicy policy = context.attribute(InterceptorAttributes.RETRY_POLICY, RetryPolicy.class);
            Exception last = null;
            for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
                try {
                    return invocation.proceed();
                } catch (Exception e) {
                    last = e;
                    if (attempt == policy.maxAttempts()) {
                        break;
                    }
                    sleep(policy.backoffFor(attempt + 1));
                }
            }
            throw new RetryExhaustedException(context.operation(), policy.maxAttempts(), last);
        }

        /** 退避等待，中断时恢复中断标记并抛出。 */
        private void sleep(Duration backoff) {
            if (backoff == null || backoff.isZero() || backoff.isNegative()) {
                return;
            }
            try {
                Thread.sleep(backoff.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("重试退避被中断", e);
            }
        }
    }

    /** 超时拦截器：使用虚拟线程承载调用并设置硬性截止时间。 */
    public static final class Timeout implements Interceptor {

        @Override
        public String name() {
            return "timeout";
        }

        @Override
        public int order() {
            return 15;
        }

        @Override
        public boolean supports(InterceptorContext context) {
            TimeoutPolicy policy = context.attribute(InterceptorAttributes.TIMEOUT_POLICY, TimeoutPolicy.class);
            return policy != null && policy.enabled();
        }

        @Override
        public <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception {
            TimeoutPolicy policy = context.attribute(InterceptorAttributes.TIMEOUT_POLICY, TimeoutPolicy.class);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                // 必须经 TaskPropagation：新开的虚拟线程不继承线程本地变量，不搬运上下文的话
                // 节点里的链路追踪 span 会全部退化成根 span，一次问答断成好几条 trace
                Future<T> future = executor.submit(TaskPropagation.wrap(invocation::proceed));
                try {
                    return future.get(policy.timeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw new TimeoutExceededException(context.operation(), policy.timeout());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw new IllegalStateException(cause);
                }
            }
        }
    }

    /** 熔断拦截器：连续失败达到阈值后进入冷却期，期间直接拒绝调用。 */
    public static final class CircuitBreaker implements Interceptor {

        private final int failureThreshold;
        private final Duration openDuration;
        private final Map<String, State> states = new ConcurrentHashMap<>();

        /**
         * @param failureThreshold 触发熔断的连续失败次数
         * @param openDuration     冷却时长
         */
        public CircuitBreaker(int failureThreshold, Duration openDuration) {
            this.failureThreshold = Math.max(1, failureThreshold);
            this.openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        }

        @Override
        public String name() {
            return "circuit-breaker";
        }

        @Override
        public int order() {
            return 25;
        }

        @Override
        public <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception {
            String key = String.valueOf(context.attributes()
                    .getOrDefault(InterceptorAttributes.CIRCUIT_KEY, context.operation()));
            State state = states.computeIfAbsent(key, ignored -> new State());
            long remaining = state.openUntilMillis - System.currentTimeMillis();
            if (remaining > 0) {
                throw new CircuitOpenException(key, remaining);
            }
            try {
                T result = invocation.proceed();
                state.failures.set(0);
                return result;
            } catch (Exception e) {
                if (state.failures.incrementAndGet() >= failureThreshold) {
                    state.openUntilMillis = System.currentTimeMillis() + openDuration.toMillis();
                    state.failures.set(0);
                }
                throw e;
            }
        }

        /**
         * @param key 熔断分组键
         * @return 当前是否处于打开状态
         */
        public boolean isOpen(String key) {
            State state = states.get(key);
            return state != null && state.openUntilMillis > System.currentTimeMillis();
        }

        /** 单个分组的熔断状态。 */
        private static final class State {

            private final AtomicInteger failures = new AtomicInteger();
            private volatile long openUntilMillis;
        }
    }

    /** 日志拦截器：记录调用开始、耗时与失败原因。 */
    public static final class Logging implements Interceptor {

        private static final System.Logger LOG = System.getLogger(Interceptors.Logging.class.getName());

        @Override
        public String name() {
            return "logging";
        }

        @Override
        public int order() {
            return 1000;
        }

        @Override
        public <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception {
            long start = System.nanoTime();
            try {
                T result = invocation.proceed();
                LOG.log(System.Logger.Level.DEBUG, "调用成功：{0}（{1} ms）", context.operation(),
                        elapsedMillis(start));
                return result;
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "调用失败：{0}（{1} ms）：{2}", context.operation(),
                        elapsedMillis(start), e.getMessage());
                throw e;
            }
        }

        /** @return 从开始到现在的毫秒数 */
        private long elapsedMillis(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }
    }
}
