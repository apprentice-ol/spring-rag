package com.agentframework.engine.middleware;

import com.agentframework.crosscutting.filter.Filter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 过滤器泛型解析工具：推断 {@link Filter} 的输入类型。
 *
 * <p>有了输入类型，管道才能在同一个注册表里安全地混装 {@code Filter<String,String>}、
 * {@code Filter<List<Message>,List<Message>>} 等不同类型的过滤器。</p>
 */
final class FilterTypes {

    private static final Map<Class<?>, Class<?>> CACHE = new ConcurrentHashMap<>();

    private FilterTypes() {
    }

    /**
     * 解析过滤器的输入类型。
     *
     * @param filter 过滤器实例
     * @return 输入类型；无法解析时返回 {@code Object.class}
     */
    static Class<?> inputType(Filter<?, ?> filter) {
        return CACHE.computeIfAbsent(filter.getClass(), FilterTypes::resolve);
    }

    /** 遍历类层次与接口，寻找 {@code Filter<I,O>} 的第一个类型参数。 */
    private static Class<?> resolve(Class<?> type) {
        for (Type candidate : type.getGenericInterfaces()) {
            Class<?> resolved = fromType(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        Type superclass = type.getGenericSuperclass();
        if (superclass instanceof ParameterizedType parameterized) {
            Class<?> resolved = fromType(parameterized);
            if (resolved != null) {
                return resolved;
            }
        }
        if (type.getSuperclass() != null && type.getSuperclass() != Object.class) {
            return resolve(type.getSuperclass());
        }
        return Object.class;
    }

    /** 从参数化类型中取出 {@code Filter} 的输入类型。 */
    private static Class<?> fromType(Type candidate) {
        if (!(candidate instanceof ParameterizedType parameterized)) {
            return null;
        }
        if (!Filter.class.equals(parameterized.getRawType())) {
            return null;
        }
        Type argument = parameterized.getActualTypeArguments()[0];
        if (argument instanceof Class<?> clazz) {
            return clazz;
        }
        if (argument instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return Object.class;
    }
}
