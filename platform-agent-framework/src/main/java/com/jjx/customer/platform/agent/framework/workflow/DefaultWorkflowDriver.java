package com.jjx.customer.platform.agent.framework.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.capability.AgentCapabilityContractException;
import com.jjx.customer.platform.agent.framework.guard.JsonSchemaValidator;
import com.jjx.customer.platform.agent.framework.node.AgentInvocation;
import com.jjx.customer.platform.agent.framework.node.AgentInvoker;
import com.jjx.customer.platform.agent.framework.node.BudgetView;
import com.jjx.customer.platform.agent.framework.node.NodeContext;
import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.result.Citation;
import com.jjx.customer.platform.agent.framework.result.CitationIndex;
import com.jjx.customer.platform.agent.framework.result.ClarifyInfo;
import com.jjx.customer.platform.agent.framework.result.ContextArtifact;
import com.jjx.customer.platform.agent.framework.result.ContextBundle;
import com.jjx.customer.platform.agent.framework.result.ExecutionMetadataSink;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.GenerationSpec;
import com.jjx.customer.platform.agent.framework.result.MetadataContributor;
import com.jjx.customer.platform.agent.framework.result.RetrievalStats;
import com.jjx.customer.platform.agent.framework.spi.ExecutionEvents;
import com.jjx.customer.platform.agent.framework.spi.ExecutionPhase;
import com.jjx.customer.platform.agent.framework.trace.AgentTrace;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowErrorPolicy;
import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowDriver;
import com.jjx.customer.platform.agent.framework.model.ModelPort;
import com.jjx.customer.platform.agent.framework.model.ModelReply;
import com.jjx.customer.platform.agent.framework.model.ModelRequest;
import com.jjx.customer.platform.agent.framework.node.NodeExecutorRegistry;
import com.jjx.customer.platform.agent.framework.node.NodeResult;
import com.jjx.customer.platform.agent.framework.trace.ExecutionTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 默认工作流驱动（引擎骨架实现）。
 *
 * <p>固化能力：槽位（声明式归一 → LLM 抽槽 → 缺必填一次问齐）、when 跳过、replan 检查点
 * （continue/adjust/escalate 三态裁决）、预算检查（流程级限额与父级切分取 min）、错误策略分派、
 * 产出护栏（JSON Schema）、固定切点 trace（子 Agent 轨迹嵌套上卷）、元数据流出
 * （上下文/引用/指纹先于流式）、结果装配与元数据贡献收集。</p>
 *
 * <p>不做：不写业务规则、不认识具体工具——节点内部行为全部委托 {@code NodeExecutor}。</p>
 */
public class DefaultWorkflowDriver implements WorkflowDriver {

    private final NodeExecutorRegistry nodeExecutors;
    private final ModelPort modelPort;
    private final List<MetadataContributor> metadataContributors;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultWorkflowDriver(NodeExecutorRegistry nodeExecutors) {
        this(nodeExecutors, null, List.of());
    }

    public DefaultWorkflowDriver(NodeExecutorRegistry nodeExecutors, ModelPort modelPort,
                                 List<MetadataContributor> metadataContributors) {
        this.nodeExecutors = nodeExecutors;
        this.modelPort = modelPort;
        this.metadataContributors = metadataContributors == null ? List.of() : List.copyOf(metadataContributors);
    }

