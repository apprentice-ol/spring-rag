package com.jjx.customer.platform.business.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.interceptor.TaskPropagation;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.runtime.session.Input;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 引擎执行节点时，线程上的 OTel 上下文还在不在？
 *
 * <p>问题的由来：聊天链路在 OpenObserve 里断成好几截 —— {@code rag.chat} 之下只有编排层那几个
 * span，图内节点产生的 span（{@code rag.retrieve} 等）各自成了独立的根 trace。</p>
 *
 * <p>根因：引擎的 {@code Interceptors.Timeout} 把下游调用 submit 到一个新开的虚拟线程上执行，
 * 而新线程不继承线程本地变量，OTel 上下文当场丢失。修法是在内核留一个跨线程任务包装的扩展点
 * （{@code TaskPropagation}），由宿主注入自己的上下文传播器。</p>
 *
 * <p>这条测试把那个修复钉住：先 {@code makeCurrent()} 一个父 span，再跑图，断言节点执行器里
 * {@code Span.current()} 拿到的<b>就是</b>父 span。传播器没装、或内核那条 submit 绕过了包装，
 * 这里都会红。</p>
 */
class EngineTraceContextTest {

    /** 记录节点执行那一刻线程上的 OTel span 上下文与线程名。 */
    private static final class ProbeExecutor implements NodeExecutor {

        private final List<SpanContext> seen = new ArrayList<>();

        private final List<String> threads = new ArrayList<>();

        @Override
        public NodeType type() {
            return NodeType.CUSTOM;
        }

        @Override
        public NodeResult execute(NodeDefinition node, NodeContext context) {
            seen.add(Span.current().getSpanContext());
            threads.add(Thread.currentThread().getName());
            return NodeResult.completed(node.id(), "ok");
        }
    }

    @Test
    void 节点执行器应能看到调用方的父span() {
        // 生产由 AgentEngineConfiguration 注入 ContextPropagator::wrap；
        // 这里用等价的纯 OTel 写法，免得测试反过来依赖遥测库。
        // 顺带记录钩子被调了几次、捕获时上下文长什么样——失败时要能一眼看出断在哪一环。
        StringBuilder diag = new StringBuilder();
        java.util.concurrent.atomic.AtomicInteger wraps = new java.util.concurrent.atomic.AtomicInteger();
        TaskPropagation.install(task -> {
            wraps.incrementAndGet();
            SpanContext at = Span.current().getSpanContext();
            diag.append("[wrap] 线程=").append(Thread.currentThread().getName())
                    .append(" 捕获到 span 有效=").append(at.isValid())
                    .append(" trace=").append(at.getTraceId()).append('\n');
            return Context.current().wrap(task);
        });

        ProbeExecutor probe = new ProbeExecutor();
        Engine engine = EngineBuilder.create()
                .workflow(WorkflowBuilder.create("probe-wf", "1.0.0")
                        .node(new CustomNodeDefinition("probe", "probe-exec", java.util.Map.of(), "out",
                                NodeMeta.empty().withAttribute("terminal", true)))
                        .slot("out", SlotType.STRING)
                        .build())
                .nodeExecutor("probe-exec", probe)
                .agent(AgentDefinition.builder("probe-agent").workflow("probe-wf").build())
                .build();

        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        Tracer tracer = provider.get("trace-context-probe");
        Span parent = tracer.spanBuilder("parent").startSpan();
        String callerThreadName = Thread.currentThread().getName();
        try (Scope ignored = parent.makeCurrent()) {
            engine.run("probe-agent", Input.of("hi"));
        } finally {
            parent.end();
            provider.close();
            engine.close();
            // 传播器是进程级静态装配，用完必须还原，免得污染同 JVM 里的其它用例
            TaskPropagation.install(null);
        }

        assertEquals(1, probe.seen.size(), "探针节点应执行一次");
        SpanContext seenInNode = probe.seen.get(0);
        String where = "调用方线程=" + callerThreadName + "，节点线程=" + probe.threads.get(0)
                + "，TaskPropagation 被调用 " + wraps.get() + " 次\n" + diag;
        assertTrue(seenInNode.isValid(), "节点执行时线程上没有有效的 OTel span（" + where + "）");
        assertEquals(parent.getSpanContext().getTraceId(), seenInNode.getTraceId(),
                "节点执行时的 span 应属于调用方那条 trace");
        assertEquals(parent.getSpanContext().getSpanId(), seenInNode.getSpanId(),
                "节点执行时的 span 应当就是调用方的父 span");
    }
}
