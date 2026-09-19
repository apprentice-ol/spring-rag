package com.agentframework.engine.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 组件引用：名字 + 可选参数。
 *
 * <p>参数只对参数化组件（{@link PolicyFactory}）有意义；同名引用在作用域链上多次出现时，
 * 最靠近执行单元的参数生效。</p>
 *
 * @param name   组件名
 * @param params 参数表
 */
public record PolicyRef(String name, Map<String, Object> params) {

    public PolicyRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("policy ref name is required");
        }
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /**
     * @param name 组件名
     * @return 无参数引用
     */
    public static PolicyRef of(String name) {
        return new PolicyRef(name, null);
    }

    /**
     * @param name   组件名
     * @param params 参数表
     * @return 带参数引用
     */
    public static PolicyRef of(String name, Map<String, Object> params) {
        return new PolicyRef(name, params);
    }

    /** @return 是否携带参数 */
    public boolean hasParams() {
        return !params.isEmpty();
    }
}
