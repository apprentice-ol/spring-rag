package com.agentframework.engine.agentmanager;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.codec.DefinitionKind;
import com.agentframework.definition.codec.DefinitionLoader;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.runtime.persistence.DefinitionRecord;
import com.agentframework.runtime.persistence.DefinitionStatus;
import com.agentframework.runtime.persistence.DefinitionStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储支撑的定义来源：只读取已发布版本，供引擎装载使用。
 *
 * <p>发布新版本后调用 {@link #invalidate(String)} 失效缓存，引擎即可看到新定义。</p>
 */
public final class StoreBackedDefinitionSource implements DefinitionSource {

    private final DefinitionStore store;
    private final DefinitionLoader loader;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    /**
     * @param store 定义存储
     */
    public StoreBackedDefinitionSource(DefinitionStore store) {
        this(store, new DefinitionLoader());
    }

    /**
     * @param store  定义存储
     * @param loader 定义加载器
     */
    public StoreBackedDefinitionSource(DefinitionStore store, DefinitionLoader loader) {
        if (store == null) {
            throw new IllegalArgumentException("definition store is required");
        }
        this.store = store;
        this.loader = loader == null ? new DefinitionLoader() : loader;
    }

    @Override
    public Optional<AgentDefinition> agent(String id, String version) {
        return load(DefinitionKind.AGENT, id, version).map(AgentDefinition.class::cast);
    }

    @Override
    public Optional<WorkflowDefinition> workflow(String id, String version) {
        return load(DefinitionKind.WORKFLOW, id, version).map(WorkflowDefinition.class::cast);
    }

    @Override
    public Optional<PromptDefinition> prompt(String id, String version) {
        return load(DefinitionKind.PROMPT, id, version).map(PromptDefinition.class::cast);
    }

    @Override
    public Optional<ToolDefinition> tool(String id, String version) {
        return load(DefinitionKind.TOOL, id, version).map(ToolDefinition.class::cast);
    }

    @Override
    public List<AgentDefinition> agents() {
        return store.list(DefinitionKind.AGENT, DefinitionStatus.PUBLISHED).stream()
                .map(record -> (AgentDefinition) decode(record))
                .toList();
    }

    /**
     * 失效某定义的缓存。
     *
     * @param id 定义 id
     */
    public void invalidate(String id) {
        cache.keySet().removeIf(key -> key.contains("|" + id + "|"));
    }

    /** @return 已缓存的版本数量 */
    public int cachedVersions() {
        return cache.size();
    }

    /**
     * @param kind    定义种类
     * @param id      定义 id
     * @param version 版本号，{@code latest} 表示最新已发布
     * @return 定义对象
     */
    private Optional<Object> load(DefinitionKind kind, String id, String version) {
        DefinitionRecord record = version == null || version.isBlank() || AgentDefinition.LATEST.equals(version)
                ? store.latestPublished(kind, id).orElse(null)
                : store.find(kind, id, version, DefinitionStatus.PUBLISHED).orElse(null);
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(decode(record));
    }

    /**
     * @param record 已发布记录
     * @return 定义对象
     */
    private Object decode(DefinitionRecord record) {
        String key = record.kind().wireName() + "|" + record.id() + "|" + record.version();
        return cache.computeIfAbsent(key, ignored -> {
            var result = loader.load(record.kind(), record.document());
            if (!result.accepted()) {
                throw new IllegalStateException("已发布定义无法加载：" + record.key() + " → "
                        + result.report().errorMessages());
            }
            return result.definition();
        });
    }
}
