package com.agentframework.infra.storage;

import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.event.EventHandler;
import com.agentframework.runtime.event.Subscription;
import com.agentframework.runtime.event.Topics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存事件总线：单进程内的发布订阅实现。
 *
 * <p>处理器异常会被隔离，避免一个订阅者影响其它订阅者与主流程。</p>
 */
public final class InMemoryEventBus implements EventBus {

    private final Map<String, List<EventHandler>> handlers = new ConcurrentHashMap<>();
    private final List<Event> history = new ArrayList<>();
    private final int historyLimit;

    /**
     * @param historyLimit 保留的事件历史条数，≤0 表示不保留
     */
    public InMemoryEventBus(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    /** 默认保留最近 1000 条事件。 */
    public InMemoryEventBus() {
        this(1000);
    }

    @Override
    public void publish(Event event) {
        if (event == null) {
            return;
        }
        if (historyLimit > 0) {
            synchronized (history) {
                history.add(event);
                while (history.size() > historyLimit) {
                    history.remove(0);
                }
            }
        }
        dispatch(handlers.get(event.type()), event);
        if (!Topics.ALL.equals(event.type())) {
            dispatch(handlers.get(Topics.ALL), event);
        }
    }

    @Override
    public Subscription subscribe(String topic, EventHandler handler) {
        String key = topic == null ? Topics.ALL : topic;
        List<EventHandler> bucket = handlers.computeIfAbsent(key, ignored -> new ArrayList<>());
        synchronized (bucket) {
            bucket.add(handler);
        }
        return new Subscription() {
            @Override
            public String topic() {
                return key;
            }

            @Override
            public void close() {
                synchronized (bucket) {
                    bucket.remove(handler);
                }
            }
        };
    }

    /** @return 事件历史快照 */
    public List<Event> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /**
     * @param type 事件类型
     * @return 该类型的事件数量
     */
    public long count(String type) {
        synchronized (history) {
            return history.stream().filter(event -> event.type().equals(type)).count();
        }
    }

    /** 派发事件到处理器列表，单个处理器异常不影响其它处理器。 */
    private void dispatch(List<EventHandler> bucket, Event event) {
        if (bucket == null) {
            return;
        }
        List<EventHandler> snapshot;
        synchronized (bucket) {
            snapshot = List.copyOf(bucket);
        }
        for (EventHandler handler : snapshot) {
            try {
                handler.onEvent(event);
            } catch (RuntimeException ignored) {
                // 订阅者异常隔离，保证主流程与其它订阅者不受影响
            }
        }
    }
}
