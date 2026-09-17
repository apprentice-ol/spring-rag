package com.jjx.customer.platform.common.util;

import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.StringReader;

/**
 * 从混合文本中提取 JSON 对象的工具。
 * <p>
 * 与 {@link JsonResponseParser} 的分工：后者面向「整个响应就是一个 JSON」的场景；
 * 本类面向「JSON 混在说明文字 / code fence 中」的场景（如 LLM-as-judge 的输出），
 * 按花括号配对扫描提取<b>首个完整平衡</b>的 JSON 对象子串再解析——
 * 比 indexOf('{') / lastIndexOf('}') 截取更健壮（正文含花括号、输出多段 JSON 时不会截错）。
 * </p>
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JsonUtil {

    /**
     * 从任意文本中提取并解析首个完整 JSON 对象。
     *
     * @param raw 原始文本（可包含 code fence / 前后说明文字）
     * @return 解析成功的 JsonObject；文本中无 JSON 对象或解析失败返回 null
     */
    public static JsonObject firstJsonObject(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String candidate = LLMResponseCleaner.stripMarkdownCodeFence(raw.trim());
        String body = scanFirstBalancedObject(candidate);
        if (body == null) {
            return null;
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(body));
            reader.setStrictness(Strictness.LENIENT);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            log.warn("[JsonUtil] JSON 对象提取后解析失败, 片段: {}", StrUtil.subPre(body, 200), e);
            return null;
        }
    }

    /**
     * 扫描首个花括号平衡的 {@code {...}} 子串。
     * <p>跳过字符串字面量内部的花括号（含转义），避免正文/字符串值干扰配对。</p>
     */
    private static String scanFirstBalancedObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return null;
    }
}
