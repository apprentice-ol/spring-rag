package com.agentframework.engine.promptmanager;

import com.agentframework.engine.policy.ResolvedPolicy;

/**
 * Prompt 构建请求。
 *
 * @param promptId      Prompt 资产 id
 * @param promptVersion 版本号，{@code latest} 表示最新
 * @param context       渲染上下文
 * @param policy        该节点的已解析策略，null 表示走全局注册语义
 */
public record PromptRequest(String promptId, String promptVersion, PromptContext context, ResolvedPolicy policy) {

    /**
     * @param promptId      Prompt 资产 id
     * @param promptVersion 版本号
     * @param context       渲染上下文
     */
    public PromptRequest(String promptId, String promptVersion, PromptContext context) {
        this(promptId, promptVersion, context, null);
    }

    public PromptRequest {
        if (promptId == null || promptId.isBlank()) {
            throw new IllegalArgumentException("构建 Prompt 需要指定 promptId");
        }
        promptVersion = promptVersion == null ? "latest" : promptVersion;
        context = context == null ? PromptContext.of(null) : context;
    }

    /**
     * @param promptId Prompt 资产 id
     * @param context  渲染上下文
     * @return 使用最新版本的构建请求
     */
    public static PromptRequest of(String promptId, PromptContext context) {
        return new PromptRequest(promptId, null, context, null);
    }

    /**
     * @param promptId      Prompt 资产 id
     * @param promptVersion 版本号
     * @param context       渲染上下文
     * @return 指定版本的构建请求
     */
    public static PromptRequest of(String promptId, String promptVersion, PromptContext context) {
        return new PromptRequest(promptId, promptVersion, context, null);
    }

    /**
     * @param policy 已解析策略
     * @return 绑定策略后的请求
     */
    public PromptRequest withPolicy(ResolvedPolicy policy) {
        return new PromptRequest(promptId, promptVersion, context, policy);
    }
}
