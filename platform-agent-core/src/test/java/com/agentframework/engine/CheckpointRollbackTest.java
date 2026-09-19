package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.persistence.RollbackResult;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.storage.InMemoryCheckpointStore;
import com.agentframework.runtime.persistence.CheckpointEntry;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;

import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检查点历史与回滚测试：步进快照、截断、续跑与副作用边界。
 */
class CheckpointRollbackTest {

    @Test
    @DisplayName("每步都留下检查点，步号与经过的边可读")
    void historyRecordsEveryStep() {
        Engine engine = engine(new InMemoryCheckpointStore());
        RunResult result = engine.run("rollback-agent", Input.of("开始"));

        List<CheckpointEntry> history = engine.history(result.sessionId());

        assertEquals(List.of(0, 1, 2), history.stream().map(CheckpointEntry::step).toList());
        assertEquals(java.util.Arrays.asList(null, "a->b", "b->c"),
                history.stream().map(CheckpointEntry::edge).toList());
        assertEquals("a", history.get(0).session().cursor().nodeId());
    }

    @Test
    @DisplayName("回滚到中间步后可从该步继续，且历史被截断")
    void rollbackRestoresIntermediateStateAndResumes() {
        Engine engine = engine(new InMemoryCheckpointStore());
        RunResult completed = engine.run("rollback-agent", Input.of("开始"));
        assertEquals(List.of("a", "b", "c"), completed.visitedNodes());

        RollbackResult rollback = engine.rollback(completed.sessionId(), 1);

        assertEquals(1, rollback.step());
        assertEquals("b", rollback.cursor().nodeId());
        assertTrue(rollback.slots().containsKey("out_a"));
        assertFalse(rollback.slots().containsKey("out_c"), "回滚后不应保留后续步骤写入的槽位");
        assertEquals(List.of(0, 1), engine.history(completed.sessionId()).stream()
                .map(CheckpointEntry::step).toList());

        Session restored = engine.contexts().load(completed.sessionId()).orElseThrow();
        assertEquals(SessionState.SUSPENDED, restored.state());

        RunResult resumed = engine.resume(restored, Input.empty());

        assertEquals(SessionState.COMPLETED, resumed.state(), () -> "实际错误：" + resumed.error());
        assertEquals(List.of("b", "c"), resumed.visitedNodes());
    }

    @Test
    @DisplayName("回滚到不存在的步会给出可用步号")
    void rollbackToUnknownStepFails() {
        Engine engine = engine(new InMemoryCheckpointStore());
        RunResult result = engine.run("rollback-agent", Input.of("开始"));

        NoSuchElementException failure = assertThrows(NoSuchElementException.class,
                () -> engine.rollback(result.sessionId(), 99));

        assertTrue(failure.getMessage().contains("可用步号"), () -> "实际消息：" + failure.getMessage());
    }

    @Test
    @DisplayName("保留策略限制每会话的检查点数量")
    void historyRetentionLimit() {
        Engine engine = engine(new InMemoryCheckpointStore(2));

        RunResult result = engine.run("rollback-agent", Input.of("开始"));

        assertEquals(2, engine.history(result.sessionId()).size());
    }

    @Test
    @DisplayName("回滚结果声明副作用边界")
    void rollbackResultDeclaresSideEffectBoundary() {
        Engine engine = engine(new InMemoryCheckpointStore());
        RunResult result = engine.run("rollback-agent", Input.of("开始"));

        RollbackResult rollback = engine.rollback(result.sessionId(), 0);

        assertEquals("a", rollback.cursor().nodeId());
        assertTrue(rollback.notice().contains("不可回滚"), () -> "实际提示：" + rollback.notice());
    }

    /**
     * @param store 检查点存储
     * @return 三节点线性工作流引擎
     */
    private Engine engine(InMemoryCheckpointStore store) {
        WorkflowDefinition workflow = WorkflowBuilder.create("rollback-wf", "1.0.0")
                .node(LlmNodeDefinition.of("a", "prompt-a", "out_a"))
                .node(LlmNodeDefinition.of("b", "prompt-b", "out_b"))
                .node(LlmNodeDefinition.of("c", "prompt-c", "out_c")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("a", "b")
                .edge("b", "c")
                .slot("out_a", SlotType.STRING)
                .slot("out_b", SlotType.STRING)
                .slot("out_c", SlotType.STRING)
                .build();
        return EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("prompt-a", "A"))
                .prompt(PromptDefinition.template("prompt-b", "B"))
                .prompt(PromptDefinition.template("prompt-c", "C"))
                .modelProvider(new EchoModelProvider("echo", request -> "step"))
                .defaultModelProvider("echo")
                .agent(AgentDefinition.builder("rollback-agent").workflow("rollback-wf")
                        .model("echo", "echo").build())
                .checkpointStore(store)
                .build();
    }
}
