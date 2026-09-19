package com.agentframework.definition.node;

/**
 * 节点类型：工作流运行时可直接执行的节点种类。
 *
 * <p>新增类型通过扩展注册表注册 {@code NodeExecutor} 接入，无需修改内核。</p>
 */
public enum NodeType {
    LLM,
    TOOL,
    CONDITION,
    PARALLEL,
    HUMAN,
    SUB_WORKFLOW,
    CUSTOM
}
