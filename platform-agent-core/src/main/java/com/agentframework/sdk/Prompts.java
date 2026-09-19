package com.agentframework.sdk;

import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.prompt.PromptScope;

/**
 * Prompt 资产助手。
 */
public final class Prompts {

    private Prompts() {
    }

    /**
     * @param id       资产 id
     * @param template 模板正文
     * @return 版本为 latest 的 Prompt 定义
     */
    public static PromptDefinition template(String id, String template) {
        return PromptDefinition.template(id, template);
    }

    /**
     * 定义带版本与作用域的 Prompt。
     *
     * @param id       资产 id
     * @param version  版本号
     * @param scope    作用域
     * @param template 模板正文
     * @return Prompt 定义
     */
    public static PromptDefinition of(String id, String version, PromptScope scope, String template) {
        return PromptDefinition.of(id, version, template).withScope(scope);
    }

    /**
     * 定义系统提示词。
     *
     * @param id       资产 id
     * @param template 模板正文
     * @return Prompt 定义
     */
    public static PromptDefinition system(String id, String template) {
        return PromptDefinition.template(id, template)
                .withVariable("input", "string", false)
                .withVariable("slots", "object", false);
    }
}
