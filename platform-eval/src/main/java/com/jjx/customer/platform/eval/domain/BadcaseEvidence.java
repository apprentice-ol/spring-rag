package com.jjx.customer.platform.eval.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * bad case 归集时的证据快照（{@code sa_eval_item.evidence}）。
 *
 * <p>归集时从原始现场（sa_message + sa_agent_trace.steps）取一次固化，之后不依赖轨迹长存——
 * 轨迹表会清理、消息可能被删，而归因与管理页展示需要现场。</p>
 *
 * <p>解析收口在 {@code badcase.EvidenceExtractor} 单点：steps 里 {@code detail.slotWrites} 的 key
 * 是代码契约（随图演进），前端零解析、只消费本结构。</p>
 *
 * @param question       用户原始问题（轨迹里的原文优先，退回上一条 user 消息）
 * @param conversationId 会话 id（回跳会话现场）
 * @param answer         系统回答（截断保存，现场回顾用）
 * @param traceId        本次请求 OTel traceId（跳 OpenObserve 全链路）
 * @param paradigm       本轮范式（knowledge / ops_diagnose / react_loop）
 * @param hasTrace       是否拿到 agent 轨迹（false = 闲聊/缓存重放/降级路径，无归因证据）
 * @param intent         意图分类节点产物
 * @param retrieval      检索节点产物
 * @param citations      回答引用（文档级摘要）
 * @param hitCollections 命中片段所属文档集合（按命中条数排序；domain 推荐的运行期信号）
 * @param note           组装说明（无轨迹、解析失败等降级原因）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BadcaseEvidence(
        String question,
        String conversationId,
        String answer,
        String traceId,
        String paradigm,
        boolean hasTrace,
        IntentEvidence intent,
        RetrievalEvidence retrieval,
        List<CitationBrief> citations,
        List<HitCollection> hitCollections,
        String note
) {

    /** 意图分类产物（KbClassifyExecutor / KbRouteExecutor 的槽位写入）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IntentEvidence(
            String domain,
            Double confidence,
            Boolean needsRetrieval,
            Boolean needsWebSearch,
            String reason,
            String routeTarget
    ) {
    }

    /** 检索产物摘要（kb_chunks_data.retrieval + 命中片段）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetrievalEvidence(
            Integer channelCandidates,
            Integer finalHits,
            List<ChannelHits> channels,
            List<ChunkBrief> chunks
    ) {
    }

    /** 单通道召回条数（如 向量 20 条）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChannelHits(String name, Integer hits) {
    }

    /** 命中片段摘要（content 截断，避免证据快照过大）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkBrief(
            Integer ref,
            String docId,
            String docName,
            Double score,
            Double originalScore,
            String channel,
            String preview
    ) {
    }

    /** 回答引用摘要（sa_message.citations 的精简投影）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CitationBrief(String docId, String docName, Integer chunkCount) {
    }

    /**
     * 命中片段所属的文档集合（domain 推荐的运行期信号：归集时系统"已经知道"这次检索
     * 主要来自哪个集合——用户点踩时答不出领域，但这份了解可以带给人工确认）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HitCollection(Long collectionId, String name, Integer hits) {
    }
}
