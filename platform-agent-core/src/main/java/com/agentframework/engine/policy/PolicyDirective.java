package com.agentframework.engine.policy;

import com.agentframework.definition.policy.MergeMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规范化后的策略指令：解析器的唯一输入。
 *
 * <p>与定义层 record 的区别在于三态表达：空列表表示“未声明”，而不是“显式为空”。</p>
 *
 * @param kind    组件类别
 * @param enable  显式启用的引用，名字 {@code *} 表示同类别全部组件
 * @param disable 显式禁用的名字，{@code *} 表示同类别全部非强制组件
 * @param mode    与继承集合的合并模式
 * @param flags   三态开关，例如 {@code trace/cache/metrics}
 */
public record PolicyDirective(
        PolicyKind kind,
        List<PolicyRef> enable,
        List<String> disable,
        MergeMode mode,
        Map<String, Tri> flags) {

    public PolicyDirective {
        kind = kind == null ? PolicyKind.GUARD : kind;
        enable = List.copyOf(enable == null ? List.of() : enable);
        disable = List.copyOf(disable == null ? List.of() : disable);
        mode = mode == null ? MergeMode.ADD : mode;
        flags = flags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(flags));
    }

    /**
     * @param kind 组件类别
     * @return 空指令
     */
    public static PolicyDirective empty(PolicyKind kind) {
        return new PolicyDirective(kind, null, null, null, null);
    }

    /**
     * @param kind    组件类别
     * @param enable  启用的引用
     * @param disable 禁用的名字
     * @param mode    合并模式
     * @return 策略指令
     */
    public static PolicyDirective of(PolicyKind kind, List<PolicyRef> enable, List<String> disable, MergeMode mode) {
        return new PolicyDirective(kind, enable, disable, mode, null);
    }

    /**
     * @param kind  组件类别
     * @param enable 启用的引用
     * @return 追加模式的策略指令
     */
    public static PolicyDirective enabling(PolicyKind kind, List<PolicyRef> enable) {
        return new PolicyDirective(kind, enable, null, MergeMode.ADD, null);
    }

    /** @return 是否不携带任何有效声明 */
    public boolean isEmpty() {
        return enable.isEmpty() && disable.isEmpty() && flags.isEmpty();
    }
}
