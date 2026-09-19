package com.jjx.customer.platform.business.workflow.common;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import java.util.Map;

/**
 * 升级终态执行器：把升级原因作为流程输出直出（缺用户输入 / 预算受限 / adjust 额度用尽）。
 * 终态语义（terminal=true）：nextNode 为 null 时运行时以 COMPLETED 收束。
 *
 * <p>升级输出**必须带上此前各阶段的产出**：升级原因是"为什么需要人工"，
 * 阶段产出是"已经查到什么"。只输出原因会让用户以为什么都没查到（真实踩过）。</p>
 */
public class EscalateExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        String reason = context.slots().getString(EscalateTerminal.REASON_SLOT, "");
        String note = reason.isBlank() ? "模型判断无法继续，需要人工介入" : reason;
        String findings = lastStageOutput(context);
        String text = findings.isBlank()
                ? note
                : findings + "\n\n——\n需人工介入：" + note;
        return NodeResult.completed(node.id(), text,
                Map.of(EscalateTerminal.REASON_SLOT, note, "final_output", text));
    }

    /**
     * 取最后一个非空阶段产出（inv → res → ver，后覆盖前）。
     *
     * @param context 节点上下文
     * @return 阶段产出；全空时返回空串
     */
    private static String lastStageOutput(NodeContext context) {
        String found = "";
        for (String slot : EscalateTerminal.STAGE_OUTPUT_SLOTS) {
            String value = context.slots().getString(slot, "");
            if (!value.isBlank()) {
                found = value;
            }
        }
        return found;
    }
}
