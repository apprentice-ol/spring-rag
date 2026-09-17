package com.jjx.customer.platform.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 日志查询缓存 key（纯函数；observe 工具与检索侧 CacheKeys 共用同一口径，避免公式重复）。 */
public final class LogCacheKeys {

    private LogCacheKeys() {
    }

    /** 日志查询 key：traceId + 分钟桶时间窗 + 关键字 + 级别 + 条数。 */
    public static String logsKey(String traceId, long startMs, long endMs, String keyword, String level, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append(traceId == null ? "" : traceId).append('\u0001').append(startMs).append('\u0001')
          .append(endMs).append('\u0001').append(keyword == null ? "" : keyword).append('\u0001')
          .append(level == null ? "" : level).append('\u0001').append(limit);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return "rag:cache:logs:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}