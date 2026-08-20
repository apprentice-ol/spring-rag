package com.nageoffer.ai.rag.eval.controller;

import com.nageoffer.ai.rag.eval.dao.entity.EvalMetricEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalRunEntity;
import com.nageoffer.ai.rag.eval.domain.EvalRunOptions;
import com.nageoffer.ai.rag.eval.service.EvalService;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测运行：触发 / 列表 / 状态轮询 / 指标明细。
 */
@RestController
@RequestMapping("/eval/runs")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    /**
     * 触发异步评测运行，立即返回 runId
     */
    @PostMapping
    public Map<String, Object> trigger(@RequestBody EvalRunOptions options) {
        Long runId = evalService.triggerRun(options);
        return Map.of("runId", runId);
    }

    /**
     * 列运行（可按 datasetId / status 过滤）
     */
    @GetMapping
    public List<EvalRunEntity> list(@RequestParam(required = false) Long datasetId,
                                    @RequestParam(required = false) String status) {
        return evalService.listRuns(datasetId, status);
    }

    @GetMapping("/{id}")
    public EvalRunEntity get(@PathVariable Long id) {
        return evalService.getRun(id);

    }

    /** 指标明细（item 级分页：page/size 针对 item，每 item 含其全部指标行；不含 agent_trace） */
    @GetMapping("/{id}/metrics")
    public com.nageoffer.ai.rag.ingestion.domain.dto.PageResult<EvalMetricEntity> metrics(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return evalService.getMetrics(id, page, size);
    }

    /**
     * 删除运行（级联删指标明细）
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        evalService.deleteRun(id);
        return Map.of("id", id, "status", "DELETED");
    }

    /**
     * 重试运行（用原数据集重新触发评测，返回新 runId）
     */
    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable Long id) {
        Long runId = evalService.retryRun(id);
        return Map.of("runId", runId);
    }

    /**
     * 单条重评（保留历史）：对某次运行的某条条目重新检索打分，新增 attempt 行（带备注），
     * 可选启用查询改写。不动 run 聚合/done/total。返回本次 attempt 序号。
     */
    @PostMapping("/{id}/items/{itemId}/reevaluate")
    public Map<String, Object> reevaluateItem(@PathVariable Long id, @PathVariable Long itemId,
                                              @RequestBody(required = false) ReevaluateRequest body) {
        ReevaluateRequest request = body == null ? new ReevaluateRequest() : body;
        int attempt = evalService.reevaluateItem(id, itemId, request.getRewriteEnabled(), request.getRemark(),
                request.getParadigm(), request.getPerQuestion());
        return Map.of("runId", id, "itemId", itemId, "attempt", attempt);
    }

    @lombok.Data
    public static class ReevaluateRequest {
        /** 是否启用查询改写；null/false=裸检索 */
        private Boolean rewriteEnabled;
        /** agent 范式（naive/react）；null 用原 run 范式 */
        private String paradigm;
        /** 仅检索期望文档（per-question 模式）；null 沿用原 run 设置 */
        private Boolean perQuestion;
        /** 备注（显示在该条重评记录上） */
        private String remark;
    }
}
