package com.agentframework.sdk;

import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具构建助手：用一个函数即可定义工具，无需实现接口样板代码。
 *
 * <pre>
 * Tool calculator = Tools.of("calculator", "计算两数之和",
 *         ToolParameter.required("a", "number"), ToolParameter.required("b", "number"))
 *         .invoke(input -&gt; ToolResult.ok(String.valueOf(input.integer("a", 0) + input.integer("b", 0))));
 * </pre>
 */
public final class Tools {

    private Tools() {
    }

    /**
     * 定义工具。
     *
     * @param id         工具 id
     * @param description 工具说明
     * @param schema     工具契约
     * @param handler    执行函数
     * @return 工具实现
     */
    public static Tool of(String id, String description, ToolSchema schema,
            Function<ToolInput, ToolResult> handler) {
        ToolSchema effective = schema == null ? ToolSchema.noArgs(id, description) : schema;
        return new Tool() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ToolSchema schema() {
                return effective;
            }

            @Override
            public ToolResult invoke(ToolInput input, ToolContext context) {
                // 异常不在此处吞掉：交由工具执行器的拦截链处理，重试 / 熔断 / 指标才能感知失败
                return handler.apply(input);
            }
        };
    }

    /**
     * 定义无参工具。
     *
     * @param id          工具 id
     * @param description 工具说明
     * @param handler     执行函数
     * @return 工具实现
     */
    public static Tool of(String id, String description, Function<ToolInput, ToolResult> handler) {
        return of(id, description, ToolSchema.noArgs(id, description), handler);
    }

    /**
     * 定义返回固定文本的工具，便于冒烟测试与占位。
     *
     * @param id      工具 id
     * @param output  固定输出
     * @return 工具实现
     */
    public static Tool constant(String id, String output) {
        return of(id, "固定输出工具", input -> ToolResult.ok(output, Map.of("constant", true)));
    }
}