    @Override
    public ExecutionResult drive(ExecutionPlan plan, ExecutionMetadataSink sink, AgentInvoker invoker,
                                 AgentInvocation invocation, ExecutionEvents events) {
        Workflow workflow = plan.workflow();
        String agentId = plan.agent().id();
        // 账本共享：顶层账本由引擎挂进调用上下文；子重入直接复用同一实例（消耗逐笔上卷）。
        // 非 ExecutionTrace 的自定义账本实现以父链包装上卷。
        ExecutionTrace trace = invocation.ledger() instanceof ExecutionTrace existing
                ? existing : new ExecutionTrace(invocation.ledger());
        int llmLimit = llmLimit(workflow, invocation);
        long deadline = deadline(workflow, invocation);

        Map<String, Object> slots = new LinkedHashMap<>(plan.request().attributes());
        slots.putAll(plan.prefill());

        // 路由是一个 trace step（命中策略/域/目标 agent）
        if (plan.routeNote() != null) {
            trace.step("route", plan.routeNote(), null, "OK", System.currentTimeMillis());
        }

        // 槽位：声明式归一 → LLM 抽槽（已确认值优先，抽取只填空缺）
        normalizeSlots(workflow, slots);
        extractSlotsIfNeeded(plan, slots, trace);
        events.phase(ExecutionPhase.SLOT_PREPARE, null);

        // 槽位校验：缺必填 ⇒ 一次问齐（CLARIFY）
        List<SlotSpec> missing = workflow.slots().stream()
                .filter(SlotSpec::required)
                .filter(slot -> isBlank(slots.get(slot.name())))
                .toList();
        events.phase(ExecutionPhase.SLOT_VALIDATE,
                missing.isEmpty() ? null : missing.stream().map(SlotSpec::name).collect(Collectors.joining(",")));
        if (!missing.isEmpty()) {
            long t = System.currentTimeMillis();
            String askText = missing.stream()
                    .map(s -> s.question() == null || s.question().isBlank() ? s.name() : s.question())
                    .collect(Collectors.joining("\n"));
            trace.step("collect_slots", "缺少 " + missing.size() + " 项必填信息",
                    missing.stream().map(SlotSpec::name).collect(Collectors.joining(",")),
                    "CLARIFY", t);
            return complete(plan, trace, sink, ExecutionResult.clarify(askText, plan.fingerprint(),
                    snapshot(plan, trace),
                    new ClarifyInfo("collect_slots", declaredSlots(workflow, slots),
                            missing.stream().map(SlotSpec::name).toList())));
        }

        List<ContextArtifact> artifacts = new ArrayList<>();
        String finalText = null;
        int index = 0;
        int adjustRetries = 0;
        String adjustNote = null;

        while (index < workflow.stages().size()) {
            WorkflowStageSpec stage = workflow.stages().get(index);
            long stageStart = System.currentTimeMillis();
            if (stage.when() != null && !stage.when().test(slots)) {
                trace.step(stage.name(), "when 条件不满足，跳过", null, "SKIPPED", stageStart);
                index++;
                continue;
            }
            if (budgetExceeded(llmLimit, trace, deadline)) {
                String reason = budgetReason(llmLimit, trace, deadline);
                trace.step("budget_exhausted", reason, stage.name(), "ESCALATE", stageStart);
                return complete(plan, trace, sink, ExecutionResult.escalate(
                        "执行预算受限中断：" + reason, plan.fingerprint(), snapshot(plan, trace)));
            }

            events.phase(ExecutionPhase.STAGE, stage.name());
            StageOutcome outcome = runStage(plan, stage, slots, invocation, sink, invoker,
                    trace, deadline, llmLimit, adjustNote);
            if (outcome.terminal() != null) {
                return complete(plan, trace, sink, outcome.terminal());
            }
            NodeResult result = outcome.result();
            if ("ASK_USER".equals(result.status())) {
                ExecutionResult clarify = ExecutionResult.clarify(
                        result.text() == null || result.text().isBlank() ? "需要补充信息后继续" : result.text(),
                        plan.fingerprint(), snapshot(plan, trace),
                        new ClarifyInfo(stage.name(), declaredSlots(workflow, slots), List.of()));
                return complete(plan, trace, sink, clarify);
            }
            if ("ESCALATE".equals(result.status())) {
                trace.step(stage.name(), "模型主动升级", result.text(), "ESCALATE", stageStart);
                ExecutionResult escalated = ExecutionResult.escalate(
                        result.text() == null || result.text().isBlank() ? "模型判断无法继续" : result.text(),
                        plan.fingerprint(), snapshot(plan, trace));
                return complete(plan, trace, sink, escalated);
            }
            if ("BUDGET_EXHAUSTED".equals(result.status())) {
                String reason = "阶段 " + stage.name() + " 触及预算上限";
                ExecutionResult escalated = ExecutionResult.escalate("执行预算受限中断：" + reason,
                        plan.fingerprint(), snapshot(plan, trace));
                return complete(plan, trace, sink, escalated);
            }
            if (result.nestedSteps().isEmpty()) {
                trace.step(stage.name(), null, result.text(), result.status(), stageStart);
            } else {
                // 子 Agent 重入：子轨迹嵌套挂到本阶段步骤下
                trace.step(stage.name(), null, result.text(), result.status(), stageStart,
                        result.nestedSteps());
            }
            artifacts.addAll(result.artifacts());
            slots.putAll(result.slotUpdates());
            if (result.text() != null && !result.text().isBlank()) {
                finalText = result.text();
            }

            // replan 检查点（非末阶段 + 声明了裁决 prompt + 本阶段有产出）
            boolean lastStage = index == workflow.stages().size() - 1;
            if (lastStage || workflow.replanPromptKey() == null || modelPort == null
                    || "SKIPPED".equals(result.status())) {
                index++;
                adjustNote = null;
                continue;
            }
            events.phase(ExecutionPhase.REPLAN, stage.name());
            ReplanVerdict verdict = replan(plan, workflow, index, result, trace);
            if (verdict == null) {
                index++;
                adjustNote = null;
                continue;
            }
            switch (verdict.decision()) {
                case "adjust" -> {
                    if (adjustRetries >= Math.max(0, workflow.maxAdjustRetries())) {
                        ExecutionResult escalated = ExecutionResult.escalate(
                                "replan adjust 重跑达上限(" + workflow.maxAdjustRetries() + ")：" + verdict.note(),
                                plan.fingerprint(), snapshot(plan, trace));
                        return complete(plan, trace, sink, escalated);
                    }
                    adjustRetries++;
                    adjustNote = verdict.note();
                    // 重跑本阶段（index 不动）
                }
                case "escalate" -> {
                    ExecutionResult escalated = ExecutionResult.escalate(
                            verdict.note() == null || verdict.note().isBlank() ? "replan 裁决升级" : verdict.note(),
                            plan.fingerprint(), snapshot(plan, trace));
                    return complete(plan, trace, sink, escalated);
                }
                default -> {
                    index++;
                    adjustNote = null;
                }
            }
        }

        events.phase(ExecutionPhase.ASSEMBLE, null);
        ContextBundle context = new ContextBundle(artifacts);
        if (plan.capabilities().contains(AgentCapability.STREAMING)) {
            return complete(plan, trace, sink, withContext(plan, context, trace, sink));
        }
        if (finalText == null || finalText.isBlank()) {
            ExecutionResult escalated = ExecutionResult.escalate("流程未产出结论",
                    plan.fingerprint(), snapshot(plan, trace));
            return complete(plan, trace, sink, escalated);
        }
        ExecutionResult direct = ExecutionResult.direct(finalText, plan.fingerprint(), snapshot(plan, trace));
        return complete(plan, trace, sink, direct);
    }

