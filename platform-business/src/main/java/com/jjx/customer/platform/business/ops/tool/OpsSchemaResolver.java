package com.jjx.customer.platform.business.ops.tool;

import com.agentframework.engine.core.NodeContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;

/**
 * 阶段出口护栏的 schema 解析器：按 {@code interface} 槽位推导 schema id
 * （取路径末两段以下划线连接，如 /api/invoice/reverse → invoice_reverse），
 * 未收录对应 schema 的接口自动无护栏（不误伤）。
 */
public final class OpsSchemaResolver {

    private OpsSchemaResolver() {
    }

    /**
     * @param tool 校验工具（schema 加载与缓存复用它）
     * @return 出口护栏解析函数
     */
    public static Function<NodeContext, JsonNode> byInterfaceSlot(ValidateRequestTool tool) {
        return context -> {
            String iface = context.slots().getString("interface", "");
            String schemaId = schemaIdOf(iface);
            return schemaId == null ? null : tool.loadSchema(schemaId);
        };
    }

    /**
     * @param interfacePath 接口路径或名称
     * @return schema id（推导不出返回 null）
     */
    static String schemaIdOf(String interfacePath) {
        if (interfacePath == null || interfacePath.isBlank()) {
            return null;
        }
        String[] segments = interfacePath.trim().split("[^A-Za-z0-9]+");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String segment : segments) {
            if (!segment.isBlank()) {
                parts.add(segment.toLowerCase());
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return parts.get(parts.size() - 2) + "_" + parts.get(parts.size() - 1);
    }
}
