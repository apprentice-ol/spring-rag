package com.agentframework.infra.modelgateway;

import com.agentframework.definition.tool.ToolSchema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型调用请求。
 *
 * @param provider    模型提供方逻辑标识
 * @param model       模型名
 * @param messages    对话消息
 * @param temperature 采样温度，null 表示使用提供方默认值
 * @param maxTokens   最大生成 token 数，null 表示不限制
 * @param tools       可调用的工具契约
 * @param parameters  透传给提供方的扩展参数
 */
public record ModelRequest(
        String provider,
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        List<ToolSchema> tools,
        Map<String, Object> parameters) {

    public ModelRequest {
        provider = provider == null || provider.isBlank() ? "default" : provider;
        model = model == null || model.isBlank() ? "default" : model;
        messages = List.copyOf(messages == null ? List.of() : messages);
        tools = List.copyOf(tools == null ? List.of() : tools);
        parameters = parameters == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    /**
     * @param provider 提供方标识
     * @param model    模型名
     * @param messages 对话消息
     * @return 基础请求
     */
    public static ModelRequest of(String provider, String model, List<ChatMessage> messages) {
        return new ModelRequest(provider, model, messages, null, null, null, null);
    }

    /**
     * @param temperature 采样温度
     * @return 覆盖温度后的请求
     */
    public ModelRequest withTemperature(Double temperature) {
        return new ModelRequest(provider, model, messages, temperature, maxTokens, tools, parameters);
    }

    /**
     * @param maxTokens 最大生成 token 数
     * @return 覆盖上限后的请求
     */
    public ModelRequest withMaxTokens(Integer maxTokens) {
        return new ModelRequest(provider, model, messages, temperature, maxTokens, tools, parameters);
    }

    /**
     * @param tools 工具契约列表
     * @return 追加工具后的请求
     */
    public ModelRequest withTools(List<ToolSchema> tools) {
        return new ModelRequest(provider, model, messages, temperature, maxTokens, tools, parameters);
    }

    /**
     * @param key   参数名
     * @param value 参数值
     * @return 追加扩展参数后的请求
     */
    public ModelRequest withParameter(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(parameters);
        merged.put(key, value);
        return new ModelRequest(provider, model, messages, temperature, maxTokens, tools, merged);
    }

    /**
     * @param extra 待追加的扩展参数
     * @return 批量追加扩展参数后的请求
     */
    public ModelRequest withParameters(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(parameters);
        merged.putAll(extra);
        return new ModelRequest(provider, model, messages, temperature, maxTokens, tools, merged);
    }

    /** @return 最后一条 user 消息内容 */
    public String lastUserMessage() {
        return messages.stream()
                .filter(message -> message.role() == ChatRole.USER)
                .reduce((first, second) -> second)
                .map(ChatMessage::content)
                .orElse("");
    }

    /** @return 拼接后的全部文本，用于缓存键与用量估算 */
    public String flatten() {
        StringBuilder builder = new StringBuilder();
        messages.forEach(message -> builder.append(message.role()).append(':')
                .append(message.content()).append('\n'));
        return builder.toString();
    }
}
