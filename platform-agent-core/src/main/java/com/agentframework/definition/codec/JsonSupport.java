package com.agentframework.definition.codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 编解码：只支持标准 JSON 子集（对象 / 数组 / 字符串 / 数字 / 布尔 / null）。
 *
 * <p>刻意不引入第三方依赖：框架承诺零第三方运行时依赖，声明式定义所需的 JSON 能力由此处提供；
 * 需要 YAML 等其它格式时通过 {@link DefinitionCodec} 扩展点接入。</p>
 */
public final class JsonSupport {

    private JsonSupport() {
    }

    /**
     * 解析 JSON 文本。
     *
     * @param text JSON 文本
     * @return Map / List / String / Long / Double / Boolean / null
     * @throws DefinitionDocumentException 语法错误时抛出
     */
    public static Object parse(String text) {
        if (text == null || text.isBlank()) {
            throw new DefinitionDocumentException("DEFINITION_PARSE_ERROR", "JSON 文本为空");
        }
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.expectEnd();
        return value;
    }

    /**
     * 解析为 JSON 对象。
     *
     * @param text JSON 文本
     * @return 键值映射
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) {
            throw new DefinitionDocumentException("DEFINITION_PARSE_ERROR", "JSON 根节点必须是对象");
        }
        return (Map<String, Object>) value;
    }

    /**
     * 序列化为 JSON 文本。
     *
     * @param value 待序列化的值
     * @return JSON 文本
     */
    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    /**
     * 写入单个值。
     *
     * @param builder 输出缓冲
     * @param value   值
     */
    private static void writeValue(StringBuilder builder, Object value) {
        switch (value) {
            case null -> builder.append("null");
            case String text -> writeString(builder, text);
            case Boolean bool -> builder.append(bool);
            case Number number -> builder.append(number);
            case Map<?, ?> map -> {
                builder.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    writeString(builder, String.valueOf(entry.getKey()));
                    builder.append(':');
                    writeValue(builder, entry.getValue());
                }
                builder.append('}');
            }
            case Iterable<?> iterable -> {
                builder.append('[');
                boolean first = true;
                for (Object element : iterable) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    writeValue(builder, element);
                }
                builder.append(']');
            }
            default -> writeString(builder, String.valueOf(value));
        }
    }

    /**
     * 写入字符串字面量。
     *
     * @param builder 输出缓冲
     * @param text    文本
     */
    private static void writeString(StringBuilder builder, String text) {
        builder.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

    /** 递归下降解析器。 */
    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw error("意外的文本结束");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        void expectEnd() {
            skipWhitespace();
            if (pos < text.length()) {
                throw error("根节点之后存在多余内容");
            }
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return result;
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return result;
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c != '\\') {
                    builder.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw error("转义序列不完整");
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("Unicode 转义不完整");
                        }
                        builder.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("不支持的转义字符：\\" + escape);
                }
            }
            throw error("字符串未闭合");
        }

        private Boolean parseBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw error("非法的布尔字面量");
        }

        private Object parseNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw error("非法的 null 字面量");
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String token = text.substring(start, pos);
            if (token.isEmpty()) {
                throw error("非法的数字字面量");
            }
            try {
                if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw error("非法的数字字面量：" + token);
            }
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("意外的文本结束");
            }
            return text.charAt(pos);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw error("期望字符 '" + expected + "'");
            }
            pos++;
        }

        private DefinitionDocumentException error(String message) {
            return new DefinitionDocumentException("DEFINITION_PARSE_ERROR",
                    message + "（位置 " + pos + "）");
        }
    }
}