    // ==================== 槽位（归一 / 抽槽） ====================

    /** 声明式归一：对已有槽位值应用 normalizer（无法识别保留原值）。 */
    private static void normalizeSlots(Workflow workflow, Map<String, Object> slots) {
        for (SlotSpec spec : workflow.slots()) {
            Object value = slots.get(spec.name());
            if (value == null) {
                continue;
            }
            String normalized = applyNormalizer(spec, String.valueOf(value));
            if (normalized != null) {
                slots.put(spec.name(), normalized);
            }
        }
    }

    /**
     * LLM 抽槽：声明了抽槽 prompt 且存在空缺槽位时，经一次 LLM 调用从用户消息抽取
     * （已确认值优先，抽取只填空缺——Merger 语义），再应用归一。
     * 失败容错：记 trace 后按原槽位继续（不阻断）。
     */
    private void extractSlotsIfNeeded(ExecutionPlan plan, Map<String, Object> slots, ExecutionTrace trace) {
        Workflow workflow = plan.workflow();
        String promptKey = workflow.slotExtractPromptKey();
        if (promptKey == null || modelPort == null) {
            return;
        }
        String input = plan.request().input();
        if (input == null || input.isBlank()) {
            return;
        }
        boolean anyBlank = workflow.slots().stream().anyMatch(spec -> isBlank(slots.get(spec.name())));
        if (!anyBlank) {
            return;
        }
        String prompt = plan.promptSnapshot().promptOrNull(promptKey);
        if (prompt == null) {
            throw new IllegalStateException("声明了抽槽但 prompt 不在快照内: " + promptKey
                    + "（workflow=" + workflow.id() + "）");
        }
        String system = prompt
                + "\n\n## 槽位目录\n" + renderSlotCatalog(workflow)
                + "\n\n## 已确认槽位（不要重复抽取，保持原值）\n" + confirmedSlots(workflow, slots);
        long started = System.currentTimeMillis();
        try {
            ModelReply reply = modelPort.complete(new ModelRequest(system, input, List.of(), List.of()));
            trace.addLlmCalls(1);
            Map<String, Object> extracted = parseJsonObject(reply.text());
            if (extracted == null) {
                trace.step("extract_slots", "抽槽输出无法解析（按已知槽位继续）", null, "WARN", started);
                return;
            }
            int filled = 0;
            for (SlotSpec spec : workflow.slots()) {
                if (!isBlank(slots.get(spec.name()))) {
                    continue;
                }
                Object value = extracted.get(spec.name());
                if (value == null || isNullLiteral(value)) {
                    continue;
                }
                String normalized = applyNormalizer(spec, String.valueOf(value));
                if (normalized != null && !normalized.isBlank()) {
                    slots.put(spec.name(), normalized);
                    filled++;
                }
            }
            trace.step("extract_slots", "LLM 抽槽填充 " + filled + " 项", null,
                    filled > 0 ? "OK" : "EMPTY", started);
        } catch (Exception e) {
            trace.addLlmCalls(1);
            trace.step("extract_slots", "抽槽失败（按已知槽位继续）", e.getMessage(), "FAILED", started);
        }
    }

