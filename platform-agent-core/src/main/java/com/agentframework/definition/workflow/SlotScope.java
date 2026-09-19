package com.agentframework.definition.workflow;

/**
 * 槽位作用域：决定槽位值的生命周期，以及哪些会话可以读取它。
 */
public enum SlotScope {
    /** 租户级共享，所有 Agent 的所有会话可见。 */
    GLOBAL,
    /** 同一 Agent 版本的所有会话共享。 */
    AGENT,
    /** 单个会话私有。 */
    SESSION,
    /** 会话内当前工作流运行私有。 */
    WORKFLOW,
    /**
     * Region 局部模板：名字是短名，不直接进入全局槽位空间；
     * 带slotPrefix 的 Region 内写入时由引擎展开为 {@code {prefix}_{name}} 物理槽，
     * 变量表同时以短名暴露（别名），供表达式与 Prompt 模板使用。
     */
    REGION,
    /** 仅在单次节点执行期间有效。 */
    NODE
}
