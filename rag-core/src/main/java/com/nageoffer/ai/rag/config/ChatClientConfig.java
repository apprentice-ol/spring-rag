package com.nageoffer.ai.rag.config;

import com.nageoffer.ai.rag.config.properties.ChatProperties;
import com.nageoffer.ai.rag.config.properties.VlmProperties;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import com.nageoffer.ai.rag.config.telemetry.LlmTraceAdvisor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * 装配 RAG 专用 ChatClient 与异步执行器。
 *
 * <p>多通道检索引擎需要异步线程池并行执行各通道检索。
 * ragChatClient 挂 MessageChatMemoryAdvisor（会话记忆），
 * 检索由 {@link com.nageoffer.ai.rag.chat.service.pipeline.StreamChatPipeline} 编排。</p>
 *
 * @see com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine
 * @see com.nageoffer.ai.rag.chat.service.pipeline.StreamChatPipeline
 */
@Configuration
public class ChatClientConfig {

    /** 全局默认文本模型（application.yaml spring.ai.openai.chat.options.model），LlmTraceAdvisor 兜底用 */
    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String defaultLlmModel;

    /** LLM 用法埋点开关（LlmTraceAdvisor：模型参数/token 用量 → MDC + 结构化日志）。换 AI 框架时可关。 */
    @Value("${rag.observability.llm.usage-attributes:true}")
    private boolean llmUsageAttributesEnabled;

    @Primary
    @Bean
    public ChatClient ragChatClient(ChatClient.Builder builder,
                                    ChatProperties props,
                                    PromptStore promptStore) {

        // 系统 base 提示词 = answer-chat-system（角色「小码」+ 问题分类 + 闲聊/超范围处理）。
        // 召回后的 KB 回答规则由 StreamChatPipeline 用 answer-chat-kb 显式覆盖 system。
        String systemPrompt = StringUtils.hasText(props.getSystemPrompt())
                ? props.getSystemPrompt()
                : promptStore.raw("chat/pipeline/rag-answer-system");

        // 【单轮阶段】不挂 ChatMemory 顾问：资料拼在 user message（匹配 kb 提示词的输入结构），
        // 若挂 PromptChatMemoryAdvisor 会把含资料的 user message 存进记忆、下轮又折叠回 system，
        // 造成 conversationId=default 串会话 + prompt 膨胀 + 无关历史资料干扰（见 2026-07-30 运行日志）。
        // 多轮阶段恢复记忆时，须同时保证"资料不进 memory"（资料放 system，或改用 RAG 专用无记忆 client）。
        // maxTokens=8192：放宽输出上限，使回答能包含多段代码块 + 对比表 + JSON 示例而不被截断。
        ChatClient.Builder clientBuilder = builder
                .defaultSystem(systemPrompt)
                // 最终 RAG 回答用温度 0.0（贴近 ragent）：严格遵循资料边界与引用规则，输出稳定、确定。
                // 意图分类/查询改写/入库增强走 ingestionChatClient，沿用 yaml 低温度（0.1）保证确定性。
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.1).maxTokens(8192).build());
        // LLM 调用埋点（可配置）：记录模型参数 / token 用量为通用 span/MDC 属性 + 结构化日志
        if (llmUsageAttributesEnabled) {
            clientBuilder = clientBuilder.defaultAdvisors(new LlmTraceAdvisor("rag", defaultLlmModel));
        }
        return clientBuilder.build();
    }

    /**
     * 裸 ChatClient（不带任何 Advisor），给 Enhancer/Enricher/IntentClassifier 纯 LLM 调用用。
     * 走全局 DeepSeek（文本模型）。
     */
    @Bean("ingestionChatClient")
    public ChatClient ingestionChatClient(ChatModel chatModel) {
        // 挂 LlmTraceAdvisor（只读不改 request/response），给 Enhancer/Enricher/意图/诊断的 LLM 调用埋点
        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel);
        if (llmUsageAttributesEnabled) {
            clientBuilder = clientBuilder.defaultAdvisors(new LlmTraceAdvisor("ingestion", defaultLlmModel));
        }
        return clientBuilder.build();
    }

    /**
     * VLM 多模态 ChatClient：百炼 qwen-vl（DeepSeek chat 不支持图片输入，图片描述必须独立模型）。
     * <p>无 Advisor，给 ImageChunker 图片描述用（入库时逐图调 VLM 生成 description，
     * 对齐 ragent ImageDocumentParser 的双文本策略：description 进 embeddingText 参与检索、
     * 原图 URL 留在 content 供展示）。模型参数见 {@code rag.vlm.*}。</p>
     */
    @Bean("vlmChatClient")
    public ChatClient vlmChatClient(VlmProperties props) {
        // Spring AI 1.1.x：OpenAiChatModel 通过 OpenAiApi 构造（builder().openAiApi(...)）
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(props.getModel())
                        .temperature(0.1)
                        .build())
                .build();
        return ChatClient.create(model);
    }

    /**
     * 多通道检索并行执行器。
     * <p>各 SearchChannel 通过此线程池并行执行检索，避免阻塞 SSE 输出线程。</p>
     */
    @Bean("ragContextExecutor")
    public Executor ragContextExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("rag-ctx-");
        // 跨线程传播 MDC + OTel Context（Spring 官方 ContextPropagatingTaskDecorator，accessor 由 ContextPropagationConfig 注册），使通道检索子 span 挂在父 trace 下
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
