package com.agentframework.engine.policy;

import com.agentframework.engine.promptmanager.Prompt;

/**
 * Prompt 资产查询：把 Prompt 资产上的守卫 / 过滤器引用并入节点作用域。
 *
 * <p>未注册的资产返回 null，解析器会退化为只使用节点自身声明。</p>
 */
@FunctionalInterface
public interface PromptLookup {

    /** 不做任何解析的空实现。 */
    PromptLookup EMPTY = (promptId, promptVersion) -> null;

    /**
     * @param promptId      Prompt 资产 id
     * @param promptVersion 版本号
     * @return Prompt 资产，不存在时返回 null
     */
    Prompt find(String promptId, String promptVersion);
}
