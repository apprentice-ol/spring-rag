package com.jjx.customer.platform.agent.framework.workflow;

import com.jjx.customer.platform.agent.framework.node.AgentInvocation;
import com.jjx.customer.platform.agent.framework.node.AgentInvoker;
import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.result.ExecutionMetadataSink;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.spi.ExecutionEvents;

/**
 * 工作流驱动 SPI：消费已校验的 {@link ExecutionPlan}，迭代阶段并产出 {@link ExecutionResult}。
 *
 * <p>驱动骨架固化的横切：槽位（归一/抽槽/问齐）、when 跳过、replan 检查点、预算、错误策略、
 * 产出护栏、trace、元数据流出时序、结果装配。默认实现 {@code DefaultWorkflowDriver}。</p>
 *
 * <p>骨架阶段由实现通过 {@link ExecutionEvents} 发布（SLOT_PREPARE / SLOT_VALIDATE / STAGE /
 * REPLAN / ASSEMBLE），引擎再转发给监听器——观察者不必侵入驱动。</p>
 */
public interface WorkflowDriver {

    ExecutionResult drive(ExecutionPlan plan, ExecutionMetadataSink sink, AgentInvoker invoker,
                          AgentInvocation invocation, ExecutionEvents events);
}
