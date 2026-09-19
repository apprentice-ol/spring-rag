package com.agentframework.engine.toolexecutor;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具执行结果。
 *
 * @param success  是否成功
 * @param output   文本输出
 * @param data     结构化输出
 * @param error    错误信息，成功时为 null
 * @param duration 执行耗时
 * @param metadata 附加信息（例如缓存命中标记）
 */
public record ToolResult(
        boolean success,
        String output,
        Map<String, Object> data,
        String error,
        Duration duration,
        Map<String, Object> metadata) {

    public ToolResult {
        output = output == null ? "" : output;
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        duration = duration == null ? Duration.ZERO : duration;
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * @param output 文本输出
     * @return 成功结果
     */
    public static ToolResult ok(String output) {
        return new ToolResult(true, output, null, null, null, null);
    }

    /**
     * @param output 文本输出
     * @param data   结构化输出
     * @return 成功结果
     */
    public static ToolResult ok(String output, Map<String, Object> data) {
        return new ToolResult(true, output, data, null, null, null);
    }

    /**
     * @param error 错误信息
     * @return 失败结果
     */
    public static ToolResult failed(String error) {
        return new ToolResult(false, "", null, error, null, null);
    }

    /**
     * @param duration 耗时
     * @return 补齐耗时后的结果
     */
    public ToolResult withDuration(Duration duration) {
        return new ToolResult(success, output, data, error, duration, metadata);
    }

    /**
     * @param key   元数据键
     * @param value 元数据值
     * @return 追加元数据后的结果
     */
    public ToolResult withMetadata(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new ToolResult(success, output, data, error, duration, merged);
    }

    /**
     * @param newOutput 新的文本输出
     * @return 替换输出后的结果
     */
    public ToolResult withOutput(String newOutput) {
        return new ToolResult(success, newOutput, data, error, duration, metadata);
    }
}
