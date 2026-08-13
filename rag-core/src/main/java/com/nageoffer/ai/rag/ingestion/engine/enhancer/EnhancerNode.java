package com.nageoffer.ai.rag.ingestion.engine.enhancer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.ingestion.engine.IngestionContext;
import com.nageoffer.ai.rag.ingestion.engine.enums.EnhanceType;
import com.nageoffer.ai.rag.ingestion.engine.enums.IngestionNodeType;
import com.nageoffer.ai.rag.ingestion.engine.IngestionNode;
import com.nageoffer.ai.rag.ingestion.engine.NodeConfig;
import com.nageoffer.ai.rag.ingestion.engine.NodeResult;
import com.nageoffer.ai.rag.common.util.JsonResponseParser;
import com.nageoffer.ai.rag.common.util.PromptTemplateRenderer;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 文档级增强节点（搬原 ragent，LLMService→ingestionChatClient）。
 *
 * <p>4 任务：CONTEXT_ENHANCE（整理文本→enhancedText）/ KEYWORDS（→keywords）/ QUESTIONS（→questions）/ METADATA（→metadata）。
 */
@Slf4j
@Component
public class EnhancerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;
    private final PromptStore promptStore;

    /** 各增强任务类型 → 资源文件中 system prompt 的 key（DB 配置未覆盖时回退到此）。 */
    private static final Map<EnhanceType, String> SYSTEM_PROMPT_KEYS = Map.of(
            EnhanceType.CONTEXT_ENHANCE, "ingestion/enhancer/context-enhance",
            EnhanceType.KEYWORDS, "ingestion/enhancer/keywords",
            EnhanceType.QUESTIONS, "ingestion/enhancer/questions",
            EnhanceType.METADATA, "ingestion/enhancer/metadata");

    /** [暂时屏蔽] 设为 false 跳过 Enhancer 整篇增强，保持 chunk content 为原始解析内容（标题等只进 metadata）。恢复改 true */
    private static final boolean ENHANCER_ENABLED = false;

    public EnhancerNode(ObjectMapper objectMapper,
                        @Qualifier("ingestionChatClient") ChatClient chatClient,
                        PromptStore promptStore) {
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
        this.promptStore = promptStore;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENHANCER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // [暂时屏蔽] 跳过 LLM 整篇增强，保持 chunk content 为原始解析内容（标题等结构信息只进
        // metadata.outline_path，不写进 content）。恢复时把 ENHANCER_ENABLED 改回 true
        if (!ENHANCER_ENABLED) {
            return NodeResult.ok("Enhancer 已暂时屏蔽，跳过增强");
        }
        EnhancerSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("未配置增强任务");
        }
        if (context.getMetadata() == null) {
            context.setMetadata(new HashMap<>());
        }
        for (EnhancerSettings.EnhanceTask task : settings.getTasks()) {
            if (task == null || task.getType() == null) {
                continue;
            }
            EnhanceType type = task.getType();
            String input = resolveInputText(context, type);
            if (!StringUtils.hasText(input)) {
                continue;
            }
            String key = SYSTEM_PROMPT_KEYS.get(type);
            // PDF 文档的上下文增强走专用的格式修复提示词（pdf-format-guard），修复 PDF 解析的换行/表格/页眉等问题
            if (type == EnhanceType.CONTEXT_ENHANCE
                    && "application/pdf".equalsIgnoreCase(context.getMimeType())) {
                key = "ingestion/pdf-format-guard";
            }
            String systemPrompt = promptStore.rawOrOverride(key, task.getSystemPrompt());
            String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), input, context);
            String response = chat(systemPrompt, userPrompt);

            log.info("enhancer response: {}", response);
            applyTaskResult(context, type, response);
        }
        return NodeResult.ok("增强完成");
    }

    private EnhancerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnhancerSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnhancerSettings.class);
    }

    private String resolveInputText(IngestionContext context, EnhanceType type) {
        if (type == EnhanceType.CONTEXT_ENHANCE) {
            return context.getRawText();
        }
        if (StringUtils.hasText(context.getEnhancedText())) {
            return context.getEnhancedText();
        }
        return context.getRawText();
    }

    private String buildUserPrompt(String template, String input, IngestionContext context) {
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("mimeType", context.getMimeType());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    private String chat(String systemPrompt, String userPrompt) {
        return chatClient.prompt()
                .system(systemPrompt == null ? "" : systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    private void applyTaskResult(IngestionContext context, EnhanceType type, String response) {
        switch (type) {
            case CONTEXT_ENHANCE -> context.setEnhancedText(
                    StringUtils.hasText(response) ? response.trim() : response);
            case KEYWORDS -> context.setKeywords(JsonResponseParser.parseStringList(response));
            case QUESTIONS -> context.setQuestions(JsonResponseParser.parseStringList(response));
            case METADATA -> context.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }
}
