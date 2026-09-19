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
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import com.jjx.customer.platform.business.ops.slot.SlotProvenance;
import com.jjx.customer.platform.business.workflow.common.ActExecutor;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 问齐重入的人在环中单测（P2）：用户自由文本回复经 {@link HumanResponseInterpreter} 走后
 * 语义通道——补缺 / 推翻 auto-resolve 推断值 / 方向指令进 user_directive；
 * 解释器不可用时退化回抽槽器单通道（行为同 P1）。
 */
class AskMissingHandoffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** prompt 正文替身（正文为空会让解释器与抽槽器跳过模型，退化成确定性路径）。 */
    private static final java.util.function.Function<String, String> PROMPTS = key -> "（测试）prompt 正文";

    /** 按调用顺序出队的脚本模型（默认空决议——解释器判空的兜底路径）。 */
    private static final class Scripted implements SingleTurnModel {
        private final ArrayDeque<String> replies = new ArrayDeque<>();
        private int calls;

        Scripted enqueue(String... values) {
            for (String value : values) {
                replies.add(value);
            }
            return this;
        }

        @Override
        public String ask(String system, String user) {
            calls++;
            return replies.isEmpty() ? "{}" : replies.poll();
        }
    }

    private static AskMissingExecutor executor(SingleTurnModel model) {
        return new AskMissingExecutor(new OpsSlotExtractor(model, MAPPER, PROMPTS),
                new HumanResponseInterpreter(model, MAPPER, PROMPTS));
    }

    private static NodeContext contextOf(Map<String, Object> slots, String inputText) {
        return new NodeContext(null, null, null, null, new Slots(slots), null,
                new Input(inputText == null ? "" : inputText, Map.of(), Map.of()), null, null, null, null);
    }

    private static NodeDefinition node() {
        return CustomNodeDefinition.of("collect_slots", "ops-ask-missing", null);
    }

    /** 已问过一轮、必填三槽齐备的会话现场（重入只会消费答复，不再追问）。 */
    private static Map<String, Object> resumeSlots() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(AskMissingExecutor.CLARIFY_QUESTION_SLOT, "环境是正式还是测试？");
        slots.put("environment", "prod");           // auto-resolve 推断出来的值
        slots.put("interface", "/api/invoice/reverse");
        slots.put("time", "2026-09-19T10:00~2026-09-19T11:00");
        slots.put(HumanRequest.PENDING_SLOT,
                "{\"kind\":\"CLARIFY\",\"prompt\":\"请补充以下信息\",\"slots\":[]}");
        return slots;
    }

    @Test
    void 重入_用户推翻推断值并补充动态信息() {
        Map<String, Object> slots = resumeSlots();
        String reply = "环境是测试的，另外这个报错只在大促期间偶发";
        Scripted model = new Scripted().enqueue("{\"overrides\":{\"environment\":\"test\"},"
                + "\"directive\":\"只在大促期间偶发\"}");
        NodeResult result = executor(model).execute(node(), contextOf(slots, reply));

        assertTrue(result.isCompleted(), "必填齐备应放行");
        assertEquals("test", result.slotWrites().get("environment"), "用户明确纠正应覆盖推断值");
        assertEquals("只在大促期间偶发", result.slotWrites().get(ActExecutor.USER_DIRECTIVE_SLOT),
                "方向指令应进软消费通道（后续阶段 think prompt 可见）");
        assertEquals(reply, result.slotWrites().get(ActExecutor.USER_CLARIFY_SLOT), "原文照旧落 user_clarify");
        assertEquals("", result.slotWrites().get(HumanRequest.PENDING_SLOT), "齐备后应清掉挂起暂存");
    }

    @Test
    void 重入_解释器抽到槽后抽槽器不再重复调模型() {
        Map<String, Object> slots = resumeSlots();
        slots.remove("interface");                 // 缺 interface，等用户这一轮补
        Scripted model = new Scripted().enqueue("{\"slots\":{\"interface\":\"/api/order/create\"}}");
        NodeResult result = executor(model).execute(node(), contextOf(slots, "接口是 /api/order/create"));

        assertEquals("/api/order/create", result.slotWrites().get("interface"), "解释器抽到的槽值应落槽");
        assertTrue(result.isCompleted(), "补齐必填后应放行，不再追问");
        assertEquals(1, model.calls, "解释器已抽到槽，抽槽器不应再花一次模型调用");
    }

    @Test
    void 重入_解释器没抽到槽时抽槽器照常兜底() {
        Map<String, Object> slots = resumeSlots();
        slots.remove("interface");
        // 解释器只识别出方向指令没抽到槽 → 抽槽器必须仍跑（P1 语义：模型给出的线索别漏）
        Scripted model = new Scripted()
                .enqueue("{\"directive\":\"接口在订单模块\"}", "{\"interface\":\"/api/order/create\"}");
        NodeResult result = executor(model).execute(node(), contextOf(slots, "接口是 /api/order/create"));

        assertEquals("/api/order/create", result.slotWrites().get("interface"), "抽槽器兜底应补齐");
        assertEquals("接口在订单模块", result.slotWrites().get(ActExecutor.USER_DIRECTIVE_SLOT));
        assertEquals(2, model.calls, "解释器 + 抽槽器各一次");
    }

    @Test
    void 重入_槽位引用同时落指令时两者并存() {
        Map<String, Object> slots = resumeSlots();
        String reply = "traceId 是 abc123，按它去查日志，别再问我了";
        Scripted model = new Scripted().enqueue("{\"slots\":{\"trace_id\":\"abc123\"},"
                + "\"directive\":\"按 traceId 查日志\",\"autonomy_hint\":3}");
        NodeResult result = executor(model).execute(node(), contextOf(slots, reply));

        assertEquals("abc123", result.slotWrites().get("trace_id"));
        assertEquals("按 traceId 查日志", result.slotWrites().get(ActExecutor.USER_DIRECTIVE_SLOT));
        assertEquals(3, result.slotWrites().get(HumanResponseInterpreter.AUTONOMY_HINT_SLOT),
                "档位提示应落槽（P3 的 AutonomyPolicy 消费）");
    }

    @Test
    void 重入_点选纠正回传_零模型调用且改写来源() throws Exception {
        Map<String, Object> slots = resumeSlots();
        slots.put(AutoResolveExecutor.INFERRED_SLOTS_SLOT,
                "[{\"slot\":\"environment\",\"value\":\"prod\",\"method\":\"llm\","
                        + "\"evidence\":\"confidence=0.82，用户说线上问题\"}]");
        Scripted model = new Scripted(); // 不排任何回复：本路径不该调模型
        NodeResult result = executor(model).execute(node(),
                contextOf(slots, "#override:environment=test"));

        assertEquals("test", result.slotWrites().get("environment"), "点选纠正应即刻改写槽位");
        assertEquals(0, model.calls, "协议回传是确定性的，不耗模型");
        String provenance = String.valueOf(result.slotWrites().get(AutoResolveExecutor.INFERRED_SLOTS_SLOT));
        assertTrue(provenance.contains(SlotProvenance.SOURCE_OVERRIDDEN),
                "来源状态机应转 user_override，实际=" + provenance);
        assertFalse(provenance.contains("\"llm\""), "同槽位旧来源被替换（只留最新一条）");
    }

    @Test
    void 挂起_已自动补全项带值带来源透出给卡片() throws Exception {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("environment", "prod"); // auto-resolve 推断出来的
        slots.put(AutoResolveExecutor.INFERRED_SLOTS_SLOT,
                "[{\"slot\":\"environment\",\"value\":\"prod\",\"method\":\"llm\",\"evidence\":\"confidence=0.82\"}]");
        // 缺 interface / time → 继续问齐
        NodeResult result = executor(new Scripted()).execute(node(), contextOf(slots, "帮我看看"));

        assertTrue(result.isSuspended());
        HumanRequest request = MAPPER.readValue(
                (String) result.slotWrites().get(HumanRequest.PENDING_SLOT), HumanRequest.class);
        List<HumanRequest.SlotAsk> filled = request.slots().stream()
                .filter(HumanRequest.SlotAsk::hasValue).toList();
        assertEquals(1, filled.size(), "已补全项应作为「待确认」条目透出（与缺失问句同列）");
        assertEquals("environment", filled.getFirst().name());
        assertEquals("prod", filled.getFirst().value());
        assertEquals(SlotProvenance.SOURCE_INFERRED, filled.getFirst().provenance(), "来源随条目透出（前端角标）");
        assertTrue(String.valueOf(filled.getFirst().evidence()).contains("confidence=0.82"),
                "依据要随条目透出——用户会问「这值哪来的」，实际=" + filled.getFirst().evidence());
        assertEquals(List.of("prod", "test", "dev", "uat"), filled.getFirst().options(), "候选值供点选纠正");
        assertTrue(request.slots().stream().anyMatch(ask -> !ask.hasValue()), "缺失项仍在（interface/time）");
    }

    @Test
    void 重入_点选纠正后重问的卡片不再显示旧值() throws Exception {
        Map<String, Object> slots = resumeSlots();
        slots.remove("interface"); // 还缺 interface → 本轮会再问一次
        slots.put("time", "2026-09-19T10:00~2026-09-19T11:00");
        slots.put(AutoResolveExecutor.INFERRED_SLOTS_SLOT,
                "[{\"slot\":\"time\",\"value\":\"2026-09-19T10:00~2026-09-19T11:00\","
                        + "\"method\":\"default\",\"evidence\":\"缺省策略：最近30分钟\"}]");

        NodeResult result = executor(new Scripted())
                .execute(node(), contextOf(slots, "#override:time=最近1小时"));

        assertTrue(result.isSuspended(), "还缺 interface，应继续追问");
        HumanRequest request = MAPPER.readValue(
                (String) result.slotWrites().get(HumanRequest.PENDING_SLOT), HumanRequest.class);
        assertTrue(request.slots().stream().noneMatch(ask -> "time".equals(ask.name()) && ask.hasValue()),
                "刚被用户纠正的槽不该再以「已自动补全的旧值」出现在待确认列表里");
        String clarifyText = String.valueOf(result.slotWrites().get(AskMissingExecutor.CLARIFY_QUESTION_SLOT));
        assertFalse(clarifyText.contains("最近30分钟"),
                "旧缺省值不该再出现在补全说明里（写入前读 context 会拿到旧值），实际=" + clarifyText);
    }

    @Test
    void 重入_解释器不可用时行为同P1() {
        Map<String, Object> slots = resumeSlots();
        slots.remove("interface");
        String reply = "接口是 /api/order/create";
        // 解释器 null = 关闭语义通道：只走抽槽器（模型返回目录槽 JSON 也能补齐）
        AskMissingExecutor executor = new AskMissingExecutor(
                new OpsSlotExtractor(new Scripted().enqueue("{\"interface\":\"/api/order/create\"}"),
                        MAPPER, PROMPTS),
                null);
        NodeResult result = executor.execute(node(), contextOf(slots, reply));

        assertEquals("/api/order/create", result.slotWrites().get("interface"), "抽槽器兜底仍在");
        assertFalse(result.slotWrites().containsKey(ActExecutor.USER_DIRECTIVE_SLOT), "无解释器即无指令通道");
    }
}
