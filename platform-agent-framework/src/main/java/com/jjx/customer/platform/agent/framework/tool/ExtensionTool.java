package com.jjx.customer.platform.agent.framework.tool;

/**
 * 扩展能力类工具（数据面）：使用方与外部来源实现，只能经阶段白名单放开。
 *
 * <p>契约：只产出业务数据（含证据片段），不得改变循环状态。</p>
 */
public interface ExtensionTool extends AgentTool {

    @Override
    default ToolKind kind() {
        return ToolKind.EXTENSION;
    }
}
