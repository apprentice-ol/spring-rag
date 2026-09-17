package com.jjx.customer.platform.eval.sink;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.eval.dao.entity.EvalMetricEntity;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbMetricSink implements EvalResultSink {

    private final EvalMetricMapper evalMetricMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void onItemResult(EvalSample sample, List<EvalScore> scores) {
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
                metricEntity.setAgentTrace(sample.agentTrace());
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
        metricEntity.setAgentTrace(sample.agentTrace());
        metricEntity.setTraceId(sample.traceId());
        return metricEntity;
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
