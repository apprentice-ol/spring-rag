package com.agentframework.engine.agentmanager;

import com.agentframework.definition.DefinitionValidationException;
import com.agentframework.definition.ValidationLocations;
import com.agentframework.definition.ValidationProblem;
import com.agentframework.definition.ValidationReport;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.PolicyBinding;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionPolicy;
import com.agentframework.definition.workflow.ToolRequirement;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.policy.Activation;
import com.agentframework.engine.policy.PolicyCatalog;
import com.agentframework.engine.policy.PolicyComponent;
import com.agentframework.engine.policy.PolicyKind;
import com.agentframework.engine.promptmanager.PromptProvider;
import com.agentframework.engine.toolexecutor.ToolRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 定义校验器：在创建实例前一次性暴露装配问题。
 *
 * <p>校验内容：工作流结构与槽位契约、Prompt 资产是否可解析、工具是否已注册、
 * Agent / Workflow / 节点 / Prompt 四级策略引用是否可解析。只有阻断性问题（ERROR）才会失败。</p>
 */
public final class AgentValidator {

    private final PromptProvider promptProvider;
    private final ToolRegistry toolRegistry;
    private final PolicyCatalog policyCatalog;

    /**
     * @param promptProvider Prompt 资产来源，null 表示跳过 Prompt 校验
     * @param toolRegistry   工具注册表，null 表示跳过工具校验
     */
    public AgentValidator(PromptProvider promptProvider, ToolRegistry toolRegistry) {
        this(promptProvider, toolRegistry, null);
    }

    /**
     * @param promptProvider Prompt 资产来源，null 表示跳过 Prompt 校验
     * @param toolRegistry   工具注册表，null 表示跳过工具校验
     * @param policyCatalog  策略目录，null 表示跳过策略引用校验
     */
    public AgentValidator(PromptProvider promptProvider, ToolRegistry toolRegistry, PolicyCatalog policyCatalog) {
        this.promptProvider = promptProvider;
        this.toolRegistry = toolRegistry;
        this.policyCatalog = policyCatalog;
    }

    /**
     * 执行校验。
     *
     * @param definition Agent 定义
     * @param workflow   已解析的工作流，可为 null
     * @return 阻断性问题的说明列表，为空表示通过
     */
    public List<String> validate(AgentDefinition definition, WorkflowDefinition workflow) {
        return validateReport(definition, workflow).errorMessages();
    }

    /**
     * 完整校验：工作流结构 + 槽位契约 + Prompt 资产 + 工具注册 + 四级策略引用。
     *
     * @param definition Agent 定义
     * @param workflow   已解析的工作流，可为 null
     * @return 校验报告
     */
    public ValidationReport validateReport(AgentDefinition definition, WorkflowDefinition workflow) {
        List<ValidationProblem> problems = new ArrayList<>();
        if (workflow == null) {
            problems.add(ValidationProblem.error("WORKFLOW_REF_UNKNOWN", "agent:" + definition.key(),
                    "工作流不存在：" + definition.workflowId() + "@" + definition.workflowVersion()));
            return new ValidationReport(problems);
        }
        for (NodeDefinition node : workflow.nodes()) {
            String nodeLocation = ValidationLocations.node(workflow.key(), node.id());
            if (node instanceof LlmNodeDefinition llm && promptProvider != null
                    && promptProvider.find(llm.promptRef(), llm.promptVersion()).isEmpty()) {
                problems.add(ValidationProblem.error("PROMPT_REF_UNKNOWN", nodeLocation,
                        "节点 '" + node.id() + "' 引用的 Prompt 不存在：" + llm.promptRef()));
            }
            if (node instanceof ToolNodeDefinition tool && toolRegistry != null
                    && toolRegistry.resolve(tool.toolRef(), tool.toolVersion()).isEmpty()) {
                problems.add(ValidationProblem.error("TOOL_REF_UNKNOWN", nodeLocation,
                        "节点 '" + node.id() + "' 引用的工具未注册：" + tool.toolRef()));
            }
        }
        for (ToolRequirement requirement : workflow.toolRequirements()) {
            if (!requirement.optional() && toolRegistry != null
                    && toolRegistry.resolve(requirement.toolId(), requirement.versionRange()).isEmpty()) {
                problems.add(ValidationProblem.error("TOOL_REF_UNKNOWN",
                        ValidationLocations.workflow(workflow.key()),
                        "工作流依赖的工具未注册：" + requirement.toolId()));
            }
        }
        problems.addAll(validatePolicyRefs(definition, workflow));
        return workflow.validateReport().merge(new ValidationReport(problems));
    }

    /**
     * 校验并在失败时抛出。
     *
     * @param definition Agent 定义
     * @param workflow   已解析的工作流
     * @throws DefinitionValidationException 存在阻断性问题时抛出
     */
    public void validateOrThrow(AgentDefinition definition, WorkflowDefinition workflow) {
        ValidationReport report = validateReport(definition, workflow);
        if (report.hasErrors()) {
            throw new DefinitionValidationException("Agent '" + definition.key() + "'", report);
        }
    }

