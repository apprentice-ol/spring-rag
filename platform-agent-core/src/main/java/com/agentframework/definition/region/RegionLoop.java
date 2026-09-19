package com.agentframework.definition.region;

/**
 * Region 级循环声明：把 Region 内部的回边显式化。
 *
 * <p>循环标识就是 Region id，因此同一个 Region 最多声明一个循环；计数只发生在入口节点，
 * 循环守卫按循环标识读取计数，进而实现「按循环而不是按节点」的迭代上限。</p>
 *
 * @param entry         循环入口节点，每次进入都计一次迭代
 * @param exit          循环出口节点，通常在此判定是否继续
 * @param maxIterations 迭代上限，≤0 表示不限制
 * @param counterSlot   可选的计数回写槽位，便于表达式直接引用
 * @param convergence   可选的收敛判定
 */
public record RegionLoop(String entry, String exit, int maxIterations, String counterSlot,
        LoopConvergence convergence) {

    public RegionLoop {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("loop entry is required");
        }
        if (exit == null || exit.isBlank()) {
            throw new IllegalArgumentException("loop exit is required");
        }
        counterSlot = counterSlot == null || counterSlot.isBlank() ? null : counterSlot;
    }

    /**
     * @param entry         循环入口节点
     * @param exit          循环出口节点
     * @param maxIterations 迭代上限
     * @return 循环声明
     */
    public static RegionLoop of(String entry, String exit, int maxIterations) {
        return new RegionLoop(entry, exit, maxIterations, null, null);
    }

    /**
     * @param slot 计数回写槽位
     * @return 追加槽位后的循环声明
     */
    public RegionLoop withCounterSlot(String slot) {
        return new RegionLoop(entry, exit, maxIterations, slot, convergence);
    }

    /**
     * @param convergence 收敛判定
     * @return 追加收敛判定后的循环声明
     */
    public RegionLoop withConvergence(LoopConvergence convergence) {
        return new RegionLoop(entry, exit, maxIterations, counterSlot, convergence);
    }

    /** @return 是否声明了计数槽位 */
    public boolean hasCounterSlot() {
        return counterSlot != null;
    }

    /** @return 是否为不限制迭代 */
    public boolean unlimited() {
        return maxIterations <= 0;
    }

    /** @return 是否声明了收敛判定 */
    public boolean hasConvergence() {
        return convergence != null;
    }
}
