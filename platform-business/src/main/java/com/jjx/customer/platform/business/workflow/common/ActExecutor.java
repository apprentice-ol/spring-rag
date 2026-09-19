package com.jjx.customer.platform.business.workflow.common;

import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.policy.PolicyAttributes;
import com.agentframework.engine.toolexecutor.*;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.runtime.session.Message;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.workflow.common.EscalateTerminal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段内工具循环的 Act 执行器（O5 核心）：解析 think 节点的 JSON 意图
 * （{@code tool / answer / ask_user / escalate}，解析失败按参考语义把原文当终稿），
 * 白名单内经框架 ToolExecutor 管道执行工具（权限/配额/缓存/超时/重试/span），
 * Action/Observation 追加进 scratchpad 供下一轮 think 回灌。
 *
 * <p>控制信号到图范式的映射（不存在内核控制面）：
 * {@code answer} → 写阶段产出、退出循环；{@code escalate} → 动态边直达升级终态；
 * {@code ask_user} → 写 {@code pending_ask} 后整次运行挂起（{@code NodeResult.suspended}
 * 与节点类型无关），恢复即重入本节点——先消费答复再继续，不得二次挂起（幂等）。</p>
 *
 * <p>本类在 workflow/common（两域共用层），仍 import {@code ops.slot.OpsSlotCatalog}——
 * ask_user 的期望槽位清单来自 ops 槽位目录。已知妥协：参数化目录来源需改全部构造点，
 * 待后续单独处理；react 轴检索-only 白名单下 ask_user 实际不会触发。</p>
 */
