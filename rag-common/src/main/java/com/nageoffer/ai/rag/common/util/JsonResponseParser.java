package com.nageoffer.ai.rag.common.util;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import lombok.extern.slf4j.Slf4j;

import java.io.StringReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 响应解析器，用于解析 LLM 返回的 JSON 字符串
 */
@Slf4j
public final class JsonResponseParser {

    private static final Gson GSON = new Gson();

    private JsonResponseParser() {
    }

    public static List<String> parseStringList(String raw) {
        JsonElement element = parseJsonElement(raw);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        return GSON.fromJson(element, List.class);
    }

    public static Map<String, Object> parseObject(String raw) {
        JsonElement element = parseJsonElement(raw);
        if (element == null || !element.isJsonObject()) {
            return Collections.emptyMap();
        }
        return GSON.fromJson(element, LinkedHashMap.class);
    }

    private static JsonElement parseJsonElement(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        String trimmed = extractJsonBody(cleaned);
        try {
            JsonReader reader = new JsonReader(new StringReader(trimmed));
            reader.setStrictness(Strictness.LENIENT);
            return JsonParser.parseReader(reader);
        } catch (JsonSyntaxException e) {
            // 保持返回 null 契约，但留一条 warn 便于排障时区分「格式错误」与「真为空」
            log.warn("[JsonResponseParser] LLM 响应 JSON 解析失败, 片段: {}", StrUtil.subPre(trimmed, 200), e);
            return null;
        }
    }

    private static String extractJsonBody(String raw) {
        int objStart = raw.indexOf('{');
        int arrStart = raw.indexOf('[');
        int start;
        if (objStart < 0) {
            start = arrStart;
        } else if (arrStart < 0) {
            start = objStart;
        } else {
            start = Math.min(objStart, arrStart);
        }
        if (start < 0) {
            return raw;
        }
        int objEnd = raw.lastIndexOf('}');
        int arrEnd = raw.lastIndexOf(']');
        int end = Math.max(objEnd, arrEnd);
        if (end < 0 || end <= start) {
            return raw.substring(start);
        }
        return raw.substring(start, end + 1);
    }
}
