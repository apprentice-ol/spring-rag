package com.agentframework.engine.scheduling;

import java.time.Duration;

/**
 * 调度器扩展点：定时任务与延迟恢复。
 */
public interface Scheduler extends AutoCloseable {

    /**
     * 注册周期任务。
     *
     * @param name         任务名
     * @param initialDelay 首次延迟
     * @param period       执行周期
     * @param task         任务体
     * @return 任务句柄
     */
    ScheduledHandle schedule(String name, Duration initialDelay, Duration period, Runnable task);

    /**
     * 提交一次性任务。
     *
     * @param name 任务名
     * @param task 任务体
     * @return 任务句柄
     */
    ScheduledHandle submit(String name, Runnable task);

    /**
     * 提交延迟一次性任务。
     *
     * @param name  任务名
     * @param delay 延迟
     * @param task  任务体
     * @return 任务句柄
     */
    ScheduledHandle submit(String name, Duration delay, Runnable task);

    @Override
    void close();

    /** 任务句柄。 */
    interface ScheduledHandle extends AutoCloseable {

        /** @return 任务名 */
        String name();

        /** @return 是否已取消 */
        boolean cancelled();

        /** 取消任务。 */
        void cancel();

        @Override
        default void close() {
            cancel();
        }
    }
}
