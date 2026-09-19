package com.agentframework.runtime.session;

/** 会话生命周期状态。 */
public enum SessionState {
    CREATED,
    RUNNING,
    /** 停在人工节点：cursor 已持久化，可稍后恢复。 */
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** @return 是否终态（完成 / 失败 / 取消） */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
