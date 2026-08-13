package com.nageoffer.ai.rag.ingestion.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 入库流水线模板实体，映射 sa_ingestion_pipeline 表。
 * <p>
 * 定义文档入库的处理流程模板，一条流水线由多个有序节点
 * （IngestionPipelineNodeEntity）组成，构成 fetcher → parser → chunker →
 * enhancer → enricher → indexer 的处理链。支持通过 DB 配置动态编排入库流程，
 * 无需修改代码即可调整节点顺序或参数。
 * </p>
 *
 * @see IngestionPipelineNodeEntity 流水线包含的节点定义
 */
@Data
@TableName("sa_ingestion_pipeline")
public class IngestionPipelineEntity {

    /** 自增主键（数据库自动生成） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 流水线模板名称，全局唯一，用于前端下拉选择和 API 引用 */
    private String name;

    /** 流水线描述，说明该模板适用的文档类型或处理场景 */
    private String description;

    /** 创建人标识（用户 ID 或用户名） */
    private String createdBy;

    /** 最近更新人标识 */
    private String updatedBy;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录最近更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0-正常，1-已删除（MyBatis-Plus @TableLogic 自动过滤） */
    @TableLogic
    private Integer deleted;
}
