package com.agentframework.runtime.event;

/** 订阅句柄：关闭后不再接收事件。 */
public interface Subscription extends AutoCloseable {

    /** @return 订阅主题 */
    String topic();

    @Override
    void close();
}
