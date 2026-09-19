package com.agentframework.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.metrics.InMemoryMetrics;
import com.agentframework.infra.messagebus.BusMessage;
import com.agentframework.infra.messagebus.EventBusBridge;
import com.agentframework.infra.messagebus.InMemoryMessageBus;
import com.agentframework.infra.modelgateway.DefaultModelGateway;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.modelgateway.ModelCallContext;
import com.agentframework.infra.modelgateway.ModelProvider;
import com.agentframework.infra.modelgateway.ModelRequest;
import com.agentframework.infra.modelgateway.ModelResponse;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.agentframework.infra.storage.FileSessionStore;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.infra.storage.InMemorySessionStore;
import com.agentframework.infra.storage.InMemorySlotStore;
import com.agentframework.infra.storage.InMemoryWorkspace;
import com.agentframework.infra.vector.InMemoryVectorStore;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.session.DefaultSession;
import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.MutableSession;
import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.runtime.slot.Slots;
import com.agentframework.runtime.workspace.Snapshot;
import com.agentframework.runtime.workspace.VectorMatch;
import com.agentframework.runtime.workspace.VectorRecord;
import com.agentframework.runtime.workspace.WorkspaceTemplate;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 基础设施层测试：存储、向量、模型网关与消息总线。 */
class InfrastructureTest {

    @Test
    @DisplayName("内存会话存储可保存、读取并按租户过滤")
    void inMemorySessionStore() {
        InMemorySessionStore store = new InMemorySessionStore();
        MutableSession session = new DefaultSession("s1", "tenant-a", "user", "agent", "1.0.0", "wf", "1.0.0");
        session.state(SessionState.RUNNING);
        session.appendMessage(Message.user("你好"));
        store.save(session.toRecord());

        SessionRecord loaded = store.load("s1").orElseThrow();
        assertEquals(SessionState.RUNNING, loaded.state());
        assertEquals(1, loaded.messages().size());
        assertEquals(1, store.listByTenant("tenant-a").size());
        assertTrue(store.listByTenant("tenant-b").isEmpty());
        assertTrue(store.delete("s1"));
    }

    @Test
    @DisplayName("文件会话存储支持跨进程恢复")
    void fileSessionStore(@TempDir Path dir) {
        FileSessionStore store = new FileSessionStore(dir);
        MutableSession session = new DefaultSession("s2", "tenant-a", "user", "agent", "1.0.0", "wf", "1.0.0");
        session.state(SessionState.SUSPENDED);
        session.cursor(Cursor.at("approve").advanceTo("approve"));
        session.appendMessage(Message.assistant("第一行\n第二行"));
        store.save(session.toRecord());

        SessionRecord loaded = store.load("s2").orElseThrow();
        assertEquals(SessionState.SUSPENDED, loaded.state());
        assertEquals("approve", loaded.cursor().nodeId());
        assertEquals("第一行\n第二行", loaded.messages().get(0).content());
        assertEquals(1, store.list().size());
        assertTrue(store.delete("s2"));
    }

    @Test
    @DisplayName("槽位存储保存快照并支持还原")
    void slotStoreRoundTrip() {
        InMemorySlotStore store = new InMemorySlotStore();
        Slots slots = new Slots(Map.of("plan", "第一步", "count", 2));
        store.save(slots.snapshot("s1"));

        Slots restored = Slots.fromSnapshot(store.load("s1").orElseThrow());
        assertEquals("第一步", restored.get("plan"));
        assertEquals(2, restored.getInt("count", 0));
        assertEquals(2, restored.size());
    }

    @Test
    @DisplayName("向量索引按余弦相似度排序并支持元数据过滤")
    void vectorStore() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(new VectorRecord("a", new float[] {1f, 0f}, "第一条", Map.of("kind", "doc")));
        store.upsert(new VectorRecord("b", new float[] {0.9f, 0.1f}, "第二条", Map.of("kind", "doc")));
        store.upsert(new VectorRecord("c", new float[] {0f, 1f}, "第三条", Map.of("kind", "image")));

