package com.agentframework.definition.workflow;

/**
 * 单个槽位的 schema 声明。
 *
 * @param name         槽位名
 * @param type         声明类型
 * @param required     是否必填
 * @param defaultValue 缺省值，非空时即使必填也可缺省注入
 * @param scope        作用域，缺省为 SESSION
 */
public record SlotSpec(String name, SlotType type, boolean required, Object defaultValue, SlotScope scope) {

    public SlotSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("slot name is required");
        }
        type = type == null ? SlotType.ANY : type;
        scope = scope == null ? SlotScope.SESSION : scope;
    }

    /**
     * @param name 槽位名
     * @param type 声明类型
     * @return 可选槽位声明
     */
    public static SlotSpec of(String name, SlotType type) {
        return new SlotSpec(name, type, false, null, null);
    }

    /**
     * @param name 槽位名
     * @param type 声明类型
     * @return 必填槽位声明
     */
    public static SlotSpec required(String name, SlotType type) {
        return new SlotSpec(name, type, true, null, null);
    }

    /**
     * @param name         槽位名
     * @param type         声明类型
     * @param defaultValue 缺省值
     * @return 带缺省值的槽位声明
     */
    public static SlotSpec of(String name, SlotType type, Object defaultValue) {
        return new SlotSpec(name, type, false, defaultValue, null);
    }

    /**
     * @param scope 作用域
     * @return 覆盖作用域后的声明
     */
    public SlotSpec withScope(SlotScope scope) {
        return new SlotSpec(name, type, required, defaultValue, scope);
    }

    /** @return 标记为必填后的声明 */
    public SlotSpec asRequired() {
        return new SlotSpec(name, type, true, defaultValue, scope);
    }
}
