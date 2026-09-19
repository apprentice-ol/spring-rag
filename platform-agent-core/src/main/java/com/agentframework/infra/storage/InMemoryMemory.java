package com.agentframework.infra.storage;

import com.agentframework.runtime.workspace.Memory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 内存长期记忆实现。 */
public final class InMemoryMemory implements Memory {

    private final Map<String, Object> values = new LinkedHashMap<>();

    @Override
    public synchronized void put(String key, Object value) {
        if (key != null) {
            values.put(key, value);
        }
    }

    @Override
    public synchronized Object get(String key) {
        return values.get(key);
    }

    @Override
    public synchronized Map<String, Object> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public synchronized void remove(String key) {
        values.remove(key);
    }

    @Override
    public synchronized void clear() {
        values.clear();
    }

    /** @return 记忆条目数量 */
    public synchronized int size() {
        return values.size();
    }
}
