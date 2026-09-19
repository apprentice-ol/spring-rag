package com.agentframework.definition.codec;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.HumanNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.node.ParallelNodeDefinition;
import com.agentframework.definition.node.SubWorkflowNodeDefinition;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 内置 JSON 编解码：基于 record 组件做结构化映射，不引入第三方库。
 *
 * <p>语义：
 * <ul>
 *   <li>record → 对象；枚举 → 名称；{@link Duration} → ISO-8601 字符串</li>
 *   <li>节点是多态的，编码时写入 {@code type} 字段，解码时按 {@link NodeType} 分派</li>
 *   <li>遇到无法表达的类型直接失败（{@code DEFINITION_ENCODE_ERROR}），不做静默丢弃</li>
 *   <li>{@code Object} 类型字段的数字有**规范形态**：整数在 int 范围内取 {@link Integer}，
 *       超出取 {@link Long}，含小数取 {@link Double}；由此保证 round-trip 稳定</li>
 * </ul>
 */
public final class JsonDefinitionCodec implements DefinitionCodec {

    @Override
    public String format() {
        return "json";
    }

    @Override
    public Map<String, Object> encode(DefinitionKind kind, Object definition) {
        if (definition == null) {
            throw new DefinitionDocumentException("DEFINITION_ENCODE_ERROR", "定义为空");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", DefinitionLoader.SCHEMA_VERSION);
        document.put("kind", kind.wireName());
        document.putAll(encodeRecord(definition));
        return document;
    }

    @Override
    public Object decode(DefinitionKind kind, Map<String, Object> document) {
        Map<String, Object> body = new LinkedHashMap<>(document);
        body.remove("schemaVersion");
        body.remove("kind");
        return decodeRecord(targetType(kind), body);
    }

    /**
     * @param kind 定义种类
     * @return 对应的定义类型
     */
    public Class<?> targetType(DefinitionKind kind) {
        return switch (kind) {
            case AGENT -> AgentDefinition.class;
            case WORKFLOW -> WorkflowDefinition.class;
            case PROMPT -> PromptDefinition.class;
            case TOOL -> ToolDefinition.class;
        };
    }

    /**
     * @param kind 定义种类
     * @return 该种类文档的已知顶层字段
     */
    public Set<String> knownFields(DefinitionKind kind) {
        Set<String> fields = new LinkedHashSet<>();
        fields.add("schemaVersion");
        fields.add("kind");
        for (RecordComponent component : targetType(kind).getRecordComponents()) {
            fields.add(component.getName());
        }
        return fields;
    }

    /**
     * 编码单个定义对象为文档主体（不含 schemaVersion / kind）。
     *
     * @param definition 定义对象
     * @return 文档主体
     */
    Map<String, Object> encodeBody(Object definition) {
        return encodeRecord(definition);
    }

    /**
     * @param record record 实例
     * @return 字段映射
     */
    private Map<String, Object> encodeRecord(Object record) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                result.put(component.getName(), encodeValue(component.getAccessor().invoke(record)));
            } catch (ReflectiveOperationException e) {
                throw new DefinitionDocumentException("DEFINITION_ENCODE_ERROR",
                        "读取字段失败：" + component.getName(), e);
            }
        }
        return result;
    }

    /**
     * @param value 任意字段值
     * @return 可 JSON 化的值
     */
    private Object encodeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Duration duration) {
            return duration.toString();
        }
        if (value instanceof NodeDefinition node) {
            Map<String, Object> encoded = encodeRecord(node);
            encoded.put("type", node.type().name());
            return encoded;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> encoded.put(String.valueOf(key), encodeValue(entryValue)));
            return encoded;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> encoded = new ArrayList<>(collection.size());
            collection.forEach(element -> encoded.add(encodeValue(element)));
            return encoded;
        }
        if (value.getClass().isRecord()) {
            return encodeRecord(value);
        }
        throw new DefinitionDocumentException("DEFINITION_ENCODE_ERROR",
                "无法编码的类型：" + value.getClass().getName());
    }

    /**
     * @param type 目标 record 类型
     * @param body 文档主体
     * @return 实例
     */
    private Object decodeRecord(Class<?> type, Map<String, Object> body) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            arguments[i] = convert(body.get(components[i].getName()), components[i].getGenericType());
            parameterTypes[i] = components[i].getType();
        }
        try {
            return type.getDeclaredConstructor(parameterTypes).newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new DefinitionDocumentException("DEFINITION_FIELD_INVALID",
                    "构造 " + type.getSimpleName() + " 失败：" + cause.getMessage(), cause);
        }
    }

    /**
     * @param value 文档中的值
     * @param type  目标类型
     * @return 转换后的值
     */
    private Object convert(Object value, Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (List.class.isAssignableFrom(raw) || Collection.class == raw) {
                List<Object> converted = new ArrayList<>();
                for (Object element : asList(value)) {
                    converted.add(convert(element, arguments[0]));
                }
                return converted;
            }
            if (Set.class.isAssignableFrom(raw)) {
                Set<Object> converted = new LinkedHashSet<>();
                for (Object element : asList(value)) {
                    converted.add(convert(element, arguments[0]));
                }
                return converted;
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<String, Object> converted = new LinkedHashMap<>();
                asMap(value).forEach((key, entryValue) ->
                        converted.put(String.valueOf(key), convert(entryValue, arguments[1])));
                return converted;
            }
            if (raw.isRecord()) {
                return decodeRecord(raw, asMap(value));
            }
            return value;
        }
        if (!(type instanceof Class<?> clazz)) {
            return value;
        }
        return convertToClass(value, clazz);
    }

    /**
     * @param value 文档中的值
     * @param clazz 目标类型
     * @return 转换后的值
     */
    private Object convertToClass(Object value, Class<?> clazz) {
        if (clazz == Object.class) {
            return canonicalize(value);
        }
        if (clazz == String.class) {
            return value == null ? null : String.valueOf(value);
        }
        // 基本类型与包装类型在 null 上语义不同：包装类型的 null 是有意义的“未设置”/“继承”
        if (clazz == int.class || clazz == Integer.class) {
            return value == null ? (clazz == int.class ? 0 : null) : number(value).intValue();
        }
        if (clazz == long.class || clazz == Long.class) {
            return value == null ? (clazz == long.class ? 0L : null) : number(value).longValue();
        }
        if (clazz == double.class || clazz == Double.class) {
            return value == null ? (clazz == double.class ? 0.0d : null) : number(value).doubleValue();
        }
        if (clazz == float.class || clazz == Float.class) {
            return value == null ? (clazz == float.class ? 0.0f : null) : number(value).floatValue();
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            if (value == null) {
                return clazz == boolean.class ? Boolean.FALSE : null;
            }
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
        }
        if (value == null) {
            return null;
        }
        if (clazz.isEnum()) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class<? extends Enum>) clazz,
                        String.valueOf(value).toUpperCase(Locale.ROOT));
                return enumValue;
            } catch (IllegalArgumentException e) {
                throw new DefinitionDocumentException("DEFINITION_FIELD_INVALID",
                        "非法的枚举值：" + value + "（" + clazz.getSimpleName() + "）");
            }
        }
        if (clazz == Duration.class) {
            try {
                return Duration.parse(String.valueOf(value));
            } catch (RuntimeException e) {
                throw new DefinitionDocumentException("DEFINITION_FIELD_INVALID",
                        "非法的时长：" + value);
            }
        }
        if (NodeDefinition.class.equals(clazz)) {
            return decodeNode(asMap(value));
        }
        if (clazz.isRecord()) {
            return decodeRecord(clazz, asMap(value));
        }
        if (List.class.isAssignableFrom(clazz) || Collection.class == clazz) {
            return asList(value);
        }
        if (Set.class.isAssignableFrom(clazz)) {
            return new LinkedHashSet<>(asList(value));
        }
        if (Map.class.isAssignableFrom(clazz)) {
            return asMap(value);
        }
        throw new DefinitionDocumentException("DEFINITION_FIELD_INVALID",
                "不支持的字段类型：" + clazz.getName());
    }

    /**
     * @param document 节点文档
     * @return 节点定义
     */
    private NodeDefinition decodeNode(Map<String, Object> document) {
        Object typeName = document.get("type");
        if (typeName == null) {
            throw new DefinitionDocumentException("DEFINITION_FIELD_INVALID", "节点缺少 type 字段");
        }
        NodeType type;
        try {
            type = NodeType.valueOf(String.valueOf(typeName).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DefinitionDocumentException("DEFINITION_FIELD_INVALID", "未知的节点类型：" + typeName);
        }
        Class<? extends NodeDefinition> target = switch (type) {
            case LLM -> LlmNodeDefinition.class;
            case TOOL -> ToolNodeDefinition.class;
            case CONDITION -> ConditionNodeDefinition.class;
            case PARALLEL -> ParallelNodeDefinition.class;
            case HUMAN -> HumanNodeDefinition.class;
            case SUB_WORKFLOW -> SubWorkflowNodeDefinition.class;
            case CUSTOM -> CustomNodeDefinition.class;
        };
        return (NodeDefinition) decodeRecord(target, document);
    }

    /**
     * @param value 文档中的值
     * @return 列表视图
     */
    private List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw new DefinitionDocumentException("DEFINITION_FIELD_INVALID", "期望数组，实际为：" + value.getClass());
    }

    /**
     * @param value 文档中的值
     * @return 映射视图
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new DefinitionDocumentException("DEFINITION_FIELD_INVALID", "期望对象，实际为：" + value.getClass());
    }

    /**
     * @param value 数字
     * @return 数字视图
     */
    private Number number(Object value) {
        if (value instanceof Number numeric) {
            return numeric;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new DefinitionDocumentException("DEFINITION_FIELD_INVALID", "期望数字，实际为：" + value);
        }
    }

    /**
     * 把 {@code Object} 字段的值规整为稳定形态：整数按范围取 Integer / Long，小数取 Double，
     * 容器递归处理。
     *
     * @param value 原始值
     * @return 规范形态
     */
    private Object canonicalize(Object value) {
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble == Math.rint(asDouble) && asDouble >= Integer.MIN_VALUE && asDouble <= Integer.MAX_VALUE) {
                return number.intValue();
            }
            if (asDouble == Math.rint(asDouble)) {
                return number.longValue();
            }
            return asDouble;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> normalized.put(String.valueOf(key), canonicalize(entryValue)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            list.forEach(element -> normalized.add(canonicalize(element)));
            return normalized;
        }
        return value;
    }
}
