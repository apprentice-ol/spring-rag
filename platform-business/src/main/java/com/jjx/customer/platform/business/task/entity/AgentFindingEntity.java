package com.jjx.customer.platform.business.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jjx.customer.platform.common.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 诊断主张实体，映射 {@code sa_agent_finding}。
 *
 * <p>{@code evidence} 是 jsonb（存 JSON 文本，与 {@code AgentTaskEntity.slots} 同范式）。</p>
 */
@Data
@TableName(value = "sa_agent_finding", autoResultMap = true)
public class AgentFindingEntity {

    /** 主张 id（UUID，由抽取器生成） */
    @TableId(type = IdType.INPUT)
    private String findingId;

    /** 所属任务 */
    private String taskId;

    /** 所属对话（冗余自 task，按对话查主张是高频路径） */
    private String conversationId;

    /** ROOT_CAUSE / CONSTRAINT / FIX / RISK / ANSWER */
    private String kind;

    /** 断言正文 */
    private String claim;

    /** 证据指针数组 [{label,value,source}] jsonb */
    @TableField(value = "evidence", typeHandler = JsonbTypeHandler.class)
    private String evidence;

    /** ACTIVE / SUPERSEDED / RETRACTED */
    private String status;

    /** 由第几次 attempt 产出 */
    private Integer attemptNo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