public class ActExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActExecutor.class);

    /** ask_user 暂存槽位名（非空即"环内已提问，等答复"）。 */
    public static final String PENDING_ASK_SLOT = "pending_ask";

    /** ask_user 声明的期望补充槽位名（逗号分隔，目录内；空 = 纯文本追问）。 */
    public static final String PENDING_ASK_SLOTS_SLOT = "pending_ask_slots";

    /** 用户补充槽位名（与入口问齐共用，恢复输入经 Input.slots 写入）。 */
    public static final String USER_CLARIFY_SLOT = "user_clarify";

    private final String prefix;

    private final String stageName;

    private final Set<String> allowedTools;

    private final ToolRegistry registry;

    private final com.agentframework.engine.toolexecutor.ToolExecutor toolExecutor;

    private final ObjectMapper objectMapper;

    private final int maxLlmCalls;

    /** 出口护栏：阶段产出中的 JSON 代码块按此 schema 校验（null = 该阶段无护栏）。 */
    private final java.util.function.Function<NodeContext, com.fasterxml.jackson.databind.JsonNode> outputSchema;

    /**
     * @param prefix       阶段槽位前缀（inv/res/ver）
     * @param stageName    阶段名（trace 展示用）
     * @param allowedTools 阶段工具白名单
     * @param registry     工具注册表
     * @param toolExecutor 工具执行管道（缓存/超时/重试/审计在管道上）
     * @param objectMapper JSON 解析
     */
    public ActExecutor(String prefix, String stageName, Set<String> allowedTools,
            ToolRegistry registry, com.agentframework.engine.toolexecutor.ToolExecutor toolExecutor,
            ObjectMapper objectMapper) {
        this(prefix, stageName, allowedTools, registry, toolExecutor, objectMapper, 0, null);
    }

    /**
     * @param prefix       阶段槽位前缀（inv/res/ver）
     * @param stageName    阶段名（trace 展示用）
     * @param allowedTools 阶段工具白名单
     * @param registry     工具注册表
     * @param toolExecutor 工具执行管道（缓存/超时/重试/审计在管道上）
     * @param objectMapper JSON 解析
     * @param maxLlmCalls  流程级 LLM 调用上限（O9，≤0 表示不限）
     */
    public ActExecutor(String prefix, String stageName, Set<String> allowedTools,
            ToolRegistry registry, com.agentframework.engine.toolexecutor.ToolExecutor toolExecutor,
            ObjectMapper objectMapper, int maxLlmCalls) {
        this(prefix, stageName, allowedTools, registry, toolExecutor, objectMapper, maxLlmCalls, null);
    }

    /**
     * @param prefix       阶段槽位前缀（inv/res/ver）
     * @param stageName    阶段名（trace 展示用）
     * @param allowedTools 阶段工具白名单
     * @param registry     工具注册表
     * @param toolExecutor 工具执行管道（缓存/超时/重试/审计在管道上）
     * @param objectMapper JSON 解析
     * @param maxLlmCalls  流程级 LLM 调用上限（O9，≤0 表示不限）
     * @param outputSchema 出口护栏（O8）：按上下文解析该阶段的产出 schema，null = 无护栏
     */
    public ActExecutor(String prefix, String stageName, Set<String> allowedTools,
            ToolRegistry registry, com.agentframework.engine.toolexecutor.ToolExecutor toolExecutor,
            ObjectMapper objectMapper, int maxLlmCalls,
            java.util.function.Function<NodeContext, com.fasterxml.jackson.databind.JsonNode> outputSchema) {
        this.prefix = prefix;
        this.stageName = stageName;
        this.allowedTools = Set.copyOf(allowedTools);
        this.registry = registry;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.maxLlmCalls = maxLlmCalls;
        this.outputSchema = outputSchema;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        Map<String, Object> writes = new LinkedHashMap<>();
        String scratchpad = context.slots().getString(prefix + "_scratchpad", "");
        String pendingAsk = context.slots().getString(PENDING_ASK_SLOT, "");

        // ① 幂等重入：上一轮 ask_user 挂起后带答复回来 → 消费答复回环，绝不再挂起
        if (!pendingAsk.isBlank()) {
            String reply = replyOf(context);
            if (!reply.isBlank()) {
                scratchpad += "AskUser: " + pendingAsk + "\n用户补充: " + reply + "\n---\n";
                writes.put("scratchpad", scratchpad);
                writes.put(PENDING_ASK_SLOT, "");
                writes.put(PENDING_ASK_SLOTS_SLOT, "");
                writes.put(USER_CLARIFY_SLOT, reply);
                writes.put("has_calls", 1);
                writes.put("llm_calls", llmCalls(context) + 1);
                return NodeResult.completed(node.id(), "已收到补充：" + reply, writes)
                        .withMessage(Message.user(reply));
            }
            // 重入但答复为空：同一问题（连同声明的槽位）再挂起（幂等）
            String askSlots = context.slots().getString(PENDING_ASK_SLOTS_SLOT, "");
            return NodeResult.suspended(node.id(), pendingAsk)
                    .withSlotWrite(PENDING_ASK_SLOT, pendingAsk)
                    .withSlotWrite(PENDING_ASK_SLOTS_SLOT, askSlots);
        }

        // ② 预算闸门（O9/D6/D9）：LLM 调用数触及上限 → 显式升级，绝不静默截断
        //（动态边直达升级终态：act 节点的动态路由白名单已含 escalate_node，见图声明）
        int used = llmCalls(context);
        if (maxLlmCalls > 0 && used >= maxLlmCalls) {
            String reason = "执行预算受限中断：LLM 调用数触及上限 " + maxLlmCalls
                    + "（阶段 " + stageName + "），请缩小排查范围或补充信息后重试";
            return NodeResult.dynamic(node.id(), reason, EscalateTerminal.NODE_ID)
                    .withSlotWrite("escalate_reason", reason)
                    .withSlotWrite("has_calls", 0);
        }

        // ③ 解析 think 意图（原文不可解析 ⇒ 当终稿，对齐参考 JsonResponseParser 语义）
        Intent intent = this.parseLLMThink(context.slots().getString(prefix + "_out", ""));
        writes.put("llm_calls", used + 1);

        switch (intent.kind()) {
            case TOOL -> {
                ToolOutcome outcome = this.executeTool(intent.toolName(), intent.arguments(), node, context);
                String observation = outcome.observation();
                scratchpad += "Thought: " + preview(context.slots().getString(prefix + "_out", ""))
                        + "\nAction: " + intent.toolName() + " " + intent.arguments()
                        + "\nObservation: " + observation + "\n---\n";
                writes.put("scratchpad", scratchpad);
                writes.put("observation", observation);
                writes.put("has_calls", 1);
                // 工具结构化命中的跨轮累积（如 retrieve_knowledge 的 chunks）：
                // 每轮 append 进 tool_chunks（Region 前缀展开为 {prefix}_tool_chunks），
                // 供出口层（knowledge react 线）一次性取全量命中
                Object fresh = outcome.result() == null ? null : outcome.result().data().get("chunks");
                if (fresh instanceof List<?> list && !list.isEmpty()) {
                    List<Object> merged = new java.util.ArrayList<>();
                    Object existing = context.slots().get("tool_chunks");
                    if (existing instanceof List<?> prior) {
                        merged.addAll(prior);
                    }
                    merged.addAll(list);
                    writes.put("tool_chunks", merged);
                }
                return NodeResult.completed(node.id(), observation, writes)
                        .withMessage(Message.tool(node.id(), observation))
                        .withMetadata("toolCall", toolCallDocument(intent, outcome));
            }
            case ANSWER -> {
                // 出口护栏（O8/D11）：产出里的 JSON 代码块按 schema 校验，不过则回环修正
                //（受阶段 maxSteps 限，不会无限循环；参考实现的 outputGuardSchema 从未启用，此处补齐）
                List<String> violations = gateOutput(intent.text(), context);
                if (!violations.isEmpty()) {
                    String observation = "❌ 阶段产出未通过护栏校验（" + violations.size() + " 处）：\n- "
                            + String.join("\n- ", violations) + "\n请修正后重新给 answer。";
                    scratchpad += "OutputGate: " + observation + "\n---\n";
                    writes.put("scratchpad", scratchpad);
                    writes.put("has_calls", 1);
                    return NodeResult.completed(node.id(), observation, writes);
                }
                writes.put("stage_output", intent.text());
                writes.put("has_calls", 0);
                return NodeResult.completed(node.id(), intent.text(), writes)
                        .withMessage(Message.assistant(intent.text()));
            }
            case ASK_USER -> {
                String question = intent.text().isBlank() ? "需要补充信息后继续" : intent.text();
                // 挂起前必须消费触发状态：scratchpad 记录提问、pending_ask 置位（幂等标记）；
                // 模型声明的期望槽位一并暂存（交付层据此渲染结构化表单，空 = 纯文本追问）
                String askSlots = String.join(",", intent.askSlots());
                scratchpad += "AskUser: " + question + "\n（等待用户补充）\n---\n";
                writes.put("scratchpad", scratchpad);
                writes.put(PENDING_ASK_SLOT, question);
                writes.put(PENDING_ASK_SLOTS_SLOT, askSlots);
                return NodeResult.suspended(node.id(), question)
                        .withSlotWrite(PENDING_ASK_SLOT, question)
                        .withSlotWrite(PENDING_ASK_SLOTS_SLOT, askSlots)
                        .withSlotWrite("scratchpad", scratchpad);
            }
            case ESCALATE -> {
                String reason = intent.text().isBlank() ? "模型判断无法继续" : intent.text();
                String prior = priorToolBackedConclusion(context);
                if (!prior.isBlank()) {
                    // D12：前面阶段已给出工具返回支撑的结论 → 不以升级收场（用户要的是结论，
                    // 不是一句"需人工介入"）。升级诉求降级成本阶段产出里的补充说明，
                    // 走与 answer 相同的出环路径，最终由收尾节点直出。
                    String text = prior + "\n\n——\n补充说明（仍需人工确认）：" + reason;
                    writes.put("stage_output", text);
                    writes.put("has_calls", 0);
                    return NodeResult.completed(node.id(), text, writes)
                            .withMessage(Message.assistant(text));
                }
                writes.put("escalate_reason", reason);
                writes.put("has_calls", 0);
                return NodeResult.dynamic(node.id(), reason, EscalateTerminal.NODE_ID)
                        .withSlotWrite("escalate_reason", reason)
                        .withSlotWrite("has_calls", 0);
            }
            default -> {
                return NodeResult.failed(node.id(), "未知意图类型：" + intent.kind());
            }
        }
    }

    /** 三阶段槽位前缀（与收尾节点取产出的顺序一致）。 */
    private static final String[] STAGE_PREFIXES = {"inv", "res", "ver"};

    /**
     * 取前面阶段里「有工具返回支撑」的结论（最后非空者优先）。
     *
     * <p>判据：该阶段既有 answer 产出，scratchpad 里又有 {@code Observation:}（真执行过工具）。
     * 只有 AskUser / OutputGate 记录不算证据。</p>
     *
     * @param context 节点上下文
     * @return 结论文本；没有则空串
     */
    private static String priorToolBackedConclusion(NodeContext context) {
        String found = "";
        for (String stage : STAGE_PREFIXES) {
            String output = context.slots().getString(stage + "_stage_output", "");
            String stageScratchpad = context.slots().getString(stage + "_scratchpad", "");
            if (!output.isBlank() && stageScratchpad.contains("Observation:")) {
                found = output;
            }
        }
        return found;
    }

    /**
     * 出口护栏：提取产出里的首个 JSON 代码块（```json 围栏或裸 JSON 对象）按 schema 校验。
     *
     * @param answerText 阶段产出
     * @param context    上下文
     * @return 违规清单（无护栏/无 JSON 块/通过 = 空）
     */
    private List<String> gateOutput(String answerText, NodeContext context) {
        if (outputSchema == null) {
            return List.of();
        }
        com.fasterxml.jackson.databind.JsonNode schema = outputSchema.apply(context);
        if (schema == null || answerText == null) {
            return List.of();
        }
        String json = extractJsonBlock(answerText);
        if (json == null) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree(json);
            return com.jjx.customer.platform.common.util.JsonSchemaValidator.validate(schema, payload);
        } catch (Exception e) {
            return List.of("产出中的 JSON 代码块无法解析：" + e.getMessage());
        }
    }

    /** 提取 ```json 围栏或首个裸 JSON 对象文本。 */
    private static String extractJsonBlock(String text) {
        int fence = text.indexOf("```json");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.indexOf("```", start + 1);
            if (start > 0 && end > start) {
                return text.substring(start + 1, end).trim();
            }
        }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }
        return null;
    }

    /**
     * 白名单校验 + 经 ToolExecutor 执行（失败回喂错误说明，不中断）。
     *
     * @param toolName  工具名
     * @param arguments 参数
     * @param node      当前节点
     * @param context   上下文
     * @return 观测文本、成败与原始结果（结构化数据累积用）
     */
    private ToolOutcome executeTool(String toolName, Map<String, Object> arguments,
            NodeDefinition node, NodeContext context) {
        if (!allowedTools.contains(toolName)) {
            return ToolOutcome.failed("[工具错误] 阶段 " + stageName + " 不允许使用工具 " + toolName
                    + "，可用：" + allowedTools);
        }
        return registry.resolve(toolName, ToolDefinition.LATEST)
                .map(tool -> run(tool.id(), arguments, node, context))
                .map(result -> result.success()
                        ? ToolOutcome.ok(result.output(), result)
                        : ToolOutcome.failed("[工具错误] " + result.error()))
                .orElseGet(() -> ToolOutcome.failed("[工具错误] 未注册工具：" + toolName));
    }

    /**
     * 轨迹里的工具调用记录。
     *
     * <p>下发结构化的工具名、参数与成败，读轨迹的人（以及前端）不必再从观测文本里
     * 认「[工具错误]」前缀来反推这次调用到底成没成。</p>
     *
     * @param intent  think 解析出的调用意图
     * @param outcome 执行结果
     * @return 可序列化的调用记录
     */
    private Map<String, Object> toolCallDocument(Intent intent, ToolOutcome outcome) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", intent.toolName());
        document.put("arguments", intent.arguments());
        document.put("ok", outcome.ok());
        if (outcome.error() != null) {
            document.put("error", outcome.error());
        }
        return document;
    }

    /**
     * 组装带策略属性的调用并执行（对齐 ReActExample 的属性传递）。
     *
     * @param toolId    工具 id
     * @param arguments 参数
     * @param node      当前节点
     * @param context   上下文
     * @return 工具结果
     */
    private ToolResult run(String toolId, Map<String, Object> arguments,
            NodeDefinition node, NodeContext context) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("toolId", toolId);
        attributes.put(InterceptorAttributes.TRACE, context.trace());
        attributes.put(InterceptorAttributes.TRACE_PARENT, context.parentSpan());
        if (context.agent() != null) {
            attributes.put(DefaultToolExecutor.TOOL_POLICY_ATTRIBUTE,
                    context.agent().policies().tool());
            attributes.put(DefaultToolExecutor.QUOTA_POLICY_ATTRIBUTE,
                    context.agent().policies().quota());
        }
        Object resolvedPolicy = context.attributes().get(PolicyAttributes.RESOLVED);
        if (resolvedPolicy != null) {
            attributes.put(PolicyAttributes.RESOLVED, resolvedPolicy);
        }
        ToolContext toolContext = new ToolContext(context.session().id(), node.id(),
                context.session().traceId(), context.workspace(), context.slots(), attributes);
        try {
            return toolExecutor.execute(
                    new ToolInvocation(toolId, ToolDefinition.LATEST, arguments,
                            context.session().id(), node.id()),
                    toolContext);
        } catch (Exception e) {
            log.warn("[ops-act] 工具执行异常：{} {}", toolId, e.getMessage());
            return ToolResult.failed("工具执行异常：" + e.getMessage());
        }
    }

    /**
     * 解析 think 输出的一行 JSON 意图。
     *
     * @param raw think 输出
     * @return 意图（不可解析时按 ANSWER 原文）
     */
    Intent parseLLMThink(String raw) {
        Map<String, Object> parsed = parseOneLineJson(raw);
        if (parsed == null) {
            return Intent.answer(raw == null ? "" : raw.trim());
        }
        if (parsed.get("tool") instanceof String tool && !tool.isBlank()) {
            Map<String, Object> args = parsed.get("args") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            return new Intent(Kind.TOOL, tool, args, "", List.of());
        }
        if (nonBlank(parsed.get("ask_user"))) {
            return Intent.askUser(String.valueOf(parsed.get("ask_user")), askSlotsOf(parsed.get("slots")));
        }
        if (nonBlank(parsed.get("escalate"))) {
            return Intent.escalate(String.valueOf(parsed.get("escalate")));
        }
        if (nonBlank(parsed.get("answer"))) {
            return Intent.answer(String.valueOf(parsed.get("answer")));
        }
        return Intent.answer(raw == null ? "" : raw.trim());
    }

    /**
     * 解析 ask_user 声明的期望槽位名：只收目录内的名字（模型编造的名字全部丢弃，
     * 退化为纯文本追问），按目录顺序去重。
     *
     * @param value 协议里的 slots 字段
     * @return 合法槽位名列表（可为空）
     */
    private static List<String> askSlotsOf(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<String> accepted = new java.util.ArrayList<>();
        for (OpsSlotCatalog.Spec spec : OpsSlotCatalog.ALL) {
            for (Object item : raw) {
                if (item != null && spec.name().equals(String.valueOf(item).trim())
                        && !accepted.contains(spec.name())) {
                    accepted.add(spec.name());
                }
            }
        }
        return accepted;
    }

    private Map<String, Object> parseOneLineJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            return null;
        }
        try {
            return objectMapper.readValue(text.substring(braceStart, braceEnd + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    private String replyOf(NodeContext context) {
        String clarified = context.slots().getString(USER_CLARIFY_SLOT, "");
        if (!clarified.isBlank()) {
            return clarified;
        }
        return context.input() == null ? "" : context.input().text();
    }

    private int llmCalls(NodeContext context) {
        Object value = context.slots().get("llm_calls");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static boolean nonBlank(Object value) {
        return value != null && !String.valueOf(value).isBlank()
                && !"null".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static String preview(String text) {
        if (text == null || text.length() <= 120) {
            return text == null ? "" : text;
        }
        return text.substring(0, 120) + "…";
    }

    /** 意图类型。 */
    enum Kind {TOOL, ANSWER, ASK_USER, ESCALATE}

    /**
     * 一次 think 意图。
     *
     * @param kind      类型
     * @param toolName  工具名（TOOL 时）
     * @param arguments 工具参数（TOOL 时）
     * @param text      文本（answer/ask_user/escalate 的载荷）
     * @param askSlots  ask_user 声明的期望补充槽位名（目录内、去重；其余意图为空）
     */
    record Intent(Kind kind, String toolName, Map<String, Object> arguments, String text,
            List<String> askSlots) {

        static Intent answer(String text) {
            return new Intent(Kind.ANSWER, "", Map.of(), text, List.of());
        }

        static Intent askUser(String question) {
            return new Intent(Kind.ASK_USER, "", Map.of(), question, List.of());
        }

        static Intent askUser(String question, List<String> slots) {
            return new Intent(Kind.ASK_USER, "", Map.of(), question,
                    slots == null ? List.of() : List.copyOf(slots));
        }

        static Intent escalate(String reason) {
            return new Intent(Kind.ESCALATE, "", Map.of(), reason, List.of());
        }
    }

    /**
     * 一次工具调用的结果：回喂模型的观测文本 + 成败 + 原始结果。
     *
     * @param observation 观测文本（成功是工具产出，失败是错误说明）
     * @param ok          调用是否成功
     * @param error       失败原因；成功时为 null
     * @param result      工具原始结果（结构化数据累积用，失败时为 null）
     */
    record ToolOutcome(String observation, boolean ok, String error, ToolResult result) {

        static ToolOutcome ok(String observation, ToolResult result) {
            return new ToolOutcome(observation, true, null, result);
        }

        static ToolOutcome failed(String message) {
            return new ToolOutcome(message, false, message, null);
        }
    }
}
