package com.agentframework.definition.region;

/**
 * 循环收敛判定：相邻两次迭代的槽位值变化小于阈值即视为收敛。
 *
 * <p>取值支持数字与数字字符串；不存在的槽位视为未声明收敛条件。</p>
 *
 * @param slot     观察的槽位名，通常是反思区域产出的质量分或假设内容
 * @param minDelta 最小改进量，小于该值判定为收敛，必须为正数
 */
public record LoopConvergence(String slot, double minDelta) {

    public LoopConvergence {
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("convergence slot is required");
        }
        if (minDelta <= 0) {
            throw new IllegalArgumentException("convergence minDelta must be positive");
        }
    }

    /**
     * @param slot     观察的槽位名
     * @param minDelta 最小改进量
     * @return 收敛判定
     */
    public static LoopConvergence of(String slot, double minDelta) {
        return new LoopConvergence(slot, minDelta);
    }
}
