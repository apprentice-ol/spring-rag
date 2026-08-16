package com.nageoffer.ai.rag.chat.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.rag.ingestion.utils.JsonbTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Agent 执行轨迹实体，映射 sa_agent_trace 表。
 * <p>线上 chat 每次走 agent（RAG 分支）后异步落一条，供管理后台分析多步决策规律（如 react 的
 * 拼写纠错 / 关键词重组 / 结果评估），反哺 naive 检索。steps 是 {@link com.nageoffer.ai.rag.chat.agent.AgentStep}
 * 数组的 jsonb。</p>
 */
@Data
@TableName(value = "sa_agent_trace", autoResultMap = true)
public class AgentTraceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话（sa_conversation.conversation_id） */
    private String conversationId;

    /** 关联的 assistant 消息 id（sa_message.id），可空 */
    private Long messageId;

    /** 本次请求的 OpenTelemetry traceId（32 位 hex，跳转 OpenObserve 全链路用），可空 */
    private String traceId;

    /** 范式：naive/react */
    private String paradigm;

    /** 用户原始问题 */
    private String question;

    /** AgentStep 数组 jsonb（action/thought/inputSummary/outputSummary/latencyMs） */
    @TableField(value = "steps", typeHandler = JsonbTypeHandler.class)
    private String steps;

    /** 编排内 LLM 调用次数（不含最终回答） */
    private Integer llmCallCount;

    /** 检索编排总耗时（ms） */
    private Long totalLatencyMs;

    private LocalDateTime createTime;
}
