package com.agentframework.runtime.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次运行的输入：自由文本 + 结构化数据。
 *
 * @param text    文本输入（通常作为用户消息）
 * @param payload 结构化业务数据
 * @param slots   需要写入槽位的初始值
 */
public record Input(String text, Map<String, Object> payload, Map<String, Object> slots) {

    public Input {
        text = text == null ? "" : text;
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        slots = slots == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(slots));
    }

    /**
     * @param text 文本输入
     * @return 仅含文本的输入
     */
    public static Input of(String text) {
        return new Input(text, null, null);
    }

    /**
     * @param payload 结构化业务数据
     * @return 仅含结构化数据的输入
     */
    public static Input of(Map<String, Object> payload) {
        return new Input("", payload, null);
    }

    /** @return 空输入 */
    public static Input empty() {
        return new Input("", null, null);
    }

    /**
     * @param name  槽位名
     * @param value 槽位值
     * @return 追加槽位后的输入
     */
    public Input withSlot(String name, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(slots);
        merged.put(name, value);
        return new Input(text, payload, merged);
    }

    /**
     * @param name  数据键
     * @param value 数据值
     * @return 追加结构化数据后的输入
     */
    public Input withPayload(String name, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(payload);
        merged.put(name, value);
        return new Input(text, merged, slots);
    }

    /** @return 是否没有任何输入内容 */
    public boolean isEmpty() {
        return text.isBlank() && payload.isEmpty() && slots.isEmpty();
    }

    /** @return 转为 user 消息 */
    public Message asUserMessage() {
        return Message.user(text).withMetadata("payload", payload).withMetadata("slots", slots);
    }
}
