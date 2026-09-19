package com.agentframework.runtime.slot;

/**
 * 默认槽位编解码器：支持字符串、数字、布尔与 null，其它对象退化为 {@code toString()}。
 *
 * <p>需要完整保真时，请通过扩展注册表替换为 JSON / 二进制实现。</p>
 */
public final class SimpleSlotCodec implements SlotCodec {

    @Override
    public String encode(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public Object decode(String payload, String type) {
        if (payload == null || "null".equals(type)) {
            return null;
        }
        return switch (type == null ? "string" : type) {
            case "number" -> Double.valueOf(payload);
            case "boolean" -> Boolean.valueOf(payload);
            default -> payload;
        };
    }

    @Override
    public String typeOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        return "string";
    }
}
