package com.agentframework.definition.node;

import java.util.List;

/**
 * 条件节点：纯路由步骤，按顺序求值各分支，命中即跳转。
 *
 * <p>表达式语法见 {@code com.agentframework.definition.workflow.Expression}。</p>
 *
 * @param id       节点 id
 * @param branches 分支列表，按声明顺序求值
 * @param meta     横切信息
 */
public record ConditionNodeDefinition(String id, List<Branch> branches, NodeMeta meta) implements NodeDefinition {

    /**
     * 单个分支。
     *
     * @param target     命中后跳转的节点 id
     * @param expression 判定表达式，为空表示默认分支
     */
    public record Branch(String target, String expression) {

        /**
         * @param target     目标节点 id
         * @param expression 判定表达式
         * @return 条件分支
         */
        public static Branch when(String target, String expression) {
            return new Branch(target, expression);
        }

        /**
         * @param target 目标节点 id
         * @return 默认分支（无表达式，必然命中）
         */
        public static Branch otherwise(String target) {
            return new Branch(target, null);
        }

        /** @return 是否为默认分支 */
        public boolean isDefault() {
            return expression == null || expression.isBlank();
        }
    }

    public ConditionNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("condition node id is required");
        }
        branches = List.copyOf(branches == null ? List.of() : branches);
        meta = meta == null ? NodeMeta.empty() : meta;
    }

    /**
     * @param id       节点 id
     * @param branches 分支列表
     * @return 条件节点定义
     */
    public static ConditionNodeDefinition of(String id, Branch... branches) {
        return new ConditionNodeDefinition(id, List.of(branches), null);
    }

    /**
     * @param id       节点 id
     * @param meta     横切信息（如守卫引用）
     * @param branches 分支列表
     * @return 带 meta 的条件节点定义
     */
    public static ConditionNodeDefinition of(String id, NodeMeta meta, Branch... branches) {
        return new ConditionNodeDefinition(id, List.of(branches), meta);
    }

    /**
     * 构造“命中跳一个目标、否则跳另一个目标”的条件节点。
     *
     * @param id         节点 id
     * @param expression 判定表达式
     * @param target     命中与未命中时的目标节点
     * @return 条件节点定义
     */
    public static ConditionNodeDefinition when(String id, String expression, String target) {
        return new ConditionNodeDefinition(id, List.of(new Branch(target, expression), Branch.otherwise(target)), null);
    }

    @Override
    public NodeType type() {
        return NodeType.CONDITION;
    }
}
