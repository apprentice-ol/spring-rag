package com.agentframework.runtime.event;

/** 事件处理器：订阅方实现该接口接收事件。 */
@FunctionalInterface
public interface EventHandler {

    /**
     * 处理事件。
     *
     * @param event 事件对象
     */
    void onEvent(Event event);
}
