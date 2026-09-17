package com.jjx.customer.platform.prompt.snapshot;


import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.prompt.PromptAssembler;
import com.jjx.customer.platform.agent.framework.prompt.PromptLayer;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshot;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshotSource;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.prompt.service.PromptBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 快照来源（业务实现）：把生效 prompt 按"链路 → Agent → Workflow"三层组装成快照。
 *
 * <p>三层是<b>补充增强</b>（相加，不覆盖）；快照内容 hash 进执行指纹 ⇒ 改任一层 prompt，
 * 答案缓存自动失效。</p>
 *
 * <p><b>内容来源（绑定接线）</b>：该 agent 绑定了能力包时（基座+特化 merge），key 命中包内容则
 * 包版本胜出，未命中回退 classpath——DB 版本管理 / 能力包组包对运行链路真实生效；
 * 无绑定 agent 取到空覆盖，行为与纯 classpath 完全一致。发布新 release 后下一请求即生效，
 * 指纹 hash 随内容变化自动失效相关缓存。</p>
 *
 * <p>过渡说明：现有 prompt key 仍是历史命名（{@code chat/pipeline/*}），故 Workflow 层暂不做
 * 前缀校验；B5 后续把 key 迁到 {@code workflow/{workflowId}/*} 命名空间后开校验。</p>
 */
@Component
@RequiredArgsConstructor
public class PromptStoreSnapshotSource implements PromptSnapshotSource {

    /** 链路级常驻 key（输出契约/安全类，跨能力共享）。 */
    /** 链路级常驻 system prompt key（跨域契约：内容由 Prompt 域拥有，业务侧按同名 key 引用）。 */
    private static final String LINK_BASE_SYSTEM_PROMPT = "chat/pipeline/rag-answer-system";
    private static final List<String> LINK_KEYS = List.of(LINK_BASE_SYSTEM_PROMPT);

    private final PromptStore promptStore;
    private final PromptBindingService bindingService;

    @Override
    public PromptSnapshot snapshotFor(Agent agent, Workflow workflow) {
        Map<String, String> overrides = bindingService.overridesFor(agent.id());
        List<PromptLayer> layers = new ArrayList<>();
        layers.add(PromptLayer.of("link", "chat/", contents(LINK_KEYS, overrides)));
        if (!agent.promptKeys().isEmpty()) {
            layers.add(new PromptLayer("agent:" + agent.id(), null, null, contents(agent.promptKeys(), overrides)));
        }
        List<String> workflowKeys = new ArrayList<>(workflow.promptKeys());
        if (workflow.answerPromptKey() != null) {
            workflowKeys.add(workflow.answerPromptKey());
        }
        if (!workflowKeys.isEmpty()) {
            layers.add(PromptLayer.of("workflow:" + workflow.id(), null, contents(workflowKeys, overrides)));
        }
        return PromptAssembler.assemble(layers);
    }

    /** key → 生效内容：绑定包覆盖优先，未命中回退 classpath。 */
    private Map<String, String> contents(List<String> keys, Map<String, String> overrides) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            String override = overrides.get(key);
            out.put(key, override != null ? override : promptStore.raw(key));
        }
        return out;
    }
}
