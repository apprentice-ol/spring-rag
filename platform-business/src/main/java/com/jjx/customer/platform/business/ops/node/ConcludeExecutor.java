package com.jjx.customer.platform.business.ops.node;

import com.jjx.customer.platform.business.workflow.common.EscalateTerminal;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直答收尾执行器（O10）：结论不走 RAG 再生成，取最后非空阶段产出直接交付
 * （对齐参考 {@code finalText} 覆盖语义）。无任何产出时如实说明，不编造结论。
 */
public class ConcludeExecutor implements NodeExecutor {

    /** 阶段产出槽位（后覆盖前）。 EscalateExecutor（workflow.common 域外共用）也读取。 */
    public static final String[] STAGE_OUTPUTS = EscalateTerminal.STAGE_OUTPUT_SLOTS;

    /**
     * {@code {"answer":"…"}} 外壳（含换行的结论常让解析失败，于是原文直通到交付）。
     * 交付前剥掉，否则用户看到的是花括号与 {@code \n} 转义。
     */
    private static final Pattern ANSWER_ENVELOPE =
            Pattern.compile("^\\{\\s*\"answer\"\\s*:\\s*\"(.*)\"\\s*\\}$", Pattern.DOTALL);

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
                finalText = unwrapAnswerEnvelope(value);
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

    /**
     * 剥掉 {@code {"answer":"…"}} 外壳；不是该形态（含 JSON 解析失败后原文直通的情况）原样返回。
     *
     * <p>只做字符串级反转义（{@code \n \" \\}）——够用且不会把正文里的正常引号吃掉。</p>
     *
     * @param text 阶段产出原文
     * @return 剥壳后的正文；非外壳形态返回原文
     */
    private static String unwrapAnswerEnvelope(String text) {
        Matcher matcher = ANSWER_ENVELOPE.matcher(text.trim());
        if (!matcher.matches()) {
            return text;
        }
        return matcher.group(1)
                .replace("\\n", "\n")
                .replace("\\r", "")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
