package com.agentframework.extension.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 默认扩展注册表：基于类型 + 注册名的双键索引。
 *
 * <p>重复注册同名实现会直接失败，避免插件之间静默覆盖；如需替换请先 {@code unregister}。</p>
 */
public final class DefaultExtensionRegistry implements ExtensionRegistry {

    private final ApiVersion apiVersion;
    private final Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
    private final Map<String, ExtensionMeta> metas = new LinkedHashMap<>();

    /** 使用内核当前 API 版本构造。 */
    public DefaultExtensionRegistry() {
        this(ApiVersion.CURRENT);
    }

    /** @param apiVersion 内核扩展 API 版本 */
    public DefaultExtensionRegistry(ApiVersion apiVersion) {
        this.apiVersion = apiVersion == null ? ApiVersion.CURRENT : apiVersion;
    }

    @Override
    public <T> void register(Class<T> type, String id, T impl) {
        register(type, id, impl, ExtensionMeta.of(id, typeName(type)));
    }

    @Override
    public <T> void register(Class<T> type, String id, T impl, ExtensionMeta meta) {
        if (type == null || id == null || id.isBlank() || impl == null) {
            throw new IllegalArgumentException("注册扩展需要提供类型、注册名与实现");
        }
        if (!type.isInstance(impl)) {
            throw new IllegalArgumentException("实现 " + impl.getClass().getName() + " 不是 " + type.getName() + " 的实例");
        }
        versionCheck(meta == null ? null : meta.apiVersion());
        synchronized (byType) {
            Map<String, Object> bucket = byType.computeIfAbsent(typeName(type), ignored -> new LinkedHashMap<>());
            if (bucket.containsKey(id)) {
                throw new IllegalStateException("扩展点 " + typeName(type) + " 下已存在同名扩展：" + id);
            }
            bucket.put(id, impl);
            metas.put(key(typeName(type), id), meta == null ? ExtensionMeta.of(id, typeName(type)) : meta);
        }
    }

    @Override
    public void registerExtension(Extension extension) {
        if (extension == null || extension.meta() == null) {
            return;
        }
        ExtensionMeta meta = extension.meta();
        synchronized (byType) {
            Map<String, Object> bucket = byType.computeIfAbsent(meta.type(), ignored -> new LinkedHashMap<>());
            bucket.put(meta.id(), extension);
            metas.put(key(meta.type(), meta.id()), meta);
        }
    }

    @Override
    public <T> T resolve(Class<T> type, String id) {
        return tryResolve(type, id).orElseThrow(() -> new NoSuchElementException(
                "未找到扩展：type=" + typeName(type) + ", id=" + id));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> tryResolve(Class<T> type, String id) {
        synchronized (byType) {
            Map<String, Object> bucket = byType.get(typeName(type));
            if (bucket == null) {
                return Optional.empty();
            }
            Object impl = bucket.get(id);
            return type.isInstance(impl) ? Optional.of((T) impl) : Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> list(Class<T> type) {
        synchronized (byType) {
            Map<String, Object> bucket = byType.get(typeName(type));
            if (bucket == null) {
                return List.of();
            }
            List<T> result = new ArrayList<>();
            bucket.values().stream().filter(type::isInstance).forEach(value -> result.add((T) value));
            return List.copyOf(result);
        }
    }

    @Override
    public List<String> ids(Class<?> type) {
        synchronized (byType) {
            Map<String, Object> bucket = byType.get(typeName(type));
            return bucket == null ? List.of() : List.copyOf(bucket.keySet());
        }
    }

    @Override
    public List<ExtensionMeta> metas() {
        synchronized (byType) {
            return List.copyOf(metas.values());
        }
    }

    /**
     * @param type 扩展点类型
     * @param id   注册名
     * @return 元信息，未注册返回 null
     */
    public ExtensionMeta meta(Class<?> type, String id) {
        synchronized (byType) {
            return metas.get(key(typeName(type), id));
        }
    }

    @Override
    public boolean unregister(Class<?> type, String id) {
        synchronized (byType) {
            Map<String, Object> bucket = byType.get(typeName(type));
            if (bucket == null || bucket.remove(id) == null) {
                return false;
            }
            metas.remove(key(typeName(type), id));
            return true;
        }
    }

    @Override
    public void versionCheck(String requiredApiVersion) {
        if (requiredApiVersion == null || requiredApiVersion.isBlank()) {
            return;
        }
        ApiVersion required = ApiVersion.parse(requiredApiVersion);
        if (!apiVersion.compatibleWith(required)) {
            throw new IllegalStateException("扩展要求 API 版本 " + required + "，当前内核为 " + apiVersion);
        }
    }

    @Override
    public ApiVersion apiVersion() {
        return apiVersion;
    }

    /** @return 注册表内的扩展点数量 */
    public int typeCount() {
        synchronized (byType) {
            return byType.size();
        }
    }

    /** @return 注册项总数 */
    public int size() {
        synchronized (byType) {
            return byType.values().stream().mapToInt(Map::size).sum();
        }
    }

    /** 释放全部实现 {@link Extension} 的资源。 */
    public void closeAll() {
        List<Extension> extensions;
        synchronized (byType) {
            extensions = byType.values().stream()
                    .flatMap(bucket -> bucket.values().stream())
                    .filter(Extension.class::isInstance)
                    .map(Extension.class::cast)
                    .toList();
        }
        for (Extension extension : extensions) {
            try {
                extension.close();
            } catch (RuntimeException ignored) {
                // 单个扩展释放失败不应影响其它扩展
            }
        }
    }

    /** @return 扩展点名称 */
    private static String typeName(Class<?> type) {
        return type == null ? "unknown" : type.getName();
    }

    /** @return 元信息索引键 */
    private static String key(String type, String id) {
        return type + "#" + id;
    }
}
