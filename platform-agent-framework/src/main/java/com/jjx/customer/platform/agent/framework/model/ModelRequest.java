package com.jjx.customer.platform.agent.framework.model;

import java.util.List;

/**
 * 模型请求（框架语义的最小子集）。
 *
 * @param system       system prompt（阶段 prompt，来自快照）
 * @param user         user 内容（问题 + 上下文等）
 * @param tools        可用工具 schema
 * @param observations 上一轮工具产出的文本观测（JSON 协议端口的回喂载体）
 * @param turns        结构化对话历史（原生 function calling 端口的回喂载体；无历史为空）
 */
public record ModelRequest(String system, String user, List<ToolSchema> tools,
                           List<String> observations, List<ModelTurn> turns) {

    public ModelRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
        observations = observations == null ? List.of() : List.copyOf(observations);
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public ModelRequest(String system, String user, List<ToolSchema> tools, List<String> observations) {
        this(system, user, tools, observations, List.of());
    }
}
