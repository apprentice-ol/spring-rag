package com.agentframework.engine.policy;

import java.util.Map;

/**
 * 参数化组件工厂：按作用域参数创建实例。
 *
 * <p>有状态或需要按作用域差异化配置的组件应通过工厂注册；工厂返回的实例按
 * {@code (类别, 名字, 参数)} 缓存复用，保证同一作用域解析结果的稳定性与线程安全。</p>
 */
@FunctionalInterface
public interface PolicyFactory {

    /**
     * @param params 引用参数，非空
     * @return 组件实例
     */
    Object create(Map<String, Object> params);
}
