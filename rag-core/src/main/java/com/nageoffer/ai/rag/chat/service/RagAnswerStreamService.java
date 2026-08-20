package com.nageoffer.ai.rag.chat.service;

import com.nageoffer.ai.rag.config.prompt.PromptStore;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import java.time.Duration;
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

    /** 闲聊流式回答：两个 token 之间超过该时长即判定挂起，报错结束。 */
    private static final Duration CHITCHAT_IDLE_TIMEOUT = Duration.ofSeconds(60);

    /** RAG 流式回答：同上，长回答也按 token 间隔计，不限制总时长。 */
    private static final Duration ANSWER_IDLE_TIMEOUT = Duration.ofSeconds(90);

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
                .content()
                .timeout(CHITCHAT_IDLE_TIMEOUT);
    }

    /**
     * RAG 流式回答：资料 + 问题组装进同一条 user message，按 kb 模板单问题结构用 &lt;question&gt; 标签包裹问题。
     * <p>问题之后紧跟一行引用角标输出指令：贴近生成位置的指令对格式类要求的遵循率远高于仅写在长 system 里
     * （实测 deepseek-chat 只标末尾一处、漏标正文单元，加此后恢复逐段标注）。</p>
     */
    @TelemetryStep(value = "rag.answer", captureOutput = true)
    public Flux<String> answer(String question, String contextText) {
        String systemPrompt = promptStore.raw("chat/pipeline/rag-answer-kb");
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new SimpleLoggerAdvisor());
        return ragChatClient.prompt()
                .system(systemPrompt)
                .user(contextText + "\n\n<question>" + question + "</question>\n\n"
                        + "<output-requirement>回答中每个有资料支撑的段落、列表项、表格单元格末尾都必须标注引用角标 "
                        + "[N](#cite-N)（N=内容来源的 content ref 编号），逐项标注、禁止只在末尾汇总一处；"
                        + "角标紧贴句末最后一个字符。寒暄、过渡句、资料未提及的边界说明不加。</output-requirement>")
                .advisors(advisors)
                .stream()
                .content()
                .timeout(ANSWER_IDLE_TIMEOUT);
    }
}
