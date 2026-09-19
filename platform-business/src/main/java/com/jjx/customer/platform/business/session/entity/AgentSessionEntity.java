package com.jjx.customer.platform.business.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jjx.customer.platform.common.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 会话状态实体，映射 sa_agent_session 表。
 * <p>运维诊断追问中断的跨轮次槽位状态：一会话至多一条进行中记录（conversation_id UNIQUE）。
 * slots/missing_slots 是 jsonb（存 JSON 文本，与 AgentTraceEntity 同范式）。
 */
@Data
@TableName(value = "sa_agent_session", autoResultMap = true)
public class AgentSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话（sa_conversation.conversation_id，UNIQUE） */
    private String conversationId;

    /** agent 范式：ops_diagnose */
    private String agentType;

    /** 中断时所在骨架阶段 */
    private String stage;

    /** 状态机：AWAITING_USER / RUNNING / DONE / EXPIRED */
    private String status;

    /** 已确认槽位（slotName → 值）jsonb */
    @TableField(value = "slots", typeHandler = JsonbTypeHandler.class)
    private String slots;

    /** 中断时缺失的槽位名数组 jsonb */
    @TableField(value = "missing_slots", typeHandler = JsonbTypeHandler.class)
    private String missingSlots;

    /** 阶段产物一句话摘要 */
    private String summary;

    /** 会话自主档位（人在环中 P3：L1 多问我 / L2 默认 / L3 少问我；null = 缺省 L2） */
    private String autonomyLevel;

    /** 诊断链 traceId（首轮生成，后续追问轮沿用——同一次诊断跨多轮仍是一条链） */
    private String chainTraceId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
