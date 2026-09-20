package com.jjx.customer.platform.business.ops.node;

import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.workflow.HumanRequest;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.DefaultToolRegistry;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.slot.Slots;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.ops.AutonomyPolicy;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import com.jjx.customer.platform.business.ops.slot.SlotProvenance;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 日志反查的两条人在环中行为（2026-09-20 真机踩坑后补）：
 *
 * <ol>
 *   <li><b>反查为空要说话</b>：查了没命中时卡片必须反问"信息是否准确"——真机上
 *       10 分钟窗口内没有该单日志，「已自动补全」整块静默消失，用户分不清"查了没命中"
 *       和"根本没查"，看着像功能坏了；</li>
 *   <li><b>挂起恢复要重跑</b>：问齐环是 intake 阶段（挂起语义 = 等信息），用户这轮改了
 *       时间窗/单号后，上一轮"没查到"的结论就作废——必须拿最新槽位再查一遍。</li>
 * </ol>
 */
class LogBackfillRerunTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final com.agentframework.runtime.session.Session SESSION =
            new com.agentframework.runtime.session.DefaultSession(
                    "log-backfill-rerun-test", null, null, "ops_diagnose", "1.0.0", "ops_diagnose_v2", "1.0.0");

    /** prompt 正文替身（正文为空会让抽槽器跳过模型，退化成确定性路径）。 */
    private static final java.util.function.Function<String, String> PROMPTS = key -> "（测试）prompt 正文";

    /** 抽槽器替身模型：一律返回空对象（本测试只关心反查重跑，不关心抽槽结果）。 */
    private static final com.jjx.customer.platform.business.engine.adapter.SingleTurnModel EMPTY_MODEL =
            (system, user) -> "{}";

    /** 反查替身：固定返回给定日志行（空列表 = 什么都没查到）。 */
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

    /** 命中日志：带 traceId 的一行业务日志。 */
    private static List<Map<String, Object>> hitLines() {
        return List.of(Map.of(
                "trace_id", "7e4ff4ef8bddfbc5c0e19d482b9581ee",
                "level", "ERROR",
                "msg", "order-service ERROR 下单失败：orderNo=ORD53f64352 POST /api/invoice/reverse"));
    }

    private static LogBackfill backfill(LogTool tool) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        return new LogBackfill(new DefaultToolExecutor(registry, null, null, null), CLOCK);
    }

    /** 固定时钟：真机 2026-09-20 18:44 前后，样例日志在 15:44。 */
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
            java.time.Instant.parse("2026-09-20T10:44:00Z"), java.time.ZoneId.of("Asia/Shanghai"));

    private static NodeContext contextOf(Map<String, Object> slots, String inputText) {
        return new NodeContext(null, SESSION, null, null, new Slots(slots), null,
                new Input(inputText == null ? "" : inputText, Map.of(), Map.of()), null, null, null, null);
    }

    private static NodeDefinition node() {
        return CustomNodeDefinition.of("collect_slots", "ops-ask-missing", null);
    }

    /** 用户在问齐环里改了时间窗（首轮 10 分钟没查到，这轮说了 3 小时）。 */
    private static Map<String, Object> resumeSlots() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(AskMissingExecutor.CLARIFY_QUESTION_SLOT, "调用的是哪个接口？");
        slots.put(OpsSlotCatalog.TIME, "最近3小时");
        slots.put(OpsSlotCatalog.TRACE_ID, "ORD53f64352");
        return slots;
    }

    @Test
    void 反查命中_不留空结果说明() {
        Map<String, Object> writes = new LinkedHashMap<>();
        LogBackfill.Outcome outcome = backfill(new LogTool(hitLines())).resolve(node(),
                contextOf(resumeSlots(), "最近3小时"), confirmedOf(resumeSlots()),
                writes, new java.util.ArrayList<>(), AutonomyPolicy.of("L2"));

        assertTrue(outcome.hit(), "日志里有该单号 → 命中");
        assertEquals("", outcome.note(), "命中时不该有「未命中」说明");
        assertEquals("7e4ff4ef8bddfbc5c0e19d482b9581ee", writes.get(OpsSlotCatalog.TRACE_ID),
                "关键字反查要把业务键换成真 traceId");
    }

    @Test
    void 反查空_给出含关键字与时间窗的诚实说明() {
        Map<String, Object> writes = new LinkedHashMap<>();
        LogBackfill.Outcome outcome = backfill(new LogTool(List.of())).resolve(node(),
                contextOf(resumeSlots(), "最近3小时"), confirmedOf(resumeSlots()),
                writes, new java.util.ArrayList<>(), AutonomyPolicy.of("L2"));

        assertFalse(outcome.hit());
        assertTrue(outcome.note().contains("ORD53f64352"), "说明要写清查了哪个关键字，实际=" + outcome.note());
        // 窗口按人读形态展示（相对短语已换算成绝对窗口）——用户要能对上"我查的是哪一段"
        assertTrue(outcome.note().contains("2026-09-20 15:44 ~ 18:44"),
                "说明要写清查了哪个窗口（人读形态），实际=" + outcome.note());
        assertTrue(outcome.note().contains("未命中"), "说明要写明没查到，实际=" + outcome.note());
    }

    @Test
    void 问齐恢复重跑反查_命中则补进槽位() {
        AskMissingExecutor executor = new AskMissingExecutor(new OpsSlotExtractor(EMPTY_MODEL, MAPPER, PROMPTS), null, backfill(new LogTool(hitLines())));
        Map<String, Object> slots = resumeSlots();
        slots.put(ActExecutorSlot(), "最近3小时");   // 本轮答复（恢复输入回灌）

        NodeResult result = executor.execute(node(), contextOf(slots, "最近3小时"));

        assertEquals("7e4ff4ef8bddfbc5c0e19d482b9581ee", result.slotWrites().get(OpsSlotCatalog.TRACE_ID),
                "恢复重跑拿到真 traceId 就该落槽（否则卡片还显示业务单号）");
        assertEquals("", result.slotWrites().get(LogBackfill.LOG_LOOKUP_SLOT), "命中 → 无空结果说明");
    }

    @Test
    void 问齐恢复仍查不到_卡片反问用户确认信息() {
        AskMissingExecutor executor = new AskMissingExecutor(new OpsSlotExtractor(EMPTY_MODEL, MAPPER, PROMPTS), null, backfill(new LogTool(List.of())));
        Map<String, Object> slots = resumeSlots();
        slots.put(ActExecutorSlot(), "最近3小时");

        NodeResult result = executor.execute(node(), contextOf(slots, "最近3小时"));

        String note = String.valueOf(result.slotWrites().get(LogBackfill.LOG_LOOKUP_SLOT));
        assertTrue(note.contains("未命中"), "空结果说明要落槽（前端与结论证据链都读它），实际=" + note);
        String ask = String.valueOf(result.slotWrites().get(AskMissingExecutor.CLARIFY_QUESTION_SLOT));
        assertTrue(ask.startsWith(note), "问句前面要先反问「信息是否准确」，实际=" + ask);
        assertTrue(ask.contains("请确认"), "要明确请用户确认/修改信息，实际=" + ask);

        String request = String.valueOf(result.slotWrites().get(HumanRequest.PENDING_SLOT));
        assertTrue(request.contains("未命中"), "结构化卡片（领读句）也要带上空结果说明，实际=" + request);
    }

    @Test
    void 恢复轮给的原样时间短语要换算成绝对窗口() {
        // 真机 2026-09-20：恢复轮用户答「最近3小时」，解释器写入的是**原样短语**（抽槽路径会归一化，
        // 解释器路径不会）→ 原样当 start 传给日志接口 → 窗口内的日志查不到，卡片显示"未命中"
        Map<String, Object> writes = new LinkedHashMap<>();
        Map<String, String> confirmed = confirmedOf(resumeSlots());
        backfill(new LogTool(List.of())).resolve(node(),
                contextOf(resumeSlots(), "最近3小时"), confirmed, writes, new java.util.ArrayList<>(),
                AutonomyPolicy.of("L2"));

        String window = confirmed.get(OpsSlotCatalog.TIME);
        assertTrue(window.contains("~"), "原样短语必须换算成绝对窗口再查，实际=" + window);
        assertTrue(window.startsWith("2026-09-20T15:44"), "换算结果要覆盖 3 小时前，实际=" + window);
        assertEquals(window, writes.get(OpsSlotCatalog.TIME), "换算后的窗口要落槽（卡片与后续阶段同源）");
    }

    @Test
    void 空结果说明里的窗口是人读形态() {
        Map<String, Object> writes = new LinkedHashMap<>();
        Map<String, String> confirmed = confirmedOf(resumeSlots());
        LogBackfill.Outcome outcome = backfill(new LogTool(List.of())).resolve(node(),
                contextOf(resumeSlots(), "最近3小时"), confirmed, writes, new java.util.ArrayList<>(),
                AutonomyPolicy.of("L2"));

        assertTrue(outcome.note().contains("2026-09-20 15:44 ~ 18:44"),
                "卡片文案不该端出 ISO 原文（T 分隔 + 日期重复），实际=" + outcome.note());
        assertFalse(outcome.note().contains("T15:44"),
                "不该出现 ISO 原文，实际=" + outcome.note());
    }

    @Test
    void 台账续写_首轮反查到的接口不因这轮换窗口而丢失() {
        Map<String, Object> slots = resumeSlots();
        slots.put(ActExecutorSlot(), "最近3小时");
        slots.put(AutoResolveExecutor.INFERRED_SLOTS_SLOT,
                SlotProvenance.upsert("[]", OpsSlotCatalog.INTERFACE, "/api/invoice/reverse",
                        SlotProvenance.SOURCE_LOG, "traceId 精查日志命中接口路径"));
        Map<String, Object> writes = new LinkedHashMap<>();
        Map<String, String> confirmed = confirmedOf(slots);

        backfill(new LogTool(List.of())).rerun(node(), contextOf(slots, "最近3小时"), confirmed, writes,
                AutonomyPolicy.of("L2"));

        String merged = String.valueOf(writes.get(AutoResolveExecutor.INFERRED_SLOTS_SLOT));
        assertTrue(merged.contains("/api/invoice/reverse"),
                "首轮台账要续写而不是推倒重来，实际=" + merged);
    }

    /** 用户恢复答复回灌的槽位名（与 ActExecutor 常量同值）。 */
    private static String ActExecutorSlot() {
        return AskMissingExecutor.USER_CLARIFY_SLOT;
    }

    private static Map<String, String> confirmedOf(Map<String, Object> slots) {
        Map<String, String> confirmed = new LinkedHashMap<>();
        for (OpsSlotCatalog.Spec spec : OpsSlotCatalog.ALL) {
            Object value = slots.get(spec.name());
            if (value instanceof String text && !text.isBlank()) {
                confirmed.put(spec.name(), text);
            }
        }
        return confirmed;
    }
}
