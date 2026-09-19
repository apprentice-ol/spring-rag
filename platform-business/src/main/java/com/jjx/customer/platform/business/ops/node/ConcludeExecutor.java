package com.jjx.customer.platform.business.ops.node;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import java.util.Map;

/**
 * 直答收尾执行器（O10）：结论不走 RAG 再生成，取最后非空阶段产出直接交付
 * （对齐参考 {@code finalText} 覆盖语义）。无任何产出时如实说明，不编造结论。
 */
public class ConcludeExecutor implements NodeExecutor {

    /** 阶段产出槽位（后覆盖前）。 EscalateExecutor（workflow.common 域外共用）也读取。 */
    public static final String[] STAGE_OUTPUTS = {"inv_stage_output", "res_stage_output", "ver_stage_output"};

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        String finalText = "";
        for (String slot : STAGE_OUTPUTS) {
            String value = context.slots().getString(slot, "");
            if (!value.isBlank()) {
                finalText = value;
            }
        }
        if (finalText.isBlank()) {
            finalText = "诊断流程结束但未产出结论（可能因预算或步数限制被截断），请补充信息后重试。";
        }
        // 自主性透明化：本次自动补全了什么、依据是什么，随结论一并交付（可审计、可纠错）
        String autoNote = context.slots().getString(AutoResolveExecutor.AUTO_NOTE_SLOT, "");
        if (!autoNote.isBlank()) {
            finalText = finalText + "\n\n——\n本次诊断的上下文自动补全：\n" + autoNote;
        }
        return NodeResult.completed(node.id(), finalText, Map.of("final_output", finalText));
    }
}
