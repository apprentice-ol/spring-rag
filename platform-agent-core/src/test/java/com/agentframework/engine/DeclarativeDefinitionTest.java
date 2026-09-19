package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.codec.DefinitionKind;
import com.agentframework.definition.codec.DefinitionLoader;
import com.agentframework.definition.codec.LoadResult;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.agentmanager.StoreBackedDefinitionSource;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.definition.DefinitionPublisher;
import com.agentframework.extension.permission.PermissionDeniedException;
import com.agentframework.extension.permission.PermissionChecker;
import com.agentframework.extension.permission.PermissionSet;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.storage.FileDefinitionStore;
import com.agentframework.infra.storage.InMemoryDefinitionStore;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.persistence.DefinitionRecord;
import com.agentframework.runtime.persistence.DefinitionStatus;
import com.agentframework.runtime.persistence.DefinitionStore;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.SessionState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 声明式定义入库测试：草稿 / 发布 / 归档、权限、审计与引擎装载。 */
class DeclarativeDefinitionTest {

    private final DefinitionLoader loader = new DefinitionLoader();

    @Test
    @DisplayName("纯 JSON 定义的 Agent 可在引擎中运行")
    void declarativeAgentRuns() {
        InMemoryDefinitionStore store = new InMemoryDefinitionStore();
        DefinitionPublisher publisher = publisher(store, PermissionSet.all(), null);
        publisher.saveDraft(DefinitionKind.PROMPT, promptDocument("prompt-hello", "1.0.0"), "author");
        publisher.saveDraft(DefinitionKind.WORKFLOW, workflowDocument("declarative-wf", "1.0.0"), "author");
        publisher.saveDraft(DefinitionKind.AGENT, agentDocument("declarative-agent", "1.0.0"), "author");
        publisher.publish(DefinitionKind.WORKFLOW, "declarative-wf", "1.0.0", "ops");
        publisher.publish(DefinitionKind.PROMPT, "prompt-hello", "1.0.0", "ops");
        publisher.publish(DefinitionKind.AGENT, "declarative-agent", "1.0.0", "ops");

        Engine engine = EngineBuilder.create()
                .definitions(new StoreBackedDefinitionSource(store))
                .modelProvider(new EchoModelProvider("echo", request -> "声明式回答"))
                .defaultModelProvider("echo")
                .build();

        RunResult result = engine.run("declarative-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(List.of("hello"), result.visitedNodes());
        assertEquals("声明式回答", result.output());
    }

    @Test
    @DisplayName("未发布的草稿对引擎不可见")
    void draftIsInvisibleToEngine() {
        InMemoryDefinitionStore store = new InMemoryDefinitionStore();
        DefinitionPublisher publisher = publisher(store, PermissionSet.all(), null);
        publisher.saveDraft(DefinitionKind.WORKFLOW, workflowDocument("draft-wf", "1.0.0"), "author");
        StoreBackedDefinitionSource source = new StoreBackedDefinitionSource(store);

        assertTrue(source.workflow("draft-wf", "1.0.0").isEmpty());
        assertTrue(source.workflow("draft-wf", "latest").isEmpty());

        publisher.publish(DefinitionKind.WORKFLOW, "draft-wf", "1.0.0", "ops");
        source.invalidate("draft-wf");

        assertTrue(source.workflow("draft-wf", "1.0.0").isPresent());
    }

    @Test
    @DisplayName("latest 解析为最新已发布版本")
    void latestResolvesToNewestPublished() {
        InMemoryDefinitionStore store = new InMemoryDefinitionStore();
        DefinitionPublisher publisher = publisher(store, PermissionSet.all(), null);
        publisher.saveDraft(DefinitionKind.WORKFLOW, workflowDocument("versioned-wf", "1.0.0"), "author");
        publisher.publish(DefinitionKind.WORKFLOW, "versioned-wf", "1.0.0", "ops");
        publisher.saveDraft(DefinitionKind.WORKFLOW, workflowDocument("versioned-wf", "2.0.0"), "author");
        publisher.publish(DefinitionKind.WORKFLOW, "versioned-wf", "2.0.0", "ops");
        StoreBackedDefinitionSource source = new StoreBackedDefinitionSource(store);

        assertEquals("2.0.0", source.workflow("versioned-wf", "latest").orElseThrow().version());
        assertEquals("1.0.0", source.workflow("versioned-wf", "1.0.0").orElseThrow().version());
    }

    @Test
    @DisplayName("发布需要权限，拒绝时状态不变")
    void publishRequiresPermission() {
        InMemoryDefinitionStore store = new InMemoryDefinitionStore();
        publisher(store, PermissionSet.all(), null)
                .saveDraft(DefinitionKind.WORKFLOW, workflowDocument("secure-wf", "1.0.0"), "author");
        DefinitionPublisher restricted = publisher(store, PermissionSet.none(), null);

        assertThrows(PermissionDeniedException.class,
                () -> restricted.publish(DefinitionKind.WORKFLOW, "secure-wf", "1.0.0", "ops"));

        assertTrue(store.find(DefinitionKind.WORKFLOW, "secure-wf", "1.0.0", DefinitionStatus.DRAFT).isPresent());
        assertTrue(store.find(DefinitionKind.WORKFLOW, "secure-wf", "1.0.0", DefinitionStatus.PUBLISHED).isEmpty());
    }

    @Test
    @DisplayName("归档后引擎不再可见，历史仍可查")
    void archiveHidesFromEngine() {
        InMemoryDefinitionStore store = new InMemoryDefinitionStore();
        DefinitionPublisher publisher = publisher(store, PermissionSet.all(), null);
        publisher.saveDraft(DefinitionKind.WORKFLOW, workflowDocument("archived-wf", "1.0.0"), "author");
        publisher.publish(DefinitionKind.WORKFLOW, "archived-wf", "1.0.0", "ops");
        StoreBackedDefinitionSource source = new StoreBackedDefinitionSource(store);
        assertTrue(source.workflow("archived-wf", "1.0.0").isPresent());

        publisher.archive(DefinitionKind.WORKFLOW, "archived-wf", "1.0.0", "ops");
        source.invalidate("archived-wf");

        assertTrue(source.workflow("archived-wf", "1.0.0").isEmpty());
        List<DefinitionRecord> history = store.history(DefinitionKind.WORKFLOW, "archived-wf");
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(record -> record.status() == DefinitionStatus.ARCHIVED
                && record.publishedAt() != null));
    }

