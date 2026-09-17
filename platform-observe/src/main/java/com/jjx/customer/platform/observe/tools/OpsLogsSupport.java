package com.jjx.customer.platform.observe.tools;


import com.jjx.customer.platform.common.util.TextPreviews;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** 日志工具族的纯函数（时间解析 / 分钟桶 / 视图渲染），随 ops 工具一起归属新主线。 */
public final class OpsLogsSupport {

    private OpsLogsSupport() {
    }

    public static long parseTimeOr(String iso, long fallbackMs) {
        if (iso == null || iso.isBlank()) {
            return fallbackMs;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(iso).toEpochMilli();
            } catch (DateTimeParseException e2) {
                return fallbackMs;
            }
        }
    }

    public static long floorMinute(long ms) {
        return ms / 60_000L * 60_000L;
    }

    public static String formatInstant(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }

    public static String nvl(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 取第一个非空白值：日志流的正文/级别有两套列（新 OTel 数据的 body/severity 与历史数据的
     * message/level），渲染时按实际有值的那套取，否则会出现“命中却内容为空”。
     */
    public static String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object v : values) {
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    public static String formatLogs(String scope, List<Map<String, Object>> logs) {
        if (logs.isEmpty()) {
            return "无命中日志（" + scope + "）";
        }
        StringBuilder sb = new StringBuilder("命中 ").append(logs.size()).append(" 条（").append(scope).append("）：\n");
        for (Map<String, Object> l : logs) {
            sb.append('[').append(l.getOrDefault("level", "?")).append("][")
                    .append(l.getOrDefault("time", "?")).append("] ")
                    .append(TextPreviews.truncate(String.valueOf(l.getOrDefault("msg", "")), 160));
            Object ex = l.get("exception");
            if (ex != null && !String.valueOf(ex).isBlank()) {
                sb.append(" ⚠ ").append(TextPreviews.truncate(String.valueOf(ex), 120));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
