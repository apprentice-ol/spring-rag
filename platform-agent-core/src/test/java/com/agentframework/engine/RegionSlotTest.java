package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.DefinitionValidationException;
import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.definition.node.NodeType;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.SessionState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Region 局部槽（{@code SlotScope.REGION} 模板 + {@code slotPrefix}）的行为测试：
 * 写回短名展开为物理全名、变量表叠加短名别名、表达式可用短名路由、build 期冲突校验。
 */
class RegionSlotTest {

    /** 收集执行器在节点内看到的变量表快照（用于断言短名别名）。 */
    private final List<Map<String, Object>> seenVariables = new ArrayList<>();

    @Test
    @DisplayName("Region 内写入短名展开为 {prefix}_{name} 物理槽，短名别名对同区域后续节点可见")
    void regionSlotWritesExpandAndAlias() {
        WorkflowDefinition workflow = WorkflowBuilder.create("region-slot-wf", "1.0.0")
                .node(CustomNodeDefinition.of("act", "write-short", "act_out"))
                .node(CustomNodeDefinition.of("act2", "read-short", "act2_out"))
                .node(new CustomNodeDefinition("done", "noop", Map.of(), "done_out",
                        NodeMeta.empty().withAttribute("terminal", true)))
                .edge("act", "act2").edge("act2", "done")
                .regionSlot("scratchpad", SlotType.STRING)
                .regionSlot("has_calls", SlotType.NUMBER)
                .region(RegionDefinition.of("r1", Paradigm.TOOL_CALL, "act", "act2")
                        .withSlotPrefix("inv"))
                .build();

        Engine engine = engine(workflow, Map.of(
                "write-short", executorReturning(NodeResult.completed("act", "ok",
                        Map.of("scratchpad", "观察内容", "has_calls", 1))),
                "read-short", new NodeExecutor() {
                    @Override
                    public NodeType type() {
                        return NodeType.CUSTOM;
                    }

                    @Override
                    public NodeResult execute(NodeDefinition node, NodeContext ctx) {
                        seenVariables.add(ctx.variables());
                        return NodeResult.completed("act2", "ok");
                    }
                },
                "noop", executorReturning(NodeResult.completed("done", "ok"))));

        RunResult result = engine.run("region-slot-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        // 写回展开：物理槽是全名，短名不落库
        assertEquals("观察内容", result.slots().get("inv_scratchpad"));
        assertEquals(1, result.slots().get("inv_has_calls"));
        assertFalse(result.slots().containsKey("scratchpad"), "短名不应作为物理槽落库");
        // 变量别名：同区域后续节点以短名可见（不覆盖全局槽）
        Map<String, Object> slotView = (Map<String, Object>) seenVariables.get(0).get("slot");
        assertEquals("观察内容", slotView.get("scratchpad"));
        assertEquals(1, slotView.get("has_calls"));
    }

    @Test
    @DisplayName("条件表达式可用短名路由（slots.has_calls > 0）")
    void expressionRoutesOnShortName() {
        WorkflowDefinition workflow = WorkflowBuilder.create("region-expr-wf", "1.0.0")
                .node(CustomNodeDefinition.of("act", "set-calls", "act_out"))
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("loop", "slots.has_calls > 0"),
                        ConditionNodeDefinition.Branch.otherwise("done")))
                .node(CustomNodeDefinition.of("loop", "clear-calls", "loop_out"))
                .node(new CustomNodeDefinition("done", "noop", Map.of(), "done_out",
                        NodeMeta.empty().withAttribute("terminal", true)))
                .edge("act", "decide").edge("decide", "loop").edge("decide", "done")
                .edge("loop", "decide")
                .regionSlot("has_calls", SlotType.NUMBER)
                .region(RegionDefinition.of("r1", Paradigm.TOOL_CALL, "act", "decide", "loop")
                        .withSlotPrefix("inv"))
                .build();

        Engine engine = engine(workflow, Map.of(
                "set-calls", executorReturning(NodeResult.completed("act", "ok", Map.of("has_calls", 1))),
                "clear-calls", executorReturning(NodeResult.completed("loop", "ok", Map.of("has_calls", 0))),
                "noop", executorReturning(NodeResult.completed("done", "ok"))));

        RunResult result = engine.run("region-slot-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(List.of("act", "decide", "loop", "decide", "done"), result.visitedNodes(),
                "decide 应按短名 has_calls 先回环后放行");
        assertEquals(0, result.slots().get("inv_has_calls"));
    }

    @Test
    @DisplayName("展开名与全局槽同名是阻断性冲突")
    void templateConflictingWithGlobalSlotIsBlocking() {
        assertThrows(DefinitionValidationException.class, () -> WorkflowBuilder.create("conflict-wf", "1.0.0")
                .node(new CustomNodeDefinition("done", "noop", Map.of(), "done_out",
                        NodeMeta.empty().withAttribute("terminal", true)))
                .slot("inv_has_calls", SlotType.NUMBER)
                .regionSlot("has_calls", SlotType.NUMBER)
                .region(RegionDefinition.of("r1", Paradigm.TOOL_CALL, "done").withSlotPrefix("inv"))
                .build());
    }

    @Test
    @DisplayName("声明了模板但没有带前缀的 Region 只是警告")
    void templateWithoutPrefixIsWarningOnly() {
        WorkflowDefinition workflow = WorkflowBuilder.create("unused-wf", "1.0.0")
                .node(new CustomNodeDefinition("done", "noop", Map.of(), "done_out",
                        NodeMeta.empty().withAttribute("terminal", true)))
                .regionSlot("scratchpad", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.TOOL_CALL, "done"))
                .build();

        assertTrue(workflow.validate().isEmpty());
        assertTrue(workflow.validateReport().warnings().stream()
                .anyMatch(problem -> problem.code().equals("SLOT_REGION_TEMPLATE_UNUSED")));
    }

    @Test
    @DisplayName("knownSlots 同时包含模板短名与各前缀展开名")
    void knownSlotsContainShortAndExpandedNames() {
        WorkflowDefinition workflow = WorkflowBuilder.create("known-wf", "1.0.0")
                .node(new CustomNodeDefinition("done", "noop", Map.of(), "done_out",
                        NodeMeta.empty().withAttribute("terminal", true)))
                .slot("global_q", SlotType.STRING)
                .regionSlot("has_calls", SlotType.NUMBER)
                .region(RegionDefinition.of("r1", Paradigm.TOOL_CALL, "done").withSlotPrefix("inv"))
                .region(RegionDefinition.of("r2", Paradigm.TOOL_CALL).withSlotPrefix("res"))
                .build();

        assertTrue(workflow.knownSlots().contains("has_calls"));
        assertTrue(workflow.knownSlots().contains("inv_has_calls"));
        assertTrue(workflow.knownSlots().contains("global_q"));
    }

    // -----------------------------------------------------------------------------------------

    private static NodeExecutor executorReturning(NodeResult result) {
        return new NodeExecutor() {
            @Override
            public NodeType type() {
                return NodeType.CUSTOM;
            }

            @Override
            public NodeResult execute(NodeDefinition node, NodeContext ctx) {
                return result;
            }
        };
    }

    private static Engine engine(WorkflowDefinition workflow, Map<String, NodeExecutor> executors) {
        EngineBuilder builder = EngineBuilder.create()
                .workflow(workflow)
                .agent(com.agentframework.definition.agent.AgentDefinition
                        .builder("region-slot-agent").workflow(workflow.id()).build());
        executors.forEach(builder::nodeExecutor);
        return builder.build();
    }
}
