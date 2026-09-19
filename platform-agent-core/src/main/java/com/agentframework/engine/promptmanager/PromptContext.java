package com.agentframework.engine.promptmanager;

import com.agentframework.runtime.session.Message;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 渲染上下文。
 *
 * @param sessionId  会话 id
 * @param nodeId     节点 id
 * @param variables  模板变量
 * @param messages   会话消息，模板中可用 {@code {{messages}}} 引用
 * @param attributes 附加属性（例如节点级过滤器引用）
 */
public record PromptContext(
        String sessionId,
        String nodeId,
        Map<String, Object> variables,
        List<Message> messages,
        Map<String, Object> attributes) {

    public PromptContext {
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        messages = List.copyOf(messages == null ? List.of() : messages);
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * @param variables 模板变量
     * @return 仅含变量的上下文
     */
    public static PromptContext of(Map<String, Object> variables) {
        return new PromptContext(null, null, variables, null, null);
    }

    /**
     * @param sessionId 会话 id
     * @param nodeId    节点 id
     * @return 补充归属信息后的上下文
     */
    public PromptContext withOwner(String sessionId, String nodeId) {
        return new PromptContext(sessionId, nodeId, variables, messages, attributes);
    }

    /**
     * @param extra 待合并的属性
     * @return 合并属性后的上下文
     */
    public PromptContext withAttributes(Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        if (extra != null) {
            merged.putAll(extra);
        }
        return new PromptContext(sessionId, nodeId, variables, messages, merged);
    }

    /**
     * @param messages 会话消息
     * @return 覆盖消息后的上下文
     */
    public PromptContext withMessages(List<Message> messages) {
        return new PromptContext(sessionId, nodeId, variables, messages, attributes);
    }
}
