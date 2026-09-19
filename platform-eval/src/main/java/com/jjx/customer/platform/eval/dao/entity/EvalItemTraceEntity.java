package com.jjx.customer.platform.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jjx.customer.platform.common.mybatis.JsonbTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评测条目 agent 执行轨迹实体，映射 sa_eval_item_trace 表。
 *
 * <p>轨迹是「每条 item 一份」的事实，原先塞在 {@code sa_eval_metric} 里（每条 item × 每个指标一行），
 * 同一份轨迹被复制指标数次（实测 9 次），把 395MB 的表撑到 352MB 全是冗余副本。拆出后每
 * run + item + attempt 只有一行，指标表回归纯数值聚合。</p>
 */
@Data
@TableName(value = "sa_eval_item_trace", autoResultMap = true)
public class EvalItemTraceEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属运行 ID（sa_eval_run.id） */
    private Long runId;

    /** 所属条目 ID（sa_eval_item.id） */
    private Long itemId;

    /** 评估序号：0=原始批，1/2/3…=第 n 次单条重评（与 sa_eval_metric.attempt 同口径） */
    private Integer attempt;

    /** 该 item root trace 的 traceId（关联 OpenObserve/Langfuse） */
    private String traceId;

    /** AgentTrace JSON（检索/工具调用轨迹，PG jsonb） */
    @TableField(value = "agent_trace", typeHandler = JsonbTypeHandler.class)
    private String agentTrace;

    /** 记录创建时间 */
    private LocalDateTime createTime;
}
