package com.jjx.customer.platform.prompt.snapshot;

import com.agentframework.engine.promptmanager.Prompt;
import com.agentframework.engine.promptmanager.PromptProvider;
import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.prompt.service.PromptBindingService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 新内核 {@link PromptProvider} 的业务实现（替代旧 {@code PromptStoreSnapshotSource} 的内容来源半边）。
 *
 * <p>内容解析与旧三层快照同口径：<b>绑定包覆盖优先，未命中回退 classpath</b>——
 * DB 版本管理 / 能力包组包对引擎运行链路真实生效，发布新 release 后下一请求即生效。</p>
 *
 * <p>解析顺序：装配期动态模板（stages 在 wireRuntime 阶段组合的「正文 + 工具协议块」，
 * 如 {@code workflow/ops_diagnose_v2/investigate}）→ 绑定包覆盖 → classpath 基线。
 * 动态层保证协议块（代码拼装部分）始终最新，同时正文可被 DB 绑定覆盖。</p>
 *
 * <p>「key 属于哪个 agent」由装配方（platform-business）从 {@code AgentCatalog} 构造
 * {@code keyOwnerAgent} 映射注入——本模块不反向依赖 business。未登记的 key
 * （如链路级 key）直接走 classpath，行为与旧基线一致。</p>
 */
public class PromptStorePromptProvider implements PromptProvider {

    private static final Logger log = LoggerFactory.getLogger(PromptStorePromptProvider.class);

    private final PromptStore promptStore;
    private final PromptBindingService bindingService;
    private final Map<String, String> keyOwnerAgent;

    /** 装配期动态模板（stages wireRuntime 填充；key → 组合后完整模板）。 */
    private final Map<String, String> runtimeTemplates = new ConcurrentHashMap<>();

    /**
     * @param promptStore   classpath prompt 加载器（开发态默认来源）
     * @param bindingService 绑定解析（基座+特化 merge 的生效覆盖）
     * @param keyOwnerAgent prompt key → agentType 归属（决定覆盖从哪个绑定解析）
     */
    public PromptStorePromptProvider(PromptStore promptStore,
                                     PromptBindingService bindingService,
                                     Map<String, String> keyOwnerAgent) {
        this.promptStore = promptStore;
        this.bindingService = bindingService;
        this.keyOwnerAgent = keyOwnerAgent == null ? Map.of() : keyOwnerAgent;
    }

    /**
     * 注册装配期组合模板（正文 + 工具协议块），优先级最高。
     *
     * @param id       prompt 资产 id（= LLM 节点 promptRef）
     * @param template 组合后的完整模板
     */
    public void registerRuntimeTemplate(String id, String template) {
        runtimeTemplates.put(id, template);
    }

    @Override
    public Prompt get(String id, String version) {
        String composed = runtimeTemplates.get(id);
        if (composed != null) {
            return Prompt.of(id, version == null ? "latest" : version, composed);
        }
        String agentType = keyOwnerAgent.get(id);
        String content = null;
        if (agentType != null) {
            Map<String, String> overrides = bindingService.overridesFor(agentType);
            content = overrides.get(id);
            if (content != null) {
                log.debug("[prompt-provider] 绑定包覆盖生效: {} (agent={})", id, agentType);
            }
        }
        if (content == null) {
            content = promptStore.raw(id);
        }
        return Prompt.of(id, version == null ? "latest" : version, content);
    }
}
