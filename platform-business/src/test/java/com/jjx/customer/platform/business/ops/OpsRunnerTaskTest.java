package com.jjx.customer.platform.business.ops;

import com.agentframework.engine.core.Engine;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.engine.PromptFingerprintResolver;
import com.jjx.customer.platform.business.engine.outcome.OutcomeKind;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.task.AgentFinding;
import com.jjx.customer.platform.business.task.AgentFindingServiceImpl;
import com.jjx.customer.platform.business.task.AgentTaskServiceImpl;
import com.jjx.customer.platform.business.task.AgentTaskState;
import com.jjx.customer.platform.config.prompt.PromptStore;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpsRunner} 的任务接线端到端：三场景里最关键的那条——<b>收尾后追问</b>。
 *
 * <p>这里守的是一个曾经真实存在的缺陷：旧实现把「一次诊断」等同于「一个会话」
 * （引擎会话 id = {@code "ops-" + conversationId}），诊断一出结论就把状态置 DONE，
 * 下一轮追问既恢复不了、也带不走已确认槽位——用户说"你这结论不对"时，
 * 系统从零重排，连接口和环境都忘了。</p>
 *
 * <p>本测试同时钉住三件事：① attempt 之间用不同的引擎会话 id（不覆盖）；
 * ② 出结论落 {@code CONCLUDED}（不是终态）；③ 追问轮把任务上的槽位带进新 attempt。</p>
 */
class OpsRunnerTaskTest {

    private static final String CONV = "conv-task-test";

    /** 内存任务服务：生产实现依赖 MyBatis/DB，这里只保留 OpsRunner 真正用到的语义。 */
    private static final class InMemoryTaskService extends AgentTaskServiceImpl {

        private final Map<String, AgentTaskState> byId = new LinkedHashMap<>();
        private final Map<String, String> activeByConversation = new HashMap<>();

        InMemoryTaskService() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        public Optional<AgentTaskState> findActive(String conversationId) {
            AgentTaskState t = byId.get(activeByConversation.get(conversationId));
            // 与 DB 查询同口径：只有「可沿用」状态才会被找到（RUNNING 期间找不到，用于并发保护）
            return t == null || !t.reusable() ? Optional.empty() : Optional.of(t);
        }

        @Override
        public AgentTaskState create(String conversationId, String agentId) {
            AgentTaskState t = new AgentTaskState(UUID.randomUUID().toString(), conversationId, agentId,
                    null, AgentTaskState.Status.OPEN, Map.of(), null, null, null, 0);
            store(t);
            return t;
        }

        @Override
        public boolean claim(String taskId) {
            store(mutate(byId.get(taskId), AgentTaskState.Status.RUNNING, null, null));
            return true;
        }

        @Override
        public int beginAttempt(String taskId) {
            AgentTaskState cur = byId.get(taskId);
            int next = cur.attemptCount() + 1;
            store(mutate(cur, AgentTaskState.Status.RUNNING, next, null));
            return next;
        }

        @Override
        public void release(String taskId) {
            store(mutate(byId.get(taskId), AgentTaskState.Status.SUSPENDED, null, null));
        }

        @Override
        public void markSuspended(AgentTaskState state) {
            // 与生产实现同口径：只更新 status/槽位/stage/档位/链 id，**不碰 attempt_count**。
            // 传入的 state 由 suspendedStateOf 用「本轮开始前读到的」task 构造，其 attemptCount
            // 是旧值；若照抄过来，会把 beginAttempt 刚递增的序号冲掉（本测试第一版就栽在这里）。
            AgentTaskState cur = byId.get(state.taskId());
            store(new AgentTaskState(state.taskId(), state.conversationId(), state.agentId(), state.stage(),
                    AgentTaskState.Status.SUSPENDED, state.slots(), state.summary(),
                    state.autonomyLevel(), state.chainTraceId(),
                    cur == null ? state.attemptCount() : cur.attemptCount()));
        }