    @Test
    @DisplayName("状态迁移发布审计事件并携带报告摘要")
    void stateChangesAreAudited() {
        InMemoryDefinitionStore store = new InMemoryDefinitionStore();
        InMemoryEventBus events = new InMemoryEventBus();
        DefinitionPublisher publisher = publisher(store, PermissionSet.all(), events);
        publisher.saveDraft(DefinitionKind.WORKFLOW, workflowDocument("audited-wf", "1.0.0"), "author");
        publisher.publish(DefinitionKind.WORKFLOW, "audited-wf", "1.0.0", "ops");
        publisher.archive(DefinitionKind.WORKFLOW, "audited-wf", "1.0.0", "ops");

        assertEquals(1L, events.count(Topics.DEFINITION_SAVED));
        assertEquals(1L, events.count(Topics.DEFINITION_PUBLISHED));
        assertEquals(1L, events.count(Topics.DEFINITION_ARCHIVED));
        assertTrue(events.history().stream()
                .filter(event -> event.type().equals(Topics.DEFINITION_PUBLISHED))
                .allMatch(event -> event.payload().containsKey("errors")));
    }

    @Test
    @DisplayName("文件存储可落盘再读回，校验报告一并保留")
    void fileStoreRoundTrip() throws Exception {
        Path root = Files.createTempDirectory("agent-framework-definitions");
        try {
            DefinitionStore store = new FileDefinitionStore(root);
            DefinitionPublisher publisher = publisher(store, PermissionSet.all(), null);
            publisher.saveDraft(DefinitionKind.WORKFLOW, workflowDocument("file-wf", "1.0.0"), "author");
            publisher.publish(DefinitionKind.WORKFLOW, "file-wf", "1.0.0", "ops");

            DefinitionRecord loaded = store.find(DefinitionKind.WORKFLOW, "file-wf", "1.0.0",
                    DefinitionStatus.PUBLISHED).orElseThrow();

            assertEquals("file-wf", loaded.id());
            assertEquals("ops", loaded.author());
            assertEquals(2, store.history(DefinitionKind.WORKFLOW, "file-wf").size());
            assertTrue(new StoreBackedDefinitionSource(store).workflow("file-wf", "1.0.0").isPresent());
        } finally {
            deleteDirectory(root);
        }
    }

    /**
     * @param store       定义存储
     * @param permissions 权限集合
     * @param events      事件总线，可为 null
     * @return 发布器
     */
    private DefinitionPublisher publisher(DefinitionStore store, PermissionSet permissions, InMemoryEventBus events) {
        return new DefinitionPublisher(store, new PermissionChecker(permissions), events);
    }

    /**
     * @param id      工作流 id
     * @param version 版本号
     * @return 工作流加载结果
     */
    private LoadResult workflowDocument(String id, String version) {
        WorkflowDefinition workflow = WorkflowBuilder.create(id, version)
                .node(LlmNodeDefinition.of("hello", "prompt-hello", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .build();
        LoadResult result = loader.load(DefinitionKind.WORKFLOW,
                loader.codec().encode(DefinitionKind.WORKFLOW, workflow));
        assertTrue(result.accepted(), () -> "工作流文档无效：" + result.report().messages());
        return result;
    }

    /**
     * @param id  Agent id
     * @param version 版本号
     * @return Agent 加载结果
     */
    private LoadResult agentDocument(String id, String version) {
        AgentDefinition agent = AgentDefinition.builder(id, version)
                .workflow("declarative-wf", "1.0.0")
                .model("echo", "echo")
                .build();
        LoadResult result = loader.load(DefinitionKind.AGENT,
                loader.codec().encode(DefinitionKind.AGENT, agent));
        assertTrue(result.accepted(), () -> "Agent 文档无效：" + result.report().messages());
        return result;
    }

    /**
     * @param id      Prompt 资产 id
     * @param version 版本号
     * @return Prompt 加载结果
     */
    private LoadResult promptDocument(String id, String version) {
        PromptDefinition prompt = PromptDefinition.of(id, version, "问候：{{messages}}");
        LoadResult result = loader.load(DefinitionKind.PROMPT,
                loader.codec().encode(DefinitionKind.PROMPT, prompt));
        assertTrue(result.accepted());
        return result;
    }

    /**
     * @param root 目录
     */
    private void deleteDirectory(Path root) {
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 清理失败不影响断言
                }
            });
        } catch (Exception ignored) {
            // 清理失败不影响断言
        }
    }
}
