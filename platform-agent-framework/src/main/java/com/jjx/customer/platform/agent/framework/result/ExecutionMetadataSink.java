package com.jjx.customer.platform.agent.framework.result;

import com.jjx.customer.platform.agent.framework.trace.AgentTrace;

/**
 * 执行中流出元数据的接口（引擎 → 调用方/管线），解决时序问题：
 * 引用映射与指纹必须**先于流式生成**就绪，不能等执行结束。
 *
 * <p>所有方法都应为非阻塞、幂等容忍（引擎可能多次回调）。</p>
 */
public interface ExecutionMetadataSink {

    /** 上下文产物就绪（检索/证据完成即可下发）。 */
    default void onContextReady(ContextBundle context) {
    }

    /** 引用索引就绪（流式开始前必须调用）。 */
    default void onCitationsReady(CitationIndex index) {
    }

    /** 执行指纹就绪（缓存 key 的先决信息）。 */
    default void onFingerprint(ExecutionFingerprint fingerprint) {
    }

    /** 检索明细就绪（eval 通道）。 */
    default void onRetrievalStats(RetrievalStats stats) {
    }

    /** 轨迹增量。 */
    default void onTraceUpdate(AgentTrace trace) {
    }

    /** 空实现（调用方不关心元数据时使用）。 */
    ExecutionMetadataSink NOOP = new ExecutionMetadataSink() {
    };
}
