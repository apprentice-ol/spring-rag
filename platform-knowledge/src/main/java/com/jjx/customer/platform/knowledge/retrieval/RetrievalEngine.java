package com.jjx.customer.platform.knowledge.retrieval;

/**
 * 多通道检索引擎接口：{@link MultiChannelRetrievalEngine} 是唯一生产实现，
 * {@code CachingRetrievalEngine}（chat/cache）以 @Primary 装饰它做 Redis 结果缓存。
 * <p>注入方应依赖本接口（拿到缓存装饰器）；需要绕过缓存的实时场景才依赖具体类
 * {@link MultiChannelRetrievalEngine}（2026-09-12 诊断合流后暂无此类调用方，保留口子）。
 */
public interface RetrievalEngine {

    /** 执行多通道检索 + 后处理链（去重→RRF→rerank→多样性）。 */
    MultiChannelRetrievalEngine.RetrievalResult retrieve(SearchContext context);
}
