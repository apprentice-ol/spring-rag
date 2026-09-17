package com.jjx.customer.platform.config.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.agent.framework.model.ModelPort;
import com.jjx.customer.platform.agent.framework.model.ModelReply;
import com.jjx.customer.platform.agent.framework.model.ModelRequest;
import com.jjx.customer.platform.agent.framework.model.ModelTurn;
import com.jjx.customer.platform.agent.framework.model.ToolCall;
import com.jjx.customer.platform.agent.framework.model.ToolSchema;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原生 function calling 的模型端口（R1 目标形态）：框架工具 schema → 模型工具定义 →
 * 结构化回传（assistant tool_calls / tool result 消息序列），工具执行权仍在框架
 * （{@code internalToolExecutionEnabled=false} 手动驱动——经 ToolRegistry 收口、
 * 计数、trace、BaseTool 控制信号一个不少）。
 *
 * <p>与 {@link JsonProtocolModelPort}（JSON 文本协议）互为降级逃生门：
 * 模型端 function calling 遵循率差时可切 {@code rag.chat.agent.model-port: json}。</p>
 */
public class NativeToolModelPort implements ModelPort {

    private final ChatModel chatModel;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NativeToolModelPort(ChatModel chatModel) {
        this(chatModel, null);
    }

    public NativeToolModelPort(ChatModel chatModel, String model) {
        this.chatModel = chatModel;
        this.model = model;
    }

    @Override
    public ModelReply complete(ModelRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.system()));
        messages.add(new UserMessage(request.user()));
        appendTurns(messages, request.turns());

        org.springframework.ai.openai.OpenAiChatOptions.Builder options =
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .toolCallbacks(project(request.tools()))
                        // 手动驱动：模型只声明工具调用，执行回到框架 ToolRegistry
                        .internalToolExecutionEnabled(false);
        if (model != null && !model.isBlank()) {
            options.model(model);
        }

        ChatResponse response = chatModel.call(new Prompt(messages, options.build()));
        AssistantMessage output = response.getResult().getOutput();
        if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
            List<ToolCall> calls = output.getToolCalls().stream()
                    .map(tc -> new ToolCall(tc.name(), parseArgs(tc.arguments())))
                    .toList();
            return new ModelReply(null, calls);
        }
        return new ModelReply(output.getText(), List.of());
    }

    /** 结构化历史 → 原生消息序列：assistant 工具调用声明与 tool 结果按 tool_call_id 成对。 */
    private static void appendTurns(List<Message> messages, List<ModelTurn> turns) {
        List<ToolCall> pendingCalls = null;
        for (ModelTurn turn : turns) {
            if (turn.kind() == ModelTurn.Kind.ASSISTANT) {
                if (turn.toolCalls().isEmpty()) {
                    if (turn.text() != null && !turn.text().isBlank()) {
                        messages.add(new AssistantMessage(turn.text()));
                    }
                    continue;
                }
                pendingCalls = turn.toolCalls();
            } else {
                if (pendingCalls == null || pendingCalls.isEmpty()) {
                    continue;
                }
                // tool_call_id 取结果观测的 id（循环节点保证与调用声明一一对应）
                List<AssistantMessage.ToolCall> declared = new ArrayList<>();
                List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
                int size = Math.min(pendingCalls.size(), turn.observations().size());
                for (int i = 0; i < size; i++) {
                    String id = turn.observations().get(i).id();
                    ToolCall call = pendingCalls.get(i);
                    declared.add(new AssistantMessage.ToolCall(id, "function", call.toolName(),
                            toJsonArgs(call)));
                    results.add(new ToolResponseMessage.ToolResponse(id,
                            turn.observations().get(i).toolName(),
                            turn.observations().get(i).content()));
                }
                messages.add(AssistantMessage.builder().toolCalls(declared).build());
                messages.add(ToolResponseMessage.builder().responses(results).build());
                pendingCalls = null;
            }
        }
    }

    /** 框架工具 schema → Spring AI 工具定义（只投影，不执行）。 */
    private static List<ToolCallback> project(List<ToolSchema> tools) {
        return tools.stream()
                .<ToolCallback>map(tool -> new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name(tool.name())
                                .description(tool.description())
                                .inputSchema(tool.inputSchema())
                                .build();
                    }

                    @Override
                    public String call(String toolInput) {
                        // 手动驱动下不会被 Spring AI 调用（执行走框架 ToolRegistry）
                        throw new UnsupportedOperationException("工具执行权在框架 ToolRegistry");
                    }
                })
                .toList();
    }

    private static String toJsonArgs(ToolCall call) {
        return call.args() == null || call.args().isEmpty() ? "{}" : call.args().toString();
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
