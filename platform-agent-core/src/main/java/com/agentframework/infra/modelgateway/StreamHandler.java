package com.agentframework.infra.modelgateway;

/**
 * 模型流式回调：每收到一段文本片段调用一次。
 *
 * <p>刻意只做单方法回调——流式调用的完成与失败仍由 {@code stream(...)} 的返回值与异常交付
 * （阻塞到流结束返回聚合结果），与 {@code complete} 的同步契约保持一致，内核无需异步原语。
 * 回调抛出的异常由调用方隔离，不应中断模型流。</p>
 */
@FunctionalInterface
public interface StreamHandler {

    /**
     * @param piece 本次到达的文本片段（非空，可能为多字符）
     */
    void onToken(String piece);
}
