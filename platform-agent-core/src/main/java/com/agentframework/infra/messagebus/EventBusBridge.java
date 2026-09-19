package com.agentframework.infra.messagebus;

import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.event.EventHandler;
import com.agentframework.runtime.event.Subscription;
import com.agentframework.runtime.event.Topics;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件总线到消息总线的桥接器。
 *
 * <p>引擎继续发布领域事件，桥接器负责把它们转成跨进程消息，二者互不感知。</p>
 */
public final class EventBusBridge {

    private final MessageBus messageBus;

    /** @param messageBus 目标消息总线 */
    public EventBusBridge(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    /**
     * 订阅事件总线并把事件转发到消息总线。
     *
     * @param eventBus 事件总线
     * @param topic    转发目标主题
     * @return 订阅句柄
     */
    public Subscription bridge(EventBus eventBus, String topic) {
        String targetTopic = topic == null ? "agent.events" : topic;
        EventHandler handler = event -> messageBus.publish(toBusMessage(event, targetTopic));
        return eventBus.subscribe(Topics.ALL, handler);
    }

    /**
     * 事件转总线消息。
     *
     * @param event 领域事件
     * @param topic 目标主题
     * @return 总线消息
     */
    private BusMessage toBusMessage(Event event, String topic) {
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.put("eventId", event.id());
        payload.put("eventType", event.type());
        if (event.sessionId() != null) {
            payload.put("sessionId", event.sessionId());
        }
        if (event.tenantId() != null) {
            payload.put("tenantId", event.tenantId());
        }
        BusMessage message = BusMessage.of(topic, payload);
        if (event.sessionId() != null) {
            message = message.withKey(event.sessionId());
        }
        return event.traceId() == null ? message : message.withHeader("traceId", event.traceId());
    }
}
