package com.nageoffer.ai.rag.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评测数据集实体，映射 sa_eval_dataset 表。
 * <p>一个数据集包含若干 {@link EvalItemEntity} 黄金问答条目。</p>
 */
@Data
@TableName(value = "sa_eval_dataset", autoResultMap = true)
public class EvalDatasetEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据集名称（全局唯一） */
    private String name;

    /** 数据集描述 */
    private String description;

    /** 条目数（冗余，便于列表展示） */
    private Integer itemCount;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录更新时间 */
    private LocalDateTime updateTime;

    /** 已弃用：原逻辑删除列，数据集改为物理删除；字段保留仅为向后兼容（DB 列仍在，默认 0，不再参与过滤） */
    private Integer deleted;
}
