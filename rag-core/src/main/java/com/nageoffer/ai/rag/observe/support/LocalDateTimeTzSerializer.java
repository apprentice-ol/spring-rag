package com.nageoffer.ai.rag.observe.support;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * {@link LocalDateTime} 序列化为「带 JVM 默认时区偏移」的 ISO 字符串（如 {@code 2026-08-16T12:49:32.223+08:00}）。
 *
 * <p>后端默认把无时区的 {@code LocalDateTime} 序列化成 {@code 2026-08-16T12:49:32.223}，前端 {@code new Date()}
 * 会按浏览器本地时区解析——服务器容器是 UTC 而浏览器是 +08 时偏移 8 小时，
 * 导致 AgentTracePanel 的 OpenObserve 深链查询窗口错位查不到（对话页用 {@code Date.now()} 无此问题）。
 * 带时区偏移后前端解析不受浏览器时区影响，时区自包含。</p>
 */
public class LocalDateTimeTzSerializer extends JsonSerializer<LocalDateTime> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(value.atZone(ZoneId.systemDefault()).format(FORMATTER));
    }
}
