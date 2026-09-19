package com.agentframework.runtime.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行游标：一次执行的可持久化位置。
 *
 * <p>持久化 cursor 是断点续跑的基础——新进程可以从中断处继续执行同一个会话。</p>
 *
 * @param nodeId   当前（或下一个）节点 id
 * @param step     已执行步数
 * @param lastEdge 进入当前节点所经过的边，便于审计与回放
 * @param state    游标级附加状态
 */
public record Cursor(String nodeId, long step, String lastEdge, Map<String, Object> state) {

    /** 循环计数在游标状态中的键。 */
    public static final String LOOP_COUNTERS = "loopCounters";

    public Cursor {
        state = state == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    /**
     * @param nodeId 起始节点 id
     * @return 指向该节点、步数为 0 的游标
     */
    public static Cursor at(String nodeId) {
        return new Cursor(nodeId, 0, null, null);
    }

    /** @return 尚未开始的空游标 */
    public static Cursor initial() {
        return new Cursor(null, 0, null, null);
    }

    /**
     * 前进到指定节点，步数加一。
     *
     * @param nodeId 目标节点 id
     * @return 新的游标
     */
    public Cursor advanceTo(String nodeId) {
        return new Cursor(nodeId, step + 1, null, state);
    }

    /**
     * 记录本次迁移经过的边，步数不变。
     *
     * @param edgeKey 边的唯一键，形如 {@code a->b}
     * @return 新的游标
     */
    public Cursor arriveVia(String edgeKey) {
        return new Cursor(nodeId, step, edgeKey, state);
    }

    /**
     * @param key   状态键
     * @param value 状态值
     * @return 追加状态后的游标
     */
    public Cursor withState(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(state);
        merged.put(key, value);
        return new Cursor(nodeId, step, lastEdge, merged);
    }

    /** @return 是否已指向某个节点 */
    public boolean started() {
        return nodeId != null;
    }

    /**
     * @return 节点 id 到累计执行次数的只读视图
     */
    public Map<String, Integer> loopCounters() {
        Object raw = state.get(LOOP_COUNTERS);
        if (!(raw instanceof Map<?, ?> counters)) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        counters.forEach((key, value) -> {
            if (key instanceof String text && value instanceof Number number) {
                result.put(text, number.intValue());
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * @param key   计数键，通常为节点 id
     * @param value 累计次数
     * @return 写入计数后的游标
     */
    public Cursor withLoopCounter(String key, int value) {
        LinkedHashMap<String, Integer> counters = new LinkedHashMap<>(loopCounters());
        counters.put(key, value);
        return withState(LOOP_COUNTERS, Collections.unmodifiableMap(counters));
    }
}
