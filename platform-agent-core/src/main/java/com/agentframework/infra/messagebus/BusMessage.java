package com.agentframework.infra.messagebus;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 消息总线上的消息。
 *
 * @param id      消息 id
 * @param topic   主题
 * @param key     分区键，可为 null
 * @param payload 负载
 * @param headers 头信息
 * @param at      产生时间
 */
public record BusMessage(
        String id,
        String topic,
        String key,
        Map<String, Object> payload,
        Map<String, String> headers,
        Instant at) {

    public BusMessage {
        id = id == null ? UUID.randomUUID().toString() : id;
        topic = topic == null ? "default" : topic;
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        at = at == null ? Instant.now() : at;
    }

    /**
     * @param topic   主题
     * @param payload 负载
     * @return 总线消息
     */
    public static BusMessage of(String topic, Map<String, Object> payload) {
        return new BusMessage(null, topic, null, payload, null, null);
    }

    /**
     * @param key 分区键
     * @return 指定分区键后的消息
     */
    public BusMessage withKey(String key) {
        return new BusMessage(id, topic, key, payload, headers, at);
    }

    /**
     * @param name  头名
     * @param value 头值
     * @return 追加头信息后的消息
     */
    public BusMessage withHeader(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.put(name, value);
        return new BusMessage(id, topic, key, payload, merged, at);
    }
}
