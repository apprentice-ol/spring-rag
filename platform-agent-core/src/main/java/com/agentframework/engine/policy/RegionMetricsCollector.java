package com.agentframework.engine.policy;

import com.agentframework.definition.node.NodeType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region 指标采集器：按 {@code (sessionId, regionId)} 原子累加，可被并行分支安全写入。
 *
 * <p>采集只在节点属于显式 Region 时发生；未声明 Region 的工作流零开销。</p>
 */
public final class RegionMetricsCollector {

    private final Map<String, Accumulator> accumulators = new ConcurrentHashMap<>();

    /**
     * 记录一次节点执行。
     *
     * @param sessionId 会话 id
     * @param policy    节点解析结果，未归属 Region 时忽略
     * @param type      节点类型
     * @param durationMs 本次耗时
     * @param tokens    本次 token 用量
     * @param iterations 该节点所属循环的当前迭代数，0 表示不在显式循环中
     * @param suspended 本次是否为挂起（人工节点等待输入）
     */
    public void record(String sessionId, ResolvedPolicy policy, NodeType type, long durationMs, long tokens,
            int iterations, boolean suspended) {
        if (sessionId == null || policy == null || !policy.inRegion()) {
            return;
        }
        Accumulator accumulator = accumulators.computeIfAbsent(key(sessionId, policy.regionId()),
                ignored -> new Accumulator(policy.regionId(), policy.paradigm()));
        accumulator.add(type, durationMs, tokens, iterations, suspended);
    }

    /**
     * @param sessionId 会话 id
     * @return 区域 id 到指标的映射，无数据时为空表
     */
    public Map<String, RegionMetrics> metrics(String sessionId) {
        Map<String, RegionMetrics> result = new LinkedHashMap<>();
        String prefix = sessionId + "::";
        accumulators.forEach((key, accumulator) -> {
            if (key.startsWith(prefix)) {
                result.put(accumulator.regionId, accumulator.snapshot());
            }
        });
        return Map.copyOf(result);
    }

    /**
     * @param sessionId 会话 id
     * @return 全部区域的聚合视图，按区域 id 排序
     */
    public Map<String, RegionMetrics> metrics(String sessionId, String regionId) {
        return metrics(sessionId).entrySet().stream()
                .filter(entry -> entry.getKey().equals(regionId))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * 清理会话指标。
     *
     * @param sessionId 会话 id
     */
    public void clear(String sessionId) {
        String prefix = sessionId + "::";
        accumulators.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** @return 当前跟踪的会话数量 */
    public int trackedSessions() {
        return (int) accumulators.keySet().stream().map(key -> key.substring(0, key.indexOf("::"))).distinct().count();
    }

    /**
     * @param sessionId 会话 id
     * @param regionId  区域 id
     * @return 累加键
     */
    private String key(String sessionId, String regionId) {
        return sessionId + "::" + regionId;
    }

    /** 单个区域的原子累加器。 */
    private static final class Accumulator {

        private final String regionId;
        private final String paradigm;
        private final AtomicLong executionTimeMs = new AtomicLong();
        private final AtomicInteger nodeCount = new AtomicInteger();
        private final AtomicInteger llmCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicLong tokenCount = new AtomicLong();
        private final AtomicInteger approvalCount = new AtomicInteger();
        private final AtomicInteger approvalPendingCount = new AtomicInteger();
        private final AtomicInteger iterationCount = new AtomicInteger();

        Accumulator(String regionId, String paradigm) {
            this.regionId = regionId;
            this.paradigm = paradigm;
        }

        void add(NodeType type, long durationMs, long tokens, int iterations, boolean suspended) {
            executionTimeMs.addAndGet(Math.max(0, durationMs));
            tokenCount.addAndGet(Math.max(0, tokens));
            if (iterations > 0) {
                iterationCount.accumulateAndGet(iterations, Math::max);
            }
            if (suspended) {
                approvalPendingCount.incrementAndGet();
            }
            if (type == null) {
                return;
            }
            switch (type) {
                case LLM -> llmCalls.incrementAndGet();
                case TOOL -> toolCalls.incrementAndGet();
                case HUMAN -> approvalCount.incrementAndGet();
                default -> {
                    // 其余节点类型只计入 nodeCount
                }
            }
            nodeCount.incrementAndGet();
        }

        RegionMetrics snapshot() {
            return new RegionMetrics(regionId, paradigm, executionTimeMs.get(), nodeCount.get(), llmCalls.get(),
                    toolCalls.get(), tokenCount.get(), iterationCount.get(), approvalCount.get(),
                    approvalPendingCount.get());
        }
    }
}
