package com.jjx.customer.platform.agent.framework.result;

import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.capability.AgentCapabilityContractException;
import com.jjx.customer.platform.agent.framework.trace.AgentTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionResultValidatorTest {

    private static final ExecutionFingerprint FP =
            new ExecutionFingerprint("knowledge", "knowledge_qa_fast", "abc123", null);
    private static final AgentTrace TRACE = AgentTrace.empty("knowledge", "knowledge_qa_fast");

    private static GenerationSpec generation() {
        return new GenerationSpec("workflow/knowledge_qa/answer", "内容快照", "上下文装配结果", FP);
    }

    @Test
    void 声明流式但缺生成规格_报错() {
        ExecutionResult result = ExecutionResult.direct("直出文本", FP, TRACE);

        assertThrows(AgentCapabilityContractException.class,
                () -> ExecutionResultValidator.validate(result, Set.of(AgentCapability.STREAMING)));
    }

    @Test
    void 上下文非空但引用索引为空_报错() {
        ExecutionResult result = ExecutionResult.withContext(
                new ContextBundle(List.of(new ContextArtifact(1, "片段", "doc.md", 0.9, "VECTOR", null))),
                CitationIndex.empty(), generation(), FP, null, TRACE);

        assertThrows(AgentCapabilityContractException.class, () -> ExecutionResultValidator.validate(
                result, Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS)));
    }

    @Test
    void 检索零命中_空上下文空引用索引_通过() {
        ExecutionResult result = ExecutionResult.withContext(
                ContextBundle.empty(), CitationIndex.empty(), generation(), FP, null, TRACE);

        assertDoesNotThrow(() -> ExecutionResultValidator.validate(
                result, Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS)));
    }

    @Test
    void 完整上下文型结果_通过() {
        ExecutionResult result = ExecutionResult.withContext(
                new ContextBundle(List.of(new ContextArtifact(1, "片段", "doc.md", 0.9, "VECTOR", null))),
                new CitationIndex(List.of(new Citation(1, "doc.md", "第 3 节"))),
                generation(), FP, new RetrievalStats(10, 1, List.of("VECTOR"), 0.9), TRACE);

        assertDoesNotThrow(() -> ExecutionResultValidator.validate(result, Set.of(
                AgentCapability.STREAMING, AgentCapability.CITATIONS,
                AgentCapability.ANSWER_CACHE, AgentCapability.RETRIEVAL_METRICS)));
    }

    @Test
    void 直出结果缺文本_报错() {
        ExecutionResult result = ExecutionResult.direct("  ", FP, TRACE);

        assertThrows(AgentCapabilityContractException.class,
                () -> ExecutionResultValidator.validate(result, Set.of()));
    }

    @Test
    void 缺指纹_报错() {
        ExecutionResult result = ExecutionResult.direct("文本", null, TRACE);

        assertThrows(AgentCapabilityContractException.class,
                () -> ExecutionResultValidator.validate(result, Set.of()));
    }
}
