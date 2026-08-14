package com.nageoffer.ai.rag.chat.intent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.nageoffer.ai.obs.observation.annotation.ObservedStep;

/**
 * 基于 LLM 的意图分类器实现。
 * <p>
 * 使用 Spring AI ChatClient（ingestionChatClient，裸调用不带 Advisor）对用户问题
 * 进行意图分类。输出格式为 JSON，包含 intent、confidence、needsRetrieval 等字段。
 * </p>
 *
 * <p>分类 prompt 严格约束输出格式，temperature 设为 0 确保确定性。</p>
 */
@Slf4j
@Service
public class DefaultIntentClassifier implements IntentClassifier {

    private final ChatClient ingestionChatClient;
    private final Gson gson;
    private final PromptStore promptStore;

    public DefaultIntentClassifier(@Qualifier("ingestionChatClient") ChatClient ingestionChatClient,
                                    Gson gson,
                                    PromptStore promptStore) {
        this.ingestionChatClient = ingestionChatClient;
        this.gson = gson;
        this.promptStore = promptStore;
    }

    @Override
    @ObservedStep("rag.intent.classify")
    public IntentResult classify(String question) {
        try {
            // system/user 均不传 param → 不走 StringTemplate，prompt 中的 JSON 花括号原样发送
            String systemText = promptStore.raw("chat/intent/classify-system");
            String userText = promptStore.raw("chat/intent/classify-user") + "\n\n用户问题：" + question;
            String response = ingestionChatClient.prompt()
                    .system(systemText)
                    .user(userText)
                    .call()
                    .content();

            log.info("[意图分类] LLM 原始响应: {}", response);
            return parseResponse(question, response);
        } catch (Exception e) {
            log.warn("[意图分类] 分类异常, 默认走 knowledge_query: {}", e.getMessage());
            return IntentResult.builder()
                    .intent("knowledge_query")
                    .confidence(0.5)
                    .needsRetrieval(true)
                    .needsWebSearch(false)
                    .reason("分类异常，默认走知识库查询")
                    .build();
        }
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
            String intent = obj.get("intent").getAsString();
            double confidence = obj.has("confidence") ? obj.get("confidence").getAsDouble() : 0.5;
            boolean needsRetrieval = obj.has("needs_retrieval") && obj.get("needs_retrieval").getAsBoolean();
            boolean needsWebSearch = obj.has("needs_web_search") && obj.get("needs_web_search").getAsBoolean();
            boolean needsDiagnose = obj.has("needs_diagnose") && obj.get("needs_diagnose").getAsBoolean();
            String reason = obj.has("reason") ? obj.get("reason").getAsString() : "";

            log.info("[意图分类] 分类结果: intent={}, confidence={}, needsRetrieval={}, needsDiagnose={}, reason={}",
                    intent, confidence, needsRetrieval, needsDiagnose, reason);

            return IntentResult.builder()
                    .intent(intent)
                    .confidence(confidence)
                    .needsRetrieval(needsRetrieval)
                    .needsWebSearch(needsWebSearch)
                    .needsDiagnose(needsDiagnose)
                    .reason(reason)
                    .build();
        } catch (Exception e) {
            log.warn("[意图分类] 解析响应异常: {}, raw={}", e.getMessage(), response);
            return IntentResult.builder()
                    .intent("knowledge_query")
                    .confidence(0.5)
                    .needsRetrieval(true)
                    .needsWebSearch(false)
                    .reason("解析异常，默认走知识库查询")
                    .build();
        }
    }
}
