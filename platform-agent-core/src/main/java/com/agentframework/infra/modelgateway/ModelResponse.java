package com.agentframework.infra.modelgateway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型调用结果。
 *
 * @param provider     提供方标识
 * @param model        模型名
 * @param content      文本回答
 * @param toolCalls    工具调用请求
 * @param usage        token 用量
 * @param finishReason 结束原因，例如 stop / tool_calls / length
 * @param metadata     附加信息
 */
public record ModelResponse(
        String provider,
        String model,
        String content,
        List<ToolCall> toolCalls,
        Usage usage,
        String finishReason,
        Map<String, Object> metadata) {

    public ModelResponse {
        content = content == null ? "" : content;
        toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
        usage = usage == null ? Usage.zero() : usage;
        finishReason = finishReason == null ? "stop" : finishReason;
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * @param content 文本回答
     * @return 纯文本结果
     */
    public static ModelResponse text(String content) {
        return new ModelResponse(null, null, content, null, null, null, null);
    }

    /**
     * @param content      文本回答
     * @param promptTokens 输入 token
     * @param outputTokens 输出 token
     * @return 带用量的文本结果
     */
    public static ModelResponse text(String content, int promptTokens, int outputTokens) {
        return new ModelResponse(null, null, content, null, Usage.of(promptTokens, outputTokens), null, null);
    }

    /**
     * @param toolCalls 工具调用列表
     * @return 工具调用结果
     */
    public static ModelResponse tools(List<ToolCall> toolCalls) {
        return new ModelResponse(null, null, "", toolCalls, null, "tool_calls", null);
    }

    /** @return 是否请求调用工具 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * @param key   元数据键
     * @param value 元数据值
     * @return 追加元数据后的结果
     */
    public ModelResponse withMetadata(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new ModelResponse(provider, model, content, toolCalls, usage, finishReason, merged);
    }

    /**
     * @param provider 提供方标识
     * @param model    模型名
     * @return 补齐提供方与模型名后的结果
     */
    public ModelResponse withIdentity(String provider, String model) {
        return new ModelResponse(provider, model, content, toolCalls, usage, finishReason, metadata);
    }
}
