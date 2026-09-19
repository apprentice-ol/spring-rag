package com.jjx.customer.platform.business.ops.node;

import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.workflow.HumanRequest;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.slot.Slots;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;
import com.jjx.customer.platform.business.ops.HumanResponseInterpreter;
import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 基本信息确认门单测（人在环中）：槽位齐备后先出确认单，**只有确认才开诊断**——
 * 确认页上顺手改一项（或给个方向）不该把人推进到诊断，改完刷新确认单再问一轮。
 */
class ConfirmExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final java.util.function.Function<String, String> PROMPTS = key -> "（测试）prompt 正文";

    private static ConfirmExecutor executor(SingleTurnModel model) {
        return new ConfirmExecutor(new HumanResponseInterpreter(model, MAPPER, PROMPTS));
    }

    private static NodeContext contextOf(Map<String, Object> slots, String inputText) {
        return new NodeContext(null, null, null, null, new Slots(slots), null,
                new Input(inputText == null ? "" : inputText, Map.of(), Map.of()), null, null, null, null);
    }

    private static NodeDefinition node() {
        return CustomNodeDefinition.of("confirm_slots", "ops-confirm", null);
    }

    /** 基本信息齐备 + 一项是机器补的（time 缺省值）。 */
    private static Map<String, Object> readySlots() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("environment", "prod");
        slots.put("interface", "/api/invoice/reverse");
        slots.put("time", "2026-09-19T10:00~2026-09-19T11:00");
        slots.put(AutoResolveExecutor.INFERRED_SLOTS_SLOT,
                "[{\"slot\":\"time\",\"value\":\"2026-09-19T10:00~2026-09-19T11:00\","
                        + "\"method\":\"default\",\"evidence\":\"缺省策略：最近30分钟\"}]");
        return slots;
    }

    private static HumanRequest cardOf(NodeResult result) throws Exception {
        Object json = result.slotWrites().get(HumanRequest.PENDING_SLOT);
        assertTrue(json instanceof String s && !s.isBlank(), "应暂存确认单");
        return MAPPER.readValue((String) json, HumanRequest.class);
    }

    @Test
    void 齐备后出确认单_机器补的带来源用户给的标user() throws Exception {
        NodeResult result = executor(null).execute(node(), contextOf(readySlots(), ""));

        assertTrue(result.isSuspended(), "确认前不推进");
        HumanRequest card = cardOf(result);
        assertEquals(HumanRequest.Kind.CONFIRM, card.kind());
        assertEquals(3, card.slots().size(), "已收集的基本信息都要摊开");
        assertEquals(1, card.options().size(), "只有一个明确动作：确认，开始排查");
        assertEquals("confirm", card.options().getFirst().value());

        HumanRequest.SlotAsk time = card.slots().stream()
                .filter(a -> "time".equals(a.name())).findFirst().orElseThrow();
        assertEquals("default", time.provenance(), "机器补的要标来源");
        assertTrue(String.valueOf(time.evidence()).contains("缺省策略"), "并带依据（回答「这值哪来的」）");

        HumanRequest.SlotAsk env = card.slots().stream()
                .filter(a -> "environment".equals(a.name())).findFirst().orElseThrow();
        assertEquals(ConfirmExecutor.SOURCE_USER, env.provenance(), "用户直供的标 user");
    }

    @Test
    void 点确认才开诊断() {
        Map<String, Object> slots = readySlots();
        slots.put(HumanRequest.PENDING_SLOT, "{\"kind\":\"CONFIRM\",\"prompt\":\"确认这些信息\"}");
        NodeResult result = executor(null)
                .execute(node(), contextOf(slots, "#decision:confirm"));

        assertTrue(result.isCompleted(), "确认后放行");
        assertEquals(OpsDiagnoseWorkflowFactory.INV_THINK_NODE, result.dynamicNextNodeId(), "进第一阶段");
        assertEquals("", result.slotWrites().get(HumanRequest.PENDING_SLOT), "确认单消费掉");
    }

    @Test
    void 打字说确认也放行_保守关键词() {
        Map<String, Object> slots = readySlots();
        slots.put(HumanRequest.PENDING_SLOT, "{\"kind\":\"CONFIRM\",\"prompt\":\"确认这些信息\"}");
        NodeResult result = executor((system, user) -> "{}")
                .execute(node(), contextOf(slots, "没问题，开始吧"));

        assertEquals(OpsDiagnoseWorkflowFactory.INV_THINK_NODE, result.dynamicNextNodeId(),
                "「没问题」这类明确确认语应放行");
    }

    @Test
    void 确认页上改一项_只刷新确认单不放行() throws Exception {
        Map<String, Object> slots = readySlots();
        slots.put(HumanRequest.PENDING_SLOT, "{\"kind\":\"CONFIRM\",\"prompt\":\"确认这些信息\"}");
        // 用户点了「改成 最近1小时」——确定性回传，不是确认
        NodeResult result = executor(null)
                .execute(node(), contextOf(slots, "#override:time=最近1小时"));

        assertTrue(result.isSuspended(), "改了东西就刷新确认单再问一轮，不能顺手把人推进诊断");
        assertEquals("最近1小时", result.slotWrites().get("time"), "改动要落槽");
        HumanRequest refreshed = cardOf(result);
        assertEquals(HumanRequest.Kind.CONFIRM, refreshed.kind());
        assertTrue(String.valueOf(result.slotWrites().get(AutoResolveExecutor.INFERRED_SLOTS_SLOT))
                .contains("user_override"), "来源状态机：被改的项转 user_override");
    }

    @Test
    void 清空某项_该槽从确认单消失() throws Exception {
        Map<String, Object> slots = readySlots();
        slots.put(HumanRequest.PENDING_SLOT, "{\"kind\":\"CONFIRM\",\"prompt\":\"确认这些信息\"}");
        NodeResult result = executor(null)
                .execute(node(), contextOf(slots, "#override:payload="));

        assertTrue(result.isSuspended());
        assertFalse(cardOf(result).slots().stream().anyMatch(a -> "payload".equals(a.name())),
                "空值=清空（这里本来没有 payload，也不该凭空出现）");
    }
}
