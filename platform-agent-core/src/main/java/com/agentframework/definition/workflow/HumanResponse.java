package com.agentframework.definition.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人机协作协议：人对 {@link HumanRequest} 的决议（恢复时交回引擎，各字段全可选、可并存）。
 *
 * <p><b>抗分类误差</b>：字段之间不互斥——分类不确定时填槽与方向指令可同时产出，
 * 下游 prompt 注入是软消费（LLM 自行消化），不存在「分错类就丢信息」；
 * 最坏退化 = 现状（整句当 user_clarify 文本）。</p>
 *
 * <p><b>两种产出方式</b>：业务侧的分类器（{@code HumanResponseInterpreter}，prompt 经
 * {@code workflow/ops_diagnose_v2/human-response} 资产注入）负责把自由文本解析成
 * slotFills/overrides/directive/decision；{@link #parse} 是<b>确定性兜底</b>——
 * 只识别点选回传的 {@code #decision:<value>} 前缀，其余全文进 directive。
 * 点选回传与分类器不可用时都走 {@code parse}，最坏退化 = 「整句当补充文本」。</p>
 *
 * @param slotFills   槽值补充（槽名 → 值）
 * @param overrides   推翻推断（槽名 → 新值；provenance 转 user_overridden）
 * @param directive   方向指令（软消费：进 replan/阶段 prompt，不直接改图）
 * @param decision    命中的 {@link HumanRequest.Choice#value}
 * @param autonomyHint 自主性档位调整提示（P3 消费）
 */
public record HumanResponse(Map<String, String> slotFills, Map<String, String> overrides,
                            String directive, String decision, Integer autonomyHint) {

    /** 点选回传前缀（前端决策卡片按钮固定发送 {@code #decision:<value>}）。 */
    public static final String DECISION_PREFIX = "#decision:";

    /**
     * 槽位纠正回传前缀（P3：前端「模型推断」角标点选纠正，{@code #override:<slot>=<value>}）。
     * 与 {@code #decision:} 同为确定性协议文本——点一下就该改写槽位，不必绕一趟模型。
     */
    public static final String OVERRIDE_PREFIX = "#override:";

    /**
     * 卡片一次提交（前端把卡片里选中的多项打包成 JSON 对象）：
     * {@code #fill:{"environment":"prod","time":"最近1小时"}}。与 {@code #override:} 同为确定性路径——
     * 空串值表示清空该项；补缺还是推翻由业务侧按当前值判定。
     */
    public static final String FILL_PREFIX = "#fill:";

    public HumanResponse {
        slotFills = slotFills == null ? Map.of() : Map.copyOf(slotFills);
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        directive = directive == null ? "" : directive;
    }

    /**
     * 确定性解析：自由文本 → 决议（业务侧分类器的兜底路径，也是前端协议文本的唯一解析入口）。
     *
     * <ul>
     *   <li>{@code #decision:terminate}（大小写不敏感、前后空白容忍）→ decision 字段，
     *       前缀后的剩余文本进 directive；</li>
     *   <li>{@code #override:environment=test} → overrides 字段（P3 角标点选纠正）；
     *       {@code #override:payload=}（空值）→ 清空该槽（机器猜错的值要能抹掉）；</li>
     *   <li>无前缀 → 全文进 directive（decision 为 null，由业务侧按「继续」缺省消化——
     *       人愿意打字就是在给方向，不是在终止）。</li>
     * </ul>
     *
     * @param text 用户回复原文（空安全）
     * @return 解析出的决议
     */
    public static HumanResponse parse(String text) {
        if (text == null || text.isBlank()) {
            return new HumanResponse(Map.of(), Map.of(), "", null, null);
        }
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith(DECISION_PREFIX)) {
            String rest = trimmed.substring(DECISION_PREFIX.length()).trim();
            int cut = rest.indexOf(' ');
            String decision = cut < 0 ? rest : rest.substring(0, cut);
            String directive = cut < 0 ? "" : rest.substring(cut + 1).trim();
            if (decision.isBlank()) {
                return new HumanResponse(Map.of(), Map.of(), trimmed, null, null);
            }
            return new HumanResponse(Map.of(), Map.of(), directive, decision.toLowerCase(), null);
        }
        if (lower.startsWith(FILL_PREFIX)) {
            Map<String, String> fills = parseFillBody(trimmed.substring(FILL_PREFIX.length()).trim());
            return fills == null
                    ? new HumanResponse(Map.of(), Map.of(), trimmed, null, null) // 格式不对按普通文本兜底
                    : new HumanResponse(fills, Map.of(), "", null, null);
        }
        if (lower.startsWith(OVERRIDE_PREFIX)) {
            String rest = trimmed.substring(OVERRIDE_PREFIX.length()).trim();
            int eq = rest.indexOf('=');
            if (eq > 0) {
                String slot = rest.substring(0, eq).trim();
                String value = rest.substring(eq + 1).trim();
                if (!slot.isEmpty()) {
                    // 空值 = 清空该槽（机器猜错的报文/关键数据要能抹掉，否则只能眼睁睁看着它进后续阶段）
                    return new HumanResponse(Map.of(), Map.of(slot, value), "", null, null);
                }
            }
            // 格式不对（缺槽名或值）→ 不作数，按普通文本走（不静默丢信息）
            return new HumanResponse(Map.of(), Map.of(), trimmed, null, null);
        }
        return new HumanResponse(Map.of(), Map.of(), trimmed, null, null);
    }

    /**
     * 是否为前端协议文本（点选回传）：无自然语言可解析，分类器不必为它耗一次模型。
     *
     * @param text 用户回复原文（空安全）
     * @return true = {@code #decision:} / {@code #override:} / {@code #fill:} 打头
     */
    public static boolean isProtocolText(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.trim().toLowerCase();
        return lower.startsWith(DECISION_PREFIX) || lower.startsWith(OVERRIDE_PREFIX)
                || lower.startsWith(FILL_PREFIX);
    }

    /**
     * 解析 {@code #fill:} 的 JSON 体（字符串→字符串；非对象/非法 JSON 返回 null）。
     *
     * <p>不引入 Jackson：手写小解析器够用且无依赖——只认扁平对象、值必须是字符串或空串。</p>
     *
     * @param body JSON 文本（可空）
     * @return 槽位键值；格式不对返回 null（调用方按普通文本兜底）
     */
    private static Map<String, String> parseFillBody(String body) {
        if (body == null || !body.startsWith("{") || !body.endsWith("}")) {
            return null;
        }
        Map<String, String> fills = new java.util.LinkedHashMap<>();
        int index = 1;
        int length = body.length() - 1;
        while (index < length) {
            int keyStart = body.indexOf('"', index);
            if (keyStart < 0 || keyStart >= length) {
                return null;
            }
            int keyEnd = body.indexOf('"', keyStart + 1);
            if (keyEnd < 0) {
                return null;
            }
            String key = body.substring(keyStart + 1, keyEnd);
            int colon = body.indexOf(':', keyEnd);
            if (colon < 0) {
                return null;
            }
            int valueStart = body.indexOf('"', colon);
            if (valueStart < 0 || valueStart >= length) {
                return null;
            }
            int valueEnd = -1;
            for (int i = valueStart + 1; i < length; i++) {
                char c = body.charAt(i);
                if (c == '\\') {
                    i++; // 跳过转义字符
                    continue;
                }
                if (c == '"') {
                    valueEnd = i;
                    break;
                }
            }
            if (valueEnd < 0) {
                return null;
            }
            String value = body.substring(valueStart + 1, valueEnd)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            fills.put(key, value);
            index = valueEnd + 1;
            int comma = body.indexOf(',', index);
            if (comma < 0 || comma >= length) {
                break;
            }
            index = comma + 1;
        }
        return fills.isEmpty() ? null : fills;
    }

    /** @return 是否带任何有效内容 */
    public boolean isEmpty() {
        return slotFills.isEmpty() && overrides.isEmpty() && directive.isBlank() && decision == null;
    }

    /** @return 合并视图（overrides 优先于 slotFills，P2 分类器产出后供落槽使用） */
    public Map<String, String> mergedFills() {
        Map<String, String> merged = new LinkedHashMap<>(slotFills);
        merged.putAll(overrides);
        return merged;
    }
}
