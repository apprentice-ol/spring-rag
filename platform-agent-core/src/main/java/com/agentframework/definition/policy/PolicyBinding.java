package com.agentframework.definition.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 策略绑定：组件名 + 可选参数，供 Region 等作用域声明参数化组件。
 *
 * @param name   组件名
 * @param params 参数表
 */
public record PolicyBinding(String name, Map<String, Object> params) {

    public PolicyBinding {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("policy binding name is required");
        }
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /**
     * @param name 组件名
     * @return 无参数绑定
     */
    public static PolicyBinding of(String name) {
        return new PolicyBinding(name, null);
    }

    /**
     * @param name   组件名
     * @param params 参数表
     * @return 带参数绑定
     */
    public static PolicyBinding of(String name, Map<String, Object> params) {
        return new PolicyBinding(name, params);
    }

    /** @return 是否携带参数 */
    public boolean hasParams() {
        return !params.isEmpty();
    }
}
