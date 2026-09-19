package com.agentframework.extension.registry;

import java.util.List;
import java.util.Optional;

/**
 * 扩展注册表：内核与插件之间的唯一装配点。
 *
 * <p>内核只依赖接口：所有可替换实现（模型、工具、存储、守卫等）都通过这里解析。</p>
 */
public interface ExtensionRegistry {

    /**
     * 注册扩展点实现。
     *
     * @param type 扩展点类型（接口类）
     * @param id   注册名
     * @param impl 实现实例
     * @param <T>  扩展点类型
     */
    <T> void register(Class<T> type, String id, T impl);

    /**
     * 注册带元信息的扩展。
     *
     * @param type      扩展点类型
     * @param id        注册名
     * @param impl      实现实例
     * @param meta      元信息
     * @param <T>       扩展点类型
     */
    <T> void register(Class<T> type, String id, T impl, ExtensionMeta meta);

    /**
     * 注册一个 {@link Extension}，其类型取 {@code meta().type()} 对应的扩展点。
     *
     * @param extension 扩展实例
     */
    void registerExtension(Extension extension);

    /**
     * 解析扩展实现。
     *
     * @param type 扩展点类型
     * @param id   注册名
     * @param <T>  扩展点类型
     * @return 实现实例
     * @throws java.util.NoSuchElementException 未注册时抛出
     */
    <T> T resolve(Class<T> type, String id);

    /**
     * 尝试解析扩展实现。
     *
     * @param type 扩展点类型
     * @param id   注册名
     * @param <T>  扩展点类型
     * @return 实现实例，未注册时为空
     */
    <T> Optional<T> tryResolve(Class<T> type, String id);

    /**
     * 列出某扩展点的全部实现。
     *
     * @param type 扩展点类型
     * @param <T>  扩展点类型
     * @return 实现列表
     */
    <T> List<T> list(Class<T> type);

    /**
     * 列出某扩展点的全部注册名。
     *
     * @param type 扩展点类型
     * @return 注册名列表
     */
    List<String> ids(Class<?> type);

    /** @return 全部扩展元信息 */
    List<ExtensionMeta> metas();

    /**
     * 注销扩展。
     *
     * @param type 扩展点类型
     * @param id   注册名
     * @return 是否确实移除了注册项
     */
    boolean unregister(Class<?> type, String id);

    /**
     * 校验插件声明的 API 版本是否与内核兼容。
     *
     * @param requiredApiVersion 插件声明的版本
     * @throws IllegalStateException 不兼容时抛出
     */
    void versionCheck(String requiredApiVersion);

    /** @return 内核提供的扩展 API 版本 */
    ApiVersion apiVersion();

    /**
     * 释放全部扩展资源。
     *
     * <p>由引擎在关闭时调用；单个扩展释放失败不应影响其它扩展。</p>
     */
    void closeAll();
}
