package com.nageoffer.ai.rag.eval.runner;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.chat.agent.AgentRequest;
import com.nageoffer.ai.rag.chat.agent.AgentRegistry;
import com.nageoffer.ai.rag.chat.agent.AgentRetrievalResult;
import com.nageoffer.ai.rag.chat.agent.RagAgent;
import com.nageoffer.ai.rag.chat.normalize.QueryRewriter;
import com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.RetrievalBudget;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.config.properties.AgentProperties;
import com.nageoffer.ai.rag.config.properties.ChatProperties;
import com.nageoffer.ai.rag.eval.config.EvalProperties;
import com.nageoffer.ai.rag.eval.dao.entity.EvalItemEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalMetricEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalRunEntity;
import com.nageoffer.ai.rag.eval.dao.mapper.EvalItemMapper;
import com.nageoffer.ai.rag.eval.dao.mapper.EvalMetricMapper;
import com.nageoffer.ai.rag.eval.dao.mapper.EvalRunMapper;
import com.nageoffer.ai.rag.eval.domain.EvalParamSnapshot;
import com.nageoffer.ai.rag.eval.metrics.MetricScorer;
import com.nageoffer.ai.rag.eval.metrics.MrrScorer;
import com.nageoffer.ai.rag.eval.metrics.NdcgScorer;
import com.nageoffer.ai.rag.eval.metrics.PrecisionAtKScorer;
import com.nageoffer.ai.rag.eval.metrics.RecallAtKScorer;
import com.nageoffer.ai.rag.ingestion.domain.entity.DocumentEntity;
import com.nageoffer.ai.rag.ingestion.mapper.DocumentMapper;
import com.nageoffer.ai.llmobservability.observation.TelemetryTemplate;
import com.nageoffer.ai.llmobservability.observation.span.TelemetrySpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.nageoffer.ai.llmobservability.observation.propagation.ContextPropagator;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 评测跑批引擎：对某数据集逐条调 {@link RagAgent} 检索，采集召回 doc_ids，计算检索指标，落库 + 聚合。
 *
 * <p><b>走可插拔 agent</b>（stage5 改造）：按 run 参数快照的 paradigm 选范式（naive/react），
 * naive 等价改造前的"直接调 retrievalEngine"。agent 内部检索阶段 VectorSearchChannel 自行 embed query，
 * 故本类不预存 query_embedding（字段保留供未来"预计算向量"优化）。</p>
 *
 * <p>并发：虚拟线程池 + {@link Semaphore} 限流（单实例；多实例再换 Redisson 信号量）。submit 时用
 * {@link ContextPropagator#wrap()} 包装 Runnable，把 MDC/OTel 传播到虚拟线程（traceId 贯通的前提）。
 * 异常隔离：单 item 失败记一条 error 指标（score=-1），不中断整 run。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalRunner {

    private final TelemetryTemplate ragTelemetry;
    private final AgentRegistry agentRegistry;
    private final AgentProperties agentProperties;
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;
    private final EvalProperties evalProperties;
    private final QueryRewriter queryRewriter;

    private final EvalRunMapper evalRunMapper;
    private final EvalItemMapper evalItemMapper;
    private final EvalMetricMapper evalMetricMapper;
    private final DocumentMapper documentMapper;

    /** 进度回写间隔：每 N 条 item 落一次 done 进度（降频减 DB 写，最终值由 run() 收尾补齐） */
    private static final int PROGRESS_INTERVAL = 10;

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

            List<EvalItemEntity> evalItemEntityList = evalItemMapper.selectList(new LambdaQueryWrapper<EvalItemEntity>()
                    .eq(EvalItemEntity::getDatasetId, runEntity.getDatasetId())
                    .eq(EvalItemEntity::getEnabled, 1));
            if (CollUtil.isNotEmpty(onlyItemIds)) {
                // 重试场景：限定为本次任务实际评测过的条目，不再按分类筛选/抽样
                evalItemEntityList.removeIf(item -> !onlyItemIds.contains(item.getId()));
            } else {
                // 按分类筛选（category 为空则不限）
                if (StrUtil.isEmpty(category)) {
                    evalItemEntityList.removeIf(item -> !category.equals(item.getCategory()));
                }
                // 按数量抽样（随机；limit 为空/≤0 则跑全量匹配条目）
                if (limit != null && limit > 0 && limit < evalItemEntityList.size()) {
                    Collections.shuffle(evalItemEntityList);
                    evalItemEntityList = new ArrayList<>(evalItemEntityList.subList(0, limit));
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

            List<MetricScorer> scorers = buildScorers();
            AtomicInteger doneCount = new AtomicInteger();
            AtomicInteger retrievedItemCount = new AtomicInteger();
            Map<String, List<Double>> scoreAggregate = new ConcurrentHashMap<>();
            // 信号量限流：控制同时跑的 item 数，防止下游（LLM embed / rerank / DB）被打爆。
            // 并发度由 rag.eval.concurrency 配置（默认 8）；agent 范式越重（react 多次 LLM）越要调小
            Semaphore semaphore = new Semaphore(evalProperties.getConcurrency());

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(evalItemEntityList.size());
                for (EvalItemEntity item : evalItemEntityList) {
                    futures.add(executor.submit(() -> runItemWithOwnTrace(runId, item, scorers, params,
                            doneCount, scoreAggregate, retrievedItemCount, expectedNameMap, semaphore)));
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
    private void runItemWithOwnTrace(Long runId, EvalItemEntity item, List<MetricScorer> scorers,
                                     EvalParamSnapshot params, AtomicInteger doneCount,
                                     Map<String, List<Double>> scoreAggregate, AtomicInteger retrievedCount,
                                     Map<String, String> expectedNameMap, Semaphore semaphore) {
        // 开 item 独立的 root trace（无父）：metric.trace_id 记 item 自己的 traceId（前端/OO 跳转一致），
        // 子 span 由 ContextPropagation 传播挂到 item root 下。startRoot 已把 traceId 写 MDC。
        try (TelemetrySpan root = ragTelemetry.openTrace("eval.item")) {
            root.tag("eval.item_id", item.getId());
            root.tag("eval.run_id", runId);
            root.traceInput(item.getQuestion());
            try {
                semaphore.acquire();
                try {
                    ItemScore itemScore = runOneItem(runId, item, scorers, params, doneCount,
                            scoreAggregate, retrievedCount, expectedNameMap);
                    root.traceOutput(itemScore.error() != null
                            ? "error: " + itemScore.error()
                            : "retrieved=" + itemScore.gotDocIds());
                } finally {
                    semaphore.release();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                root.traceOutput("interrupted");
                log.warn("[Eval] item {} 中断: {}", item.getId(), ex.getMessage());
            } finally {
                MDC.clear();
            }
        }
    }

    /** 单条检索打分结果（纯计算，不落库）。error 非 null 表示检索失败。 */
    private record ItemScore(List<String> gotDocIds, List<String> gotDocNames,
                             long latency, String error, Map<String, Double> scores,
                             String agentTrace, String traceId, int llmCalls) {}

    /**
     * 跑单个评测条目（批量跑批用）：检索打分 → 落库（attempt=0）→ 累计聚合 → 进度回写。
     *
     * @param runId             运行记录 id
     * @param item              评测条目（黄金问答）
     * @param scorers           指标打分器列表
     * @param params            本次 run 的检索参数快照（含 paradigm）
     * @param doneCount         已完成条目计数（进度回写用，外部传入累加）
     * @param scoreAggregate    指标→分数列表 的聚合容器（只统计 attempt=0 原始批）
     * @param retrievedCount    有召回的条目计数（全 0 则整 run 标记 FAILED）
     * @param expectedNameMap   期望文档 doc_id→name 映射（写 metric.detail 用）
     */
    private ItemScore runOneItem(Long runId, EvalItemEntity item, List<MetricScorer> scorers,
                                 EvalParamSnapshot params, AtomicInteger doneCount,
                                 Map<String, List<Double>> scoreAggregate, AtomicInteger retrievedCount,
                                 Map<String, String> expectedNameMap) {
        ItemScore itemScore = scoreItem(item, params, scorers);
        boolean retrieved = persistMetrics(runId, item, itemScore, params, 0, null, expectedNameMap);
        // 聚合只算原始批（attempt=0）
        if (itemScore.error() == null) {
            for (Map.Entry<String, Double> entry : itemScore.scores().entrySet()) {
                scoreAggregate.computeIfAbsent(entry.getKey(), k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(entry.getValue());
            }
        }
        if (retrieved) {
            retrievedCount.incrementAndGet();
        }
        // 进度回写降频：每 PROGRESS_INTERVAL 条 update 一次（避免每条一次 DB update）；最终值由 run() 收尾补齐
        int done = doneCount.incrementAndGet();
        if (done % PROGRESS_INTERVAL == 0) {
            updateProgress(runId, done);
        }
        return itemScore;
    }

    /** 回写 run 进度（已完成条数 done），供前端轮询。 */
    private void updateProgress(Long runId, int done) {
        EvalRunEntity progressUpdate = new EvalRunEntity();
        progressUpdate.setId(runId);
        progressUpdate.setDone(done);
        evalRunMapper.updateById(progressUpdate);
    }

    /**
     * 检索 + 打分（纯计算，不落库）。
     * <p>rewrite 开启时用 {@link QueryRewriter} 改写 query 塞进 {@code rewrittenQuery}
     * （VectorSearchChannel 用它做 embed），否则用原 question；再走 agent 检索、文档级去重、逐指标打分。
     *
     * @param item    评测条目（取 question / expectedDocIds）
     * @param params  检索参数快照（topK/threshold/budget/rewrite/paradigm）
     * @param scorers 指标打分器列表
     * @return 检索打分结果（含召回 docIds/指标分/agent 轨迹/traceId）；error 非 null 表示检索失败
     */
    private ItemScore scoreItem(EvalItemEntity item, EvalParamSnapshot params, List<MetricScorer> scorers) {
        List<String> retrievedDocIds = List.of();
        List<String> retrievedDocNames = List.of();
        long latencyMillis = 0;
        String error = null;
        Map<String, Double> scores = new LinkedHashMap<>();
        String agentTrace = null;
        String traceId = null;
        int llmCalls = 0;
        try {
            String question = item.getQuestion();
            // rewrite 开 → LLM 改写后塞进 rewrittenQuery；关 → 原始 question
            String rewrittenQuery = params.rewrite() ? queryRewriter.rewrite(question) : question;
            SearchContext searchContext = SearchContext.builder()
                    .query(question)
                    .rewrittenQuery(rewrittenQuery)
                    .topK(params.topK())
                    .threshold(params.threshold())
                    .budget(RetrievalBudget.builder()
                            .recallBudget(params.recallBudget())
                            .candidateLimit(params.candidateLimit())
                            .contextTopK(params.contextTopK())
                            .build())
                    .metadata(Map.of("intent", "EVAL"))
                    .build();

            // 走可插拔 agent（按 params.paradigm 选范式；naive = 单次检索 baseline，等价改造前直接调 retrievalEngine）
            RagAgent agent = agentRegistry.require(params.effectiveParadigm());
            AgentRequest agentRequest = AgentRequest.forEval(question, searchContext, agentProperties, null);
            long startTimeMillis = System.currentTimeMillis();
            AgentRetrievalResult agentResult = agent.planAndRetrieve(agentRequest);
            latencyMillis = System.currentTimeMillis() - startTimeMillis;
            if (agentResult.trace() != null) {
                llmCalls = agentResult.trace().getLlmCallCount();
                agentTrace = toJson(agentResult.trace());
            }
            traceId = MDC.get("traceId");
            List<RetrievedChunk> retrievedChunks = agentResult.finalChunks();

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
            retrievedDocIds = new ArrayList<>(docIdSet);
            retrievedDocNames = new ArrayList<>(docNameSet);
            List<String> expectedDocIds = parseDocIds(item.getExpectedDocIds());
            for (MetricScorer scorer : scorers) {
                scores.put(scorer.name(), scorer.score(retrievedDocIds, expectedDocIds));
            }
        } catch (Exception ex) {
            log.warn("[Eval] item {} agent({}) 失败: {}", item.getId(), params.effectiveParadigm(), ex.getMessage());
            error = ex.getMessage();
        }
        return new ItemScore(retrievedDocIds, retrievedDocNames, latencyMillis, error, scores, agentTrace, traceId, llmCalls);
    }

    /**
     * 落库一条评测结果（含 attempt/rewrite/paradigm/agentTrace）。返回是否召回（gotDocIds 非空）。
     *
     * @param attempt         评估序号：0=原始批，>0=单条重评（保留历史）
     * @param remark          备注（重评时填写）
     * @param expectedNameMap 期望文档 doc_id→name 映射
     * @return 该条是否有召回（gotDocIds 非空）
     */
    private boolean persistMetrics(Long runId, EvalItemEntity item, ItemScore itemScore,
                                   EvalParamSnapshot params, int attempt, String remark,
                                   Map<String, String> expectedNameMap) {
        // 期望召回文档（黄金集自带）：与实际召回对称冗余落库，即便检索失败也写，便于前端「期望 vs 实际」对照
        List<String> expectedDocIds = parseDocIds(item.getExpectedDocIds());
        String expectedIdsJson = toJson(expectedDocIds);
        String expectedNamesJson = toJson(expectedDocIds.stream()
                .map(id -> expectedNameMap.getOrDefault(id, id)).toList());

        if (itemScore.error() != null) {
            EvalMetricEntity metricEntity = newErrorMetric(runId, item, itemScore, params, attempt, remark,
                    expectedIdsJson, expectedNamesJson);
            evalMetricMapper.insert(metricEntity);
            return false;
        }
        String detail = buildDetail(itemScore.gotDocIds(), expectedDocIds, expectedNameMap);
        String retrievedIdsJson = toJson(itemScore.gotDocIds());
        String retrievedNamesJson = toJson(itemScore.gotDocNames());
        // 批量 insert：一个 item 的多指标行攒成 list 一次 batch（替代逐条 insert，N 次 DB 往返→1 次）
        List<EvalMetricEntity> metricRows = new ArrayList<>(itemScore.scores().size());
        for (Map.Entry<String, Double> entry : itemScore.scores().entrySet()) {
            EvalMetricEntity metricEntity = new EvalMetricEntity();
            metricEntity.setRunId(runId);
            metricEntity.setItemId(item.getId());
            metricEntity.setQuestion(item.getQuestion());
            metricEntity.setRetrievedDocIds(retrievedIdsJson);
            metricEntity.setRetrievedDocNames(retrievedNamesJson);
            metricEntity.setExpectedDocIds(expectedIdsJson);
            metricEntity.setExpectedDocNames(expectedNamesJson);
            metricEntity.setMetricName(entry.getKey());
            metricEntity.setScore(BigDecimal.valueOf(entry.getValue()).setScale(4, RoundingMode.HALF_UP));
            metricEntity.setDetail(detail);
            metricEntity.setLatencyMs(itemScore.latency());
            metricEntity.setAttempt(attempt);
            metricEntity.setRemark(remark);
            metricEntity.setRewrite(params.rewrite());
            metricEntity.setParadigm(params.effectiveParadigm());
            metricEntity.setCategory(item.getCategory());
            metricEntity.setAgentTrace(itemScore.agentTrace());
            metricEntity.setTraceId(itemScore.traceId());
            metricRows.add(metricEntity);
        }
        Db.saveBatch(metricRows);
        return !itemScore.gotDocIds().isEmpty();
    }

    /** 构建一条 error 指标行（检索失败时 score=-1，仍冗余写期望文档便于前端对照）。 */
    private EvalMetricEntity newErrorMetric(Long runId, EvalItemEntity item, ItemScore itemScore,
                                            EvalParamSnapshot params, int attempt, String remark,
                                            String expectedIdsJson, String expectedNamesJson) {
        EvalMetricEntity metricEntity = new EvalMetricEntity();
        metricEntity.setRunId(runId);
        metricEntity.setItemId(item.getId());
        metricEntity.setQuestion(item.getQuestion());
        metricEntity.setMetricName("error");
        metricEntity.setScore(BigDecimal.valueOf(-1));
        metricEntity.setLatencyMs(itemScore.latency());
        metricEntity.setDetail(toJson(Map.of("error", itemScore.error())));
        metricEntity.setExpectedDocIds(expectedIdsJson);
        metricEntity.setExpectedDocNames(expectedNamesJson);
        metricEntity.setAttempt(attempt);
        metricEntity.setRemark(remark);
        metricEntity.setRewrite(params.rewrite());
        metricEntity.setParadigm(params.effectiveParadigm());
        metricEntity.setCategory(item.getCategory());
        metricEntity.setAgentTrace(itemScore.agentTrace());
        metricEntity.setTraceId(itemScore.traceId());
        return metricEntity;
    }

    /**
     * 单条重评（保留历史）：复用原 run 的检索参数快照（rewrite 用入参覆盖，便于对比裸检索 vs 含改写），
     * 跑一次检索打分，写 attempt=max+1 的指标行（带 remark/rewrite）。不修改 run 聚合/done/total。
     *
     * @return 本次重评的 attempt 序号
     */
    /**
     * 单条重评（保留历史）：复用原 run 参数快照，rewrite/paradigm 用入参覆盖（便于对比裸检索 vs 改写、不同范式），
     * 跑一次检索打分，写 attempt=max+1 的指标行。不修改 run 聚合/done/total。
     *
     * @param paradigm agent 范式；null/空 则沿用原 run 范式
     * @return 本次重评的 attempt 序号
     */
    public int reevaluateSingle(Long runId, Long itemId, boolean rewrite, String remark, String paradigm) {

        EvalRunEntity runEntity = evalRunMapper.selectById(runId);
        if (runEntity == null) {
            throw new ClientException("运行不存在: " + runId);
        }

        EvalItemEntity item = evalItemMapper.selectById(itemId);
        if (item == null) {
            throw new ClientException("条目不存在: " + itemId);
        }

        // 复用原 run 参数快照，rewrite/paradigm 用入参覆盖（paradigm 为空则沿用原 run）
        EvalParamSnapshot baseParams = parseParams(runEntity.getParamSnapshot());
        String effectiveParadigm = (paradigm != null && !paradigm.isBlank()) ? paradigm : baseParams.paradigm();
        EvalParamSnapshot params = new EvalParamSnapshot(baseParams.topK(), baseParams.threshold(),
                baseParams.recallBudget(), baseParams.candidateLimit(), baseParams.contextTopK(), rewrite, effectiveParadigm);
        int attempt = nextAttempt(runId, itemId);

        // 单条重评是同步 HTTP 请求（Span.current() = HTTP server span）：直接把 trace 级 IO 写到 HTTP 根 span，
        // 一个请求一个 trace、Langfuse 列表 IO 稳定显示（不依赖子 LLM span 的 gen_ai.*，ReAct 多轮下常丢）。
        // 不像批量 eval 走 startRoot 独立 trace——那是"一次 HTTP 跑多条 item"，必须各自独立，否则挤一个 trace 没法看。
        ragTelemetry.tag("eval.run_id", runId);
        ragTelemetry.tag("eval.item_id", itemId);
        ragTelemetry.tag("eval.attempt", attempt);
        ragTelemetry.tag("eval.paradigm", effectiveParadigm);
        ragTelemetry.traceInput(item.getQuestion());

        ItemScore itemScore = this.scoreItem(item, params, buildScorers());
        Map<String, String> docNameMap = loadDocNames(parseDocIds(item.getExpectedDocIds()));
        this.persistMetrics(runId, item, itemScore, params, attempt, remark, docNameMap);
        boolean hit = itemScore.error() == null && !itemScore.gotDocIds().isEmpty();
        ragTelemetry.tag("eval.hit", hit);
        ragTelemetry.traceOutput(itemScore.error() != null
                ? "error: " + itemScore.error()
                : "retrieved=" + itemScore.gotDocIds());
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

    private List<MetricScorer> buildScorers() {
        List<MetricScorer> scorers = new ArrayList<>();
        scorers.add(new MrrScorer());
        for (int k : evalProperties.getKs()) {
            scorers.add(new RecallAtKScorer(k));
            scorers.add(new PrecisionAtKScorer(k));
            scorers.add(new NdcgScorer(k));
        }
        return scorers;
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
        String paradigm = "naive";
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
            } catch (Exception e) {
                log.warn("[Eval] param_snapshot 解析失败，用 ChatProperties 默认: {}", e.getMessage());
            }
        }
        return new EvalParamSnapshot(topK, threshold, recallBudget, candidateLimit, contextTopK, rewrite, paradigm);
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
     * 构建命中详情（写 metric.detail），供前端「期望 vs 实际」对照。
     *
     * @param retrievedDocIds 实际召回的文档 id 列表
     * @param expectedDocIds  期望召回的文档 id 列表（黄金集）
     * @param docNameMap      doc_id→name 映射（把 id 转可读名）
     * @return 详情 JSON（含 expectedCount/retrievedCount/hitCount/期望文档 id+名）
     */
    private String buildDetail(List<String> retrievedDocIds, List<String> expectedDocIds, Map<String, String> docNameMap) {
        Set<String> expectedSet = new HashSet<>(expectedDocIds);
        long hitCount = retrievedDocIds.stream().filter(expectedSet::contains).count();
        Map<String, Object> detailMap = new LinkedHashMap<>();
        detailMap.put("expectedCount", expectedSet.size());
        detailMap.put("retrievedCount", retrievedDocIds.size());
        detailMap.put("hitCount", hitCount);
        detailMap.put("expectedDocIds", expectedDocIds);
        detailMap.put("expectedDocNames", expectedDocIds.stream().map(id -> docNameMap.getOrDefault(id, id)).toList());
        return toJson(detailMap);
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
