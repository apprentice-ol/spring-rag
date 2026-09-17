package com.jjx.customer.platform.agent.framework.tool;

/**
 * 循环保障类工具（控制面）：<b>框架内置</b>，保留字，引擎按阶段控制开关注入，不使用方白名单管理。
 *
 * <p>契约：只允许影响循环状态（出环 / 中断追问 / 升级），不得写业务数据。</p>
 */
public interface BaseTool extends AgentTool {

    @Override
    default ToolKind kind() {
        return ToolKind.BASE;
    }
}
