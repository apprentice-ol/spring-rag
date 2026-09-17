package com.jjx.customer.platform.agent.framework.tool;

import com.jjx.customer.platform.agent.framework.model.ToolSchema;

/**
 * 工具契约：自描述元数据（给模型）+ 执行入口（给框架）。
 *
 * <p>形态只有两种（见 {@link ToolKind}），由 {@link BaseTool} / {@link ExtensionTool}
 * 两个子接口固化，实现类不得自行混用。</p>
 */
public interface AgentTool {

    /** 全局唯一工具名（snake_case）。 */
    String name();

    /** 给模型的能力描述（什么时候该调我）。 */
    String description();

    /** 参数 JSON Schema。 */
    String inputSchema();

    /** 工具类别（B0 起必填：BASE / EXTENSION）。 */
    ToolKind kind();

    /**
     * 投影为给模型的工具说明（{@link ToolSchema}）。
     *
     * <p>工具元数据的唯一来源是工具自身（{@link #name()} / {@link #description()} /
     * {@link #inputSchema()}）；调用方不要手工拼 {@code ToolSchema}，避免同一份元数据两处维护。</p>
     */
    default ToolSchema toSchema() {
        return new ToolSchema(name(), description(), inputSchema());
    }

    /**
     * 执行工具。实现方必须自行把业务异常转成 {@link ToolResult#error(String)}——
     * 错误也是一种观测，回喂给模型让它自行纠偏（注册表仍会兜一层 Throwable）。
     */
    ToolResult execute(ToolInvocation invocation);
}
