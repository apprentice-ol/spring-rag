package com.jjx.customer.platform.delivery.agent.fw;

import com.jjx.customer.platform.agent.framework.model.ModelReply;
import com.jjx.customer.platform.agent.framework.model.ModelRequest;
import com.jjx.customer.platform.agent.framework.model.ModelTurn;
import com.jjx.customer.platform.agent.framework.model.ToolCall;
import com.jjx.customer.platform.agent.framework.model.ToolObservation;
import com.jjx.customer.platform.agent.framework.model.ToolSchema;
import com.jjx.customer.platform.config.llm.NativeToolModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原生 function calling 模型端口：消息序列构造、工具定义投影、手动驱动、toolCalls 解析。
 */
class NativeToolModelPortTest {

    @Test
    void 首轮请求_system加user_工具以定义投影_手动驱动() {
        CapturingModel chatModel = new CapturingModel("ok");
        NativeToolModelPort port = new NativeToolModelPort(chatModel);

        ModelReply reply = port.complete(new ModelRequest("系统", "问题",
                List.of(new ToolSchema("retrieve_knowledge", "检索", "{\"type\":\"object\"}")),
                List.of(), List.of()));

        assertEquals("ok", reply.text());
        Prompt captured = chatModel.lastPrompt;
        assertEquals(2, captured.getInstructions().size());
        // 工具投影进 options 且为手动执行（执行权在框架 ToolRegistry）
        OpenAiChatOptions options = (OpenAiChatOptions) captured.getOptions();
        assertEquals(1, options.getToolCallbacks().size());
        assertEquals("retrieve_knowledge", options.getToolCallbacks().get(0).getToolDefinition().name());
        assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
    }

    @Test
    void 带历史_工具调用与结果按id成对回传() {
        CapturingModel chatModel = new CapturingModel("结论");
        NativeToolModelPort port = new NativeToolModelPort(chatModel);

        port.complete(new ModelRequest("系统", "问题", List.of(), List.of(), List.of(
                ModelTurn.assistantToolCalls(List.of(
                        new ToolCall("query_logs", Map.of("traceId", "abc")),
                        new ToolCall("finish", Map.of("answer", "done")))),
                ModelTurn.toolResults(List.of(
                        new ToolObservation("call_0_1", "query_logs", "日志内容"),
                        new ToolObservation("call_0_2", "finish", "完成"))))));

        List<Message> messages = chatModel.lastPrompt.getInstructions();
        assertEquals(4, messages.size());
        AssistantMessage assistant = (AssistantMessage) messages.get(2);
        assertEquals(2, assistant.getToolCalls().size());
        assertEquals("call_0_1", assistant.getToolCalls().get(0).id());
        assertEquals("query_logs", assistant.getToolCalls().get(0).name());
        ToolResponseMessage toolMessage = (ToolResponseMessage) messages.get(3);
        assertEquals(2, toolMessage.getResponses().size());
        assertEquals("call_0_2", toolMessage.getResponses().get(1).id());
        assertEquals("完成", toolMessage.getResponses().get(1).responseData());
    }

    @Test
    void 模型回工具调用_解析为框架ToolCall() {
        AssistantMessage.ToolCall raw = new AssistantMessage.ToolCall(
                "id-1", "function", "query_logs", "{\"traceId\":\"abc\"}");
        CapturingModel chatModel = new CapturingModel(null, List.of(raw));
        NativeToolModelPort port = new NativeToolModelPort(chatModel);

        ModelReply reply = port.complete(new ModelRequest("s", "u", List.of(), List.of(), List.of()));

        assertTrue(reply.hasToolCalls());
        assertEquals("query_logs", reply.toolCalls().get(0).toolName());
        assertEquals("abc", reply.toolCalls().get(0).args().get("traceId"));
    }

    /** 捕获 Prompt 的 ChatModel 桩。 */
    private static final class CapturingModel implements ChatModel {
        private Prompt lastPrompt;
        private final String text;
        private final List<AssistantMessage.ToolCall> toolCalls;

        CapturingModel(String text) {
            this(text, List.of());
        }

        CapturingModel(String text, List<AssistantMessage.ToolCall> toolCalls) {
            this.text = text;
            this.toolCalls = toolCalls;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            AssistantMessage output = AssistantMessage.builder()
                    .content(text)
                    .toolCalls(toolCalls)
                    .build();
            return new ChatResponse(List.of(new Generation(output, ChatGenerationMetadata.builder().build())));
        }
    }
}
