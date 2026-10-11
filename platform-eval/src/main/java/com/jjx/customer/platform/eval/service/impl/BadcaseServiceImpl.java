package com.jjx.customer.platform.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.common.dto.PageResult;
import com.jjx.customer.platform.common.exception.ClientException;
import com.jjx.customer.platform.common.exception.ResourceNotFoundException;
import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.config.properties.ChatProperties;
import com.jjx.customer.platform.eval.badcase.BadcaseTriageRules;
import com.jjx.customer.platform.eval.badcase.EvidenceExtractor;
import com.jjx.customer.platform.eval.config.EvalProperties;
import com.jjx.customer.platform.eval.dao.entity.EvalDatasetEntity;
import com.jjx.customer.platform.eval.dao.entity.EvalItemEntity;
import com.jjx.customer.platform.eval.dao.mapper.EvalDatasetMapper;
import com.jjx.customer.platform.eval.dao.mapper.EvalItemMapper;
import com.jjx.customer.platform.eval.domain.BadcaseCollectRequest;
import com.jjx.customer.platform.eval.domain.BadcaseEvidence;
import com.jjx.customer.platform.eval.domain.BadcaseManualRequest;
import com.jjx.customer.platform.eval.domain.BadcaseOrigin;
import com.jjx.customer.platform.eval.domain.BadcasePromoteRequest;
import com.jjx.customer.platform.eval.domain.BadcaseStats;
import com.jjx.customer.platform.eval.domain.BadcaseStatus;
import com.jjx.customer.platform.eval.domain.BadcaseTriage;
import com.jjx.customer.platform.eval.domain.BadcaseView;
import com.jjx.customer.platform.eval.domain.CandidateDoc;
import com.jjx.customer.platform.eval.domain.TriageStage;
import com.jjx.customer.platform.eval.service.BadcaseService;
import com.jjx.customer.platform.knowledge.retrieval.MultiChannelRetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalBudget;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * bad case 归集服务实现：归集落池（DRAFT）+ 规则归因。
 *
 * <p>统一模型：badcase 就是 {@code sa_eval_item} 的一行（source=feedback），
 * 不建第二张表；两区制的归集池首次使用时自动创建。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BadcaseServiceImpl implements BadcaseService {

    /** 归集池数据集名（首次归集自动建；跨领域汇聚的草稿区） */
    public static final String POOL_DATASET_NAME = "badcase 归集池";

    /** 来源大类：badcase 固定 feedback（与 source 既有语义对齐） */
    private static final String SOURCE_FEEDBACK = "feedback";

    /** 转正起草标准答案的 prompt 资产 key（正文 = prompts/eval/badcase-draft-answer.md） */
    private static final String DRAFT_ANSWER_ASSET = "eval/badcase-draft-answer";

    /** 候选检索默认条数 */
    private static final int DEFAULT_CANDIDATE_TOP_N = 20;

    /** 一次起草最多基于的文档数（防长上下文拖死生成） */
    private static final int MAX_DRAFT_DOCS = 5;

    /** 起草时每篇文档取的最大片段数 */
    private static final int DRAFT_CHUNKS_PER_DOC = 15;

    private final EvidenceExtractor evidenceExtractor;
    private final EvalProperties evalProperties;
    private final EvalItemMapper itemMapper;
    private final EvalDatasetMapper datasetMapper;
    private final ObjectMapper objectMapper;
    /** 候选检索（@Primary 为 CachingRetrievalEngine；metadata 带标注标记以区分缓存 key） */
    private final RetrievalEngine retrievalEngine;
    /** 检索默认参数（阈值/预算与线上评测同源） */
    private final ChatProperties chatProperties;
    private final PromptStore promptStore;
    private final JdbcTemplate jdbcTemplate;

    /** 起草标准答案用裸 client（与评测答案生成同款，不带查询链 Advisor）。
     *  字段注入 + @Qualifier：ChatClient 有多个实现 bean，构造器注入无法带限定符 */
    @Autowired
    @Qualifier("ingestionChatClient")
    private ChatClient ingestionChatClient;

    /** 向量表名（与 spring.ai.vectorstore.pgvector.table-name 同源） */
    @Value("${spring.ai.vectorstore.pgvector.table-name:spring_ai_store_vector}")
    private String vectorTable;

    @Override
    @Transactional
    public BadcaseView collect(BadcaseCollectRequest request) {
        if (request == null || request.messageId() == null) {
            throw new ClientException("messageId 不能为空");
        }
        // 幂等（by messageId）：重复点踩 = 更新原因/备注，不重复建条目
        EvalItemEntity existing = findByMessageId(request.messageId());
        if (existing != null) {
            return refreshExisting(existing, request);
        }

        BadcaseEvidence evidence = evidenceExtractor.extract(request.messageId());
        if (evidence == null) {
            throw new ResourceNotFoundException("消息不存在: " + request.messageId());
        }
        BadcaseTriageRules.Verdict verdict = BadcaseTriageRules.judge(evidence);

        EvalItemEntity item = new EvalItemEntity();
        item.setDatasetId(ensurePoolDataset());
        item.setItemKey("msg-" + request.messageId());
        item.setQuestion(StrUtil.blankToDefault(evidence.question(), "(未取到原始问题)"));
        item.setSource(SOURCE_FEEDBACK);
        item.setOrigin(StrUtil.blankToDefault(request.origin(), BadcaseOrigin.ONLINE_DISLIKE.code()));
        item.setBadcaseStatus(BadcaseStatus.DRAFT.name());
        item.setEnabled(0);
        item.setDomain(request.domain());
        item.setFeedbackReason(request.reason());
        item.setFeedbackNote(request.note());
        item.setOriginRef(toJson(originRefOf(evidence, request.messageId())));
        item.setEvidence(toJson(evidence));
        item.setTriage(toJson(new BadcaseTriage(verdict.stage().name(), true, verdict.note())));
        item.setCreateTime(LocalDateTime.now());
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.insert(item);

        datasetMapper.update(null, new LambdaUpdateWrapper<EvalDatasetEntity>()
                .eq(EvalDatasetEntity::getId, item.getDatasetId())
                .setSql("item_count = item_count + 1"));

        log.info("[Badcase] 归集: itemId={}, messageId={}, origin={}, stage={}",
                item.getId(), request.messageId(), item.getOrigin(), verdict.stage());
        return toView(item);
    }

    /**
     * 重复归集：更新用户侧字段（原因/备注/领域）。
     *
     * <p>证据可能需补取：agent 轨迹异步落库，首次点踩可能早于轨迹写入（拿到"无轨迹"证据）；
     * 再次点踩时补一次提取。人工确认过的 triage（auto=false）不被规则覆盖。</p>
     */
    private BadcaseView refreshExisting(EvalItemEntity existing, BadcaseCollectRequest request) {
        boolean changed = false;
        if (StrUtil.isNotBlank(request.reason())) {
            existing.setFeedbackReason(request.reason());
            changed = true;
        }
        if (StrUtil.isNotBlank(request.note())) {
            existing.setFeedbackNote(request.note());
            changed = true;
        }
        if (StrUtil.isNotBlank(request.domain())) {
            existing.setDomain(request.domain());
            changed = true;
        }
        BadcaseEvidence old = parseEvidence(existing.getEvidence());
        // 补取/刷新证据的三类场景：① 首次归集早于轨迹异步落库（旧证据无轨迹）；
        // ② evidence 结构升级（老快照缺 hitCollections 等新字段，补一次让管理页拿到新信号）。
        // 二者都以「新证据确实有轨迹」为前提——防轨迹被清理后用空证据覆盖掉好证据。
        boolean staleStructure = old != null && old.hasTrace() && old.hitCollections() == null;
        if (old == null || !old.hasTrace() || staleStructure) {
            BadcaseEvidence fresh = evidenceExtractor.extract(request.messageId());
            if (fresh != null && fresh.hasTrace()) {
                existing.setEvidence(toJson(fresh));
                BadcaseTriage current = parseTriage(existing.getTriage());
                if (current == null || Boolean.TRUE.equals(current.auto())) {
                    BadcaseTriageRules.Verdict verdict = BadcaseTriageRules.judge(fresh);
                    existing.setTriage(toJson(new BadcaseTriage(verdict.stage().name(), true, verdict.note())));
                }
                changed = true;
                log.info("[Badcase] 补取证据成功: itemId={}, messageId={}", existing.getId(), request.messageId());
            }
        }
        if (changed) {
            existing.setUpdateTime(LocalDateTime.now());
            itemMapper.updateById(existing);
        }
        return toView(existing);
    }

    /** 幂等查找：全表按 origin_ref.messageId（不限数据集——条目可能已转正搬家）。 */
    private EvalItemEntity findByMessageId(Long messageId) {
        return itemMapper.selectOne(new LambdaQueryWrapper<EvalItemEntity>()
                .eq(EvalItemEntity::getSource, SOURCE_FEEDBACK)
                .apply("origin_ref->>'messageId' = {0}", String.valueOf(messageId))
                .orderByDesc(EvalItemEntity::getId)
                .last("LIMIT 1"));
    }

    /** 归集池：按名查/建（并发首建撞唯一约束时重查）。 */
    private Long ensurePoolDataset() {
        EvalDatasetEntity pool = datasetMapper.selectOne(new LambdaQueryWrapper<EvalDatasetEntity>()
                .eq(EvalDatasetEntity::getName, POOL_DATASET_NAME)
                .last("LIMIT 1"));
        if (pool != null) {
            return pool.getId();
        }
        EvalDatasetEntity created = new EvalDatasetEntity();
        created.setName(POOL_DATASET_NAME);
        created.setDescription("bad case 归集池：线上点踩 / 评测低分 / 巡检归集的草稿区，"
                + "补完 ground truth 后转正到领域数据集（见 docs/plans/2026-10-11-badcase-collection.md）");
        created.setItemCount(0);
        created.setCreateTime(LocalDateTime.now());
        created.setUpdateTime(LocalDateTime.now());
        try {
            datasetMapper.insert(created);
            return created.getId();
        } catch (DuplicateKeyException e) {
            EvalDatasetEntity again = datasetMapper.selectOne(new LambdaQueryWrapper<EvalDatasetEntity>()
                    .eq(EvalDatasetEntity::getName, POOL_DATASET_NAME)
                    .last("LIMIT 1"));
            if (again == null) {
                throw e;
            }
            return again.getId();
        }
    }

    @Override
    @Transactional
    public BadcaseView createManual(BadcaseManualRequest request) {
        if (request == null || StrUtil.isBlank(request.question())) {
            throw new ClientException("question 不能为空");
        }
        EvalItemEntity item = new EvalItemEntity();
        item.setDatasetId(ensurePoolDataset());
        item.setQuestion(request.question());
        item.setSource(SOURCE_FEEDBACK);
        item.setOrigin(BadcaseOrigin.MANUAL.code());
        item.setBadcaseStatus(BadcaseStatus.DRAFT.name());
        item.setEnabled(0);
        item.setDomain(request.domain());
        item.setFeedbackNote(request.note());
        item.setTriage(toJson(new BadcaseTriage(TriageStage.UNKNOWN.name(), true,
                "手工录入，无线上现场证据，需人工判断")));
        item.setCreateTime(LocalDateTime.now());
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.insert(item);
        datasetMapper.update(null, new LambdaUpdateWrapper<EvalDatasetEntity>()
                .eq(EvalDatasetEntity::getId, item.getDatasetId())
                .setSql("item_count = item_count + 1"));
        log.info("[Badcase] 手工录入: itemId={}, question={}", item.getId(),
                StrUtil.maxLength(item.getQuestion(), 40));
        return toView(item);
    }

    // ===== 管理页：列表 / 详情 / 归因确认 / 状态 / 统计 / 字典 / 已反馈标记 =====

    @Override
    public PageResult<BadcaseView> list(String origin, String domain, String status, String keyword,
                                        int page, int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<EvalItemEntity> filter = new LambdaQueryWrapper<EvalItemEntity>()
                .eq(EvalItemEntity::getSource, SOURCE_FEEDBACK);
        if (StrUtil.isNotBlank(origin)) {
            filter.eq(EvalItemEntity::getOrigin, origin);
        }
        if (StrUtil.isNotBlank(domain)) {
            filter.eq(EvalItemEntity::getDomain, domain);
        }
        if (StrUtil.isNotBlank(status)) {
            filter.eq(EvalItemEntity::getBadcaseStatus, status);
        }
        if (StrUtil.isNotBlank(keyword)) {
            filter.like(EvalItemEntity::getQuestion, keyword);
        }
        // 手写分页（项目未配 MyBatis-Plus 分页拦截器，selectPage 会退化为全量）
        Long total = itemMapper.selectCount(filter);
        List<EvalItemEntity> rows = itemMapper.selectList(filter
                .orderByDesc(EvalItemEntity::getId)
                .last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size));
        return new PageResult<>(total == null ? 0 : total, rows.stream().map(this::toView).toList());
    }

    @Override
    public BadcaseView get(Long id) {
        return toView(requireBadcase(id));
    }

    @Override
    @Transactional
    public BadcaseView updateTriage(Long id, String stage, String note, String domain) {
        EvalItemEntity item = requireBadcase(id);
        if (StrUtil.isBlank(stage)) {
            throw new ClientException("stage 不能为空");
        }
        try {
            TriageStage.valueOf(stage);
        } catch (IllegalArgumentException e) {
            throw new ClientException("非法归因环节: " + stage);
        }
        // auto=false：人工确认过的结论不再被规则覆盖（重复归集补证据时也不动）
        item.setTriage(toJson(new BadcaseTriage(stage, false, note)));
        if (StrUtil.isNotBlank(domain)) {
            item.setDomain(domain);
        }
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.updateById(item);
        return toView(item);
    }

    @Override
    @Transactional
    public BadcaseView updateStatus(Long id, String status) {
        EvalItemEntity item = requireBadcase(id);
        if (BadcaseStatus.READY.name().equals(item.getBadcaseStatus())) {
            throw new ClientException("已转正的 bad case 不能忽略（如需下线请直接删除评测条目）");
        }
        if (!BadcaseStatus.IGNORED.name().equals(status) && !BadcaseStatus.DRAFT.name().equals(status)) {
            throw new ClientException("status 只允许 IGNORED（忽略）或 DRAFT（恢复）");
        }
        item.setBadcaseStatus(status);
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.updateById(item);
        return toView(item);
    }

    @Override
    public BadcaseStats stats() {
        List<EvalItemEntity> all = itemMapper.selectList(new LambdaQueryWrapper<EvalItemEntity>()
                .select(EvalItemEntity::getId, EvalItemEntity::getOrigin, EvalItemEntity::getDomain,
                        EvalItemEntity::getBadcaseStatus, EvalItemEntity::getTriage)
                .eq(EvalItemEntity::getSource, SOURCE_FEEDBACK));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> byStage = new LinkedHashMap<>();
        Map<String, Long> byDomain = new LinkedHashMap<>();
        Map<String, Long> byOrigin = new LinkedHashMap<>();
        for (EvalItemEntity item : all) {
            bump(byStatus, StrUtil.blankToDefault(item.getBadcaseStatus(), BadcaseStatus.DRAFT.name()));
            BadcaseTriage triage = parseTriage(item.getTriage());
            bump(byStage, triage == null || StrUtil.isBlank(triage.stage())
                    ? TriageStage.UNKNOWN.name() : triage.stage());
            bump(byDomain, StrUtil.blankToDefault(item.getDomain(), "(未分领域)"));
            bump(byOrigin, StrUtil.blankToDefault(item.getOrigin(), "(未知渠道)"));
        }
        return new BadcaseStats(all.size(), byStatus, byStage, byDomain, byOrigin);
    }

    @Override
    public List<String> domains() {
        // 领域体系以「领域数据集」为准（转正目标与领域下拉同一口径，归集到的领域必然有处可去）；
        // 存量条目的既有领域并入保证可筛，静态配置只作库为空时的兜底。
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (EvalDatasetEntity ds : datasetMapper.selectList(new LambdaQueryWrapper<EvalDatasetEntity>()
                .select(EvalDatasetEntity::getName)
                .ne(EvalDatasetEntity::getName, POOL_DATASET_NAME)
                .isNotNull(EvalDatasetEntity::getName))) {
            merged.add(ds.getName());
        }
        for (EvalItemEntity item : itemMapper.selectList(new LambdaQueryWrapper<EvalItemEntity>()
                .select(EvalItemEntity::getDomain)
                .eq(EvalItemEntity::getSource, SOURCE_FEEDBACK)
                .isNotNull(EvalItemEntity::getDomain))) {
            if (StrUtil.isNotBlank(item.getDomain())) {
                merged.add(item.getDomain());
            }
        }
        merged.addAll(evalProperties.getBadcase().getDomains());
        return List.copyOf(merged);
    }

    @Override
    public Map<Long, Long> feedbackFlags(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        String in = messageIds.stream().filter(Objects::nonNull).distinct()
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(","));
        if (in.isEmpty()) {
            return Map.of();
        }
        List<EvalItemEntity> rows = itemMapper.selectList(new LambdaQueryWrapper<EvalItemEntity>()
                .select(EvalItemEntity::getId, EvalItemEntity::getOriginRef)
                .eq(EvalItemEntity::getSource, SOURCE_FEEDBACK)
                .apply("origin_ref->>'messageId' IN (" + in + ")"));
        Map<Long, Long> flags = new LinkedHashMap<>();
        for (EvalItemEntity row : rows) {
            Long mid = messageIdOf(row.getOriginRef());
            if (mid != null) {
                flags.put(mid, row.getId());
            }
        }
        return flags;
    }

    // ===== 转正：候选检索 / 答案起草 / 搬家 =====

    @Override
    public List<CandidateDoc> searchCandidates(Long id, Long targetDatasetId, Integer topN) {
        EvalItemEntity item = requireBadcase(id);
        EvalDatasetEntity target = null;
        if (targetDatasetId != null) {
            target = datasetMapper.selectById(targetDatasetId);
            if (target == null) {
                throw new ResourceNotFoundException("目标数据集不存在: " + targetDatasetId);
            }
        }
        int limit = topN == null || topN <= 0 ? DEFAULT_CANDIDATE_TOP_N : Math.min(topN, 50);
        SearchContext searchContext = SearchContext.builder()
                .query(item.getQuestion())
                .topK(limit)
                .threshold(chatProperties.getSimilarityThreshold())
                .collectionId(target == null ? null : target.getCollectionId())
                .budget(RetrievalBudget.builder()
                        .recallBudget(chatProperties.getRecallBudget())
                        .candidateLimit(chatProperties.getCandidateLimit())
                        .contextTopK(chatProperties.getContextTopK())
                        .build())
                // 与线上/评测检索区分缓存 key：标注辅助要实时，不吃历史缓存
                .metadata(Map.of("intent", "BAD_CASE_ANNOTATION"))
                .build();
        MultiChannelRetrievalEngine.RetrievalResult result = retrievalEngine.retrieve(searchContext);
        List<CandidateDoc> candidates = new ArrayList<>();
        for (RetrievedChunk chunk : result.getFinalChunks()) {
            Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
            candidates.add(new CandidateDoc(
                    metadata.get("doc_id") == null ? null : String.valueOf(metadata.get("doc_id")),
                    metadata.get("doc_name") == null ? null : String.valueOf(metadata.get("doc_name")),
                    chunk.getScore(),
                    chunk.getChannelType() == null ? null : chunk.getChannelType().name(),
                    StrUtil.maxLength(chunk.getContent(), 200)));
        }
        return candidates;
    }

    @Override
    public String draftAnswer(Long id, List<String> docIds) {
        EvalItemEntity item = requireBadcase(id);
        if (docIds == null || docIds.isEmpty()) {
            throw new ClientException("docIds 不能为空");
        }
        if (docIds.size() > MAX_DRAFT_DOCS) {
            throw new ClientException("一次最多基于 " + MAX_DRAFT_DOCS + " 篇文档起草");
        }
        // 按 docId 直接取向量库片段，不走检索链：这里要的是"这篇文档讲了什么"，
        // 与相关度排序无关；且 rerank 相关度下限会把低分文档整批砍光（已知问题源）
        List<String> contents = new ArrayList<>();
        for (String docId : docIds) {
            contents.addAll(jdbcTemplate.query(
                    "SELECT content FROM " + vectorTable + " WHERE metadata->>'doc_id' = ? LIMIT ?",
                    (rs, rowNum) -> rs.getString(1), docId, DRAFT_CHUNKS_PER_DOC));
        }
        if (contents.isEmpty()) {
            throw new ClientException("勾选文档在向量库中没有内容（可能未成功入库）");
        }
        return ingestionChatClient.prompt()
                .system(promptStore.raw(DRAFT_ANSWER_ASSET))
                .user(String.join("\n\n---\n\n", contents) + "\n\nQuestion: " + item.getQuestion())
                .call()
                .content();
    }

    @Override
    @Transactional
    public BadcaseView promote(Long id, BadcasePromoteRequest request) {
        EvalItemEntity item = requireBadcase(id);
        if (!BadcaseStatus.DRAFT.name().equals(item.getBadcaseStatus())) {
            throw new ClientException("只有 DRAFT（未转正）的 bad case 可转正，当前状态: " + item.getBadcaseStatus());
        }
        if (request == null || request.targetDatasetId() == null) {
            throw new ClientException("targetDatasetId 不能为空");
        }
        EvalDatasetEntity target = datasetMapper.selectById(request.targetDatasetId());
        if (target == null) {
            throw new ResourceNotFoundException("目标数据集不存在: " + request.targetDatasetId());
        }
        if (POOL_DATASET_NAME.equals(target.getName())) {
            throw new ClientException("转正目标不能是归集池，请选择领域数据集");
        }
        if (request.expectedDocIds() == null || request.expectedDocIds().isEmpty()) {
            throw new ClientException("expected_doc_ids 不能为空（转正必须补 ground truth）");
        }

        Long sourceDatasetId = item.getDatasetId();
        item.setDatasetId(target.getId());
        item.setExpectedDocIds(toJson(request.expectedDocIds()));
        if (StrUtil.isNotBlank(request.expectedAnswer())) {
            item.setExpectedAnswer(request.expectedAnswer());
        }
        if (StrUtil.isNotBlank(request.domain())) {
            item.setDomain(request.domain());
        }
        item.setBadcaseStatus(BadcaseStatus.READY.name());
        item.setEnabled(1);
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.updateById(item);
        // item_count 迁移（原子自增/自减，避免并发丢更新）
        if (sourceDatasetId != null && !sourceDatasetId.equals(target.getId())) {
            datasetMapper.update(null, new LambdaUpdateWrapper<EvalDatasetEntity>()
                    .eq(EvalDatasetEntity::getId, sourceDatasetId)
                    .setSql("item_count = item_count - 1"));
            datasetMapper.update(null, new LambdaUpdateWrapper<EvalDatasetEntity>()
                    .eq(EvalDatasetEntity::getId, target.getId())
                    .setSql("item_count = item_count + 1"));
        }
        log.info("[Badcase] 转正: itemId={}, dataset {} → {}（{}）, docs={}",
                id, sourceDatasetId, target.getId(), target.getName(),
                request.expectedDocIds().size());
        return toView(item);
    }

    private EvalItemEntity requireBadcase(Long id) {
        EvalItemEntity item = itemMapper.selectById(id);
        if (item == null || !SOURCE_FEEDBACK.equals(item.getSource())) {
            throw new ResourceNotFoundException("bad case 不存在: " + id);
        }
        return item;
    }

    private Long messageIdOf(String originRefJson) {
        if (StrUtil.isBlank(originRefJson)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(originRefJson).path("messageId");
            return node.isNumber() ? node.asLong()
                    : (node.isMissingNode() ? null : Long.valueOf(node.asText()));
        } catch (Exception e) {
            return null;
        }
    }

    private static void bump(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
    }

    private Map<String, Object> originRefOf(BadcaseEvidence evidence, Long messageId) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("messageId", messageId);
        ref.put("conversationId", evidence.conversationId());
        ref.put("traceId", evidence.traceId());
        return ref;
    }

    private BadcaseView toView(EvalItemEntity item) {
        return new BadcaseView(
                item.getId(),
                item.getDatasetId(),
                item.getQuestion(),
                item.getOrigin(),
                item.getDomain(),
                item.getBadcaseStatus(),
                item.getFeedbackReason(),
                item.getFeedbackNote(),
                parseEvidence(item.getEvidence()),
                parseTriage(item.getTriage()),
                item.getExpectedAnswer(),
                countDocIds(item.getExpectedDocIds()),
                item.getCreateTime(),
                item.getUpdateTime());
    }

    private BadcaseEvidence parseEvidence(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, BadcaseEvidence.class);
        } catch (Exception e) {
            log.warn("[Badcase] evidence 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private BadcaseTriage parseTriage(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, BadcaseTriage.class);
        } catch (Exception e) {
            log.warn("[Badcase] triage 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private Integer countDocIds(String expectedDocIdsJson) {
        if (StrUtil.isBlank(expectedDocIdsJson)) {
            return 0;
        }
        try {
            List<Object> ids = objectMapper.readValue(expectedDocIdsJson, new TypeReference<List<Object>>() {
            });
            return ids.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[Badcase] JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
