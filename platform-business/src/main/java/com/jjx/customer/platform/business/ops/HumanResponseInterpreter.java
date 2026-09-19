package com.jjx.customer.platform.business.ops;

import com.agentframework.definition.workflow.HumanRequest;
import com.agentframework.definition.workflow.HumanResponse;
import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户回复解释器（人在环中 P2）：把用户对流程提问的自由文本回复解析成结构化决议
 * （{@link HumanResponse}：补槽 / 推翻推断 / 方向指令 / 选项命中 / 档位提示）。
 *
 * <p><b>为什么要它</b>：恢复通道原先只有「抽槽」一条语义路径——用户回复里的假设、领域知识、
 * 方向指令（「别再查日志了，直接给我报文」）在抽槽器眼里都不是槽值，被静默丢弃；
 * 槽位又是单向的，auto-resolve 推断出的值用户无从推翻。本类补齐这条语义通道。</p>
 *
 * <p><b>三条防线（最坏退化 = P1 现状，绝不比现状更差）</b>：</p>
 * <ol>
 *   <li><b>点选回传不耗模型</b>：{@code #decision:<value>} 是前端协议文本，无自然语言可解析，
 *       直接走 {@link HumanResponse#parse} 确定性路径；</li>
 *   <li><b>模型不可用 / 正文缺失 / 解析失败</b> → 同样退化到 {@code parse}（整句进 directive）；</li>
 *   <li><b>白名单 + 调和</b>：槽位名只收目录名与本次提问问到过的动态槽名（模型编造的名字一律丢弃）；
 *       {@code slots}/{@code overrides} 分桶误差由「当前值是否为空」在本地重新调和——
 *       模型分错桶不影响结果，只影响日志可读性。</li>
 * </ol>
 *
 * <p><b>软消费</b>：directive 不改图结构，进后续阶段的 think prompt 由模型自行消化
 * （硬控制留待真实 badcase，见 plan/2026-09-19-human-in-the-loop.md §七）。</p>
 */
public class HumanResponseInterpreter {

    private static final Logger log = LoggerFactory.getLogger(HumanResponseInterpreter.class);

    /** 解释器 prompt 资产 key（正文 = prompts/workflow/ops_diagnose_v2/human-response.md）。 */
    public static final String ASSET = "workflow/ops_diagnose_v2/human-response";

    /** 自主档位提示槽位名（用户回复里顺带调的档；P3 的 AutonomyPolicy 消费）。 */
    public static final String AUTONOMY_HINT_SLOT = "autonomy_hint";

    /** 槽值长度上限（模型偶发把整段日志塞进槽位，截断防上下文膨胀）。 */
    private static final int MAX_VALUE_LENGTH = 2000;

    /** 已确认槽位回显的单值长度上限。 */
    private static final int MAX_ECHO_LENGTH = 200;

    /**
     * 「模型判空」时保留原文兜底的最短长度：过于简短的应承（"好的"）视为无信息，
     * 交调用方按「没收到新信息」处理；够长的文本宁可整句进 directive（P1 语义）也不丢。
     */
    private static final int MIN_FALLBACK_LENGTH = 8;

    private final SingleTurnModel model;

    private final ObjectMapper objectMapper;

    /** 解释器正文来源（绑定包覆盖优先，classpath 兜底；null 时只走确定性路径） */
    private final Function<String, String> promptBody;

    /**
     * @param model        轻量问答模型（null = 不可用，只走确定性解析）
     * @param objectMapper JSON 解析
     * @param promptBody   prompt key → 正文
     */
    public HumanResponseInterpreter(SingleTurnModel model, ObjectMapper objectMapper,
            Function<String, String> promptBody) {
        this.model = model;
        this.objectMapper = objectMapper;
        this.promptBody = promptBody;
    }

    /** @return 模型是否可用（不可用时调用方不必构造解释器依赖的上下文） */
    public boolean available() {
        return model != null;
    }

    /**
     * 解释一次用户回复。
     *
     * @param reply   用户回复原文（空安全）
     * @param pending 上一轮发出的结构化请求（可为 null：无暂存 / 反序列化失败 / 存量会话）
     * @param slots   当前已确认槽位（判定「补空」还是「推翻已有」的依据）
     * @return 解释产物（决议 + 是否真的调了模型，供 llm_calls 预算计数）
     */
    public Outcome interpret(String reply, HumanRequest pending, Map<String, String> slots) {
        String text = reply == null ? "" : reply.trim();
        HumanResponse deterministic = HumanResponse.parse(text);
        if (text.isEmpty() || HumanResponse.isProtocolText(text)) {
            return new Outcome(deterministic, false); // 点选回传（#decision:/#override:）：不耗模型
        }
        String base = promptBody == null ? null : promptBody.apply(ASSET);
        if (model == null || base == null || base.isBlank()) {
            return new Outcome(deterministic, false);
        }
        boolean llmCalled = true;
        try {
            String raw = model.ask(base + "\n\n## 槽位目录\n" + OpsSlotCatalog.renderForExtraction(),
                    userPrompt(pending, slots, text));
            Map<String, Object> parsed = parseOneLineJson(raw);
            if (parsed == null) {
                return new Outcome(deterministic, llmCalled); // 模型输出不可解析：按 P1 兜底
            }
            HumanResponse interpreted = reconcile(parsed, pending, slots);
            if (interpreted.isEmpty() && text.length() >= MIN_FALLBACK_LENGTH) {
                // 模型判空但用户写了一段话：宁可整句进 directive（P1 语义）也不丢信息
                return new Outcome(deterministic, llmCalled);
            }
            return new Outcome(interpreted, llmCalled);
        } catch (Exception e) {
            log.warn("[ops-human] 回复解释失败（按原文兜底）：{}", e.getMessage());
            return new Outcome(deterministic, llmCalled);
        }
    }

    /**
     * 模型输出 → 决议（白名单过滤 + 与当前槽值调和）。
     *
     * <p>{@code slots} 与 {@code overrides} 合并处理：槽位当前为空 = 补缺（slotFills），
     * 当前有值且被改写 = 推翻（overrides）——模型分错桶不影响结果。</p>
     */
    private static HumanResponse reconcile(Map<String, Object> parsed, HumanRequest pending,
            Map<String, String> slots) {
        Set<String> allowed = allowedNames(pending);
        Map<String, String> claimed = new LinkedHashMap<>();
        claimed.putAll(slotMap(parsed.get("slots"), allowed));
        claimed.putAll(slotMap(parsed.get("overrides"), allowed)); // 同名声明显式纠正优先
        Map<String, String> fills = new LinkedHashMap<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        claimed.forEach((name, value) -> {
            String existing = slots == null ? null : slots.get(name);
            if (existing != null && !existing.isBlank()) {
                if (!existing.equals(value)) {
                    overrides.put(name, value);
                }
            } else {
                fills.put(name, value);
            }
        });
        return new HumanResponse(fills, overrides, textOf(parsed.get("directive")),
                decisionOf(parsed.get("decision"), pending), intOf(parsed.get("autonomy_hint")));
    }

    /** 合法槽位名：业务目录 ∪ 本次提问问到过的槽位（动态槽只在被问到的那一轮可回填）。 */
    private static Set<String> allowedNames(HumanRequest pending) {
        Set<String> names = new LinkedHashSet<>(OpsSlotCatalog.NAMES);
        if (pending != null) {
            pending.slots().forEach(ask -> names.add(ask.name()));
        }
        return names;
    }

    /** 模型给的槽位键值 → 白名单过滤 + 目录归一。 */
    private static Map<String, String> slotMap(Object raw, Set<String> allowed) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return out;
        }
        map.forEach((key, value) -> {
            String name = key == null ? "" : String.valueOf(key).trim();
            String text = textOf(value);
            if (!allowed.contains(name) || text.isBlank() || "null".equalsIgnoreCase(text)) {
                return;
            }
            out.put(name, normalize(name, text));
        });
        return out;
    }

    /** 目录归一（environment 之类有 normalizer 的槽位统一取值形态）；目录外/无法识别保留原值。 */
    private static String normalize(String name, String value) {
        String trimmed = value.trim();
        String capped = trimmed.length() > MAX_VALUE_LENGTH
                ? trimmed.substring(0, MAX_VALUE_LENGTH) : trimmed;
        return OpsSlotCatalog.ALL.stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .map(spec -> spec.normalize(capped))
                .orElse(capped);
    }

    /** 选项命中校验：只认本次提问声明过的选项值，模型自造的 decision 一律丢弃（防越权路由）。 */
    private static String decisionOf(Object value, HumanRequest pending) {
        String decision = textOf(value);
        if (decision.isBlank() || pending == null) {
            return null;
        }
        return pending.options().stream()
                .filter(choice -> choice.value().equalsIgnoreCase(decision))
                .map(HumanRequest.Choice::value)
                .findFirst()
                .orElse(null);
    }

    /** 组装 user prompt：本次提问 → 已确认槽位 → 用户回复原文。 */
    private String userPrompt(HumanRequest pending, Map<String, String> slots, String reply) {
        StringBuilder sb = new StringBuilder();
        if (pending == null) {
            sb.append("## 本次提问\n（无结构化提问记录，按用户的补充信息处理）\n");
        } else {
            sb.append("## 本次提问（用户正在回复它）\n").append(pending.kind()).append("：")
                    .append(pending.prompt().isBlank() ? "（无正文）" : pending.prompt()).append('\n');
            if (!pending.slots().isEmpty()) {
                sb.append("问到的问题：\n");
                for (HumanRequest.SlotAsk ask : pending.slots()) {
                    sb.append("- ").append(ask.name()).append("（")
                            .append(ask.label() == null || ask.label().isBlank() ? ask.name() : ask.label())
                            .append("）\n");
                }
            }
            if (!pending.options().isEmpty()) {
                sb.append("可选值（decision 只能从这里取）：\n");
                for (HumanRequest.Choice choice : pending.options()) {
                    sb.append("- ").append(choice.value()).append("：").append(choice.label()).append('\n');
                }
            }
            if (pending.context() != null && !pending.context().evidence().isEmpty()) {
                sb.append("提问时给出的已知信息：\n");
                pending.context().evidence().forEach(point -> sb.append("- ").append(point).append('\n'));
            }
        }
        sb.append("\n## 已确认槽位（多为推断/自动补全而来，用户可能纠正）\n");
        if (slots == null || slots.isEmpty()) {
            sb.append("（无）\n");
        } else {
            slots.forEach((name, value) -> sb.append(name).append(" = ")
                    .append(abbreviate(value)).append('\n'));
        }
        sb.append("\n## 用户回复原文\n").append(reply).append('\n');
        return sb.toString();
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

    private static Integer intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = textOf(value);
        try {
            return text.isBlank() ? null : Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textOf(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_ECHO_LENGTH ? oneLine : oneLine.substring(0, MAX_ECHO_LENGTH) + "…";
    }

    /**
     * 一次解释的产物。
     *
     * @param response  结构化决议（{@link HumanResponse#mergedFills()} 即本次可落槽的键值；
     *                  全字段空 = 没解析出可消费信息，调用方按「无新信息」处理）
     * @param llmCalled 是否真的调用了模型（计入 llm_calls 预算，与 O9 口径一致）
     */
    public record Outcome(HumanResponse response, boolean llmCalled) {
    }
}