    private static String applyNormalizer(SlotSpec spec, String value) {
        if (spec.normalizer() == null || value == null) {
            return value;
        }
        String normalized = value.isBlank() ? value : spec.normalizer().apply(value.trim());
        return normalized == null ? value : normalized;
    }

    private static String renderSlotCatalog(Workflow workflow) {
        return workflow.slots().stream()
                .map(spec -> "- " + spec.name() + (spec.required() ? "（必填）" : "（选填）")
                        + "：" + (spec.extractionHint() != null && !spec.extractionHint().isBlank()
                        ? spec.extractionHint() : spec.question()))
                .collect(Collectors.joining("\n"));
    }

    private String confirmedSlots(Workflow workflow, Map<String, Object> slots) {
        Map<String, Object> confirmed = new LinkedHashMap<>();
        for (SlotSpec spec : workflow.slots()) {
            Object value = slots.get(spec.name());
            if (!isBlank(value)) {
                confirmed.put(spec.name(), value);
            }
        }
        return confirmed.isEmpty() ? "（无）" : toJson(confirmed);
    }

    // ==================== replan 检查点 ====================

    /** 三态裁决（continue / adjust / escalate）；解析失败返回 null（按 continue 处理）。 */
    private ReplanVerdict replan(ExecutionPlan plan, Workflow workflow, int stageIndex,
                                 NodeResult stageResult, ExecutionTrace trace) {
        String prompt = plan.promptSnapshot().promptOrNull(workflow.replanPromptKey());
        if (prompt == null) {
            throw new IllegalStateException("声明了 replan 但 prompt 不在快照内: "
                    + workflow.replanPromptKey() + "（workflow=" + workflow.id() + "）");
        }
        WorkflowStageSpec current = workflow.stages().get(stageIndex);
        WorkflowStageSpec next = workflow.stages().get(stageIndex + 1);
        String system = prompt
                + "\n\n## 流程阶段\n" + workflow.stages().stream()
                .map(s -> (s == current ? "→（刚完成）" : s == next ? "→（下一个）" : "-") + " " + s.name())
                .collect(Collectors.joining("\n"));
        String user = "上一阶段（" + current.name() + "）产出：\n"
                + (stageResult.text() == null || stageResult.text().isBlank() ? "（无文本产出）" : stageResult.text());
        long started = System.currentTimeMillis();
        try {
            ModelReply reply = modelPort.complete(new ModelRequest(system, user, List.of(), List.of()));
            trace.addLlmCalls(1);
            Map<String, Object> parsed = parseJsonObject(reply.text());
            String decision = parsed == null ? null : strOf(parsed.getOrDefault("action", parsed.get("decision")));
            String reason = parsed == null ? null : strOf(parsed.get("reason"));
            String adjustment = parsed == null ? null : strOf(parsed.get("adjustment"));
            trace.step("replan", "裁决: " + decision, reason, decision == null ? "WARN" : decision.toUpperCase(),
                    started);
            if ("adjust".equals(decision)) {
                String note = adjustment == null || adjustment.isBlank() ? reason : adjustment;
                return new ReplanVerdict("adjust", note);
            }
            if ("escalate".equals(decision)) {
                return new ReplanVerdict("escalate", reason);
            }
            return new ReplanVerdict("continue", null);
        } catch (Exception e) {
            trace.addLlmCalls(1);
            trace.step("replan", "裁决失败（按 continue 处理）", e.getMessage(), "WARN", started);
            return null;
        }
    }

