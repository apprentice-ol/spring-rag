package com.jjx.customer.platform.console.controller;

import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.ops.OpsDiagnosisStages;

import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.config.properties.AgentProperties;
import com.jjx.customer.platform.knowledge.tools.RetrievalTool;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 能力清单接口（前端范式选择器 + 管理后台「Agent 清单」页）。
 *
 * <p>数据源是 {@link AgentCatalog} 静态目录 + 图常量（ops 三阶段 / 工具白名单 /
 * 槽位目录）——新内核的 {@code AgentDefinition} 不含业务元数据，清单在此集中投影。
 * REST 字段（type/label/description/stages/tools/...）结构与旧版完全一致，前端零改动。</p>
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentRegistryController {

    private final AgentProperties agentProperties;

    /**
     * 链路级 key 的展示归类（编排链消费、不挂在 agent 骨架上的 key，按用户直觉归入所属链路）。
     * 展示归类而非运行依赖——运行消费点仍各自按 key 引用；此处集中声明供 console 按 Agent 组织。
     */
    private static final Map<String, List<String>> LINK_KEYS_BY_AGENT = Map.of(
            "knowledge", List.of(
                    "rag/intent/classify-system",
                    "rag/intent/classify-user",
                    "rag/query-rewrite",
                    "rag/query-expand",
                    "rag/spell-fix",
                    "rag/pipeline/rag-answer-system",
                    "rag/pipeline/empty-retrieval",
                    "rag/pipeline/chitchat-system"));

    /** 各 agent 的阶段视图（图主链的语义阶段名，与旧版口径对齐）。 */
    private static final Map<String, List<StageView>> STAGES_BY_AGENT = Map.of(
            AgentCatalog.OPS.id(), OpsDiagnosisStages.ALL.stream()
                    .map(AgentRegistryController::toStageView).toList(),
            AgentCatalog.KNOWLEDGE.id(), List.of(new StageView("retrieve", "TOOL",
                    "workflow/knowledge_qa/retrieve", List.of(RetrievalTool.TOOL_ID), 1,
                    false, false, null, null)),
            AgentCatalog.REACT.id(), List.of(new StageView("tool_loop", "TOOL_CALL",
                    "agent/react-loop", List.of(RetrievalTool.TOOL_ID),
                    4, false, false, null, null)));

    @GetMapping("/registry")
    public RegistryView registry() {
        List<AgentView> views = AgentCatalog.all().stream().map(this::toView).toList();
        return new RegistryView(agentProperties.paradigmCode(), views);
    }

    private AgentView toView(AgentCatalog.Entry entry) {
        List<StageView> stageViews = STAGES_BY_AGENT.getOrDefault(entry.id(), List.of());
        List<String> stages = stageViews.stream().map(StageView::name).toList();
        List<String> tools = stageViews.stream()
                .flatMap(stage -> stage.tools().stream())
                .distinct().toList();
        boolean ops = AgentCatalog.OPS.id().equals(entry.id());
        return new AgentView(
                entry.id(), entry.label(),
                entry.description().isBlank() ? entry.label() : entry.description(),
                stages, tools,
                entry.intentDomain(),
                entry.capabilities(),
                entry.workflowId(),
                entry.agentPromptKeys(),
                workflowPromptKeys(entry, stageViews),
                entry.answerPromptKey(),
                ops ? "workflow/ops_diagnose_v2/slot-extract" : null,
                ops ? "workflow/ops_diagnose_v2/replan" : null,
                LINK_KEYS_BY_AGENT.getOrDefault(entry.id(), List.of()),
                ops ? OpsSlotCatalog.ALL.stream()
                        .map(s -> new SlotView(s.name(), s.required(), s.question(), s.hint()))
                        .toList() : List.of(),
                stageViews,
                new PolicyView(ops,
                        1,
                        agentProperties.getWorkflow().getMaxLlmCalls(),
                        agentProperties.getWorkflow().getTimeoutSeconds()));
    }

    /** 流程层 prompt 依赖（流程级 + 抽槽 / replan / 答案 key + 阶段 system key；绑定校验同口径）。 */
    private List<String> workflowPromptKeys(AgentCatalog.Entry entry, List<StageView> stages) {
        Set<String> keys = new LinkedHashSet<>(entry.workflowPromptKeys());
        if (entry.answerPromptKey() != null) {
            keys.add(entry.answerPromptKey());
        }
        if (AgentCatalog.OPS.id().equals(entry.id())) {
            keys.add("workflow/ops_diagnose_v2/slot-extract");
            keys.add("workflow/ops_diagnose_v2/replan");
        }
        stages.stream()
                .map(StageView::systemPromptKey)
                .filter(k -> k != null && !k.isBlank())
                .forEach(keys::add);
        return new ArrayList<>(keys);
    }

    private static StageView toStageView(OpsDiagnosisStages.Stage stage) {
        return new StageView(stage.name(), "TOOL_CALL", stage.promptAssetId(),
                List.copyOf(stage.toolIds()), stage.maxSteps(), stage.outputGate(),
                true, null, null);
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
