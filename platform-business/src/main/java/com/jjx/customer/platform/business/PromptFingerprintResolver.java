package com.jjx.customer.platform.business;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshot;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.agent.AgentWorkflowBindingResolver;
import com.jjx.customer.platform.agent.framework.agent.AgentRegistry;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshotSource;
import com.jjx.customer.platform.agent.framework.workflow.InMemoryWorkflowCatalog;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 指纹解析器：按范式（= Agent id）解析三层快照的内容 hash——
 * 缓存 key 的先决信息（不变量 5/9：指纹先于流式与缓存读写）。
 *
 * <p>引擎内每次执行会再装配一次同样的快照（hash 相同，纯内存计算）；
 * 管线侧提前解析是为了在<b>执行前</b>组缓存 key——命中即整链免执行。</p>
 */
@Slf4j
@Component
public class PromptFingerprintResolver {

    /** 解析失败的回退值（key 拼段稳定，缓存语义退化为不含指纹的旧口径）。 */
    public static final String UNKNOWN = "no-prompt-fingerprint";

    private final AgentRegistry agentRegistry;
    private final AgentWorkflowBindingResolver bindingResolver;
    private final WorkflowCatalog workflowCatalog;
    private final PromptSnapshotSource snapshotSource;

    public PromptFingerprintResolver(AgentRegistry agentRegistry,
                                     AgentWorkflowBindingResolver bindingResolver,
                                     List<Workflow> workflows,
                                     PromptSnapshotSource snapshotSource) {
        this.agentRegistry = agentRegistry;
        this.bindingResolver = bindingResolver;
        this.workflowCatalog = new InMemoryWorkflowCatalog(workflows);
        this.snapshotSource = snapshotSource;
    }

    /** 范式 → prompt 内容 hash（改任一层 prompt 自动变 ⇒ 缓存自动失效）。 */
    public String promptHash(String paradigm) {
        try {
            Agent agent = agentRegistry.byId(paradigm).orElse(null);
            if (agent == null) {
                return UNKNOWN;
            }
            Workflow workflow = workflowCatalog.byId(bindingResolver.workflowIdFor(agent))
                    .orElse(null);
            if (workflow == null) {
                return UNKNOWN;
            }
            PromptSnapshot snapshot = snapshotSource.snapshotFor(agent, workflow);
            return snapshot.contentHash() == null || snapshot.contentHash().isBlank()
                    ? UNKNOWN : snapshot.contentHash();
        } catch (Exception e) {
            log.warn("[指纹解析] 失败回退 {}: {}", UNKNOWN, e.getMessage());
            return UNKNOWN;
        }
    }
}
