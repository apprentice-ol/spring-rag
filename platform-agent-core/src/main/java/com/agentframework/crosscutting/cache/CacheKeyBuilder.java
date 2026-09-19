package com.agentframework.crosscutting.cache;

/**
 * 缓存键构建扩展点。
 *
 * <p>键必须同时包含命名空间与操作名，避免不同 Agent、不同节点之间串数据。</p>
 */
public interface CacheKeyBuilder {

    /**
     * 构建缓存键。
     *
     * @param namespace 命名空间，通常为会话或 Agent 维度
     * @param operation 操作名，例如 {@code llm:plan} 或 {@code tool:search}
     * @param payload   参与键计算的载荷
     * @return 缓存键
     */
    String build(String namespace, String operation, Object payload);
}
