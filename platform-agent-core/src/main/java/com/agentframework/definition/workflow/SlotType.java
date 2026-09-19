package com.agentframework.definition.workflow;

import java.util.Collection;
import java.util.Map;

/**
 * 槽位声明类型，{@link #ANY} 表示关闭类型检查。
 */
public enum SlotType {
    STRING,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY,
    ANY;

    /**
     * 判断实际值是否符合该声明类型。
     *
     * @param value 槽位值，null 视为通过
     * @return 符合返回 true
     */
    public boolean matches(Object value) {
        if (value == null) {
            return true;
        }
        return switch (this) {
            case STRING -> value instanceof CharSequence;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case OBJECT -> value instanceof Map;
            case ARRAY -> value instanceof Collection || value.getClass().isArray();
            case ANY -> true;
        };
    }
}
