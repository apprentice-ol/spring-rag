package com.agentframework.runtime.session;

/** 会话的写视图：只有引擎与上下文管理器持有。 */
public interface MutableSession extends Session {

    /** @param state 新的会话状态 */
    void state(SessionState state);

    /** @param cursor 新的运行游标 */
    void cursor(Cursor cursor);

    /** @param message 追加的消息 */
    void appendMessage(Message message);

    /** @param output 最终输出 */
    void output(String output);

    /** @param error 失败原因 */
    void error(String error);

    /** @param traceId 链路追踪 id */
    void traceId(String traceId);

    /**
     * @param extensionId 扩展 id
     * @param version     本次会话固定的版本
     */
    void extensionVersion(String extensionId, String version);
}
