package com.nageoffer.ai.rag.chat.postprocessor;

import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelResult;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import java.util.List;

/**
 * 检索结果后置处理器接口（扩展点，责任链模式）。
 * <p>
 * 多通道检索完成后，结果依次经过注册的处理器链：
 * 去重（DeduplicationPostProcessor）→ RRF 融合（FusionPostProcessor）→
 * Rerank 精排（RerankPostProcessor）。按 {@link #getOrder()} 升序执行，
 * 前一处理器的输出作为下一处理器的输入。
 * </p>
 */
public interface SearchResultPostProcessor {

    /** 处理器名称标识 */
    String getName();

    /** 数字越小越先执行 */
    int getOrder();

    /** 当前上下文中此处理器是否启用 */
    boolean isEnabled(SearchContext context);

    /**
     * 执行后处理逻辑。
     *
     * @param chunks  当前待处理的文档片段列表（上一处理器的输出）
     * @param results 所有通道的原始检索结果（供融合处理器使用）
     * @param context 检索上下文
     * @return 处理后的文档片段列表
     */
    List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                  List<SearchChannelResult> results,
                                  SearchContext context);
}
