package com.jjx.customer.platform.common.util;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 阻塞调用超时守卫。
 *
 * <p>Spring AI 的 {@code chatClient.prompt().call()} 是同步阻塞调用，且当前版本未暴露 HTTP 超时配置；
 * 一旦上游（DeepSeek 等）挂起不返回，请求线程会永久阻塞，前端 SSE 一直转圈且无任何报错。
 * 本工具把调用放到虚拟线程执行，主线程只等有限时间：超时抛 {@link TimeoutException}，
 * 由调用方按原有降级策略处理（意图分类默认走检索、查询改写回退原文、ReAct 终止循环）。
 * 底层被挂起的虚拟线程不会释放，但虚拟线程成本极低，属于可接受的“只泄漏线程不阻塞请求”的兜底。</p>
 */
@Slf4j
public final class LlmCallGuard {

    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("llm-guard-", 0).factory());

    private LlmCallGuard() {
    }

    /**
     * 在虚拟线程中执行 LLM 调用并限时等待。
     *
     * <p>切换线程前捕获并恢复 thread-local 上下文（MDC / OTel / 对话上下文，同 {@code ContextPropagator.wrap}）：
     * 否则被守卫的 chat/embedding 调用在无父上下文的 llm-guard 线程执行，Spring AI 自动观测会脱离当前 trace
     * 成为独立根 trace（Langfuse/OpenObserve 出现 chat、embedding 碎片 trace）。</p>
     *
     * @param supplier LLM 调用（阻塞）
     * @param timeout  总超时
     * @param what     调用用途（日志用，如“意图分类”）
     * @return LLM 返回内容
     * @throws Exception 调用异常或超时（TimeoutException）
     */
    public static <T> T call(Supplier<T> supplier, Duration timeout, String what) throws Exception {
        ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
        try {
            return CompletableFuture.supplyAsync(
                            () -> {
                                try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                                    return supplier.get();
                                }
                            },
                            EXECUTOR)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[LlmCallGuard] {} 超时({}ms)，交由调用方降级", what, timeout.toMillis());
            throw new TimeoutException(what + " 超时(" + timeout.toMillis() + "ms)");
        } catch (Exception e) {
            log.warn("[LlmCallGuard] {} 调用异常: {}", what, e.getMessage());
            throw e;
        }
    }
}
