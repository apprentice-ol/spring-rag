package com.nageoffer.ai.rag.ingestion.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 异步入库业务事件（纯 POJO，无 MQ 框架依赖）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestionMessage {

    /** 任务唯一标识 */
    private String taskId;

    /** 关联文档标识 */
    private String docId;

    /** 文件在本地临时目录的路径（消费者按路径读回） */
    private String filePath;

    /** 原始文件名 */
    private String filename;

    /** MIME 类型 */
    private String mimeType;

    /** 所属集合 ID（可空，NULL=独立文件，未归入任何集合） */
    private Long collectionId;
}
