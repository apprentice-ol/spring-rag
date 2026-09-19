package com.agentframework.engine.toolexecutor;

import com.agentframework.definition.tool.ToolSchema;

/**
 * 工具执行扩展点：能力提供方实现该接口。
 *
 * <p>实现只关心“怎么做”，权限、缓存、超时、重试等由执行器与中间件负责。</p>
 */
public interface Tool {

    /** @return 工具 id，对应 {@code ToolDefinition.id()} */
    String id();

    /** @return 工具契约 */
    ToolSchema schema();

    /**
     * 执行工具。
     *
     * @param input   调用参数
     * @param context 执行上下文
     * @return 执行结果
     */
    ToolResult invoke(ToolInput input, ToolContext context);
}
