package com.agentframework.engine.scheduling;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 默认调度器：基于虚拟线程的定时执行。
 *
 * <p>任务异常会被捕获，避免单个任务失败导致调度线程退出。</p>
 */
public final class DefaultScheduler implements Scheduler {

    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(2, Thread.ofVirtual().name("agent-scheduler-", 0).factory());
    private final Map<String, Future<?>> handles = new ConcurrentHashMap<>();

    @Override
    public ScheduledHandle schedule(String name, Duration initialDelay, Duration period, Runnable task) {
        long delayMillis = initialDelay == null ? 0 : Math.max(0, initialDelay.toMillis());
        long periodMillis = period == null ? 1000 : Math.max(1, period.toMillis());
        Future<?> future = executor.scheduleAtFixedRate(guarded(task), delayMillis, periodMillis,
                TimeUnit.MILLISECONDS);
        return register(name, future);
    }

    @Override
    public ScheduledHandle submit(String name, Runnable task) {
        return register(name, executor.submit(guarded(task)));
    }

    @Override
    public ScheduledHandle submit(String name, Duration delay, Runnable task) {
        long delayMillis = delay == null ? 0 : Math.max(0, delay.toMillis());
        return register(name, executor.schedule(guarded(task), delayMillis, TimeUnit.MILLISECONDS));
    }

    @Override
    public void close() {
        handles.values().forEach(future -> future.cancel(true));
        handles.clear();
        executor.shutdownNow();
    }

    /** @return 当前登记的任务名 */
    public List<String> taskNames() {
        return List.copyOf(handles.keySet());
    }

    /**
     * 登记任务句柄。
     *
     * @param name   任务名
     * @param future 底层 future
     * @return 任务句柄
     */
    private ScheduledHandle register(String name, Future<?> future) {
        String key = name == null ? "task" : name;
        handles.put(key, future);
        return new ScheduledHandle() {
            @Override
            public String name() {
                return key;
            }

            @Override
            public boolean cancelled() {
                return future.isCancelled() || future.isDone();
            }

            @Override
            public void cancel() {
                future.cancel(true);
                handles.remove(key);
            }
        };
    }

    /** @return 包裹异常隔离后的任务 */
    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ignored) {
                // 单个任务异常不应影响调度线程与其它任务
            }
        };
    }
}
