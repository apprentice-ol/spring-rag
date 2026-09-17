package com.jjx.customer.platform.agent.framework.tool;

/**
 * 工具二分：控制面（保障循环）与数据面（提供能力），不得混。
 */
public enum ToolKind {

    /** 循环保障类：finish / ask_user / escalate；框架内置、保留字、引擎注入、不可关闭。 */
    BASE,

    /** 能力类：业务与外部工具；只能经阶段白名单放开。 */
    EXTENSION
}