        List<VectorMatch> hits = store.query(new float[] {1f, 0f}, 2, Map.of("kind", "doc"));
        assertEquals(2, hits.size());
        assertEquals("a", hits.get(0).id());
        assertEquals(3, store.size());
        store.delete("a");
        assertEquals(2, store.size());
    }

    @Test
    @DisplayName("模型网关按提供方路由并记录指标")
    void modelGatewayRouting() {
        InMemoryMetrics metrics = new InMemoryMetrics();
        DefaultModelGateway gateway = new DefaultModelGateway("echo", metrics)
                .register(new EchoModelProvider())
                .register(new ModelProvider() {
                    @Override
                    public String id() {
                        return "vendor-x";
                    }

                    @Override
                    public ModelResponse complete(ModelRequest request, ModelCallContext context) {
                        return ModelResponse.text("来自 vendor-x").withIdentity("vendor-x", request.model());
                    }
                });

        ModelResponse response = gateway.complete(
                ModelRequest.of("vendor-x", "big-model", List.of(com.agentframework.infra.modelgateway.ChatMessage.user("hi"))),
                ModelCallContext.of("s1", "n1"));

        assertEquals("来自 vendor-x", response.content());
        assertTrue(gateway.providerIds().containsAll(List.of("echo", "vendor-x")));
        assertEquals(1L, metrics.counterValue("model.calls"));
    }

    @Test
    @DisplayName("脚本化模型提供方记录请求并按序返回")
    void scriptedModelProvider() {
        ScriptedModelProvider provider = new ScriptedModelProvider("scripted").enqueueText("第一次", "第二次");

        ModelRequest request = ModelRequest.of("scripted", "m",
                List.of(com.agentframework.infra.modelgateway.ChatMessage.user("问题")));
        assertEquals("第一次", provider.complete(request, ModelCallContext.of("s", "n")).content());
        assertEquals("第二次", provider.complete(request, ModelCallContext.of("s", "n")).content());
        assertEquals(2, provider.requests().size());
        assertEquals(0, provider.remaining());
    }

    @Test
    @DisplayName("事件总线到消息总线的桥接保持事件类型与负载")
    void eventBusBridge() {
        InMemoryEventBus events = new InMemoryEventBus();
        InMemoryMessageBus bus = new InMemoryMessageBus();
        AtomicReference<BusMessage> received = new AtomicReference<>();
        bus.subscribe("agent.events", received::set);

        new EventBusBridge(bus).bridge(events, "agent.events");
        events.publish(Event.of(Topics.SESSION_COMPLETED, "s1", Map.of("nodes", 3)).withTrace("trace-1"));

        assertTrue(received.get() != null);
        assertEquals("s1", received.get().key());
        assertEquals(Topics.SESSION_COMPLETED, received.get().payload().get("eventType"));
        assertEquals("trace-1", received.get().headers().get("traceId"));
    }

    @Test
    @DisplayName("工作区按模板初始化并支持快照与恢复")
    void workspaceSnapshotAndRestore() {
        WorkspaceTemplate template = WorkspaceTemplate.of("seed")
                .withFile("notes/readme.md", "初始内容")
                .withMemory("topic", "调研");
        InMemoryWorkspace workspace = InMemoryWorkspace.fromTemplate("ws-1", "s1", template);

        assertEquals("初始内容", workspace.fs().readString("notes/readme.md"));
        assertEquals("调研", workspace.memory().get("topic"));

        workspace.fs().writeString("notes/readme.md", "修改后的内容");
        workspace.memory().put("topic", "已更新");
        Snapshot snapshot = workspace.snapshot("before");

        workspace.fs().writeString("notes/readme.md", "再一次修改");
        workspace.restore(snapshot);

        assertEquals("修改后的内容", workspace.fs().readString("notes/readme.md"));
        assertEquals("已更新", workspace.memory().get("topic"));
        assertFalse(workspace.stateSummary().isEmpty());
    }

    @Test
    @DisplayName("事件总线隔离订阅者异常")
    void eventBusIsolatesFailures() {
        InMemoryEventBus events = new InMemoryEventBus();
        events.subscribeAll(event -> {
            throw new IllegalStateException("订阅者异常");
        });
        events.subscribeAll(event -> {
        });
        events.publish(Event.of(Topics.NODE_STARTED, "s1", Map.of()));
        assertEquals(1, events.history().size());
    }
}
