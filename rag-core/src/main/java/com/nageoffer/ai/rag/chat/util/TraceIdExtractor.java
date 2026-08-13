package com.nageoffer.ai.rag.chat.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * traceId 提取器：从用户消息中提取 traceId（32 位十六进制）。
 *
 * <p>用于对话式诊断：用户消息含 traceId 时优先走诊断分支——既能"一步式"诊断
 * （"52462c684... 这个报错怎么回事"），也能接住"反问→给出 traceId"的多轮第二回合
 * （第二轮用户只发 traceId，正则即提取到，不依赖会话状态）。
 *
 * <p>OpenObserve 实测 traceId 为 32 位 hex（如 {@code 52462c684e1c47242ffea1bc96af94c3}）；
 * spanId 为 16 位 hex，本正则严格匹配 32 位，不会误识别 spanId。
 */
public final class TraceIdExtractor {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-fA-F]{32}");

    private TraceIdExtractor() {
    }

    /** 提取首个 32 位 hex traceId；未找到返回 null */
    public static String extract(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = TRACE_ID.matcher(text);
        return m.find() ? m.group() : null;
    }
}
