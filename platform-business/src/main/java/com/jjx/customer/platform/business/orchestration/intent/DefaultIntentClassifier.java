package com.jjx.customer.platform.business.orchestration.intent;
import com.jjx.customer.platform.intent.IntentResult;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.jjx.customer.platform.routing.RouteRegistry;
import com.jjx.customer.platform.common.util.LlmCallGuard;
import com.jjx.customer.platform.config.prompt.PromptStore;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;

/**
 * 基于 LLM 的意图分类器实现（2026-09-12 框架化批次 2 去域感知改造）。
 *
 * <p>输出结构只含通用字段（domain/confidence/needs_web_search/reason）；
 * 域清单 = prompt 文件里的基础域 + {@link RouteRegistry#domainDescriptors()} 动态拼装的
 * workflow 域——新增 workflow 域自动进入分类器视野，本类与 prompt 文件零改动。</p>
 */
@Slf4j
@Service
public class DefaultIntentClassifier implements IntentClassifier {

    private static final Duration CLASSIFY_TIMEOUT = Duration.ofSeconds(30);

    private final ChatClient ingestionChatClient;
    private final Gson gson;
    private final PromptStore promptStore;
    private final RouteRegistry routeRegistry;

    public DefaultIntentClassifier(@Qualifier("ingestionChatClient") ChatClient ingestionChatClient,
                                    Gson gson,
                                    PromptStore promptStore,
                                    RouteRegistry routeRegistry) {
        this.ingestionChatClient = ingestionChatClient;
        this.gson = gson;
        this.promptStore = promptStore;
        this.routeRegistry = routeRegistry;
    }

    @Override
    @TelemetryStep("rag.intent.classify")
    public IntentResult classify(String question) {
        try {
            // system/user 均不传 param → 不走 StringTemplate，prompt 中的 JSON 花括号原样发送
            String systemText = promptStore.raw("chat/intent/classify-system");
            String userText = promptStore.raw("chat/intent/classify-user")
                    + dynamicDomainSection()
                    + "\n\n用户问题：" + question;
            String response = LlmCallGuard.call(() -> ingestionChatClient.prompt()
                    .system(systemText)
                    .user(userText)
                    .call()
                    .content(), CLASSIFY_TIMEOUT, "意图分类");

            log.info("[意图分类] LLM 原始响应: {}", response);
            return parseResponse(question, response);
        } catch (Exception e) {
            log.warn("[意图分类] 分类异常, 默认走知识检索: {}", e.getMessage());
            return fallback("分类异常，默认走知识检索");
        }
    }

    /** 动态域清单段：各 workflow 域 RouteRule 注册的描述，拼进分类 prompt（无注册域则空段）。 */
    private String dynamicDomainSection() {
        List<String> descriptors = routeRegistry.domainDescriptors();
        if (descriptors.isEmpty()) {
            return "";
        }
        return "\n\n## 动态能力域（与上述基础域并列可选）\n" + String.join("\n", descriptors) + "\n";
    }

    private IntentResult fallback(String reason) {
        return IntentResult.builder()
                .domain(IntentResult.DOMAIN_KNOWLEDGE)
                .confidence(0.5)
                .needsWebSearch(false)
                .reason(reason)
                .build();
    }

    private IntentResult parseResponse(String question, String response) {
        try {
            // 尝试提取 JSON（模型可能返回 markdown 包裹）
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7, json.lastIndexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3, json.lastIndexOf("```"));
            }
            json = json.trim();

            JsonObject obj = gson.fromJson(json, JsonObject.class);
            String domain = obj.has("domain") ? obj.get("domain").getAsString()
                    : IntentResult.DOMAIN_KNOWLEDGE;
            double confidence = obj.has("confidence") ? obj.get("confidence").getAsDouble() : 0.5;
            boolean needsWebSearch = obj.has("needs_web_search") && obj.get("needs_web_search").getAsBoolean();
            String reason = obj.has("reason") ? obj.get("reason").getAsString() : "";

            log.info("[意图分类] 分类结果: domain={}, confidence={}, needsWebSearch={}, reason={}",
                    domain, confidence, needsWebSearch, reason);

            return IntentResult.builder()
                    .domain(domain)
                    .confidence(confidence)
                    .needsWebSearch(needsWebSearch)
                    .reason(reason)
                    .build();
        } catch (Exception e) {
            log.warn("[意图分类] 解析响应异常: {}, raw={}", e.getMessage(), response);
            return fallback("解析异常，默认走知识检索");
        }
    }
}
