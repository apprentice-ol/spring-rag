package com.jjx.customer.platform.eval.config;

import com.jjx.customer.platform.common.exception.ClientException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 评测 run 级并发治理：全局同时运行的 run 数上限 + 受管关闭。
 *
 * <p>替代此前「无界虚拟线程池 + 永不关闭」的模式：
 * <ul>
 *   <li><b>fail-fast</b>：{@link Semaphore#tryAcquire} 拿不到许可同步报错「已有 N 个评测运行中」——
 *       排队方案会让 run 先 insert 且状态 RUNNING 再慢慢等，前端轮询永远 0/N，用户无从得知在排队</li>
 *   <li><b>全局保护</b>：此前 run 内 Semaphore(8) 是每 run 各一份，连点 10 次 trigger 就是 80 路
 *       LLM/embedding 并发打穿下游；上限在 run 级收口后总并发恒 ≤ maxConcurrentRuns × concurrency</li>
 *   <li><b>优雅停机</b>：close() 等待在途 run 完成或超时，替代「停机硬杀、run 永久 RUNNING」</li>
 * </ul></p>
 */
@Slf4j
@Component
public class EvalConcurrencyGuard {

    private final EvalProperties properties;
    private final Semaphore activeRuns;

    /** run 执行器（虚拟线程；停机时限时等待在途任务） */
    private final ExecutorService runExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("eval-run-", 0).factory());

    public EvalConcurrencyGuard(EvalProperties properties) {
        this.properties = properties;
        this.activeRuns = new Semaphore(properties.getMaxConcurrentRuns());
    }

    /**
     * 提交一个 run（占用一个全局许可，执行完释放）。
     *
     * @throws ClientException 已有 maxConcurrentRuns 个 run 在跑
     */
    public void submitRun(Runnable task) {
        if (!activeRuns.tryAcquire()) {
            throw new ClientException("已有 " + properties.getMaxConcurrentRuns()
                    + " 个评测运行中，请等待完成后再触发（可调 rag.eval.max-concurrent-runs）");
        }
        boolean submitted = false;
        try {
            runExecutor.execute(() -> {
                try {
                    task.run();
                } finally {
                    activeRuns.release();
                }
            });
            submitted = true;
        } finally {
            // execute 抛错（如已停机）时归还许可
            if (!submitted) {
                activeRuns.release();
            }
        }
    }

    /** 当前活跃 run 数（监控/日志用） */
    public int activeRuns() {
        return properties.getMaxConcurrentRuns() - activeRuns.availablePermits();
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        log.info("[EvalConcurrencyGuard] 停机：等待在途评测 run 最多 60s（活跃 {}）", activeRuns());
        runExecutor.shutdown();
        if (!runExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
            log.warn("[EvalConcurrencyGuard] 60s 后仍有在途评测 run，强制停机（run 可能停留在 RUNNING，可 retryRun 兜底）");
        }
    }
}
