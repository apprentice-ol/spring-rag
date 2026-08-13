package com.nageoffer.ai.rag.chat.retrieval;

/**
 * 检索通道接口（扩展点）。
 * <p>
 * 对应原 ragent 的 SearchChannel：多路并行检索（向量 / 关键词 / 联网）。
 * 各通道通过 Spring 依赖注入自动注册到 {@link MultiChannelRetrievalEngine}，
 * 引擎根据 isEnabled 判断是否启用，然后并行执行 search。
 * </p>
 *
 * @see VectorSearchChannel 向量检索通道
 * @see MultiChannelRetrievalEngine 多通道检索引擎
 */
public interface SearchChannel {

    /** 通道名称标识，如 "vector"、"keyword" */
    String getName();

    /** 通道类型，用于融合时区分权重 */
    SearchChannelType getType();

    /** 当前上下文下此通道是否启用 */
    boolean isEnabled(SearchContext context);

    /** 执行检索并返回通道结果（含命中文档 + 耗时等） */
    SearchChannelResult search(SearchContext context);
}
