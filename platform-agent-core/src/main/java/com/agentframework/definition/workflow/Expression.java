package com.agentframework.definition.workflow;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 轻量表达式引擎（零依赖）：供边条件、条件节点与 {@code ${...}} 取值引用使用。
 *
 * <pre>
 *   slots.quality == 'good'
 *   slots.retries &lt; 3 &amp;&amp; !empty(slots.answer)
 *   matches(slots.city, '^Shang')
 *   'urgent' in slots.tags
 * </pre>
 *
 * <p>语法支持：字面量（字符串 / 数字 / 布尔 / null）、点号路径、比较运算
 * {@code == != > >= < <=}、逻辑运算 {@code && || !}、括号、集合字面量，
 * 比较关键字 {@code contains / startsWith / endsWith / matches / in}，
 * 以及函数 {@code exists, empty, size, len, lower, upper, str, num, bool, matches, contains}。
 * 空白表达式恒为 true。</p>
 */
public final class Expression {

    private static final Set<String> TWO_CHAR_OPS = Set.of("==", "!=", ">=", "<=", "&&", "||");
    private static final Set<String> COMPARISON_OPS =
            Set.of("==", "!=", ">", ">=", "<", "<=", "contains", "startsWith", "endsWith", "matches", "in");

    private Expression() {
    }

    /**
     * 求值并按真值语义返回布尔结果。
     *
     * @param expression 表达式文本，空白表示恒真
     * @param variables  变量表，例如 {@code {"slots": {...}}}
     * @return 条件是否成立
     * @throws ExpressionException 语法错误或使用未支持的运算符时抛出
     */
    public static boolean evaluate(String expression, Map<String, Object> variables) {
        return truthy(value(expression, variables));
    }

