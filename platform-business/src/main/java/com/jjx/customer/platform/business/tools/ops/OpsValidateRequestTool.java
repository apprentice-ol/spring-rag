package com.jjx.customer.platform.business.tools.ops;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.agent.framework.guard.JsonSchemaValidator;
import com.jjx.customer.platform.agent.framework.tool.ExtensionTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求报文校验扩展工具（T5）：按接口名加载 classpath schema 做确定性校验，不走模型。
 */
@Slf4j
@Component
public class OpsValidateRequestTool implements ExtensionTool {

    public static final String NAME = "validate_request";

    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> schemaCache = new ConcurrentHashMap<>();

    public OpsValidateRequestTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "校验请求报文是否符合接口规范（确定性代码校验，不走模型）。参数 iface 为接口名"
                + "（如 invoice_reverse），payload 为报文 JSON 文本；返回逐字段结果与修正建议。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{
                  "iface":{"type":"string","description":"接口名（schema 标识，如 invoice_reverse）"},
                  "payload":{"type":"string","description":"请求报文 JSON 文本"}
                },"required":["iface","payload"]}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String iface = invocation.str("iface", "");
        String payloadText = invocation.str("payload", "");
        if (iface.isBlank() || payloadText.isBlank()) {
            return ToolResult.error("iface 与 payload 均为必填");
        }
        JsonNode schema = loadSchema(iface);
        if (schema == null) {
            return ToolResult.error("接口 " + iface + " 未收录校验规范。可用接口请查知识库，或让用户确认接口名。");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadText);
        } catch (Exception e) {
            return ToolResult.error("报文不是合法 JSON：" + e.getMessage());
        }
        List<String> errors = JsonSchemaValidator.validate(schema, payload);
        if (errors.isEmpty()) {
            return ToolResult.ok("✅ 校验通过：报文符合 " + iface + " 规范。", List.of(),
                    Map.of("valid", true, "iface", iface));
        }
        StringBuilder sb = new StringBuilder("❌ 校验未通过（").append(errors.size()).append(" 处）：\n");
        for (String error : errors) {
            sb.append("- ").append(error).append('\n');
        }
        sb.append("请对照上述问题修正报文后重新校验。");
        return ToolResult.ok(sb.toString(), List.of(),
                Map.of("valid", false, "iface", iface, "errors", errors));
    }

    private JsonNode loadSchema(String iface) {
        return schemaCache.computeIfAbsent(iface, name -> {
            ClassPathResource resource = new ClassPathResource("agent/schemas/" + name + ".json");
            if (!resource.exists()) {
                return null;
            }
            try (var in = resource.getInputStream()) {
                JsonNode node = objectMapper.readTree(in.readNBytes(Integer.MAX_VALUE));
                return node == null || node.isMissingNode() ? null : node;
            } catch (Exception e) {
                log.warn("[validate_request] schema {} 加载失败: {}", name, e.getMessage());
                return null;
            }
        });
    }
}
