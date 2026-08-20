package com.nageoffer.ai.rag.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.rag.ingestion.utils.JsonbTypeHandler;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评测指标实体，映射 sa_eval_metric 表。
 * <p>每条评测条目 × 每个指标（recall_at_5 / precision_at_5 / mrr / ndcg）一行。</p>
 */
@Data
@TableName(value = "sa_eval_metric", autoResultMap = true)
public class EvalMetricEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属运行 ID */
    private Long runId;

    /** 所属条目 ID */
    private Long itemId;

    /** 条目问题（冗余，便于查询展示） */
    private String question;

    /** 实际召回的 doc_id 列表（去重后，PG jsonb） */
    @TableField(value = "retrieved_doc_ids", typeHandler = JsonbTypeHandler.class)
    private String retrievedDocIds;

    /** 实际召回的 doc_name 列表（人类可读，展示用，PG jsonb） */
    @TableField(value = "retrieved_doc_names", typeHandler = JsonbTypeHandler.class)
    private String retrievedDocNames;

    /** 期望召回的 doc_id 列表（黄金集 sa_eval_item.expected_doc_ids 冗余投影，PG jsonb） */
    @TableField(value = "expected_doc_ids", typeHandler = JsonbTypeHandler.class)
    private String expectedDocIds;

    /** 期望召回的 doc_name 列表（人类可读，展示用，PG jsonb） */
    @TableField(value = "expected_doc_names", typeHandler = JsonbTypeHandler.class)
    private String expectedDocNames;

    /** 标准答案（黄金集 sa_eval_item.expected_answer 冗余投影，答案评测展示用） */
    private String expectedAnswer;

    /** 系统生成答案（answerEval 评测时 LLM 生成，前端「系统 vs 标准」对照用） */
    private String generatedAnswer;

    /** 指标名：recall_at_5 / precision_at_5 / mrr / ndcg 等 */
    private String metricName;

    /** 指标得分（0~1；-1 表示该条执行失败） */
    private BigDecimal score;

    /** 指标明细（k / hitCount / expectedCount 等，PG jsonb） */
    @TableField(value = "detail", typeHandler = JsonbTypeHandler.class)
    private String detail;

    /** 该条检索耗时（毫秒） */
    private Long latencyMs;

    /** 评估序号：0=原始批，1/2/3…=第 n 次单条重评（保留历史） */
    private Integer attempt;

    /** 备注（单条重评时填写） */
    private String remark;

    /** 该条检索是否启用了查询改写（QueryRewriter） */
    private Boolean rewrite;

    /** 该条是否仅检索期望文档（per-question 模式；重评可覆盖原 run 设置，区分上限对照记录） */
    private Boolean perQuestion;

    /** traceId（预留，关联 OpenObserve） */
    private String traceId;

    /** agent 执行轨迹（AgentTrace JSON，前端对照面板渲染用，PG jsonb） */
    @TableField(value = "agent_trace", typeHandler = JsonbTypeHandler.class)
    private String agentTrace;

    /** agent 范式（naive/react） */
    private String paradigm;

    /** 条目分类（冗余自 sa_eval_item.category，便于运行记录按分类展示/筛选） */
    private String category;

    /** 记录创建时间 */
    private LocalDateTime createTime;
}