    /**
     * 求值并返回原始值，用于 {@code ${...}} 形式的取值引用。
     *
     * @param expression 表达式文本
     * @param variables  变量表
     * @return 表达式结果值
     * @throws ExpressionException 语法错误时抛出
     */
    public static Object value(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return Boolean.TRUE;
        }
        Parser parser = new Parser(tokenize(expression), variables == null ? Map.of() : variables, expression);
        Object result = parser.parse();
        parser.expectEnd();
        return result;
    }

    /**
     * 真值判定：null / 空串 / 空集合 / 空映射 / 0 / false 视为假。
     *
     * @param value 待判定值
     * @return 真值语义下的结果
     */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        if (value instanceof CharSequence text) {
            String trimmed = text.toString().trim();
            return !trimmed.isEmpty() && !"false".equalsIgnoreCase(trimmed) && !"0".equals(trimmed);
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value) > 0;
        }
        return true;
    }

    /**
     * 提取表达式引用到的槽位名，供定义期校验使用。
     *
     * <p>只识别 {@code slots.X} 与 {@code slot.X} 形式的一级路径，跳过字符串字面量与函数名；
     * 表达式语法非法时返回空集合，由求值阶段负责报出语法错误。</p>
     *
     * @param expression 表达式文本
     * @return 槽位名集合，保持出现顺序
     */
    public static Set<String> dependencies(String expression) {
        if (expression == null || expression.isBlank()) {
            return Set.of();
        }
        List<Token> tokens;
        try {
            tokens = tokenize(expression);
        } catch (ExpressionException e) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i + 2 < tokens.size(); i++) {
            Token root = tokens.get(i);
            if (root.string() || !("slots".equals(root.text()) || "slot".equals(root.text()))) {
                continue;
            }
            if (i > 0 && tokens.get(i - 1).is(".")) {
                continue;
            }
            if (!tokens.get(i + 1).is(".")) {
                continue;
            }
            Token segment = tokens.get(i + 2);
            if (!segment.string() && isIdentifier(segment.text())) {
                names.add(segment.text());
            }
        }
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(names));
    }

    /**
     * 校验表达式语法，供定义期检查使用。
     *
     * @param expression 表达式文本
     * @return 语法错误说明；语法正确或表达式为空时返回空
     */
    public static java.util.Optional<String> check(String expression) {
        if (expression == null || expression.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            Parser parser = new Parser(tokenize(expression), Map.of(), expression);
            parser.parse();
            parser.expectEnd();
            return java.util.Optional.empty();
        } catch (ExpressionException e) {
            return java.util.Optional.ofNullable(e.getMessage()).or(() -> java.util.Optional.of("表达式语法错误"));
        }
    }

    /** @return 是否为标识符形式的路径段 */
    private static boolean isIdentifier(String text) {
        if (text == null || text.isEmpty() || !Character.isLetter(text.charAt(0)) && text.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- parsing

    private record Token(String text, boolean string) {

        boolean is(String candidate) {
            return !string && text.equals(candidate);
        }
    }

    private static List<Token> tokenize(String src) {
        java.util.ArrayList<Token> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < src.length() && src.charAt(i) != quote) {
                    char ch = src.charAt(i);
                    if (ch == '\\' && i + 1 < src.length()) {
                        i++;
                        ch = switch (src.charAt(i)) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            default -> src.charAt(i);
                        };
                    }
                    sb.append(ch);
                    i++;
                }
                if (i >= src.length()) {
                    throw new ExpressionException("unterminated string literal in: " + src);
                }
                i++;
                out.add(new Token(sb.toString(), true));
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)))) {
                int start = i;
                i++;
                while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(src.substring(start, i), false));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Token(src.substring(start, i), false));
                continue;
            }
            if (i + 1 < src.length() && TWO_CHAR_OPS.contains(src.substring(i, i + 2))) {
                out.add(new Token(src.substring(i, i + 2), false));
                i += 2;
                continue;
            }
            if (c == '=') {
                out.add(new Token("==", false));
                i++;
                continue;
            }
            if ("()[],.".indexOf(c) >= 0 || "<>!".indexOf(c) >= 0) {
                out.add(new Token(String.valueOf(c), false));
                i++;
                continue;
            }
            throw new ExpressionException("unexpected character '" + c + "' in expression: " + src);
        }
        return out;
    }

    private static final class Parser {

        private final List<Token> tokens;
        private final Map<String, Object> variables;
        private final String source;
        private int pos;

        Parser(List<Token> tokens, Map<String, Object> variables, String source) {
            this.tokens = tokens;
            this.variables = variables;
            this.source = source;
        }

        Object parse() {
            return parseOr();
        }

        void expectEnd() {
            if (pos < tokens.size()) {
                throw new ExpressionException("unexpected token '" + tokens.get(pos).text() + "' in: " + source);
            }
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (peek().is("||")) {
                pos++;
                Object right = parseAnd();
                left = truthy(left) || truthy(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseUnary();
            while (peek().is("&&")) {
                pos++;
                Object right = parseUnary();
                left = truthy(left) && truthy(right);
            }
            return left;
        }

        private Object parseUnary() {
            if (peek().is("!")) {
                pos++;
                return !truthy(parseUnary());
            }
            return parseComparison();
        }

        private Object parseComparison() {
            Object left = parsePrimary();
            Token token = peek();
            if (!token.string() && COMPARISON_OPS.contains(token.text())) {
                pos++;
                Object right = parsePrimary();
                return compare(token.text(), left, right);
            }
            return left;
        }

        private Object parsePrimary() {
            Token token = peek();
            if (token.is("<end>")) {
                throw new ExpressionException("表达式意外结束：" + source);
            }
            if (token.is("(")) {
                pos++;
                Object inner = parseOr();
                if (!peek().is(")")) {
                    throw new ExpressionException("missing ')' in: " + source);
                }
                pos++;
                return inner;
            }
            if (token.is("[")) {
                pos++;
                java.util.ArrayList<Object> list = new java.util.ArrayList<>();
                if (!peek().is("]")) {
                    list.add(parseOr());
                    while (peek().is(",")) {
                        pos++;
                        list.add(parseOr());
                    }
                }
                if (!peek().is("]")) {
                    throw new ExpressionException("missing ']' in: " + source);
                }
                pos++;
                return List.copyOf(list);
            }
            pos++;
            if (token.string()) {
                return token.text();
            }
            if (token.text().equals("true")) {
                return Boolean.TRUE;
            }
            if (token.text().equals("false")) {
                return Boolean.FALSE;
            }
            if (token.text().equals("null") || token.text().equals("nil")) {
                return null;
            }
            if (isNumber(token.text())) {
                return Double.parseDouble(token.text());
            }
            StringBuilder path = new StringBuilder(token.text());
            while (peek().is(".")) {
                pos++;
                Token segment = peek();
                if (segment.string()) {
                    throw new ExpressionException("invalid path segment in: " + source);
                }
                pos++;
                path.append('.').append(segment.text());
            }
            String pathText = path.toString();
            if (peek().is("(")) {
                pos++;
                java.util.ArrayList<Object> args = new java.util.ArrayList<>();
                if (!peek().is(")")) {
                    args.add(parseOr());
                    while (peek().is(",")) {
                        pos++;
                        args.add(parseOr());
                    }
                }
                if (!peek().is(")")) {
                    throw new ExpressionException("missing ')' after function '" + pathText + "'");
                }
                pos++;
                return call(pathText, args);
            }
            return resolve(pathText);
        }

        private Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : new Token("<end>", false);
        }

        private Object resolve(String path) {
            if (variables.containsKey(path)) {
                return variables.get(path);
            }
            String[] parts = path.split("\\.");
            Object current = variables.get(parts[0]);
            if (current == null && ("slot".equals(parts[0]) || "slots".equals(parts[0]))) {
                current = variables.get("slots");
            }
            for (int i = 1; i < parts.length && current != null; i++) {
                current = property(current, parts[i]);
            }
            return current;
        }

        private Object property(Object target, String name) {
            if (target instanceof Map<?, ?> map) {
                return map.get(name);
            }
            if (target instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(name);
                    return index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            if (target.getClass().isArray()) {
                try {
                    int index = Integer.parseInt(name);
                    return index >= 0 && index < java.lang.reflect.Array.getLength(target)
                            ? java.lang.reflect.Array.get(target, index)
                            : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private Object call(String name, List<Object> args) {
            return switch (name) {
                case "exists" -> arg(args, 0) != null;
                case "empty" -> isEmpty(arg(args, 0));
                case "size", "len" -> size(arg(args, 0));
                case "lower" -> String.valueOf(arg(args, 0)).toLowerCase();
                case "upper" -> String.valueOf(arg(args, 0)).toUpperCase();
                case "str" -> String.valueOf(arg(args, 0));
                case "num" -> toNumber(arg(args, 0));
                case "bool" -> truthy(arg(args, 0));
                case "matches" -> matches(arg(args, 0), arg(args, 1));
                case "contains" -> contains(arg(args, 0), arg(args, 1));
                default -> throw new ExpressionException("unknown function '" + name + "' in: " + source);
            };
        }

        private Object arg(List<Object> args, int index) {
            return index < args.size() ? args.get(index) : null;
        }

        private Object compare(String operator, Object left, Object right) {
            return switch (operator) {
                case "==" -> equalsValue(left, right);
                case "!=" -> !equalsValue(left, right);
                case ">" -> compareOrder(left, right) > 0;
                case ">=" -> compareOrder(left, right) >= 0;
                case "<" -> compareOrder(left, right) < 0;
                case "<=" -> compareOrder(left, right) <= 0;
                case "contains" -> contains(left, right);
                case "startsWith" -> String.valueOf(left).startsWith(String.valueOf(right));
                case "endsWith" -> String.valueOf(left).endsWith(String.valueOf(right));
                case "matches" -> matches(left, right);
                case "in" -> contains(right, left);
                default -> throw new ExpressionException("unsupported operator '" + operator + "'");
            };
        }
    }

    // ------------------------------------------------------------- operations

    private static boolean isNumber(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c) && c != '.' && !(i == 0 && c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsValue(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (left instanceof Number || right instanceof Number) {
            Double a = toNumber(left);
            Double b = toNumber(right);
            if (a != null && b != null) {
                return a.doubleValue() == b.doubleValue();
            }
        }
        return Objects.equals(left, right);
    }

    private static int compareOrder(Object left, Object right) {
        Double a = toNumber(left);
        Double b = toNumber(right);
        if (a != null && b != null) {
            return Double.compare(a, b);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static Double toNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof CharSequence text) {
            try {
                return Double.valueOf(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence text) {
            return text.toString().isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value) == 0;
        }
        return false;
    }

    private static Object size(Object value) {
        if (value == null) {
            return 0d;
        }
        if (value instanceof CharSequence text) {
            return (double) text.length();
        }
        if (value instanceof Collection<?> collection) {
            return (double) collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return (double) map.size();
        }
        if (value.getClass().isArray()) {
            return (double) java.lang.reflect.Array.getLength(value);
        }
        return 0d;
    }

    private static boolean contains(Object container, Object needle) {
        if (container == null) {
            return false;
        }
        if (container instanceof CharSequence text) {
            return text.toString().contains(String.valueOf(needle));
        }
        if (container instanceof Collection<?> collection) {
            return collection.stream().anyMatch(candidate -> equalsValue(candidate, needle));
        }
        if (container instanceof Map<?, ?> map) {
            return map.containsKey(needle) || map.containsValue(needle);
        }
        if (container.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(container);
            for (int i = 0; i < length; i++) {
                if (equalsValue(java.lang.reflect.Array.get(container, i), needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(Object text, Object regex) {
        if (text == null || regex == null) {
            return false;
        }
        try {
            return Pattern.compile(String.valueOf(regex)).matcher(String.valueOf(text)).find();
        } catch (PatternSyntaxException e) {
            throw new ExpressionException("invalid regex '" + regex + "': " + e.getMessage());
        }
    }
}
