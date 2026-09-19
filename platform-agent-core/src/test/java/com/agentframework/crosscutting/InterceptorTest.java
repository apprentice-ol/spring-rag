package com.agentframework.crosscutting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.cache.InMemoryCacheStore;
import com.agentframework.crosscutting.interceptor.CircuitOpenException;
import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.crosscutting.interceptor.InterceptorChain;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.InterceptorPhase;
import com.agentframework.crosscutting.interceptor.Interceptors;
import com.agentframework.crosscutting.interceptor.TimeoutExceededException;
import com.agentframework.crosscutting.metrics.InMemoryMetrics;
import com.agentframework.crosscutting.trace.SimpleTracer;
import com.agentframework.crosscutting.trace.SpanKind;
import com.agentframework.crosscutting.trace.TraceContext;
import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.policy.RetryPolicy;
import com.agentframework.definition.policy.TimeoutPolicy;
import com.agentframework.infra.storage.InMemorySpanExporter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 横切层测试：控制管道（重试、超时、缓存、熔断、追踪、指标）。 */
class InterceptorTest {

    @Test
    @DisplayName("重试拦截器按策略重试并最终成功")
    void retryEventuallySucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        InterceptorContext context = InterceptorContext.of(InterceptorPhase.AROUND_TOOL, "tool:flaky")
                .withAttribute(InterceptorAttributes.RETRY_POLICY, RetryPolicy.of(3, Duration.ofMillis(1)));

        String result = new InterceptorChain().add(new Interceptors.Retry()).execute(context, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("临时失败");
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("重试耗尽后抛出 RetryExhaustedException")
    void retryExhausted() {
        InterceptorContext context = InterceptorContext.of(InterceptorPhase.AROUND_TOOL, "tool:down")
                .withAttribute(InterceptorAttributes.RETRY_POLICY, RetryPolicy.of(2, Duration.ofMillis(1)));

        assertThrows(com.agentframework.crosscutting.interceptor.RetryExhaustedException.class,
                () -> new InterceptorChain().add(new Interceptors.Retry()).execute(context, () -> {
                    throw new IllegalStateException("一直失败");
                }));
    }

    @Test
    @DisplayName("超时拦截器中断超时调用")
    void timeoutInterrupts() {
        InterceptorContext context = InterceptorContext.of(InterceptorPhase.AROUND_LLM, "llm:slow")
                .withAttribute(InterceptorAttributes.TIMEOUT_POLICY, TimeoutPolicy.of(Duration.ofMillis(50)));

        assertThrows(TimeoutExceededException.class,
                () -> new InterceptorChain().add(new Interceptors.Timeout()).execute(context, () -> {
                    Thread.sleep(2000);
                    return "迟到";
                }));
    }

    @Test
    @DisplayName("缓存拦截器命中后不再执行目标调用")
    void cacheHitSkipsInvocation() throws Exception {
        InMemoryCacheStore store = new InMemoryCacheStore();
        AtomicInteger calls = new AtomicInteger();
        InterceptorContext context = InterceptorContext.of(InterceptorPhase.AROUND_LLM, "llm:plan")
                .withAttribute(InterceptorAttributes.CACHE_POLICY, CachePolicy.content(Duration.ofMinutes(5)))
                .withAttribute(InterceptorAttributes.CACHE_NAMESPACE, "session-1")
                .withAttribute(InterceptorAttributes.CACHE_INPUT, "同样的输入");
        InterceptorChain chain = new InterceptorChain()
                .add(new Interceptors.Cache(store, new com.agentframework.crosscutting.cache.ContentHashCacheKeyBuilder(),
                        null, null));

        String first = chain.execute(context, () -> {
            calls.incrementAndGet();
            return "结果";
        });
        String second = chain.execute(context, () -> {
            calls.incrementAndGet();
            return "结果";
        });

        assertEquals("结果", first);
        assertEquals("结果", second);
        assertEquals(1, calls.get());
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("熔断器在连续失败达到阈值后打开")
    void circuitBreakerOpens() {
        Interceptors.CircuitBreaker breaker = new Interceptors.CircuitBreaker(2, Duration.ofSeconds(1));
        InterceptorContext context = InterceptorContext.of(InterceptorPhase.AROUND_TOOL, "tool:db")
                .withAttribute(InterceptorAttributes.CIRCUIT_KEY, "db");
        InterceptorChain chain = new InterceptorChain().add(breaker);

        for (int i = 0; i < 2; i++) {
            assertThrows(IllegalStateException.class, () -> chain.execute(context, () -> {
                throw new IllegalStateException("数据库异常");
            }));
        }
        assertTrue(breaker.isOpen("db"));
        assertThrows(CircuitOpenException.class, () -> chain.execute(context, () -> "不该执行"));
    }

    @Test
    @DisplayName("追踪拦截器产出 span 并导出")
    void traceInterceptor() throws Exception {
        InMemorySpanExporter exporter = new InMemorySpanExporter();
        SimpleTracer tracer = SimpleTracer.of(exporter);
        TraceContext trace = tracer.startTrace("agent:demo", SpanKind.AGENT, Map.of("sessionId", "s1"));
        InterceptorContext context = InterceptorContext.of(InterceptorPhase.AROUND_TOOL, "tool:search")
                .withAttribute(InterceptorAttributes.TRACE, trace)
                .withAttribute(InterceptorAttributes.SPAN_KIND, SpanKind.TOOL)
                .withAttribute(InterceptorAttributes.SPAN_NAME, "tool:search");

        String result = new InterceptorChain().add(new Interceptors.Trace(tracer)).execute(context, () -> "ok");
        tracer.endSpan(trace.rootSpan());
        tracer.flush();

        assertEquals("ok", result);
        assertEquals(1, exporter.spansNamed("tool:search").size());
        assertTrue(exporter.spansNamed("tool:search").get(0).finished());
        assertEquals(com.agentframework.crosscutting.trace.SpanStatus.OK,
                exporter.spansNamed("tool:search").get(0).status());
    }

    @Test
    @DisplayName("指标拦截器记录调用次数与耗时")
    void metricsInterceptor() throws Exception {
        InMemoryMetrics metrics = new InMemoryMetrics();
        InterceptorContext context = InterceptorContext.of(InterceptorPhase.AROUND_NODE, "node:plan");

        new InterceptorChain().add(new Interceptors.MetricsInterceptor(metrics, "test"))
                .execute(context, () -> "ok");

        assertEquals(1L, metrics.counterValue("test.calls"));
        assertEquals(1, metrics.values("test.duration_ms").size());
    }
}
