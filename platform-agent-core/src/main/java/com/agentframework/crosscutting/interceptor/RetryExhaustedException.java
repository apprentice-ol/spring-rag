package com.agentframework.crosscutting.interceptor;

/** 重试耗尽异常：达到最大尝试次数后仍失败。 */
public class RetryExhaustedException extends RuntimeException {

    /**
     * @param operation 操作名
     * @param attempts  实际尝试次数
     * @param cause     最后一次失败原因
     */
    public RetryExhaustedException(String operation, int attempts, Throwable cause) {
        super("操作 " + operation + " 重试 " + attempts + " 次后仍然失败：" + cause.getMessage(), cause);
    }
}
