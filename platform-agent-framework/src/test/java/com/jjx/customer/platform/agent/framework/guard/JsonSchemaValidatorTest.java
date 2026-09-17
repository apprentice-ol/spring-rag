package com.jjx.customer.platform.agent.framework.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String schema, String payload) {
        try {
            JsonNode schemaNode = MAPPER.readTree(schema);
            JsonNode payloadNode = MAPPER.readTree(payload);
            return JsonSchemaValidator.validate(schemaNode, payloadNode);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 合法对象_通过() {
        String schema = """
                {"type":"object","required":["code"],"properties":{
                  "code":{"type":"string","pattern":"^[A-Z]{2}$"},"retry":{"type":"integer","minimum":0}}}""";

        assertTrue(validate(schema, "{\"code\":\"OK\",\"retry\":2}").isEmpty());
    }

    @Test
    void 缺必填字段_报错() {
        List<String> errors = validate("{\"type\":\"object\",\"required\":[\"code\"]}", "{}");

        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("code"));
    }

    @Test
    void 类型不符_报错() {
        List<String> errors = validate("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}",
                "{\"n\":\"文本\"}");

        assertFalse(errors.isEmpty());
    }

    @Test
    void 数组元素边界_报错() {
        String schema = """
                {"type":"object","properties":{"items":{"type":"array","minItems":2,
                  "items":{"type":"string","enum":["a","b"]}}}}""";

        assertFalse(validate(schema, "{\"items\":[\"a\"]}").isEmpty());
    }

    @Test
    void 未声明字段_在additionalProperties关闭时报错() {
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}"
                + ",\"additionalProperties\":false}";

        assertFalse(validate(schema, "{\"a\":\"x\",\"b\":\"y\"}").isEmpty());
    }
}
