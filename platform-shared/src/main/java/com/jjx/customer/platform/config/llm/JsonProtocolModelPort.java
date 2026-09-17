package com.jjx.customer.platform.config.llm;

import com.jjx.customer.platform.agent.framework.model.ModelPort;
import com.jjx.customer.platform.agent.framework.model.ModelReply;
import com.jjx.customer.platform.agent.framework.model.ModelRequest;
import com.jjx.customer.platform.agent.framework.model.ToolCall;
import com.jjx.customer.platform.agent.framework.model.ToolSchema;
import com.jjx.customer.platform.common.util.JsonResponseParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具协议的模型端口（R1：让框架 LOOP 节点具备真实工具循环能力）。
 *
 * <p>协议（贴近生成位置，模型遵循率高）：
 * <ul>
 *   <li>要调工具：只输出 {@code {"tool":"工具名","args":{...}}}</li>
 *   <li>给结论：只输出 {@code {"answer":"最终结论"}}</li>
 * </ul>
 * 解析失败时按"模型给了终稿"处理（把原文当答案），不打断链路。</p>
 *
 * <p>依赖收口在 {@link ChatCaller}（system/user → 文本），便于单测与后续替换为
 * 原生 function calling 适配——框架侧契约（{@link ModelPort}）不变。</p>
 */
public class JsonProtocolModelPort implements ModelPort {

    /** 一次模型调用（业务侧用 ChatClient 实现；测试用 lambda）。 */
    @FunctionalInterface
    public interface ChatCaller {
        String call(String system, String user);
    }

    private final ChatCaller caller;

    public JsonProtocolModelPort(ChatCaller caller) {
        this.caller = caller;
    }

    @Override
    public ModelReply complete(ModelRequest request) {
        String system = request.system() + "\n\n" + protocol(request.tools());
        String user = buildUser(request);
        String raw = caller.call(system, user);
        return parse(raw);
    }

    /** 工具协议说明（工具清单与参数 schema 由框架投影而来，业务不重复声明）。 */
    private static String protocol(List<ToolSchema> tools) {
        StringBuilder sb = new StringBuilder("""
                ## 工具使用协议
                需要调用工具时，只输出一行 JSON：{"tool":"工具名","args":{...}}
                可以直接给出结论时，只输出一行 JSON：{"answer":"最终结论"}
                不要输出 markdown 围栏、不要输出多余解释。
                """);
        if (tools.isEmpty()) {
            return sb.append("\n（本阶段无可用工具，直接给 answer）").toString();
        }
        sb.append("\n## 可用工具\n");
        for (ToolSchema tool : tools) {
            sb.append("- ").append(tool.name()).append("：").append(tool.description()).append('\n')
                    .append("  参数 schema: ").append(tool.inputSchema()).append('\n');
        }
        return sb.toString();
    }

    /** user 内容 = 问题 + 已发生的工具观测（回喂模型自行纠偏）。 */
    private static String buildUser(ModelRequest request) {
        StringBuilder sb = new StringBuilder("用户问题：").append(request.user());
        if (!request.observations().isEmpty()) {
            sb.append("\n\n## 已执行的工具结果\n");
            for (String observation : request.observations()) {
                sb.append(observation).append('\n');
            }
        }
        return sb.toString();
    }

    private static ModelReply parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ModelReply("", List.of());
        }
        Map<String, Object> parsed;
        try {
            parsed = JsonResponseParser.parseObject(raw);
        } catch (Exception e) {
            return new ModelReply(raw, List.of());
        }
        Object tool = parsed.get("tool");
        if (tool != null && !String.valueOf(tool).isBlank()) {
            return new ModelReply(null, List.of(new ToolCall(String.valueOf(tool), argsOf(parsed.get("args")))));
        }
        Object answer = parsed.containsKey("answer") ? parsed.get("answer") : parsed.get("final");
        return new ModelReply(answer == null ? raw : String.valueOf(answer), List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argsOf(Object args) {
        if (args instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<?, ?>) map).forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    /** 便捷：把模型回复里声明的多工具（数组形式）也接住（容错，非协议主路径）。 */
    static List<ToolCall> multiToolOf(Object raw) {
        List<ToolCall> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object name = map.get("tool");
                    if (name != null) {
                        out.add(new ToolCall(String.valueOf(name), argsOf(map.get("args"))));
                    }
                }
            }
        }
        return out;
    }
}
