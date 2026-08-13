package com.nageoffer.ai.rag.ingestion.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 异步入库任务状态实体，映射 sa_ingestion_task 表。
 * <p>
 * IngestionEngine 提交一个文档入库请求时，会创建一个 IngestionTask 来追踪
 * 整个处理流程的执行状态。每个任务关联一个文档，包含总体进度和节点级执行详情
 * （记录在 IngestionTaskNodeEntity 中）。前端通过轮询此表获取入库进度。
 * </p>
 *
 * @see DocumentEntity 该任务处理的文档
 * @see IngestionTaskNodeEntity 各节点执行详情
 */
@Data
@TableName("sa_ingestion_task")
public class IngestionTaskEntity {

    /** 自增主键（数据库自动生成） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务唯一标识（UUID 字符串），供前端轮询和 API 引用 */
    private String taskId;

    /** 关联文档标识 docId，指向 sa_document 表 */
    private String docId;

    /** 任务状态：PENDING（排队中）/ PROCESSING（处理中）/ DONE（完成）/ FAILED（失败） */
    private String status;

    /** 处理进度百分比（0~100），由各节点执行情况累加计算 */
    private Integer progress;

    /** 失败时的错误信息，仅 status = FAILED 时有值 */
    private String errorMsg;

    /** 记录创建时间，任务提交时自动设置 */
    private LocalDateTime createdAt;

    /** 记录更新时间，每次状态变更时自动更新 */
    private LocalDateTime updatedAt;
}
