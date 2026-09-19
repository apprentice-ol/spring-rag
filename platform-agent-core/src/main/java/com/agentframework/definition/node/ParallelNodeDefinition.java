package com.agentframework.definition.node;

import java.util.List;

/**
 * 并行节点：扇出到多个分支节点，并把结果汇聚写入同一个槽位。
 *
 * @param id             节点 id
 * @param branches       分支节点 id 列表
 * @param maxConcurrency 最大并发度，≤0 表示按分支数量并发
 * @param joinStrategy   汇聚策略
 * @param outputSlot     汇聚结果写入的槽位名
 * @param meta           横切信息
 */
public record ParallelNodeDefinition(
        String id,
        List<String> branches,
        int maxConcurrency,
        JoinStrategy joinStrategy,
        String outputSlot,
        NodeMeta meta) implements NodeDefinition {

    /** 分支汇聚策略。 */
    public enum JoinStrategy {
        /** 等待全部分支完成。 */
        ALL,
        /** 任一分支成功即可返回。 */
        ANY,
        /** 取第一个成功的分支结果。 */
        FIRST
    }

    public ParallelNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("parallel node id is required");
        }
        branches = List.copyOf(branches == null ? List.of() : branches);
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("parallel node '" + id + "' needs at least one branch");
        }
        maxConcurrency = maxConcurrency <= 0 ? branches.size() : maxConcurrency;
        joinStrategy = joinStrategy == null ? JoinStrategy.ALL : joinStrategy;
        outputSlot = outputSlot == null || outputSlot.isBlank() ? "parallel_result" : outputSlot;
        meta = meta == null ? NodeMeta.empty() : meta;
    }

    /**
     * @param id         节点 id
     * @param outputSlot 输出槽位名
     * @param branches   分支节点 id
     * @return 并行节点定义
     */
    public static ParallelNodeDefinition of(String id, String outputSlot, String... branches) {
        return new ParallelNodeDefinition(id, List.of(branches), 0, null, outputSlot, null);
    }

    @Override
    public NodeType type() {
        return NodeType.PARALLEL;
    }

    /**
     * @param maxConcurrency 最大并发度
     * @return 覆盖并发度后的节点定义
     */
    public ParallelNodeDefinition withMaxConcurrency(int maxConcurrency) {
        return new ParallelNodeDefinition(id, branches, maxConcurrency, joinStrategy, outputSlot, meta);
    }

    /**
     * @param strategy 汇聚策略
     * @return 覆盖汇聚策略后的节点定义
     */
    public ParallelNodeDefinition withJoinStrategy(JoinStrategy strategy) {
        return new ParallelNodeDefinition(id, branches, maxConcurrency, strategy, outputSlot, meta);
    }
}
