package com.agentframework.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 校验分级测试：警告不阻断、错误阻断、定位与报告结构。 */
class ValidationReportTest {

    @Test
    @DisplayName("未声明槽位在无 schema 时降级为警告，不阻断构建")
    void unknownSlotIsWarningWhenSchemaIsEmpty() {
        WorkflowDefinition workflow = looseWorkflow("loose");

        assertTrue(workflow.validate().isEmpty(), "警告不应出现在 validate() 中");
        ValidationReport report = workflow.validateReport();
        assertFalse(report.hasErrors());
        assertTrue(report.warnings().stream().anyMatch(problem ->
                problem.code().equals("EXPRESSION_UNKNOWN_SLOT")));
    }

    @Test
    @DisplayName("声明了 schema 后未声明槽位升级为错误并阻断构建")
    void unknownSlotIsErrorWhenSchemaDeclared() {
        WorkflowDefinition workflow = strictWorkflow().buildUnvalidated();

        ValidationReport report = workflow.validateReport();

        assertTrue(report.hasErrors());
        assertTrue(report.errors().stream().anyMatch(problem ->
                problem.code().equals("EXPRESSION_UNKNOWN_SLOT")));
        assertThrows(DefinitionValidationException.class, () -> strictWorkflow().build());
    }

    @Test
    @DisplayName("表达式语法错误是阻断性问题")
    void expressionParseErrorIsBlocking() {
        WorkflowDefinition workflow = WorkflowBuilder.create("bad-expr", "1.0.0")
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("done", "slots.a ==")))
                .node(LlmNodeDefinition.of("done", "p", "out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("a", SlotType.STRING)
                .buildUnvalidated();

        ValidationReport report = workflow.validateReport();

        assertTrue(report.errors().stream().anyMatch(problem ->
                problem.code().equals("EXPRESSION_PARSE_ERROR")));
    }

    @Test
    @DisplayName("节点 outputSlot 自动纳入已知槽位，不再提示未声明写入")
    void outputSlotIsAutoRegistered() {
        WorkflowDefinition workflow = WorkflowBuilder.create("write-warn", "1.0.0")
                .node(LlmNodeDefinition.of("plan", "p", "undeclared_out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("declared", SlotType.STRING)
                .build();

        assertTrue(workflow.validate().isEmpty());
        assertFalse(workflow.validateReport().warnings().stream().anyMatch(problem ->
                problem.code().equals("SLOT_UNDECLARED_WRITE")),
                "outputSlot 属于引擎自动识别的已知槽位，不应再产生 SLOT_UNDECLARED_WRITE");
    }

    @Test
    @DisplayName("问题带有机器可读的错误码与精确定位")
    void problemsCarryCodeAndLocation() {
        ValidationReport report = strictWorkflow().buildUnvalidated().validateReport();

        ValidationProblem problem = report.errors().stream()
                .filter(candidate -> candidate.code().equals("EXPRESSION_UNKNOWN_SLOT"))
                .findFirst()
                .orElseThrow();

        assertEquals(ValidationLocations.node("strict@1.0.0", "decide") + "/branches[0]", problem.location());
    }

    @Test
    @DisplayName("异常携带完整报告，旧构造器仍可用")
    void exceptionCarriesReport() {
        DefinitionValidationException failure = assertThrows(DefinitionValidationException.class,
                () -> strictWorkflow().build());

        assertTrue(failure.report().hasErrors());
        assertTrue(failure.report().errors().stream().anyMatch(problem ->
                problem.code().equals("EXPRESSION_UNKNOWN_SLOT")),
                () -> "实际错误码：" + failure.report().errors().stream().map(ValidationProblem::code).toList());
        assertTrue(failure.getMessage().contains("未声明的槽位"));

        DefinitionValidationException legacy = new DefinitionValidationException("subject", List.of("旧消息"));
        assertTrue(legacy.report().hasErrors());
        assertEquals(List.of("旧消息"), legacy.problems());
    }

    /**
     * @param id 工作流 id
     * @return 未声明任何槽位的工作流：引用未声明槽位只产生警告
     */
    private WorkflowDefinition looseWorkflow(String id) {
        return WorkflowBuilder.create(id, "1.0.0")
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("done", "slots.missing == true")))
                .node(LlmNodeDefinition.of("done", "p", "out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .build();
    }

    /**
     * @return 声明了 schema 但引用了未声明槽位的工作流：阻断性错误
     */
    private WorkflowBuilder strictWorkflow() {
        return WorkflowBuilder.create("strict", "1.0.0")
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("done", "slots.missing == true")))
                .node(LlmNodeDefinition.of("done", "p", "out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("declared", SlotType.STRING);
    }
}
