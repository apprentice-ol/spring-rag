package com.jjx.customer.platform.business.ops.node;

import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.slot.Slots;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;
import com.jjx.customer.platform.business.ops.AutonomyLevel;
import com.jjx.customer.platform.business.ops.OpsPrompts;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.slot.SlotProvenance;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 自主补全的档位闸门单测（P3）：同一段输入在不同档位下的补全结果必须不同——
 * L1 只做用户原文的确定性提取，L2 保持既有行为，L3 放宽额度。
 */
class AutoResolveAutonomyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneId.of("Asia/Shanghai"));

    /** 推断模型：固定返回一条 0.6 置信度的 environment 推断（L2 门槛 0.7 不生效，L3 门槛 0.5 生效）。 */
    private static final SingleTurnModel INFER = (system, user) ->
            "[{\"slot\":\"environment\",\"value\":\"prod\",\"confidence\":0.6,\"evidence\":\"用户说线上\"}]";

    private static AutoResolveExecutor executor() {
        // toolExecutor=null：反查层不参与，本测试只看规则层与推断层
        return new AutoResolveExecutor(INFER, null, MAPPER, CLOCK,
                key -> OpsPrompts.AUTO_RESOLVE_ASSET.equals(key)
                        ? "{{now}}\n{{missing}}\n{{confirmed}}\n{{question}}" : "");
    }

    /** 用户描述含唯一可匹配接口的英文词（invoice → invoice_reverse），且没给时间与环境。 */
    private static NodeContext context(AutonomyLevel level, String question) {
        Map<String, Object> slots = new LinkedHashMap<>();
        if (level != null) {
            slots.put(AutonomyLevel.SLOT, level.name());
        }
        return new NodeContext(null, null, null, null, new Slots(slots), null,
                new Input(question, Map.of(), Map.of()), null, null, null, null);
    }

    private static NodeDefinition node() {
        return CustomNodeDefinition.of("auto_resolve", "ops-auto-resolve", null);
    }

    @Test
    void L1_不代填缺省时间_不猜接口_不推断() {
        NodeResult result = executor().execute(node(),
                context(AutonomyLevel.L1, "invoice 接口报错，帮我看看"));

        assertFalse(result.slotWrites().containsKey(OpsSlotCatalog.TIME), "L1 不代填缺省时间窗");
        assertFalse(result.slotWrites().containsKey(OpsSlotCatalog.INTERFACE), "L1 不用接口目录猜接口");
        assertFalse(result.slotWrites().containsKey(OpsSlotCatalog.ENVIRONMENT), "L1 不推断");
        assertEquals("[]", result.slotWrites().get(AutoResolveExecutor.INFERRED_SLOTS_SLOT),
                "没有任何自主补全 → 来源登记为空");
        assertEquals(3, result.slotWrites().get(SlotExtractExecutor.MISSING_COUNT_SLOT),
                "L1 一个都不补，三项必填全缺（等问用户）");
    }

    @Test
    void L2_既有行为_缺省时间与接口目录匹配都生效() {
        NodeResult result = executor().execute(node(),
                context(AutonomyLevel.L2, "invoice 接口报错，帮我看看"));

        assertNotNull(result.slotWrites().get(OpsSlotCatalog.TIME), "L2 落目录缺省时间窗");
        assertEquals("invoice_reverse", result.slotWrites().get(OpsSlotCatalog.INTERFACE),
                "L2 走接口目录唯一匹配");
        assertFalse(result.slotWrites().containsKey(OpsSlotCatalog.ENVIRONMENT),
                "0.6 置信度低于 L2 门槛 0.7，不生效");
        assertTrue(String.valueOf(result.slotWrites().get(AutoResolveExecutor.INFERRED_SLOTS_SLOT))
                .contains(SlotProvenance.SOURCE_DEFAULT), "来源登记应写明 default");
    }

    @Test
    void L3_门槛放宽_低置信推断也生效并登记来源() {
        NodeResult result = executor().execute(node(),
                context(AutonomyLevel.L3, "invoice 接口报错，帮我看看"));

        assertEquals("prod", result.slotWrites().get(OpsSlotCatalog.ENVIRONMENT), "L3 门槛 0.5，0.6 应生效");
        String provenance = String.valueOf(result.slotWrites().get(AutoResolveExecutor.INFERRED_SLOTS_SLOT));
        assertTrue(provenance.contains(SlotProvenance.SOURCE_INFERRED), "推断值要登记来源（前端角标据此渲染）");
        assertTrue(provenance.contains("\"slot\":\"environment\""));
    }

    @Test
    void 档位槽缺失_按缺省L2处理() {
        NodeResult result = executor().execute(node(), context(null, "invoice 接口报错，帮我看看"));

        assertNotNull(result.slotWrites().get(OpsSlotCatalog.TIME), "没有档位槽 = 老行为（L2）");
        assertFalse(result.slotWrites().containsKey(OpsSlotCatalog.ENVIRONMENT));
    }

    // -----------------------------------------------------------------------------------------
    // 日志反查层的档位额度（L2 两次刚好够「关键字 + 精查」，L3 才有余量换条件重试）
    // -----------------------------------------------------------------------------------------

    /** 反查替身：带时间窗的关键字查命中不到 traceId，去掉窗口才命中。 */
    private static final class WindowBlindLogTool implements com.agentframework.engine.toolexecutor.Tool {

        private int calls;

        @Override
        public String id() {
            return "query_logs";
        }

        @Override
        public com.agentframework.definition.tool.ToolSchema schema() {
            // 参数契约必须与真实 query_logs 同形：DefaultToolExecutor 会先按 schema 校验参数，
            // 声明成 noArgs 会被参数校验拦下（工具根本不会被调用）
            return com.agentframework.definition.tool.ToolSchema.of(id(), "查日志（测试替身）",
                    com.agentframework.definition.tool.ToolParameter.of("keyword", "string"),
                    com.agentframework.definition.tool.ToolParameter.of("start", "string"),
                    com.agentframework.definition.tool.ToolParameter.of("end", "string"),
                    com.agentframework.definition.tool.ToolParameter.of("limit", "number"));
        }

        @Override
        public com.agentframework.engine.toolexecutor.ToolResult invoke(
                com.agentframework.engine.toolexecutor.ToolInput input,
                com.agentframework.engine.toolexecutor.ToolContext context) {
            calls++;
            if (input.arguments().containsKey("start")) {
                return com.agentframework.engine.toolexecutor.ToolResult.ok("（替身）窗口内无日志",
                        Map.of("logs", List.of()));
            }
            return com.agentframework.engine.toolexecutor.ToolResult.ok("（替身）全时段命中",
                    Map.of("logs", List.of(Map.of("trace_id", "abc1234567890abcdef"))));
        }
    }

    /** 反查路径要读会话 id（工具调用上下文），给个最小会话替身。 */
    private static final com.agentframework.runtime.session.Session SESSION =
            new com.agentframework.runtime.session.DefaultSession(
                    "auto-resolve-test", null, null, "ops_diagnose", "1.0.0", "ops_diagnose_v2", "1.0.0");

    /** 带时间窗与接口的现场：关键字反查必带窗口（时间由用户给出，不落缺省）。 */
    private static NodeContext logContext(AutonomyLevel level) {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(AutonomyLevel.SLOT, level.name());
        slots.put(OpsSlotCatalog.TIME, "2026-09-19T09:00~2026-09-19T10:00");
        slots.put(OpsSlotCatalog.INTERFACE, "/api/invoice/reverse");
        return new NodeContext(null, SESSION, null, null, new Slots(slots), null,
                new Input("invoice 接口报错，帮我看看", Map.of(), Map.of()), null, null, null, null);
    }

    private static NodeResult resolve(AutonomyLevel level, WindowBlindLogTool tool) {
        com.agentframework.engine.toolexecutor.DefaultToolRegistry registry =
                new com.agentframework.engine.toolexecutor.DefaultToolRegistry();
        registry.register(tool);
        AutoResolveExecutor executor = new AutoResolveExecutor(null,
                new com.agentframework.engine.toolexecutor.DefaultToolExecutor(registry, null, null, null),
                MAPPER, CLOCK, key -> "{{now}}\n{{missing}}\n{{confirmed}}\n{{question}}");
        return executor.execute(node(), logContext(level));
    }

    @Test
    void L2_反查额度刚好够关键字与精查_不换条件重试() {
        WindowBlindLogTool tool = new WindowBlindLogTool();
        NodeResult result = resolve(AutonomyLevel.L2, tool);

        assertEquals(1, tool.calls, "L2 无余量：带窗查无果就到此为止，直接问用户");
        assertFalse(result.slotWrites().containsKey(OpsSlotCatalog.TRACE_ID), "没命中就不该落槽");
    }

    @Test
    void L3_反查额度有余量_去掉时间窗重试并命中() {
        WindowBlindLogTool tool = new WindowBlindLogTool();
        NodeResult result = resolve(AutonomyLevel.L3, tool);

        assertEquals(2, tool.calls, "L3 去掉时间窗再查一次");
        String traceId = String.valueOf(result.slotWrites().get(OpsSlotCatalog.TRACE_ID));
        assertTrue(traceId.startsWith("abc1234567890"), "重试命中应落槽，实际=" + traceId);
        assertTrue(String.valueOf(result.slotWrites().get(AutoResolveExecutor.INFERRED_SLOTS_SLOT))
                .contains(SlotProvenance.SOURCE_LOG), "来源登记为日志反查");
    }
}