    /**
     * 校验 Agent / Workflow / 节点 / Prompt 四级策略引用是否都能在目录中解析。
     *
     * @param definition Agent 定义
     * @param workflow   工作流定义
     * @return 问题列表
     */
    private List<ValidationProblem> validatePolicyRefs(AgentDefinition definition, WorkflowDefinition workflow) {
        List<ValidationProblem> problems = new ArrayList<>();
        if (policyCatalog == null) {
            return problems;
        }
        String agentLocation = "agent:" + definition.key() + "/policies";
        checkRefs(problems, PolicyKind.GUARD, definition.policies().guard().refs(), agentLocation + "/guard");
        checkRefs(problems, PolicyKind.FILTER, definition.policies().filter().refs(), agentLocation + "/filter");
        checkDisabled(problems, PolicyKind.FILTER, definition.policies().filter().disabled(),
                agentLocation + "/filter");
        checkRefs(problems, PolicyKind.INTERCEPTOR, definition.policies().interceptor().refs(),
                agentLocation + "/interceptor");

        String workflowLocation = ValidationLocations.workflow(workflow.key());
        checkRefs(problems, PolicyKind.GUARD, workflow.guardRefs(), workflowLocation + "/guardRefs");
        checkRefs(problems, PolicyKind.FILTER, workflow.filterRefs(), workflowLocation + "/filterRefs");
        checkRefs(problems, PolicyKind.INTERCEPTOR, workflow.interceptorRefs(), workflowLocation + "/interceptorRefs");

        for (NodeDefinition node : workflow.nodes()) {
            if (node.meta() == null) {
                continue;
            }
            String nodeLocation = ValidationLocations.node(workflow.key(), node.id());
            checkRefs(problems, PolicyKind.GUARD, node.meta().guardRefs(), nodeLocation + "/meta.guardRefs");
            checkRefs(problems, PolicyKind.FILTER, node.meta().filterRefs(), nodeLocation + "/meta.filterRefs");
        }
        for (RegionDefinition region : workflow.regions()) {
            RegionPolicy policy = region.policy();
            String regionLocation = ValidationLocations.field(workflowLocation, "region:" + region.id());
            checkBindings(problems, PolicyKind.GUARD, policy.guards(), regionLocation + "/policy.guards");
            checkBindings(problems, PolicyKind.FILTER, policy.filters(), regionLocation + "/policy.filters");
            checkBindings(problems, PolicyKind.INTERCEPTOR, policy.interceptors(),
                    regionLocation + "/policy.interceptors");
        }
        if (promptProvider != null) {
            for (NodeDefinition node : workflow.nodes()) {
                if (!(node instanceof LlmNodeDefinition llm)) {
                    continue;
                }
                promptProvider.find(llm.promptRef(), llm.promptVersion()).ifPresent(prompt -> {
                    String promptLocation = ValidationLocations.prompt(prompt.key());
                    checkRefs(problems, PolicyKind.GUARD, prompt.guardRefs(), promptLocation + "/guardRefs");
                    checkRefs(problems, PolicyKind.FILTER, prompt.filterRefs(), promptLocation + "/filterRefs");
                });
            }
        }
        return problems;
    }

    /**
     * 校验引用是否存在。
     *
     * @param problems 问题收集器
     * @param kind     组件类别
     * @param refs     引用列表
     * @param location 位置
     */
    private void checkRefs(List<ValidationProblem> problems, PolicyKind kind, List<String> refs, String location) {
        for (String ref : refs) {
            if (ref == null || ref.isBlank() || "*".equals(ref)) {
                continue;
            }
            if (policyCatalog.find(kind, ref).isEmpty()) {
                List<String> candidates = policyCatalog.suggest(kind, ref);
                problems.add(ValidationProblem.error("POLICY_REF_UNKNOWN", location,
                        location + " 引用的 " + label(kind) + " '" + ref
                                + "' 未注册；已注册：" + names(kind) + suggestion(candidates))
                        .withCandidates(candidates));
            }
        }
    }

    /**
     * 校验 Region 策略绑定中的组件名是否存在。
     *
     * @param problems 问题收集器
     * @param kind     组件类别
     * @param bindings 绑定列表
     * @param location 位置
     */
    private void checkBindings(List<ValidationProblem> problems, PolicyKind kind, List<PolicyBinding> bindings,
            String location) {
        if (bindings.isEmpty()) {
            return;
        }
        checkRefs(problems, kind, bindings.stream().map(PolicyBinding::name).toList(), location);
    }

    /**
     * 校验禁用项：强制组件不可被禁用。
     *
     * @param problems 问题收集器
     * @param kind     组件类别
     * @param disabled 禁用列表
     * @param location 位置
     */
    private void checkDisabled(List<ValidationProblem> problems, PolicyKind kind, List<String> disabled,
            String location) {
        for (String name : disabled) {
            PolicyComponent component = policyCatalog.find(kind, name).orElse(null);
            if (component != null && component.activation() == Activation.MANDATORY) {
                problems.add(ValidationProblem.error("POLICY_REF_DISABLED_MANDATORY", location,
                        location + " 试图禁用强制组件 '" + name + "'"));
            }
        }
    }

    /** @return 类别中文名 */
    private String label(PolicyKind kind) {
        return switch (kind) {
            case GUARD -> "守卫";
            case FILTER -> "过滤器";
            case INTERCEPTOR -> "拦截器";
        };
    }

    /** @return 已注册名字列表 */
    private String names(PolicyKind kind) {
        return String.join(", ", policyCatalog.names(kind));
    }

    /** @return 拼写建议片段，无候选时为空串 */
    private String suggestion(List<String> candidates) {
        return candidates.isEmpty() ? "" : "；是否想引用：" + String.join(", ", candidates);
    }
}
