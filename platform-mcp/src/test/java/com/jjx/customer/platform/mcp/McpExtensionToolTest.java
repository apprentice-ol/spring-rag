package com.jjx.customer.platform.mcp;

import com.jjx.customer.platform.agent.framework.tool.AgentTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MCP 桥接验证：命名前缀、schema 透传、参数序列化、错误回喂。 */
class McpExtensionToolTest {

    private static ToolCallback callback(AtomicReference<String> captured, boolean fail) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("search_logs")
                    .description("外部日志检索")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")
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
    void 桥接为扩展工具_名字带前缀_元数据透传_参数序列化() {
        AtomicReference<String> captured = new AtomicReference<>();
        McpExtensionTool tool = new McpExtensionTool(callback(captured, false), "mcp_");

        assertEquals("mcp_search_logs", tool.name());
        assertEquals("外部日志检索", tool.description());
        assertTrue(tool.inputSchema().contains("\"q\""));

        var result = tool.execute(new ToolInvocation("mcp_search_logs", Map.of("q", "冲红"), null));

        assertTrue(result.ok());
        assertEquals("外部命中 2 条", result.content());
        assertTrue(captured.get().contains("\"q\":\"冲红\""));
    }

    @Test
    void 外部工具抛错_转为错误结果不打断链路() {
        McpExtensionTool tool = new McpExtensionTool(callback(new AtomicReference<>(), true), "mcp_");

        var result = tool.execute(new ToolInvocation("mcp_search_logs", Map.of(), null));

        assertFalse(result.ok());
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
