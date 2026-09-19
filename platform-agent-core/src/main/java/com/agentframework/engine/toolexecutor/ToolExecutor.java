package com.agentframework.engine.toolexecutor;

/**
 * 工具执行器：把一次工具调用串成“守卫 → 缓存 → 过滤 → 沙箱/超时/重试 → 追踪”的完整链路。
 */
public interface ToolExecutor {

    /**
     * 执行工具调用。
     *
     * @param invocation 调用请求
     * @param context    执行上下文
     * @return 执行结果；失败也会以结果对象返回，不抛异常
     */
    ToolResult execute(ToolInvocation invocation, ToolContext context);
}
