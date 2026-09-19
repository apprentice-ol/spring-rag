package com.agentframework.engine.agentmanager;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 默认 Agent 管理器：解析工作流、校验定义并缓存实例。
 *
 * <p>缓存以 {@code id@version} 为键，同一版本复用同一实例，避免重复解析。</p>
 */
public final class DefaultAgentManager implements AgentManager {

    private final DefinitionSource definitionSource;
    private final AgentValidator validator;
    private final Map<String, Agent> cache = new LinkedHashMap<>();

    /**
     * @param definitionSource 定义来源
     * @param validator        定义校验器
     */
    public DefaultAgentManager(DefinitionSource definitionSource, AgentValidator validator) {
        this.definitionSource = definitionSource;
        this.validator = validator;
    }

    @Override
    public Agent create(AgentDefinition agentDefinition) {
        String key = agentDefinition.key();
        synchronized (cache) {
            Agent cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        WorkflowDefinition workflowDefinition = definitionSource
                .workflow(agentDefinition.workflowId(), agentDefinition.workflowVersion())
                .orElse(null);
        validator.validateOrThrow(agentDefinition, workflowDefinition);
        Agent agent = new DefaultAgent(agentDefinition, workflowDefinition);
        synchronized (cache) {
            cache.put(key, agent);
        }
        return agent;
    }

    @Override
    public Agent load(String agentId, String version) {
        AgentDefinition definition = definitionSource.agent(agentId, version)
                .orElseThrow(() -> new NoSuchElementException(
                        "未找到 Agent 定义：" + agentId + "@" + (version == null ? "latest" : version)));
        return create(definition);
    }

    @Override
    public List<Agent> list() {
        synchronized (cache) {
            return List.copyOf(cache.values());
        }
    }

    @Override
    public boolean destroy(String agentId, String version) {
        synchronized (cache) {
            return cache.remove(agentId + "@" + (version == null ? AgentDefinition.LATEST : version)) != null;
        }
    }
}
