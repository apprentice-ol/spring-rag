package com.agentframework.extension.registry;

/**
 * 扩展统一入口：所有插件提供的扩展点实现都应实现该接口。
 *
 * <p>生命周期由引擎托管：注册 → {@link #init(ExtensionContext)} → 使用 → {@link #close()}。</p>
 */
public interface Extension {

    /** @return 扩展元信息 */
    ExtensionMeta meta();

    /**
     * 初始化扩展。
     *
     * @param context 扩展上下文
     */
    default void init(ExtensionContext context) {
    }

    /** 释放资源。 */
    default void close() {
    }
}