        @Override
        public void markConcluded(String taskId, String conclusion) {
            store(mutate(byId.get(taskId), AgentTaskState.Status.CONCLUDED, null, conclusion));
        }

        @Override
        public AutonomyLevel autonomyOf(String conversationId) {
            return AutonomyLevel.L2;
        }

        // P1：QA 配额计数（内存版）
        private final Map<String, Integer> qaCount = new HashMap<>();

        @Override
        public int qaCountOf(String taskId) {
            return qaCount.getOrDefault(taskId, 0);
        }

        @Override
        public void incrementQaCount(String taskId) {
            qaCount.merge(taskId, 1, Integer::sum);
        }

        /** @return 当前登记的任务 id 集合（断言"没有新建任务"用） */
        Set<String> taskIds() {
            return byId.keySet();
        }

        AgentTaskState.Status statusOf(String taskId) {
            return byId.get(taskId).status();
        }

        private void store(AgentTaskState t) {
            byId.put(t.taskId(), t);
            activeByConversation.put(t.conversationId(), t.taskId());
        }

        private static AgentTaskState mutate(AgentTaskState t, AgentTaskState.Status status,
                                             Integer attemptCount, String summary) {
            return new AgentTaskState(t.taskId(), t.conversationId(), t.agentId(), t.stage(),
                    status, t.slots(), summary == null ? t.summary() : summary,
                    t.autonomyLevel(), t.chainTraceId(),
                    attemptCount == null ? t.attemptCount() : attemptCount);
        }
    }

    /** 内存主张服务：只保留 OpsRunner 用到的两个方法（落库/读取）。 */
    private static final class InMemoryFindingService extends AgentFindingServiceImpl {

        private final Map<String, java.util.List<AgentFinding>> byTask = new HashMap<>();

        InMemoryFindingService() {
            super(null, new ObjectMapper());
        }

        @Override
        public void replaceActiveWith(java.util.List<AgentFinding> findings) {
            if (findings == null || findings.isEmpty()) {
                return;
            }
            byTask.put(findings.getFirst().taskId(), java.util.List.copyOf(findings));
        }

        @Override
        public java.util.List<AgentFinding> activeOf(String taskId) {
            return byTask.getOrDefault(taskId, java.util.List.of());
        }

        int countOf(String taskId) {
            return byTask.getOrDefault(taskId, java.util.List.of()).size();
        }

        // P1.5：关联诊断背景（内存版，同 conversation 其他任务的主张）
        private final Map<String, java.util.List<AgentFinding>> related = new HashMap<>();

        void seedRelated(String conversationId, java.util.List<AgentFinding> findings) {
            related.put(conversationId, java.util.List.copyOf(findings));
        }

        @Override
        public java.util.List<AgentFinding> relatedOf(String conversationId, String excludeTaskId) {
            return related.getOrDefault(conversationId, java.util.List.of());
        }
    }

    /** 复用 {@link OpsGraphSuspendTest} 的内存引擎骨架（同一个 ops 包，装配口径一致）。 */
    private static OpsRunner runnerOf(Engine engine, InMemoryTaskService tasks, InMemoryFindingService findings) {
        // QA 模型缺席 = 任务内直答永远返回 empty，行为与 2026-09-20 之前完全一致
        return runnerOf(engine, tasks, findings, new TaskFollowUpQa(null, null, new ObjectMapper(), key -> null));
    }

    private static OpsRunner runnerOf(Engine engine, InMemoryTaskService tasks, InMemoryFindingService findings,
            TaskFollowUpQa followUpQa) {
        return runnerOf(engine, tasks, findings, followUpQa, new OpsProperties(null, null, null, null, null));
    }

    private static OpsRunner runnerOf(Engine engine, InMemoryTaskService tasks, InMemoryFindingService findings,
            TaskFollowUpQa followUpQa, OpsProperties opsProperties) {
        return new OpsRunner(engine, tasks, findings,
                new PromptFingerprintResolver(new PromptStore(), null), new ObjectMapper(), followUpQa,
                opsProperties);
    }

