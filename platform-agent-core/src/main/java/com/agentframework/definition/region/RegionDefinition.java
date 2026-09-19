package com.agentframework.definition.region;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Region 定义：显式声明的治理锚点。
 *
 * <p>Region 不参与路由：它只决定区域内节点继承哪些治理策略，以及指标与 Trace 如何归类。</p>
 *
 * @param id       区域 id
 * @param paradigm 范式标签
 * @param nodeIds  区域包含的节点 id
 * @param policy   治理策略
 * @param loop     循环声明，null 表示区域内没有显式循环
 * @param slotPrefix Region 局部槽位前缀，null 表示不启用；区域内节点写入
 *                   {@code SlotScope.REGION} 模板短名时展开为 {@code {prefix}_{name}}
 * @param metadata 自定义元数据
 */
public record RegionDefinition(
        String id,
        Paradigm paradigm,
        List<String> nodeIds,
        RegionPolicy policy,
        RegionLoop loop,
        String slotPrefix,
        Map<String, Object> metadata) {

    public RegionDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("region id is required");
        }
        paradigm = paradigm == null ? Paradigm.CUSTOM : paradigm;
        nodeIds = List.copyOf(nodeIds == null ? List.of() : nodeIds);
        policy = policy == null ? RegionPolicy.EMPTY : policy;
        slotPrefix = slotPrefix == null || slotPrefix.isBlank() ? null : slotPrefix;
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * @param id       区域 id
     * @param paradigm 范式
     * @param nodeIds  节点 id
     * @return 无治理策略的区域
     */
    public static RegionDefinition of(String id, Paradigm paradigm, String... nodeIds) {
        return new RegionDefinition(id, paradigm, List.of(nodeIds), null, null, null, null);
    }

    /**
     * @param policy 治理策略
     * @return 覆盖策略后的区域
     */
    public RegionDefinition withPolicy(RegionPolicy policy) {
        return new RegionDefinition(id, paradigm, nodeIds, policy, loop, slotPrefix, metadata);
    }

    /**
     * @param loop 循环声明
     * @return 追加循环后的区域
     */
    public RegionDefinition withLoop(RegionLoop loop) {
        return new RegionDefinition(id, paradigm, nodeIds, policy, loop, slotPrefix, metadata);
    }

    /**
     * @param slotPrefix Region 局部槽位前缀
     * @return 启用局部槽位展开后的区域
     */
    public RegionDefinition withSlotPrefix(String slotPrefix) {
        return new RegionDefinition(id, paradigm, nodeIds, policy, loop, slotPrefix, metadata);
    }

    /**
     * @param key   元数据键
     * @param value 元数据值
     * @return 追加元数据后的区域
     */
    public RegionDefinition withMetadata(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        return new RegionDefinition(id, paradigm, nodeIds, policy, loop, slotPrefix, merged);
    }

    /** @return 是否包含任何节点 */
    public boolean isEmpty() {
        return nodeIds.isEmpty();
    }

    /** @return 是否声明了循环 */
    public boolean hasLoop() {
        return loop != null;
    }

    /** @return 是否启用了局部槽位前缀 */
    public boolean hasSlotPrefix() {
        return slotPrefix != null;
    }
}
