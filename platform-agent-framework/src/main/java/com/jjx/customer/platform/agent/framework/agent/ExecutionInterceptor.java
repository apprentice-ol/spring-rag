package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.spi.Ordered;

/**
 * 执行拦截器 SPI（对外注册开放）：鉴权、限流、审计、成本统计。
 *
 * <p>边界（决策 D5）：<b>可否决请求，不可改流程控制</b>——不得改写横切语义
 * （错误策略/预算/护栏/会话）与执行顺序。只在顶层执行（depth == 0）生效，
 * 子 Agent 重入不重复拦截。</p>
 */
public interface ExecutionInterceptor extends Ordered {

    /**
     * 执行前裁决：null = 放行；非空 = 否决理由（引擎产出 ESCALATE 结果，不执行流程）。
     */
    String beforeExecution(ExecutionPlan plan);

    /**
     * 执行后回调（终态结果已出，含被否决的结果；观测用，不得抛错阻断——异常由引擎吞掉并忽略）。
     */
    default void afterExecution(ExecutionPlan plan, ExecutionResult result) {
    }
}
