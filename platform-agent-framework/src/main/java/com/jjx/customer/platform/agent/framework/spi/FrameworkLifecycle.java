package com.jjx.customer.platform.agent.framework.spi;

/**
 * 框架生命周期（Spring {@code Lifecycle} 范式）：装配完成后 {@link #start()}，停机前 {@link #stop()}。
 *
 * <p>启动 = 冻结注册表 / 预热端口 / 打开后端；停止 = 冲刷（flush）挂起数据、释放资源。
 * 生命周期事件会转发给所有 {@link ExecutionListener}（见 {@code onEngineStart/onEngineStop}）。</p>
 */
public interface FrameworkLifecycle {

    void start();

    void stop();

    boolean isRunning();
}
