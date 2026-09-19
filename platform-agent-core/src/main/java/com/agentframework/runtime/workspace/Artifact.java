package com.agentframework.runtime.workspace;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 产物：一次运行产生的、可交还给应用层的文件化结果。
 *
 * @param name        产物名
 * @param path        工作区内的路径
 * @param contentType 内容类型
 * @param size        字节大小
 * @param createdAt   产生时间
 * @param metadata    附加信息
 */
public record Artifact(String name, String path, String contentType, long size, Instant createdAt,
        Map<String, Object> metadata) {

    public Artifact {
        name = name == null ? "artifact" : name;
        contentType = contentType == null ? "application/octet-stream" : contentType;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * @param name    产物名
     * @param content 文本内容
     * @return 文本产物
     */
    public static Artifact of(String name, String content) {
        byte[] bytes = content == null ? new byte[0] : content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new Artifact(name, name, "text/plain", bytes.length, null, null);
    }
}
