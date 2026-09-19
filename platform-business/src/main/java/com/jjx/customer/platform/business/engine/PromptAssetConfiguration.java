package com.jjx.customer.platform.business.engine;

import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;
import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.prompt.mapper.PromptBindingMapper;
import com.jjx.customer.platform.prompt.mapper.PromptBundleMapper;
import com.jjx.customer.platform.prompt.mapper.PromptBundleReleaseMapper;
import com.jjx.customer.platform.prompt.mapper.PromptMapper;
import com.jjx.customer.platform.prompt.mapper.PromptVersionMapper;
import com.jjx.customer.platform.prompt.service.PromptBindingService;
import com.jjx.customer.platform.prompt.snapshot.PromptStorePromptProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 资产管道装配：{@link PromptStorePromptProvider}（绑定包覆盖优先，回退 classpath）
 * + 绑定解析服务。key → agent 归属由 {@link AgentCatalog} 推导；解析顺序 =
 * 装配期组合模板（think 正文 + 工具协议块，stages wireRuntime 注册）→ 绑定包覆盖 → classpath 基线。
 */
@Configuration
public class PromptAssetConfiguration {

    /**
     * Prompt 绑定解析服务：requiredKeys 清单由 {@link AgentCatalog} 推导注入
     * （platform-prompt 不反向依赖 business）。
     */
    @Bean
    public PromptBindingService promptBindingService(
            PromptBindingMapper bindingMapper,
            PromptBundleMapper bundleMapper,
            PromptBundleReleaseMapper releaseMapper,
            PromptMapper promptMapper,
            PromptVersionMapper versionMapper,
            PromptStore promptStore, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        Map<String, List<String>> requiredKeys = new LinkedHashMap<>();
        for (AgentCatalog.Entry entry : AgentCatalog.all()) {
            requiredKeys.put(entry.id(), entry.allPromptKeys());
        }
        return new PromptBindingService(bindingMapper, bundleMapper, releaseMapper,
                promptMapper, versionMapper, promptStore, objectMapper, requiredKeys);
    }

    /**
     * Prompt 资产来源：key → agent 归属由 {@link AgentCatalog} 推导；
     * 解析顺序 = 装配期组合模板 → 绑定包覆盖 → classpath 基线。
     */
    @Bean
    public PromptStorePromptProvider agentPromptProvider(PromptStore promptStore,
                                                         PromptBindingService bindingService) {
        Map<String, String> keyOwner = new LinkedHashMap<>();
        for (AgentCatalog.Entry entry : AgentCatalog.all()) {
            // 人格层 / 任务层 key 不允许跨 agent 重复（命名空间红线，拼错即装配期失败）
            for (String key : entry.agentPromptKeys()) {
                putStrict(keyOwner, key, entry.id());
            }
            for (String key : entry.workflowPromptKeys()) {
                putStrict(keyOwner, key, entry.id());
            }
            // answerPromptKey 允许共享（react_loop 复用 knowledge 的生成段 key，旧架构同语义）：
            // 归属首个声明者；该 key 生成段在引擎外，绑定覆盖按各自 agent 的指纹路径独立解析
            if (entry.answerPromptKey() != null) {
                keyOwner.putIfAbsent(entry.answerPromptKey(), entry.id());
            }
        }
        // ops think 的 workflow 层资产（组合模板的正文来源）也归属 ops agent
        for (String asset : List.of(OpsDiagnoseWorkflowFactory.INVESTIGATE_PROMPT,
                OpsDiagnoseWorkflowFactory.RESOLVE_PROMPT, OpsDiagnoseWorkflowFactory.VERIFY_PROMPT)) {
            keyOwner.putIfAbsent(asset, AgentCatalog.OPS.id());
        }
        return new PromptStorePromptProvider(promptStore, bindingService, keyOwner);
    }

    /** 命名空间红线：同 key 被两个 agent 的人格/任务层声明即装配期失败。 */
    private static void putStrict(Map<String, String> keyOwner, String key, String agentId) {
        String previous = keyOwner.putIfAbsent(key, agentId);
        if (previous != null && !previous.equals(agentId)) {
            throw new IllegalStateException("Prompt key 跨 agent 重复: " + key
                    + "（" + previous + " 与 " + agentId + "）——人格/任务层 key 须各归各的命名空间");
        }
    }
}
