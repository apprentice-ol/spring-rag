package com.jjx.customer.platform.mcp;

import com.agentframework.engine.toolexecutor.ToolInput;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MCP 桥接验证（新内核 Tool 契约）：命名前缀、schema 投影、参数序列化、错误回喂。 */
class McpExtensionToolTest {

    private static ToolCallback callback(AtomicReference<String> captured, boolean fail) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("search_logs")
                    .description("外部日志检索")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},"
                            + "\"required\":[\"q\"]}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                if (fail) {
                    throw new IllegalStateException("MCP 服务不可用");
                }
                captured.set(toolInput);
                return "外部命中 2 条";
            }
        };
    }

    @Test
    void 桥接为内核工具_名字带前缀_schema投影_参数序列化() {
        AtomicReference<String> captured = new AtomicReference<>();
        McpExtensionTool tool = new McpExtensionTool(callback(captured, false), "mcp_");

        assertEquals("mcp_search_logs", tool.id());
        assertEquals("外部日志检索", tool.schema().description());
        assertTrue(tool.schema().parameterNames().contains("q"));
        assertTrue(tool.schema().requiredParameters().contains("q"));

        var result = tool.invoke(ToolInput.of(Map.of("q", "冲红")), null);

        assertTrue(result.success());
        assertEquals("外部命中 2 条", result.output());
        assertTrue(captured.get().contains("\"q\":\"冲红\""));
    }

    @Test
    void 外部工具抛错_转为失败结果不打断链路() {
        McpExtensionTool tool = new McpExtensionTool(callback(new AtomicReference<>(), true), "mcp_");

        var result = tool.invoke(ToolInput.of(Map.of()), null);

        assertFalse(result.success());
        assertTrue(result.error().contains("MCP 服务不可用"));
    }

    @Test
    void 未启用_来源返回空清单() {
        McpExtensionToolSource disabled = new McpExtensionToolSource(null, false, "mcp_");

        assertTrue(disabled.tools().isEmpty(), "未启用时不应桥接任何外部工具");

        ToolCallbackProvider provider = ToolCallbackProvider.from(
                List.of(callback(new AtomicReference<>(), false)));
        McpExtensionToolSource enabled = new McpExtensionToolSource(
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(ToolCallbackProvider.class), true, "mcp_");
        // 空 BeanFactory：启用但无 MCP server，同样返回空（不阻断启动）
        assertTrue(enabled.tools().isEmpty());
        assertEquals(1, provider.getToolCallbacks().length);
    }
}
