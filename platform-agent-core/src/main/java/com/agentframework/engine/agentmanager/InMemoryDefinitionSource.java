package com.agentframework.engine.agentmanager;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 内存定义仓库：按 id 维护版本序列，并支持 {@code latest} 解析。
 *
 * <p>用于示例、测试与本地开发；生产环境实现 {@link DefinitionSource} 对接配置中心即可。</p>
 */
public final class InMemoryDefinitionSource implements DefinitionSource {

    private final Map<String, List<AgentDefinition>> agents = new LinkedHashMap<>();
    private final Map<String, List<WorkflowDefinition>> workflows = new LinkedHashMap<>();
    private final Map<String, List<PromptDefinition>> prompts = new LinkedHashMap<>();
    private final Map<String, List<ToolDefinition>> tools = new LinkedHashMap<>();

    /**
     * @param definition Agent 定义
     * @return 当前仓库
     */
    public InMemoryDefinitionSource add(AgentDefinition definition) {
        agents.computeIfAbsent(definition.id(), ignored -> new ArrayList<>()).add(definition);
        return this;
    }

    /**
     * @param definition 工作流定义
     * @return 当前仓库
     */
    public InMemoryDefinitionSource add(WorkflowDefinition definition) {
        workflows.computeIfAbsent(definition.id(), ignored -> new ArrayList<>()).add(definition);
        return this;
    }

    /**
     * @param definition Prompt 定义
     * @return 当前仓库
     */
    public InMemoryDefinitionSource add(PromptDefinition definition) {
        prompts.computeIfAbsent(definition.id(), ignored -> new ArrayList<>()).add(definition);
        return this;
    }

    /**
     * @param definition 工具定义
     * @return 当前仓库
     */
    public InMemoryDefinitionSource add(ToolDefinition definition) {
        tools.computeIfAbsent(definition.id(), ignored -> new ArrayList<>()).add(definition);
        return this;
    }

    @Override
    public Optional<AgentDefinition> agent(String id, String version) {
        return resolve(agents.get(id), version, AgentDefinition::version);
    }

    @Override
    public Optional<WorkflowDefinition> workflow(String id, String version) {
        return resolve(workflows.get(id), version, WorkflowDefinition::version);
    }

    @Override
    public Optional<PromptDefinition> prompt(String id, String version) {
        return resolve(prompts.get(id), version, PromptDefinition::version);
    }

    @Override
    public Optional<ToolDefinition> tool(String id, String version) {
        return resolve(tools.get(id), version, ToolDefinition::version);
    }

    @Override
    public List<AgentDefinition> agents() {
        return agents.values().stream().flatMap(List::stream).toList();
    }

    /** @return 已注册的工作流数量 */
    public int workflowCount() {
        return workflows.values().stream().mapToInt(List::size).sum();
    }

    /** @return 已注册的工具定义数量 */
    public int toolCount() {
        return tools.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 版本解析：精确匹配优先；{@code latest} 取版本号最大的定义。
     *
     * @param candidates 候选定义
     * @param version    目标版本
     * @param versionOf  版本提取函数
     * @param <T>        定义类型
     * @return 匹配到的定义
     */
    private <T> Optional<T> resolve(List<T> candidates, String version, Function<T, String> versionOf) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (version == null || version.isBlank() || AgentDefinition.LATEST.equals(version)) {
            return candidates.stream().max(Comparator.comparing(versionOf));
        }
        return candidates.stream().filter(candidate -> versionOf.apply(candidate).equals(version)).findFirst();
    }
}
