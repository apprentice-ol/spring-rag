package com.jjx.customer.platform.business;
import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.prompt.service.PromptBindingService;
import com.jjx.customer.platform.prompt.snapshot.PromptStoreSnapshotSource;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.jjx.customer.platform.business.agents.KnowledgeFrameworkAgent;
import com.jjx.customer.platform.business.routing.KnowledgeRouteStrategy;
import com.jjx.customer.platform.knowledge.tools.RetrievalExtensionTool;
import com.jjx.customer.platform.business.workflows.KnowledgeQaWorkflow;

import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.OutcomeKind;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.agent.ExecutionPlanner;
import com.jjx.customer.platform.agent.framework.agent.WorkflowEngine;
import com.jjx.customer.platform.agent.framework.agent.AgentWorkflowBindingResolver;
import com.jjx.customer.platform.agent.framework.capability.CapabilityConfig;
import com.jjx.customer.platform.agent.framework.workflow.DefaultWorkflowDriver;
import com.jjx.customer.platform.agent.framework.node.DeterministicNodeExecutor;
import com.jjx.customer.platform.agent.framework.node.NodeExecutorRegistry;
import com.jjx.customer.platform.agent.framework.agent.AgentRegistry;
import com.jjx.customer.platform.agent.framework.workflow.InMemoryWorkflowCatalog;
import com.jjx.customer.platform.agent.framework.route.RouteTable;
import com.jjx.customer.platform.agent.framework.tool.ToolRegistry;
import com.jjx.customer.platform.knowledge.retrieval.MultiChannelRetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelType;
import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.config.properties.ChatProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直配路径验证：RAG 作为"Agent + Workflow"经框架引擎执行，产出上下文/引用/生成规格。
 */
class FrameworkKnowledgePathTest {

    @Test
    void 知识问答走框架引擎_产出上下文引用与生成规格() {
        MultiChannelRetrievalEngine.RetrievalResult retrievalResult =
                new MultiChannelRetrievalEngine.RetrievalResult(List.of(), List.of(
                        new RetrievedChunk("片段一：发票冲红流程说明", 0.92,
                                Map.of("doc_name", "invoice.md"), SearchChannelType.VECTOR),
                        new RetrievedChunk("片段二：冲红常见错误码", 0.81,
                                Map.of("doc_name", "faq.md"), SearchChannelType.KEYWORD)),
                        12L);
        RetrievalEngine retrievalEngine = context -> retrievalResult;

        RetrievalExtensionTool tool = new RetrievalExtensionTool(retrievalEngine, new ChatProperties());
        KnowledgeQaWorkflow workflow = new KnowledgeQaWorkflow();
        KnowledgeFrameworkAgent agent = new KnowledgeFrameworkAgent(workflow);
        PromptStoreSnapshotSource snapshotSource = new PromptStoreSnapshotSource(new PromptStore(), noBindings());

        ToolRegistry tools = new ToolRegistry(List.of(tool));
        NodeExecutorRegistry executors = new NodeExecutorRegistry(List.of(new DeterministicNodeExecutor(tools)));
        ExecutionPlanner planner = new ExecutionPlanner(
                new RouteTable(List.of(new KnowledgeRouteStrategy(agent))),
                new InMemoryWorkflowCatalog(List.of((Workflow) workflow)),
                AgentWorkflowBindingResolver.DEFAULT, CapabilityConfig.DEFAULT, snapshotSource);
        WorkflowEngine engine = new WorkflowEngine(planner, new DefaultWorkflowDriver(executors),
                new AgentRegistry(List.of((Agent) agent)), 3);

        ExecutionResult result = engine.execute(new AgentRequest("发票怎么冲红", Map.of()));

        assertEquals(OutcomeKind.WITH_CONTEXT, result.kind());
        assertEquals(2, result.context().artifacts().size());
        assertEquals(2, result.citations().citations().size());
        assertEquals(2, result.retrievalStats().returned());
        assertEquals("knowledge", result.fingerprint().agentId());
        assertEquals("knowledge_qa", result.fingerprint().workflowId());
        assertFalse(result.fingerprint().promptHash().isBlank(), "prompt 内容 hash 必须进指纹");
        assertTrue(result.generation().answerPromptContent().contains("引用"),
                "答案 prompt 内容应来自 PromptStore 的 rag-answer-kb");
        assertTrue(result.generation().assembledContextText().contains("[ref=1]"));
        assertTrue(result.trace().steps().stream().anyMatch(s -> s.action().equals("retrieve")));
    }
    /** 无绑定 stub：overridesFor 返回空 map（快照层回退 classpath，等价接线前行为）。 */
    private static PromptBindingService noBindings() {
        PromptBindingService m = mock(PromptBindingService.class);
        when(m.overridesFor(anyString())).thenReturn(java.util.Map.of());
        return m;
    }
}
