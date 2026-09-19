package com.agentframework.runtime.workspace;

import java.util.Map;

/** 工作区长期记忆：与工作流槽位分离的键值存储。 */
public interface Memory {

    /**
     * @param key   键
     * @param value 值
     */
    void put(String key, Object value);

    /**
     * @param key 键
     * @return 值，不存在返回 null
     */
    Object get(String key);

    /** @return 只读视图 */
    Map<String, Object> asMap();

    /** @param key 需要删除的键 */
    void remove(String key);

    /** 清空全部记忆。 */
    void clear();
}
