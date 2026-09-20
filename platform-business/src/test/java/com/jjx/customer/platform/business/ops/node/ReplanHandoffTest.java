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
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * replan 四态裁决的人在环中单测（单元级，不起引擎）：ask_human / escalate（无证据）/
 * adjust 额度耗尽统一收敛为 DECIDE 挂起（决策移交不再是终态）；重入消费 redirect / terminate；
 * D12（有工具证据的 escalate 降级 continue）行为保持；
 * P2 起重入回复经 {@link HumanResponseInterpreter} 解析（点选与模型不可用时退化到前缀解析）。
 */
class ReplanHandoffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** prompt 正文替身（正文为空会让解释器与抽槽器跳过模型，退化成确定性路径）。 */
    private static final java.util.function.Function<String, String> PROMPTS = key -> "（测试）prompt 正文";

    private static ReplanExecutor executor(SingleTurnModel model) {
        return new ReplanExecutor("inv", "排查", "定位", "inv_think", "res_think",
                model, MAPPER, PROMPTS, new HumanResponseInterpreter(model, MAPPER, PROMPTS));
    }

    /** 决策请求暂存（带继续/终止两个选项——decision 只认这里声明过的值）。 */
    private static String decideJson() throws Exception {
        return MAPPER.writeValueAsString(HumanRequest.decide("排查卡住了，需要你的决策",
                new HumanRequest.DecisionContext("阶段「排查」结束后需要人工判断", List.of("日志 14:00 无异常")),
                List.of(new HumanRequest.Choice("redirect", "我补充信息，继续排查", null),
                        new HumanRequest.Choice("terminate", "终止排查", null))));
    }

    private static Map<String, Object> decideSlots() throws Exception {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(HumanRequest.PENDING_SLOT, decideJson());
        slots.put("interface", "/api/invoice/reverse");
        return slots;
    }

    private static NodeContext contextOf(Map<String, Object> slots, String inputText) {
        return new NodeContext(null, null, null, null, new Slots(slots), null,
                new Input(inputText == null ? "" : inputText, Map.of(), Map.of()), null, null, null, null);
    }

    private static NodeDefinition node() {
        return CustomNodeDefinition.of("inv_replan", "replan", null);
    }

    private static HumanRequest pendingOf(NodeResult result) throws Exception {
        Object json = result.slotWrites().get(HumanRequest.PENDING_SLOT);
        assertTrue(json instanceof String s && !s.isBlank(), "挂起应暂存 pending_human_request");
        return MAPPER.readValue((String) json, HumanRequest.class);
    }

    @Test
    void ask_human裁决_挂起并暂存决策请求() throws Exception {
        ReplanExecutor executor = executor((system, user) ->
                "{\"action\":\"ask_human\",\"reason\":\"证据矛盾\",\"question\":\"报文与日志对不上，以哪边为准？\","
                        + "\"evidence\":\"日志 14:00 成功；报文时间戳 15:00\"}");
        NodeResult result = executor.execute(node(), contextOf(Map.of(), null));

        assertTrue(result.isSuspended(), "ask_human 应挂起（决策移交），而非直通终态");
        HumanRequest request = pendingOf(result);
        assertEquals(HumanRequest.Kind.DECIDE, request.kind());
        assertEquals("报文与日志对不上，以哪边为准？", request.prompt());
        assertTrue(request.context().evidence().toString().contains("14:00"), "证据应随请求透出");
        assertEquals(2, request.options().size(), "应带继续/终止两个选项");
        assertTrue(request.options().stream().anyMatch(c -> "redirect".equals(c.value())));
        assertTrue(request.options().stream().anyMatch(c -> "terminate".equals(c.value())));
        assertTrue(request.context().hypotheses().isEmpty(), "无假设时为空列表（卡片退化为现状文本）");
    }

    /**
     * P0（2026-09-20）：ask_human 的竞争假设外化——结构化决策面（真机轮 5 夹具：
     * adjust 用尽挂起时用户只看到一段糊状「日志查询未得到有效结果」）。
     */
    @Test
    void ask_human裁决_竞争假设外化_无判别动作的丢弃() throws Exception {
        ReplanExecutor executor = executor((system, user) ->
                "{\"action\":\"ask_human\",\"reason\":\"查不到\",\"question\":\"需要你的判断\","
                        + "\"evidence\":\"检索未命中\","
                        + "\"hypotheses\":["
                        + "{\"claim\":\"知识库缺该接口的字段表\",\"status\":\"verified\","
                        + "\"evidence\":\"检索未命中：通道有候选被精排过滤\",\"next_action\":\"提供接口文档或样例报文\"},"
                        + "{\"claim\":\"检索问句改写得不好\",\"status\":\"unverified\","
                        + "\"evidence\":\"\",\"next_action\":\"换问句重查\"},"
                        + "{\"claim\":\"可能就是没文档\",\"status\":\"unverified\","
                        + "\"evidence\":\"\",\"next_action\":\"\"}]}");   // 无判别动作 → 必须丢弃
        NodeResult result = executor.execute(node(), contextOf(Map.of(), null));

        assertTrue(result.isSuspended());
        List<HumanRequest.Hypothesis> hypotheses = pendingOf(result).context().hypotheses();
        assertEquals(2, hypotheses.size(), "无 next_action 的假设不进决策卡（不可证伪的假设没有决策增量）");
        assertEquals("知识库缺该接口的字段表", hypotheses.getFirst().claim());
        assertEquals("verified", hypotheses.getFirst().status());
        assertTrue(hypotheses.getFirst().evidence().contains("精排过滤"));
        assertTrue(hypotheses.getFirst().nextAction().contains("样例报文"));
        assertEquals("换问句重查", hypotheses.get(1).nextAction());
    }

    /** 旧挂起会话的暂存 JSON（无 hypotheses 字段）反序列化兼容——存量恢复不受协议扩展影响。 */
    @Test
    void 旧决策JSON无hypotheses字段_反序列化为空列表() throws Exception {
        String legacy = "{\"kind\":\"DECIDE\",\"prompt\":\"需要判断\",\"slots\":[],"
                + "\"context\":{\"summary\":\"上下文\",\"evidence\":[\"事实一条\"]},"
                + "\"options\":[{\"value\":\"redirect\",\"label\":\"继续\"}],\"allowFreeText\":true}";
        HumanRequest parsed = MAPPER.readValue(legacy, HumanRequest.class);
        assertTrue(parsed.context() != null && parsed.context().hypotheses().isEmpty(),
                "缺 hypotheses 字段的旧 JSON 应归一化为空列表");
    }

    @Test
    void escalate裁决无证据_收敛为决策移交而非终态() throws Exception {
        ReplanExecutor executor = executor((system, user) ->
                "{\"action\":\"escalate\",\"reason\":\"缺 traceId 无法继续\"}");
        NodeResult result = executor.execute(node(), contextOf(Map.of(), null));

        assertTrue(result.isSuspended(), "无证据的 escalate 应挂起移交（终局判断交给人）");
        assertEquals(HumanRequest.Kind.DECIDE, pendingOf(result).kind());
        assertFalse(result.slotWrites().containsKey("escalate_reason"), "不再直写终态升级原因");
    }

    @Test
    void D12保持_有工具证据的escalate仍降级continue() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("inv_stage_output", "日志无异常，疑与发版相关");
        slots.put("inv_scratchpad", "Thought: 查日志\nAction: query_logs\nObservation: 无异常日志");
        ReplanExecutor executor = executor((system, user) ->
                "{\"action\":\"escalate\",\"reason\":\"定位不到根因\"}");
        NodeResult result = executor.execute(node(), contextOf(slots, null));

        assertTrue(result.isCompleted(), "有工具证据应降级 continue");
        assertEquals("res_think", result.dynamicNextNodeId());
        assertEquals("continue", result.slotWrites().get("replan_verdict"));
    }

    @Test
    void adjust额度耗尽_决策移交而非硬升级() throws Exception {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("inv_retries", 1); // 已达 MAX_ADJUST_RETRIES
        ReplanExecutor executor = executor((system, user) ->
                "{\"action\":\"adjust\",\"reason\":\"时间窗不对\",\"adjustment\":\"扩大到 24 小时\"}");
        NodeResult result = executor.execute(node(), contextOf(slots, null));

        assertTrue(result.isSuspended(), "额度耗尽应挂起移交（用户可能给得出新方向）");
        assertEquals(HumanRequest.Kind.DECIDE, pendingOf(result).kind());
    }

    @Test
    void 挂起重入_自由文本按redirect重跑() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(HumanRequest.PENDING_SLOT, "{\"kind\":\"DECIDE\",\"prompt\":\"需要判断\"}");
        slots.put("user_clarify", "traceId 是 abc123，按它再查一遍");
        ReplanExecutor executor = executor(null);   // 模型不可用 → 退化到前缀解析（P1 行为）
        NodeResult result = executor.execute(node(), contextOf(slots, "traceId 是 abc123，按它再查一遍"));

        assertTrue(result.isCompleted(), "重入应产出路由结果");
        assertEquals("inv_think", result.dynamicNextNodeId(), "redirect 应回本阶段重跑");
        assertEquals("redirect", result.slotWrites().get("replan_verdict"));
        assertTrue(String.valueOf(result.slotWrites().get("user_directive")).contains("abc123"),
                "用户指令应进 user_directive（软消费通道，跨阶段可见）");
        assertFalse(String.valueOf(result.slotWrites().get("replan_note")).isBlank(),
                "replan_note 应带上「为什么重跑」的指针");
        assertEquals("", result.slotWrites().get(HumanRequest.PENDING_SLOT), "暂存应一次性消费");
        assertEquals("", result.slotWrites().get("inv_stage_output"), "重跑前清上一轮产出（同 D8）");
    }

    @Test
    void 挂起重入_terminate选择走终态() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(HumanRequest.PENDING_SLOT, "{\"kind\":\"DECIDE\",\"prompt\":\"需要判断\"}");
        slots.put("user_clarify", "#decision:terminate");
        ReplanExecutor executor = executor(null);
        NodeResult result = executor.execute(node(), contextOf(slots, "#decision:terminate"));

        assertTrue(result.isCompleted());
        assertEquals(OpsDiagnoseWorkflowFactory.ESCALATE_NODE, result.dynamicNextNodeId(),
                "用户确认终止应走升级终态节点");
        assertTrue(String.valueOf(result.slotWrites().get("escalate_reason")).contains("终止"));
        assertEquals("", result.slotWrites().get(HumanRequest.PENDING_SLOT));
    }

    @Test
    void 挂起重入_自由文本终止意图由解释器识别() throws Exception {
        Map<String, Object> slots = decideSlots();
        slots.put("user_clarify", "算了，这个问题先不查了");
        ReplanExecutor executor = executor((system, user) ->
                "{\"decision\":\"terminate\",\"directive\":\"先不查了\"}");
        NodeResult result = executor.execute(node(), contextOf(slots, "算了，这个问题先不查了"));

        assertEquals(OpsDiagnoseWorkflowFactory.ESCALATE_NODE, result.dynamicNextNodeId(),
                "「算了不查了」应被识别为终止，而不是当成新方向重跑一轮");
        assertTrue(String.valueOf(result.slotWrites().get("escalate_reason")).contains("终止"));
    }

    @Test
    void 挂起重入_选项外decision被丢弃按继续处理() throws Exception {
        Map<String, Object> slots = decideSlots();
        String reply = "换个思路再查查看，别急着下结论";
        slots.put("user_clarify", reply);
        // 模型自造 decision（不在选项里）→ 必须丢弃，不能让模型越权路由
        ReplanExecutor executor = executor((system, user) ->
                "{\"decision\":\"give_up_forever\",\"directive\":\"换个思路\"}");
        NodeResult result = executor.execute(node(), contextOf(slots, reply));

        assertEquals("inv_think", result.dynamicNextNodeId(), "越权 decision 应被丢弃（默认继续）");
        assertEquals("redirect", result.slotWrites().get("replan_verdict"));
        assertEquals("换个思路", result.slotWrites().get("user_directive"));
    }

    @Test
    void 挂起重入_槽位补充与推翻推断一并落槽() throws Exception {
        Map<String, Object> slots = decideSlots();
        slots.put("environment", "prod");           // auto-resolve 推断出来的值
        slots.put("user_clarify", "环境是测试的，traceId 用 abc123 再查");
        ReplanExecutor executor = executor((system, user) -> "{\"slots\":{\"trace_id\":\"abc123\"},"
                + "\"overrides\":{\"environment\":\"test\"},\"directive\":\"按 traceId 再查一遍\"}");
        NodeResult result = executor.execute(node(), contextOf(slots, "环境是测试的，traceId 用 abc123 再查"));

        assertEquals("redirect", result.slotWrites().get("replan_verdict"));
        assertEquals("test", result.slotWrites().get("environment"), "用户明确纠正应覆盖推断值");
        assertEquals("abc123", result.slotWrites().get("trace_id"), "补充的槽值应落槽");
        assertEquals("按 traceId 再查一遍", result.slotWrites().get("user_directive"));
    }

    @Test
    void 挂起重入_槽名白名单外的键被丢弃() throws Exception {
        Map<String, Object> slots = decideSlots();
        slots.put("user_clarify", "顺便说一句 llm_calls 应该清零");
        ReplanExecutor executor = executor((system, user) ->
                "{\"slots\":{\"llm_calls\":\"0\",\"llm_calls_x\":\"1\",\"symptoms\":\"偶发\"}}");
        NodeResult result = executor.execute(node(), contextOf(slots, "顺便说一句 llm_calls 应该清零"));

        assertEquals(1, result.slotWrites().get("llm_calls"),
                "llm_calls 只由执行器按真实调用计数（模型编的 0 不生效）");
        assertFalse(result.slotWrites().containsKey("llm_calls_x"), "编造的槽名应被丢弃");
        assertEquals("偶发", result.slotWrites().get("symptoms"), "目录内槽位正常落槽");
    }

    @Test
    void 挂起重入_点了继续却没补充_原请求重挂() throws Exception {
        Map<String, Object> slots = decideSlots();
        slots.put("user_clarify", "#decision:redirect");
        ReplanExecutor executor = executor((system, user) -> "{\"decision\":\"redirect\"}");
        NodeResult result = executor.execute(node(), contextOf(slots, "#decision:redirect"));

        assertTrue(result.isSuspended(), "没有新信息应重挂再问，而不是空跑一整轮阶段");
        HumanRequest again = pendingOf(result);
        assertEquals(HumanRequest.Kind.DECIDE, again.kind());
        assertTrue(again.prompt().contains("没有收到新信息"), "重挂正文应提示本轮没收到信息");
        assertEquals(2, again.options().size(), "选项原样保留");
    }

    @Test
    void 挂起重入_解释器判空但回复够长_整句兜底进指令() throws Exception {
        Map<String, Object> slots = decideSlots();
        String reply = "这个报错只在灰度机器上出现，正式环境没有";
        slots.put("user_clarify", reply);
        ReplanExecutor executor = executor((system, user) -> "{}");
        NodeResult result = executor.execute(node(), contextOf(slots, reply));

        assertEquals("redirect", result.slotWrites().get("replan_verdict"), "够长的文本不丢：仍按新线索重跑");
        assertEquals(reply, result.slotWrites().get("user_directive"), "原文整句落指令通道");
    }
}
