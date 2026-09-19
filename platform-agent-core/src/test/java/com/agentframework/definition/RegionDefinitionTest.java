package com.agentframework.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.HumanNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ParallelNodeDefinition;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionSuggestion;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Region 定义与校验测试：显式声明为唯一真相，推断只读。 */
class RegionDefinitionTest {

    @Test
    @DisplayName("合法 Region 声明通过校验并可反查节点归属")
    void validRegionDeclaration() {
        WorkflowDefinition workflow = routingWorkflow()
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "classify", "route"))
                .build();

        assertTrue(workflow.validate().isEmpty());
        assertEquals(1, workflow.regions().size());
        assertEquals(Optional.of("r1"), workflow.regionOf("classify"));
        assertEquals(Optional.of("r1"), workflow.regionOf("route"));
        assertEquals(Optional.empty(), workflow.regionOf("unknown"));
    }

    @Test
    @DisplayName("重复 Region id 是阻断性错误")
    void duplicateRegionIdIsBlocking() {
        WorkflowDefinition base = routingWorkflow().build();
        RegionDefinition first = RegionDefinition.of("dup", Paradigm.ROUTER, "classify");
        RegionDefinition second = RegionDefinition.of("dup", Paradigm.ROUTER, "route");
        WorkflowDefinition duplicated = new WorkflowDefinition(base.id(), base.version(), base.nodes(),
                base.edges(), base.slotsSchema(), base.slotPolicy(), base.toolRequirements(), base.promptPolicy(),
                base.filterRefs(), base.interceptorRefs(), base.guardRefs(), base.cachePolicy(),
                base.tracePoints(), List.of(first, second), base.dynamicPolicy(), base.metadata());

        ValidationReport report = duplicated.validateReport();

        assertTrue(report.errors().stream().anyMatch(problem -> problem.code().equals("REGION_DUPLICATE_ID")));
    }

    @Test
    @DisplayName("引用不存在的节点是阻断性错误")
    void unknownRegionNodeIsBlocking() {
        WorkflowBuilder builder = routingWorkflow()
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "classify", "missing"));

        ValidationReport report = builder.buildUnvalidated().validateReport();

        assertTrue(report.errors().stream().anyMatch(problem -> problem.code().equals("REGION_NODE_UNKNOWN")));
        assertThrows(DefinitionValidationException.class, builder::build);
    }

    @Test
    @DisplayName("同一节点属于两个显式 Region 是阻断性错误")
    void overlappingRegionsAreBlocking() {
        WorkflowDefinition workflow = routingWorkflow()
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "classify", "route"))
                .region(RegionDefinition.of("r2", Paradigm.HUMAN_IN_LOOP, "route"))
                .buildUnvalidated();

        ValidationReport report = workflow.validateReport();

        assertTrue(report.errors().stream().anyMatch(problem -> problem.code().equals("REGION_OVERLAP")));
    }

    @Test
    @DisplayName("空 Region 只是警告，不阻断构建")
    void emptyRegionIsWarning() {
        WorkflowDefinition workflow = routingWorkflow()
                .region(RegionDefinition.of("r-empty", Paradigm.CUSTOM))
                .build();

        assertTrue(workflow.validate().isEmpty());
        assertTrue(workflow.validateReport().warnings().stream()
                .anyMatch(problem -> problem.code().equals("REGION_EMPTY")));
    }

    @Test
    @DisplayName("推断建议来自节点类型与回边，且不进入校验报告")
    void suggestionsAreReadOnly() {
        WorkflowDefinition workflow = WorkflowBuilder.create("suggest-wf", "1.0.0")
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("loop", "true")))
                .node(HumanNodeDefinition.of("approve", "请确认", "approval"))
                .node(ParallelNodeDefinition.of("fan-out", "branches", "a", "b"))
                .node(ToolNodeDefinition.of("call", "tool-ref", "tool_out"))
                .node(LlmNodeDefinition.of("a", "prompt-a", "out-a")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .node(LlmNodeDefinition.of("b", "prompt-b", "out-b")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .node(LlmNodeDefinition.of("loop", "prompt-loop", "loop-out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("decide", "loop")
                .edge("loop", "decide")
                .buildUnvalidated();

        List<RegionSuggestion> suggestions = workflow.regionSuggestions();

        assertTrue(hasParadigm(suggestions, Paradigm.ROUTER));
        assertTrue(hasParadigm(suggestions, Paradigm.HUMAN_IN_LOOP));
        assertTrue(hasParadigm(suggestions, Paradigm.PARALLEL_RETRIEVAL));
        assertTrue(hasParadigm(suggestions, Paradigm.TOOL_CALL));
        assertTrue(hasParadigm(suggestions, Paradigm.REFLECTION));
        assertFalse(workflow.validateReport().problems().stream()
                .anyMatch(problem -> problem.code().startsWith("REGION_SUGGESTION")));
    }

    /**
     * @param suggestions 建议列表
     * @param paradigm    范式
     * @return 是否存在该范式的建议
     */
    private boolean hasParadigm(List<RegionSuggestion> suggestions, Paradigm paradigm) {
        return suggestions.stream().anyMatch(suggestion -> suggestion.paradigm() == paradigm);
    }

    /**
     * @return 两节点路由工作流构建器
     */
    private WorkflowBuilder routingWorkflow() {
        return WorkflowBuilder.create("region-wf", "1.0.0")
                .node(ConditionNodeDefinition.of("classify",
                        new ConditionNodeDefinition.Branch("route", "true")))
                .node(LlmNodeDefinition.of("route", "prompt-route", "route_out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("classify", "route")
                .slot("route_out", SlotType.STRING);
    }
}
