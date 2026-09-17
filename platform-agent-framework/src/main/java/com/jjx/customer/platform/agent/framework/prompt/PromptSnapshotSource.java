package com.jjx.customer.platform.agent.framework.prompt;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;

/**
 * Prompt 快照来源 SPI（业务侧实现）：按 Agent + Workflow 装配三层增强快照。
 *
 * <p>框架只消费快照（内容 + 指纹），不关心绑定、版本与存储。</p>
 */
public interface PromptSnapshotSource {

    PromptSnapshot snapshotFor(Agent agent, Workflow workflow);
}