    @Test
    void 收尾后追问沿用同一任务_开新attempt_带上槽位且不覆盖旧会话() {
        // 脚本与 OpsGraphSuspendTest#缺槽挂起_补答恢复_直答收尾 同源：抽槽 → 自主补全 → 问齐
        // → 回复解释 → replan continue → 三阶段 answer 收尾
        OpsGraphSuspendTest.ScriptedAskModel askModel = new OpsGraphSuspendTest.ScriptedAskModel()
                .enqueue("{\"environment\":null,\"interface\":null,\"time\":null}", "[]",
                        "{\"slots\":{\"symptoms\":\"偶发\"},\"directive\":\"报错只在灰度机器上出现\"}",
                        "{\"action\":\"continue\"}", "{\"action\":\"continue\"}");
        ScriptedModelProvider thinkModel = new ScriptedModelProvider()
                .enqueueText("{\"answer\":\"定位：订单服务 NPE\"}")
                .enqueueText("{\"answer\":\"已生成修正报文\"}")
                .enqueueText("{\"answer\":\"结论：字段缺失导致\"}");
        Engine engine = OpsGraphSuspendTest.engineOf(askModel, thinkModel);
        InMemoryTaskService tasks = new InMemoryTaskService();
        InMemoryFindingService findings = new InMemoryFindingService();
        OpsRunner runner = runnerOf(engine, tasks, findings);

        // ---- ① 首轮：缺必填三槽 → 挂起 ----
        runner.run("接口报错了帮忙看看", Map.of(), CONV);
        assertEquals(1, tasks.taskIds().size(), "首轮应建出一个任务");
        String taskId = tasks.taskIds().iterator().next();
        String attempt1 = AgentTaskState.attemptIdOf(taskId, 1);
        assertTrue(engine.contexts().load(attempt1).isPresent(),
                "第 1 个 attempt 应落在派生出的引擎会话 id 上：" + attempt1);

        // ---- ② 补答 → 确认门 ----
        Map<String, String> answer = new LinkedHashMap<>();
        answer.put("user_clarify", "prod 环境 /api/invoice/reverse 最近1小时");
        answer.put("environment", "prod");
        answer.put("interface", "/api/invoice/reverse");
        answer.put("time", "最近1小时");
        runner.run("prod 环境 /api/invoice/reverse 最近1小时", answer, CONV);

        // ---- ③ 确认 → 走完三阶段 → 收尾 ----
        runner.run("#decision:confirm", Map.of("user_clarify", "#decision:confirm"), CONV);
        assertEquals(AgentTaskState.Status.CONCLUDED, tasks.statusOf(taskId),
                "出结论应落 CONCLUDED（可沿用），不是 CLOSED——'出结论'不等于'目标达成'");

        // ---- ④ 追问：用户不认可结论 ----
        runner.run("不对，报文里 invoiceCode 应该是真实的发票代码", Map.of(), CONV);

        assertEquals(1, tasks.taskIds().size(), "追问不该新建任务（那是另一个场景）");
        assertEquals(taskId, tasks.taskIds().iterator().next(), "追问应挂回同一个任务");

        String attempt2 = AgentTaskState.attemptIdOf(taskId, 2);
        assertTrue(engine.contexts().load(attempt2).isPresent(),
                "追问应开第 2 个 attempt：" + attempt2);
        assertTrue(engine.contexts().load(attempt1).isPresent(),
                "第 1 个 attempt 的引擎会话必须仍在——旧规则同 id 覆盖会把它连同过程记录一起抹掉");

        // 最关键的一条：追问轮把上一轮已确认的槽位带进了新 attempt。
        // 没有这一条，用户说"结论不对"时系统连接口和环境都不知道，只能从头重排。
        assertEquals("/api/invoice/reverse",
                engine.contexts().slots(attempt2).getString("interface", ""),
                "追问轮应带上任务上已确认的槽位");
    }

