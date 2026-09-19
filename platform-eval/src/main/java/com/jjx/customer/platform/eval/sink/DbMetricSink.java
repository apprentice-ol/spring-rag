package com.jjx.customer.platform.eval.sink;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.eval.dao.entity.EvalItemTraceEntity;
import com.jjx.customer.platform.eval.dao.entity.EvalMetricEntity;
import com.jjx.customer.platform.eval.dao.mapper.EvalItemTraceMapper;
import com.jjx.customer.platform.eval.dao.mapper.EvalMetricMapper;
import com.jjx.customer.platform.eval.framework.EvalResultSink;
import com.jjx.customer.platform.eval.framework.EvalSample;
import com.jjx.customer.platform.eval.framework.EvalScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 落库 sink（从 EvalRunner.persistMetrics 平移，落库口径逐字段不变）：
 * error 样本写一条 {@code metric_name="error", score=-1} 行；正常样本每指标一行批量插入。
 * EvalScore.comment 不落库（comment 是 Langfuse 展示增强，保持 sa_eval_metric 结构与口径不变）。
 *
 * <p><b>agent 轨迹拆表（2026-09-18）</b>：轨迹原先跟着每个指标行各写一份（每条 item 9 个指标 =
 * 9 份 22KB 副本，实测把 sa_eval_metric 撑到 395MB / 352MB 是冗余轨迹），改由
 * {@link #persistTrace} 单独写 {@code sa_eval_item_trace}（每 run+item+attempt 一行）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbMetricSink implements EvalResultSink {

    private final EvalMetricMapper evalMetricMapper;
    private final EvalItemTraceMapper evalItemTraceMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void onItemResult(EvalSample sample, List<EvalScore> scores) {
        // 轨迹独立落库：先写且独立吞异常——轨迹写失败不该拖累指标，反之亦然
        persistTrace(sample);
        try {
            String expectedIdsJson = toJson(sample.expectedDocIds());
            String expectedNamesJson = toJson(sample.expectedDocNames());

            if (sample.error() != null) {
                evalMetricMapper.insert(newErrorMetric(sample, expectedIdsJson, expectedNamesJson));
                return;
            }
            String detail = buildDetail(sample);
            String retrievedIdsJson = toJson(sample.retrievedDocIds());
            String retrievedNamesJson = toJson(sample.retrievedDocNames());
            // 批量 insert：一个 item 的多指标行攒成 list 一次 batch（N 次 DB 往返→1 次）
            List<EvalMetricEntity> metricRows = new ArrayList<>(scores.size());
            for (EvalScore score : scores) {
                EvalMetricEntity metricEntity = new EvalMetricEntity();
                metricEntity.setRunId(sample.runId());
                metricEntity.setItemId(sample.itemId());
                metricEntity.setQuestion(sample.question());
                metricEntity.setRetrievedDocIds(retrievedIdsJson);
                metricEntity.setRetrievedDocNames(retrievedNamesJson);
                metricEntity.setExpectedDocIds(expectedIdsJson);
                metricEntity.setExpectedDocNames(expectedNamesJson);
                metricEntity.setExpectedAnswer(sample.expectedAnswer());
                metricEntity.setGeneratedAnswer(sample.generatedAnswer());
                metricEntity.setMetricName(score.name());
                metricEntity.setScore(BigDecimal.valueOf(score.value()).setScale(4, RoundingMode.HALF_UP));
                metricEntity.setDetail(detail);
                metricEntity.setLatencyMs(sample.latencyMs());
                metricEntity.setAttempt(sample.attempt());
                metricEntity.setRemark(sample.remark());
                metricEntity.setRewrite(sample.rewrite());
                metricEntity.setPerQuestion(sample.perQuestion());
                metricEntity.setParadigm(sample.paradigm());
                metricEntity.setCategory(sample.category());
                metricEntity.setTraceId(sample.traceId());
                metricRows.add(metricEntity);
            }
            Db.saveBatch(metricRows);
        } catch (Exception ex) {
            log.error("[EvalSink] 落库失败 run={}, item={}: {}", sample.runId(), sample.itemId(), ex.getMessage(), ex);
        }
    }

    /** error 指标行（检索失败 score=-1，仍冗余写期望文档便于前端对照）。 */
    private EvalMetricEntity newErrorMetric(EvalSample sample, String expectedIdsJson, String expectedNamesJson) {
        EvalMetricEntity metricEntity = new EvalMetricEntity();
        metricEntity.setRunId(sample.runId());
        metricEntity.setItemId(sample.itemId());
        metricEntity.setQuestion(sample.question());
        metricEntity.setMetricName("error");
        metricEntity.setScore(BigDecimal.valueOf(-1));
        metricEntity.setLatencyMs(sample.latencyMs());
        metricEntity.setDetail(toJson(Map.of("error", sample.error())));
        metricEntity.setExpectedDocIds(expectedIdsJson);
        metricEntity.setExpectedDocNames(expectedNamesJson);
        metricEntity.setExpectedAnswer(sample.expectedAnswer());
        metricEntity.setAttempt(sample.attempt());
        metricEntity.setRemark(sample.remark());
        metricEntity.setRewrite(sample.rewrite());
        metricEntity.setPerQuestion(sample.perQuestion());
        metricEntity.setParadigm(sample.paradigm());
        metricEntity.setCategory(sample.category());
        metricEntity.setTraceId(sample.traceId());
        return metricEntity;
    }

    /**
     * 落库该条的 agent 执行轨迹（每 run + item + attempt 一行）。
     * <p>轨迹为空（检索前就失败）时跳过；写失败只记日志——指标行是评测的主产物，轨迹是旁证，
     * 不能因为轨迹写失败丢指标。</p>
     */
    private void persistTrace(EvalSample sample) {
        if (sample.agentTrace() == null) {
            return;
        }
        try {
            EvalItemTraceEntity trace = new EvalItemTraceEntity();
            trace.setRunId(sample.runId());
            trace.setItemId(sample.itemId());
            trace.setAttempt(sample.attempt());
            trace.setTraceId(sample.traceId());
            trace.setAgentTrace(sample.agentTrace());
            evalItemTraceMapper.insert(trace);
        } catch (Exception ex) {
            log.warn("[EvalSink] agent 轨迹落库失败 run={}, item={}: {}",
                    sample.runId(), sample.itemId(), ex.getMessage());
        }
    }

    /** 命中详情（metric.detail）：期望/实际计数 + 命中数 + 期望文档 id+名（前端「期望 vs 实际」对照）。 */
    private String buildDetail(EvalSample sample) {
        Set<String> expectedSet = new HashSet<>(sample.expectedDocIds());
        long hitCount = sample.retrievedDocIds().stream().filter(expectedSet::contains).count();
        Map<String, Object> detailMap = new LinkedHashMap<>();
        detailMap.put("expectedCount", expectedSet.size());
        detailMap.put("retrievedCount", sample.retrievedDocIds().size());
        detailMap.put("hitCount", hitCount);
        detailMap.put("expectedDocIds", sample.expectedDocIds());
        detailMap.put("expectedDocNames", sample.expectedDocNames());
        return toJson(detailMap);
    }

    /** 对象 → JSON；失败返回 null（不抛异常，避免阻断落库）。 */
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
}
