package com.agentframework.definition.region;

import java.util.List;

/**
 * Region 推断建议：只读产物。
 *
 * <p>建议不进入作用域链、不产生任何策略、不改变执行路径，只用于校验报告与可视化。</p>
 *
 * @param id         建议 id
 * @param paradigm   建议的范式
 * @param nodeIds    建议覆盖的节点
 * @param reason     推断依据
 * @param confidence 置信度，0 到 1
 */
public record RegionSuggestion(
        String id,
        Paradigm paradigm,
        List<String> nodeIds,
        String reason,
        double confidence) {

    public RegionSuggestion {
        nodeIds = List.copyOf(nodeIds == null ? List.of() : nodeIds);
        reason = reason == null ? "" : reason;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}
