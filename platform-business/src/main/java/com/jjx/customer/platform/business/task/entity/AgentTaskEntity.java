package com.jjx.customer.platform.business.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jjx.customer.platform.common.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 任务实体，映射 {@code sa_agent_task}。
 *
 * <p>与 {@code sa_agent_session} 的关键差别：那张表把两个生命周期塞在一起
 * （Task 级的 slots/summary/autonomy 与 Attempt 级的 stage/missing_slots），
 * 且 {@code conversation_id} 上有 UNIQUE——即"一个会话至多一个进行中的诊断"。
 * 本表只承载 <b>Task 级</b>字段，会话与任务是一对多。</p>
 */
@Data
@TableName(value = "sa_agent_task", autoResultMap = true)
public class AgentTaskEntity {

    /** 任务 id（UUID，与 conversation_id 解耦，故不派生） */
    @TableId(type = IdType.INPUT)
    private String taskId;

    /** 所属对话（sa_conversation.conversation_id） */
    private String conversationId;

    /** agent 范式：ops_diagnose */
    private String agentType;

    /** 状态机：open / running / suspended / concluded / closed / abandoned */
    private String status;

    /** 业务槽位（slotName → 值）jsonb */
    @TableField(value = "slots", typeHandler = JsonbTypeHandler.class)
    private String slots;

    /** 挂起时所在节点（UI 展示"卡在哪一步"） */
    private String stage;

    /** 终态回填的结论摘要 */
    private String summary;

    /** 会话自主档位（L1/L2/L3；null = 缺省 L2） */
    private String autonomyLevel;

    /** 诊断链 traceId（首轮生成，后续 attempt 沿用——一次诊断跨 attempt 仍是一条链） */
    private String chainTraceId;

    /** 已发起的 attempt 数；引擎会话 id = {@code ops-<taskId>#<n>} */
    private Integer attemptCount;

    /** 任务内直答（Task QA）成功次数（P1 配额计数，超限回固定文案不降级重跑） */
    private Integer qaCount;

    /** 所属主题（Topic 层预留，当前为空） */
    private String topicId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime closeTime;
}
