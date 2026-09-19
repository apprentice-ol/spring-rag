package com.agentframework.crosscutting.interceptor;

import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;

/**
 * 跨线程任务包装钩子：把调用方线程的执行上下文搬进目标线程。
 *
 * <p><b>为什么需要它</b>：{@link Interceptors.Timeout} 把下游调用 {@code submit} 到一个新开的
 * 虚拟线程上执行，而新线程<b>不继承线程本地变量</b>——链路追踪上下文（OTel）、日志 MDC、
 * 会话环境全部原地丢失。后果不是"少几个 span"这么轻：节点里产生的 span 拿不到父上下文，
 * 只能各自成为根 trace，一次问答在链路追踪里断成互不相干的好几截。</p>
 *
 * <p><b>为什么是钩子而不是直接实现</b>：内核不依赖任何具体的可观测性实现（不引 OTel、不引日志门面）。
 * 这里只留一个恒等的包装点，由宿主在装配期注入自己的传播器，例如：</p>
 *
 * <pre>{@code
 * TaskPropagation.install(ContextPropagator::wrap);   // 包装时捕获、执行时恢复
 * }</pre>
 *
 * <p>未安装时行为与改造前完全一致（恒等包装），所以这是个零风险的扩展点。</p>
 */
public final class TaskPropagation {

    /** 缺省恒等：没有宿主注入时不改变任何行为。 */
    private static volatile UnaryOperator<Runnable> wrapper = UnaryOperator.identity();

    private TaskPropagation() {
    }

    /**
     * 注入包装器（传 null 恢复恒等）。
     *
     * @param taskWrapper 包装器：入参是待执行任务，返回"执行前先恢复上下文"的任务
     */
    public static void install(UnaryOperator<Runnable> taskWrapper) {
        wrapper = taskWrapper == null ? UnaryOperator.identity() : taskWrapper;
    }

    /**
     * @param task 待执行任务
     * @return 包装后的任务
     */
    public static Runnable wrap(Runnable task) {
        return wrapper.apply(task);
    }

    /**
     * {@link Callable} 版本：包装 Runnable 会丢掉返回值与异常，这里用一格中转把它们接回来。
     *
     * @param task 待执行任务
     * @param <T>  返回值类型
     * @return 包装后的任务（返回值与异常原样透出）
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        // 必须在这里就求值：包装器的语义是"此刻捕获上下文、执行时恢复"。若把 wrap(...) 写进
        // 下面返回的 lambda 里，捕获会被推迟到目标线程真正跑的时候，而那时上下文早就丢了——
        // 包了等于没包，而且失败得很安静（span 静默退化成根 trace）。
        Runnable wrapped = wrap(() -> {
            try {
                result[0] = task.call();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        return () -> {
            wrapped.run();
            if (failure[0] != null) {
                if (failure[0] instanceof Exception exception) {
                    throw exception;
                }
                throw new IllegalStateException(failure[0]);
            }
            @SuppressWarnings("unchecked")
            T value = (T) result[0];
            return value;
        };
    }
}
