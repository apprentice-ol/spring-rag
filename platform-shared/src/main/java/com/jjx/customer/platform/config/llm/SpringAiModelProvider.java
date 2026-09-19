package com.jjx.customer.platform.config.llm;

import com.agentframework.infra.modelgateway.ChatMessage;
import com.agentframework.infra.modelgateway.ChatRole;
import com.agentframework.infra.modelgateway.ModelCallContext;
import com.agentframework.infra.modelgateway.ModelProvider;
import com.agentframework.infra.modelgateway.ModelRequest;
import com.agentframework.infra.modelgateway.ModelResponse;
import com.agentframework.infra.modelgateway.StreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI → 新内核 {@link ModelProvider} 适配器：内核的 LLM 节点（think / replan / 抽槽）
 * 经模型网关统一走宿主已装配的 {@link ChatModel}（DeepSeek OpenAI 兼容端点）。
 *
 * <p>替代旧核心的 {@code NativeToolModelPort}/{@code JsonProtocolModelPort} 双协议端口：
 * 新内核的工具循环固定 JSON 文本协议（prompt 自带协议块），本适配器只搬运消息文本，
 * 不投影工具契约；流式实现把 chunk 逐段回调 {@link StreamHandler}（model.token 事件的源头）。</p>
 */
public class SpringAiModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringAiModelProvider.class);

    /** 提供方标识（AgentDefinition.model 的 provider 值）。 */
    public static final String PROVIDER_ID = "spring-ai";

    private final ChatModel chatModel;

    /**
     * @param chatModel 宿主自动装配的对话模型（DeepSeek）
     */
    public SpringAiModelProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<String> models() {
        return List.of();
    }

    @Override
    public ModelResponse complete(ModelRequest request, ModelCallContext context) {
        try {
            String text = chatModel.call(new Prompt(toSpringMessages(request)))
                    .getResult().getOutput().getText();
            return ModelResponse.text(text == null ? "" : text);
        } catch (Exception e) {
            log.warn("[engine-model] 模型调用失败：{}", e.getMessage());
            throw new IllegalStateException("模型调用失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelCallContext context, StreamHandler handler) {
        StringBuilder full = new StringBuilder();
        try {
            chatModel.stream(new Prompt(toSpringMessages(request))).toStream().forEach(chunk -> {
                String piece = chunk.getResult() == null || chunk.getResult().getOutput() == null
                        ? null : chunk.getResult().getOutput().getText();
                if (piece != null && !piece.isEmpty()) {
                    full.append(piece);
                    if (handler != null) {
                        handler.onToken(piece);
                    }
                }
            });
            return ModelResponse.text(full.toString());
        } catch (Exception e) {
            log.warn("[engine-model] 模型流式调用失败：{}", e.getMessage());
            throw new IllegalStateException("模型调用失败：" + e.getMessage(), e);
        }
    }

    private List<Message> toSpringMessages(ModelRequest request) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            String content = message.content() == null ? "" : message.content();
            if (message.role() == ChatRole.SYSTEM) {
                messages.add(new SystemMessage(content));
            } else if (message.role() == ChatRole.USER) {
                messages.add(new UserMessage(content));
            } else {
                messages.add(new AssistantMessage(content));
            }
        }
        return messages;
    }
}
