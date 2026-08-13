package com.nageoffer.ai.rag.ingestion.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.rag.ingestion.utils.JsonbTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 流水线节点定义实体，映射 sa_ingestion_pipeline_node 表。
 * <p>
 * 每个节点对应 IngestionEngine 中的一个处理步骤，按 nextNodeId 串联为执行链。
 * settingsJson 和 conditionJson 以 PG jsonb 类型存储，支持灵活的节点配置
 * 与条件执行（ConditionEvaluator 根据 conditionJson 判断此节点是否跳过）。
 * 可用的节点类型由 {@link com.nageoffer.ai.rag.ingestion.domain.enums.NodeType} 枚举定义。
 * </p>
 *
 * @see IngestionPipelineEntity 所属流水线
 * @see com.nageoffer.ai.rag.ingestion.engine.node.IngestionNode 节点执行逻辑接口
 * @see com.nageoffer.ai.rag.ingestion.engine.ConditionEvaluator 条件评估器
 */
@Data
@TableName(value = "sa_ingestion_pipeline_node", autoResultMap = true)
public class IngestionPipelineNodeEntity {

    /** 自增主键（数据库自动生成） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属流水线 ID，关联 sa_ingestion_pipeline 表 */
    private Long pipelineId;

    /** 节点 ID（流水线内唯一标识，如 "parser_01"、"chunker_main"），供 nextNodeId 引用 */
    private String nodeId;

    /** 节点类型：fetcher（获取）/ parser（解析）/ chunker（切分）/ enhancer（增强）/ enricher（丰富）/ indexer（索引） */
    private String nodeType;

    /** 下一节点 ID，构成链式执行顺序，为空表示当前为末节点 */
    private String nextNodeId;

    /** 节点配置 JSON，以 PG jsonb 格式存储，包含该节点特有的参数（如 chunker 的 chunkSize、overlap 等） */
    @TableField(value = "settings_json", typeHandler = JsonbTypeHandler.class)
    private String settingsJson;

    /** 节点执行条件 JSON，以 PG jsonb 格式存储，ConditionEvaluator 据此判断运行环境是否满足该节点执行条件 */
    @TableField(value = "condition_json", typeHandler = JsonbTypeHandler.class)
    private String conditionJson;

    /** 创建人标识 */
    private String createdBy;

    /** 最近更新人标识 */
    private String updatedBy;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录最近更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0-正常，1-已删除 */
    @TableLogic
    private Integer deleted;
}
