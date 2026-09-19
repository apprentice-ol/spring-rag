package com.agentframework.engine.core;

/** 节点执行状态。 */
public enum NodeStatus {
    /** 执行完成，可继续后续节点。 */
    COMPLETED,
    /** 挂起，等待外部输入（例如人工节点）。 */
    SUSPENDED,
    /** 跳过，不产生输出但可继续。 */
    SKIPPED,
    /** 执行失败。 */
    FAILED
}
