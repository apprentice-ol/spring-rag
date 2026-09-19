package com.jjx.customer.platform.mcp;


import com.agentframework.engine.toolexecutor.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 扩展工具来源：把 MCP server 暴露的工具桥接成新内核 {@link Tool} 注册表条目。
 *
 * <p>开关与命名：{@code rag.chat.agent.mcp.enabled}（默认 false）、
 * {@code rag.chat.agent.mcp.tool-prefix}（默认 {@code mcp_}）。
 * 未配置 MCP server 时静默返回空清单（不阻断启动）。</p>
 */
@Slf4j
@Component
public class McpExtensionToolSource {

    private final ObjectProvider<ToolCallbackProvider> providers;
    private final boolean enabled;
    private final String toolPrefix;

    public McpExtensionToolSource(ObjectProvider<ToolCallbackProvider> providers,
                                  @Value("${rag.chat.agent.mcp.enabled:false}") boolean enabled,
                                  @Value("${rag.chat.agent.mcp.tool-prefix:mcp_}") String toolPrefix) {
        this.providers = providers;
        this.enabled = enabled;
        this.toolPrefix = toolPrefix;
    }

    /** 全部 MCP 扩展工具（未启用/无 server 时为空）。 */
    public List<Tool> tools() {
        if (!enabled) {
            return List.of();
        }
        List<Tool> tools = new ArrayList<>();
        for (ToolCallbackProvider provider : providers) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                if (callback == null || callback.getToolDefinition() == null) {
                    continue;
                }
                tools.add(new McpExtensionTool(callback, toolPrefix));
            }
        }
        if (!tools.isEmpty()) {
            log.info("[MCP] 桥接 {} 个外部工具进框架注册表（前缀={}）", tools.size(), toolPrefix);
        }
        return tools;
    }
}
