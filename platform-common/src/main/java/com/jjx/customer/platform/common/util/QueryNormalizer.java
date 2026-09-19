package com.jjx.customer.platform.common.util;

import java.util.regex.Pattern;

/**
 * 用户查询归一化工具。
 * <p>
 * 在意图分类与检索前对用户输入做清洗，去除与语义无关的噪声，提升分类与召回质量。
 * <b>纯清洗、不改写语义内容</b>（2026-09-19 移除了曾有的规则式疑问句→陈述句改写表，
 * 语义改写统一归 {@code QueryRewriter} 的 LLM 改写/反思环承担）：仅做全角→半角、
 * 压缩空白、去无意义前缀（请问/你好等）、去首尾标点。
 * </p>
 *
 * <p>示例：
 * <ul>
 *   <li>"请问一下，Ｓｐｒｉｎｇ ＡＩ 怎么用？" → "Spring AI 怎么用"</li>
 *   <li>"  你好，麻烦问下 ｐｇｖｅｃｔｏｒ 是啥！！" → "pgvector 是啥"</li>
 *   <li>"RAG 的流程" → "RAG 的流程"（无变化）</li>
 * </ul>
 * </p>
 */
public final class QueryNormalizer {

    private QueryNormalizer() {
    }

    /**
     * 无意义前缀；按长度降序排列以优先匹配长串（"请问一下"先于"请问"）。
     * 前缀后允许跟随标点或空白，一并去除。可叠加（"你好，请问一下"）。
     */
    private static final Pattern NOISE_PREFIX = Pattern.compile(
            "^(?:请问一下|麻烦问一下|麻烦问下|帮我问一下|我想问一下|我想问|我想知道|"
                    + "咨询一下|请教一下|请问|问一下|帮我问|您好|你好|哈喽|嗨)"
                    + "[\\s,.!?;:，。！？；：]*");

    /** 首部标点（中英文），归一化后去除 */
    private static final Pattern LEAD_PUNCT = Pattern.compile(
            "^[\\p{Punct}\\s，。！？、；：“”‘’（）《》【】「」『』·…]+");
    /** 尾部标点（中英文），归一化后去除 */
    private static final Pattern TAIL_PUNCT = Pattern.compile(
            "[\\p{Punct}\\s，。！？、；：“”‘’（）《》【】「」『』·…]+$");

    /** 连续空白压缩（热路径，避免 replaceAll 每次隐式编译） */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 归一化用户查询。
     *
     * @param raw 原始输入
     * @return 归一化后的查询；输入为空返回空串
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // 1. 全角→半角（英数字与常见标点）
        String s = toHalfWidth(raw);
        // 2. 压缩连续空白
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        // 3. 去无意义前缀（循环，应对"你好，请问一下"这类叠加）
        String prev;
        do {
            prev = s;
            s = NOISE_PREFIX.matcher(s).replaceFirst("");
        } while (!s.equals(prev));
        // 4. 去首尾标点
        s = LEAD_PUNCT.matcher(s).replaceFirst("");
        s = TAIL_PUNCT.matcher(s).replaceFirst("");
        return s.trim();
    }

    /**
     * 全角字符（！~～）转半角；全角空格（　）转普通空格。
     * 中文专用标点（。、「」等）不在该范围，保持原样。
     */
    private static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '！' && c <= '～') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '　') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