    /**
     * 任务内直答 + 重跑降摩擦（2026-09-20，plan/2026-09-20-task-qa-loop.md）：
     *
     * <p>CONCLUDED 后的解释类追问（「为什么…」）<b>不进 SOP</b>——不开新 attempt、不动主张；
     * 随后的异议重跑（「不对…」）<b>跳过确认门</b>直进阶段 1（否则会以 CLARIFY 挂在确认单上）。</p>
     */
    @Test
    void 解释类追问走任务内直答_异议重跑跳过确认门() {
        // attempt 1 同收尾后追问场景；attempt 2（重跑）补一份边角脚本
        OpsGraphSuspendTest.ScriptedAskModel askModel = new OpsGraphSuspendTest.ScriptedAskModel()
                .enqueue("{\"environment\":null,\"interface\":null,\"time\":null}", "[]",
                        "{\"slots\":{\"symptoms\":\"偶发\"},\"directive\":\"报错只在灰度机器上出现\"}",
                        "{\"action\":\"continue\"}", "{\"action\":\"continue\"}")
                // attempt 2：抽槽 → 自主补全(无推断) → replan×2（确认门被跳过，不再有解释器调用）
                .enqueue("{\"environment\":null,\"interface\":null,\"time\":null}", "[]",
                        "{\"action\":\"continue\"}", "{\"action\":\"continue\"}");
        ScriptedModelProvider thinkModel = new ScriptedModelProvider()
                .enqueueText("{\"answer\":\"定位：订单服务 NPE\"}")
                .enqueueText("{\"answer\":\"已生成修正报文\"}")
                .enqueueText("{\"answer\":\"结论：字段缺失导致\"}")
                // attempt 2 的三阶段
                .enqueueText("{\"answer\":\"定位：时间窗错了\"}")
                .enqueueText("{\"answer\":\"修正报文已更新\"}")
                .enqueueText("{\"answer\":\"结论：时间窗修正后通过\"}");
        Engine engine = OpsGraphSuspendTest.engineOf(askModel, thinkModel);
        InMemoryTaskService tasks = new InMemoryTaskService();
        InMemoryFindingService findings = new InMemoryFindingService();
        // QA 用真实 classpath 资产（顺带验证 prompt 已随包发布）；模型按序两响：
        // 分类（judge answer）→ 直答解读文本（语义判断 2026-09-20 第三轮起全交模型）
        java.util.concurrent.atomic.AtomicInteger qaCalls = new java.util.concurrent.atomic.AtomicInteger();
        TaskFollowUpQa qa = new TaskFollowUpQa(
                (system, user) -> qaCalls.getAndIncrement() == 0
                        ? "{\"label\":\"answer\"}"
                        : "解读：因为日志显示 invoiceNumber 缺失，依据见结论证据链。",
                null, new ObjectMapper(), new PromptStore()::raw);
        OpsRunner runner = runnerOf(engine, tasks, findings, qa);

        // ---- ①~③ 首轮到收尾（同既有场景脚本）----
        runner.run("接口报错了帮忙看看", Map.of(), CONV);
        Map<String, String> answer = new LinkedHashMap<>();
        answer.put("user_clarify", "prod 环境 /api/invoice/reverse 最近1小时");
        answer.put("environment", "prod");
        answer.put("interface", "/api/invoice/reverse");
        answer.put("time", "最近1小时");
        runner.run("prod 环境 /api/invoice/reverse 最近1小时", answer, CONV);
        runner.run("#decision:confirm", Map.of("user_clarify", "#decision:confirm"), CONV);
        String taskId = tasks.taskIds().iterator().next();
        assertEquals(AgentTaskState.Status.CONCLUDED, tasks.statusOf(taskId));
        int findingsBefore = findings.countOf(taskId);

        // ---- ④ 解释类追问：任务内直答，不开新 attempt、不改主张 ----
        OpsRunner.OpsAnswer qaAnswer = runner.run("为什么结论说缺 invoiceNumber？", Map.of(), CONV);
        assertEquals(OutcomeKind.DIRECT, qaAnswer.kind(), "解读应走 DIRECT 直答交付");
        assertTrue(qaAnswer.text().contains("解读"), "文本应来自 QA 模型而非重新诊断");
        assertTrue(qaAnswer.trace() != null && qaAnswer.trace().getLlmCallCount() == 2,
                "QA 轮应带轨迹（分类 + 直答两步）——追问轮此前 trace=null 前端无轨迹");
        assertEquals(1, tasks.taskIds().size(), "解读轮不新建任务");
        assertFalse(engine.contexts().load(AgentTaskState.attemptIdOf(taskId, 2)).isPresent(),
                "任务内直答不应创建引擎会话（attempt 不变）");
        assertEquals(findingsBefore, findings.countOf(taskId), "解读不产生新主张");

        // ---- ⑤ 异议重跑：跳过确认门直进阶段，跑完直接再出结论 ----
        // P1.5：先撒同会话早前任务的主张，重跑轮应携带「关联诊断背景」
        findings.seedRelated(CONV, List.of(
                AgentFinding.active("r1", "task-early", CONV, AgentFinding.Kind.ROOT_CAUSE,
                        "order-service 连接池耗尽导致下单超时", null, 1)));
        OpsRunner.OpsAnswer rerun = runner.run("不对，其实是时间窗选错了", Map.of(), CONV);
        assertEquals(OutcomeKind.DIRECT, rerun.kind(),
                "重跑轮应跳过确认门跑完全程（若停在确认单会以 CLARIFY 挂起）");
        assertTrue(engine.contexts().load(AgentTaskState.attemptIdOf(taskId, 2)).isPresent(),
                "异议重跑应开出第 2 个 attempt");
        assertEquals(AgentTaskState.Status.CONCLUDED, tasks.statusOf(taskId));
        assertTrue(engine.contexts().slots(AgentTaskState.attemptIdOf(taskId, 2))
                        .getString(OpsRunner.RELATED_FINDINGS_SLOT, "").contains("早前任务·根因"),
                "重跑轮应携带关联诊断背景（P1.5：同会话早前任务的结论概要）");
    }

