package com.agentframework.crosscutting.filter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 过滤器上下文：数据变换时可读的元信息。
 *
 * @param phase      挂载点
 * @param sessionId  会话 id
 * @param nodeId     节点 id，可为 null
 * @param attributes 附加属性（例如工具名、模型名）
 */
public record FilterContext(FilterPhase phase, String sessionId, String nodeId, Map<String, Object> attributes) {

    public FilterContext {
        phase = phase == null ? FilterPhase.PROMPT : phase;
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * @param phase 挂载点
     * @return 仅含挂载点的上下文
     */
    public static FilterContext of(FilterPhase phase) {
        return new FilterContext(phase, null, null, null);
    }

    /**
     * @param sessionId 会话 id
     * @param nodeId    节点 id
     * @return 补充归属信息后的上下文
     */
    public FilterContext withOwner(String sessionId, String nodeId) {
        return new FilterContext(phase, sessionId, nodeId, attributes);
    }

    /**
     * 替换挂载点，其余字段原样保留。
     *
     * @param newPhase 新挂载点
     * @return 替换挂载点后的上下文
     */
    public FilterContext withPhase(FilterPhase newPhase) {
        return new FilterContext(newPhase, sessionId, nodeId, attributes);
    }

    /**
     * 批量追加属性。
     *
     * @param extra 待追加的键值对
     * @return 追加属性后的上下文
     */
    public FilterContext withAttributes(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.putAll(extra);
        return new FilterContext(phase, sessionId, nodeId, merged);
    }

    /**
     * @param key   属性名
     * @param value 属性值
     * @return 追加属性后的上下文
     */
    public FilterContext withAttribute(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new FilterContext(phase, sessionId, nodeId, merged);
    }
}
