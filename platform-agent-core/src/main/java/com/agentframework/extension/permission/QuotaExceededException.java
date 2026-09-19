package com.agentframework.extension.permission;

/** 配额超限异常：会话消耗超过声明的资源上限。 */
public class QuotaExceededException extends RuntimeException {

    private final String sessionId;
    private final String resource;

    /**
     * @param sessionId 会话 id
     * @param resource  超限资源名（tokens / nodes / toolCalls / wallTime）
     * @param detail    详情描述
     */
    public QuotaExceededException(String sessionId, String resource, String detail) {
        super("会话 " + sessionId + " 超出配额 " + resource + "：" + detail);
        this.sessionId = sessionId;
        this.resource = resource;
    }

    /** @return 会话 id */
    public String sessionId() {
        return sessionId;
    }

    /** @return 超限资源名 */
    public String resource() {
        return resource;
    }
}
