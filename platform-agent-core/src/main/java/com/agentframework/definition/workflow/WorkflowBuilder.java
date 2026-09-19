package com.agentframework.definition.workflow;

import com.agentframework.definition.DefinitionValidationException;
import com.agentframework.definition.ValidationReport;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.region.RegionDefinition;

import java.util.*;

/**
 * {@link WorkflowDefinition} 的流式构建器。
 *
 * <pre>
 * WorkflowDefinition wf = WorkflowBuilder.create("research", "1.0.0")
 *         .node(LlmNodeDefinition.of("plan", "plan-prompt", "plan"))
 *         .node(ToolNodeDefinition.of("search", "web-search", "hits"))
 *         .edge("plan", "search")
 *         .edge("search", "done")
 *         .slot(SlotSpec.required("question", SlotType.STRING))
 *         .build();
 * </pre>
 */
public final class WorkflowBuilder {

    private final String id;
    private final String version;
    private final List<NodeDefinition> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, SlotSpec> slots = new LinkedHashMap<>();
    private final Map<String, ToolRequirement> toolRequirements = new LinkedHashMap<>();
    private final List<String> guardRefs = new ArrayList<>();
    private final List<String> filterRefs = new ArrayList<>();
    private final List<String> interceptorRefs = new ArrayList<>();
    private final List<TracePoint> tracePoints = new ArrayList<>();
    private final List<RegionDefinition> regions = new ArrayList<>();
    private final Map<String, List<String>> dynamicTargets = new LinkedHashMap<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private PromptPolicy promptPolicy = PromptPolicy.defaults();
    private CachePolicy cachePolicy = CachePolicy.disabled();
    private SlotPolicy slotPolicy;

    private WorkflowBuilder(String id, String version) {
        this.id = id;
        this.version = version;
    }

    /**
     * @param id      工作流 id
     * @param version 版本号
     * @return 新的构建器
     */
    public static WorkflowBuilder create(String id, String version) {
        return new WorkflowBuilder(id, version);
    }

    /**
     * 添加（或替换）一个节点。
     *
     * @param node 节点定义
     * @return 当前构建器
     */
    public WorkflowBuilder node(NodeDefinition node) {
        nodes.removeIf(existing -> existing.id().equals(node.id()));
        nodes.add(node);
        return this;
    }

    /**
     * 添加一条无条件边。
     *
     * @param from 起点节点 id
     * @param to   终点节点 id
     * @return 当前构建器，便于连续书写
     */
    public WorkflowBuilder edge(String from, String to) {
        edges.add(Edge.of(from, to));
        return this;
    }

    /**
     * 添加一条带守卫的边。
     *
     * @param from     起点节点 id
     * @param to       终点节点 id
     * @param guardRef 守卫名称
     * @return 当前构建器
     */
    public WorkflowBuilder edgeGuard(String from, String to, String guardRef) {
        edges.add(Edge.of(from, to).withGuard(guardRef));
        return this;
    }

    /**
     * 添加一条带优先级的边（优先级高者先被选中）。
     *
     * @param from     起点节点 id
     * @param to       终点节点 id
     * @param priority 优先级
     * @return 当前构建器
     */
    public WorkflowBuilder edgePriority(String from, String to, int priority) {
        edges.add(Edge.of(from, to).withPriority(priority));
        return this;
    }

    /**
     * 添加一条条件边。
     *
     * @param from       起点节点 id
     * @param to         终点节点 id
     * @param expression 条件表达式
     * @return 当前构建器
     */
    public WorkflowBuilder when(String from, String to, String expression) {
        edges.add(Edge.conditional(from, to, expression));
        return this;
    }

    /**
     * @param spec 槽位声明
     * @return 当前构建器
     */
    public WorkflowBuilder slot(SlotSpec spec) {
        slots.put(spec.name(), spec);
        return this;
    }

    /**
     * @param name 槽位名
     * @param type 槽位类型
     * @return 当前构建器
     */
    public WorkflowBuilder slot(String name, SlotType type) {
        return slot(SlotSpec.of(name, type));
    }

    /**
     * @param name 槽位名
     * @param type 槽位类型
     * @return 当前构建器
     */
    public WorkflowBuilder requiredSlot(String name, SlotType type) {
        return slot(SlotSpec.required(name, type));
    }

    /**
     * 声明 Region 局部槽位模板：名字是短名，在带 {@code slotPrefix} 的 Region 内
     * 写入时由引擎展开为 {@code {prefix}_{name}} 物理槽，变量表同时以短名暴露。
     *
     * @param name 短名
     * @param type 槽位类型
     * @return 当前构建器
     */
    public WorkflowBuilder regionSlot(String name, SlotType type) {
        return slot(SlotSpec.of(name, type).withScope(SlotScope.REGION));
    }

    /**
     * 显式启用严格槽位策略：条件表达式引用未知槽位为阻断性错误。
     *
     * <p>默认策略为「声明过任意 {@code .slot(...)} 即严格」；本方法允许在零声明时也启用严格校验。</p>
     *
     * @return 当前构建器
     */
    public WorkflowBuilder strictSlots() {
        this.slotPolicy = SlotPolicy.STRICT;
        return this;
    }

