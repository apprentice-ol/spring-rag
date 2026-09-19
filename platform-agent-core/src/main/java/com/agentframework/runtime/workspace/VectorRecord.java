package com.agentframework.runtime.workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向量记录：写入工作区向量索引的一个分片。
 *
 * @param id       记录 id
 * @param vector   向量值
 * @param text     原始文本
 * @param metadata 附加元数据，可用于过滤
 */
public record VectorRecord(String id, float[] vector, String text, Map<String, Object> metadata) {

    public VectorRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("vector record id is required");
        }
        vector = vector == null ? new float[0] : vector.clone();
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
