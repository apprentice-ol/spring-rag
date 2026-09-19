package com.agentframework.definition.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位契约：工作流读写到的全部槽位声明。
 *
 * @param slots 槽位名到声明的映射
 */
public record SlotsSchema(Map<String, SlotSpec> slots) {

    public SlotsSchema {
        slots = slots == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(slots));
    }

    /** @return 空 schema */
    public static SlotsSchema empty() {
        return new SlotsSchema(null);
    }

    /**
     * @param specs 槽位声明
     * @return 由声明构造的 schema
     */
    public static SlotsSchema of(SlotSpec... specs) {
        Map<String, SlotSpec> map = new LinkedHashMap<>();
        for (SlotSpec spec : specs) {
            map.put(spec.name(), spec);
        }
        return new SlotsSchema(map);
    }

    /**
     * @param name 槽位名
     * @return 对应声明，不存在返回 null
     */
    public SlotSpec spec(String name) {
        return slots.get(name);
    }

    /**
     * @param spec 槽位声明
     * @return 追加声明后的 schema
     */
    public SlotsSchema with(SlotSpec spec) {
        Map<String, SlotSpec> merged = new LinkedHashMap<>(slots);
        merged.put(spec.name(), spec);
        return new SlotsSchema(merged);
    }

    /**
     * 校验槽位值：报告必填缺失、未声明键与类型不符。
     *
     * @param values 待校验的槽位值
     * @return 问题列表，为空表示通过
     */
    public List<String> validate(Map<String, Object> values) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> safe = values == null ? Map.of() : values;
        for (SlotSpec spec : slots.values()) {
            Object value = safe.get(spec.name());
            if (value == null) {
                if (spec.required() && spec.defaultValue() == null) {
                    problems.add("missing required slot '" + spec.name() + "'");
                }
                continue;
            }
            if (!spec.type().matches(value)) {
                problems.add("slot '" + spec.name() + "' expects " + spec.type() + " but got "
                        + value.getClass().getSimpleName());
            }
        }
        for (String key : safe.keySet()) {
            if (!slots.containsKey(key)) {
                problems.add("slot '" + key + "' is not declared in the workflow schema");
            }
        }
        return problems;
    }

    /**
     * 注入缺省值，返回可直接用于初始化运行时的可变映射。
     *
     * @param values 调用方传入的初始值
     * @return 合并缺省值后的槽位映射
     */
    public Map<String, Object> applyDefaults(Map<String, Object> values) {
        Map<String, Object> seeded = new LinkedHashMap<>();
        for (SlotSpec spec : slots.values()) {
            if (spec.defaultValue() != null) {
                seeded.put(spec.name(), spec.defaultValue());
            }
        }
        if (values != null) {
            seeded.putAll(values);
        }
        return seeded;
    }
}
