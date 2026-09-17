package com.jjx.customer.platform.agent.framework.node;

/**
 * 节点形态标识（骨架仍是顺序 + when + replan，不引入图编排）。
 *
 * <p>刻意做成值类型而不是枚举：内置形态给常量，使用方可以
 * {@link #of(String)} 注册自定义形态（实现 {@code engine.NodeExecutor}），由引擎托管横切。</p>
 */
public record NodeKind(String id) {

    /** 模型在扩展工具白名单内自主决策的多步工具循环。 */
    public static final NodeKind LOOP = new NodeKind("LOOP");

    /** 确定性调用指定扩展工具，零 LLM 决策（单次检索、固定校验等）。 */
    public static final NodeKind DETERMINISTIC = new NodeKind("DETERMINISTIC");

    /** 调用子 Agent（重入引擎内核，预算总账共享、trace 嵌套）。 */
    public static final NodeKind AGENT_CALL = new NodeKind("AGENT_CALL");

    public static NodeKind of(String id) {
        return new NodeKind(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