    // ==================== 阶段执行与错误策略 ====================

    private StageOutcome runStage(ExecutionPlan plan, WorkflowStageSpec stage, Map<String, Object> slots,
                                  AgentInvocation invocation, ExecutionMetadataSink sink, AgentInvoker invoker,
                                  ExecutionTrace trace, long deadline, int llmLimit, String adjustNote) {
        WorkflowErrorPolicy policy = stage.workfolwErrorPolicy();
        int attempt = 0;
        while (true) {
            long started = System.currentTimeMillis();
            try {
                NodeContext context = new NodeContext(plan, stage, plan.request(), slots, invocation,
                        invoker, sink, budgetView(llmLimit, trace, deadline), adjustNote);
                NodeResult result = nodeExecutors.require(stage.nodeKind()).execute(context);
                guard(stage, result);
                trace.addLlmCalls(result.llmCalls());
                trace.addToolCalls(result.toolCalls());
                return new StageOutcome(result, null);
            } catch (Exception e) {
                if (policy.action() == WorkflowErrorPolicy.Action.RETRY && attempt < policy.retries()) {
                    attempt++;
                    trace.step(stage.name(), "阶段失败，重试第 " + attempt + " 次", e.getMessage(), "RETRY", started);
                    continue;
                }
                return switch (policy.action()) {
                    case SKIP -> {
                        trace.step(stage.name(), "阶段失败，按策略放弃", e.getMessage(), "STAGE_SKIPPED", started);
                        yield new StageOutcome(NodeResult.skipped(), null);
                    }
                    case ESCALATE -> {
                        trace.step(stage.name(), "阶段失败，升级", e.getMessage(), "ESCALATE", started);
                        yield new StageOutcome(null, ExecutionResult.escalate(
                                "阶段 " + stage.name() + " 失败：" + e.getMessage(),
                                plan.fingerprint(), snapshot(plan, trace)));
                    }
                    case FAIL -> {
                        trace.step(stage.name(), "阶段失败，终止流程", e.getMessage(), "STAGE_FAILED", started);
                        yield new StageOutcome(null, ExecutionResult.direct(
                                "阶段 " + stage.name() + " 执行失败：" + e.getMessage(),
                                plan.fingerprint(), snapshot(plan, trace)));
                    }
                    default -> {
                        trace.step(stage.name(), "阶段失败（AS_IS）", e.getMessage(), "FAILED", started);
                        yield new StageOutcome(NodeResult.failed(e.getMessage()), null);
                    }
                };
            }
        }
    }

