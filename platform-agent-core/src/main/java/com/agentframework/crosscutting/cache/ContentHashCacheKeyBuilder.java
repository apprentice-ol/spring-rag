package com.agentframework.crosscutting.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 默认缓存键构建器：按内容哈希生成稳定键。
 *
 * <p>相同输入必然命中同一键，因此适合 LLM 与工具调用的幂等缓存。</p>
 */
public final class ContentHashCacheKeyBuilder implements CacheKeyBuilder {

    @Override
    public String build(String namespace, String operation, Object payload) {
        String raw = (namespace == null ? "default" : namespace)
                + '|' + (operation == null ? "op" : operation)
                + '|' + canonical(payload);
        return operation + ":" + sha256(raw);
    }

    /**
     * 将载荷规范化为可哈希字符串。
     *
     * @param payload 载荷
     * @return 规范化文本
     */
    private String canonical(Object payload) {
        if (payload == null) {
            return "null";
        }
        if (payload instanceof java.util.Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> builder.append(entry.getKey()).append('=')
                            .append(canonical(entry.getValue())).append(';'));
            return builder.append('}').toString();
        }
        if (payload instanceof java.util.Collection<?> collection) {
            StringBuilder builder = new StringBuilder("[");
            collection.forEach(item -> builder.append(canonical(item)).append(';'));
            return builder.append(']').toString();
        }
        return String.valueOf(payload);
    }

    /**
     * @param text 待哈希文本
     * @return 十六进制 SHA-256（截断为 32 位）
     */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 未提供 SHA-256 实现", e);
        }
    }
}
