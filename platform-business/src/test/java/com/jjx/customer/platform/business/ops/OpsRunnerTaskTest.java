package com.jjx.customer.platform.business.ops;

import com.agentframework.engine.core.Engine;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.engine.PromptFingerprintResolver;
import com.jjx.customer.platform.business.engine.outcome.OutcomeKind;
import com.jjx.customer.platform.business.task.AgentFinding;
import com.jjx.customer.platform.business.task.AgentFindingServiceImpl;
import com.jjx.customer.platform.business.task.AgentTaskServiceImpl;
import com.jjx.customer.platform.business.task.AgentTaskState;
import com.jjx.customer.platform.config.prompt.PromptStore;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    }

    /** 复用 {@link OpsGraphSuspendTest} 的内存引擎骨架（同一个 ops 包，装配口径一致）。 */
    private static OpsRunner runnerOf(Engine engine, InMemoryTaskService tasks, InMemoryFindingService findings) {
        // QA 模型缺席 = 任务内直答永远返回 empty，行为与 2026-09-20 之前完全一致
        return runnerOf(engine, tasks, findings, new TaskFollowUpQa(null, null, new ObjectMapper(), key -> null));
    }

    private static OpsRunner runnerOf(Engine engine, InMemoryTaskService tasks, InMemoryFindingService findings,
            TaskFollowUpQa followUpQa) {
        return new OpsRunner(engine, tasks, findings,
                new PromptFingerprintResolver(new PromptStore(), null), new ObjectMapper(), followUpQa);
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
        OpsRunner.OpsAnswer rerun = runner.run("不对，其实是时间窗选错了", Map.of(), CONV);
        assertEquals(OutcomeKind.DIRECT, rerun.kind(),
                "重跑轮应跳过确认门跑完全程（若停在确认单会以 CLARIFY 挂起）");
        assertTrue(engine.contexts().load(AgentTaskState.attemptIdOf(taskId, 2)).isPresent(),
                "异议重跑应开出第 2 个 attempt");
        assertEquals(AgentTaskState.Status.CONCLUDED, tasks.statusOf(taskId));
    }
}
