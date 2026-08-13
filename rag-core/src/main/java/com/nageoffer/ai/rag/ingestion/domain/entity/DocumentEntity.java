package com.nageoffer.ai.rag.ingestion.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文档元数据实体，映射 sa_document 表。
 * <p>
 * 记录用户上传或导入的每一个文档的基本信息，包括文件来源、MIME 类型、
 * 入库处理状态、切分后的 chunk 数量等。入库引擎（IngestionEngine）
 * 通过此实体追踪每个文档从上传到处理完成的全生命周期。
 * </p>
 *
 * @see IngestionTaskEntity 该文档对应的异步任务状态
 */
@Data
@TableName("sa_document")
public class DocumentEntity {

    /** 自增主键（数据库自动生成） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档唯一业务标识（UUID 字符串），供前端和 API 层引用 */
    private String docId;

    /** 文档原始文件名（上传时携带的完整文件名，含后缀） */
    private String name;

    /** MIME 类型，如 application/pdf、text/markdown、text/plain */
    private String mimeType;

    /** 来源类型，标识文档由何种方式接入：UPLOAD（页面上传）/ URL（远程拉取）/ API（接口导入）等 */
    private String sourceType;

    /** 来源位置，上传时的存储路径或远程 URL 地址 */
    private String sourceLocation;

    /** 文档切分后的向量片段（chunk）总数，入库完成后回填 */
    private Integer chunkCount;

    /** 文档处理状态：PENDING（待处理）/ PROCESSING（处理中）/ DONE（完成）/ FAILED（失败） */
    private String status;

    /** 失败时的错误信息，仅 status = FAILED 时有值 */
    private String errorMsg;

    /** 所属集合 ID（NULL=独立文件，未归入任何集合） */
    private Long collectionId;

    /** 记录创建时间，入库时自动设置 */
    private LocalDateTime createdAt;

    /** 记录更新时间，每次字段变更时自动更新 */
    private LocalDateTime updatedAt;
}
