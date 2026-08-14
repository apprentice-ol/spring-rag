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
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

/**
 * 装配 RAG 专用 ChatClient 与异步执行器。
 *
 * <p><b>LLM 埋点不在此处理</b>——Spring AI 每次调用自动建 ChatModel observation，
 * gen_ai.* 内容与 token 用量由 Spring AI 原生输出；会话/pipeline 关联由 obs-telemetry 的
 * {@link com.nageoffer.ai.obs.springai.SpringAiConversationCorrelationFilter} 挂到原生 span。
 * 本类只装配 ChatClient，不挂任何 Advisor。</p>
 *
 * @see com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine
 * @see com.nageoffer.ai.rag.chat.service.pipeline.StreamChatPipeline
 */
@Configuration
public class ChatClientConfig {

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
        return builder
                .defaultSystem(systemPrompt)
                // 最终 RAG 回答用温度 0.0（贴近 ragent）：严格遵循资料边界与引用规则，输出稳定、确定。
                // 意图分类/查询改写/入库增强走 ingestionChatClient，沿用 yaml 低温度（0.1）保证确定性。
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.1).maxTokens(8192).build())
                .build();
    }

    /**
     * 裸 ChatClient（不带任何 Advisor），给 Enhancer/Enricher/IntentClassifier 纯 LLM 调用用。
     * 走全局 DeepSeek（文本模型）。
     */
    @Bean("ingestionChatClient")
    public ChatClient ingestionChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
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
        // 跨线程传播 MDC + OTel Context（Spring 官方 ContextPropagatingTaskDecorator，accessor 由 obs ContextPropagationConfig 注册），使通道检索子 span 挂在父 trace 下
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
