package com.jjx.customer.platform.console.controller;

import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.agent.AgentRegistry;
import com.jjx.customer.platform.config.properties.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 能力清单接口（前端范式选择器 + 管理后台「Agent 清单」页）。
 *
 * <p>数据源是框架注册表：Agent 身份 + 其组合 Workflow 的配置视图（阶段/工具/槽位/
 * 策略/prompt 依赖）。旧字段（type/label/description/stages/tools）结构不变——
 * 范式选择器零改动；管理页消费新增的详情字段（缺省即旧后端，前端兼容渲染）。</p>
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentRegistryController {

    private final AgentRegistry agentRegistry;
    private final AgentProperties agentProperties;

    /**
     * 链路级 key 的展示归类（编排链消费、不挂在 agent 骨架上的 key，按用户直觉归入所属链路）。
     * 展示归类而非运行依赖——运行消费点仍各自按 key 引用；此处集中声明供 console 按 Agent 组织。
     */
    private static final java.util.Map<String, List<String>> LINK_KEYS_BY_AGENT = java.util.Map.of(
            "knowledge", List.of(
                    "chat/intent/classify-system",
                    "chat/intent/classify-user",
                    "chat/query-rewrite",
                    "chat/pipeline/rag-answer-system",
                    "chat/pipeline/context-block",
                    "chat/pipeline/empty-retrieval",
                    "chat/pipeline/chitchat-system"));

    @GetMapping("/registry")
    public RegistryView registry() {
        List<AgentView> views = agentRegistry.all().stream().map(this::toView).toList();
        return new RegistryView(agentProperties.paradigmCode(), views);
    }

    private AgentView toView(Agent agent) {
        Workflow wf = agent.workflow();
        List<String> stages = wf.stages().stream().map(WorkflowStageSpec::name).toList();
        List<String> tools = wf.stages().stream()
                .flatMap(stage -> stage.extensionTools().stream())
                .distinct().toList();
        return new AgentView(
                agent.id(), agent.label(),
                agent.description().isBlank() ? agent.label() : agent.description(),
                stages, tools,
                agent.intentDomain(),
                agent.capabilities().stream().map(Enum::name).toList(),
                wf.id(),
                agent.promptKeys(),
                workflowPromptKeys(wf),
                wf.answerPromptKey(), wf.slotExtractPromptKey(), wf.replanPromptKey(),
                LINK_KEYS_BY_AGENT.getOrDefault(agent.id(), List.of()),
                wf.slots().stream()
                        .map(s -> new SlotView(s.name(), s.required(), s.question(), s.hint()))
                        .toList(),
                wf.stages().stream().map(this::toStageView).toList(),
                new PolicyView(wf.replanPromptKey() != null, wf.maxAdjustRetries(),
                        wf.maxLlmCalls(), wf.timeoutSeconds()));
    }

    /** 流程层 prompt 依赖（流程级 + 抽槽 / replan / 答案 key + 阶段 system key；绑定校验同口径）。 */
    private List<String> workflowPromptKeys(Workflow wf) {
        Set<String> keys = new LinkedHashSet<>(wf.promptKeys());
        if (wf.answerPromptKey() != null) {
            keys.add(wf.answerPromptKey());
        }
        if (wf.slotExtractPromptKey() != null) {
            keys.add(wf.slotExtractPromptKey());
        }
        if (wf.replanPromptKey() != null) {
            keys.add(wf.replanPromptKey());
        }
        wf.stages().stream()
                .map(WorkflowStageSpec::systemPromptKey)
                .filter(k -> k != null && !k.isBlank())
                .forEach(keys::add);
        return new ArrayList<>(keys);
    }

    private StageView toStageView(WorkflowStageSpec s) {
        return new StageView(s.name(), s.nodeKind().id(), s.systemPromptKey(),
                s.extensionTools(), s.maxSteps(), s.outputGuardSchema() != null,
                s.when() != null,
                s.workfolwErrorPolicy() == null ? null : s.workfolwErrorPolicy().action().name(),
                s.targetAgentId());
    }

    /** 能力清单视图（与前端 evalShared.refreshParadigms 的字段约定一致）。 */
    public record RegistryView(String defaultAgent, List<AgentView> agents) {
    }

    public record AgentView(String type, String label, String description,
                            List<String> stages, List<String> tools,
                            String intentDomain, List<String> capabilities,
                            String workflowId,
                            List<String> agentPromptKeys, List<String> workflowPromptKeys,
                            String answerPromptKey, String slotExtractPromptKey, String replanPromptKey,
                            List<String> linkPromptKeys,
                            List<SlotView> slots, List<StageView> stageDetails,
                            PolicyView policy) {
    }

    /** 槽位目录行。 */
    public record SlotView(String name, boolean required, String question, String hint) {
    }

    /** 阶段配置视图（nodeKind 形态 / prompt / 工具白名单 / 护栏与条件）。 */
    public record StageView(String name, String nodeKind, String systemPromptKey,
                            List<String> tools, int maxSteps, boolean guard,
                            boolean conditional, String errorPolicy, String targetAgentId) {
    }

    /** 流程策略：replan 检查点 / adjust 上限 / LLM 预算 / 超时（0 = 不限）。 */
    public record PolicyView(boolean replan, int maxAdjustRetries, int maxLlmCalls, int timeoutSeconds) {
    }
}
