package com.agentframework.engine.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话视图：前端展示运行态所需的游标、槽位、区域指标与最近步。
 *
 * @param sessionId     会话 id
 * @param agentId       Agent id
 * @param workflowId    工作流 id
 * @param state         会话状态
 * @param currentNode   当前节点
 * @param step          当前步号
 * @param lastEdge      最近经过的边，可为 null
 * @param slots         槽位快照
 * @param regionMetrics 区域指标
 * @param loopCounters  循环计数
 * @param recentSteps   最近若干步的检查点摘要
 */
public record SessionView(
        String sessionId,
        String agentId,
        String workflowId,
        String state,
        String currentNode,
        int step,
        String lastEdge,
        Map<String, Object> slots,
        Map<String, RegionMetricsView> regionMetrics,
        Map<String, Integer> loopCounters,
        List<CheckpointSummary> recentSteps) {

    public SessionView {
        // 槽位值允许为 null，不能用 Map.copyOf
        slots = slots == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        regionMetrics = regionMetrics == null ? Map.of() : Map.copyOf(regionMetrics);
        loopCounters = loopCounters == null ? Map.of() : Map.copyOf(loopCounters);
        recentSteps = List.copyOf(recentSteps == null ? List.of() : recentSteps);
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("sessionId", sessionId);
        document.put("agentId", agentId);
        document.put("workflowId", workflowId);
        document.put("state", state);
        document.put("currentNode", currentNode);
        document.put("step", step);
        document.put("lastEdge", lastEdge);
        document.put("slots", slots);
        Map<String, Object> metrics = new LinkedHashMap<>();
        regionMetrics.forEach((key, value) -> metrics.put(key, value.toDocument()));
        document.put("regionMetrics", metrics);
        document.put("loopCounters", loopCounters);
        document.put("recentSteps", recentSteps.stream().map(CheckpointSummary::toDocument).toList());
        return document;
    }
}
