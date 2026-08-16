package com.nageoffer.ai.rag.chat.service;

import com.nageoffer.ai.rag.config.prompt.PromptStore;
import com.nageoffer.ai.llmobservability.observation.annotation.TelemetryStep;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * LLM 流式回答服务：把「闲聊」与「RAG 回答」两路流式 LLM 调用抽成返回 {@link Flux}{@code <String>} 的独立
 * bean 方法，用 {@link TelemetryStep} 注解自动埋点（开 span + 完整回答捕获 + 流终止 finish）。
 *
 * <p>抽独立 bean 的原因：流式方法的 span 生命周期要覆盖整个 Flux 订阅期，只能靠 {@link TelemetryStepAspect}
 * 装饰返回的 Flux；而 Spring AOP 只切跨 bean 调用（同类 {@code this.xxx()} 内部调用不代理），故不能留在
 * {@code StreamChatPipeline} 内部私有方法里。</p>
 *
 * <p>调用方（StreamChatPipeline）拿 Flux 后只写业务副作用（{@code sendEvent}/{@code saveMessage}），
 * 埋点零感知。</p>
 */
@Component
public class RagAnswerStreamService {

    private final ChatClient ragChatClient;
    private final PromptStore promptStore;

    public RagAnswerStreamService(ChatClient ragChatClient, PromptStore promptStore) {
        this.ragChatClient = ragChatClient;
        this.promptStore = promptStore;
    }

    /** 问候/闲聊流式回答（不检索）。 */
    @TelemetryStep(value = "rag.chitchat", captureOutput = true)
    public Flux<String> chitchat(String question, String conversationId) {
        String systemPrompt = promptStore.raw("chat/pipeline/chitchat-system");
        return ragChatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    /** RAG 流式回答：资料 + 问题组装进同一条 user message，按 kb 模板单问题结构用 &lt;question&gt; 标签包裹问题。 */
    @TelemetryStep(value = "rag.answer", captureOutput = true)
    public Flux<String> answer(String question, String contextText) {
        String systemPrompt = promptStore.raw("chat/pipeline/rag-answer-kb");
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new SimpleLoggerAdvisor());
        return ragChatClient.prompt()
                .system(systemPrompt)
                .user(contextText + "\n\n<question>" + question + "</question>")
                .advisors(advisors)
                .stream()
                .content();
    }
}
