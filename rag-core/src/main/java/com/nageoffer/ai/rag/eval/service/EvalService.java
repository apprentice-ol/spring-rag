package com.nageoffer.ai.rag.eval.service;

import com.nageoffer.ai.rag.eval.dao.entity.EvalDatasetEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalItemEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalMetricEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalRunEntity;
import com.nageoffer.ai.rag.eval.domain.EvalItemRequest;
import com.nageoffer.ai.rag.eval.domain.EvalRunOptions;
import java.util.List;

/** 评测服务：数据集 CRUD + 触发运行 + 结果查询。 */
public interface EvalService {

    /** 建数据集，返回 id */
    Long createDataset(String name, String description);

    /** 列数据集 */
    List<EvalDatasetEntity> listDatasets();

    /** 批量加条目 */
    void addItems(Long datasetId, List<EvalItemRequest> items);

    /** 列条目 */
    List<EvalItemEntity> listItems(Long datasetId);

    /** 删数据集（级联删条目） */
    void deleteDataset(Long datasetId);

    /** 改数据集名称/描述 */
    void updateDataset(Long datasetId, String name, String description);

    /** 删条目（原子扣减数据集 itemCount） */
    void deleteItem(Long datasetId, Long itemId);

    /** 启用/禁用条目（enabled：1 启用 / 0 禁用） */
    void toggleItemEnabled(Long itemId, int enabled);

    /** 触发异步评测运行，立即返回 runId */
    Long triggerRun(EvalRunOptions options);

    /** 查单次运行（含状态/聚合分，供前端轮询） */
    EvalRunEntity getRun(Long runId);

    /** 列运行（可按 datasetId / status 过滤，均空则全部） */
    List<EvalRunEntity> listRuns(Long datasetId, String status);

    /** 查某次运行的逐条指标明细 */
    List<EvalMetricEntity> getMetrics(Long runId);

    /** 删运行（级联删指标明细） */
    void deleteRun(Long runId);

    /** 重试某次运行（用原数据集重新触发评测，返回新 runId） */
    Long retryRun(Long runId);

    /**
     * 单条重评（保留历史，新增一条 attempt）：对某次运行的某条条目重新检索打分。
     * 可选启用查询改写、可选指定 agent 范式（覆盖原 run 范式）、可填备注，结果以新 attempt 行落库，不污染原 run 聚合。
     *
     * @param paradigm agent 范式（naive/crag/self_rag/react/plan_execute）；null 用原 run 范式
     * @return 本次重评的 attempt 序号（1 起）
     */
    int reevaluateItem(Long runId, Long itemId, Boolean rewriteEnabled, String remark, String paradigm);
}
