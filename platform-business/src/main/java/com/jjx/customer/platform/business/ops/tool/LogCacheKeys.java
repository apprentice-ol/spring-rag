package com.jjx.customer.platform.business.ops.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 日志查询缓存键：拼接字段序 {@code traceId  startMs  endMs  keyword  level  limit}，
 * SHA-256 取前 12 字节（24 hex），前缀 {@code ops:cache:logs:}。逐字对齐参考 {@code LogCacheKeys}
 * （仅前缀改为 ops 命名空间，避免与 RAG 缓存混淆）。
 */
public final class LogCacheKeys {

    private LogCacheKeys() {
    }

    /**
     * @param traceId 链路 id（精查键此值参与；时间窗键传空）
     * @param startMs 开始毫秒（精查传 0；时间窗须先落分钟桶）
     * @param endMs   结束毫秒（同上）
     * @param keyword 关键字
     * @param level   级别
     * @param limit   条数
     * @return 缓存键
     */
    public static String logsKey(String traceId, long startMs, long endMs, String keyword, String level, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append(traceId == null ? "" : traceId).append('').append(startMs).append('')
                .append(endMs).append('').append(keyword == null ? "" : keyword).append('')
                .append(level == null ? "" : level).append('').append(limit);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return "ops:cache:logs:" + hex;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param ms 毫秒时间戳 @return 落到分钟桶的时间戳（时间窗缓存键的稳定性来源） */
    public static long floorMinute(long ms) {
        return ms / 60_000L * 60_000L;
    }
}
