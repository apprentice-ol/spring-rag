package com.agentframework.engine.view;

import com.agentframework.engine.core.Engine;
import com.agentframework.engine.policy.RegionMetrics;
import com.agentframework.runtime.persistence.CheckpointEntry;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.slot.Slots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话视图装配器：把引擎运行态组装成前端可直接渲染的文档。
 *
 * <p>默认只取最近 20 步，避免长会话把响应撑大。</p>
 */
public final class SessionViewFactory {

    /** 默认展示的最近步数。 */
    public static final int DEFAULT_RECENT_STEPS = 20;

    private SessionViewFactory() {
    }

    /**
     * @param engine  引擎
     * @param session 会话
     * @return 会话视图
     */
    public static SessionView of(Engine engine, Session session) {
        return of(engine, session, DEFAULT_RECENT_STEPS);
    }

    /**
     * @param engine      引擎
     * @param session     会话
     * @param recentSteps 展示的最近步数，≤0 表示不展示
     * @return 会话视图
     */
    public static SessionView of(Engine engine, Session session, int recentSteps) {
        Slots slots = engine.contexts().slots(session.id());
        Map<String, Object> slotValues = slots == null ? Map.of() : slots.asMap();
        Map<String, RegionMetricsView> metrics = new LinkedHashMap<>();
        engine.regionMetrics(session.id()).forEach((regionId, value) ->
                metrics.put(regionId, RegionMetricsView.of(value)));
        List<CheckpointSummary> steps = new ArrayList<>();
        if (recentSteps > 0) {
            List<CheckpointEntry> history = engine.history(session.id());
            int from = Math.max(0, history.size() - recentSteps);
            for (CheckpointEntry entry : history.subList(from, history.size())) {
                steps.add(CheckpointSummary.of(entry));
            }
        }
        Map<String, Integer> counters = session.cursor() == null ? Map.of() : session.cursor().loopCounters();
        return new SessionView(session.id(), session.agentId(), session.workflowId(), session.state().name(),
                session.cursor() == null ? null : session.cursor().nodeId(),
                session.cursor() == null ? 0 : (int) session.cursor().step(),
                session.cursor() == null ? null : session.cursor().lastEdge(),
                slotValues, metrics, counters, steps);
    }

    /**
     * @param metrics 区域指标
     * @return 视图
     */
    public static RegionMetricsView metrics(RegionMetrics metrics) {
        return RegionMetricsView.of(metrics);
    }
}
