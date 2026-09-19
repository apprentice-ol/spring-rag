package com.agentframework.crosscutting.interceptor;

import java.time.Duration;

/** 超时异常：单次调用超过策略规定的时长。 */
public class TimeoutExceededException extends RuntimeException {

    /**
     * @param operation 操作名
     * @param timeout   超时时长
     */
    public TimeoutExceededException(String operation, Duration timeout) {
        super("操作 " + operation + " 超过超时上限 " + timeout.toMillis() + " ms");
    }
}
