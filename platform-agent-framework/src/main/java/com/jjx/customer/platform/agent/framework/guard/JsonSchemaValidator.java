package com.jjx.customer.platform.agent.framework.guard;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 轻量 JSON Schema 校验（零新依赖）：覆盖阶段产出护栏常用子集。
 *
 * <p>支持：type（含数组形式）、enum、const、required、properties、additionalProperties=false、
 * items、minItems/maxItems、minLength/maxLength、pattern、minimum/maximum。
 * 不支持的关键字忽略（不误报）。</p>
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {
    }

    /** 校验；返回错误清单（空 = 通过）。 */
    public static List<String> validate(JsonNode schema, JsonNode node) {
        List<String> errors = new ArrayList<>();
        validateNode(schema, node, "$", errors);
        return errors;
    }

    private static void validateNode(JsonNode schema, JsonNode node, String path, List<String> errors) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        checkType(schema.get("type"), node, path, errors);
        checkEnum(schema.get("enum"), node, path, errors);
        checkConst(schema.get("const"), node, path, errors);

        if (node != null && node.isObject()) {
            validateObject(schema, node, path, errors);
        }
        if (node != null && node.isArray()) {
            validateArray(schema, node, path, errors);
        }
        if (node != null && node.isTextual()) {
            validateString(schema, node, path, errors);
        }
        if (node != null && node.isNumber()) {
            validateNumber(schema, node, path, errors);
        }
    }

    private static void checkType(JsonNode type, JsonNode node, String path, List<String> errors) {
        if (type == null || node == null) {
            return;
        }
        List<String> expected = new ArrayList<>();
        if (type.isArray()) {
            type.forEach(t -> expected.add(t.asText()));
        } else {
            expected.add(type.asText());
        }
        boolean matched = expected.stream().anyMatch(t -> matchesType(t, node));
        if (!matched) {
            errors.add(path + " 类型不符：期望 " + expected + "，实际 " + node.getNodeType());
        }
    }

    private static boolean matchesType(String type, JsonNode node) {
        return switch (type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "integer" -> node.isIntegralNumber();
            case "number" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            default -> true;
        };
    }

    private static void checkEnum(JsonNode enumNode, JsonNode node, String path, List<String> errors) {
        if (enumNode == null || !enumNode.isArray() || node == null) {
            return;
        }
        boolean hit = false;
        for (JsonNode candidate : enumNode) {
            if (candidate.equals(node)) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            errors.add(path + " 取值不在枚举内：" + node);
        }
    }

    private static void checkConst(JsonNode constNode, JsonNode node, String path, List<String> errors) {
        if (constNode == null || constNode.isMissingNode() || node == null) {
            return;
        }
        if (!constNode.equals(node)) {
            errors.add(path + " 不等于 const：" + constNode);
        }
    }

    private static void validateObject(JsonNode schema, JsonNode node, String path, List<String> errors) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                if (!node.has(field.asText())) {
                    errors.add(path + " 缺少必填字段：" + field.asText());
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (node.has(name)) {
                    validateNode(properties.get(name), node.get(name), path + "." + name, errors);
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
            List<String> allowed = new ArrayList<>();
            if (properties != null) {
                properties.fieldNames().forEachRemaining(allowed::add);
            }
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!allowed.contains(name)) {
                    errors.add(path + " 含未声明字段（additionalProperties=false）：" + name);
                }
            }
        }
    }

    private static void validateArray(JsonNode schema, JsonNode node, String path, List<String> errors) {
        JsonNode items = schema.get("items");
        if (items != null) {
            for (int i = 0; i < node.size(); i++) {
                validateNode(items, node.get(i), path + "[" + i + "]", errors);
            }
        }
        checkBounds(schema, "minItems", node.size(), path, "元素过少", errors, true);
        checkBounds(schema, "maxItems", node.size(), path, "元素过多", errors, false);
    }

    private static void validateString(JsonNode schema, JsonNode node, String path, List<String> errors) {
        checkBounds(schema, "minLength", node.asText().length(), path, "长度不足", errors, true);
        checkBounds(schema, "maxLength", node.asText().length(), path, "长度超限", errors, false);
        JsonNode pattern = schema.get("pattern");
        if (pattern != null && pattern.isTextual() && !Pattern.compile(pattern.asText()).matcher(node.asText()).find()) {
            errors.add(path + " 不匹配 pattern：" + pattern.asText());
        }
    }

    private static void validateNumber(JsonNode schema, JsonNode node, String path, List<String> errors) {
        JsonNode minimum = schema.get("minimum");
        if (minimum != null && node.asDouble() < minimum.asDouble()) {
            errors.add(path + " 小于 minimum：" + minimum.asDouble());
        }
        JsonNode maximum = schema.get("maximum");
        if (maximum != null && node.asDouble() > maximum.asDouble()) {
            errors.add(path + " 大于 maximum：" + maximum.asDouble());
        }
    }

    private static void checkBounds(JsonNode schema, String keyword, int actual, String path,
                                    String label, List<String> errors, boolean lower) {
        JsonNode bound = schema.get(keyword);
        if (bound == null || !bound.isNumber()) {
            return;
        }
        int limit = bound.asInt();
        if (lower && actual < limit) {
            errors.add(path + " " + label + "（" + keyword + "=" + limit + "，实际 " + actual + "）");
        }
        if (!lower && actual > limit) {
            errors.add(path + " " + label + "（" + keyword + "=" + limit + "，实际 " + actual + "）");
        }
    }
}
