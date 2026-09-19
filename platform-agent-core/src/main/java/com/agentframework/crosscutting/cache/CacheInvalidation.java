package com.agentframework.crosscutting.cache;

import com.agentframework.runtime.event.Event;

/**
 * 缓存失效扩展点：把领域事件映射为缓存失效动作。
 *
 * <p>示例：会话结束时清空该会话命名空间下的全部缓存。</p>
 */
public interface CacheInvalidation {

    /**
     * 订阅的事件类型。
     *
     * @return 事件类型，取值见 {@code Topics}
     */
    String eventType();

    /**
     * 执行失效。
     *
     * @param store 缓存存储
     * @param event 触发事件
     * @return 失效的条目数
     */
    int invalidate(CacheStore store, Event event);

    /**
     * 构造“按会话命名空间失效”的规则。
     *
     * @param eventType 触发事件类型
     * @return 失效规则
     */
    static CacheInvalidation onSessionEvent(String eventType) {
        return new CacheInvalidation() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public int invalidate(CacheStore store, Event event) {
                return store.invalidateNamespace(event == null ? null : event.sessionId());
            }
        };
    }
}
