package com.jjx.customer.platform.mcp;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.agent.framework.tool.ExtensionTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * MCP 工具 → 框架扩展工具桥（T8）：外部来源的工具按统一 ExtensionTool 契约进入注册表，
 * 与本地业务工具同权（白名单放开、trace 收口、错误回喂模型）。
 *
 * <p>名字加前缀（默认 {@code mcp_}）防与本地工具重名——注册表重名启动即失败。</p>
 */
public class McpExtensionTool implements ExtensionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback callback;
    private final String name;
    private final ToolDefinition definition;

    public McpExtensionTool(ToolCallback callback, String toolPrefix) {
        this.callback = callback;
        this.definition = callback.getToolDefinition();
        String prefix = toolPrefix == null ? "" : toolPrefix;
        this.name = prefix + definition.name();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return definition.description();
    }

    @Override
    public String inputSchema() {
        return definition.inputSchema();
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            String json = MAPPER.writeValueAsString(invocation.args());
            String result = callback.call(json);
            return ToolResult.ok(result == null ? "" : result);
        } catch (Exception e) {
            return ToolResult.error("MCP 工具调用失败(" + definition.name() + "): " + e.getMessage());
        }
    }
}
