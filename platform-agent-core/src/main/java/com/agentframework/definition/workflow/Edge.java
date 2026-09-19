package com.agentframework.definition.workflow;

/**
 * 节点之间的有向迁移。
 *
 * <p>条件边只在表达式为真时被选中；边上的守卫可以独立于条件否决本次迁移。</p>
 *
 * @param from      起点节点 id
 * @param to        终点节点 id
 * @param condition 迁移条件表达式，为空表示无条件
 * @param guardRef  迁移守卫名称，为空表示不校验
 * @param priority  优先级，数值越大越先被考虑
 * @param label     可读标签，便于可视化
 */
public record Edge(String from, String to, String condition, String guardRef, int priority, String label) {

    public Edge {
        if (from == null || to == null) {
            throw new IllegalArgumentException("edge endpoints are required");
        }
    }

    /**
     * @param from 起点节点 id
     * @param to   终点节点 id
     * @return 无条件边
     */
    public static Edge of(String from, String to) {
        return new Edge(from, to, null, null, 0, null);
    }

    /**
     * @param from       起点节点 id
     * @param to         终点节点 id
     * @param expression 迁移条件表达式
     * @return 条件边
     */
    public static Edge conditional(String from, String to, String expression) {
        return new Edge(from, to, expression, null, 0, null);
    }

    /** @return 是否带条件 */
    public boolean isConditional() {
        return condition != null && !condition.isBlank();
    }

    /** @return 是否挂载了守卫 */
    public boolean isGuarded() {
        return guardRef != null && !guardRef.isBlank();
    }

    /**
     * @param priority 优先级
     * @return 覆盖优先级后的边
     */
    public Edge withPriority(int priority) {
        return new Edge(from, to, condition, guardRef, priority, label);
    }

    /**
     * @param guardRef 守卫名称
     * @return 挂载守卫后的边
     */
    public Edge withGuard(String guardRef) {
        return new Edge(from, to, condition, guardRef, priority, label);
    }

    /**
     * @param label 可读标签
     * @return 覆盖标签后的边
     */
    public Edge withLabel(String label) {
        return new Edge(from, to, condition, guardRef, priority, label);
    }

    /** @return 边的唯一键，形如 {@code a->b} */
    public String key() {
        return from + "->" + to;
    }
}
