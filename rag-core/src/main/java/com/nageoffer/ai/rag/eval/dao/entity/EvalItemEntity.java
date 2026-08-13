package com.nageoffer.ai.rag.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.rag.ingestion.utils.JsonbTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评测条目（黄金问答）实体，映射 sa_eval_item 表。
 *
 * <p>注意：{@code query_embedding} 是 pgvector VECTOR(1024) 列，MyBatis-Plus 无现成 TypeHandler，
 * <b>不在此声明</b>——回填与读取都走 JdbcTemplate（参见 EvalRunner）。</p>
 *
 * <p>{@code expected_doc_ids} 存检索 ground truth（task_id 列表，JSON 数组），与检索结果
 * {@code chunk.metadata["doc_id"]} 同源，用于计算 Recall@k / Precision@k / MRR / nDCG。</p>
 */
@Data
@TableName(value = "sa_eval_item", autoResultMap = true)
public class EvalItemEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属数据集 ID */
    private Long datasetId;

    /** 可选用例标识 */
    private String itemKey;

    /** 分类：qa / summarization / adversarial 等 */
    private String category;

    /** 用户问题 */
    private String question;

    /** 检索 ground truth：期望命中的 task_id 列表（PG jsonb，JSON 数组字符串） */
    @TableField(value = "expected_doc_ids", typeHandler = JsonbTypeHandler.class)
    private String expectedDocIds;

    /** 标准答案（Phase 2 生成质量指标用，本轮留字段） */
    private String expectedAnswer;

    /** 答案必含关键词（Phase 2 用，PG jsonb） */
    @TableField(value = "must_contain", typeHandler = JsonbTypeHandler.class)
    private String mustContain;

    /** 开放式评分标准（Phase 2 用） */
    private String rubric;

    /** 来源：builtin（人工构造）/ feedback（线上 badcase 回流） */
    private String source;

    /** 是否启用：1-启用，0-禁用 */
    private Integer enabled;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录更新时间 */
    private LocalDateTime updateTime;
}
