package com.agentframework.extension.sandbox;

import com.agentframework.extension.manifest.Isolation;
import java.util.function.Function;

/**
 * 内置沙箱实现集合。
 *
 * <p>内核只保证进程内沙箱可用：插件默认在宿主进程内执行，权限与配额由
 * {@code PermissionSet} / {@code Quota} 约束。PROCESS / CONTAINER / WASM 需要由部署方
 * 提供实现并注册到扩展点，避免框架悄悄给出不安全或不可移植的“伪隔离”。</p>
 */
public final class Sandboxes {

    private Sandboxes() {
    }

    /**
     * 进程内沙箱：直接调用宿主提供的执行函数。
     *
     * @param executor 实际执行逻辑
     * @return 沙箱实现
     */
    public static Sandbox inProcess(Function<SandboxRequest, Object> executor) {
        return new Sandbox() {
            @Override
            public Isolation level() {
                return Isolation.NONE;
            }

            @Override
            public SandboxResult execute(SandboxRequest request) {
                long start = System.nanoTime();
                try {
                    Object output = executor.apply(request);
                    return SandboxResult.ok(output, elapsed(start));
                } catch (RuntimeException e) {
                    return SandboxResult.failed(e.getMessage(), elapsed(start));
                }
            }
        };
    }

    /** @return 默认进程内沙箱：直接回显请求载荷 */
    public static Sandbox echo() {
        return inProcess(request -> request.payload());
    }

    /**
     * 未实现隔离级别的占位实现。
     *
     * @param isolation 隔离级别
     * @return 调用即抛异常的沙箱
     */
    public static Sandbox unsupported(Isolation isolation) {
        return new Sandbox() {
            @Override
            public Isolation level() {
                return isolation;
            }

            @Override
            public SandboxResult execute(SandboxRequest request) {
                throw new SandboxNotSupportedException(isolation);
            }
        };
    }

    /** @return 耗时毫秒 */
    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
