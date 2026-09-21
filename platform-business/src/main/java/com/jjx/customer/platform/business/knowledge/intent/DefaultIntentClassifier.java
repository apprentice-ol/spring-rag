package com.jjx.customer.platform.business.knowledge.intent;
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
import org.springframework.ai.chat.prompt.ChatOptions;
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
        return classify(question, "");
    }

    @Override
    @TelemetryStep("rag.intent.classify")
    public IntentResult classify(String question, String recentHistory) {
        try {
            // system/user 均不传 param → 不走 StringTemplate，prompt 中的 JSON 花括号原样发送
            String systemText = promptStore.raw("rag/intent/classify-system");
            String userText = promptStore.raw("rag/intent/classify-user")
                    + dynamicDomainSection()
                    + historySection(recentHistory)
                    + "\n\n用户问题：" + question;
            String response = LlmCallGuard.call(() -> ingestionChatClient.prompt()
                    .system(systemText)
                    .user(userText)
                    // 分类要确定性：ingestionChatClient 是共享 bean（默认温度非零），
                    // 同一问题 4 次能判出 2 次 knowledge / 2 次 chitchat（2026-09-20 实测），
                    // 意图抖动直接放大成路由抖动。按调用点覆盖，不动共享 bean。
                    .options(ChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content(), CLASSIFY_TIMEOUT, "意图分类");

            log.info("[意图分类] LLM 原始响应: {}", response);
            return parseResponse(question, response);
        } catch (Exception e) {
            log.warn("[意图分类] 分类异常, 默认走知识检索: {}", e.getMessage());
            return fallback("分类异常，默认走知识检索");
        }
    }

    /**
     * 最近对话段：多轮追问常是裸关键词，脱离历史只能按字面猜意图。
     *
     * <p>截尾部（最近一轮最能说明话题）；标注"仅供参考话题延续"，防止分类器
     * 把历史里的问题当成当前要分类的问题。</p>
     */
    private static String historySection(String recentHistory) {
        if (recentHistory == null || recentHistory.isBlank()) {
            return "";
        }
        String trimmed = recentHistory.strip();
        String tail = trimmed.length() > 600 ? trimmed.substring(trimmed.length() - 600) : trimmed;
        return "\n\n## 最近对话（仅供判断话题延续，请对最末的「用户问题」归类）\n" + tail + "\n";
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