    /**
     * 真机 bug 回归（2026-09-20）：用户改说另一个接口后，任务槽位里仍是旧接口——
     * 抽槽语义是「已确认值优先、只填空缺」，旧值不清理就永远占位，新值抽不进来，
     * 模型同时收到矛盾的 {@code {{slots.interface}}} 与 {@code {{slots.user_directive}}}。
     */
    @Test
    void 换目标_旧目标痕迹被清除_条件槽保留() {
        OpsGraphSuspendTest.ScriptedAskModel askModel = new OpsGraphSuspendTest.ScriptedAskModel()
                .enqueue("{\"environment\":null,\"interface\":null,\"time\":null}", "[]",
                        "{\"slots\":{\"symptoms\":\"偶发\"},\"directive\":\"报错只在灰度机器上出现\"}",
                        "{\"action\":\"continue\"}", "{\"action\":\"continue\"}");
        // attempt 2 不给脚本：抽槽返回默认值（解析不出槽位）→ 被清空的 interface 保持空 → 走问齐挂起
        ScriptedModelProvider thinkModel = new ScriptedModelProvider()
                .enqueueText("{\"answer\":\"定位：订单服务 NPE\"}")
                .enqueueText("{\"answer\":\"已生成修正报文\"}")
                .enqueueText("{\"answer\":\"结论：字段缺失导致\"}");
        Engine engine = OpsGraphSuspendTest.engineOf(askModel, thinkModel);
        InMemoryTaskService tasks = new InMemoryTaskService();
        InMemoryFindingService findings = new InMemoryFindingService();
        OpsRunner runner = runnerOf(engine, tasks, findings);

        // ---- 首轮：补齐环境/接口/时间 + traceId/报文等目标痕迹 ----
        runner.run("接口报错了帮忙看看", Map.of(), CONV);
        Map<String, String> answer = new LinkedHashMap<>();
        answer.put("user_clarify", "prod 环境 /api/invoice/reverse 最近1小时");
        answer.put("environment", "prod");
        answer.put("interface", "/api/invoice/reverse");
        answer.put("time", "最近1小时");
        answer.put("trace_id", "126ed9a47866214d40c8fd996edb8c2d");
        answer.put("payload", "{\"invoiceCode\":\"\",\"invoiceNumber\":\"00412345\"}");
        runner.run("prod 环境 /api/invoice/reverse 最近1小时", answer, CONV);
        runner.run("#decision:confirm", Map.of("user_clarify", "#decision:confirm"), CONV);
        String taskId = tasks.taskIds().iterator().next();

        // ---- 换目标：消息里出现与任务已确立不同的接口路径 ----
        runner.run("另一个接口 /api/order/create 也报错了，帮我看看", Map.of(), CONV);

        String attempt2 = AgentTaskState.attemptIdOf(taskId, 2);
        assertEquals("/api/order/create", engine.contexts().slots(attempt2).getString("interface", ""),
                "旧接口被清后新接口才能抽进来——不清则抽槽的「已确认值优先」会让旧值一直占位");
        assertEquals("", engine.contexts().slots(attempt2).getString("trace_id", ""),
                "旧 traceId 属于已切换的目标，必须清（本轮没给新的，故为空）");
        assertEquals("", engine.contexts().slots(attempt2).getString("payload", ""),
                "旧故障的报文是该目标的观测，必须清");
        assertEquals("prod", engine.contexts().slots(attempt2).getString("environment", ""),
                "环境是「在什么条件下查」，与换目标无关，保留");
        assertFalse(engine.contexts().slots(attempt2).getString("time", "").isBlank(),
                "时间窗同理保留（抽槽会把它规范化为 ISO 窗口，故只断言非空）");
    }

