package com.agentframework.engine.promptmanager;

/**
 * Prompt 渲染器扩展点。
 *
 * <p>内置模板渲染器处理 {@code {{变量}}}；需要模板引擎、多语言或结构化渲染时注册自己的实现。</p>
 */
public interface PromptRenderer {

    /** @return 渲染器名称，对应 {@code Prompt.renderer()} */
    String name();

    /**
     * 渲染模板。
     *
     * @param prompt  Prompt 资产
     * @param context 渲染上下文
     * @return 渲染结果
     */
    String render(Prompt prompt, PromptContext context);
}
