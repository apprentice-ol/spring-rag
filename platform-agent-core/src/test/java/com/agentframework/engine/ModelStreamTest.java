package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.InterceptorPhase;
import com.agentframework.crosscutting.interceptor.Invocation;
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
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.modelgateway.ModelCallContext;
import com.agentframework.infra.modelgateway.ModelProvider;
import com.agentframework.infra.modelgateway.ModelRequest;
import com.agentframework.infra.modelgateway.ModelResponse;
import com.agentframework.infra.modelgateway.StreamHandler;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.SessionState;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型流式契约测试：默认退化、token 事件、拦截链语义与未声明路径回归。
 */
class ModelStreamTest {

    @Test
    @DisplayName("Provider 未重写 stream 时退化为整段单 token，槽位内容完整")
    void defaultStreamDegeneratesToSingleToken() {
        InMemoryEventBus events = new InMemoryEventBus();
        Engine engine = engine(new EchoModelProvider("echo", request -> "完整回答"), true, events);

        RunResult result = engine.run("stream-agent", Input.of("问题"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals("完整回答", result.slots().get("out"));
        assertEquals(1L, events.count(Topics.MODEL_TOKEN));
        Event token = events.history().stream()
                .filter(event -> event.type().equals(Topics.MODEL_TOKEN))
                .findFirst().orElseThrow();
        assertEquals("gen", token.payload().get("nodeId"));
        assertEquals("完整回答", token.payload().get("text"));
    }

    @Test
    @DisplayName("Provider 重写 stream 时 token 片段按序外发，聚合文本写入槽位")
    void chunkedStreamEmitsOrderedTokensAndAggregates() {
        InMemoryEventBus events = new InMemoryEventBus();
        Engine engine = engine(new ChunkedProvider("第一段", "第二段", "第三段"), true, events);

        RunResult result = engine.run("stream-agent", Input.of("问题"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals("第一段第二段第三段", result.slots().get("out"));
        List<String> pieces = events.history().stream()
                .filter(event -> event.type().equals(Topics.MODEL_TOKEN))
                .map(event -> String.valueOf(event.payload().get("text")))
                .toList();
        assertEquals(List.of("第一段", "第二段", "第三段"), pieces);
    }

    @Test
    @DisplayName("节点未声明 stream 属性时保持阻塞调用，不产生 token 事件")
    void nodeWithoutStreamAttributeStaysBlocking() {
        InMemoryEventBus events = new InMemoryEventBus();
        Engine engine = engine(new ChunkedProvider("第一段", "第二段"), false, events);

        RunResult result = engine.run("stream-agent", Input.of("问题"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals("第一段第二段", result.slots().get("out"));
        assertEquals(0L, events.count(Topics.MODEL_TOKEN));
    }

    @Test
    @DisplayName("流式调用仍经过 AROUND_LLM 拦截链")
    void streamCallStillGoesThroughAroundLlmInterceptors() {
        InMemoryEventBus events = new InMemoryEventBus();
        AtomicInteger aroundLlmCalls = new AtomicInteger();
        Engine engine = engineBuilder(new ChunkedProvider("片段"), true, events)
                .interceptor(new Interceptor() {
                    @Override
                    public String name() {
                        return "count-llm";
                    }

                    @Override
                    public boolean supports(InterceptorContext context) {
                        return context.phase() == InterceptorPhase.AROUND_LLM;
                    }

                    @Override
                    public <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception {
                        aroundLlmCalls.incrementAndGet();
                        return invocation.proceed();
                    }
                })
                .build();

        RunResult result = engine.run("stream-agent", Input.of("问题"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(1, aroundLlmCalls.get());
        assertEquals(1L, events.count(Topics.MODEL_TOKEN));
    }

    @Test
    @DisplayName("DefaultModelGateway.stream 路由提供方并返回聚合结果")
    void gatewayStreamRoutesProviderAndReturnsAggregation() {
        com.agentframework.infra.modelgateway.DefaultModelGateway gateway =
                new com.agentframework.infra.modelgateway.DefaultModelGateway("chunk", null)
                        .register(new ChunkedProvider("甲", "乙"));
        List<String> received = new java.util.ArrayList<>();
        ModelRequest request = ModelRequest.of("chunk", "m", List.of());

        ModelResponse response = gateway.stream(request,
                ModelCallContext.of("s", "n").withTimeout(java.time.Duration.ofSeconds(60)), received::add);

        assertEquals("甲乙", response.content());
        assertEquals(List.of("甲", "乙"), received);
    }

    /**
     * @param provider  模型提供方
     * @param streaming 节点是否声明 stream 属性
     * @param events    事件总线
     * @return 引擎
     */
    private Engine engine(ModelProvider provider, boolean streaming, InMemoryEventBus events) {
        return engineBuilder(provider, streaming, events).build();
    }

    /**
     * @param provider  模型提供方
     * @param streaming 节点是否声明 stream 属性
     * @param events    事件总线
     * @return 装配器
     */
    private EngineBuilder engineBuilder(ModelProvider provider, boolean streaming, InMemoryEventBus events) {
        LlmNodeDefinition gen = LlmNodeDefinition.of("gen", "gen-prompt", "out")
                .withMeta(NodeMeta.empty().withAttribute("terminal", true));
        if (streaming) {
            gen = LlmNodeDefinition.of("gen", "gen-prompt", "out")
                    .withMeta(NodeMeta.empty().withAttribute("terminal", true).withAttribute("stream", true));
        }
        WorkflowDefinition workflow = WorkflowBuilder.create("stream-wf", "1.0.0")
                .node(gen)
                .slot("out", SlotType.STRING)
                .build();
        return EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("gen-prompt", "回答用户问题"))
                .modelProvider(provider)
                .defaultModelProvider(provider.id())
                .agent(AgentDefinition.builder("stream-agent").workflow("stream-wf")
                        .model(provider.id(), "test-model").build())
                .events(events);
    }

    /** 按固定片段流式输出的测试提供方。 */
    private static final class ChunkedProvider implements ModelProvider {

        private final String[] pieces;

        ChunkedProvider(String... pieces) {
            this.pieces = pieces;
        }

        @Override
        public String id() {
            return "chunk";
        }

        @Override
        public ModelResponse complete(ModelRequest request, ModelCallContext context) {
            StringBuilder all = new StringBuilder();
            for (String piece : pieces) {
                all.append(piece);
            }
            return ModelResponse.text(all.toString());
        }

        @Override
        public ModelResponse stream(ModelRequest request, ModelCallContext context, StreamHandler handler) {
            StringBuilder all = new StringBuilder();
            for (String piece : pieces) {
                all.append(piece);
                if (handler != null) {
                    handler.onToken(piece);
                }
            }
            return ModelResponse.text(all.toString());
        }
    }
}
