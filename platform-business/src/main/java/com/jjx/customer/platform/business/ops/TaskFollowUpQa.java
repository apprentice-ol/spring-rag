package com.jjx.customer.platform.business.ops;

import com.agentframework.definition.workflow.HumanResponse;
import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;
import com.jjx.customer.platform.business.engine.outcome.OutcomeKind;
import com.jjx.customer.platform.business.task.AgentFinding;
import com.jjx.customer.platform.business.task.AgentTaskState;
import com.jjx.customer.platform.business.task.FindingsText;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务内直答（Task QA，2026-09-20）：CONCLUDED 任务上「对结论的提问」的消费路径。
 *
 * <p><b>为什么要有它</b>：Task 层闭环后，用户对结论的反应仍只有一条消费路径——开新 attempt
 * 重跑整个 SOP。于是「为什么说缺 invoiceNumber？」得到的不是回答，而是一次带确认卡的重新诊断
 * （贵、慢、四段式格式答非所问）。本类补上「读投影、不进循环」的轻路径：结论全文（task.summary）
 * + 生效主张 + 槽位投影组装后单次 LLM 直答，对应设计见 plan/2026-09-20-task-qa-loop.md。</p>
 *
 * <p><b>不变量（QA 轮不做的事）</b>：不递增 attempt、不 markConcluded、不重抽 findings
 * （解读不产生新主张——下一轮异议仍以原结论主张为靶）、不建引擎会话。任何一步失败都返回
 * empty 落回重跑路径，<b>最坏退化 = 现状行为</b>，用户不会因为本路径拿不到回复。</p>
 *
 * <p><b>语义判断全交模型</b>（2026-09-20 第三轮修正）：确定性词表在开放语言空间里覆盖度不够，
 * 真机首日即踩坑（「给我一个修复后的报文」被词「修复」误判成行动指令，复读整份结论）——
 * 踩坑加词是打地鼠。判据与速判例沉淀在 qa-classify.md（prompt 资产，可热更不用改代码发版），
 * 分类时带上结论摘要：判「用户要的东西是否已在结论里」（如修复报文在「修正动作」段）
 * 需要看见结论，只看消息原文判不准索取句。</p>
 *
 * <p><b>三态消费路径</b>（第四轮）：answer = 结论素材直答；<b>retrieve = 针对性检索直答</b>
 * （分类时同步改写检索问句，调一次知识检索，命中进材料后回答——「从接口文档中看下是否
 * 符合规格」这类追问在此消化，不再被迫走全量 SOP 重跑）；rerun = 完整诊断重跑。</p>
 *
 * <p><b>保留的两条确定性规则</b>：协议文本（{@code #decision:/#override:} 是机器协议
 * 不是自然语言，不耗模型）与失败降级（模型不可用 / 解析失败 / 检索通道缺席或异常，
 * 一律按 rerun，不劣化）。</p>
 *
 * <p><b>偏置方向</b>：误判到 rerun 的代价是多花几次调用（结论仍正确），误判到 answer 的代价是
 * 该重查的问题只凭旧结论回答——判据（qa-classify.md 的「拿不准选 rerun」）与降级默认都偏向
 * rerun；分错不是终态：用户下一句「不对 / 重查」自然落回重跑路径。</p>
 */
public class TaskFollowUpQa {

    private static final Logger log = LoggerFactory.getLogger(TaskFollowUpQa.class);

    /** 分类器 prompt 资产 key（正文 = prompts/workflow/ops_diagnose_v2/qa-classify.md）。 */
    public static final String CLASSIFY_ASSET = "workflow/ops_diagnose_v2/qa-classify";

    /** 直答 prompt 资产 key（正文 = prompts/workflow/ops_diagnose_v2/qa-answer.md）。 */
    public static final String ANSWER_ASSET = "workflow/ops_diagnose_v2/qa-answer";

    /** QA 轨迹的流程标识（非框架路径）：对照面板按此区分直答轮与完整诊断轮（ops_diagnose_v2）。 */
    public static final String QA_WORKFLOW_MARKER = "task_qa";

    /** 结论全文注入上限（结论本身有界，这里只是防长尾把解读上下文撑爆）。 */
    private static final int SUMMARY_MAX = 6000;

    /** 单条主张截断长度（口径收敛在 {@link FindingsText#CLAIM_MAX_CHARS}——编号引用必须两边一致）。 */
    private static final int FINDING_CLAIM_MAX = FindingsText.CLAIM_MAX_CHARS;

    /** 注入的主张条数上限（有界是硬要求：组装成本与历史长度解耦；口径收敛在 {@link FindingsText#MAX_ITEMS}）。 */
    private static final int FINDING_MAX = FindingsText.MAX_ITEMS;

    /** 分类注入的结论摘要上限：判「用户要的东西是否已在结论里」够用即可。 */
    private static final int CLASSIFY_SUMMARY_MAX = 800;

    /** 检索命中文本注入上限（与工具观测口径一致：够读，不撑爆解读上下文）。 */
    private static final int RETRIEVAL_MAX = 4000;

    private final SingleTurnModel model;

    /** 针对性检索通道（null = retrieve 判定降级为 rerun）。 */
    private final Retriever retriever;

    private final ObjectMapper objectMapper;

    /** prompt 正文来源（绑定包覆盖优先，classpath 兜底；null = 只走确定性规则）。 */
    private final Function<String, String> promptBody;

    /**
     * 检索通道：query → 观测文本（[ref=N] 命中列表或带归因的空召回说明）。
     * 与引擎内 retrieve_knowledge 同一工具，QA 侧只做一次针对性检索。
     */
    @FunctionalInterface
    public interface Retriever {
        String retrieve(String query);
    }

    /**
     * @param model        轻量问答模型（null = 不可用，QA 整体降级为 rerun）
     * @param retriever    针对性检索通道（null = retrieve 判定降级为 rerun）
     * @param objectMapper JSON 解析
     * @param promptBody   prompt key → 正文
     */
    public TaskFollowUpQa(SingleTurnModel model, Retriever retriever, ObjectMapper objectMapper,
            Function<String, String> promptBody) {
        this.model = model;
        this.retriever = retriever;
        this.objectMapper = objectMapper;
        this.promptBody = promptBody;
    }

    /**
     * 尝试对 CONCLUDED 任务的追问做任务内直答。
     *
     * <p>判定的次序是「便宜的在前面」：状态 / 结论全文 / 模型可用性先挡掉不该进 QA 的轮次，
     * 分类器（含其 LLM 兜底）只在真的有结论可解读时才跑；应答失败也返回 empty 落回重跑。</p>
     *
     * @param task     任务状态（须为 CONCLUDED，由调用方的路由保证）
     * @param question 本轮用户消息
     * @param findings 生效主张的惰性来源（判定通过才取，避免重跑轮多付一次查询）
     * @return 直答产物；empty = 本轮该走重跑路径
     */
    public Optional<OpsRunner.OpsAnswer> tryAnswer(AgentTaskState task, String question,
            Supplier<List<AgentFinding>> findings) {
        if (task == null || task.status() != AgentTaskState.Status.CONCLUDED) {
            return Optional.empty();
        }
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            return Optional.empty();
        }
        String summary = task.summary() == null ? "" : task.summary().trim();
        if (summary.isBlank()) {
            return Optional.empty();   // 没有结论可解读（异常路径落的 CONCLUDED）→ 重跑
        }
        String answerBody = promptBody == null ? null : promptBody.apply(ANSWER_ASSET);
        if (model == null || answerBody == null || answerBody.isBlank()) {
            return Optional.empty();   // QA 不可用 → 重跑（现状行为）
        }
        // QA 轮轨迹（classify [→ retrieve] → answer；判 rerun 时随 empty 一起丢弃——
        // 重跑路径有自己的完整轨迹）。workflowId 用 task_qa 标记（非框架路径），
        // promptHash 留空——OPS 指纹盖不住 QA 资产，宁缺勿假
        TraceView trace = new TraceView(AgentCatalog.OPS.id(), QA_WORKFLOW_MARKER, null);
        Verdict verdict = classify(q, summary, trace);
        if (verdict == null || (!verdict.answer() && !verdict.retrieve())) {
            return Optional.empty();
        }
        String retrievalText = null;
        if (verdict.retrieve()) {
            if (retriever == null || verdict.query().isBlank()) {
                return Optional.empty();   // 检索通道缺席 / 协议不完整 → 重跑降级（不劣化）
            }
            try {
                long t = System.currentTimeMillis();
                retrievalText = retriever.retrieve(verdict.query());
                trace.stepDetail("task_qa_retrieve", "针对性检索（分类时改写的检索问句，不进 SOP）",
                        verdict.query(), preview(retrievalText), t, null);
            } catch (Exception e) {
                log.warn("[ops-task-qa] 检索失败（落回重跑路径）：{}", e.getMessage());
                return Optional.empty();
            }
        }
        try {
            long t = System.currentTimeMillis();
            List<AgentFinding> active = findings.get();
            String text = model.ask(answerBody, userContent(summary, active, task.slots(), q, retrievalText));
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            trace.stepDetail("task_qa_answer", "任务内直答（读结论全文/主张/槽位投影"
                            + (retrievalText == null ? "" : "/检索命中") + "，不进 SOP、不开 attempt）",
                    "结论 " + summary.length() + " 字 / 主张 " + active.size() + " 条 / 槽位 "
                            + (task.slots() == null ? 0 : task.slots().size()) + " 项",
                    preview(text), t, null);
            trace.incrementLlmCall();
            log.info("[ops-task-qa] 追问走任务内{}（attempt 不变）：{}", retrievalText == null ? "直答" : "检索直答", preview(q));
            return Optional.of(new OpsRunner.OpsAnswer(OutcomeKind.DIRECT, text.trim(), trace));
        } catch (Exception e) {
            log.warn("[ops-task-qa] 直答失败（落回重跑路径）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 分类裁决：三态 label + retrieve 时的检索问句（分类时同步改写，省一次调用）。 */
    private record Verdict(String label, String query) {

        boolean answer() {
            return "answer".equals(label);
        }

        boolean retrieve() {
            return "retrieve".equals(label);
        }
    }

    /**
     * 追问是否是「对结论的提问 / 索取」。
     *
     * <p><b>语义判断全交模型</b>（2026-09-20 第三轮修正）：确定性词表在开放语言空间里
     * 覆盖度不够，真机首日即踩坑（「给我一个修复后的报文」被词「修复」误判成行动指令，
     * 复读整份结论）——踩坑加词是打地鼠。保留的唯一确定性规则是<b>协议文本</b>
     * （{@code #decision:/#override:} 是机器协议不是自然语言，不耗模型）；其余一律单次
     * LLM 分类，模型不可用 / 输出不可解析按 rerun 降级（不劣化）。</p>
     */
    private Verdict classify(String question, String summary, TraceView trace) {
        if (HumanResponse.isProtocolText(question)) {
            return null;               // #decision:/#override: 是纠错/确认载荷，走恢复通道
        }
        return classifyByModel(question, summary, trace);
    }

    /**
     * LLM 三态分类（qa-classify.md）：answer（结论素材直答）/ retrieve（一次针对性检索）/
     * rerun（完整重跑）。结论摘要随行——判「用户要的东西是在结论里、文档里还是日志里」
     * 需要看见结论，只看消息原文判不准索取句。任何失败按 rerun（null）。
     */
    private Verdict classifyByModel(String question, String summary, TraceView trace) {
        String base = promptBody == null ? null : promptBody.apply(CLASSIFY_ASSET);
        if (model == null || base == null || base.isBlank()) {
            return null;
        }
        try {
            String digest = summary.length() > CLASSIFY_SUMMARY_MAX
                    ? summary.substring(0, CLASSIFY_SUMMARY_MAX) + "…" : summary;
            long t = System.currentTimeMillis();
            Map<String, Object> parsed = parseOneLineJson(model.ask(base,
                    "## 上一轮诊断结论（摘要）\n" + digest + "\n\n## 用户消息\n" + question));
            String label = parsed == null ? ""
                    : String.valueOf(parsed.getOrDefault("label", "")).trim().toLowerCase();
            String query = parsed == null ? "" : String.valueOf(parsed.getOrDefault("query", ""));
            if ("null".equalsIgnoreCase(query)) {
                query = "";
            }
            // 分类步如实入轨迹（含降级形态）：判 rerun 时轨迹随 empty 丢弃，不会泄漏到重跑轮
            trace.stepDetail("task_qa_classify", "追问分类（三态：直答/针对性检索/重跑，结论摘要随行）",
                    preview(question),
                    (label.isEmpty() ? "label=rerun（输出不可解析，降级）" : "label=" + label)
                            + (query.isBlank() ? "" : "｜query=" + query)
                            + (parsed != null && !String.valueOf(parsed.getOrDefault("reason", "")).isBlank()
                                    ? "｜" + parsed.get("reason") : ""),
                    t, null);
            trace.incrementLlmCall();
            return new Verdict(label, query.trim());
        } catch (Exception e) {
            log.warn("[ops-task-qa] 追问分类失败（按重跑处理）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 直答的 user 内容：结论全文 + 生效主张 + 关键槽位 + （retrieve 分支的）检索命中 + 问题。
     *
     * <p>主张行格式与 {@code OpsRunner.renderFindings} 一致（{@code [#n] [kind] claim}、
     * 同截断同上限）——编号是用户回指「第几条」的锚点，两边必须同源同序。</p>
     */
    private static String userContent(String summary, List<AgentFinding> findings,
            Map<String, String> slots, String question, String retrievalText) {
        StringBuilder sb = new StringBuilder("## 上一轮诊断结论（全文）\n");
        if (summary.length() > SUMMARY_MAX) {
            sb.append(summary, 0, SUMMARY_MAX)
                    .append("…（结论过长已截断，共 ").append(summary.length()).append(" 字符）\n");
        } else {
            sb.append(summary).append('\n');
        }
        sb.append("\n## 当前生效主张\n").append(renderClaims(findings));
        sb.append("\n## 已确认的关键信息\n").append(renderSlots(slots));
        if (retrievalText != null && !retrievalText.isBlank()) {
            sb.append("\n## 知识库检索命中（为本轮追问针对性检索）\n");
            if (retrievalText.length() > RETRIEVAL_MAX) {
                sb.append(retrievalText, 0, RETRIEVAL_MAX).append("…（检索结果过长已截断）\n");
            } else {
                sb.append(retrievalText).append('\n');
            }
        }
        sb.append("\n## 用户的问题\n").append(question);
        return sb.toString();
    }

    /** 主张渲染（QA 口径空列表显示「（无）」；行格式与 OpsRunner.renderFindings 同源同序——FindingsText）。 */
    private static String renderClaims(List<AgentFinding> findings) {
        String rendered = FindingsText.renderNumbered(findings, FINDING_MAX, FINDING_CLAIM_MAX);
        return rendered.isEmpty() ? "（无）" : rendered;
    }

    /** 关键槽位投影（只挑定位用得上的四项，其余槽位与解读无关）。 */
    private static String renderSlots(Map<String, String> slots) {
        if (slots == null || slots.isEmpty()) {
            return "（无）";
        }
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("environment", "环境");
        labels.put("interface", "接口");
        labels.put("time", "时间");
        labels.put("trace_id", "traceId");
        StringBuilder sb = new StringBuilder();
        labels.forEach((name, label) -> {
            String value = slots.getOrDefault(name, "");
            if (!value.isBlank()) {
                sb.append(label).append("：").append(value).append('\n');
            }
        });
        return sb.isEmpty() ? "（无）" : sb.toString();
    }

    /** 一行 JSON 解析（花括号截取，与 ActExecutor/ReplanExecutor 同口径）。 */
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

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }
}
