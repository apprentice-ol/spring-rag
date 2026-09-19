package com.agentframework.definition.prompt;

/** Prompt 挂载范围：整个 Agent、单个工作流，或单个节点。 */
public enum PromptScope {
    AGENT,
    WORKFLOW,
    NODE
}
