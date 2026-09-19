package com.agentframework.definition.policy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具策略：决定 Agent <em>能调用什么</em>。
 *
 * <p>能力与决策分离——Tool 提供能力，Workflow 决定时机，本策略决定是否放行。
 * 判定顺序为：拒绝清单优先，其次允许清单，最后回落到 {@code denyByDefault}。</p>
 *
 * @param allowed          允许调用的工具 id 列表，包含 {@code *} 表示全部允许；为空时取决于 {@code denyByDefault}
 * @param denied           禁止调用的工具 id 列表，包含 {@code *} 表示全部禁止，优先级最高
 * @param approvalRequired 需要人工审批的工具 id 集合，包含 {@code *} 表示全部需要审批
 * @param denyByDefault    允许清单为空时是否默认拒绝
 */
public record ToolPolicy(List<String> allowed, List<String> denied, Set<String> approvalRequired, boolean denyByDefault) {

    public ToolPolicy {
        allowed = List.copyOf(allowed == null ? List.of() : allowed);
        denied = List.copyOf(denied == null ? List.of() : denied);
        approvalRequired = Set.copyOf(approvalRequired == null ? Set.of() : approvalRequired);
    }

    /** 允许全部工具且无需审批。@return 宽松策略 */
    public static ToolPolicy allowAll() {
        return new ToolPolicy(List.of(), List.of(), Set.of(), false);
    }

    /**
     * 仅允许列出的工具。
     *
     * @param tools 允许的工具 id
     * @return 白名单策略，其余工具一律拒绝
     */
    public static ToolPolicy only(String... tools) {
        return new ToolPolicy(List.of(tools), List.of(), Set.of(), true);
    }

    /** 全部拒绝。@return 拒绝一切工具调用的策略 */
    public static ToolPolicy denyAll() {
        return new ToolPolicy(List.of(), List.of("*"), Set.of(), true);
    }

    /**
     * 判断某个工具是否放行。
     *
     * @param toolId 工具 id，可为 null（null 视为拒绝）
     * @return 放行返回 true
     */
    public boolean allows(String toolId) {
        if (toolId == null) {
            return false;
        }
        if (denied.contains(toolId) || denied.contains("*")) {
            return false;
        }
        if (allowed.isEmpty()) {
            return !denyByDefault;
        }
        return allowed.contains(toolId) || allowed.contains("*");
    }

    /**
     * 判断某个工具是否需要人工审批。
     *
     * @param toolId 工具 id
     * @return 需要审批返回 true
     */
    public boolean requiresApproval(String toolId) {
        return approvalRequired.contains(toolId) || approvalRequired.contains("*");
    }

    /**
     * 追加拒绝项。
     *
     * @param tools 追加到拒绝清单的工具 id
     * @return 新的策略实例
     */
    public ToolPolicy withDenied(String... tools) {
        return new ToolPolicy(allowed, concat(denied, tools), approvalRequired, denyByDefault);
    }

    /**
     * 追加允许项，并切换为白名单模式。
     *
     * @param tools 追加到允许清单的工具 id
     * @return 新的策略实例
     */
    public ToolPolicy withAllowed(String... tools) {
        return new ToolPolicy(concat(allowed, tools), denied, approvalRequired, true);
    }

    /**
     * 追加需要审批的工具。
     *
     * @param tools 需要审批的工具 id
     * @return 新的策略实例
     */
    public ToolPolicy withApprovalRequired(String... tools) {
        Set<String> merged = new LinkedHashSet<>(approvalRequired);
        merged.addAll(List.of(tools));
        return new ToolPolicy(allowed, denied, merged, denyByDefault);
    }

    /** 合并两个字符串列表并去重。 */
    private static List<String> concat(List<String> base, String... extra) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(base);
        merged.addAll(List.of(extra));
        return List.copyOf(merged);
    }
}
