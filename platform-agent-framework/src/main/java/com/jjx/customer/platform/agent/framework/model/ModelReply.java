package com.jjx.customer.platform.agent.framework.model;

import java.util.List;

/**
 * 模型回复：要么给最终文本（toolCalls 为空），要么给工具调用（可多个）。
 */
public record ModelReply(String text, List<ToolCall> toolCalls) {

    public ModelReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
