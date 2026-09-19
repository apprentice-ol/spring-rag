package com.agentframework.engine.policy;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.promptmanager.Prompt;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已解析策略索引：会话/工作流启动期预热，运行期只做查表。
 *
 * <p>索引同时提供 Agent 级与节点级解析结果。节点级结果 = 全局 + Agent + Workflow + Node，
 * 后续 Region 只需插入一格，索引结构与调用方都无需改动。</p>
 */
public final class ResolvedPolicyIndex {

    private final ResolvedPolicy agentLevel;
    private final Map<String, ResolvedPolicy> nodes;
    private final long catalogVersion;

    private ResolvedPolicyIndex(ResolvedPolicy agentLevel, Map<String, ResolvedPolicy> nodes, long catalogVersion) {
        this.agentLevel = agentLevel;
        this.nodes = Map.copyOf(nodes);
        this.catalogVersion = catalogVersion;
    }

    /**
     * 构建索引。
     *
     * @param resolver   策略解析器
     * @param agent      Agent 定义
     * @param workflow   工作流定义
     * @return 已解析策略索引
     */
    public static ResolvedPolicyIndex build(PolicyResolver resolver, AgentDefinition agent,
            WorkflowDefinition workflow) {
        PolicyScope global = PolicyScope.global();
        PolicyScope agentScope = PolicyScope.agent(agent);
        PolicyScope workflowScope = PolicyScope.workflow(workflow);
        ResolvedPolicy agentLevel = resolver.resolve(PolicyScopeChain.of(global, agentScope, workflowScope));
        Map<String, ResolvedPolicy> nodes = new LinkedHashMap<>();
        for (NodeDefinition node : workflow.nodes()) {
            RegionDefinition region = workflow.regionOf(node.id()).flatMap(workflow::region).orElse(null);
            ResolvedPolicy resolved = region == null
                    ? resolver.resolve(PolicyScopeChain.of(global, agentScope, workflowScope,
                            nodeScope(resolver, node)))
                    : resolver.resolve(PolicyScopeChain.of(global, agentScope, workflowScope,
                            PolicyScope.region(region), nodeScope(resolver, node)))
                            .withRegion(region.id(), region.paradigm().name(), region.policy().quota(),
                                    region.loop());
            nodes.put(node.id(), resolved);
        }
        return new ResolvedPolicyIndex(agentLevel, nodes, resolver.catalogVersion());
    }

    /**
     * 构建节点作用域：LLM 节点会把 Prompt 资产上的守卫 / 过滤器引用一并并入。
     *
     * @param resolver 解析器
     * @param node     节点定义
     * @return 节点作用域
     */
    private static PolicyScope nodeScope(PolicyResolver resolver, NodeDefinition node) {
        if (node instanceof LlmNodeDefinition llm) {
            Prompt prompt = resolver.promptLookup().find(llm.promptRef(), llm.promptVersion());
            if (prompt != null) {
                return PolicyScope.node(node, prompt);
            }
        }
        return PolicyScope.node(node);
    }

    /**
     * @param agent     Agent 定义
     * @param workflow  工作流定义
     * @param version   目录版本
     * @return 可用于缓存的键
     */
    public static String cacheKey(AgentDefinition agent, WorkflowDefinition workflow, long version) {
        return agent.key() + "|" + workflow.key() + "|" + version;
    }

    /** @return Agent 级解析结果（全局 + Agent + Workflow） */
    public ResolvedPolicy agentLevel() {
        return agentLevel;
    }

    /**
     * @param nodeId 节点 id
     * @return 节点级解析结果，未知节点回退到 Agent 级
     */
    public ResolvedPolicy node(String nodeId) {
        return nodes.getOrDefault(nodeId, agentLevel);
    }

    /** @return 索引构建时的目录版本 */
    public long catalogVersion() {
        return catalogVersion;
    }

    /** @return 已索引的节点数量 */
    public int size() {
        return nodes.size();
    }
}
