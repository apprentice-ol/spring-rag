package com.agentframework.definition.view;

import com.agentframework.definition.region.RegionLoop;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 循环视图：供前端画循环标注。
 *
 * @param entry         入口节点
 * @param exit          出口节点
 * @param maxIterations 迭代上限
 * @param counterSlot   计数槽位，可为 null
 * @param convergenceSlot 收敛观察槽位，可为 null
 * @param minDelta      收敛阈值，可为 null
 */
public record LoopView(
        String entry,
        String exit,
        int maxIterations,
        String counterSlot,
        String convergenceSlot,
        Double minDelta) {

    /**
     * @param loop 循环声明
     * @return 视图
     */
    public static LoopView of(RegionLoop loop) {
        if (loop == null) {
            return null;
        }
        return new LoopView(loop.entry(), loop.exit(), loop.maxIterations(), loop.counterSlot(),
                loop.hasConvergence() ? loop.convergence().slot() : null,
                loop.hasConvergence() ? loop.convergence().minDelta() : null);
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("entry", entry);
        document.put("exit", exit);
        document.put("maxIterations", maxIterations);
        document.put("counterSlot", counterSlot);
        document.put("convergenceSlot", convergenceSlot);
        document.put("minDelta", minDelta);
        return document;
    }
}
