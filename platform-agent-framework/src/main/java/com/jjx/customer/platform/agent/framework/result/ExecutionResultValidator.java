package com.jjx.customer.platform.agent.framework.result;

import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.capability.AgentCapabilityContractException;

import java.util.Set;

/**
 * 执行结果与生效能力的契约校验：声明了某能力却没产出对应元数据即失败（不静默降级）。
 */
public final class ExecutionResultValidator {

    private ExecutionResultValidator() {
    }

    public static void validate(ExecutionResult result, Set<AgentCapability> effectiveCapabilities) {
        Set<AgentCapability> effective = effectiveCapabilities == null ? Set.of() : effectiveCapabilities;

        if (result.fingerprint() == null) {
            throw new AgentCapabilityContractException("执行指纹缺失：每次执行都必须带 agent+workflow+promptHash");
        }
        if (effective.contains(AgentCapability.STREAMING) && result.generation() == null) {
            throw new AgentCapabilityContractException("声明了 STREAMING 但未产出 GenerationSpec");
        }
        // 引用契约：有上下文（检索到片段）却没装配引用索引 = 装配漏了；
        // 上下文为空（检索 0 命中）时索引为空是合法产出，不算违约。
        if (effective.contains(AgentCapability.CITATIONS)
                && !result.context().artifacts().isEmpty()
                && (result.citations() == null || result.citations().isEmpty())) {
            throw new AgentCapabilityContractException(
                    "声明了 CITATIONS 且上下文非空，但未产出 CitationIndex（引用装配缺失）");
        }
        if (effective.contains(AgentCapability.RETRIEVAL_METRICS) && result.retrievalStats() == null) {
            throw new AgentCapabilityContractException("声明了 RETRIEVAL_METRICS 但未产出 RetrievalStats");
        }
        if ((effective.contains(AgentCapability.ANSWER_CACHE) || effective.contains(AgentCapability.SEMANTIC_CACHE))
                && result.fingerprint().promptHash() == null) {
            throw new AgentCapabilityContractException("声明了答案缓存能力但指纹缺少 promptHash——缓存无法随 prompt 失效");
        }
        if (result.kind() == OutcomeKind.DIRECT && (result.text() == null || result.text().isBlank())) {
            throw new AgentCapabilityContractException("DIRECT 结果必须带非空文本");
        }
    }
}
