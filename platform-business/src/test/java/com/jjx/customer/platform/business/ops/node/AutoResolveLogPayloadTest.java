package com.jjx.customer.platform.business.ops.node;

import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.DefaultToolRegistry;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.runtime.session.DefaultSession;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.slot.Slots;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 日志反查的报文提取单测：日志里混着**应用自身的遥测 JSON**（最长的那块）与真正的业务请求报文时，
 * 不能把遥测当报文补进 payload 槽——实测会把 {@code {"_event":"step.output",...}} 摆到问齐卡片上，
 * 既难看又毒化第二阶段（生成/纠正报文）的输入。
 */
class AutoResolveLogPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final Session SESSION = new DefaultSession(
            "auto-resolve-log-test", null, null, "ops_diagnose", "1.0.0", "ops_diagnose_v2", "1.0.0");

    /** 反查替身：按给定日志行回结果。 */
    private static final class LogTool implements Tool {

        private final List<Map<String, Object>> lines;

        LogTool(List<Map<String, Object>> lines) {
            this.lines = lines;
        }

        @Override
        public String id() {
            return "query_logs";
        }

        @Override
        public com.agentframework.definition.tool.ToolSchema schema() {
            return com.agentframework.definition.tool.ToolSchema.of(id(), "查日志（测试替身）",
                    com.agentframework.definition.tool.ToolParameter.of("trace_id", "string"),
                    com.agentframework.definition.tool.ToolParameter.of("keyword", "string"),
                    com.agentframework.definition.tool.ToolParameter.of("start", "string"),
                    com.agentframework.definition.tool.ToolParameter.of("end", "string"),
                    com.agentframework.definition.tool.ToolParameter.of("limit", "number"));
        }

        @Override
        public ToolResult invoke(ToolInput input, ToolContext context) {
            return ToolResult.ok("（替身）" + lines.size() + " 行日志", Map.of("logs", lines));
        }
    }

    /** 遥测行 + 真报文行（真报文那行自己写了"报文"）。 */
    private static List<Map<String, Object>> telemetryAndPayload() {
        return List.of(
                Map.of("level", "INFO", "msg",
                        "{\"_event\":\"step.output\",\"step\":\"rag.query.normalize\",\"step_index\":0,"
                                + "\"spanId\":\"9f2c1a\",\"durationMs\":12}"),
                Map.of("level", "INFO", "msg",
                        "订单服务入参报文：{\"orderNo\":\"SO-20260919-001\",\"amount\":128.5,\"channel\":\"WECHAT\"}"));
    }

    /** 只有应用自身的日志（prompt 模板被包在遥测 JSON 里）——实测被当成"请求报文"摆上卡片。 */
    private static List<Map<String, Object>> promptTemplateOnly() {
        return List.of(
                Map.of("level", "INFO", "msg",
                        "{\"_event\":\"step.output\",\"step\":\"replan\",\"output\":"
                                + "\"{\\\"action\\\":\\\"continue|adjust|ask_human|escalate\\\","
                                + "\\\"reason\\\":\\\"一句话理由\\\"}\"}"),
                Map.of("level", "INFO", "msg",
                        "{\"action\":\"continue|adjust|ask_human|escalate\",\"reason\":\"一句话理由\"}"));
    }

    private static NodeDefinition node() {
        return CustomNodeDefinition.of("auto_resolve", "ops-auto-resolve", null);
    }

    @Test
    void 遥测JSON不当报文_真报文照抓() {
        Object payload = resolve(telemetryAndPayload()).get(OpsSlotCatalog.PAYLOAD);

        assertTrue(String.valueOf(payload).contains("orderNo"), "标注了报文的日志行应被提取，实际=" + payload);
        assertFalse(String.valueOf(payload).contains("_event"), "遥测 JSON 不该被当 payload");
    }

    @Test
    void prompt模板JSON不当报文_没有标记就不提取() {
        Map<String, Object> writes = resolve(promptTemplateOnly());

        assertFalse(writes.containsKey(OpsSlotCatalog.PAYLOAD),
                "日志里只有应用自身的 prompt 模板（无报文标记）→ 报文槽必须留空交给用户补，实际="
                        + writes.get(OpsSlotCatalog.PAYLOAD));
    }

    @Test
    void 日志里挖出接口路径_回头补接口不再问用户() {
        // 用户没给接口，日志报文里带着路径——接口规则层跑在反查之前，必须拿新证据补一轮
        List<Map<String, Object>> lines = List.of(
                Map.of("level", "ERROR", "msg",
                        "invoice-service ERROR 冲红失败：POST /api/invoice/reverse 请求报文："
                                + "{\"invoiceCode\":\"\",\"invoiceNumber\":\"00412345\"}"));
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new LogTool(lines));
        AutoResolveExecutor executor = new AutoResolveExecutor(null,
                new DefaultToolExecutor(registry, null, null, null), MAPPER, CLOCK,
                key -> "{{now}}\n{{missing}}\n{{confirmed}}\n{{question}}");

        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(OpsSlotCatalog.TIME, "2026-09-19T09:00~2026-09-19T10:00");
        slots.put(OpsSlotCatalog.TRACE_ID, "abc1234567890abcdef1234567890ab");
        NodeContext context = new NodeContext(null, SESSION, null, null, new Slots(slots), null,
                new Input("帮我看看为什么报错", Map.of(), Map.of()), null, null, null, null);

        Map<String, Object> writes = executor.execute(node(), context).slotWrites();

        assertEquals("/api/invoice/reverse", writes.get(OpsSlotCatalog.INTERFACE),
                "日志里挖到的接口路径要回头补上，否则卡片还在问「调用的是哪个接口」");
    }

    @Test
    void 日志里的响应也挖出来_没标记就不挖() {
        // 有「响应：」标记 → 挖进 response 槽
        List<Map<String, Object>> lines = List.of(
                Map.of("level", "ERROR", "msg",
                        "invoice-service ERROR 冲红失败：POST /api/invoice/reverse 请求报文："
                                + "{\"invoiceCode\":\"\",\"invoiceNumber\":\"00412345\"}"),
                Map.of("level", "INFO", "msg",
                        "invoice-service INFO 响应：{\"code\":\"INVOICE_CODE_REQUIRED\",\"success\":false}"));
        Object response = resolve(lines).get(OpsSlotCatalog.RESPONSE);
        assertTrue(String.valueOf(response).contains("INVOICE_CODE_REQUIRED"),
                "写明「响应：」的日志行要挖进 response 槽，实际=" + response);

        // 没有标记的行（哪怕有 JSON）不许当响应——高影响槽宁可漏
        List<Map<String, Object>> noMarker = List.of(
                Map.of("level", "INFO", "msg", "invoice-service INFO 处理中 {\"code\":\"OK\",\"success\":true}"));
        assertFalse(resolve(noMarker).containsKey(OpsSlotCatalog.RESPONSE),
                "没有响应标记的 JSON 不该被当响应");
    }

    /** 跑一轮自主补全，返回本轮槽位写入。 */
    private static Map<String, Object> resolve(List<Map<String, Object>> lines) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new LogTool(lines));
        AutoResolveExecutor executor = new AutoResolveExecutor(null,
                new DefaultToolExecutor(registry, null, null, null), MAPPER, CLOCK,
                key -> "{{now}}\n{{missing}}\n{{confirmed}}\n{{question}}");

        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(OpsSlotCatalog.INTERFACE, "/api/order/create");
        slots.put(OpsSlotCatalog.TIME, "2026-09-19T09:00~2026-09-19T10:00");
        slots.put(OpsSlotCatalog.TRACE_ID, "abc1234567890abcdef1234567890ab");
        NodeContext context = new NodeContext(null, SESSION, null, null, new Slots(slots), null,
                new Input("订单接口报错", Map.of(), Map.of()), null, null, null, null);
        return executor.execute(node(), context).slotWrites();
    }
}
