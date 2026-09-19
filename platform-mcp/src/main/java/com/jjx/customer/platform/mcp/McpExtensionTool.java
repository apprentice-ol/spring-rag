package com.jjx.customer.platform.mcp;


import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * MCP 工具 → 新内核 {@link Tool} 契约桥（T8）：外部来源的工具按统一契约进入注册表，
 * 与本地业务工具同权（白名单放开、trace 收口、错误回喂模型）。
 *
 * <p>名字加前缀（默认 {@code mcp_}）防与本地工具重名——注册表重名启动即失败。
 * MCP 侧 inputSchema（JSON Schema 字符串）解析投影为 {@link ToolSchema} 参数清单，
 * 模型经网关按 schema 下发参数。</p>
 */
public class McpExtensionTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback callback;
    private final String toolId;
    private final ToolDefinition definition;
    private final ToolSchema schema;

    public McpExtensionTool(ToolCallback callback, String toolPrefix) {
        this.callback = callback;
        this.definition = callback.getToolDefinition();
        String prefix = toolPrefix == null ? "" : toolPrefix;
        this.toolId = prefix + definition.name();
        this.schema = toSchema(toolId, definition);
    }

    @Override
    public String id() {
        return toolId;
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult invoke(ToolInput input, ToolContext context) {
        try {
            String json = MAPPER.writeValueAsString(input.arguments());
            String result = callback.call(json);
            return ToolResult.ok(result == null ? "" : result);
        } catch (Exception e) {
            return ToolResult.failed("MCP 工具调用失败(" + definition.name() + "): " + e.getMessage());
        }
    }

    /** MCP 的 JSON Schema 字符串 → 内核 ToolSchema（properties/required 投影为参数清单）。 */
    private static ToolSchema toSchema(String id, ToolDefinition definition) {
        List<ToolParameter> parameters = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(definition.inputSchema());
            JsonNode required = root.get("required");
            JsonNode properties = root.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(name -> {
                    JsonNode prop = properties.get(name);
                    String type = prop == null || prop.get("type") == null
                            ? "string" : prop.get("type").asText("string");
                    ToolParameter parameter = ToolParameter.of(name, type);
                    if (required != null && required.isArray()) {
                        for (JsonNode item : required) {
                            if (name.equals(item.asText())) {
                                parameter = ToolParameter.required(name, type);
                                break;
                            }
                        }
                    }
                    if (prop != null && prop.get("description") != null) {
                        parameter = parameter.describedAs(prop.get("description").asText());
                    }
                    parameters.add(parameter);
                });
            }
        } catch (Exception ignored) {
            // schema 解析失败按无参工具处理——调用时参数透传给 MCP 端校验
        }
        return ToolSchema.of(id,
                definition.description() == null ? "" : definition.description(),
                parameters.toArray(new ToolParameter[0]));
    }
}
