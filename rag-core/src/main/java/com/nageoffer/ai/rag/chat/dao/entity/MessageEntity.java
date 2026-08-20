package com.nageoffer.ai.rag.chat.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.rag.ingestion.utils.JsonbTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName(value = "sa_message", autoResultMap = true)
public class MessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conversationId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
    /** RAG 回答的引用溯源 JSON（[{ref,docId,docName,chunkCount,preview,sourceLocation}]；非 RAG 消息为 null） */
    @TableField(value = "citations", typeHandler = JsonbTypeHandler.class)
    private String citations;

    /** 关联 Agent 轨迹的 traceId（非表字段，历史消息加载时批量回填，跳 OpenObserve 全链路用） */
    @TableField(exist = false)
    private String traceId;
}
