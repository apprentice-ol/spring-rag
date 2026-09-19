package com.jjx.customer.platform.eval.runner;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import com.jjx.ai.llmobservability.observation.propagation.ContextPropagator;
import com.jjx.ai.llmobservability.observation.span.TelemetrySpan;
import com.jjx.customer.platform.business.FrameworkKnowledgeRunner;
import com.jjx.customer.platform.business.knowledge.RewritePolicy;
import com.jjx.customer.platform.business.orchestration.rag.RagContextAssembler;
import com.jjx.customer.platform.common.exception.ClientException;
import com.jjx.customer.platform.config.properties.AgentProperties;
import com.jjx.customer.platform.config.properties.ChatProperties;
import com.jjx.customer.platform.eval.config.EvalProperties;
import com.jjx.customer.platform.eval.dao.entity.EvalDatasetEntity;
import com.jjx.customer.platform.eval.dao.entity.EvalItemEntity;
import com.jjx.customer.platform.eval.dao.entity.EvalMetricEntity;
import com.jjx.customer.platform.eval.dao.entity.EvalRunEntity;
import com.jjx.customer.platform.eval.dao.mapper.EvalDatasetMapper;
import com.jjx.customer.platform.eval.dao.mapper.EvalItemMapper;
import com.jjx.customer.platform.eval.dao.mapper.EvalMetricMapper;
import com.jjx.customer.platform.eval.dao.mapper.EvalRunMapper;
import com.jjx.customer.platform.eval.domain.EvalParamSnapshot;
import com.jjx.customer.platform.eval.framework.*;
import com.jjx.customer.platform.ingestion.domain.entity.DocumentEntity;
import com.jjx.customer.platform.ingestion.mapper.DocumentMapper;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalBudget;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 评测跑批引擎：对某数据集逐条调 {@link } 检索，采集召回 doc_ids，计算检索指标，落库 + 聚合。
 *
 * <p><b>走可插拔 agent</b>（stage5 改造）：按 run 参数快照的 paradigm 选范式（naive/react），
 * naive 等价改造前的"直接调 retrievalEngine"。agent 内部检索阶段 VectorSearchChannel 自行 embed query，
 * 故本类不预存 query_embedding（字段保留供未来"预计算向量"优化）。</p>
 *
 * <p>并发：虚拟线程池 + {@link Semaphore} 限流（单实例；多实例再换 Redisson 信号量）。submit 时用
 * {@link ContextPropagator#()} 包装 Runnable，把 MDC/OTel 传播到虚拟线程（traceId 贯通的前提）。
 * 异常隔离：单 item 失败记一条 error 指标（score=-1），不中断整 run。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalRunner {

    private final TelemetryTemplate ragTelemetry;
    private final FrameworkKnowledgeRunner frameworkKnowledgeRunner;
    private final AgentProperties agentProperties;
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;
    private final EvalProperties evalProperties;
    /** 答案质量评测（answerEval）用：生成答案 + LLM-as-judge 都走裸 client（不带查询链 Advisor）。
     *  字段注入 + @Qualifier：ChatClient 有两个实现 bean，lombok 构造器注入无法带限定符 */
    @Autowired
    @Qualifier("ingestionChatClient")
    private ChatClient ingestionChatClient;

    private final EvalRunMapper evalRunMapper;
    private final EvalItemMapper evalItemMapper;
    private final EvalMetricMapper evalMetricMapper;
    private final EvalDatasetMapper evalDatasetMapper;
    private final DocumentMapper documentMapper;
    /** 评测维度注册表（doc 集适配器 + 所有 EvalScorer bean）与输出 sink（落库 + Langfuse 推送） */
    private final EvalScorerRegistry scorerRegistry;
    private final List<EvalResultSink> resultSinks;
    /** 线上同款上下文组装器：answerEval 的生成输入与生产 streamRagResponse 完全一致（评测即真实） */
    private final RagContextAssembler ragContextAssembler;
    /** 单条重评 attempt 分配的分布式锁（并发同 item 重评防 attempt 冲突） */
    private final RedissonClient redissonClient;
    /** 集合隔离防呆检查用（集合在向量库的实际向量数） */
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 向量表名（与 spring.ai.vectorstore.pgvector.table-name 同源） */
    @Value("${spring.ai.vectorstore.pgvector.table-name:spring_ai_store_vector}")
    private String vectorTable;

    /** 进度回写间隔：每 N 条 item 落一次 done 进度（降频减 DB 写，最终值由 run() 收尾补齐） */
    private static final int PROGRESS_INTERVAL = 10;

    /** 未指定范围与数量时的默认抽样比例（全量的 10%，至少 1 条）；防止误触全量评测打爆下游 */
    private static final double DEFAULT_SAMPLE_RATIO = 0.10;

    /**
     * 全局 item 级限流（bean 单例，所有 run 共享）：此前每 run 各 new 一个 Semaphore，
     * 多 run 并行时全局 LLM 并发 = run 数 × concurrency，会打穿下游限流。
     * 收敛为全局恒等于 rag.eval.concurrency（run 级并发另由 EvalConcurrencyGuard 上限保护）。
     */
    private Semaphore itemLimiter;

    @PostConstruct
    void initItemLimiter() {
        this.itemLimiter = new Semaphore(evalProperties.getConcurrency());
    }

    /**
     * 后台跑批入口（由 EvalService.triggerRun 在虚拟线程里调用）。
     *
     * @param runId       运行记录 id（sa_eval_run.id）
     * @param category    按分类筛选（qa/summarization/adversarial）；null/空 则不限
     * @param limit       抽样条数上限；null/≤0 跑全量
     * @param onlyItemIds 重试场景：限定只跑这些 itemId（本次任务实际评测过的条目）；null/空 按 category/limit 正常筛选抽样
     */
    public void run(Long runId, String category, Integer limit, Collection<Long> onlyItemIds) {
        EvalRunEntity runEntity = evalRunMapper.selectById(runId);
        if (runEntity == null) {
            log.warn("[Eval] run {} 不存在，跳过", runId);
            return;
        }
        try {
            EvalParamSnapshot params = this.parseParams(runEntity.getParamSnapshot());

            LambdaQueryWrapper<EvalItemEntity> queryWrapper = new LambdaQueryWrapper<EvalItemEntity>()
                    .eq(EvalItemEntity::getDatasetId, runEntity.getDatasetId())
                    .eq(EvalItemEntity::getEnabled, 1);

            List<EvalItemEntity> evalItemEntityList = evalItemMapper.selectList(queryWrapper);
            if (CollUtil.isNotEmpty(onlyItemIds)) {
                // 重试场景：限定为本次任务实际评测过的条目，不再按分类筛选/抽样
                // HashSet 免 O(n²)（List.contains 线性扫，全量 895 条时明显）
                Set<Long> only = new HashSet<>(onlyItemIds);
                evalItemEntityList.removeIf(item -> !only.contains(item.getId()));
            } else {
                ScopeResult scope = applyScopeAndSample(evalItemEntityList, category, limit);
                evalItemEntityList = scope.items();
                if (scope.note() != null) {
                    updateRunNote(runId, params, scope.note());
                    log.info("[Eval] run {} 抽样说明: {}", runId, scope.note());
                }
            }

            EvalRunEntity totalUpdate = new EvalRunEntity();
            totalUpdate.setId(runId);
            totalUpdate.setTotal(evalItemEntityList.size());
            totalUpdate.setUpdateTime(LocalDateTime.now());
            evalRunMapper.updateById(totalUpdate);

            if (evalItemEntityList.isEmpty()) {
                log.info("[Eval] run {} 数据集 {} 无启用条目", runId, runEntity.getDatasetId());
                this.finishRun(runId, Map.of(), "DONE");
                return;
            }


            List<String> list = evalItemEntityList.stream().flatMap(item -> parseDocIds(item.getExpectedDocIds()).stream()).distinct().toList();
            // 预查期望文档名（doc_id → name），写入 metric.detail 供前端展示期望/实际召回对比
            final Map<String, String> expectedNameMap = this.loadDocNames(list);
            // 预查期望文档集合归属（doc_id → collection_id）：评测检索按期望文档所在集合过滤，
            // 排除独立文件与其他集合（如自传 PDF）对混合语料评测的污染
            final Map<String, Long> docCollectionMap = this.loadDocCollections(list);

            // run 级上下文 + sink 启动通知（LangfusePushSink 预热 dataset 等，自吞异常不挡跑批）
            String datasetName = this.loadDatasetName(runEntity.getDatasetId());
            EvalRunContext runCtx = new EvalRunContext(runId, runEntity.getDatasetId(), datasetName,
                    params.effectiveParadigm(), params);
            for (EvalResultSink sink : resultSinks) {
                sink.onRunStart(runCtx);
            }

            AtomicInteger doneCount = new AtomicInteger();
            AtomicInteger retrievedItemCount = new AtomicInteger();
            Map<String, List<Double>> scoreAggregate = new ConcurrentHashMap<>();
            // 信号量限流：控制同时跑的 item 数，防止下游（LLM embed / rerank / DB）被打爆。
            // 用全局单例 itemLimiter（多 run 共享）：全局并发恒 = rag.eval.concurrency，
            // 不随并行 run 数线性放大；并发度由 rag.eval.concurrency 配置（默认 8）
            Semaphore semaphore = itemLimiter;

            // 固定并发 worker，而非「每条 item 一个虚拟线程」：真实并发度本就由 itemLimiter 限死在
            // rag.eval.concurrency，线程数没必要跟着 item 数涨——895 条各起一个虚拟线程，等于把
            // 895 份任务上下文（含各自未结束的 root span）同时挂在堆上。worker 数与信号量同源。
            int workers = Math.max(1, evalProperties.getConcurrency());
            try (ExecutorService executor = Executors.newFixedThreadPool(
                    workers, Thread.ofVirtual().name("eval-item-", 0).factory())) {
                List<Future<?>> futures = new ArrayList<>(evalItemEntityList.size());
                for (EvalItemEntity item : evalItemEntityList) {
                    futures.add(executor.submit(() -> runItemWithOwnTrace(runCtx, item, params,
                            doneCount, scoreAggregate, retrievedItemCount, expectedNameMap, docCollectionMap, semaphore)));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception ex) {
                        log.warn("[Eval] item 任务异常: {}", ex.getMessage());
                    }
                }
            }

            // 收尾：降频进度回写可能漏掉最后几条，这里强制刷成全量完成（done=总条数）
            updateProgress(runId, doneCount.get());

            Map<String, Map<String, Double>> aggregatedMetrics = computeAggregate(scoreAggregate);
            // 全部条目均无任何召回文档 → 实质失败（检索返回空，疑似参数过严 / 向量库无数据）
            if (retrievedItemCount.get() == 0) {
                finishRun(runId, aggregatedMetrics, "FAILED");
                log.warn("[Eval] run {} 标记失败: {} 条全部零召回（retrievedCount=0）", runId, evalItemEntityList.size());
            } else {
                finishRun(runId, aggregatedMetrics, "DONE");
                log.info("[Eval] run {} 完成: {} 条", runId, evalItemEntityList.size());
            }
        } catch (Exception ex) {
            log.error("[Eval] run {} 失败", runId, ex);
            finishRun(runId, Map.of(), "FAILED");
        }
    }



    /**
     * 单 item 独立 root trace 执行：开 {@code setNoParent} 的 root span，让该 item 的所有 step span
     * （normalize/rewrite/retrieve/各通道/rerank）挂在 item 自己的 trace 下，而非 HTTP 请求 trace。
     * <p>① 整批不再共用一个 traceId（批量可读性）；② item trace 与 item 同生命周期（root span 随 item 结束 end），
     * 避免 HTTP root 早结束后子 span export 丢失（OO 查不到的根因）。
     */
    /** 范围 + 抽样的应用结果：筛选/抽样后的条目 + 决策说明（无说明场景为 null）。 */
    record ScopeResult(List<EvalItemEntity> items, String note) {
    }

    /**
     * 范围与抽样规则（2026-08 修复：旧版 gate 条件写反——选了范围反而跳过筛选跑全量）：
     * <ul>
     *   <li>① 选范围 + 无数量 → 范围全量；</li>
     *   <li>② 选范围 + 数量在范围内 → 范围内随机抽 N；</li>
     *   <li>③ 选范围 + 数量 ≥ 范围条数 → 范围全量并写说明（note 供前端展示）；</li>
     *   <li>④ 无范围 → 全量随机抽 N；无数量则按默认比例抽样（至少 1 条），避免误跑全量。</li>
     * </ul>
     */
    static ScopeResult applyScopeAndSample(List<EvalItemEntity> items, String category, Integer limit) {
        List<EvalItemEntity> list = new ArrayList<>(items);
        int totalEnabled = list.size();
        String note = null;
        if (StrUtil.isNotEmpty(category)) {
            list.removeIf(item -> !category.equals(item.getCategory()));
            int scopeSize = list.size();
            if (scopeSize == 0) {
                note = "分类「" + category + "」无启用条目，实际评测 0 条";
            } else if (limit != null && limit > 0) {
                if (limit < scopeSize) {
                    Collections.shuffle(list);
                    list = new ArrayList<>(list.subList(0, limit));
                } else {
                    // 规则③：数量 ≥ 范围条数 → 全量评测，写说明供前端展示
                    note = "抽样数量 " + limit + " ≥ 范围内条目数 " + scopeSize + "，已全量评测该范围";
                }
            }
        } else {
            if (limit != null && limit > 0) {
                if (limit < totalEnabled) {
                    Collections.shuffle(list);
                    list = new ArrayList<>(list.subList(0, limit));
                }
            } else {
                // 规则④：未选范围也未指定数量 → 按默认比例抽样（至少 1 条），避免误跑全量
                int sampleSize = Math.max(1, (int) Math.ceil(totalEnabled * DEFAULT_SAMPLE_RATIO));
                if (sampleSize < totalEnabled) {
                    Collections.shuffle(list);
                    list = new ArrayList<>(list.subList(0, sampleSize));
                }
                note = "未指定范围与数量，按 " + Math.round(DEFAULT_SAMPLE_RATIO * 100)
                        + "% 比例抽样 " + list.size() + "/" + totalEnabled + " 条";
            }
        }
        return new ScopeResult(list, note);
    }

    // ---------- 答案质量评测（生成在编排层，judge 见 AnswerJudgeScorer） ----------

    private static final String ANSWER_GEN_PROMPT = """
            Answer the question based ONLY on the provided documents.
            If the documents do not contain the answer, say you don't know. Answer in English, concisely.""";

    /** 生成答案（裸 client，与线上 ragChatClient 的系统提示不同——评测基准用统一简化提示，跨 run 可比；
     *  输入=线上同款 RagContext 文本，与生产"最后推送 LLM 的数据"一致）。 */
    private String generateAnswer(String question, String contextText) {
        return ingestionChatClient.prompt()
                .system(ANSWER_GEN_PROMPT)
                .user(contextText + "\n\nQuestion: " + question)
                .call()
                .content();
    }

    /**
     * 把抽样说明写回 run 的 param_snapshot（note 字段），供前端运行详情展示
     * "数量超范围已全量评测 / 按比例抽了多少条"等决策信息。写失败只记日志，不影响跑批。
     */
    private void updateRunNote(Long runId, EvalParamSnapshot params, String note) {
        try {
            EvalRunEntity update = new EvalRunEntity();
            update.setId(runId);
            update.setParamSnapshot(objectMapper.writeValueAsString(params.withNote(note)));
            update.setUpdateTime(LocalDateTime.now());
            evalRunMapper.updateById(update);
        } catch (Exception ex) {
            log.warn("[Eval] run {} 写抽样说明失败: {}", runId, ex.getMessage());
        }
    }

    /**
     * 跑单个评测条目（批量跑批用）：开 item 独立 root trace，调 runOneItem
     * @param runCtx 评测运行上下文
     * @param item 评测条目
     * @param params 评测参数
     * @param doneCount 已处理条目计数
     * @param scoreAggregate 评分聚合结果
     * @param retrievedCount 召回条目计数
     * @param expectedNameMap 预期文档名称映射
     * @param docCollectionMap 文档集合映射
     * @param semaphore 信号量限流
     */
    private void runItemWithOwnTrace(EvalRunContext runCtx, EvalItemEntity item,
                                     EvalParamSnapshot params, AtomicInteger doneCount,
                                     Map<String, List<Double>> scoreAggregate, AtomicInteger retrievedCount,
                                     Map<String, String> expectedNameMap, Map<String, Long> docCollectionMap,
                                     Semaphore semaphore) {
        // 先抢并发许可再开 trace：此前是先开 root trace 再 acquire，被限流的 item 会带着一个
        // 未结束的 root span 在信号量上排队——排队时长记进 span.duration（把实际耗时的统计口径也带歪了），
        // 且全量 run 会同时挂起 item 数个空 trace。现在只有真正在跑的 item 才有 root span。
        try {
            semaphore.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("[Eval] item {} 等待并发许可时中断: {}", item.getId(), ex.getMessage());
            return;
        }
        try {
            // 开 item 独立的 root trace（无父）：metric.trace_id 记 item 自己的 traceId（前端/OO 跳转一致），
            // 子 span 由 ContextPropagation 传播挂到 item root 下。startRoot 已把 traceId 写 MDC。
            try (TelemetrySpan root = ragTelemetry.openTrace("eval.item")) {
                root.tag("eval.item_id", item.getId());
                root.tag("eval.run_id", runCtx.runId());
                root.traceInput(item.getQuestion());
                try {
                    EvalSample sample = runOneItem(runCtx, item, params, doneCount,
                            scoreAggregate, retrievedCount, expectedNameMap, docCollectionMap);
                    root.traceOutput(sample.error() != null
                            ? "error: " + sample.error()
                            : "retrieved=" + sample.retrievedDocIds());
                } finally {
                    MDC.clear();
                }
            }
        } finally {
            semaphore.release();
        }
    }

    /**
     * 跑单个评测条目（批量跑批用）：组样本（检索 + 可选答案生成）→ 全维度打分 → 分发 sinks
     * （落库 + Langfuse 推送，各 sink 自吞异常）→ 累计聚合 → 进度回写。
     */
    private EvalSample runOneItem(EvalRunContext runCtx, EvalItemEntity item, EvalParamSnapshot params,
                                  AtomicInteger doneCount, Map<String, List<Double>> scoreAggregate,
                                  AtomicInteger retrievedCount, Map<String, String> expectedNameMap,
                                  Map<String, Long> docCollectionMap) {
        EvalSample sample = scoreItem(runCtx, item, params, 0, null, expectedNameMap, docCollectionMap);
        List<EvalScore> scores = scoreAll(sample);
        for (EvalResultSink sink : resultSinks) {
            sink.onItemResult(sample, scores);
        }
        // 聚合只算原始批（attempt=0）
        if (sample.error() == null) {
            for (EvalScore score : scores) {
                scoreAggregate.computeIfAbsent(score.name(), k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(score.value());
            }
        }
        if (sample.retrieved()) {
            retrievedCount.incrementAndGet();
        }
        // 进度回写降频：每 PROGRESS_INTERVAL 条 update 一次（避免每条一次 DB update）；最终值由 run() 收尾补齐
        int done = doneCount.incrementAndGet();
        if (done % PROGRESS_INTERVAL == 0) {
            updateProgress(runCtx.runId(), done);
        }
        return sample;
    }

    /** 遍历全部评测维度（registry：doc 集适配器 + context/answer 等 bean scorer）；单维度异常跳过。 */
    private List<EvalScore> scoreAll(EvalSample sample) {
        List<EvalScore> scores = new ArrayList<>();
        for (var scorer : scorerRegistry.scorers()) {
            try {
                scores.addAll(scorer.score(sample));
            } catch (Exception ex) {
                log.warn("[Eval] scorer {} 异常（跳过该维度）item={}: {}", scorer.name(), sample.itemId(), ex.getMessage());
            }
        }
        return scores;
    }

    /** 回写 run 进度（已完成条数 done），供前端轮询。 */
    private void updateProgress(Long runId, int done) {
        EvalRunEntity progressUpdate = new EvalRunEntity();
        progressUpdate.setId(runId);
        progressUpdate.setDone(done);
        evalRunMapper.updateById(progressUpdate);
    }

    /**
     * 检索 + 组样本（纯计算，不落库、不打分）。
     * <p>rewrite 开启时用 {@link QueryRewriter} 改写 query 塞进 {@code rewrittenQuery}
     * （VectorSearchChannel 用它做 embed），否则用原 question；走 agent 检索 → 文档级去重 →
     * <b>线上同款 {@link RagContextAssembler} 组装上下文</b>（"最后推送 LLM 的数据"，context 级
     * 比对与 answerEval 生成输入都以它为准，评测与生产完全一致）→（answerEval 开关）生成系统答案。
     *
     * @return 评测样本；error 非 null 表示检索失败
     */
    private EvalSample scoreItem(EvalRunContext runCtx, EvalItemEntity item, EvalParamSnapshot params,
                                 int attempt, String remark, Map<String, String> expectedNameMap,
                                 Map<String, Long> docCollectionMap) {
        List<String> expectedDocIds = parseDocIds(item.getExpectedDocIds());
        List<String> expectedDocNames = expectedDocIds.stream()
                .map(id -> expectedNameMap.getOrDefault(id, id)).toList();
        EvalSample.Builder builder = EvalSample.builder()
                .runId(runCtx.runId())
                .itemId(item.getId())
                .attempt(attempt)
                .remark(remark)
                .datasetName(runCtx.datasetName())
                .paradigm(params.effectiveParadigm())
                .rewrite(params.rewrite())
                .perQuestion(params.perQuestionEnabled())
                .category(item.getCategory())
                .question(item.getQuestion())
                .expectedDocIds(expectedDocIds)
                .expectedDocNames(expectedDocNames)
                .expectedAnswer(item.getExpectedAnswer());
        long latencyMillis = 0;
        try {
            String question = item.getQuestion();
            // 改写交给图内的 kb_rewrite 节点：rewrite 开关映射成改写策略随运行上下文注入。
            // 先前这里自己算好 rewrittenQuery 塞进 SearchContext——图化之后那个字段已经
            // 不参与检索（查询由节点产出），等于开关接在了一根断线上：开关怎么拨，
            // 送进检索的查询都一模一样，于是"改写有没有用"永远测不出来。
            RewritePolicy rewritePolicy = params.rewrite() ? RewritePolicy.FORCE : RewritePolicy.OFF;
            SearchContext searchContext = SearchContext.builder()
                    .query(question)
                    .topK(params.topK())
                    .threshold(params.threshold())
                    // 检索范围隔离：期望文档同属一个集合时限定该集合（LiveRAG 题只查 cid=2，不再捞到自传 PDF）。
                    // 防呆：sa_document 的集合归属与向量 metadata 可能错位（归集只改了 sa_document 的历史缺口），
                    // 集合在向量库一条向量都没有时，隔离等于把检索限死在空集 → 必然 0 召回，退回全库并告警
                    .collectionId(resolveSafeCollection(expectedDocIds, docCollectionMap, item.getId()))
                    // per-question 实验：检索限定在该题期望文档内（模拟官方独立语料，验证混合语料是否唯一瓶颈）
                    .restrictedDocIds(params.perQuestionEnabled() && !expectedDocIds.isEmpty()
                            ? new LinkedHashSet<>(expectedDocIds) : null)
                    .budget(RetrievalBudget.builder()
                            .recallBudget(params.recallBudget())
                            .candidateLimit(params.candidateLimit())
                            .contextTopK(params.contextTopK())
                            .build())
                    .metadata(Map.of("intent", "EVAL"))
                    .build();

            long startTimeMillis = System.currentTimeMillis();
            List<RetrievedChunk> retrievedChunks;
            // knowledge 轴走框架主线（Agent + Workflow，检索为 extension tool）；
            // 其余范式（react_loop 等）仍走旧链，供对拍期对照。
            // 全轴走框架主线：knowledge（确定性检索节点）/ react_loop（工具循环节点）
            // forEval：改写按开关走，并把本轮钉死在知识检索上——分类器把题认成运维故障
            // 就会被转走，这里拿到空 chunks 静默记 0 分，掉的是分类器的域覆盖不是检索质量
            FrameworkKnowledgeRunner.KnowledgeAnswer answer = frameworkKnowledgeRunner.retrieve(
                    question, searchContext, params.effectiveParadigm(),
                    FrameworkKnowledgeRunner.RunContext.forEval(rewritePolicy));
            if (answer.isRoutedElsewhere()) {
                log.warn("[Eval] run={} item={} 被路由到 {}（强制检索未生效），该题按 0 召回计",
                        runCtx.runId(), item.getId(), answer.routeTarget());
            }
            retrievedChunks = answer.chunks();
            builder.agentTrace(toJson(answer.trace()));
            latencyMillis = System.currentTimeMillis() - startTimeMillis;
            builder.traceId(MDC.get("traceId"));

            // 文档级去重保序：同一文档多个 chunk 只算一次，按首次出现排序
            LinkedHashSet<String> docIdSet = new LinkedHashSet<>();
            LinkedHashSet<String> docNameSet = new LinkedHashSet<>();
            for (RetrievedChunk chunk : retrievedChunks) {
                Map<String, Object> metadata = chunk.getMetadata();
                if (metadata == null) {
                    continue;
                }
                Object docId = metadata.get("doc_id");
                if (ObjUtil.isNotEmpty(docId)) {
                    docIdSet.add(docId.toString());
                }
                Object docName = metadata.get("doc_name");
                if (ObjUtil.isNotEmpty(docName)) {
                    docNameSet.add(docName.toString());
                }
            }
            builder.retrievedDocIds(new ArrayList<>(docIdSet)).retrievedDocNames(new ArrayList<>(docNameSet));

            if (!retrievedChunks.isEmpty()) {
                builder.finalChunks(retrievedChunks);
                // 线上同款组装：context 级比对（context_recall 等）与答案生成共用，评测即真实输入
                RagContextAssembler.RagContext context = ragContextAssembler.buildContextText(retrievedChunks);
                builder.context(context);
                // 答案生成（answerEval 开关，judge 由 AnswerJudgeScorer 承担）：有黄金答案才生成；
                // 生成失败不阻断（答案指标缺省不打，检索指标照常落库）
                if (params.answerEvalEnabled() && StrUtil.isNotBlank(item.getExpectedAnswer())) {
                    try {
                        String generated = generateAnswer(question, context.text());
                        builder.generatedAnswer(generated);
                        String genPreview = generated == null ? "" : generated;
                        log.info("[Eval] 答案生成 item={}: 生成=\"{}\"", item.getId(),
                                genPreview.length() > 80 ? genPreview.substring(0, 80) + "..." : genPreview);
                    } catch (Exception ex) {
                        log.warn("[Eval] 答案生成失败（跳过，不影响检索指标）item={}: {}", item.getId(), ex.getMessage());
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("[Eval] item {} agent({}) 失败: {}", item.getId(), params.effectiveParadigm(), ex.getMessage());
            builder.error(ex.getMessage());
        }
        builder.latencyMs(latencyMillis);
        return builder.build();
    }

    /**
     * 单条重评（保留历史）：复用原 run 的检索参数快照（rewrite 用入参覆盖，便于对比裸检索 vs 含改写），
     * 跑一次检索打分，写 attempt=max+1 的指标行（带 remark/rewrite）。不修改 run 聚合/done/total。
     *
     * @return 本次重评的 attempt 序号
     */
    /**
     * 单条重评（保留历史）：复用原 run 参数快照，rewrite/paradigm/perQuestion 用入参覆盖
     * （便于对比裸检索 vs 改写、不同范式、是否限定期望文档），跑一次检索打分，写 attempt=max+1 的指标行。不修改 run 聚合/done/total。
     *
     * @param paradigm    agent 范式；null/空 则沿用原 run 范式
     * @param perQuestion 仅检索期望文档；null 则沿用原 run 设置
     * @return 本次重评的 attempt 序号
     */
    public int reevaluateSingle(Long runId, Long itemId, boolean rewrite, String remark, String paradigm, Boolean perQuestion) {

        EvalRunEntity runEntity = evalRunMapper.selectById(runId);
        if (runEntity == null) {
            throw new ClientException("运行不存在: " + runId);
        }

        EvalItemEntity item = evalItemMapper.selectById(itemId);
        if (item == null) {
            throw new ClientException("条目不存在: " + itemId);
        }

        // 复用原 run 参数快照，rewrite/paradigm/perQuestion 用入参覆盖（paradigm/perQuestion 为空则沿用原 run）
        EvalParamSnapshot baseParams = parseParams(runEntity.getParamSnapshot());
        String effectiveParadigm = (paradigm != null && !paradigm.isBlank()) ? paradigm : baseParams.paradigm();
        Boolean effectivePerQuestion = perQuestion != null ? perQuestion : baseParams.perQuestion();
        // 重评语义：条目有标准答案时强制生成系统回答 + judge 打分（不依赖原 run 是否开过 answerEval），
        // 保证每次重评都产出「系统回答 vs 标准答案」对照落库
        boolean evalAnswer = baseParams.answerEvalEnabled() || StrUtil.isNotBlank(item.getExpectedAnswer());
        EvalParamSnapshot params = new EvalParamSnapshot(baseParams.topK(), baseParams.threshold(),
                baseParams.recallBudget(), baseParams.candidateLimit(), baseParams.contextTopK(), rewrite,
                effectiveParadigm, baseParams.note(), evalAnswer ? Boolean.TRUE : null, effectivePerQuestion);

        // attempt 分配加分布式锁：nextAttempt 是 read-then-write，并发同 item 重评会拿到相同 attempt
        //（历史行冲突）。attempt 定即释放锁，检索/落库不在锁内（锁持有毫秒级）
        int attempt;
        RLock attemptLock = redissonClient.getLock("eval:reeval:" + runId + ":" + itemId);
        boolean locked = false;
        try {
            locked = attemptLock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new ClientException("该条目正在重评，请稍后再试");
            }
            attempt = nextAttempt(runId, itemId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("重评获取锁被中断");
        } finally {
            if (locked) {
                attemptLock.unlock();
            }
        }

        // 单条重评是同步 HTTP 请求（Span.current() = HTTP server span）：直接把 trace 级 IO 写到 HTTP 根 span，
        // 一个请求一个 trace、Langfuse 列表 IO 稳定显示（不依赖子 LLM span 的 gen_ai.*，ReAct 多轮下常丢）。
        // 不像批量 eval 走 startRoot 独立 trace——那是"一次 HTTP 跑多条 item"，必须各自独立，否则挤一个 trace 没法看。
        ragTelemetry.tag("eval.run_id", runId);
        ragTelemetry.tag("eval.item_id", itemId);
        ragTelemetry.tag("eval.attempt", attempt);
        ragTelemetry.tag("eval.paradigm", effectiveParadigm);
        ragTelemetry.traceInput(item.getQuestion());

        EvalRunContext runCtx = new EvalRunContext(runId, runEntity.getDatasetId(),
                loadDatasetName(runEntity.getDatasetId()), effectiveParadigm, params);
        EvalSample sample = this.scoreItem(runCtx, item, params, attempt, remark,
                loadDocNames(parseDocIds(item.getExpectedDocIds())),
                loadDocCollections(parseDocIds(item.getExpectedDocIds())));
        List<EvalScore> scores = scoreAll(sample);
        for (EvalResultSink sink : resultSinks) {
            sink.onItemResult(sample, scores);
        }
        boolean hit = sample.error() == null && !sample.retrievedDocIds().isEmpty();
        ragTelemetry.tag("eval.hit", hit);
        ragTelemetry.traceOutput(sample.error() != null
                ? "error: " + sample.error()
                : "retrieved=" + sample.retrievedDocIds());
        log.info("[Eval] 单条重评 runId={}, itemId={}, attempt={}, rewrite={}, 命中={}",
                runId, itemId, attempt, rewrite, hit);
        return attempt;
    }

    /** 该 run+item 当前最大 attempt（保留历史，新重评 = max+1）。 */
    private int nextAttempt(Long runId, Long itemId) {
        List<EvalMetricEntity> existingMetrics = evalMetricMapper.selectList(new LambdaQueryWrapper<EvalMetricEntity>()
                .eq(EvalMetricEntity::getRunId, runId)
                .eq(EvalMetricEntity::getItemId, itemId)
                .select(EvalMetricEntity::getAttempt));
        int maxAttempt = 0;
        for (EvalMetricEntity metric : existingMetrics) {
            if (metric.getAttempt() != null && metric.getAttempt() > maxAttempt) {
                maxAttempt = metric.getAttempt();
            }
        }
        return maxAttempt + 1;
    }

    /**
     * 结束 run：更新 run 的聚合指标和状态。
     * @param runId 运行 ID
     * @param aggregateMetrics 聚合指标
     * @param status 运行状态
     */
    private void finishRun(Long runId, Map<String, ?> aggregateMetrics, String status) {
        EvalRunEntity runUpdate = new EvalRunEntity();
        runUpdate.setId(runId);
        runUpdate.setStatus(status);
        runUpdate.setAggregateMetrics(toJson(aggregateMetrics));
        runUpdate.setFinishedAt(LocalDateTime.now());
        runUpdate.setUpdateTime(LocalDateTime.now());
        evalRunMapper.updateById(runUpdate);
    }

    /**
     * 数据集名（Langfuse dataset 同步依据）：本地数据集名 + {@code rag.eval.langfuse.dataset-prefix}
     * 前缀（多环境共用一个 Langfuse 时隔离）；数据集缺失/删除回退 {@code dataset-{id}}。
     */
    private String loadDatasetName(Long datasetId) {
        String effective;
        try {
            EvalDatasetEntity dataset = evalDatasetMapper.selectById(datasetId);
            effective = dataset != null && StrUtil.isNotBlank(dataset.getName())
                    ? dataset.getName() : ("dataset-" + datasetId);
        } catch (Exception e) {
            effective = "dataset-" + datasetId;
        }
        String prefix = evalProperties.getLangfuse().getDatasetPrefix();
        return prefix == null ? effective : prefix + effective;
    }

    /** 聚合各指标的 count/mean/median/min/max（中位数排序后取中位元素）。 */
    private Map<String, Map<String, Double>> computeAggregate(Map<String, List<Double>> scoreSamples) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        scoreSamples.forEach((metricName, scoreList) -> {
            DoubleSummaryStatistics stats = scoreList.stream().mapToDouble(Double::doubleValue).summaryStatistics();
            List<Double> sorted = new ArrayList<>(scoreList);
            Collections.sort(sorted);
            double median = sorted.isEmpty() ? 0.0
                    : (sorted.size() % 2 == 1
                            ? sorted.get(sorted.size() / 2)
                            : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0);
            Map<String, Double> metricStats = new LinkedHashMap<>();
            metricStats.put("count", (double) stats.getCount());
            metricStats.put("mean", round4(stats.getAverage()));
            metricStats.put("median", round4(median));
            metricStats.put("min", round4(stats.getMin()));
            metricStats.put("max", round4(stats.getMax()));
            result.put(metricName, metricStats);
        });
        return result;
    }

    private EvalParamSnapshot parseParams(String json) {
        int topK = chatProperties.getTopK();
        double threshold = chatProperties.getSimilarityThreshold();
        int recallBudget = chatProperties.getRecallBudget();
        int candidateLimit = chatProperties.getCandidateLimit();
        int contextTopK = chatProperties.getContextTopK();
        boolean rewrite = false;
        String paradigm = "knowledge";
        Boolean answerEval = null;
        Boolean perQuestion = null;
        if (json != null && !json.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(json);
                topK = node.path("topK").asInt(topK);
                threshold = node.path("threshold").asDouble(threshold);
                recallBudget = node.path("recallBudget").asInt(recallBudget);
                candidateLimit = node.path("candidateLimit").asInt(candidateLimit);
                contextTopK = node.path("contextTopK").asInt(contextTopK);
                rewrite = node.path("rewrite").asBoolean(rewrite);
                paradigm = node.path("paradigm").asText(paradigm);
                answerEval = node.hasNonNull("answerEval") ? node.path("answerEval").asBoolean() : null;
                perQuestion = node.hasNonNull("perQuestion") ? node.path("perQuestion").asBoolean() : null;
            } catch (Exception e) {
                log.warn("[Eval] param_snapshot 解析失败，用 ChatProperties 默认: {}", e.getMessage());
            }
        }
        return new EvalParamSnapshot(topK, threshold, recallBudget, candidateLimit, contextTopK, rewrite,
                paradigm, null, answerEval, perQuestion);
    }

    private List<String> parseDocIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[Eval] expected_doc_ids 解析失败: {}", e.getMessage());
            return List.of();
        }
    }


    /**
     * 加载文档名（sa_document）。
     *
     * @param docIds 文档 id 列表
     * @return doc_id → 文档名 映射
     */
    private Map<String, String> loadDocNames(Collection<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        List<DocumentEntity> documents = documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                .in(DocumentEntity::getDocId, docIds)
                .select(DocumentEntity::getDocId, DocumentEntity::getName));

        return documents.stream().collect(Collectors.toMap(
                DocumentEntity::getDocId,
                DocumentEntity::getName,
                (name1, name2) -> name1, // 处理重复 key 的情况
                LinkedHashMap::new));
    }

    /** 预查期望文档的集合归属（doc_id → collection_id），供检索时按集合过滤（评测不检索集合外文档）。 */
    private Map<String, Long> loadDocCollections(Collection<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        List<DocumentEntity> documents = documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                .in(DocumentEntity::getDocId, docIds)
                .select(DocumentEntity::getDocId, DocumentEntity::getCollectionId));
        Map<String, Long> map = new LinkedHashMap<>();
        for (DocumentEntity d : documents) {
            if (d.getCollectionId() != null) {
                map.put(d.getDocId(), d.getCollectionId());
            }
        }
        return map;
    }

    /**
     * 推断单条 item 的检索集合：期望文档全部归属同一集合 → 该集合（检索范围隔离，
     * 排除独立文件与其他集合的污染）；混合/无集合 → null（不过滤，保持全库语义）。
     */
    private static Long resolveCollection(List<String> expectedDocIds, Map<String, Long> docCollectionMap) {
        if (expectedDocIds == null || expectedDocIds.isEmpty() || docCollectionMap.isEmpty()) {
            return null;
        }
        Long resolved = null;
        for (String docId : expectedDocIds) {
            Long cid = docCollectionMap.get(docId);
            if (cid == null) {
                return null; // 任一期望文档无集合（独立文件）→ 全库模式
            }
            if (resolved == null) {
                resolved = cid;
            } else if (!resolved.equals(cid)) {
                return null; // 期望文档跨集合 → 不过滤
            }
        }
        return resolved;
    }

    /**
     * resolveCollection 的防呆版：解析出的集合在向量库一条向量都没有时退回全库。
     * <p>触发场景：sa_document 的集合归属与向量 metadata 错位（历史归集只改 sa_document 不同步向量，
     * 或期望文档的向量根本不在本库——数据集从他处迁移）。此时按集合隔离 = 检索限死空集 → 必然 0 召回，
     * 全 run 被标 FAILED（服务器实际踩过）。退全库会引入跨集合噪声，但对"全错"是严格改善。</p>
     */
    private Long resolveSafeCollection(List<String> expectedDocIds, Map<String, Long> docCollectionMap, Long itemId) {
        Long cid = resolveCollection(expectedDocIds, docCollectionMap);
        if (cid == null) {
            return null;
        }
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + vectorTable + " WHERE metadata->>'collection_id' = ?",
                    Long.class, String.valueOf(cid));
            if (count == null || count == 0) {
                log.warn("[Eval] item {} 期望文档所在集合 {} 在向量库无任何向量"
                        + "（归集未同步向量 metadata 或期望文档不在本库），退回全库检索", itemId, cid);
                return null;
            }
        } catch (Exception e) {
            log.warn("[Eval] item {} 集合 {} 向量数检查失败，保守退回全库检索: {}", itemId, cid, e.getMessage());
            return null;
        }
        return cid;
    }

    /** 对象 → JSON 字符串；序列化失败返回 null（不抛异常，避免阻断落库）。 */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 保留 4 位小数（聚合指标用）。 */
    private static double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