    /**
     * 显式启用开放槽位策略：未知槽位引用仅警告（附已知槽位候选）。
     *
     * <p>适合探索期工作流或槽位完全由执行器自管理的场景。</p>
     *
     * @return 当前构建器
     */
    public WorkflowBuilder openSlots() {
        this.slotPolicy = SlotPolicy.OPEN;
        return this;
    }

    /**
     * @param toolId 必选工具 id
     * @return 当前构建器
     */
    public WorkflowBuilder requireTool(String toolId) {
        toolRequirements.put(toolId, ToolRequirement.required(toolId));
        return this;
    }

    /**
     * @param toolId 可选工具 id
     * @return 当前构建器
     */
    public WorkflowBuilder optionalTool(String toolId) {
        toolRequirements.put(toolId, ToolRequirement.optional(toolId));
        return this;
    }

    /**
     * @param promptPolicy Prompt 默认策略
     * @return 当前构建器
     */
    public WorkflowBuilder promptPolicy(PromptPolicy promptPolicy) {
        this.promptPolicy = promptPolicy;
        return this;
    }

    /**
     * @param refs 工作流级守卫名称
     * @return 当前构建器
     */
    public WorkflowBuilder guards(String... refs) {
        Collections.addAll(guardRefs, refs);
        return this;
    }

    /**
     * @param refs 工作流级过滤器名称
     * @return 当前构建器
     */
    public WorkflowBuilder filters(String... refs) {
        Collections.addAll(filterRefs, refs);
        return this;
    }

    /**
     * @param refs 工作流级拦截器名称
     * @return 当前构建器
     */
    public WorkflowBuilder interceptors(String... refs) {
        Collections.addAll(interceptorRefs, refs);
        return this;
    }

    /**
     * @param cachePolicy 工作流级缓存策略
     * @return 当前构建器
     */
    public WorkflowBuilder cache(CachePolicy cachePolicy) {
        this.cachePolicy = cachePolicy;
        return this;
    }

    /**
     * @param name 埋点名
     * @return 当前构建器
     */
    public WorkflowBuilder tracePoint(String name) {
        tracePoints.add(TracePoint.of(name));
        return this;
    }

    /**
     * 添加（或替换）一个 Region 声明。
     *
     * @param region 区域定义
     * @return 当前构建器
     */
    public WorkflowBuilder region(RegionDefinition region) {
        if (region != null) {
            regions.removeIf(existing -> existing.id().equals(region.id()));
            regions.add(region);
        }
        return this;
    }

    /**
     * 批量添加 Region 声明。
     *
     * @param regions 区域定义
     * @return 当前构建器
     */
    public WorkflowBuilder regions(RegionDefinition... regions) {
        if (regions != null) {
            for (RegionDefinition region : regions) {
                region(region);
            }
        }
        return this;
    }

    /**
     * 声明节点的动态路由白名单：节点只能在这些出边之间自主选择。
     *
     * @param nodeId  节点 id
     * @param targets 允许的目标节点 id
     * @return 当前构建器
     */
    public WorkflowBuilder dynamic(String nodeId, String... targets) {
        dynamicTargets.put(nodeId, List.of(targets == null ? new String[0] : targets));
        return this;
    }

    /**
     * @param key   元数据键
     * @param value 元数据值
     * @return 当前构建器
     */
    public WorkflowBuilder metadata(String key, Object value) {
        metadata.put(key, value);
        return this;
    }

    /**
     * 构建并做结构校验。
     *
     * @return 校验通过的 {@link WorkflowDefinition}
     * @throws DefinitionValidationException 存在结构问题时抛出
     */
    public WorkflowDefinition build() {
        WorkflowDefinition workflow = buildUnvalidated();
        ValidationReport report = workflow.validateReport();
        if (report.hasErrors()) {
            throw new DefinitionValidationException("workflow '" + workflow.key() + "'", report);
        }
        return workflow;
    }

    /**
     * 构建但不做结构校验，便于先拼装后统一校验。
     *
     * @return 工作流定义
     * @throws DefinitionValidationException 未添加任何节点时抛出
     */
    public WorkflowDefinition buildUnvalidated() {
        if (nodes.isEmpty()) {
            throw new DefinitionValidationException("workflow '" + id + "'", List.of("no nodes were added"));
        }
        SlotPolicy effectiveSlotPolicy = slotPolicy != null ? slotPolicy
                : slots.isEmpty() ? SlotPolicy.OPEN : SlotPolicy.STRICT;
        return new WorkflowDefinition(id, version, nodes, edges, new SlotsSchema(slots), effectiveSlotPolicy,
                List.copyOf(new LinkedHashSet<>(toolRequirements.values())), promptPolicy, filterRefs,
                interceptorRefs, guardRefs, cachePolicy, tracePoints, regions, DynamicPolicy.of(dynamicTargets),
                metadata);
    }

}
