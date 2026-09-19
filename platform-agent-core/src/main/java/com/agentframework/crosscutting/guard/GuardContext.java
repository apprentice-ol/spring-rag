package com.agentframework.crosscutting.guard;

import com.agentframework.runtime.slot.Slots;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 守卫上下文：守卫做决策所需的全部信息。
 *
 * @param phase     当前挂载点
 * @param sessionId 会话 id
 * @param tenantId  租户 id
 * @param agentId   Agent id
 * @param nodeId    节点 id，非节点阶段可为 null
 * @param payload   被检查的载荷（提示词文本、工具调用、模型输出等）
 * @param slots     当前槽位容器，可为 null
 * @param attributes 附加上下文（例如角色、用户属性）
 * @param at        发生时间
 */
public record GuardContext(
        GuardPhase phase,
        String sessionId,
        String tenantId,
        String agentId,
        String nodeId,
        Object payload,
        Slots slots,
        Map<String, Object> attributes,
        Instant at) {

    public GuardContext {
        phase = phase == null ? GuardPhase.BEFORE_NODE : phase;
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        at = at == null ? Instant.now() : at;
    }

    /** 循环计数在上下文属性中的键。 */
    public static final String LOOP_COUNTERS = "loopCounters";

    /** 当前节点所属循环标识在上下文属性中的键。 */
    public static final String LOOP_KEY = "loopKey";

    /** Region 已消耗 token 数在上下文属性中的键。 */
    public static final String REGION_TOKENS = "regionTokens";

    /**
     * @return 当前节点所属 Region 已消耗的 token 数，未知时为 0
     */
    public long regionTokens() {
        Object value = attributes.get(REGION_TOKENS);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * @return 当前节点所属的循环标识；节点不在任何显式循环中时回退为节点 id
     */
    public String loopKey() {
        Object value = attributes.get(LOOP_KEY);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return nodeId;
    }

    /**
     * @param loopKey  循环标识，null 时回退为节点 id
     * @param counters 循环计数快照
     * @return 绑定循环信息后的上下文
     */
    public GuardContext withLoop(String loopKey, Map<String, Integer> counters) {
        GuardContext bound = withLoopCounters(counters);
        return loopKey == null ? bound : bound.withAttribute(LOOP_KEY, loopKey);
    }

    /**
     * @param key 计数键，通常为节点 id
     * @return 当前累计次数，未记录时返回 0
     */
    public int loopCounter(String key) {
        Object raw = attributes.get(LOOP_COUNTERS);
        if (raw instanceof Map<?, ?> counters) {
            Object value = counters.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return 0;
    }

    /**
     * @param counters 循环计数快照
     * @return 绑定计数后的上下文
     */
    public GuardContext withLoopCounters(Map<String, Integer> counters) {
        return withAttribute(LOOP_COUNTERS, counters == null ? Map.of() : Map.copyOf(counters));
    }

    /**
     * 构造仅含必需字段的上下文。
     *
     * @param phase   挂载点
     * @param payload 被检查载荷
     * @return 守卫上下文
     */
    public static GuardContext of(GuardPhase phase, Object payload) {
        return new GuardContext(phase, null, null, null, null, payload, null, null, null);
    }

    /**
     * 补全会话信息。
     *
     * @param sessionId 会话 id
     * @param tenantId  租户 id
     * @return 补充会话信息后的上下文
     */
    public GuardContext withSession(String sessionId, String tenantId) {
        return new GuardContext(phase, sessionId, tenantId, agentId, nodeId, payload, slots, attributes, at);
    }

    /**
     * 补全 Agent 与节点信息。
     *
     * @param agentId Agent id
     * @param nodeId  节点 id
     * @return 补充归属信息后的上下文
     */
    public GuardContext withOwner(String agentId, String nodeId) {
        return new GuardContext(phase, sessionId, tenantId, agentId, nodeId, payload, slots, attributes, at);
    }

    /**
     * 绑定槽位容器。
     *
     * @param slots 槽位容器
     * @return 绑定槽位后的上下文
     */
    public GuardContext withSlots(Slots slots) {
        return new GuardContext(phase, sessionId, tenantId, agentId, nodeId, payload, slots, attributes, at);
    }

    /**
     * 替换挂载点，其余字段原样保留。
     *
     * @param newPhase 新挂载点
     * @return 替换挂载点后的上下文
     */
    public GuardContext withPhase(GuardPhase newPhase) {
        return new GuardContext(newPhase, sessionId, tenantId, agentId, nodeId, payload, slots, attributes, at);
    }

    /**
     * 批量追加属性。
     *
     * @param extra 待追加的键值对
     * @return 追加属性后的上下文
     */
    public GuardContext withAttributes(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.putAll(extra);
        return new GuardContext(phase, sessionId, tenantId, agentId, nodeId, payload, slots, merged, at);
    }

    /**
     * 追加属性。
     *
     * @param key   属性名
     * @param value 属性值
     * @return 追加属性后的上下文
     */
    public GuardContext withAttribute(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new GuardContext(phase, sessionId, tenantId, agentId, nodeId, payload, slots, merged, at);
    }

    /**
     * 替换载荷。
     *
     * @param newPayload 新载荷
     * @return 替换载荷后的上下文
     */
    public GuardContext withPayload(Object newPayload) {
        return new GuardContext(phase, sessionId, tenantId, agentId, nodeId, newPayload, slots, attributes, at);
    }

    /** @return 载荷的文本形式，null 返回空串 */
    public String payloadAsText() {
        return payload == null ? "" : String.valueOf(payload);
    }
}
