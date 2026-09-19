package com.agentframework.engine.promptmanager;

import java.util.Optional;

/**
 * Prompt 资产来源扩展点。
 *
 * <p>资产可以来自内存、文件、数据库或配置中心；引擎只按 {@code id + version} 取用。</p>
 */
public interface PromptProvider {

    /**
     * 获取 Prompt 资产。
     *
     * @param id      资产 id
     * @param version 版本号，{@code latest} 表示最新
     * @return Prompt 资产
     * @throws java.util.NoSuchElementException 资产不存在时抛出
     */
    Prompt get(String id, String version);

    /**
     * 尝试获取 Prompt 资产，不抛异常。
     *
     * @param id      资产 id
     * @param version 版本号
     * @return 资产，不存在时为空
     */
    default Optional<Prompt> find(String id, String version) {
        try {
            return Optional.ofNullable(get(id, version));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
