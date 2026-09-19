package com.agentframework.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.ToolPolicy;
import com.agentframework.definition.workflow.SlotSpec;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.SlotsSchema;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 定义层测试：工作流构建、校验与槽位契约。 */
class WorkflowDefinitionTest {

    @Test
    @DisplayName("流式构建器可装配节点、边与槽位")
    void builderProducesWorkflow() {
        WorkflowDefinition workflow = WorkflowBuilder.create("research", "1.0.0")
                .node(LlmNodeDefinition.of("plan", "plan-prompt", "plan"))
                .node(ToolNodeDefinition.of("search", "web-search", "hits"))
                .node(LlmNodeDefinition.of("report", "report-prompt", "report")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("plan", "search")
                .edge("search", "report")
                .slot(SlotSpec.required("question", SlotType.STRING))
                .requireTool("web-search")
                .build();

        assertEquals(3, workflow.nodes().size());
        assertEquals("plan", workflow.entryNode().id());
        assertEquals(List.of("search"), workflow.outgoing("plan").stream().map(edge -> edge.to()).toList());
        assertTrue(workflow.toolIds().contains("web-search"));
        assertTrue(workflow.validate().isEmpty());
    }

    @Test
    @DisplayName("悬空边与重复节点会被校验拦截")
    void validationRejectsBrokenGraph() {
        List<String> problems = WorkflowBuilder.create("broken", "1.0.0")
                .node(LlmNodeDefinition.of("a", "p", "out"))
                .edge("a", "missing")
                .buildUnvalidated()
                .validate();

        assertTrue(problems.stream().anyMatch(problem -> problem.contains("unknown node")));

        WorkflowDefinition duplicated = new WorkflowDefinition("dup", "1.0.0",
                List.of(LlmNodeDefinition.of("a", "p", "out"), LlmNodeDefinition.of("a", "p2", "out2")),
                List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(duplicated.validate().stream().anyMatch(problem -> problem.contains("duplicate node id")));

        assertThrows(DefinitionValidationException.class, () -> WorkflowBuilder.create("broken", "1.0.0")
                .node(LlmNodeDefinition.of("a", "p", "out"))
                .edge("a", "missing")
                .build());
    }

    @Test
    @DisplayName("条件分支目标必须存在")
    void conditionTargetsAreValidated() {
        WorkflowDefinition workflow = WorkflowBuilder.create("branch", "1.0.0")
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("done", "slots.ok == true")))
                .node(LlmNodeDefinition.of("done", "p", "out"))
                .build();

        assertEquals("decide", workflow.requireNode("decide").id());
        assertEquals("done", ((ConditionNodeDefinition) workflow.requireNode("decide")).branches().get(0).target());
        assertThrows(IllegalArgumentException.class, () -> workflow.requireNode("nope"));
    }

    @Test
    @DisplayName("槽位 schema 校验必填、类型与未声明键")
    void slotsSchemaValidation() {
        SlotsSchema schema = SlotsSchema.of(
                SlotSpec.required("count", SlotType.NUMBER),
                SlotSpec.of("label", SlotType.STRING, "默认"));

        assertTrue(schema.validate(Map.of("count", 1, "label", "ok")).isEmpty());
        assertTrue(schema.validate(Map.of()).stream().anyMatch(p -> p.contains("missing required slot")));
        assertTrue(schema.validate(Map.of("count", "三")).stream().anyMatch(p -> p.contains("expects NUMBER")));
        assertTrue(schema.validate(Map.of("count", 1, "extra", true)).stream()
                .anyMatch(p -> p.contains("not declared")));
        assertEquals("默认", schema.applyDefaults(Map.of()).get("label"));
    }

    @Test
    @DisplayName("工具策略实现白名单、黑名单与审批语义")
    void toolPolicySemantics() {
        ToolPolicy policy = ToolPolicy.only("calculator").withDenied("danger").withApprovalRequired("deploy");

        assertTrue(policy.allows("calculator"));
        assertTrue(!policy.allows("search"));
        assertTrue(!policy.allows("danger"));
        assertTrue(policy.requiresApproval("deploy"));
        assertTrue(ToolPolicy.allowAll().allows("anything"));
    }
}
