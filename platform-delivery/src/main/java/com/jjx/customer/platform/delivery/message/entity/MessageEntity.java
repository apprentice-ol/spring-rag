package com.jjx.customer.platform.delivery.message.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jjx.customer.platform.common.mybatis.JsonbTypeHandler;
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

    /** 澄清卡片结构化载荷（ClarifyRequest JSON；刷新页面后据此重新渲染卡片） */
    @TableField(value = "clarify", typeHandler = JsonbTypeHandler.class)
    private String clarify;

    /** 关联 Agent 轨迹的 traceId（非表字段，历史消息加载时批量回填，跳 OpenObserve 全链路用） */
    @TableField(exist = false)
    private String traceId;
}
