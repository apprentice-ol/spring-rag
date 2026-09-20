package com.jjx.customer.platform.business.ops;

import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;
import com.jjx.customer.platform.business.engine.outcome.OutcomeKind;
import com.jjx.customer.platform.business.task.AgentFinding;
import com.jjx.customer.platform.business.task.AgentTaskState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskFollowUpQa} 的判定与直答。语义分类<b>全交模型</b>（2026-09-20 第三轮修正：
 * 确定性词表覆盖度不够，真机首日踩坑「给我一个修复后的报文」）——单测钉的是三件事：
 *
 * <ol>
 *   <li><b>裁决的执行语义</b>：判 answer → 直答（材料注入正确）；判 rerun / 解析失败 /
 *       模型缺席 → empty 落回重跑（不劣化）；协议文本不耗模型；</li>
 *   <li><b>分类调用形态</b>：结论摘要随行（判「要的东西是否已在结论里」需要看见结论）；</li>
 *   <li><b>判据钉子</b>：qa-classify.md 必须承载「索取已有产物」判别与速判例——
 *       判据丢了 = 真机坑回归，测试要红。</li>
 * </ol>
 *
 * 语义判断本身（哪句话该判哪个）由 qa-classify.md 的判据与速判例承载，靠真机回归验证，
 * 单测不假装能测模型的语义理解。
 */
class TaskFollowUpQaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一次性脚本模型：按序出队，按序记录每次收到的 system/user 内容供断言。 */
    private static final class ScriptedModel implements SingleTurnModel {
        private final List<String> replies;
        private final AtomicInteger index = new AtomicInteger();
        final List<String> systems = new java.util.ArrayList<>();
        final List<String> users = new java.util.ArrayList<>();

        ScriptedModel(String... replies) {
            this.replies = List.of(replies);
        }

        String lastSystem() {
            return systems.isEmpty() ? null : systems.getLast();
        }

        String lastUser() {
            return users.isEmpty() ? null : users.getLast();
        }

        @Override
        public String ask(String system, String user) {
            systems.add(system);
            users.add(user);
            int i = index.getAndIncrement();
            return i < replies.size() ? replies.get(i) : null;
        }
    }

    private static AgentTaskState concluded(String summary) {
        return new AgentTaskState("task-1", "conv-1", "ops_diagnose", null,
                AgentTaskState.Status.CONCLUDED,
                Map.of("environment", "prod", "interface", "/api/invoice/reverse"),
                summary, "L2", "chain-1", 1);
    }

    private static List<AgentFinding> findings() {
        return List.of(AgentFinding.active("f1", "task-1", "conv-1",
                AgentFinding.Kind.ROOT_CAUSE, "order-service 处理 POST /api/invoice/reverse 时因 invoiceNumber 缺失报错", null, 1));
    }

    private static TaskFollowUpQa qaOf(ScriptedModel model) {
        return new TaskFollowUpQa(model, null, MAPPER, key -> "prompt:" + key);
    }

    private static TaskFollowUpQa qaOf(ScriptedModel model, TaskFollowUpQa.Retriever retriever) {
        return new TaskFollowUpQa(model, retriever, MAPPER, key -> "prompt:" + key);
    }

    private static Optional<OpsRunner.OpsAnswer> ask(TaskFollowUpQa qa, AgentTaskState task, String question) {
        return qa.tryAnswer(task, question, TaskFollowUpQaTest::findings);
    }

    @Test
    void 判answer走直答_材料含结论与主张() {
        ScriptedModel model = new ScriptedModel("{\"label\":\"answer\"}", "解读：因为日志显示 invoiceNumber 缺失。");
        Optional<OpsRunner.OpsAnswer> answer = ask(qaOf(model),
                concluded("排查结论：字段缺失导致失败。"), "为什么结论说缺 invoiceNumber？");

        assertTrue(answer.isPresent(), "判 answer 应走任务内直答");
        assertEquals(OutcomeKind.DIRECT, answer.get().kind());
        assertTrue(answer.get().text().contains("解读"));
        // QA 轮轨迹：两步（分类 + 直答）、各计一次 LLM、task_qa 流程标记可区分完整诊断轮
        com.jjx.customer.platform.business.trace.model.TraceView trace = answer.get().trace();
        assertNotNull(trace, "QA 轮应有自己的轨迹（2026-09-20 补：追问轮此前 trace=null 前端无轨迹）");
        assertEquals(com.jjx.customer.platform.business.engine.AgentCatalog.OPS.id(), trace.getParadigm());
        assertEquals(TaskFollowUpQa.QA_WORKFLOW_MARKER, trace.getWorkflowId());
        assertEquals(2, trace.getLlmCallCount(), "分类 + 直答各计一次");
        assertEquals(2, trace.getSteps().size(), "轨迹应为两步");
        assertEquals("task_qa_classify", trace.getSteps().get(0).action());
        assertEquals("task_qa_answer", trace.getSteps().get(1).action());
        assertTrue(trace.getSteps().get(1).inputSummary().contains("主张 1 条"),
                "直答步入参摘要应反映材料规模");
        // 组装材料：结论全文、主张（[#1] 锚点）、槽位投影、问题原文都要进 user 内容
        assertTrue(model.lastUser().contains("排查结论：字段缺失导致失败。"), "应注入结论全文");
        assertTrue(model.lastUser().contains("[#1] [ROOT_CAUSE]"), "应注入带编号的主张");
        assertTrue(model.lastUser().contains("invoiceNumber 缺失"), "主张正文应在材料里");
        assertTrue(model.lastUser().contains("环境：prod"), "关键槽位应投影");
        assertTrue(model.lastUser().contains("为什么结论说缺 invoiceNumber"), "问题原文应带入");
    }

    @Test
    void 分类调用携带结论摘要与消息原文() {
        ScriptedModel model = new ScriptedModel("{\"label\":\"rerun\"}");
        assertFalse(ask(qaOf(model), concluded("排查结论：字段缺失导致失败。"), "随便什么").isPresent());
        assertTrue(model.lastUser().contains("排查结论：字段缺失导致失败。"),
                "分类要看见结论摘要——判「要的东西是否已在结论里」的依据");
        assertTrue(model.lastUser().contains("随便什么"), "消息原文应带入");
    }

    @Test
    void 判rerun_解析失败_模型缺席_一律落回重跑() {
        // 明确判 rerun
        assertFalse(ask(qaOf(new ScriptedModel("{\"label\":\"rerun\"}")), concluded("结论"),
                "不对，其实是时间窗选错了").isPresent(), "判 rerun 应落回重跑路径");
        // 输出不可解析
        assertFalse(ask(qaOf(new ScriptedModel("我拒绝输出 JSON")), concluded("结论"),
                "这笔账后来怎么样了").isPresent(), "分类输出不可解析按 rerun（不劣化）");
        // 模型缺席：QA 整体不可用
        assertFalse(ask(new TaskFollowUpQa(null, null, MAPPER, key -> "p"), concluded("结论"),
                "为什么缺 invoiceNumber？").isPresent());
        // 模型抛异常：绝不把异常抛给对话主链路
        assertFalse(ask(new TaskFollowUpQa((s, u) -> {
            throw new IllegalStateException("网关抖动");
        }, null, MAPPER, key -> "p"), concluded("结论"), "为什么缺 invoiceNumber？").isPresent());
    }

    @Test
    void 分类通过但直答出不来_落回重跑() {
        // 第二次出队越界返回 null：分类判了 answer，应答拿不到文本 → empty
        assertFalse(ask(qaOf(new ScriptedModel("{\"label\":\"answer\"}")), concluded("结论"),
                "为什么缺 invoiceNumber？").isPresent());
    }

    @Test
    void 协议文本不耗模型() {
        ScriptedModel model = new ScriptedModel("不该被调用");
        assertFalse(ask(qaOf(model), concluded("结论"), "#override:time=昨天全天").isPresent(),
                "#override: 是纠错载荷，本就走恢复通道（确定性规则，不经模型）");
        assertNull(model.lastUser(), "协议文本不该消耗模型调用");
    }

    @Test
    void 非CONCLUDED或空结论不进QA() {
        ScriptedModel model = new ScriptedModel("不该被调用");
        AgentTaskState suspended = new AgentTaskState("task-1", "conv-1", "ops_diagnose", "collect_slots",
                AgentTaskState.Status.SUSPENDED, Map.of(), "结论", null, null, 1);
        assertFalse(ask(qaOf(model), suspended, "为什么？").isPresent(),
                "挂起中的追问是「对挂起提问的答复」，必须走恢复通道，不能被 QA 截胡");
        assertFalse(ask(qaOf(model), concluded("  "), "为什么？").isPresent(),
                "无结论可解读（异常路径落的 CONCLUDED）应落回重跑");
        assertNull(model.lastUser());
    }

    /**
     * 真机钉（2026-09-20）：「给我一个修复后的报文」曾命中词表「修复」被判重跑、复读整份结论。
     * 语义判断交给模型后，钉子钉的是<b>判据本身</b>——qa-classify.md 必须承载「索取已有产物」
     * 判别与速判例（含真机原句），判据被删/被改丢，这里立刻红。
     */
    @Test
    void 索取已有产物判answer_判据钉在prompt里() {
        ScriptedModel model = new ScriptedModel("{\"label\":\"answer\"}",
                "修正后的报文如下（取自上一轮结论「修正动作」段）：\n```json\n{...}\n```");
        // 用真实 classpath 资产（钉的就是 qa-classify.md 的内容，lambda 替身断言不到）
        TaskFollowUpQa qa = new TaskFollowUpQa(model, null, MAPPER,
                new com.jjx.customer.platform.config.prompt.PromptStore()::raw);
        Optional<OpsRunner.OpsAnswer> answer = qa.tryAnswer(
                concluded("排查结论：invoiceCode 为空被拦截。修正动作：修正后报文 {\"invoiceCode\":\"0412345\",...}"),
                "给我一个修复后的报文", TaskFollowUpQaTest::findings);
        assertTrue(answer.isPresent(), "索取结论已有产物（修复报文）应走任务内直答，不重跑");
        assertTrue(model.systems.getFirst().contains("索取"), "qa-classify.md 必须承载「索取已有产物」判别");
        assertTrue(model.systems.getFirst().contains("给我一个修复后的报文"),
                "速判例必须含真机原句——判据与踩坑句是配套的");
    }

    /**
     * 真机回归（2026-09-20 第四轮）：「从接口文档中看下是否符合规格」曾被判 rerun 走全量 SOP，
     * 日志查不出任何东西挂在 adjust 重试用尽的决策卡上。retrieve 分支 = 分类时改写检索问句 →
     * 一次针对性检索 → 命中进材料直答，不进 SOP、不开 attempt。
     */
    @Test
    void 判retrieve走针对性检索直答() {
        ScriptedModel model = new ScriptedModel(
                "{\"label\":\"retrieve\",\"query\":\"invoice_reverse 接口 请求报文 字段 必填 规格\"}",
                "该接口请求字段要求：invoiceCode 必填……[ref=1]");
        StringBuilder asked = new StringBuilder();
        Optional<OpsRunner.OpsAnswer> answer = qaOf(model, q -> {
            asked.append(q);
            return "命中 2 条：\n[ref=1] invoice_reverse 请求字段表：invoiceCode 必填…\n[ref=2] ……";
        }).tryAnswer(concluded("排查结论：invoiceCode 为空被拦截。"), "从接口文档中看下是否符合规格",
                TaskFollowUpQaTest::findings);

        assertTrue(answer.isPresent(), "检索型追问应走针对性检索直答，不重跑 SOP");
        assertTrue(asked.toString().contains("invoice_reverse"), "检索问句应是分类时改写后的形式，非原句");
        assertTrue(model.users.get(1).contains("[ref=1]"), "检索命中应注入直答材料");
        assertEquals(3, answer.get().trace().getSteps().size(), "三步：classify → retrieve → answer");
        assertEquals("task_qa_retrieve", answer.get().trace().getSteps().get(1).action());
        assertEquals(2, answer.get().trace().getLlmCallCount(), "检索不是 LLM 调用（分类 + 直答各一）");
    }

    @Test
    void 检索通道缺席_缺query_检索异常_一律落回重跑() {
        ScriptedModel model = new ScriptedModel("{\"label\":\"retrieve\",\"query\":\"接口 文档\"}");
        // 通道缺席
        assertFalse(ask(qaOf(model), concluded("结论"), "从接口文档中看下").isPresent(),
                "retriever 为 null 时 retrieve 判定应降级 rerun");
        // 判了 retrieve 但没给 query（协议不完整）
        ScriptedModel noQuery = new ScriptedModel("{\"label\":\"retrieve\"}");
        StringBuilder never = new StringBuilder();
        assertFalse(ask(qaOf(noQuery, q -> never.append("不该被调").toString()), concluded("结论"),
                "看下文档").isPresent(), "缺检索问句应降级 rerun");
        assertEquals("", never.toString());
        // 检索抛异常：统一降级，不冒充命中
        ScriptedModel ok = new ScriptedModel("{\"label\":\"retrieve\",\"query\":\"文档\"}");
        assertFalse(ask(qaOf(ok, q -> {
            throw new IllegalStateException("检索超时");
        }), concluded("结论"), "看下文档").isPresent());
    }
}
