package com.agentframework.runtime.workspace;

import java.util.Map;

/**
 * 向量检索命中结果。
 *
 * @param id       记录 id
 * @param score    相似度，越大越接近
 * @param text     命中文本
 * @param metadata 记录元数据
 */
public record VectorMatch(String id, double score, String text, Map<String, Object> metadata) {

    /**
     * @param id    记录 id
     * @param score 相似度
     * @param text  命中文本
     * @return 检索结果
     */
    public static VectorMatch of(String id, double score, String text) {
        return new VectorMatch(id, score, text, null);
    }
}
