package com.jjx.customer.platform.common.util;

import java.util.regex.Pattern;

/**
 * 文本单行预览工具（日志与 agent 轨迹展示用）。
 * <p>收敛此前散落在各检索通道 / agent 里逐字重复的 truncate / preview 私有实现。</p>
 */
public final class TextPreviews {

    /** 连续空白压缩（预览前处理，预编译） */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextPreviews() {
    }

    /** 截断到 maxLen 并加省略号；null 安全 */
    public static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /** 空白压成单空格后截断到 maxLen（agent 轨迹 / LLM 工具输出里的单行预览）；空输入返回空串 */
    public static String preview(String content, int maxLen) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String s = WHITESPACE.matcher(content).replaceAll(" ").trim();
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }
}
