package com.agentframework.crosscutting.filter;

/** 过滤器挂载点：数据在哪些边界被变换。 */
public enum FilterPhase {
    /** Agent 输入进入工作流之前。 */
    BEFORE_AGENT_INPUT,
    /** Prompt 渲染完成、送入模型之前。 */
    PROMPT,
    /** 调用模型之前。 */
    BEFORE_LLM,
    /** 模型返回之后。 */
    AFTER_LLM,
    /** 调用工具之前。 */
    BEFORE_TOOL_CALL,
    /** 工具返回之后。 */
    AFTER_TOOL_RESULT,
    /** 节点输出写回槽位之前。 */
    AFTER_NODE_OUTPUT,
    /** 返回给应用层之前。 */
    BEFORE_OUTPUT,
    /** 返回给应用层之后（仅用于审计，不改变结果）。 */
    AFTER_OUTPUT
}
