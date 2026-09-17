package com.jjx.customer.platform.agent.framework.node;

import com.jjx.customer.platform.agent.framework.node.NodeContext;
import com.jjx.customer.platform.agent.framework.node.NodeKind;

/**
 * 节点执行器 SPI（对外注册开放）：新增节点形态 = 实现本接口并注册。
 *
 * <p>契约：执行器只填"节点内部怎么跑"；生命周期、预算、观测、护栏、错误策略由引擎托管，
 * 不得自行记账或吞错——异常直接抛出，由引擎按阶段 {@code errorPolicy} 处置。</p>
 */
public interface NodeExecutor {

    /** 本执行器负责的节点形态。 */
    NodeKind nodeKind();

    /** 执行节点。 */
    NodeResult execute(NodeContext context);
}
