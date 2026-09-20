package com.jjx.customer.platform.business.ops;

import com.jjx.customer.platform.business.trace.EngineTraceMapper;

import com.agentframework.definition.workflow.HumanRequest;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.StartOptions;
import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.engine.PromptFingerprintResolver;
import com.jjx.customer.platform.business.engine.AgentFingerprint;
import com.jjx.customer.platform.business.engine.outcome.OutcomeKind;
import com.jjx.customer.platform.business.engine.outcome.RunOutcomeMapper;
import com.jjx.customer.platform.business.ops.AutonomyLevel;
import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.node.AskMissingExecutor;
import com.jjx.customer.platform.business.task.AgentFinding;
import com.jjx.customer.platform.business.task.AgentFindingServiceImpl;
import com.jjx.customer.platform.business.task.AgentTaskServiceImpl;
import com.jjx.customer.platform.business.task.AgentTaskState;
import com.jjx.customer.platform.business.task.FindingsText;
import com.jjx.customer.platform.business.task.FindingExtractor;
import com.jjx.customer.platform.business.trace.model.TraceView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 运维诊断主线执行器（对话链路与 REST 端点共用，签名与返回契约完全不变）。
 *
 * <p>内部执行权在新内核引擎（图 = {@code ops_diagnose_v2}，装配见
 * {@code AgentEngineConfiguration}）：</p>
 * <ul>
 *   <li>会话恢复：sa_agent_session 有已确认槽位且引擎会话（id = {@code ops-<conversationId>}
 *       确定性派生）可 load → {@code engine.resume}（答复写 user_clarify 槽，问齐与环内追问
 *       共用恢复通道）；引擎侧无记录（如旧版本挂起的存量会话）→ 带已确认槽位重开新会话；</li>
 *   <li>出口映射：{@link RunOutcomeMapper}（挂起→CLARIFY / 升级→ESCALATE / 收尾→DIRECT）；</li>
 *   <li>澄清落库：挂起写 AWAITING_USER（已确认槽位 + 缺失清单）、终态写 DONE——
 *       替代旧内核的 {@code SessionRecordingListener}；REST 单轮（sessionId=null）用
 *       ephemeral 会话，不落库。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsRunner {

    /** 上一轮主张的槽位名（由 IntakeStageModule 声明，阶段 prompt 经 {{slots.prior_findings}} 消费）。 */
    public static final String PRIOR_FINDINGS_SLOT = "prior_findings";

    /** 「基本信息已确认过」槽位名（由 IntakeStageModule 声明）：重跑轮预填 1，跳过重复确认门。 */
    public static final String INTAKE_CONFIRMED_SLOT = "intake_confirmed";

    /** 关联诊断背景的槽位名（由 IntakeStageModule 声明，compose 渲染「关联诊断背景」段）。 */
    public static final String RELATED_FINDINGS_SLOT = "related_findings";

    /** 单条主张截断长度（口径收敛在 {@link FindingsText#CLAIM_MAX_CHARS}）。 */
    private static final int FINDING_CLAIM_MAX = FindingsText.CLAIM_MAX_CHARS;

    /** 关联背景单条截断（比主张的 400 更紧——背景只要能认出「是哪件事」）。 */
    private static final int RELATED_CLAIM_MAX = 200;

    /** 注入的主张条数上限——**有界是硬要求**：组装成本必须与历史长度解耦（口径收敛在 {@link FindingsText#MAX_ITEMS}）。 */
    private static final int FINDING_MAX = FindingsText.MAX_ITEMS;

    /**
     * 把主张渲染成注入文本。
     *
     * <p><b>每条带编号</b>（{@code [#3]}）：模型据此在结论的「主张变更」段里精确指认被否定的那条，
     * 抽取器按编号落位——不靠文本相似度匹配，也不额外花一次模型调用做归因。</p>
     *
     * <p>对应设计里「有界而非全量」那条不变式：宁可少带，也不能让上下文随对话轮数无上限增长。
     * 超出上限时显式写明省略了多少条，而不是静默截断。编号与省略都如实反映，
     * 否则模型会引用到不存在的编号。</p>
     *
     * @param findings 生效主张（顺序即编号，需与后续 {@code activeOf} 一致）
     * @return 注入文本；无主张 = 空串（不占槽位）
     */
    static String renderFindings(List<AgentFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "";
        }
        // 行格式与常量收敛在 FindingsText（TaskFollowUpQa 同源调用——编号锚点契约靠同一函数锁步）
        return "上一轮已确立的主张（编号供你在「主张变更」段中精确指认；"
                + "用户可能正对其中某条提出异议，请据此回应或修正）：\n"
                + FindingsText.renderNumbered(findings, FINDING_MAX, FINDING_CLAIM_MAX);
    }

    /**
     * 渲染关联诊断背景（同会话早前任务的结论概要，P1.5）。
     *
     * <p><b>不带 {@code [#n]} 编号</b>：编号是「本任务主张可被主张变更精确指认」的锚点，
     * 跨任务主张混入会把否定标错对象——来源改用「早前任务·类型」文字标记。</p>
     *
     * @param findings 关联主张（任务倒序、每任务 ≤2 条，由 relatedOf 保证有界）
     * @return 注入文本；无关联 = 空串
     */
    static String renderRelated(List<AgentFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentFinding f : findings) {
            String claim = f.claim() == null ? "" : f.claim().replaceAll("\\s+", " ").trim();
            if (claim.length() > RELATED_CLAIM_MAX) {
                claim = claim.substring(0, RELATED_CLAIM_MAX) + "…";
            }
            String label = f.kind() == AgentFinding.Kind.ROOT_CAUSE ? "根因" : "修正";
            sb.append("- [早前任务·").append(label).append("] ").append(claim).append('\n');
        }
        return sb.toString();
    }

    /** 一次诊断的执行产物（交付由调用方决定：SSE / REST 投影）。 */
    public record OpsAnswer(OutcomeKind kind, String text, TraceView trace,
                            HumanRequest humanRequest) {
        /** 兼容旧构造（无人在环请求）。 */
        public OpsAnswer(OutcomeKind kind, String text, TraceView trace) {
            this(kind, text, trace, null);
        }
    }

    private final Engine agentEngine;
    private final AgentTaskServiceImpl taskService;
    private final AgentFindingServiceImpl findingService;
    private final PromptFingerprintResolver fingerprintResolver;
    private final ObjectMapper objectMapper;

    /** 任务内直答（Task QA）：CONCLUDED 任务上「对结论的提问」的轻路径，不进 SOP。 */
    private final TaskFollowUpQa followUpQa;

    /** ops 配置（P1：qaMaxPerTask 配额）。 */
    private final OpsProperties opsProperties;

    /**
     * @param sessionSlots 会话已确认槽位（可空：单轮调用 / 首轮）
     * @param sessionId    会话标识（非空 = 出口落澄清会话；REST 单轮为 null）
     */
    public OpsAnswer run(String question, Map<String, String> sessionSlots, String sessionId) {
        AgentTaskState task = (sessionId == null || sessionId.isBlank()) ? null : ensureTask(sessionId);
        // 任务内直答（2026-09-20）：CONCLUDED 任务上「对结论的提问」不进 SOP——读投影单次直答。
        // P1 配额检查在分类调用**之前**：超限轮零 LLM 消耗，回固定文案而非降级重跑
        //（重跑更贵，降级正中滥用）；判 rerun / 应答失败照旧落回下方重跑路径（不劣化）。
        if (task != null && task.status() == AgentTaskState.Status.CONCLUDED) {
            int qaBudget = opsProperties.qaMaxPerTaskEffective();
            if (taskService.qaCountOf(task.taskId()) >= qaBudget) {
                return new OpsAnswer(OutcomeKind.DIRECT,
                        "本任务追问解读已达上限（" + qaBudget + " 次）。如结论仍有疑问，"
                                + "请直接指出哪条不对（系统会重新排查），或开启新对话。",
                        null);
            }
            Optional<OpsRunner.OpsAnswer> direct = followUpQa.tryAnswer(task, question,
                    () -> findingService.activeOf(task.taskId()));
            if (direct.isPresent()) {
                taskService.incrementQaCount(task.taskId());
                return direct.get();
            }
        }
        RunResult result = execute(question, sessionSlots, task);
        OutcomeKind kind = RunOutcomeMapper.kindOf(result);
        if (task != null) {
            if (kind == OutcomeKind.CANCELLED) {
                // 用户中止：本轮**没有结论**。任务放回可恢复态即可，绝不按收尾落库——
                // 那会把一次中止记成 CONCLUDED，还会抽出一批基于空文本的"主张"。
                // 引擎会话已被 cancel 置为 CANCELLED，下一次消息会走新 attempt。
                taskService.release(task.taskId());
            } else if (kind == OutcomeKind.CLARIFY) {
                taskService.markSuspended(suspendedStateOf(task, result));
            } else {
                // 出结论 → CONCLUDED 而非 CLOSED：目标是否达成由人判定，
                // 留成可沿用态，下一轮追问才能挂回同一 Task 并带上槽位
                taskService.markConcluded(task.taskId(), textOf(result));
                // 出结论即抽主张。四段式交付格式让这一步是**纯解析**（0 模型调用）——
                // 抽出的主张供下一轮追问作为上下文，是"用户说不对"能接得上的关键。
                // attempt 序号现读权威值：task 是本轮开始前的快照，beginAttempt 已把它递增过。
                int attemptNo = taskService.findById(task.taskId())
                        .map(AgentTaskState::attemptCount).orElse(task.attemptCount());
                // 顺序不可颠倒：先把被用户明确否定的主张标 RETRACTED，再让新结论整体取代。
                // 反过来的话，replaceActiveWith 会把它一并标成 SUPERSEDED——
                // "被更新"和"被推翻"是两种不同的审计事实，混了事后就分不出来。
                applyDenials(task.taskId(), textOf(result));
                findingService.replaceActiveWith(FindingExtractor.extract(
                        task.taskId(), task.conversationId(), attemptNo, textOf(result)));
            }
        }
        AgentCatalog.Entry ops = AgentCatalog.OPS;
        TraceView trace = EngineTraceMapper.toBusinessTrace(result, new AgentFingerprint(
                ops.id(), ops.workflowId(), fingerprintResolver.promptHash(ops.id())));
        return new OpsAnswer(kind, textOf(result), trace, humanRequestOf(result));
    }

    /**
     * 应用结论「主张变更」段里的否定：把被点名的旧主张标为 {@code RETRACTED}。
     *
     * <p>编号来自注入时渲染的 {@code [#n]}，所以这里必须用**与注入时同一查询**（{@code activeOf}）
     * 取列表——顺序一变编号就错位，会把否定标到另一条主张上。越界编号一律丢弃并记日志，
     * 绝不猜。</p>
     *
     * @param taskId     任务标识
     * @param conclusion 本轮结论
     */
    private void applyDenials(String taskId, String conclusion) {
        java.util.Set<Integer> denied = FindingExtractor.deniedIndices(conclusion);
        if (denied.isEmpty()) {
            return;
        }
        List<AgentFinding> active = findingService.activeOf(taskId);
        for (Integer idx : denied) {
            if (idx == null || idx < 1 || idx > active.size()) {
                log.warn("[ops] 「主张变更」引用越界编号 #{}（当前 {} 条生效主张），已丢弃", idx, active.size());
                continue;
            }
            AgentFinding target = active.get(idx - 1);
            String preview = target.claim() == null ? "" : target.claim().replaceAll("\\s+", " ").trim();
            if (preview.length() > 60) {
                preview = preview.substring(0, 60) + "…";
            }
            if (findingService.markRetracted(target.findingId())) {
                log.info("[ops] 主张 #{} 被用户否定 → RETRACTED: {}", idx, preview);
            }
        }
    }

    /**
     * 取消该对话正在进行的诊断（用户点「停止生成」时由交付层调用）。
     *
     * <p><b>为什么需要它</b>：交付层的取消只 dispose 了 LLM 流 + 关闭 SSE，
     * <b>引擎完全不知情</b>——它会继续把剩下的节点跑完、把结果落库。实测取消路径
     * （{@code SseDeliveryPort} 注册的 Runnable）确认只做三件事：落部分输出、关 emitter、
     * 记一行日志，没有任何信号给引擎。</p>
     *
     * <p>这里把引擎会话置为 {@code CANCELLED}，运行循环在<b>节点边界</b>检查到后立即收尾
     * （见 {@code DefaultWorkflowRuntime}——正在执行的节点打断不了，但不会再开下一个）。
     * 随后把任务放回可恢复态：不放的话它会停在 RUNNING，下一次消息被"已有请求在执行中"
     * 挡住，直到 TTL 才自愈。</p>
     *
     * @param conversationId 对话标识
     * @return 是否确实取消到了一个进行中的诊断
     */
    public boolean cancelDiagnosis(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        AgentTaskState task = taskService.findActive(conversationId).orElse(null);
        if (task == null || task.status() != AgentTaskState.Status.RUNNING) {
            return false;
        }
        Optional<Session> session = agentEngine.contexts().load(task.currentAttemptId());
        session.ifPresent(agentEngine::cancel);
        taskService.release(task.taskId());
        log.info("[ops] 用户中止诊断: conversationId={}, attempt={}, 引擎会话命中={}",
                conversationId, task.attemptCount(), session.isPresent());
        return session.isPresent();
    }

    /**
     * 取该对话可沿用的任务，没有就新建。
     *
     * <p>「可沿用」含 {@code CONCLUDED}——<b>这是"诊断出结论后追问，上下文全丢"的修复点</b>：
     * 旧实现把收尾当成会话结束（置 DONE 且只认 AWAITING_USER），追问时既恢复不了也带不走槽位。</p>
     *
     * <p>创建失败直接抛：任务没有归属就无处回写结论，继续跑只会产出一份无法落地的诊断。</p>
     *
     * @param conversationId 对话标识
     * @return 任务状态
     */
    private AgentTaskState ensureTask(String conversationId) {
        AgentTaskState task = taskService.findActive(conversationId)
                .orElseGet(() -> taskService.create(conversationId, AgentCatalog.OPS.id()));
        if (task == null) {
            throw new IllegalStateException("诊断任务创建失败，请稍后重试");
        }
        return task;
    }

    /**
     * 挂起时的结构化决议请求（DECIDE 决策移交 / 未来的 APPROVE、CONFIRM）。
     *
     * <p>槽位里没有 {@code pending_human_request}（普通问齐挂起 / 非挂起出口）返回 null，
     * 交付侧按现状纯文本渲染——CLARIFY 问齐卡片不依赖本字段。</p>
     */
    private HumanRequest humanRequestOf(RunResult result) {
        if (result.slots() == null) {
            return null;
        }
        Object pending = result.slots().get(HumanRequest.PENDING_SLOT);
        if (!(pending instanceof String json) || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, HumanRequest.class);
        } catch (Exception e) {
            log.warn("[ops] 决议请求反序列化失败（按纯文本交付）: {}", e.getMessage());
            return null;
        }
    }

    private RunResult execute(String question, Map<String, String> sessionSlots, AgentTaskState task) {
        if (task == null) {
            // REST 单轮：ephemeral（不落引擎会话表，也无 Task 可归属）。
            // 必须显式 startSession + asEphemeral()——agentEngine.run(agentId, input) 内部走的是
            // StartOptions.defaults()（ephemeral=false），于是每次 REST 调用都会生成一个随机 UUID
            // 会话，往 ops_engine_session / ops_engine_slots 各写一行且永不删除。
            Map<String, Object> prefill = new LinkedHashMap<>();
            if (sessionSlots != null) {
                prefill.putAll(sessionSlots);
            }
            Session singleTurn = agentEngine.startSession(
                    agentEngine.loadAgent(AgentCatalog.OPS.id(), "latest"),
                    StartOptions.defaults().asEphemeral()
                            // 会话 traceId 对齐调用链（REST 单轮 = 当前请求的 OTel trace）：
                            // 不传时引擎自生成随机 UUID，会话记录/引擎事件与业务链各说各话
                            .withTraceId(currentTraceId()));
            return agentEngine.run(singleTurn, new Input(question, Map.of(), prefill));
        }

        // 槽位来源：任务上已确认的 → 本轮传入的（后者覆盖）。
        // 关键差别：**已收尾（CONCLUDED）的追问也带**。旧实现只在挂起恢复时才带槽位，
        // 于是"出了结论后说不对"等于从头开始——接口/环境/traceId 全没了。
        Map<String, Object> prefill = new LinkedHashMap<>();
        if (task.slots() != null) {
            prefill.putAll(task.slots());
        }
        if (sessionSlots != null) {
            prefill.putAll(sessionSlots);
        }
        // 目标切换（用户改说另一个接口 / 另一个 traceId）：清掉旧目标的痕迹。
        // 不清的话抽槽的「已确认值优先、只填空缺」会让旧 interface 永远占位，
        // 新接口抽不进来——模型同时收到矛盾的 {{slots.interface}} 与 {{slots.user_directive}}
        // （真机 2026-09-20：用户说 /api/order/create 后槽位仍是 /api/invoice/reverse）。
        // 环境/时间/现象保留：那是"在什么条件下查"，与换目标无关。
        if (OpsSlotCatalog.targetShifted(task.slots(), question)) {
            OpsSlotCatalog.STALE_ON_TARGET_SHIFT.forEach(prefill::remove);
            log.info("[ops] 目标已切换（换接口/换 traceId），清理旧目标槽位：{}",
                    OpsSlotCatalog.STALE_ON_TARGET_SHIFT);
        }
        withAutonomy(prefill, task);

        // 上一轮已确立的主张带进本轮上下文。没有它，"你这结论不对"会让模型不知道
        // 上一轮到底断言了什么，只能从零重排——这正是本次改造要消除的体验。
        String priorFindings = renderFindings(findingService.activeOf(task.taskId()));
        if (!priorFindings.isBlank()) {
            prefill.put(PRIOR_FINDINGS_SLOT, priorFindings);
        }

        // P1.5 关联诊断背景（Topic 投影的最小形态）：同会话早前任务的结论概要。
        // 让"刚才那个问题是连接池耗尽，这个是不是也一样"里的「刚才那个」可见；
        // 防锚定（跨任务根因是强锚）由 compose 段头降权标注 + verify 证据链铁律双保险兜住
        String related = renderRelated(findingService.relatedOf(task.conversationId(), task.taskId()));
        if (!related.isBlank()) {
            prefill.put(RELATED_FINDINGS_SLOT, related);
        }

        // ① 挂起中 → 恢复同一个 attempt。先抢占：并发双请求只有一个能继续，
        //    失败的那个不会被静默当成新诊断跑一份。
        if (task.status() == AgentTaskState.Status.SUSPENDED) {
            Optional<Session> restored = agentEngine.contexts().load(task.currentAttemptId());
            if (restored.isPresent() && restored.get().state()
                    == com.agentframework.runtime.session.SessionState.SUSPENDED
                    && taskService.claim(task.taskId())) {
                prefill.put(AskMissingExecutor.USER_CLARIFY_SLOT, question == null ? "" : question);
                try {
                    return agentEngine.resume(restored.get(),
                            new Input(question == null ? "" : question, Map.of(), prefill));
                } catch (RuntimeException e) {
                    taskService.release(task.taskId());
                    throw e;
                }
            }
        }

        // ② 新 attempt：**一个 attempt 一个引擎会话 id**。
        //    旧规则 "ops-" + conversationId 让同 id 反复 startSession 覆盖，
        //    每一次收尾都在销毁上一轮的过程记录（scratchpad / 阶段产出 / 预算计数）。
        int attemptNo = taskService.beginAttempt(task.taskId());
        if (attemptNo < 0) {
            throw new IllegalStateException("该诊断任务已有请求在执行中，请稍候再试");
        }
        // 重跑轮（第 2+ 个 attempt）跳过基本信息确认门：确认门摊开的是「被推断的前提」，
        // 同一 Task 沿用槽位即前提没变，不该再问；追问轮改的槽值来自用户自己的话或 #override:，
        // 天然经过用户。挂起恢复路径不预填——恢复继续原 attempt 的流程。
        if (attemptNo >= 2) {
            prefill.put(INTAKE_CONFIRMED_SLOT, 1);
            // 追问语义进软指令通道（各阶段 think prompt 的「用户补充说明」段，compose 渲染）：
            // 没有它，investigate 只见槽位与主张、看不到用户这句话想要什么——真机踩坑
            // （「从接口文档中看下是否符合规格」被当成无目标重跑，日志查不出任何东西，
            // 挂在 adjust 重试用尽的决策卡上）。句子原样透传，模型自行判断怎么采纳（软消费）。
            if (question != null && !question.isBlank()) {
                prefill.put(com.jjx.customer.platform.business.workflow.common.ActExecutor.USER_DIRECTIVE_SLOT,
                        question.trim());
            }
        }
        Session session = agentEngine.startSession(
                agentEngine.loadAgent(AgentCatalog.OPS.id(), "latest"),
                StartOptions.defaults()
                        .withSessionId(AgentTaskState.attemptIdOf(task.taskId(), attemptNo))
                        // 一次诊断从追问到结论是一条链：每个 attempt 的引擎会话都挂到 chainTraceId 上
                        //（首轮缺省时兜当前请求 trace，与 markSuspended 的取值口径一致）
                        .withTraceId(task.chainTraceId() != null ? task.chainTraceId() : currentTraceId()));
        try {
            return agentEngine.run(session, new Input(question, Map.of(), prefill));
        } catch (RuntimeException e) {
            taskService.release(task.taskId());
            throw e;
        }
    }

    /**
     * 档位缺省注入（人在环中 P3）：本轮槽位没带（前端旋钮/TraceId 短路等路径）→ 取会话记录，
     * 记录也没有 → 不注入，{@code AutoResolveExecutor} 侧按缺省 L2 同口径处理。
     *
     * @param slots     引擎输入槽位（就地补 {@code autonomy_level}）
     * @param sessionId 会话标识
     */
    private void withAutonomy(Map<String, Object> slots, AgentTaskState task) {
        if (!slots.containsKey(AutonomyLevel.SLOT)) {
            slots.put(AutonomyLevel.SLOT, AutonomyLevel.parse(task.autonomyLevel()).name());
        }
    }

    /**
     * 组装挂起态：已确认槽位取引擎快照里的业务槽。
     *
     * <p>缺失清单不再落库——它由 {@code OpsSlotCatalog} 按目录重算即可（读侧本就是同口径），
     * 存一份只是多一个会过期的副本。</p>
     */
    private AgentTaskState suspendedStateOf(AgentTaskState task, RunResult result) {
        Map<String, String> confirmed = new LinkedHashMap<>();
        if (result.slots() != null) {
            for (String name : OpsSlotCatalog.NAMES) {
                Object value = result.slots().get(name);
                if (value != null && !String.valueOf(value).isBlank()) {
                    confirmed.put(name, String.valueOf(value));
                }
            }
        }
        String stage = result.suspendedNode() == null ? OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE
                : result.suspendedNode();
        // 档位随任务落库（本轮由前端旋钮 / 用户顺带调档 / 上轮沿用而来），下一轮无显式传入时沿用
        String autonomy = result.slots() == null ? ""
                : String.valueOf(result.slots().getOrDefault(AutonomyLevel.SLOT, ""));
        return new AgentTaskState(task.taskId(), task.conversationId(), task.agentId(), stage,
                AgentTaskState.Status.SUSPENDED, confirmed, task.summary(),
                autonomy.isBlank() || "null".equals(autonomy) ? task.autonomyLevel() : autonomy,
                task.chainTraceId() != null ? task.chainTraceId() : currentTraceId(),
                task.attemptCount());
    }

    /**
     * 当前 OTel traceId（诊断链 id 的取值）。
     *
     * <p>同一诊断任务的追问轮会沿用它（见 ChatOrchestrator），所以一次诊断从追问到结论
     * 是一条链而不是 N 段孤立的轨迹；落库侧只在首轮写入
     * （{@code AgentTaskServiceImpl.markSuspended/markConcluded}，chainTraceId 随任务行持久化）。</p>
     *
     * @return traceId；无有效 span 时 null
     */
    private static String currentTraceId() {
        var ctx = io.opentelemetry.api.trace.Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : null;
    }

    private static String textOf(RunResult result) {
        String text = result.output();
        return text == null || text.isBlank() ? "诊断完成（无结论文本，详见轨迹）。" : text;
    }
}
