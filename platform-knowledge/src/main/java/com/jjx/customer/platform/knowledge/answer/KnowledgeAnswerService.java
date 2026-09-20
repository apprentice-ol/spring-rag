package com.jjx.customer.platform.knowledge.answer;

import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * LLM 流式回答服务：把「闲聊」与「RAG 回答」两路流式 LLM 调用抽成返回 {@link Flux}{@code <String>} 的独立
 * bean 方法，用 {@link TelemetryStep} 注解自动埋点（开 span + 完整回答捕获 + 流终止 finish）。
 *
 * <p>抽独立 bean 的原因：流式方法的 span 生命周期要覆盖整个 Flux 订阅期，只能靠 {@link TelemetryStepAspect}
 * 装饰返回的 Flux；而 Spring AOP 只切跨 bean 调用（同类 {@code this.xxx()} 内部调用不代理），故不能留在
 * {@code ChatOrchestrator} 内部私有方法里。</p>
 *
 * <p>调用方（ChatOrchestrator）拿 Flux 后只写业务副作用（{@code sendEvent}/{@code saveMessage}），
 * 埋点零感知。</p>
 */
@Component
public class KnowledgeAnswerService {

    /** 闲聊流式回答：两个 token 之间超过该时长即判定挂起，报错结束。 */
    private static final Duration CHITCHAT_IDLE_TIMEOUT = Duration.ofSeconds(60);

    /** RAG 流式回答：同上，长回答也按 token 间隔计，不限制总时长。 */
    private static final Duration ANSWER_IDLE_TIMEOUT = Duration.ofSeconds(90);

    private final ChatClient ragChatClient;
    private final PromptStore promptStore;

    public KnowledgeAnswerService(ChatClient ragChatClient, PromptStore promptStore) {
        this.ragChatClient = ragChatClient;
        this.promptStore = promptStore;
    }

    /**
     * 问候/闲聊流式回答（不检索）。
     *
     * <p><b>这条路径是非知识库请求的兜底</b>——「翻译下结果」「总结一下」「换个说法」这类
     * <b>对上一轮回复做变换</b>的请求都会落到这里。不给历史时模型只拿到孤立的一句
     * 「翻译下结果」，只能反问"你想翻译什么"，看着像坏了（实测）。给了历史才能真的翻译上文。</p>
     *
     * @param history 最近若干轮对话历史（空白 = 首轮，输入与加历史之前逐字节相同）
     */
    @TelemetryStep(value = "rag.chitchat", captureOutput = true)
    public Flux<String> chitchat(String question, String history) {
        String systemPrompt = promptStore.raw("rag/pipeline/chitchat-system");
        String userText = history == null || history.isBlank()
                ? question
                : historyBlock(history).strip() + "\n\n" + question;
        // ragChatClient 不挂 ChatMemory advisor——多轮靠显式拼 history（见 ChatClientConfig 的取舍注释），
        // 此前的 CONVERSATION_ID 参数无人消费，是死参数，已删
        return ragChatClient.prompt()
                .system(systemPrompt)
                .user(userText)
                .stream()
                .content()
                .timeout(CHITCHAT_IDLE_TIMEOUT);
    }

    /**
     * RAG 流式回答：资料 + 问题组装进同一条 user message，按 kb 模板单问题结构用 &lt;question&gt; 标签包裹问题。
     * <p>问题之后紧跟一行引用角标输出指令：贴近生成位置的指令对格式类要求的遵循率远高于仅写在长 system 里
     * （实测 deepseek-chat 只标末尾一处、漏标正文单元，加此后恢复逐段标注）。</p>
     *
     * @param history      最近若干轮对话历史（空白 = 首轮，不拼该块——首轮输入与"没有多轮"时逐字节相同）
     * @param systemPrompt kb 回答 system 全文（P2 起由调用方自快照解析——内容随能力包走；
     *                     null 回退 classpath 基线）
     */
    @TelemetryStep(value = "rag.answer", captureOutput = true)
    public Flux<String> answer(String question, String contextText, String history, String systemPrompt) {
        String system = systemPrompt != null ? systemPrompt : promptStore.raw("rag/pipeline/rag-answer-kb");
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new SimpleLoggerAdvisor());
        return ragChatClient.prompt()
                .system(system)
                .user(contextText + historyBlock(history) + "\n\n<question>" + question + "</question>\n\n"
                        + "<output-requirement>回答中每个有资料支撑的段落、列表项、表格单元格末尾都必须标注引用角标 "
                        + "[N](#cite-N)（N=内容来源的 content ref 编号），逐项标注、禁止只在末尾汇总一处；"
                        + "角标紧贴句末最后一个字符。寒暄、过渡句、资料未提及的边界说明不加。</output-requirement>")
                .advisors(advisors)
                .stream()
                .content()
                .timeout(ANSWER_IDLE_TIMEOUT);
    }

    /**
     * 对话历史块（首轮返回空串，输入与"没有多轮"时逐字节相同——多轮是新行为，首轮不该被它带偏）。
     *
     * <p><b>位置必须在 {@code <question>} 之前</b>：末尾的 {@code <output-requirement>} 得留在最后，
     * 角标格式的遵循率靠"指令贴近生成位置"（见 {@link #answer} 上的实测结论），历史插在它后面会把
     * 这条挤离生成位置。</p>
     *
     * <p>历史在 prompt 里的定位（"只用于理解指代、不是事实来源"）由 system 侧
     * （{@code rag-answer-kb} 的「对话历史的使用边界」章）约束——那段边界说明不是可选的：
     * 该 system 的最高约束写着"严禁使用任何外部知识"，而历史严格说就是外部知识，
     * 不划清边界，模型要么无视历史，要么拿历史里的旧结论作答（那些内容没有 ref 编号，标不出角标）。</p>
     */
    private static String historyBlock(String history) {
        if (history == null || history.isBlank()) {
            return "";
        }
        return "\n\n<conversation-history>\n" + history.strip() + "\n</conversation-history>";
    }
}
