package com.jjx.customer.platform.eval.badcase;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeReactGraphFactory;
import com.jjx.customer.platform.business.trace.AgentTraceService;
import com.jjx.customer.platform.business.trace.entity.AgentTraceEntity;
import com.jjx.customer.platform.delivery.message.entity.MessageEntity;
import com.jjx.customer.platform.delivery.message.mapper.MessageMapper;
import com.jjx.customer.platform.eval.domain.BadcaseEvidence;
import com.jjx.customer.platform.ingestion.collection.domain.entity.DocCollectionEntity;
import com.jjx.customer.platform.ingestion.collection.mapper.DocCollectionMapper;
import com.jjx.customer.platform.ingestion.domain.entity.DocumentEntity;
import com.jjx.customer.platform.ingestion.mapper.DocumentMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * bad case 证据提取器：按 assistant 消息 id 组装「原始现场」证据快照。
 *
 * <p>取数两处：{@code sa_message}（问题/答案/citations）+ {@code sa_agent_trace.steps}
 * （意图分类与召回的槽位写入）。归集时取一次固化进 {@code sa_eval_item.evidence}，之后不依赖轨迹长存。</p>
 *
 * <p><b>槽名解析收口在本类</b>：{@code detail.slotWrites} 的 key 是代码契约（随图演进），
 * 一律引用图声明常量（{@link KnowledgeQaGraphFactory} / {@link KnowledgeReactGraphFactory}），
 * 不写字面量；前端零解析、只消费 {@link BadcaseEvidence} 结构。</p>
 *
 * <p>形状兼容对齐 {@code KnowledgeRunner.toChunks} 的口径：knowledge 线优先
 * {@code kb_chunks_all}（跨轮累积，ARRAY），空则 {@code kb_chunks_data}（单轮，OBJECT{chunks:[…]}）；
 * react 线取 {@code react_tool_chunks}（累积，ARRAY）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceExtractor {

    private static final String USER_ROLE = "user";

    /** 证据快照保留的命中片段条数上限（现场回顾不求全量） */
    private static final int MAX_CHUNKS = 10;
    /** 命中片段 preview 截断长度 */
    private static final int PREVIEW_CHARS = 200;
    /** 证据快照保留的 citations 条数上限 */
    private static final int MAX_CITATIONS = 20;
    /** 快照里答案的截断长度（原文仍在 sa_message，此处只为防快照过大） */
    private static final int ANSWER_MAX_CHARS = 4000;

    /**
     * react 线命中累积槽在轨迹里的键名（实测 2026-10-11）：图声明常量是 region 展开后的
     * {@code react_tool_chunks}（引擎槽位视角，KnowledgeRunner 用它读 RunResult.slots），
     * 但 {@code step.slotWrites} 里记的是节点写入时的原始名 {@code tool_chunks}——
     * 两个都要认（前端 AgentTraceTree 是同款兼容）。
     */
    private static final List<String> REACT_CHUNK_SLOT_KEYS = List.of(
            KnowledgeReactGraphFactory.TOOL_CHUNKS_SLOT, "tool_chunks");

    private final MessageMapper messageMapper;
    private final AgentTraceService agentTraceService;
    private final DocumentMapper documentMapper;
    private final DocCollectionMapper docCollectionMapper;
    private final ObjectMapper objectMapper;

    /**
     * 组装证据快照。
     *
     * @param messageId assistant 消息 id（SSE meta.messageId）
     * @return 证据快照；消息不存在返回 null（调用方报 404）
     */
    public BadcaseEvidence extract(Long messageId) {
        MessageEntity assistant = messageMapper.selectById(messageId);
        if (assistant == null) {
            return null;
        }
        AgentTraceEntity trace = agentTraceService.getByMessageId(messageId);
        String question = trace != null && StrUtil.isNotBlank(trace.getQuestion())
                ? trace.getQuestion()
                : lastUserQuestion(assistant);
        List<BadcaseEvidence.CitationBrief> citations = parseCitations(assistant.getCitations());

        boolean hasTrace = trace != null && StrUtil.isNotBlank(trace.getSteps());
        BadcaseEvidence.IntentEvidence intent = null;
        BadcaseEvidence.RetrievalEvidence retrieval = null;
        String note = null;
        if (hasTrace) {
            try {
                JsonNode steps = objectMapper.readTree(trace.getSteps());
                intent = extractIntent(steps);
                retrieval = extractRetrieval(steps);
            } catch (Exception e) {
                // 解析失败不阻断归集：证据降级为「无归因数据」，人工判断
                log.warn("[Badcase] 轨迹解析失败 messageId={}: {}", messageId, e.getMessage());
                note = "轨迹解析失败：" + e.getMessage();
            }
        } else {
            note = "无 agent 轨迹（闲聊 / 缓存重放 / 降级路径），归因证据不足";
        }
        return new BadcaseEvidence(
                question,
                assistant.getConversationId(),
                StrUtil.maxLength(assistant.getContent(), ANSWER_MAX_CHARS),
                trace == null ? null : trace.getTraceId(),
                trace == null ? null : trace.getParadigm(),
                hasTrace,
                intent,
                retrieval,
                citations,
                resolveHitCollections(retrieval, citations),
                note);
    }

    /**
     * 命中片段所属的文档集合（domain 推荐的运行期信号，按命中条数排序）。
     *
     * <p>集合归属以 doc_id 反查 {@code sa_document} 为准：chunk metadata 里的 collection_id
     * 只有 knowledge 线 / 较新入库的文档才有，react 线实测缺失（2026-10-11）。chunks 为空时
     * 退回 citations 的引用文档。查不到集合归属（独立文件）不返回。</p>
     */
    private List<BadcaseEvidence.HitCollection> resolveHitCollections(
            BadcaseEvidence.RetrievalEvidence retrieval, List<BadcaseEvidence.CitationBrief> citations) {
        Map<String, Integer> hitsByDoc = new LinkedHashMap<>();
        if (retrieval != null && retrieval.chunks() != null) {
            for (BadcaseEvidence.ChunkBrief chunk : retrieval.chunks()) {
                if (StrUtil.isNotBlank(chunk.docId())) {
                    hitsByDoc.merge(chunk.docId(), 1, Integer::sum);
                }
            }
        }
        if (hitsByDoc.isEmpty()) {
            for (BadcaseEvidence.CitationBrief citation : citations) {
                if (StrUtil.isNotBlank(citation.docId())) {
                    hitsByDoc.merge(citation.docId(), 1, Integer::sum);
                }
            }
        }
        if (hitsByDoc.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> hitsByCollection = new LinkedHashMap<>();
        List<DocumentEntity> documents = documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                .in(DocumentEntity::getDocId, hitsByDoc.keySet())
                .select(DocumentEntity::getDocId, DocumentEntity::getCollectionId));
        for (DocumentEntity doc : documents) {
            if (doc.getCollectionId() != null) {
                hitsByCollection.merge(doc.getCollectionId(), hitsByDoc.getOrDefault(doc.getDocId(), 1), Integer::sum);
            }
        }
        if (hitsByCollection.isEmpty()) {
            return List.of();
        }
        List<BadcaseEvidence.HitCollection> out = new ArrayList<>();
        for (DocCollectionEntity collection : docCollectionMapper.selectList(
                new LambdaQueryWrapper<DocCollectionEntity>().in(DocCollectionEntity::getId, hitsByCollection.keySet()))) {
            out.add(new BadcaseEvidence.HitCollection(collection.getId(), collection.getName(),
                    hitsByCollection.get(collection.getId())));
        }
        out.sort(Comparator.comparingInt((BadcaseEvidence.HitCollection h) ->
                h.hits() == null ? 0 : h.hits()).reversed());
        return out;
    }

    /** 上一条 user 消息（id 游标口径同 ChatMessageWriter.historyContext：同会话、id 小于本条、倒序取首条）。 */
    private String lastUserQuestion(MessageEntity assistant) {
        MessageEntity user = messageMapper.selectOne(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, assistant.getConversationId())
                .eq(MessageEntity::getRole, USER_ROLE)
                .lt(MessageEntity::getId, assistant.getId())
                .orderByDesc(MessageEntity::getId)
                .last("LIMIT 1"));
        return user == null ? null : user.getContent();
    }

    /**
     * 意图产物：取最后一个含 intent 的槽位写入（多轮图里意图只算一次，取最后以防覆盖写），
     * route_target 可能由后续路由节点写入，单独扫。
     */
    private BadcaseEvidence.IntentEvidence extractIntent(JsonNode steps) {
        JsonNode intentSw = null;
        String routeTarget = null;
        for (JsonNode step : steps) {
            JsonNode sw = slotWritesOf(step);
            if (sw == null) {
                continue;
            }
            if (sw.has(KnowledgeQaGraphFactory.INTENT_SLOT)) {
                intentSw = sw;
            }
            if (sw.has(KnowledgeQaGraphFactory.ROUTE_TARGET_SLOT)) {
                routeTarget = textOf(sw.get(KnowledgeQaGraphFactory.ROUTE_TARGET_SLOT));
            }
        }
        if (intentSw == null && routeTarget == null) {
            return null;
        }
        return new BadcaseEvidence.IntentEvidence(
                intentSw == null ? null : textOf(intentSw.get(KnowledgeQaGraphFactory.INTENT_SLOT)),
                intentSw == null ? null : doubleOf(intentSw.get(KnowledgeQaGraphFactory.INTENT_CONFIDENCE_SLOT)),
                intentSw == null ? null : boolOf(intentSw.get(KnowledgeQaGraphFactory.NEEDS_RETRIEVAL_SLOT)),
                intentSw == null ? null : boolOf(intentSw.get(KnowledgeQaGraphFactory.NEEDS_WEB_SEARCH_SLOT)),
                intentSw == null ? null : textOf(intentSw.get(KnowledgeQaGraphFactory.INTENT_REASON_SLOT)),
                routeTarget);
    }

    /**
     * 检索产物：通道统计取最后一份 {@code kb_chunks_data.retrieval}（单轮实际检索口径）；
     * 命中片段按出口口径选源（chunks_all 累积集优先，空则 data 单轮，再退 react 累积）。
     */
    private BadcaseEvidence.RetrievalEvidence extractRetrieval(JsonNode steps) {
        JsonNode chunksAll = null;
        JsonNode chunksData = null;
        JsonNode reactChunks = null;
        for (JsonNode step : steps) {
            JsonNode sw = slotWritesOf(step);
            if (sw == null) {
                continue;
            }
            if (isPresent(sw, KnowledgeQaGraphFactory.CHUNKS_ALL_SLOT)) {
                chunksAll = sw.get(KnowledgeQaGraphFactory.CHUNKS_ALL_SLOT);
            }
            if (isPresent(sw, KnowledgeQaGraphFactory.CHUNKS_DATA_SLOT)) {
                chunksData = sw.get(KnowledgeQaGraphFactory.CHUNKS_DATA_SLOT);
            }
            for (String slot : REACT_CHUNK_SLOT_KEYS) {
                if (isPresent(sw, slot)) {
                    reactChunks = sw.get(slot);
                }
            }
        }

        Integer channelCandidates = null;
        Integer finalHits = null;
        List<BadcaseEvidence.ChannelHits> channels = null;
        JsonNode retrieval = chunksData == null ? null : chunksData.path("retrieval");
        if (retrieval != null && retrieval.isObject()) {
            channels = new ArrayList<>();
            int sum = 0;
            for (JsonNode ch : retrieval.path("channels")) {
                int hits = ch.path("hits").asInt(0);
                sum += hits;
                channels.add(new BadcaseEvidence.ChannelHits(ch.path("name").asText(null), hits));
            }
            channelCandidates = sum;
            finalHits = retrieval.path("finalHits").asInt(0);
        }

        List<BadcaseEvidence.ChunkBrief> briefs = briefsOf(firstNonEmpty(
                asList(chunksAll), asList(chunksData), asList(reactChunks)));
        if (channelCandidates == null && finalHits == null && briefs.isEmpty()) {
            return null;
        }
        return new BadcaseEvidence.RetrievalEvidence(channelCandidates, finalHits, channels, briefs);
    }

    /** 命中片段摘要（取前 {@link #MAX_CHUNKS} 条，content 截断为 preview）。 */
    private List<BadcaseEvidence.ChunkBrief> briefsOf(JsonNode chunks) {
        List<BadcaseEvidence.ChunkBrief> out = new ArrayList<>();
        if (chunks == null) {
            return out;
        }
        for (JsonNode c : chunks) {
            if (out.size() >= MAX_CHUNKS) {
                break;
            }
            JsonNode metadata = c.path("metadata");
            out.add(new BadcaseEvidence.ChunkBrief(
                    c.path("ref").isInt() ? c.get("ref").asInt() : null,
                    metadata.path("doc_id").asText(null),
                    c.path("docName").asText(null),
                    doubleOf(c.get("score")),
                    doubleOf(c.get("originalScore")),
                    c.path("channel").asText(null),
                    StrUtil.maxLength(c.path("content").asText(""), PREVIEW_CHARS)));
        }
        return out;
    }

    /** sa_message.citations（[{ref,docId,docName,chunkCount,…}]）→ 精简投影。 */
    private List<BadcaseEvidence.CitationBrief> parseCitations(String citationsJson) {
        if (StrUtil.isBlank(citationsJson)) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(citationsJson);
            if (!arr.isArray()) {
                return List.of();
            }
            List<BadcaseEvidence.CitationBrief> out = new ArrayList<>();
            for (JsonNode c : arr) {
                if (out.size() >= MAX_CITATIONS) {
                    break;
                }
                out.add(new BadcaseEvidence.CitationBrief(
                        c.path("docId").asText(null),
                        c.path("docName").asText(null),
                        c.path("chunkCount").isInt() ? c.get("chunkCount").asInt() : null));
            }
            return out;
        } catch (Exception e) {
            log.warn("[Badcase] citations 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ===== JsonNode 小工具（防御式：缺失键 / 类型漂移一律降级为 null，不抛） =====

    /** 步骤的槽位写入（detail.slotWrites），非对象返回 null。 */
    private static JsonNode slotWritesOf(JsonNode step) {
        JsonNode sw = step.path("detail").path("slotWrites");
        return sw.isObject() ? sw : null;
    }

    private static boolean isPresent(JsonNode slotWrites, String slot) {
        return slotWrites.has(slot) && !slotWrites.get(slot).isNull();
    }

    /** 槽值 → 裸 List：兼容 {@code {chunks:[…]}}（OBJECT 派生槽）与裸数组（ARRAY 槽）两种形状。 */
    private static JsonNode asList(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        JsonNode nested = node.path("chunks");
        return nested.isArray() ? nested : null;
    }

    private static JsonNode firstNonEmpty(JsonNode... candidates) {
        for (JsonNode c : candidates) {
            if (c != null && !c.isEmpty()) {
                return c;
            }
        }
        return null;
    }

    private static String textOf(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Double doubleOf(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.asDouble();
    }

    private static Boolean boolOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        // 槽位写入可能是字符串化的布尔（兼容旧数据）
        return Boolean.parseBoolean(node.asText());
    }
}