    /** 产出护栏：schema 非空且产出非空时按 JSON Schema 校验（不符即阶段失败，交 errorPolicy）。 */
    private void guard(WorkflowStageSpec stage, NodeResult result) {
        if (stage.outputGuardSchema() == null || stage.outputGuardSchema().isBlank()
                || result.text() == null || result.text().isBlank()) {
            return;
        }
        try {
            JsonNode payload = objectMapper.readTree(result.text());
            JsonNode schema = objectMapper.readTree(stage.outputGuardSchema());
            List<String> errors = JsonSchemaValidator.validate(schema, payload);
            if (!errors.isEmpty()) {
                throw new IllegalStateException("产出护栏不符: " + String.join("; ", errors));
            }
        } catch (Exception e) {
            throw new IllegalStateException("产出护栏不符: " + e.getMessage());
        }
    }

    // ==================== 预算 ====================

    /** 流程级限额与父级切分取 min（子不可绕过父）。 */
    private static int llmLimit(Workflow workflow, AgentInvocation invocation) {
        int own = workflow.maxLlmCalls();
        int shared = invocation.llmBudget();
        if (own > 0 && shared > 0) {
            return Math.min(own, shared);
        }
        return Math.max(own, shared);
    }

    /** 流程超时与父级截止取更早者。 */
    private static long deadline(Workflow workflow, AgentInvocation invocation) {
        long own = workflow.timeoutSeconds() > 0
                ? System.currentTimeMillis() + workflow.timeoutSeconds() * 1000L : Long.MAX_VALUE;
        return Math.min(own, invocation.deadline());
    }

    /** 阶段内预算视图：总账在 trace（引擎托管），节点只读。 */
    private static BudgetView budgetView(int llmLimit, ExecutionTrace trace, long deadline) {
        int remaining = llmLimit > 0 ? Math.max(0, llmLimit - trace.llmCalls()) : -1;
        return new BudgetView(remaining, deadline);
    }

    private static boolean budgetExceeded(int llmLimit, ExecutionTrace trace, long deadline) {
        return (llmLimit > 0 && trace.llmCalls() >= llmLimit)
                || System.currentTimeMillis() > deadline;
    }

    private static String budgetReason(int llmLimit, ExecutionTrace trace, long deadline) {
        return llmLimit > 0 && trace.llmCalls() >= llmLimit
                ? "LLM 调用预算耗尽（" + trace.llmCalls() + "/" + llmLimit + "）"
                : "流程超时";
    }

    // ==================== 结果装配与收口 ====================

    /** 终态收口：元数据贡献收集 + trace 流出（统一出口，onTraceUpdate 恰好一次）。 */
    private ExecutionResult complete(ExecutionPlan plan, ExecutionTrace trace,
                                     ExecutionMetadataSink sink, ExecutionResult result) {
        ExecutionResult contributed = applyContributors(plan, result);
        AgentTrace snapshot = contributed.trace() == null ? snapshot(plan, trace) : contributed.trace();
        sink.onTraceUpdate(snapshot);
        return contributed;
    }

