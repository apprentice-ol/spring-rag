package com.nageoffer.ai.rag.eval.service.impl;

import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.config.properties.ChatProperties;
import com.nageoffer.ai.rag.eval.config.EvalConcurrencyGuard;
import com.nageoffer.ai.rag.eval.dao.entity.EvalDatasetEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalItemEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalMetricEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalRunEntity;
import com.nageoffer.ai.rag.eval.dao.mapper.EvalDatasetMapper;
import com.nageoffer.ai.rag.eval.dao.mapper.EvalItemMapper;
import com.nageoffer.ai.rag.eval.dao.mapper.EvalMetricMapper;
import com.nageoffer.ai.rag.eval.dao.mapper.EvalRunMapper;
import com.nageoffer.ai.rag.eval.domain.EvalItemRequest;
import com.nageoffer.ai.rag.eval.domain.EvalParamSnapshot;
import com.nageoffer.ai.rag.eval.domain.EvalRunOptions;
import com.nageoffer.ai.rag.eval.runner.EvalRunner;
import com.nageoffer.ai.rag.eval.service.EvalService;
import com.nageoffer.ai.rag.ingestion.domain.dto.PageResult;
import com.jjx.ai.llmobservability.observation.propagation.ContextPropagator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 评测服务实现。
 *
 * <p>triggerRun 异步：建 sa_eval_run(RUNNING) 后 submit 到虚拟线程池立即返回 runId，
 * 由 {@link EvalRunner} 后台跑批。Phase 1 用应用内线程池（不引 MQ），
 * Phase 2+ 数据集大时再接 RocketMQ（照 IngestionProducer/Consumer）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalServiceImpl implements EvalService {

    private final EvalDatasetMapper datasetMapper;
    private final EvalItemMapper itemMapper;
    private final EvalRunMapper runMapper;
    private final EvalMetricMapper metricMapper;
    private final ChatProperties chatProperties;
    private final EvalRunner evalRunner;
    private final ObjectMapper objectMapper;
    /** run 级并发治理（全局上限 + 优雅停机），替代此前无界且永不关闭的本地线程池 */
    private final EvalConcurrencyGuard concurrencyGuard;

    @Override
    @Transactional
    public Long createDataset(String name, String description) {
        if (!StringUtils.hasText(name)) {
            throw new ClientException("数据集名称不能为空");
        }
        EvalDatasetEntity d = new EvalDatasetEntity();
        d.setName(name);
        d.setDescription(description);
        d.setItemCount(0);
        datasetMapper.insert(d);
        return d.getId();
    }

    @Override
    public List<EvalDatasetEntity> listDatasets() {
        return datasetMapper.selectList(new LambdaQueryWrapper<EvalDatasetEntity>()
                .orderByDesc(EvalDatasetEntity::getId));
    }

    @Override
    @Transactional
    public void addItems(Long datasetId, List<EvalItemRequest> items) {
        EvalDatasetEntity d = datasetMapper.selectById(datasetId);
        if (d == null) {
            throw new ClientException("数据集不存在: " + datasetId);
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        List<EvalItemEntity> entities = new ArrayList<>(items.size());
        for (EvalItemRequest req : items) {
            if (!StringUtils.hasText(req.question())) {
                throw new ClientException("question 不能为空");
            }
            EvalItemEntity e = new EvalItemEntity();
            e.setDatasetId(datasetId);
            e.setQuestion(req.question());
            e.setExpectedDocIds(req.expectedDocIds() == null ? null : toJson(req.expectedDocIds()));
            e.setExpectedAnswer(req.expectedAnswer());
            e.setCategory(req.category());
            e.setItemKey(req.itemKey());
            e.setSource(StringUtils.hasText(req.source()) ? req.source() : "builtin");
            e.setEnabled(1);
            entities.add(e);
        }
        // 批量插入（此前逐条 insert = N 次 DB 往返，LiveRAG 50 题 = 50 次）
        Db.saveBatch(entities);
        // 原子累加 itemCount（此前 read-modify-write，并发 addItems 丢更新——deleteItem 已是正确示范）
        datasetMapper.update(null, new LambdaUpdateWrapper<EvalDatasetEntity>()
                .eq(EvalDatasetEntity::getId, datasetId)
                .setSql("item_count = item_count + " + entities.size()));
    }

    @Override
    public List<EvalItemEntity> listItems(Long datasetId) {
        return itemMapper.selectList(new LambdaQueryWrapper<EvalItemEntity>()
                .eq(EvalItemEntity::getDatasetId, datasetId)
                .orderByAsc(EvalItemEntity::getId));
    }

    @Override
    public Long triggerRun(EvalRunOptions options) {
        if (options == null || options.datasetId() == null) {
            throw new ClientException("datasetId 不能为空");
        }
        EvalDatasetEntity evalDatasetEntity = datasetMapper.selectById(options.datasetId());
        if (ObjUtil.isEmpty(evalDatasetEntity)) {
            throw new ClientException("数据集不存在: " + options.datasetId());
        }

        // 合并检索参数 + paradigm：options.paradigm 优先，否则 paramsOverride 自带，否则 naive
        EvalParamSnapshot baseParams = options.paramsOverride();


        String paradigm;
        if (options.paradigm() != null && !options.paradigm().isBlank()) {
            paradigm = options.paradigm();
        } else {
            paradigm = baseParams != null ? baseParams.effectiveParadigm() : "naive";
        }
        EvalParamSnapshot evalParamSnapshot = new EvalParamSnapshot(
                baseParams != null ? baseParams.topK() : chatProperties.getTopK(),
                baseParams != null ? baseParams.threshold() : chatProperties.getSimilarityThreshold(),
                baseParams != null ? baseParams.recallBudget() : chatProperties.getRecallBudget(),
                baseParams != null ? baseParams.candidateLimit() : chatProperties.getCandidateLimit(),
                baseParams != null ? baseParams.contextTopK() : chatProperties.getContextTopK(),
                baseParams != null ? baseParams.rewrite() : Boolean.TRUE.equals(options.rewriteEnabled()),
                paradigm,
                null,
                Boolean.TRUE.equals(options.answerEval()) ? Boolean.TRUE : null,
                Boolean.TRUE.equals(options.perQuestion()) ? Boolean.TRUE : null);

        EvalRunEntity evalRunEntity = new EvalRunEntity();
        evalRunEntity.setDatasetId(options.datasetId());
        evalRunEntity.setStatus("RUNNING");
        evalRunEntity.setDone(0);
        evalRunEntity.setParamSnapshot(toJson(evalParamSnapshot));
        evalRunEntity.setParadigm(evalParamSnapshot.effectiveParadigm());
        evalRunEntity.setStartedAt(LocalDateTime.now());
        evalRunEntity.setCreateTime(LocalDateTime.now());
        evalRunEntity.setUpdateTime(LocalDateTime.now());
        runMapper.insert(evalRunEntity);

        Long runId = evalRunEntity.getId();
        concurrencyGuard.submitRun(ContextPropagator.wrap(() -> {
            try {
                // itemIds 非空时走精确子集（agent 对照用：多范式同题），否则按 category/limit 抽样
                evalRunner.run(runId, options.category(), options.limit(), options.itemIds());
            } catch (Exception e) {
                log.error("[Eval] 后台跑批异常 runId={}", runId, e);
            }
        }));
        log.info("[Eval] 触发运行: runId={}, datasetId={}", runId, options.datasetId());
        return runId;
    }

    @Override
    public EvalRunEntity getRun(Long runId) {
        EvalRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new ClientException("运行不存在: " + runId);
        }
        return run;
    }

    @Override
    public List<EvalRunEntity> listRuns(Long datasetId, String status) {
        LambdaQueryWrapper<EvalRunEntity> qw = new LambdaQueryWrapper<EvalRunEntity>()
                .orderByDesc(EvalRunEntity::getId);
        if (datasetId != null) {
            qw.eq(EvalRunEntity::getDatasetId, datasetId);
        }
        if (StringUtils.hasText(status)) {
            qw.eq(EvalRunEntity::getStatus, status);
        }
        return runMapper.selectList(qw);
    }

    /**
     * {@inheritDoc}
     * <p>item 级分页 + 列裁剪：一次 run 的 metric 行数 = item 数 × 指标数，且每行冗余携带同一 item 的
     * 大文本（question/expectedAnswer/agentTrace/detail）。LiveRAG 895 题 ≈ 6300+ 行数十 MB，
     * 全量返回既慢又撑爆前端。按 item 分页两步查询（先页内 item id，再取这些 item 的行），
     * 并排除 agent_trace 列（前端不渲染，DB 照写）。</p>
     */
    @Override
    public PageResult<EvalMetricEntity> getMetrics(Long runId, int page, int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 200);
        // total = DISTINCT item 数（COUNT(DISTINCT)，不能用 selectCount+groupBy——多行会报错）
        List<java.util.Map<String, Object>> totalRows = metricMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<EvalMetricEntity>()
                        .select("COUNT(DISTINCT item_id) AS cnt")
                        .eq("run_id", runId));
        long total = totalRows.isEmpty() || totalRows.get(0) == null
                ? 0 : ((Number) totalRows.get(0).get("cnt")).longValue();
        if (total == 0) {
            return new PageResult<>(0, List.of());
        }
        // 页内 item id（DISTINCT item_id 分页）
        List<Long> pageItemIds = metricMapper.selectList(new LambdaQueryWrapper<EvalMetricEntity>()
                        .select(EvalMetricEntity::getItemId)
                        .eq(EvalMetricEntity::getRunId, runId)
                        .groupBy(EvalMetricEntity::getItemId)
                        .orderByAsc(EvalMetricEntity::getItemId)
                        .last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size))
                .stream().map(EvalMetricEntity::getItemId).toList();
        if (pageItemIds.isEmpty()) {
            return new PageResult<>(total, List.of());
        }
        // 页内 item 的全部指标行；排除 agent_trace 大文本列（前端不渲染，DB 照写，只是不回传）
        List<EvalMetricEntity> records = metricMapper.selectList(new LambdaQueryWrapper<EvalMetricEntity>()
                .select(EvalMetricEntity.class, i -> !"agent_trace".equals(i.getColumn()))
                .eq(EvalMetricEntity::getRunId, runId)
                .in(EvalMetricEntity::getItemId, pageItemIds)
                .orderByAsc(EvalMetricEntity::getItemId)
                .orderByAsc(EvalMetricEntity::getId));
        return new PageResult<>(total, records);
    }

    /**
     * {@inheritDoc}
     * <p>级联删除 run/metric（此前只删 items，孤儿 run/metric 越积越多且仍可查询到）。</p>
     */
    @Override
    @Transactional
    public void deleteDataset(Long datasetId) {
        EvalDatasetEntity d = datasetMapper.selectById(datasetId);
        if (d == null) {
            throw new ClientException("数据集不存在: " + datasetId);
        }
        List<Long> runIds = runMapper.selectList(new LambdaQueryWrapper<EvalRunEntity>()
                        .select(EvalRunEntity::getId)
                        .eq(EvalRunEntity::getDatasetId, datasetId))
                .stream().map(EvalRunEntity::getId).toList();
        if (!runIds.isEmpty()) {
            boolean hasRunning = runMapper.selectCount(new LambdaQueryWrapper<EvalRunEntity>()
                    .eq(EvalRunEntity::getDatasetId, datasetId)
                    .eq(EvalRunEntity::getStatus, "RUNNING")) > 0;
            if (hasRunning) {
                throw new ClientException("数据集有正在运行的评测，无法删除（请先等待完成或删除该 run）");
            }
            metricMapper.delete(new LambdaQueryWrapper<EvalMetricEntity>()
                    .in(EvalMetricEntity::getRunId, runIds));
            runMapper.deleteByIds(runIds);
        }
        itemMapper.delete(new LambdaQueryWrapper<EvalItemEntity>()
                .eq(EvalItemEntity::getDatasetId, datasetId));
        datasetMapper.deleteById(datasetId);
        log.info("[Eval] 级联删除数据集及条目/运行/指标: datasetId={}, name={}, runs={}",
                datasetId, d.getName(), runIds.size());
    }

    @Override
    @Transactional
    public void updateDataset(Long datasetId, String name, String description) {
        EvalDatasetEntity d = datasetMapper.selectById(datasetId);
        if (d == null) {
            throw new ClientException("数据集不存在: " + datasetId);
        }
        if (StringUtils.hasText(name)) {
            d.setName(name);
        }
        if (description != null) {
            d.setDescription(description);
        }
        datasetMapper.updateById(d);
    }

    @Override
    @Transactional
    public void deleteItem(Long datasetId, Long itemId) {
        EvalItemEntity item = itemMapper.selectById(itemId);
        if (item == null || !datasetId.equals(item.getDatasetId())) {
            throw new ClientException("条目不存在或不属于该数据集");
        }
        itemMapper.deleteById(itemId);
        // 原子扣减 itemCount，避免并发丢更新（勿照抄 addItems 的非原子 read-modify-write）
        datasetMapper.update(null, new LambdaUpdateWrapper<EvalDatasetEntity>()
                .eq(EvalDatasetEntity::getId, datasetId)
                .setSql("item_count = item_count - 1"));
    }

    @Override
    public void toggleItemEnabled(Long itemId, int enabled) {
        int affected = itemMapper.update(null, new LambdaUpdateWrapper<EvalItemEntity>()
                .eq(EvalItemEntity::getId, itemId)
                .set(EvalItemEntity::getEnabled, enabled));
        if (affected == 0) {
            throw new ClientException("条目不存在: " + itemId);
        }
    }

    @Override
    @Transactional
    public void deleteRun(Long runId) {
        EvalRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new ClientException("运行不存在: " + runId);
        }
        metricMapper.delete(new LambdaQueryWrapper<EvalMetricEntity>()
                .eq(EvalMetricEntity::getRunId, runId));
        runMapper.deleteById(runId);
        log.info("[Eval] 删除运行及指标: runId={}", runId);
    }

    @Override
    public Long retryRun(Long runId) {
        EvalRunEntity orig = runMapper.selectById(runId);
        if (orig == null) {
            throw new ClientException("运行不存在: " + runId);
        }
        // 重试只重跑"本次任务"实际评测过的条目（按原 run 的 metric 记录取 itemId），
        // 而非整个数据集全量重跑——否则抽样 N 条的任务，重试会变成全量重跑整个数据集
        List<Long> onlyItemIds = metricMapper.selectList(new LambdaQueryWrapper<EvalMetricEntity>()
                .eq(EvalMetricEntity::getRunId, runId)
                .select(EvalMetricEntity::getItemId))
                .stream()
                .map(EvalMetricEntity::getItemId)
                .distinct()
                .toList();
        if (onlyItemIds.isEmpty()) {
            // 原 run 因落库失败/进程中断没有任何 metric 时，回退为按原参数全量重跑，而不是直接拒绝
            log.warn("[Eval] 重试 run {} 无评测记录，回退为全量重跑", runId);
            onlyItemIds = null;
        }

        // 复用原 run 的检索参数快照，保证重试与原任务同参数
        EvalRunEntity run = new EvalRunEntity();
        run.setDatasetId(orig.getDatasetId());
        run.setStatus("RUNNING");
        run.setDone(0);
        run.setParamSnapshot(orig.getParamSnapshot());
        run.setParadigm(orig.getParadigm() != null ? orig.getParadigm() : "naive");
        run.setStartedAt(LocalDateTime.now());
        run.setCreateTime(LocalDateTime.now());
        run.setUpdateTime(LocalDateTime.now());
        runMapper.insert(run);

        Long newRunId = run.getId();
        final List<Long> scope = onlyItemIds;
        // 无 metric 回退时跑全量（limit 取极大值绕开默认 10% 抽样）
        final Integer retryLimit = scope == null ? Integer.MAX_VALUE : null;
        concurrencyGuard.submitRun(ContextPropagator.wrap(() -> {
            try {
                evalRunner.run(newRunId, null, retryLimit, scope);
            } catch (Exception e) {
                log.error("[Eval] 重试跑批异常 runId={}", newRunId, e);
            }
        }));
        log.info("[Eval] 重试运行: 原runId={}, 新runId={}, 重试{}条", runId, newRunId, scope.size());
        return newRunId;
    }

    /**
     * 启动恢复：进程被杀/崩溃时遗留的 RUNNING/PENDING run 永远卡住且无法重试，
     * 应用就绪后统一标记为 FAILED，前端即可重试或删除。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        int affected = runMapper.update(null, new LambdaUpdateWrapper<EvalRunEntity>()
                .in(EvalRunEntity::getStatus, "RUNNING", "PENDING")
                .set(EvalRunEntity::getStatus, "FAILED")
                .set(EvalRunEntity::getFinishedAt, LocalDateTime.now())
                .set(EvalRunEntity::getUpdateTime, LocalDateTime.now()));
        if (affected > 0) {
            log.warn("[Eval] 启动恢复：将 {} 个中断的 RUNNING/PENDING run 标记为 FAILED", affected);
        }
    }

    @Override
    public int reevaluateItem(Long runId, Long itemId, Boolean rewriteEnabled, String remark, String paradigm, Boolean perQuestion) {
        // 单条检索 ~1-2s（含 LLM 改写），同步执行即可；不动 run 聚合/done/total（保留历史）
        return evalRunner.reevaluateSingle(runId, itemId, Boolean.TRUE.equals(rewriteEnabled), remark, paradigm, perQuestion);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
