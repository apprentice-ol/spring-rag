package com.jjx.customer.platform.business.ops.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 零依赖轻量 JSON Schema 子集校验（O8 产出护栏的确定性内核），逐字对齐参考实现
 * {@code guard/JsonSchemaValidator}：支持 type（含数组形式）、enum、const、required、
 * properties、additionalProperties=false、items、minItems/maxItems、minLength/maxLength、
 * pattern、minimum/maximum；不支持的关键字忽略（不误报）。
 *
 * <p>错误消息模板与参考一致（路径根为 {@code $}，字段 {@code $.field}，数组元素 {@code $.field[i]}），
 * 逐条即回喂模型的修正指引。</p>
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {
    }

    /**
     * @param schema  JSON Schema 节点
     * @param payload 待校验的报文节点
     * @return 错误列表（空 = 通过）
     */
    public static List<String> validate(JsonNode schema, JsonNode payload) {
        List<String> errors = new ArrayList<>();
        validate(schema, payload, "$", errors);
        return errors;
    }

    private static void validate(JsonNode schema, JsonNode node, String path, List<String> errors) {
        if (schema == null || schema.isNull() || !schema.isObject()) {
            return;
        }
        checkType(schema, node, path, errors);
        checkEnumAndConst(schema, node, path, errors);
        if (node != null && node.isObject()) {
            checkObject(schema, node, path, errors);
        }
        if (node != null && node.isArray()) {
            checkArray(schema, node, path, errors);
        }
        checkStringRules(schema, node, path, errors);
        checkNumberRules(schema, node, path, errors);
    }

    private static void checkType(JsonNode schema, JsonNode node, String path, List<String> errors) {
        JsonNode typeNode = schema.get("type");
        if (typeNode == null || node == null) {
            return;
        }
        List<String> expected = new ArrayList<>();
        if (typeNode.isArray()) {
            typeNode.forEach(t -> expected.add(t.asText()));
        } else {
            expected.add(typeNode.asText());
        }
        String actual = typeOf(node);
        if (!expected.contains(actual)) {
            errors.add(path + " 类型不符：期望 " + String.join("|", expected) + "，实际 " + actual);
        }
    }

    private static void checkEnumAndConst(JsonNode schema, JsonNode node, String path, List<String> errors) {
        if (node == null) {
            return;
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            boolean matched = false;
            for (JsonNode candidate : enumNode) {
                if (candidate.equals(node)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(path + " 取值不在枚举内：" + node);
            }
        }
        JsonNode constNode = schema.get("const");
        if (constNode != null && !constNode.equals(node)) {
            errors.add(path + " 不等于 const：" + constNode);
        }
    }

    private static void checkObject(JsonNode schema, JsonNode node, String path, List<String> errors) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                if (!node.has(field.asText())) {
                    errors.add(path + " 缺少必填字段：" + field.asText());
                }
            }
        }
        JsonNode properties = schema.get("properties");
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> field = it.next();
            String childPath = path + "." + field.getKey();
            if (properties != null && properties.isObject() && properties.has(field.getKey())) {
                validate(properties.get(field.getKey()), field.getValue(), childPath, errors);
            } else if (properties != null && properties.isObject()
                    && schema.has("additionalProperties")
                    && schema.get("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean()) {
                errors.add(path + " 含未声明字段（additionalProperties=false）：" + field.getKey());
            }
        }
    }

    private static void checkArray(JsonNode schema, JsonNode node, String path, List<String> errors) {
        int size = node.size();
        JsonNode minItems = schema.get("minItems");
        if (minItems != null && minItems.isInt() && size < minItems.asInt()) {
            errors.add(path + " 元素过少（minItems=" + minItems.asInt() + "，实际 " + size + "）");
        }
        JsonNode maxItems = schema.get("maxItems");
        if (maxItems != null && maxItems.isInt() && size > maxItems.asInt()) {
            errors.add(path + " 元素过多（maxItems=" + maxItems.asInt() + "，实际 " + size + "）");
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            for (int i = 0; i < size; i++) {
                validate(items, node.get(i), path + "[" + i + "]", errors);
            }
        }
    }

    private static void checkStringRules(JsonNode schema, JsonNode node, String path, List<String> errors) {
        if (node == null || !node.isTextual()) {
            return;
        }
        String text = node.asText();
        int length = text.length();
        JsonNode minLength = schema.get("minLength");
        if (minLength != null && minLength.isInt() && length < minLength.asInt()) {
            errors.add(path + " 长度不足（minLength=" + minLength.asInt() + "，实际 " + length + "）");
        }
        JsonNode maxLength = schema.get("maxLength");
        if (maxLength != null && maxLength.isInt() && length > maxLength.asInt()) {
            errors.add(path + " 长度超限（maxLength=" + maxLength.asInt() + "，实际 " + length + "）");
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null && pattern.isTextual() && !Pattern.compile(pattern.asText()).matcher(text).find()) {
            errors.add(path + " 不匹配 pattern：" + pattern.asText());
        }
    }

    private static void checkNumberRules(JsonNode schema, JsonNode node, String path, List<String> errors) {
        if (node == null || !node.isNumber()) {
            return;
        }
        double value = node.asDouble();
        JsonNode minimum = schema.get("minimum");
        if (minimum != null && minimum.isNumber() && value < minimum.asDouble()) {
            errors.add(path + " 小于 minimum：" + minimum.asText());
        }
        JsonNode maximum = schema.get("maximum");
        if (maximum != null && maximum.isNumber() && value > maximum.asDouble()) {
            errors.add(path + " 大于 maximum：" + maximum.asText());
        }
    }

    private static String typeOf(JsonNode node) {
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNull()) {
            return "null";
        }
        return "unknown";
    }
}
