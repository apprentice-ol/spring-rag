package com.agentframework.runtime.event;

/**
 * 事件总线：解耦节点执行与外部系统（审计、通知、指标、消息队列）。
 *
 * <p>引擎只在关键节点发布事件，不关心有多少订阅者。</p>
 */
public interface EventBus {

    /**
     * 发布事件。
     *
     * @param event 事件对象
     */
    void publish(Event event);

    /**
     * 订阅指定主题。
     *
     * @param topic   主题，{@link Topics#ALL} 表示全部事件
     * @param handler 事件处理器
     * @return 订阅句柄，可用于取消订阅
     */
    Subscription subscribe(String topic, EventHandler handler);

    /**
     * 订阅全部主题的便捷方法。
     *
     * @param handler 事件处理器
     * @return 订阅句柄
     */
    default Subscription subscribeAll(EventHandler handler) {
        return subscribe(Topics.ALL, handler);
    }
}
