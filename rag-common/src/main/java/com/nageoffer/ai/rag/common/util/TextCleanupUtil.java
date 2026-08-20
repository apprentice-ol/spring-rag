package com.nageoffer.ai.rag.common.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 文本清理工具类
 * <p>
 * 提供统一的文本清理逻辑，用于文档解析后的文本规范化。
 * 正则全部预编译（本类在入库解析热路径上按文档反复调用）。
 * </p>
 */
public final class TextCleanupUtil {

    /** 行尾空格/制表符（含尾随换行符） */
    private static final Pattern TRAILING_BLANKS = Pattern.compile("[ \\t]+\\n");

    /** 3 个及以上连续换行压缩为 2 个（cleanup() 默认规则） */
    private static final Pattern BLANK_LINES_3_PLUS = Pattern.compile("\\n{3,}");

    /** maxConsecutiveLines 参数化的连续空行规则缓存（取值离散且小，避免每次调用拼串编译） */
    private static final Map<Integer, Pattern> BLANK_LINE_RULES = new ConcurrentHashMap<>();

    private TextCleanupUtil() {
    }

    /**
     * 清理文本内容
     * <p>
     * 执行以下清理操作：
     * 1. 移除 BOM 标记（﻿）
     * 2. 移除行尾多余的空格和制表符
     * 3. 压缩连续的空行（3个以上压缩为2个）
     * 4. 去除首尾空白
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    public static String cleanup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // 移除 BOM 标记
        String result = text.replace("﻿", "");
        // 移除行尾的空格和制表符
        result = TRAILING_BLANKS.matcher(result).replaceAll("\n");
        // 压缩连续的空行（3个以上压缩为2个）
        result = BLANK_LINES_3_PLUS.matcher(result).replaceAll("\n\n");
        return result.trim();
    }

    /**
     * 清理文本内容（自定义规则）
     *
     * @param text                原始文本
     * @param removeBOM           是否移除 BOM
     * @param trimTrailingSpaces  是否移除行尾空格
     * @param compressEmptyLines  是否压缩空行
     * @param maxConsecutiveLines 最多保留的连续空行数
     * @return 清理后的文本
     */
    public static String cleanup(String text,
                                 boolean removeBOM,
                                 boolean trimTrailingSpaces,
                                 boolean compressEmptyLines,
                                 int maxConsecutiveLines) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;

        if (removeBOM) {
            result = result.replace("﻿", "");
        }

        if (trimTrailingSpaces) {
            result = TRAILING_BLANKS.matcher(result).replaceAll("\n");
        }

        if (compressEmptyLines && maxConsecutiveLines > 0) {
            result = blankLineRule(maxConsecutiveLines).matcher(result)
                    .replaceAll("\n".repeat(maxConsecutiveLines));
        }

        return result.trim();
    }

    /** (maxConsecutiveLines+1) 个及以上换行的压缩规则，按参数缓存编译结果 */
    private static Pattern blankLineRule(int maxConsecutiveLines) {
        return BLANK_LINE_RULES.computeIfAbsent(maxConsecutiveLines,
                n -> Pattern.compile("\\n{" + (n + 1) + ",}"));
    }
}
