package com.nageoffer.ai.rag.ingestion.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.rag.ingestion.utils.JsonbTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 节点级执行日志实体，映射 sa_ingestion_task_node 表。
 * <p>
 * 记录入库任务中每个节点的详细执行信息，包括状态、耗时、输出等。
 * IngestionEngine 每执行一个节点，都会在此表中插入一条记录，便于
 * 追踪故障节点、分析性能瓶颈。NodeOutputExtractor 负责捕获节点输出
 * 并写入 output_json 字段，供下游节点或调试使用。
 * </p>
 *
 * @see IngestionTaskEntity 所属的入库任务
 * @see com.nageoffer.ai.rag.ingestion.engine.NodeOutputExtractor 节点输出提取器
 */
@Data
@TableName(value = "sa_ingestion_task_node", autoResultMap = true)
public class IngestionTaskNodeEntity {

    /** 自增主键（数据库自动生成） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联任务标识 taskId，指向 sa_ingestion_task 表 */
    private String taskId;

    /** 关联流水线 ID，指向 sa_ingestion_pipeline 表 */
    private Long pipelineId;

    /** 节点 ID，对应流水线模板中定义的 node_id */
    private String nodeId;

    /** 节点类型：fetcher / parser / chunker / enhancer / enricher / indexer */
    private String nodeType;

    /** 节点执行顺序序号，按 IngestionEngine 执行顺序递增 */
    private Integer nodeOrder;

    /** 节点执行状态：PENDING（待执行）/ PROCESSING（执行中）/ DONE（完成）/ FAILED（失败）/ SKIPPED（条件不满足跳过） */
    private String status;

    /** 节点执行耗时，单位毫秒 */
    private Long durationMs;

    /** 节点执行消息，记录执行过程中的关键信息或摘要 */
    private String message;

    /** 节点执行错误信息，仅 status = FAILED 时有值 */
    private String errorMessage;

    /** 节点输出 JSON，由 NodeOutputExtractor 在执行完成后捕获，包含该节点的处理结果（PG jsonb 类型） */
    @TableField(value = "output_json", typeHandler = JsonbTypeHandler.class)
    private String outputJson;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录更新时间 */
    private LocalDateTime updateTime;
}
