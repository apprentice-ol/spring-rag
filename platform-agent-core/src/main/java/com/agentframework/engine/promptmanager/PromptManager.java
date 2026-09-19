package com.agentframework.engine.promptmanager;

/**
 * Prompt 管理器：负责资产的解析、渲染、过滤与守卫。
 *
 * <p>Prompt 是资产、Manager 负责构建——这是把提示词从代码中抽离出来的关键一环。</p>
 */
public interface PromptManager {

    /**
     * 解析 Prompt 资产。
     *
     * @param promptId      资产 id
     * @param promptVersion 版本号
     * @return Prompt 资产
     */
    Prompt resolve(String promptId, String promptVersion);

    /**
     * 渲染模板。
     *
     * @param prompt  Prompt 资产
     * @param context 渲染上下文
     * @return 渲染结果
     */
    String render(Prompt prompt, PromptContext context);

    /**
     * 完整构建：解析 → 渲染 → 过滤 → 守卫。
     *
     * @param request 构建请求
     * @return 最终提示词文本
     */
    String build(PromptRequest request);
}
