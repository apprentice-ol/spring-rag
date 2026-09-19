package com.agentframework.infra.modelgateway;

import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.MessageRole;
import java.util.List;

/**
 * 发送给模型的对话消息。
 *
 * @param role      角色
 * @param content   文本内容
 * @param name      工具名或发言者名
 * @param toolCalls 模型发起的工具调用（仅 assistant 角色使用）
 */
public record ChatMessage(ChatRole role, String content, String name, List<ToolCall> toolCalls) {

    public ChatMessage {
        role = role == null ? ChatRole.USER : role;
        content = content == null ? "" : content;
        toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
    }

    /**
     * @param content 内容
     * @return system 消息
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, null);
    }

    /**
     * @param content 内容
     * @return user 消息
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, null);
    }

    /**
     * @param content 内容
     * @return assistant 消息
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, null);
    }

    /**
     * @param name    工具名
     * @param content 工具输出
     * @return tool 消息
     */
    public static ChatMessage tool(String name, String content) {
        return new ChatMessage(ChatRole.TOOL, content, name, null);
    }

    /**
     * @param content   内容
     * @param toolCalls 工具调用列表
     * @return 带工具调用的 assistant 消息
     */
    public static ChatMessage assistantWithTools(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, toolCalls);
    }

    /**
     * 会话消息转模型消息。
     *
     * @param message 会话消息
     * @return 模型消息
     */
    public static ChatMessage from(Message message) {
        return new ChatMessage(switch (message.role()) {
            case SYSTEM -> ChatRole.SYSTEM;
            case USER -> ChatRole.USER;
            case ASSISTANT -> ChatRole.ASSISTANT;
            case TOOL -> ChatRole.TOOL;
        }, message.content(), message.name(), null);
    }

    /** @return 是否为 system 消息 */
    public boolean isSystem() {
        return role == ChatRole.SYSTEM;
    }

    /** @return 是否包含工具调用 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** @return 对应的会话角色 */
    public MessageRole sessionRole() {
        return switch (role) {
            case SYSTEM -> MessageRole.SYSTEM;
            case USER -> MessageRole.USER;
            case ASSISTANT -> MessageRole.ASSISTANT;
            case TOOL -> MessageRole.TOOL;
        };
    }
}
