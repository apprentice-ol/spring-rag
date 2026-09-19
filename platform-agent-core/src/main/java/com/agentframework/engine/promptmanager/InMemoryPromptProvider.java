package com.agentframework.engine.promptmanager;

import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.engine.agentmanager.DefinitionSource;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 内存 Prompt 资产仓库：支持直接注册，也可回退到 {@link DefinitionSource} 读取定义。
 */
public final class InMemoryPromptProvider implements PromptProvider {

    private final DefinitionSource definitionSource;
    private final Map<String, Map<String, Prompt>> prompts = new LinkedHashMap<>();

    /** 创建不依赖定义来源的仓库。 */
    public InMemoryPromptProvider() {
        this(null);
    }

    /**
     * @param definitionSource 定义来源，可为 null
     */
    public InMemoryPromptProvider(DefinitionSource definitionSource) {
        this.definitionSource = definitionSource;
    }

    /**
     * 注册定义层 Prompt。
     *
     * @param definition Prompt 定义
     * @return 当前仓库
     */
    public InMemoryPromptProvider register(PromptDefinition definition) {
        return register(new Prompt(definition.id(), definition.version(), definition.template(),
                definition.scope(), definition.renderer(), definition.requiredVariables(),
                definition.filterRefs(), definition.guardRefs()));
    }

    /**
     * 注册运行期 Prompt 资产。
     *
     * @param prompt Prompt 资产
     * @return 当前仓库
     */
    public InMemoryPromptProvider register(Prompt prompt) {
        prompts.computeIfAbsent(prompt.id(), ignored -> new LinkedHashMap<>()).put(prompt.version(), prompt);
        return this;
    }

    @Override
    public Prompt get(String id, String version) {
        return find(id, version).orElseThrow(() -> new NoSuchElementException(
                "未找到 Prompt 资产：" + id + "@" + (version == null ? "latest" : version)));
    }

    @Override
    public Optional<Prompt> find(String id, String version) {
        Map<String, Prompt> versions = prompts.get(id);
        if (versions != null && !versions.isEmpty()) {
            if (version == null || version.isBlank() || "latest".equals(version)) {
                return versions.values().stream().max(Comparator.comparing(Prompt::version));
            }
            Prompt exact = versions.get(version);
            if (exact != null) {
                return Optional.of(exact);
            }
        }
        if (definitionSource != null) {
            return definitionSource.prompt(id, version).map(definition -> new Prompt(definition.id(),
                    definition.version(), definition.template(), definition.scope(), definition.renderer(),
                    definition.requiredVariables(), definition.filterRefs(), definition.guardRefs()));
        }
        return Optional.empty();
    }

    /** @return 已注册的资产数量 */
    public int size() {
        return prompts.values().stream().mapToInt(Map::size).sum();
    }

    /** @return 全部资产 id */
    public List<String> ids() {
        return List.copyOf(prompts.keySet());
    }
}