    /** MetadataContributor 收集：单个贡献者异常只丢自身（观测不阻断）。 */
    private ExecutionResult applyContributors(ExecutionPlan plan, ExecutionResult result) {
        if (metadataContributors.isEmpty()) {
            return result;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (MetadataContributor contributor : metadataContributors) {
            try {
                Map<String, Object> contribution = contributor.contribute(plan, result);
                if (contribution != null && !contribution.isEmpty()) {
                    merged.put(contributor.key(), contribution);
                }
            } catch (Exception ignored) {
                // 观测扩展不阻断执行
            }
        }
        return merged.isEmpty() ? result : result.withMetadata(Map.copyOf(merged));
    }

    private AgentTrace snapshot(ExecutionPlan plan, ExecutionTrace trace) {
        return trace.toTrace(plan.agent().id(), plan.workflow().id(), plan.fingerprint());
    }

    private ExecutionResult withContext(ExecutionPlan plan, ContextBundle context,
                                        ExecutionTrace trace, ExecutionMetadataSink sink) {
        Workflow workflow = plan.workflow();
        String answerKey = workflow.answerPromptKey();
        if (answerKey == null || answerKey.isBlank()) {
            throw new AgentCapabilityContractException(
                    "声明了 STREAMING 但 Workflow 未提供 answerPromptKey: " + workflow.id());
        }
        String answerContent;
        try {
            answerContent = plan.promptSnapshot().prompt(answerKey);
        } catch (RuntimeException e) {
            throw new AgentCapabilityContractException("答案 prompt 不在快照内(" + answerKey + "): " + e.getMessage());
        }

        String assembled = context.artifacts().stream()
                .map(a -> "[ref=" + a.ref() + "] " + Objects.toString(a.source(), "?") + "\n"
                        + Objects.toString(a.content(), ""))
                .collect(Collectors.joining("\n\n"));
        CitationIndex citations = new CitationIndex(context.artifacts().stream()
                .map(a -> new Citation(a.ref(), a.source(), locator(a)))
                .toList());
        GenerationSpec generation = new GenerationSpec(answerKey, answerContent, assembled, plan.fingerprint());
        RetrievalStats stats = plan.capabilities().contains(AgentCapability.RETRIEVAL_METRICS)
                ? new RetrievalStats(context.artifacts().size(), context.artifacts().size(),
                        context.artifacts().stream().map(ContextArtifact::channel)
                                .filter(Objects::nonNull).distinct().toList(),
                        context.artifacts().stream().map(ContextArtifact::score)
                                .filter(Objects::nonNull).max(Double::compareTo).orElse(null))
                : null;

        // 时序约束：上下文与引用必须先于流式生成流出
        sink.onContextReady(context);
        sink.onCitationsReady(citations);
        if (stats != null) {
            sink.onRetrievalStats(stats);
        }
        return ExecutionResult.withContext(context, citations, generation,
                plan.fingerprint(), stats, snapshot(plan, trace));
    }

    private static String locator(ContextArtifact artifact) {
        Object locator = artifact.metadata().get("locator");
        return locator == null ? null : String.valueOf(locator);
    }

    // ==================== 工具方法 ====================

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    /**
     * 已确认槽位（只取流程声明过的键，值转字符串）——追问落会话用。
     *
     * <p>用流程自己的 {@link Workflow#slots()} 目录过滤，不再让使用方拿业务槽位目录重算一遍。</p>
     */
    private static Map<String, String> declaredSlots(Workflow workflow, Map<String, Object> slots) {
        Map<String, String> declared = new LinkedHashMap<>();
        for (SlotSpec spec : workflow.slots()) {
            Object value = slots.get(spec.name());
            if (value != null && !String.valueOf(value).isBlank()) {
                declared.put(spec.name(), String.valueOf(value));
            }
        }
        return declared;
    }

    private static boolean isNullLiteral(Object value) {
        return "null".equals(String.valueOf(value));
    }

    private static String strOf(Object value) {
        return value == null || "null".equals(String.valueOf(value)) ? null : String.valueOf(value);
    }

    /** 容错 JSON 对象解析：剥 markdown 围栏/前置文本后读 Map；失败返回 null。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw;
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start >= 0 && end > start) {
            candidate = candidate.substring(start, end + 1);
        }
        try {
            return objectMapper.readValue(candidate, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** replan 三态裁决。 */
    private record ReplanVerdict(String decision, String note) {
    }

    /** 阶段结果 或 终态结果（二者其一）。 */
    private record StageOutcome(NodeResult result, ExecutionResult terminal) {
    }
}
