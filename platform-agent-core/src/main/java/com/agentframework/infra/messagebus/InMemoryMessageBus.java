package com.agentframework.infra.messagebus;

import com.agentframework.runtime.event.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 内存消息总线：单进程内的主题订阅与投递。 */
public final class InMemoryMessageBus implements MessageBus {

    private final Map<String, List<Consumer<BusMessage>>> handlers = new ConcurrentHashMap<>();
    private final List<BusMessage> published = new ArrayList<>();

    @Override
    public void publish(BusMessage message) {
        if (message == null) {
            return;
        }
        synchronized (published) {
            published.add(message);
        }
        List<Consumer<BusMessage>> bucket = handlers.get(message.topic());
        if (bucket == null) {
            return;
        }
        List<Consumer<BusMessage>> snapshot;
        synchronized (bucket) {
            snapshot = List.copyOf(bucket);
        }
        snapshot.forEach(handler -> handler.accept(message));
    }

    @Override
    public Subscription subscribe(String topic, Consumer<BusMessage> handler) {
        String key = topic == null ? "default" : topic;
        List<Consumer<BusMessage>> bucket = handlers.computeIfAbsent(key, ignored -> new ArrayList<>());
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

    /** @return 已投递消息快照 */
    public List<BusMessage> published() {
        synchronized (published) {
            return List.copyOf(published);
        }
    }

    /**
     * @param topic 主题
     * @return 该主题已投递的消息数量
     */
    public long count(String topic) {
        synchronized (published) {
            return published.stream().filter(message -> message.topic().equals(topic)).count();
        }
    }
}
