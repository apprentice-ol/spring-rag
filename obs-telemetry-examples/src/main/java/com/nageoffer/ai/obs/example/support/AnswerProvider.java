package com.nageoffer.ai.obs.example.support;

/**
 * 回答提供器：输入问题，返回模型回答。
 *
 * <p>示例中用于替换“真实 RAG 调用”；生产实现通常是调用应用自己的检索问答链路。</p>
 */
@FunctionalInterface
public interface AnswerProvider {

    /**
     * 根据问题返回模型回答。
     *
     * @param question 问题
     * @return 模型回答
     */
    String apply(String question);
}
