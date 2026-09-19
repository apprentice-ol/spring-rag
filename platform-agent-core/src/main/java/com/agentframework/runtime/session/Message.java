package com.agentframework.runtime.session;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话中的一条消息：system / user / assistant / tool 输出。
 *
 * @param role       消息角色
 * @param content    文本内容
 * @param name       工具名或发言者名，可为 null
 * @param toolCallId 对应的工具调用 id，可为 null
 * @param at         产生时间
 * @param metadata   附加信息（例如 payload / slots 快照）
 */
public record Message(
        MessageRole role,
        String content,
        String name,
        String toolCallId,
        Instant at,
        Map<String, Object> metadata) {

    public Message {
        role = role == null ? MessageRole.USER : role;
        content = content == null ? "" : content;
        at = at == null ? Instant.now() : at;
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * @param content 系统提示内容
     * @return system 消息
     */
    public static Message system(String content) {
        return new Message(MessageRole.SYSTEM, content, null, null, null, null);
    }

    /**
     * @param content 用户输入内容
     * @return user 消息
     */
    public static Message user(String content) {
        return new Message(MessageRole.USER, content, null, null, null, null);
    }

    /**
     * @param content 模型输出内容
     * @return assistant 消息
     */
    public static Message assistant(String content) {
        return new Message(MessageRole.ASSISTANT, content, null, null, null, null);
    }

    /**
     * @param name    工具名
     * @param content 工具输出内容
     * @return tool 消息
     */
    public static Message tool(String name, String content) {
        return new Message(MessageRole.TOOL, content, name, null, null, null);
    }

    /**
     * @param key   元数据键
     * @param value 元数据值
     * @return 追加元数据后的消息
     */
    public Message withMetadata(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new Message(role, content, name, toolCallId, at, merged);
    }
}