    /** 目标切换判据（确定性，不耗模型）：只在出现**不同**的接口路径 / traceId 时成立。 */
    @Test
    void 目标切换判据_同接口与补槽不触发() {
        Map<String, String> slots = Map.of(
                "interface", "/api/invoice/reverse",
                "trace_id", "126ed9a47866214d40c8fd996edb8c2d",
                "environment", "prod");
        assertFalse(OpsSlotCatalog.targetShifted(slots, "那这个报错的根因是什么？"),
                "同目标追问不触发");
        assertFalse(OpsSlotCatalog.targetShifted(slots, "environment 是 test 才对"),
                "补槽/纠错不触发");
        assertFalse(OpsSlotCatalog.targetShifted(slots, "还是 /api/invoice/reverse，再查一遍"),
                "同一接口再次排查不触发");
        assertFalse(OpsSlotCatalog.targetShifted(slots, ""), "空消息不触发");
        assertTrue(OpsSlotCatalog.targetShifted(slots, "另一个接口 /api/order/create 也报错了"),
                "出现不同的接口路径 → 换目标");
        assertTrue(OpsSlotCatalog.targetShifted(slots, "换成 traceId 6a487cfbf8aa3604bbb204f2c3958bff 看看"),
                "出现不同的 traceId → 换目标");
    }

    /** P1.5：关联背景渲染——不带 [#n] 编号（防否定标错对象）、单条 200 截断、空列表空串。 */
    @Test
    void 关联背景渲染_不带编号_单条截断() {
        String longClaim = "连接池耗尽导致下单超时".repeat(30);   // 270 字符 > 200
        String rendered = OpsRunner.renderRelated(List.of(
                AgentFinding.active("r1", "task-a", CONV, AgentFinding.Kind.ROOT_CAUSE, longClaim, null, 1),
                AgentFinding.active("r2", "task-a", CONV, AgentFinding.Kind.FIX, "报文补齐 invoiceCode 后通过", null, 1)));
        assertTrue(rendered.contains("[早前任务·根因]"));
        assertTrue(rendered.contains("[早前任务·修正]"));
        assertFalse(rendered.contains("[#"), "跨任务主张不得带 [#n] 编号（编号是本任务否定指认的锚点）");
        assertTrue(rendered.contains("…"), "超长主张应截断");
        assertEquals("", OpsRunner.renderRelated(List.of()), "无关联 → 空串（槽不写，compose 段渲染默认值）");
    }

