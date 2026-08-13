package com.nageoffer.ai.rag.ingestion.collection.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文档集合（文件集）实体，映射 sa_doc_collection 表。
 *
 * <p>用户手动建集合，文档通过 {@code sa_document.collection_id} 归入；一个文档最多属于一个集合
 *（NULL=独立文件）。「加入集合」对已在别处的文档 = 移动（覆盖旧值）。</p>
 *
 * <p>{@link #docCount} 是非持久化字段（DB 无此列），由 Service 聚合查询 sa_document 回填，
 * 避免冗余列的并发计数漂移。</p>
 */
@Data
@TableName(value = "sa_doc_collection", autoResultMap = true)
public class DocCollectionEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 集合名称（未逻辑删时唯一） */
    private String name;

    /** 集合描述 */
    private String description;

    /** 文档数（非 DB 列，由聚合查询回填） */
    @TableField(exist = false)
    private Integer docCount;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0-正常，1-已删除 */
    @TableLogic
    private Integer deleted;
}
