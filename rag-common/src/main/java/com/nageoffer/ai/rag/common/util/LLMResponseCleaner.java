package com.nageoffer.ai.rag.common.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.StringReader;
import java.util.regex.Pattern;

/**
 * LLM 输出清理工具类
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LLMResponseCleaner {

    private static final Pattern LEADING_CODE_FENCE = Pattern.compile("^```[\\w-]*\\s*\\n?");
    private static final Pattern TRAILING_CODE_FENCE = Pattern.compile("\\n?```\\s*$");

    /**
     * 移除 Markdown 代码块围栏（例如 ```json ... ```）
     */
    public static String stripMarkdownCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim();
        cleaned = LEADING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        cleaned = TRAILING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        return cleaned.trim();
    }

    /**
     * 先清理 Markdown 代码块标记，再以宽松模式解析 JSON。
     * <p>
     * LLM 输出的 JSON 常包含尾部逗号、注释等非标准格式，
     * 使用 {@link Strictness#LENIENT} 模式可兼容这些情况。
     *
     * @param raw LLM 原始响应
     * @return 解析后的 JsonElement，解析失败返回 null
     */
    public static JsonElement stripAndParseLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = stripMarkdownCodeFence(raw);
        if (cleaned == null || cleaned.isBlank()) {
            return null;
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(cleaned));
            reader.setStrictness(Strictness.LENIENT);
            return JsonParser.parseReader(reader);
        } catch (JsonSyntaxException e) {
            throw new JsonSyntaxException("LLM 响应 JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