    /**
     * P1：QA 配额——超限轮回固定文案，零 LLM 消耗（检查在分类之前），不降级重跑、不开 attempt。
     * 降级到重跑是刻意不做的：重跑更贵，降级正中滥用。
     */
    @Test
    void qa直答超配额_回固定文案_不再消耗模型() {
        OpsGraphSuspendTest.ScriptedAskModel askModel = new OpsGraphSuspendTest.ScriptedAskModel()
                .enqueue("{\"environment\":null,\"interface\":null,\"time\":null}", "[]",
                        "{\"slots\":{\"symptoms\":\"偶发\"},\"directive\":\"报错只在灰度机器上出现\"}",
                        "{\"action\":\"continue\"}", "{\"action\":\"continue\"}");
        ScriptedModelProvider thinkModel = new ScriptedModelProvider()
                .enqueueText("{\"answer\":\"定位：订单服务 NPE\"}")
                .enqueueText("{\"answer\":\"已生成修正报文\"}")
                .enqueueText("{\"answer\":\"结论：字段缺失导致\"}");
        Engine engine = OpsGraphSuspendTest.engineOf(askModel, thinkModel);
        InMemoryTaskService tasks = new InMemoryTaskService();
        InMemoryFindingService findings = new InMemoryFindingService();
        java.util.concurrent.atomic.AtomicInteger qaCalls = new java.util.concurrent.atomic.AtomicInteger();
        TaskFollowUpQa qa = new TaskFollowUpQa((s, u) -> {
            int call = qaCalls.incrementAndGet();
            return call == 1 ? "{\"label\":\"answer\"}" : "解读：因为日志显示 invoiceNumber 缺失。";
        }, null, new ObjectMapper(), new PromptStore()::raw);
        // 配额 = 1：第一次 QA 消耗掉，第二次超限
        OpsRunner runner = runnerOf(engine, tasks, findings, qa, new OpsProperties(null, null, null, null, 1));

        runner.run("接口报错了帮忙看看", Map.of(), CONV);
        Map<String, String> answer = new LinkedHashMap<>();
        answer.put("user_clarify", "prod 环境 /api/invoice/reverse 最近1小时");
        answer.put("environment", "prod");
        answer.put("interface", "/api/invoice/reverse");
        answer.put("time", "最近1小时");
        runner.run("prod 环境 /api/invoice/reverse 最近1小时", answer, CONV);
        runner.run("#decision:confirm", Map.of("user_clarify", "#decision:confirm"), CONV);
        String taskId = tasks.taskIds().iterator().next();

        // 第 1 次 QA：配额内，正常直答（分类 + 直答两次模型调用）
        OpsRunner.OpsAnswer first = runner.run("为什么结论说缺 invoiceNumber？", Map.of(), CONV);
        assertTrue(first.text().contains("解读"), "配额内应正常直答");
        assertEquals(2, qaCalls.get());
        assertEquals(1, tasks.qaCountOf(taskId), "成功直答应计数");

        // 第 2 次 QA：超配额 → 固定文案，零模型调用，不开新 attempt
        OpsRunner.OpsAnswer second = runner.run("错误码是什么意思？", Map.of(), CONV);
        assertTrue(second.text().contains("上限"), "超配额应回固定文案，实际：" + second.text());
        assertEquals(OutcomeKind.DIRECT, second.kind());
        assertEquals(2, qaCalls.get(), "超限轮不得再消耗 QA 模型（检查在分类之前）");
        assertFalse(engine.contexts().load(AgentTaskState.attemptIdOf(taskId, 2)).isPresent(),
                "限额文案不是重跑：不开新 attempt");
    }
}
