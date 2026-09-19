package com.jjx.customer.platform.business.ops.stages;

import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.ConditionNodeDefinition.Branch;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.engine.core.EngineBuilder;
import com.jjx.customer.platform.business.ops.OpsDiagnoseWorkflowFactory;
import com.jjx.customer.platform.business.ops.OpsDiagnosisStages;
import com.jjx.customer.platform.business.ops.OpsPrompts;
import com.jjx.customer.platform.business.ops.executor.ActExecutor;
import com.jjx.customer.platform.business.ops.executor.ReplanExecutor;
import com.jjx.customer.platform.business.ops.tool.OpsSchemaResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 工具循环阶段模块（think → act → decide 小环，spec 驱动三份实例：定位 / 纠正 / 校验）。
 *
 * <p>本阶段的一切：图（节点 / 边 / 白名单 / Region 循环治理 / 局部槽模板）、执行器
 * （Act + 可选 Replan）、Prompt 资产、迭代守卫。阶段参数来自
 * {@link OpsDiagnosisStages}（单一事实源），下一阶段 spec 由构造注入（replan continue
 * 与守卫文案的接线目标）。</p>
 *
 * <pre>
 * {p}_think → {p}_act → {p}_decide ─┬─（has_calls &gt; 0）→ {p}_think（内循环，maxSteps 有界）
 *          ↑                        └─（无调用）→ replan ─┬ continue → 下一阶段
 *          └──── adjust ──（外循环唯一真回边，同样受治理） └ escalate → escalate_node
 * 末阶段无 replan：decide 直出 conclude。
 * </pre>
 */
public final class ToolLoopStageModule implements StageModule {

    private final OpsDiagnosisStages.Stage stage;
    private final OpsDiagnosisStages.Stage next;

    /**
     * @param stage 本阶段参数
     * @param next  下一阶段参数（replan continue 目标与文案）；末阶段传 null
     */
    public ToolLoopStageModule(OpsDiagnosisStages.Stage stage, OpsDiagnosisStages.Stage next) {
        this.stage = stage;
        this.next = next;
    }

    @Override
    public String key() {
        return stage.name();
    }

    @Override
    public Optional<String> entryNode() {
        return Optional.of(stage.thinkNode());
    }

    @Override
    public Set<String> provides() {
        Set<String> names = new LinkedHashSet<>();
        names.add(stage.actExecutorName());
        if (stage.hasReplan()) {
            names.add(stage.replanExecutorName());
        }
        return names;
    }

    @Override
    public void declareGraph(WorkflowBuilder workflowBuilder) {
        // 槽位：局部槽模板（声明一次，各阶段按 slotPrefix 展开为 {p}_xxx 物理槽）+ 预算槽
        workflowBuilder.regionSlot("scratchpad", SlotType.STRING)   // 工具调用累积草稿
          .regionSlot("has_calls", SlotType.NUMBER)    // decide 回环标记（短名表达式）
          .regionSlot("observation", SlotType.STRING)  // act 执行观察
          .regionSlot("stage_output", SlotType.STRING) // 阶段结论沉淀
          .regionSlot("retries", SlotType.NUMBER)      // replan adjust 重试计数
          .slot("llm_calls", SlotType.NUMBER);         // 预算闸门（Act / 交付层消费）

        LlmNodeDefinition think = LlmNodeDefinition.of(stage.thinkNode(), stage.promptAssetId(),
                stage.prefix() + "_out");
        if (stage.streamThink()) {
            // stream=true：结论经内核流式通道逐段外发（O10），由 OpsStreamDispatcher 剥壳转发
            think = think.withMeta(NodeMeta.empty().withAttribute("stream", true));
        }
        String loopExit = stage.hasReplan() ? stage.replanNode() : OpsDiagnoseWorkflowFactory.CONCLUDE_NODE;
        String nextThink = next == null ? null : next.thinkNode();

        workflowBuilder.node(think)
                .node(CustomNodeDefinition.of(stage.actNode(), stage.actExecutorName(),
                        stage.prefix() + "_observation"))
                .node(ConditionNodeDefinition.of(stage.decideNode(),
                        NodeMeta.empty().withGuards(stage.guardName()),
                        Branch.when(stage.thinkNode(), stage.hasCallsExpr()),
                        Branch.otherwise(loopExit)));
        if (stage.hasReplan()) {
            workflowBuilder.node(CustomNodeDefinition.of(stage.replanNode(), stage.replanExecutorName(),
                    "replan_verdict"));
        }

        workflowBuilder.edge(stage.thinkNode(), stage.actNode())
                .edge(stage.actNode(), stage.decideNode())
                .edge(stage.actNode(), OpsDiagnoseWorkflowFactory.ESCALATE_NODE)
                .edge(stage.decideNode(), stage.thinkNode())
                .dynamic(stage.actNode(), stage.decideNode(), OpsDiagnoseWorkflowFactory.ESCALATE_NODE);
        if (stage.hasReplan()) {
            workflowBuilder.edge(stage.decideNode(), stage.replanNode())
                    .edge(stage.replanNode(), nextThink)
                    .edge(stage.replanNode(), stage.thinkNode())
                    .edge(stage.replanNode(), OpsDiagnoseWorkflowFactory.ESCALATE_NODE)
                    .dynamic(stage.replanNode(), nextThink, stage.thinkNode(),
                            OpsDiagnoseWorkflowFactory.ESCALATE_NODE);
        } else {
            workflowBuilder.edge(stage.decideNode(), OpsDiagnoseWorkflowFactory.CONCLUDE_NODE);
        }

        // Region：治理锚点 + 循环上限 + 局部槽前缀（短名写回展开为 {p}_{name}）
        workflowBuilder.region(RegionDefinition.of(stage.regionId(), Paradigm.TOOL_CALL,
                        stage.thinkNode(), stage.actNode(), stage.decideNode())
                .withSlotPrefix(stage.prefix())
                .withLoop(RegionLoop.of(stage.thinkNode(), stage.decideNode(), stage.maxSteps())
                        .withCounterSlot(stage.counterSlot())));
    }

    @Override
    public void wireRuntime(EngineBuilder engineBuilder, SharedDeps deps) {
        // Prompt 协议块：人写 hint 优先，否则用工具 schema 渲染
        List<String> toolLines = stage.tools().stream()
                .map(line -> line.hint() != null ? line.hint() : deps.schemaText().get(line.toolId()))
                .toList();
        // think 模板正文：Prompt 资产（绑定包覆盖优先）优先，缺失回退内置常量（代码即基线的双保险）
        String body = deps.promptBody().apply(stage.promptAssetId());
        if (body == null || body.isBlank()) {
            body = stage.systemPrompt();
        }
        deps.promptRegister().accept(stage.promptAssetId(),
                OpsPrompts.compose(body, toolLines, "scratchpad"));
        engineBuilder.nodeExecutor(stage.actExecutorName(),
                        new ActExecutor(stage.prefix(), stage.title(), stage.toolIds(),
                                deps.sharedRegistry(), deps.toolExecutor(), deps.mapper(),
                                deps.maxLlmCalls(),
                                stage.outputGate()
                                        ? OpsSchemaResolver.byInterfaceSlot(deps.validateTool())
                                        : null))
                .loopGuard(stage.guardName(), stage.maxSteps());
        if (stage.hasReplan()) {
            engineBuilder.nodeExecutor(stage.replanExecutorName(),
                    new ReplanExecutor(stage.prefix(), stage.title(), next.title(),
                            stage.thinkNode(), next.thinkNode(), deps.model(), deps.mapper()));
        }
    }
}
