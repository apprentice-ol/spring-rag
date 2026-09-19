package com.jjx.customer.platform.business.ops.tool;

import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 报文确定性校验工具（T5/O8）：校验请求报文是否符合接口规范——纯代码校验不走模型。
 *
 * <p>schema 来源 {@code classpath:agent/schemas/<iface>.json}，进程内缓存；
 * 校验规则见 {@link JsonSchemaValidator}。工具始终返回成功（文本以
 * ❌ 开头逐行列错并指导修正），只有 schema 缺失与报文非 JSON 才返回失败。</p>
 */
public class ValidateRequestTool implements Tool {

    /** 工具 id。 */
    public static final String TOOL_ID = "validate_request";

    /** schema 资源前缀。 */
    public static final String SCHEMA_PATH = "/agent/schemas/";

    private static final Logger log = LoggerFactory.getLogger(ValidateRequestTool.class);

    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, JsonNode> schemaCache = new ConcurrentHashMap<>();

    /**
     * @param objectMapper JSON 解析
     */
    public ValidateRequestTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(TOOL_ID,
                "校验请求报文是否符合接口规范（确定性代码校验，不走模型）。参数 iface 为接口名"
                        + "（如 invoice_reverse），payload 为报文 JSON 文本；返回逐字段结果与修正建议。",
                ToolParameter.required("iface", "string"),
                ToolParameter.required("payload", "string"));
    }

    @Override
    public ToolResult invoke(ToolInput input, ToolContext context) {
        String iface = input.string("iface", "");
        String payloadText = input.string("payload", "");
        if (iface.isBlank() || payloadText.isBlank()) {
            return ToolResult.failed("iface 与 payload 均为必填");
        }
        JsonNode schema = loadSchema(iface);
        if (schema == null) {
            return ToolResult.failed("接口 " + iface + " 未收录校验规范。可用接口请查知识库，或让用户确认接口名。");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadText);
        } catch (Exception e) {
            return ToolResult.failed("报文不是合法 JSON：" + e.getMessage());
        }
        List<String> errors = JsonSchemaValidator.validate(schema, payload);
        if (errors.isEmpty()) {
            return ToolResult.ok("✅ 校验通过：报文符合 " + iface + " 规范。",
                    Map.of("valid", true, "iface", iface));
        }
        StringBuilder sb = new StringBuilder("❌ 校验未通过（").append(errors.size()).append(" 处）：\n");
        for (String error : errors) {
            sb.append("- ").append(error).append('\n');
        }
        sb.append("请对照上述问题修正报文后重新校验。");
        return ToolResult.ok(sb.toString(), Map.of("valid", false, "iface", iface, "errors", errors));
    }

    /**
     * @param iface 接口名
     * @return 对应 schema（null = 未收录；缓存中保留 null 语义避免重复读盘）
     */
    JsonNode loadSchema(String iface) {
        return schemaCache.computeIfAbsent(iface, name -> {
            try (var in = ValidateRequestTool.class.getResourceAsStream(SCHEMA_PATH + name + ".json")) {
                if (in == null) {
                    return null;
                }
                JsonNode node = objectMapper.readTree(in.readNBytes(Integer.MAX_VALUE));
                return node == null || node.isMissingNode() || node.isNull() ? null : node;
            } catch (Exception e) {
                log.warn("[validate_request] schema {} 加载失败: {}", name, e.getMessage());
                return null;
            }
        });
    }
}
