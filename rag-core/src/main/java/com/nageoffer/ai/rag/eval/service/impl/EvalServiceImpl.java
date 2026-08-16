package com.nageoffer.ai.rag.eval.service.impl;

import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.config.properties.ChatProperties;
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
import com.jjx.ai.llmobservability.observation.propagation.ContextPropagator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** 评测跑批专用虚拟线程池（应用生命周期存活） */
    private final ExecutorService evalExecutor = Executors.newVirtualThreadPerTaskExecutor();

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
        int count = 0;
        for (EvalItemRequest req : items) {
            if (!StringUtils.hasText(req.question())) {
                throw new ClientException("question 不能为空");
            }
            EvalItemEntity e = new EvalItemEntity();
            e.setDatasetId(datasetId);
            e.setQuestion(req.question());
            e.setExpectedDocIds(req.expectedDocIds() == null ? null : toJson(req.expectedDocIds()));
            e.setCategory(req.category());
            e.setItemKey(req.itemKey());
            e.setSource(StringUtils.hasText(req.source()) ? req.source() : "builtin");
            e.setEnabled(1);
            itemMapper.insert(e);
            count++;
        }
        d.setItemCount((d.getItemCount() == null ? 0 : d.getItemCount()) + count);
        datasetMapper.updateById(d);
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
                paradigm);

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
        evalExecutor.submit(ContextPropagator.wrap(() -> {
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

    @Override
    public List<EvalMetricEntity> getMetrics(Long runId) {
        return metricMapper.selectList(new LambdaQueryWrapper<EvalMetricEntity>()
                .eq(EvalMetricEntity::getRunId, runId)
                .orderByAsc(EvalMetricEntity::getItemId)
                .orderByAsc(EvalMetricEntity::getId));
    }

    @Override
    @Transactional
    public void deleteDataset(Long datasetId) {
        EvalDatasetEntity d = datasetMapper.selectById(datasetId);
        if (d == null) {
            throw new ClientException("数据集不存在: " + datasetId);
        }
        itemMapper.delete(new LambdaQueryWrapper<EvalItemEntity>()
                .eq(EvalItemEntity::getDatasetId, datasetId));
        datasetMapper.deleteById(datasetId);
        log.info("[Eval] 删除数据集及条目: datasetId={}, name={}", datasetId, d.getName());
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
            throw new ClientException("原运行无评测记录，无法重试");
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
        evalExecutor.submit(ContextPropagator.wrap(() -> {
            try {
                evalRunner.run(newRunId, null, null, scope);
            } catch (Exception e) {
                log.error("[Eval] 重试跑批异常 runId={}", newRunId, e);
            }
        }));
        log.info("[Eval] 重试运行: 原runId={}, 新runId={}, 重试{}条", runId, newRunId, scope.size());
        return newRunId;
    }

    @Override
    public int reevaluateItem(Long runId, Long itemId, Boolean rewriteEnabled, String remark, String paradigm) {
        // 单条检索 ~1-2s（含 LLM 改写），同步执行即可；不动 run 聚合/done/total（保留历史）
        return evalRunner.reevaluateSingle(runId, itemId, Boolean.TRUE.equals(rewriteEnabled), remark, paradigm);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
