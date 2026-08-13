package com.nageoffer.ai.rag.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.rag.ingestion.utils.JsonbTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 评测运行实体，映射 sa_eval_run 表。
 * <p>一次评测运行 = 对某数据集按一组参数跑一遍，产出逐条指标（sa_eval_metric）+ 聚合分。</p>
 */
@Data
@TableName(value = "sa_eval_run", autoResultMap = true)
public class EvalRunEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据集 ID */
    private Long datasetId;

    /** 运行状态：PENDING / RUNNING / DONE / FAILED */
    private String status;

    /** agent 范式（naive/crag/...，冗余自 param_snapshot 便于列表筛选） */
    private String paradigm;

    /** 条目总数 */
    private Integer total;

    /** 已完成条目数 */
    private Integer done;

    /** 本次实际参数快照（topK/threshold/recallBudget/candidateLimit/contextTopK，PG jsonb） */
    @TableField(value = "param_snapshot", typeHandler = JsonbTypeHandler.class)
    private String paramSnapshot;

    /** 聚合指标（各指标的 mean/median/min/max，PG jsonb） */
    @TableField(value = "aggregate_metrics", typeHandler = JsonbTypeHandler.class)
    private String aggregateMetrics;

    /** 开始时间 */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;

    /** 完成时间 */

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishedAt;


    /** 记录创建时间 */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss" )
    private LocalDateTime createTime;


    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
