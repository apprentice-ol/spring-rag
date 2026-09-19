package com.agentframework.engine.policy;

import java.util.Map;

/**
 * 目录条目：一个可被引用的组件。
 *
 * @param kind       组件类别
 * @param name       注册名
 * @param activation 激活语义
 * @param instance   单例实现，与 {@code factory} 二选一
 * @param factory    参数化工厂，与 {@code instance} 二选一
 */
public record PolicyComponent(
        PolicyKind kind,
        String name,
        Activation activation,
        Object instance,
        PolicyFactory factory) {

    public PolicyComponent {
        if (kind == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("policy component kind and name are required");
        }
        activation = activation == null ? Activation.DEFAULT_OFF : activation;
        if (instance == null && factory == null) {
            throw new IllegalArgumentException("policy component '" + name + "' needs an instance or a factory");
        }
    }

    /**
     * @param params 引用参数
     * @return 组件实例
     */
    public Object create(Map<String, Object> params) {
        return factory == null ? instance : factory.create(params == null ? Map.of() : params);
    }

    /** @return 是否为强制组件 */
    public boolean mandatory() {
        return activation == Activation.MANDATORY;
    }

    /** @return 是否默认生效 */
    public boolean defaultOn() {
        return activation != Activation.DEFAULT_OFF;
    }
}
