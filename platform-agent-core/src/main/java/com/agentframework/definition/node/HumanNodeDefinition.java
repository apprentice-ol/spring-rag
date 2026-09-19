package com.agentframework.definition.node;

import java.time.Duration;
import java.util.List;

/**
 * 人工节点：挂起会话，直到收到人工答复。
 *
 * <p>挂起时引擎会持久化 cursor，因此进程重启后仍可从断点恢复执行。</p>
 *
 * @param id             节点 id
 * @param promptTemplate 提示人工输入的问题
 * @param inputSlot      人工输入写入的槽位名
 * @param outputSlot     输出槽位名，缺省与输入槽位相同
 * @param choices        可选项列表，为空表示自由输入
 * @param timeout        等待超时，为零表示不超时
 * @param meta           横切信息
 */
public record HumanNodeDefinition(
        String id,
        String promptTemplate,
        String inputSlot,
        String outputSlot,
        List<String> choices,
        Duration timeout,
        NodeMeta meta) implements NodeDefinition {

    public HumanNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("human node id is required");
        }
        promptTemplate = promptTemplate == null ? "Input required" : promptTemplate;
        inputSlot = inputSlot == null || inputSlot.isBlank() ? "human_input" : inputSlot;
        outputSlot = outputSlot == null || outputSlot.isBlank() ? inputSlot : outputSlot;
        choices = List.copyOf(choices == null ? List.of() : choices);
        timeout = timeout == null ? Duration.ZERO : timeout;
        meta = meta == null ? NodeMeta.empty() : meta;
    }

    /**
     * @param id             节点 id
     * @param promptTemplate 提示语
     * @param slot           输入输出共用的槽位名
     * @return 人工节点定义
     */
    public static HumanNodeDefinition of(String id, String promptTemplate, String slot) {
        return new HumanNodeDefinition(id, promptTemplate, slot, slot, null, null, null);
    }

    @Override
    public NodeType type() {
        return NodeType.HUMAN;
    }

    /** @return 是否配置了等待超时 */
    public boolean hasTimeout() {
        return !timeout.isZero() && !timeout.isNegative();
    }
}
