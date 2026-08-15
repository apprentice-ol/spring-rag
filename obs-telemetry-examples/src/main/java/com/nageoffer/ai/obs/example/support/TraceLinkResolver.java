package com.nageoffer.ai.obs.example.support;

/**
 * 运行记录定位器：输入问题，返回该次运行在 Langfuse 中的 trace/observation 定位。
 *
 * <p>生产实现通常在跑完问答后按会话/请求 id 反查 Langfuse trace（或用 Langfuse 会话关联）。</p>
 */
@FunctionalInterface
public interface TraceLinkResolver {

    /**
     * 根据问题定位运行记录。
     *
     * @param question 问题
     * @return trace 定位信息
     */
    TraceLink apply(String question);
}
