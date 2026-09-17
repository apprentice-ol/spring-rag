package com.jjx.customer.platform.agent.framework.tool;

/**
 * 控制面信号：BaseTool 通过它影响循环走向（数据面工具必须返回 {@link #NONE}）。
 *
 * <p>引擎对四种信号的处理：
 * <ul>
 *   <li>{@link #NONE} —— 正常回喂，继续循环；</li>
 *   <li>{@link #FINISH} —— 以工具产出作为本节点终稿，立即出环；</li>
 *   <li>{@link #ASK_USER} —— 中断执行转澄清（引擎产出 CLARIFY 结果，交编排层落会话）；</li>
 *   <li>{@link #ESCALATE} —— 模型主动升级（超出能力/权限、继续无望），引擎产出 ESCALATE 结果。</li>
 * </ul>
 */
public enum ToolControl {

    NONE,
    FINISH,
    ASK_USER,
    ESCALATE
}
