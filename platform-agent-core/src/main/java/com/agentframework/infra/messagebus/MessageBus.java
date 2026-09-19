package com.agentframework.infra.messagebus;

import com.agentframework.runtime.event.Subscription;
import java.util.function.Consumer;

/**
 * 消息总线扩展点：把框架事件与业务消息投递到跨进程通道。
 *
 * <p>内核默认只在进程内投递；接入 Kafka / RocketMQ 等只需替换实现。</p>
 */
public interface MessageBus {

    /**
     * 发送消息。
     *
     * @param message 总线消息
     */
    void publish(BusMessage message);

    /**
     * 订阅主题。
     *
     * @param topic   主题
     * @param handler 消息处理器
     * @return 订阅句柄
     */
    Subscription subscribe(String topic, Consumer<BusMessage> handler);
}
