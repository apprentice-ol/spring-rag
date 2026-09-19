package com.agentframework.definition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.workflow.SlotPolicy;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 槽位自动注册（outputSlot / 派生槽 / counterSlot 免声明）与严格度策略
 * （默认「声明即 STRICT」、{@code .strictSlots()} / {@code .openSlots()} 显式覆盖）的行为测试。
 */
class SlotPolicyTest {

    @Test
    @DisplayName("STRICT 下表达式可免声明引用 outputSlot、派生槽与计数槽")
    void derivedSlotsAreKnownWithoutDeclaration() {
        WorkflowDefinition workflow = WorkflowBuilder.create("auto-known", "1.0.0")
                .node(CustomNodeDefinition.of("extract", "ops-extract", "slot_extract_raw"))
                .node(ConditionNodeDefinition.of("gate",
                        new ConditionNodeDefinition.Branch("plan",
                                "slots.missing_count > 0 && slots.round >= 1"),
                        ConditionNodeDefinition.Branch.otherwise("done")))
                .node(LlmNodeDefinition.of("plan", "p", "inv_out"))
                .node(ToolNodeDefinition.of("search", "web-search", "hits"))
                .node(LlmNodeDefinition.of("done", "p2", "final_output")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("extract", "gate").edge("gate", "plan").edge("gate", "done")
                .edge("plan", "search").edge("search", "done")
                .region(RegionDefinition.of("r1", Paradigm.TOOL_CALL, "plan", "search")
                        .withLoop(RegionLoop.of("plan", "search", 4).withCounterSlot("round")))
                // 唯一显式声明：missing_count（业务契约槽）
                .slot("missing_count", SlotType.NUMBER)
                .build();

        assertTrue(workflow.validateReport().errors().isEmpty(),
                () -> "实际错误：" + workflow.validateReport().errors());
        // knownSlots = {missing_count, inv_out(+_tool_calls), hits(+_data), final_output(+_tool_calls),
        // slot_extract_raw, round}
        assertTrue(workflow.knownSlots().contains("inv_out_tool_calls"));
        assertTrue(workflow.knownSlots().contains("hits_data"));
        assertTrue(workflow.knownSlots().contains("slot_extract_raw"), "Custom 节点 outputSlot 应被识别");
        assertTrue(workflow.knownSlots().contains("round"), "Region counterSlot 应被识别");
        assertFalse(workflow.validateReport().warnings().stream()
                .anyMatch(problem -> problem.code().equals("SLOT_UNDECLARED_WRITE")));
    }

    @Test
    @DisplayName("STRICT 下引用自动槽之外的未知槽仍是阻断性错误")
    void trulyUnknownSlotStillBlocksUnderStrict() {
        WorkflowBuilder builder = WorkflowBuilder.create("strict-unknown", "1.0.0")
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("done", "slots.typo_name > 0"),
                        ConditionNodeDefinition.Branch.otherwise("done")))
                .node(LlmNodeDefinition.of("done", "p", "out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("declared", SlotType.STRING);

        assertThrows(DefinitionValidationException.class, builder::build);
    }

    @Test
    @DisplayName("openSlots() 显式降级：声明过槽位也只警告未知引用")
    void explicitOpenSlotsDowngradesToWarning() {
        WorkflowDefinition workflow = WorkflowBuilder.create("explicit-open", "1.0.0")
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("done", "slots.missing > 0"),
                        ConditionNodeDefinition.Branch.otherwise("done")))
                .node(LlmNodeDefinition.of("done", "p", "out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("declared", SlotType.STRING)
                .openSlots()
                .build();

        assertTrue(workflow.validate().isEmpty());
        assertTrue(workflow.slotPolicy() == SlotPolicy.OPEN);
        assertTrue(workflow.validateReport().warnings().stream()
                .anyMatch(problem -> problem.code().equals("EXPRESSION_UNKNOWN_SLOT")));
    }

    @Test
    @DisplayName("strictSlots() 显式升级：零声明也阻断未知引用")
    void explicitStrictSlotsBlocksWithoutDeclarations() {
        WorkflowBuilder builder = WorkflowBuilder.create("explicit-strict", "1.0.0")
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("done", "slots.missing > 0"),
                        ConditionNodeDefinition.Branch.otherwise("done")))
                .node(LlmNodeDefinition.of("done", "p", "out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .strictSlots();

        assertThrows(DefinitionValidationException.class, builder::build);
    }

    @Test
    @DisplayName("默认策略：声明过任意槽位即 STRICT，零声明即 OPEN")
    void defaultPolicyFollowsDeclarationPresence() {
        WorkflowDefinition declared = WorkflowBuilder.create("with-decl", "1.0.0")
                .node(LlmNodeDefinition.of("a", "p", "out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("q", SlotType.STRING)
                .buildUnvalidated();
        WorkflowDefinition empty = WorkflowBuilder.create("no-decl", "1.0.0")
                .node(LlmNodeDefinition.of("a", "p", "out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .buildUnvalidated();

        assertTrue(declared.slotPolicy() == SlotPolicy.STRICT);
        assertTrue(empty.slotPolicy() == SlotPolicy.OPEN);
    }
}
