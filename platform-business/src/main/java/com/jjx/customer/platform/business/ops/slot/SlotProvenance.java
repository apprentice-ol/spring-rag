package com.jjx.customer.platform.business.ops.slot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位来源登记（人在环中 P3 的 provenance 状态机）：每个**非用户直供**的槽值都记一条
 * 「谁填的、凭什么」，用户据此在卡片上纠错，结论据此交代证据链。
 *
 * <p>来源取值（状态流转：前四种机器取值 → 用户推翻即转 {@link #SOURCE_OVERRIDDEN}）：</p>
 * <ul>
 *   <li>{@link #SOURCE_RULE}：确定性提取（用户原文里的相对时间换算、接口路径正则、接口清单唯一匹配）</li>
 *   <li>{@link #SOURCE_DEFAULT}：目录声明的缺省值（如 time 的「最近30分钟」）</li>
 *   <li>{@link #SOURCE_INFERRED}：LLM 高置信推断</li>
 *   <li>{@link #SOURCE_LOG}：日志反查命中</li>
 *   <li>{@link #SOURCE_OVERRIDDEN}：用户明确推翻（终态：此值已是用户确认值，不再是"机器猜的"）</li>
 * </ul>
 *
 * <p>存储形态沿用 {@code inferred_slots} 槽位的 JSON 数组
 * （{@code [{slot,value,method,evidence}]}，method 即来源），与旧格式向后兼容；
 * 交付层经 {@link #parse} 转结构化投影给前端（角标 + 点选纠正）——
 * 来源键到中文角标文案的映射在前端（{@code ChatView.PROVENANCE_LABELS}），后端不复制一份展示口径。</p>
 */
public final class SlotProvenance {

    /** 规则提取（确定性，非猜测）。 */
    public static final String SOURCE_RULE = "rule";
    /** 目录缺省值。 */
    public static final String SOURCE_DEFAULT = "default";
    /** LLM 推断。 */
    public static final String SOURCE_INFERRED = "llm";
    /** 日志反查。 */
    public static final String SOURCE_LOG = "log_query";
    /** 用户推翻（终态）。 */
    public static final String SOURCE_OVERRIDDEN = "user_override";

    /** 键名（与旧 {@code inferred_slots} 元素逐字一致）。 */
    private static final String KEY_SLOT = "slot";
    private static final String KEY_VALUE = "value";
    private static final String KEY_METHOD = "method";
    private static final String KEY_EVIDENCE = "evidence";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SlotProvenance() {
    }

    /**
     * 一条来源记录。
     *
     * @param slot     槽位名
     * @param value    当时写入的值（截断展示用，不用于回填）
     * @param source   来源（见本类常量）
     * @param evidence 依据说明（面向用户，如「confidence=0.82，用户说线上问题」）
     */
    public record Entry(String slot, String value, String source, String evidence) {
    }

    /** @return 单条记录（供执行器构造 provenance 列表） */
    public static Map<String, String> entry(String slot, String value, String source, String evidence) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put(KEY_SLOT, slot);
        item.put(KEY_VALUE, value);
        item.put(KEY_METHOD, source);
        item.put(KEY_EVIDENCE, evidence);
        return item;
    }

    /**
     * 结构化解析（交付层投影 / 读取判定用；解析失败按空表——脏数据不该阻断对话）。
     *
     * @param json {@code inferred_slots} 槽位的 JSON 文本
     * @return 记录列表（保序）
     */
    public static List<Entry> parse(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            List<Entry> entries = new ArrayList<>(raw.size());
            for (Map<String, Object> item : raw) {
                entries.add(new Entry(text(item.get(KEY_SLOT)), text(item.get(KEY_VALUE)),
                        text(item.get(KEY_METHOD)), text(item.get(KEY_EVIDENCE))));
            }
            return List.copyOf(entries);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 记一条来源（同槽位覆盖：状态机只保留该槽的最新来源，避免"推断 → 用户推翻"后两条并存）。
     *
     * @param json    现有 {@code inferred_slots} JSON（可空）
     * @param slot    槽位名
     * @param value   值
     * @param source  来源
     * @param evidence 依据说明
     * @return 更新后的 JSON（序列化失败按原样返回）
     */
    public static String upsert(String json, String slot, String value, String source, String evidence) {
        List<Map<String, String>> items = new ArrayList<>();
        for (Entry entry : parse(json)) {
            if (!entry.slot().equals(slot)) {
                items.add(entry(entry.slot(), entry.value(), entry.source(), entry.evidence()));
            }
        }
        items.add(entry(slot, value, source, evidence));
        try {
            return MAPPER.writeValueAsString(items);
        } catch (Exception e) {
            return json == null ? "[]" : json;
        }
    }

    /**
     * 批量记来源（用户一轮推翻多个槽位时用；输入顺序即记录顺序）。
     *
     * @param json   现有 JSON（可空）
     * @param values 槽位 → 值
     * @param source 来源
     * @param evidence 依据说明
     * @return 更新后的 JSON
     */
    public static String upsertAll(String json, Map<String, String> values, String source, String evidence) {
        String current = json;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            current = upsert(current, entry.getKey(), entry.getValue(), source, evidence);
        }
        return current;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
