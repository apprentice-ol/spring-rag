package com.agentframework.runtime.slot;

import com.agentframework.definition.workflow.SlotScope;
import com.agentframework.definition.workflow.SlotsSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话槽位容器：工作流读写状态的唯一出口。
 *
 * <p>状态与流程分离——工作流定义流程，槽位保存流程之间传递的数据。
 * 容器内部同步，可被并行节点安全读写。</p>
 */
public final class Slots {

    private final Map<String, SlotValue> values = new LinkedHashMap<>();
    private long version;

    /** 创建空容器。 */
    public Slots() {
    }

    /**
     * 用初始值创建容器。
     *
     * @param initial 初始键值对
     */
    public Slots(Map<String, Object> initial) {
        if (initial != null) {
            initial.forEach((key, value) -> values.put(key, SlotValue.of(key, value)));
        }
    }

    /**
     * 由快照还原容器。
     *
     * @param snapshot 槽位快照
     * @return 还原后的容器
     */
    public static Slots fromSnapshot(SlotSnapshot snapshot) {
        Slots slots = new Slots();
        if (snapshot != null) {
            synchronized (slots.values) {
                slots.values.putAll(snapshot.values());
                slots.version = snapshot.version();
            }
        }
        return slots;
    }

    /**
     * 读取槽位值。
     *
     * @param name 槽位名
     * @return 槽位值，不存在返回 null
     */
    public Object get(String name) {
        synchronized (values) {
            SlotValue value = values.get(name);
            return value == null ? null : value.value();
        }
    }

    /**
     * 以字符串形式读取槽位值。
     *
     * @param name     槽位名
     * @param fallback 缺省值
     * @return 字符串值或缺省值
     */
    public String getString(String name, String fallback) {
        Object value = get(name);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * 以整型读取槽位值。
     *
     * @param name     槽位名
     * @param fallback 缺省值
     * @return 整型值或缺省值
     */
    public int getInt(String name, int fallback) {
        Object value = get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    /**
     * 以布尔读取槽位值。
     *
     * @param name     槽位名
     * @param fallback 缺省值
     * @return 布尔值或缺省值
     */
    public boolean getBoolean(String name, boolean fallback) {
        Object value = get(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 按指定类型读取槽位值。
     *
     * @param name 槽位名
     * @param type 期望类型
     * @param <T>  类型参数
     * @return 值或 null
     */
    public <T> T getAs(String name, Class<T> type) {
        Object value = get(name);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /**
     * @param name 槽位名
     * @return 是否存在该槽位（值可以为 null）
     */
    public boolean contains(String name) {
        synchronized (values) {
            return values.containsKey(name);
        }
    }

    /**
     * 写入会话级槽位。
     *
     * @param name  槽位名
     * @param value 槽位值
     * @return 覆盖前的旧值，不存在返回 null
     */
    public Object put(String name, Object value) {
        return put(name, value, SlotScope.SESSION, null);
    }

    /**
     * 写入指定作用域的槽位。
     *
     * @param name     槽位名
     * @param value    槽位值
     * @param scope    作用域
     * @param scopeKey 作用域实例键，可为 null
     * @return 覆盖前的旧值，不存在返回 null
     */
    public Object put(String name, Object value, SlotScope scope, String scopeKey) {
        synchronized (values) {
            SlotValue existing = values.get(name);
            long nextVersion = existing == null ? 1L : existing.version() + 1;
            values.put(name, new SlotValue(name, scope, scopeKey, value, nextVersion, true, null));
            version++;
            return existing == null ? null : existing.value();
        }
    }

    /**
     * 批量写入会话级槽位。
     *
     * @param batch 待写入的键值对
     */
    public void putAll(Map<String, Object> batch) {
        if (batch != null) {
            batch.forEach(this::put);
        }
    }

    /**
     * 删除槽位。
     *
     * @param name 槽位名
     * @return 被删除的值，不存在返回 null
     */
    public Object remove(String name) {
        synchronized (values) {
            SlotValue removed = values.remove(name);
            if (removed != null) {
                version++;
            }
            return removed == null ? null : removed.value();
        }
    }

    /** @return 全部槽位名的有序快照 */
    public Set<String> names() {
        synchronized (values) {
            return Set.copyOf(values.keySet());
        }
    }

    /** @return 槽位名到值的不可变视图 */
    public Map<String, Object> asMap() {
        synchronized (values) {
            Map<String, Object> flat = new LinkedHashMap<>();
            values.forEach((key, value) -> flat.put(key, value.value()));
            return Collections.unmodifiableMap(flat);
        }
    }

    /** @return 全部槽位值的详细列表 */
    public List<SlotValue> values() {
        synchronized (values) {
            return List.copyOf(new ArrayList<>(values.values()));
        }
    }

    /** @return 容器自身的版本号，每次写入递增 */
    public long version() {
        synchronized (values) {
            return version;
        }
    }

    /**
     * 生成持久化快照。
     *
     * @param sessionId 所属会话 id
     * @return 槽位快照
     */
    public SlotSnapshot snapshot(String sessionId) {
        synchronized (values) {
            return new SlotSnapshot(sessionId, values, version, null);
        }
    }

    /**
     * 按 schema 校验当前槽位。
     *
     * @param schema 槽位契约
     * @return 问题列表，为空表示通过
     */
    public List<String> validate(SlotsSchema schema) {
        return schema == null ? List.of() : schema.validate(asMap());
    }

    /** @return 槽位数量 */
    public int size() {
        synchronized (values) {
            return values.size();
        }
    }
}
