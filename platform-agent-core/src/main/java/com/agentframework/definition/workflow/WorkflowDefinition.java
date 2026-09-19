package com.agentframework.definition.workflow;

import com.agentframework.definition.ValidationLocations;
import com.agentframework.definition.ValidationProblem;
import com.agentframework.definition.ValidationReport;
import com.agentframework.definition.DefinitionValidationException;
import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.HumanNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.ParallelNodeDefinition;
import com.agentframework.definition.node.SubWorkflowNodeDefinition;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.region.RegionAnnotator;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.region.RegionSuggestion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 工作流定义：行为范式，描述节点、边以及依赖的槽位 / 工具 / Prompt 契约。
 *
 * <p>工作流是纯定义，不持有任何状态；状态由 {@code Session} 与 {@code Slot} 承载。</p>
 *
 * @param id              工作流 id
 * @param version         版本号，缺省为 {@link #LATEST}
 * @param nodes           节点定义列表
 * @param edges           边定义列表
 * @param slotsSchema     槽位契约
 * @param slotPolicy      槽位严格度策略（null 归一化为 {@link SlotPolicy#OPEN}）
 * @param toolRequirements 工具依赖声明
 * @param promptPolicy    Prompt 默认策略
 * @param filterRefs      工作流级过滤器引用
 * @param interceptorRefs 工作流级拦截器引用
 * @param guardRefs       工作流级守卫引用
 * @param cachePolicy     工作流级缓存策略
 * @param tracePoints     埋点声明
 * @param regions         显式声明的 Region（治理锚点，不参与路由）
 * @param dynamicPolicy   动态路由白名单
 * @param metadata        自定义元数据
 */
public record WorkflowDefinition(
        String id,
        String version,
        List<NodeDefinition> nodes,
        List<Edge> edges,
        SlotsSchema slotsSchema,
        SlotPolicy slotPolicy,
        List<ToolRequirement> toolRequirements,
        PromptPolicy promptPolicy,
        List<String> filterRefs,
        List<String> interceptorRefs,
        List<String> guardRefs,
        CachePolicy cachePolicy,
        List<TracePoint> tracePoints,
        List<RegionDefinition> regions,
        DynamicPolicy dynamicPolicy,
        Map<String, Object> metadata) {

    public static final String LATEST = "latest";

    /** LLM 节点工具调用投影槽后缀（{@code LlmNodeExecutor} 写入）。 */
    public static final String TOOL_CALLS_SLOT_SUFFIX = "_tool_calls";
    /** 工具节点结构化数据槽后缀（{@code ToolNodeExecutor} 写入）。 */
    public static final String DATA_SLOT_SUFFIX = "_data";
    /** 子工作流节点子槽位快照后缀（{@code SubWorkflowNodeExecutor} 写入）。 */
    public static final String SLOTS_SLOT_SUFFIX = "_slots";

    public WorkflowDefinition {
        Objects.requireNonNull(id, "workflow id is required");
        version = version == null || version.isBlank() ? LATEST : version;
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        edges = List.copyOf(edges == null ? List.of() : edges);
        slotsSchema = slotsSchema == null ? SlotsSchema.empty() : slotsSchema;
        slotPolicy = slotPolicy == null ? SlotPolicy.OPEN : slotPolicy;
        toolRequirements = List.copyOf(toolRequirements == null ? List.of() : toolRequirements);
        promptPolicy = promptPolicy == null ? PromptPolicy.defaults() : promptPolicy;
        filterRefs = List.copyOf(filterRefs == null ? List.of() : filterRefs);
        interceptorRefs = List.copyOf(interceptorRefs == null ? List.of() : interceptorRefs);
        guardRefs = List.copyOf(guardRefs == null ? List.of() : guardRefs);
        cachePolicy = cachePolicy == null ? CachePolicy.disabled() : cachePolicy;
        tracePoints = List.copyOf(tracePoints == null ? List.of() : tracePoints);
        regions = List.copyOf(regions == null ? List.of() : regions);
        dynamicPolicy = dynamicPolicy == null ? DynamicPolicy.NONE : dynamicPolicy;
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** @return 定义唯一键，形如 {@code research@1.0.0} */
    public String key() {
        return id + "@" + version;
    }

    /**
     * @param nodeId 节点 id
     * @return 节点定义，可为空
     */
    public Optional<NodeDefinition> node(String nodeId) {
        return nodes.stream().filter(node -> node.id().equals(nodeId)).findFirst();
    }

    /**
     * @param nodeId 节点 id
     * @return 节点定义
     * @throws IllegalArgumentException 节点不存在时抛出
     */
    public NodeDefinition requireNode(String nodeId) {
        return node(nodeId).orElseThrow(() -> new IllegalArgumentException(
                "workflow '" + id + "' has no node '" + nodeId + "'"));
    }

    /**
     * 取节点的出边，优先级高者在前。
     *
     * @param nodeId 节点 id
     * @return 出边列表
     */
    public List<Edge> outgoing(String nodeId) {
        return edges.stream()
                .filter(edge -> edge.from().equals(nodeId))
                .sorted(Comparator.comparingInt(Edge::priority).reversed())
                .toList();
    }

    /**
     * @param nodeId 节点 id
     * @return 入边列表
     */
    public List<Edge> incoming(String nodeId) {
        return edges.stream().filter(edge -> edge.to().equals(nodeId)).toList();
    }

    /**
     * 入口节点：没有入边的节点；若形成环则退化为第一个节点。
     *
     * @return 可能的起始节点列表
     */
    public List<NodeDefinition> startNodes() {
        Set<String> targets = new HashSet<>();
        edges.forEach(edge -> targets.add(edge.to()));
        List<NodeDefinition> starts = nodes.stream().filter(node -> !targets.contains(node.id())).toList();
        return starts.isEmpty() && !nodes.isEmpty() ? List.of(nodes.get(0)) : starts;
    }

    /**
     * @return 唯一的入口节点
     * @throws IllegalStateException 工作流没有节点时抛出
     */
    public NodeDefinition entryNode() {
        return startNodes().stream().findFirst().orElseThrow(() -> new IllegalStateException(
                "workflow '" + id + "' has no entry node"));
    }

    /** @return 工作流直接引用到的全部工具 id */
    public Set<String> toolIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (NodeDefinition node : nodes) {
            if (node instanceof ToolNodeDefinition tool) {
                ids.add(tool.toolRef());
            }
        }
        toolRequirements.forEach(requirement -> ids.add(requirement.toolId()));
        return ids;
    }

    /**
     * 结构校验：重复节点 id、悬空边、分支目标缺失、空工作流等。
     *
     * <p>只返回阻断性问题（{@link com.agentframework.definition.ValidationSeverity#ERROR}）的说明，
     * 与 0.1.0 的语义保持一致：返回空列表即表示定义可用。需要完整报告请使用 {@link #validateReport()}。</p>
     *
     * @return 阻断性问题的说明列表，为空表示通过
     */
    public List<String> validate() {
        return validateReport().errorMessages();
    }

    /**
     * 完整校验：结构、表达式与槽位契约。
     *
     * <p>判定阈值由 {@link SlotPolicy} 决定（默认：声明过任意槽位即 STRICT）。
     * 节点 outputSlot、派生槽与 Region 计数槽属于引擎自动识别的已知槽位，
     * 无需 {@code .slot(...)} 声明即可被条件表达式安全引用。</p>
     *
     * @return 校验报告
     */
    public ValidationReport validateReport() {
        List<ValidationProblem> problems = new ArrayList<>();
        String workflowLocation = ValidationLocations.workflow(key());
        if (nodes.isEmpty()) {
            problems.add(ValidationProblem.error("WORKFLOW_NO_NODES", workflowLocation,
                    "workflow '" + id + "' declares no nodes"));
            return new ValidationReport(problems);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (NodeDefinition node : nodes) {
            if (!ids.add(node.id())) {
                problems.add(ValidationProblem.error("GRAPH_DUPLICATE_NODE",
                        ValidationLocations.node(key(), node.id()), "duplicate node id '" + node.id() + "'"));
            }
        }
        for (Edge edge : edges) {
            if (!ids.contains(edge.from())) {
                problems.add(ValidationProblem.error("GRAPH_DANGLING_EDGE",
                        ValidationLocations.field(workflowLocation, "edge:" + edge.key()),
                        "edge '" + edge.key() + "' starts at unknown node '" + edge.from() + "'"));
            }
            if (!ids.contains(edge.to())) {
                problems.add(ValidationProblem.error("GRAPH_DANGLING_EDGE",
                        ValidationLocations.field(workflowLocation, "edge:" + edge.key()),
                        "edge '" + edge.key() + "' targets unknown node '" + edge.to() + "'"));
            }
        }
        for (NodeDefinition node : nodes) {
            if (node instanceof ConditionNodeDefinition condition) {
                if (condition.branches().isEmpty()) {
                    problems.add(ValidationProblem.error("GRAPH_CONDITION_TARGET_UNKNOWN",
                            ValidationLocations.node(key(), node.id()),
                            "condition node '" + node.id() + "' has no branches"));
                }
                for (ConditionNodeDefinition.Branch branch : condition.branches()) {
                    if (!ids.contains(branch.target())) {
                        problems.add(ValidationProblem.error("GRAPH_CONDITION_TARGET_UNKNOWN",
                                ValidationLocations.node(key(), node.id()),
                                "condition node '" + node.id() + "' targets unknown node '"
                                        + branch.target() + "'"));
                    }
                }
            }
            if (node instanceof ParallelNodeDefinition parallel) {
                for (String branch : parallel.branches()) {
                    if (!ids.contains(branch)) {
                        problems.add(ValidationProblem.error("GRAPH_PARALLEL_BRANCH_UNKNOWN",
                                ValidationLocations.node(key(), node.id()),
                                "parallel node '" + node.id() + "' references unknown node '" + branch + "'"));
                    }
                }
            }
            if (node instanceof SubWorkflowNodeDefinition sub && sub.workflowId().equals(id)) {
                problems.add(ValidationProblem.error("GRAPH_SUB_WORKFLOW_SELF_CALL",
                        ValidationLocations.node(key(), node.id()),
                        "sub-workflow node '" + node.id() + "' cannot call its own workflow"));
            }
        }
        problems.addAll(expressionProblems());
        problems.addAll(slotWriteProblems());
        problems.addAll(regionProblems());
        problems.addAll(regionSlotProblems());
        problems.addAll(backEdgeProblems());
        problems.addAll(dynamicProblems());
        return new ValidationReport(problems);
    }

    /**
     * 回边治理校验：图中真实回边（目标可达起点的边）必须落在声明了循环治理的 Region 内——
     * 「顺序用 then、真正的迭代才用 loop」，未治理的回边提示为 warning，
     * 防止无界循环悄悄混进顺序推进的图里。
     *
     * @return 问题列表
     */
    private List<ValidationProblem> backEdgeProblems() {
        List<ValidationProblem> problems = new ArrayList<>();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (Edge edge : edges) {
            adjacency.computeIfAbsent(edge.from(), from -> new ArrayList<>()).add(edge.to());
        }
        for (Edge edge : edges) {
            if (!reaches(adjacency, edge.to(), edge.from())) {
                continue; // 前向边，无需治理
            }
            boolean governed = regions.stream()
                    .filter(RegionDefinition::hasLoop)
                    .anyMatch(region -> region.nodeIds().contains(edge.from())
                            || region.nodeIds().contains(edge.to()));
            if (!governed) {
                problems.add(ValidationProblem.warning("GRAPH_BACKEDGE_UNGOVERNED",
                        ValidationLocations.field(ValidationLocations.workflow(key()), "edge:" + edge.key()),
                        "回边 '" + edge.key() + "' 不在任何声明了循环治理的 Region 内，"
                                + "顺序推进请用前向边，真正的迭代应纳入显式循环声明"));
            }
        }
        return problems;
    }

    /**
     * @param adjacency 邻接表
     * @param from      起点
     * @param to        终点
     * @return from 是否存在路径到达 to（BFS）
     */
    private static boolean reaches(Map<String, List<String>> adjacency, String from, String to) {
        Set<String> visited = new HashSet<>();
        Set<String> frontier = new HashSet<>(adjacency.getOrDefault(from, List.of()));
        while (!frontier.isEmpty()) {
            if (frontier.contains(to)) {
                return true;
            }
            Set<String> next = new HashSet<>();
            for (String node : frontier) {
                if (visited.add(node)) {
                    next.addAll(adjacency.getOrDefault(node, List.of()));
                }
            }
            frontier = next;
        }
        return false;
    }

    /**
     * 校验 Region 局部槽模板与全局槽位名空间的冲突。
     *
     * <ul>
     *   <li>模板短名与全局显式槽同名 → ERROR（同名歧义）；</li>
     *   <li>带前缀 Region 的展开名（{@code {prefix}_{短名}}）与全局显式槽同名 → ERROR；</li>
     *   <li>声明了模板但没有任何带 slotPrefix 的 Region → WARNING（模板不生效）。</li>
     * </ul>
     *
     * @return 问题列表
     */
    private List<ValidationProblem> regionSlotProblems() {
        List<ValidationProblem> problems = new ArrayList<>();
        Set<String> templates = new LinkedHashSet<>();
        Set<String> globalNames = new LinkedHashSet<>();
        for (SlotSpec spec : slotsSchema.slots().values()) {
            if (spec.scope() == SlotScope.REGION) {
                templates.add(spec.name());
            } else {
                globalNames.add(spec.name());
            }
        }
        if (templates.isEmpty()) {
            return problems;
        }
        boolean anyPrefixedRegion = regions.stream().anyMatch(RegionDefinition::hasSlotPrefix);
        if (!anyPrefixedRegion) {
            problems.add(ValidationProblem.warning("SLOT_REGION_TEMPLATE_UNUSED",
                    ValidationLocations.field(ValidationLocations.workflow(key()), "slots"),
                    "声明了 Region 局部槽模板 " + templates + "，但没有任何 Region 设置 slotPrefix，模板不会展开"));
        }
        for (String template : templates) {
            if (globalNames.contains(template)) {
                problems.add(ValidationProblem.error("SLOT_REGION_TEMPLATE_CONFLICT",
                        ValidationLocations.field(ValidationLocations.workflow(key()), "slot:" + template),
                        "Region 局部槽模板 '" + template + "' 与全局槽位同名，短名别名会产生歧义"));
            }
            for (RegionDefinition region : regions) {
                if (region.hasSlotPrefix() && globalNames.contains(region.slotPrefix() + "_" + template)) {
                    problems.add(ValidationProblem.error("SLOT_REGION_TEMPLATE_CONFLICT",
                            ValidationLocations.node(key(), region.nodeIds().isEmpty() ? region.id()
                                    : region.nodeIds().get(0)),
                            "Region '" + region.id() + "' 的展开槽 '" + region.slotPrefix() + "_" + template
                                    + "' 与全局槽位同名"));
                }
            }
        }
        return problems;
    }

    /**
     * 校验并在失败时抛出异常。
     *
     * @throws DefinitionValidationException 存在结构问题时抛出
     */
    public void validateOrThrow() {
        ValidationReport report = validateReport();
        if (report.hasErrors()) {
            throw new DefinitionValidationException("workflow '" + key() + "'", report);
        }
    }

    /**
     * 校验边条件与条件节点表达式的语法与槽位依赖。
     *
     * @return 问题列表
     */
    private List<ValidationProblem> expressionProblems() {
        List<ValidationProblem> problems = new ArrayList<>();
        boolean strict = slotPolicy == SlotPolicy.STRICT;
        Set<String> known = knownSlots();
        for (Edge edge : edges) {
            if (edge.isConditional()) {
                problems.addAll(expressionProblems(edge.condition(),
                        ValidationLocations.field(ValidationLocations.workflow(key()), "edge:" + edge.key()),
                        strict, known));
            }
        }
        for (NodeDefinition node : nodes) {
            if (!(node instanceof ConditionNodeDefinition condition)) {
                continue;
            }
            for (int i = 0; i < condition.branches().size(); i++) {
                ConditionNodeDefinition.Branch branch = condition.branches().get(i);
                if (branch.isDefault()) {
                    continue;
                }
                problems.addAll(expressionProblems(branch.expression(),
                        ValidationLocations.indexed(ValidationLocations.node(key(), node.id()), "branches", i),
                        strict, known));
            }
        }
        return problems;
    }

    /**
     * 校验单个表达式。
     *
     * @param expression 表达式文本
     * @param location   位置
     * @param strict     是否按已声明契约严格判定
     * @param known      已知槽位集合（显式声明 ∪ 引擎自动识别）
     * @return 问题列表
     */
    private List<ValidationProblem> expressionProblems(String expression, String location, boolean strict,
            Set<String> known) {
        List<ValidationProblem> problems = new ArrayList<>();
        java.util.Optional<String> syntaxError = Expression.check(expression);
        if (syntaxError.isPresent()) {
            problems.add(ValidationProblem.error("EXPRESSION_PARSE_ERROR", location,
                    "表达式语法错误：" + syntaxError.get()));
            return problems;
        }
        for (String dependency : Expression.dependencies(expression)) {
            if (known.contains(dependency)) {
                continue;
            }
            String message = "表达式引用了未声明的槽位 '" + dependency + "'";
            problems.add(strict
                    ? ValidationProblem.error("EXPRESSION_UNKNOWN_SLOT", location, message)
                    : ValidationProblem.warning("EXPRESSION_UNKNOWN_SLOT", location, message)
                            .withCandidates(List.copyOf(known)));
        }
        return problems;
    }

    /**
     * 校验节点输出槽位是否可被引擎识别；显式声明与自动识别都不再提示。
     *
     * @return 问题列表
     */
    private List<ValidationProblem> slotWriteProblems() {
        List<ValidationProblem> problems = new ArrayList<>();
        Set<String> known = knownSlots();
        for (NodeDefinition node : nodes) {
            String outputSlot = outputSlotOf(node);
            if (outputSlot == null || outputSlot.isBlank() || known.contains(outputSlot)) {
                continue;
            }
            problems.add(ValidationProblem.warning("SLOT_UNDECLARED_WRITE",
                    ValidationLocations.node(key(), node.id()),
                    "节点 '" + node.id() + "' 写入未声明的槽位 '" + outputSlot + "'"));
        }
        return problems;
    }

    /**
     * @param node 节点定义
     * @return 输出槽位名，无输出槽位的节点返回 null
     */
    public static String outputSlotOf(NodeDefinition node) {
        return switch (node) {
            case LlmNodeDefinition llm -> llm.outputSlot();
            case ToolNodeDefinition tool -> tool.outputSlot();
            case ParallelNodeDefinition parallel -> parallel.outputSlot();
            case HumanNodeDefinition human -> human.outputSlot();
            case SubWorkflowNodeDefinition sub -> sub.outputSlot();
            case CustomNodeDefinition custom -> custom.outputSlot();
            default -> null;
        };
    }

    /**
     * 已知槽位集合 = 显式 {@code .slot(...)} 声明 ∪ 引擎自动识别的槽位。
     *
     * <p>自动识别范围：各节点 outputSlot（七类有输出的节点，含 Custom）、
     * 派生槽（LLM 的 {@code *_tool_calls}、Tool 的 {@code *_data}、SubWorkflow 的 {@code *_slots}）、
     * Region 循环计数槽（counterSlot）、Region 局部槽模板（短名 + 各带前缀 Region 的
     * {@code {prefix}_{短名}} 展开名）。它们无需声明即可被条件表达式引用，
     * 也因此不再触发 {@code SLOT_UNDECLARED_WRITE}。</p>
     *
     * @return 已知槽位名集合（保持稳定顺序：显式声明在前）
     */
    public Set<String> knownSlots() {
        Set<String> names = new LinkedHashSet<>();
        Set<String> regionTemplates = new LinkedHashSet<>();
        for (SlotSpec spec : slotsSchema.slots().values()) {
            if (spec.scope() == SlotScope.REGION) {
                regionTemplates.add(spec.name());
            } else {
                names.add(spec.name());
            }
        }
        for (NodeDefinition node : nodes) {
            String outputSlot = outputSlotOf(node);
            if (outputSlot != null && !outputSlot.isBlank()) {
                names.add(outputSlot);
                switch (node) {
                    case LlmNodeDefinition llm -> names.add(llm.outputSlot() + TOOL_CALLS_SLOT_SUFFIX);
                    case ToolNodeDefinition tool -> names.add(tool.outputSlot() + DATA_SLOT_SUFFIX);
                    case SubWorkflowNodeDefinition sub -> names.add(sub.outputSlot() + SLOTS_SLOT_SUFFIX);
                    default -> {
                        // 其余节点类型无派生槽
                    }
                }
            }
        }
        for (RegionDefinition region : regions) {
            if (region.hasLoop() && region.loop().hasCounterSlot()) {
                names.add(region.loop().counterSlot());
            }
        }
        if (!regionTemplates.isEmpty()) {
            // 短名本身视为已知（校验宽容），各带前缀 Region 再补充展开名
            names.addAll(regionTemplates);
            for (RegionDefinition region : regions) {
                if (region.hasSlotPrefix()) {
                    regionTemplates.forEach(template -> names.add(region.slotPrefix() + "_" + template));
                }
            }
        }
        return names;
    }

    /**
     * @param nodeId 节点 id
     * @return 包含该节点的 Region 定义（REGION_OVERLAP 校验保证至多一个）
     */
    public Optional<RegionDefinition> regionContaining(String nodeId) {
        return regions.stream()
                .filter(region -> region.nodeIds().contains(nodeId))
                .findFirst();
    }

    /**
     * 校验 Region 声明：重复 id、未知节点、节点重叠、空区域与指标基数。
     *
     * @return 问题列表
     */
    private List<ValidationProblem> regionProblems() {
        List<ValidationProblem> problems = new ArrayList<>();
        if (regions.isEmpty()) {
            return problems;
        }
        String workflowLocation = ValidationLocations.workflow(key());
        Set<String> knownNodes = new LinkedHashSet<>();
        nodes.forEach(node -> knownNodes.add(node.id()));
        Set<String> declaredIds = new LinkedHashSet<>();
        Map<String, String> owner = new LinkedHashMap<>();
        for (RegionDefinition region : regions) {
            String location = ValidationLocations.field(workflowLocation, "region:" + region.id());
            if (!declaredIds.add(region.id())) {
                problems.add(ValidationProblem.error("REGION_DUPLICATE_ID", location,
                        "Region id 重复：'" + region.id() + "'"));
            }
            if (region.isEmpty()) {
                problems.add(ValidationProblem.warning("REGION_EMPTY", location,
                        "Region '" + region.id() + "' 未包含任何节点"));
            }
            for (String nodeId : region.nodeIds()) {
                String nodeLocation = ValidationLocations.field(location, "node:" + nodeId);
                if (!knownNodes.contains(nodeId)) {
                    problems.add(ValidationProblem.error("REGION_NODE_UNKNOWN", nodeLocation,
                            "Region '" + region.id() + "' 引用了不存在的节点 '" + nodeId + "'"));
                    continue;
                }
                String previous = owner.putIfAbsent(nodeId, region.id());
                if (previous != null && !previous.equals(region.id())) {
                    problems.add(ValidationProblem.error("REGION_OVERLAP", nodeLocation,
                            "节点 '" + nodeId + "' 同时属于区域 '" + previous + "' 与 '" + region.id() + "'"));
                }
            }
            problems.addAll(loopProblems(region, location));
        }
        if (regions.size() > 32) {
            problems.add(ValidationProblem.warning("REGION_METRIC_CARDINALITY", workflowLocation,
                    "Region 数量 " + regions.size() + " 超过建议上限 32，可能带来指标基数压力"));
        }
        return problems;
    }

    /**
     * 校验 Region 的循环声明。
     *
     * @param region   区域定义
     * @param location 区域位置
     * @return 问题列表
     */
    private List<ValidationProblem> loopProblems(RegionDefinition region, String location) {
        List<ValidationProblem> problems = new ArrayList<>();
        RegionLoop loop = region.loop();
        if (loop == null) {
            return problems;
        }
        if (!region.nodeIds().contains(loop.entry())) {
            problems.add(ValidationProblem.error("REGION_LOOP_ENTRY_UNKNOWN", location,
                    "循环入口 '" + loop.entry() + "' 不属于区域 '" + region.id() + "'"));
        }
        if (!region.nodeIds().contains(loop.exit())) {
            problems.add(ValidationProblem.error("REGION_LOOP_EXIT_UNKNOWN", location,
                    "循环出口 '" + loop.exit() + "' 不属于区域 '" + region.id() + "'"));
        }
        if (loop.maxIterations() <= 0) {
            problems.add(ValidationProblem.error("REGION_LOOP_INVALID_MAX", location,
                    "循环 '" + region.id() + "' 的迭代上限必须为正数"));
        }
        if (region.nodeIds().contains(loop.entry()) && region.nodeIds().contains(loop.exit())
                && !canReach(loop.exit(), loop.entry())) {
            problems.add(ValidationProblem.warning("REGION_LOOP_NO_CYCLE", location,
                    "循环出口 '" + loop.exit() + "' 没有回到入口 '" + loop.entry() + "' 的路径"));
        }
        return problems;
    }

    /**
     * @param regionId 区域 id
     * @return 区域定义
     */
    public Optional<RegionDefinition> region(String regionId) {
        return regions.stream().filter(region -> region.id().equals(regionId)).findFirst();
    }

    /**
     * @param nodeId 节点 id
     * @return 该节点所属的显式区域 id
     */
    public Optional<String> regionOf(String nodeId) {
        return regions.stream()
                .filter(region -> region.nodeIds().contains(nodeId))
                .map(RegionDefinition::id)
                .findFirst();
    }

    /**
     * @return 只读的 Region 推断建议（不参与执行，也不并入校验报告）
     */
    public List<RegionSuggestion> regionSuggestions() {
        return new RegionAnnotator().suggest(this);
    }

    /**
     * @param from 起点节点 id
     * @param to   终点节点 id
     * @return 是否存在该有向边
     */
    public boolean hasEdge(String from, String to) {
        return edges.stream().anyMatch(edge -> edge.from().equals(from) && edge.to().equals(to));
    }

    /**
     * 校验动态路由白名单：节点与目标必须存在，且目标必须是该节点的出边。
     *
     * @return 问题列表
     */
    private List<ValidationProblem> dynamicProblems() {
        List<ValidationProblem> problems = new ArrayList<>();
        if (dynamicPolicy.isEmpty()) {
            return problems;
        }
        Set<String> knownNodes = new LinkedHashSet<>();
        nodes.forEach(node -> knownNodes.add(node.id()));
        String workflowLocation = ValidationLocations.workflow(key());
        for (String nodeId : dynamicPolicy.nodes()) {
            String location = ValidationLocations.field(workflowLocation, "dynamic:" + nodeId);
            if (!knownNodes.contains(nodeId)) {
                problems.add(ValidationProblem.error("DYNAMIC_NODE_UNKNOWN", location,
                        "动态路由声明的节点不存在：'" + nodeId + "'"));
                continue;
            }
            for (String target : dynamicPolicy.targetsOf(nodeId)) {
                if (!knownNodes.contains(target)) {
                    problems.add(ValidationProblem.error("DYNAMIC_TARGET_UNKNOWN", location,
                            "动态路由目标不存在：'" + target + "'（节点 '" + nodeId + "'）"));
                    continue;
                }
                if (!hasEdge(nodeId, target)) {
                    problems.add(ValidationProblem.error("DYNAMIC_TARGET_NOT_EDGE", location,
                            "动态路由目标 '" + target + "' 不是节点 '" + nodeId + "' 的出边"));
                }
            }
        }
        return problems;
    }

    /**
     * 判断两个节点之间是否存在有向路径。
     *
     * @param from 起点节点 id
     * @param to   终点节点 id
     * @return 存在路径返回 true
     */
    public boolean canReach(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        Set<String> visited = new LinkedHashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(from);
        visited.add(from);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(to)) {
                return true;
            }
            for (Edge edge : outgoing(current)) {
                if (visited.add(edge.to())) {
                    queue.add(edge.to());
                }
            }
        }
        return false;
    }

    /**
     * @param node 新节点定义（同 id 会被替换）
     * @return 替换节点后的新工作流定义
     */
    public WorkflowDefinition withNode(NodeDefinition node) {
        List<NodeDefinition> merged = new ArrayList<>(nodes);
        merged.removeIf(existing -> existing.id().equals(node.id()));
        merged.add(node);
        return new WorkflowDefinition(id, version, merged, edges, slotsSchema, slotPolicy, toolRequirements, promptPolicy,
                filterRefs, interceptorRefs, guardRefs, cachePolicy, tracePoints, regions, dynamicPolicy, metadata);
    }

    /**
     * @param edge 追加的边
     * @return 追加边后的新工作流定义
     */
    public WorkflowDefinition withEdge(Edge edge) {
        List<Edge> merged = new ArrayList<>(edges);
        merged.add(edge);
        return new WorkflowDefinition(id, version, nodes, merged, slotsSchema, slotPolicy, toolRequirements,
                promptPolicy, filterRefs, interceptorRefs, guardRefs, cachePolicy, tracePoints, regions,
                dynamicPolicy, metadata);
    }
}
