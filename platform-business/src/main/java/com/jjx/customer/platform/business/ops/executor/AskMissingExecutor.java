package com.jjx.customer.platform.business.ops.executor;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.runtime.session.Message;
import com.jjx.customer.platform.business.ops.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.OpsSlotExtractor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次问齐节点执行器（O1 核心）：必填缺失时把全部缺失项拼成一次提问并挂起整次运行；
 * 用户补充后经 {@code resume} 从本节点重入，把答复解析回槽位再判定——不足则再问，
 * 齐备则放行。挂起/恢复语义与内核 HumanNode 同构（cursor 停在本节点，恢复即重入）。
 *
 * <p>问齐文案与参考实现完全同构：目录序 × {@code join("\n")}，无编号。
 * 已知槽位不重复问（缺失判定只算未填项；抽取只填空缺）。</p>
 */
public class AskMissingExecutor implements NodeExecutor {

    /** 问齐文案槽位名（非空即"问过一轮"，是重入判定的标记）。 */
    public static final String CLARIFY_QUESTION_SLOT = "clarify_question";
    /** 用户补充槽位名（恢复输入经 Input.slots 写入）。 */
    public static final String USER_CLARIFY_SLOT = "user_clarify";

    private final OpsSlotExtractor extractor;

    /**
     * @param extractor 槽位抽取器
     */
    public AskMissingExecutor(OpsSlotExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        Map<String, String> confirmed = SlotExtractExecutor.confirmedSlots(context);
        String priorAsk = context.slots().getString(CLARIFY_QUESTION_SLOT, "");
        String reply = replyOf(context);

        Map<String, Object> writes = new LinkedHashMap<>();
        // 重入且带答复：先解析补充（只填空缺），再重判缺口；首轮（未问过）直接问
        if (!priorAsk.isBlank() && !reply.isBlank()) {
            Map<String, String> extracted = extractor.extract(reply, confirmed);
            writes.putAll(extracted);
            confirmed.putAll(extracted);
            writes.put(USER_CLARIFY_SLOT, reply);
        }

        List<String> missing = SlotExtractExecutor.missingRequired(confirmed, Map.of());
        writes.put(SlotExtractExecutor.MISSING_COUNT_SLOT, missing.size());
        if (!missing.isEmpty()) {
            String askText = String.join("\n", missing.stream().map(AskMissingExecutor::questionOf).toList());
            // 自主补全透明化：已自动推断的项随问句透出，用户一轮内既补缺又可纠错
            String autoNote = context.slots().getString(AutoResolveExecutor.AUTO_NOTE_SLOT, "");
            if (!autoNote.isBlank()) {
                askText = askText + "\n\n" + autoNote + "\n（以上已自动补全，如有误请直接指出）";
            }
            writes.put(CLARIFY_QUESTION_SLOT, askText);
            // 挂起结果同样要带回全部槽位写入：答复解析出的值必须先落槽位，否则重入会再问一遍
            NodeResult suspended = NodeResult.suspended(node.id(), askText);
            for (Map.Entry<String, Object> entry : writes.entrySet()) {
                suspended = suspended.withSlotWrite(entry.getKey(), entry.getValue());
            }
            return suspended;
        }
        if (!reply.isBlank() && !priorAsk.isBlank()) {
            return NodeResult.completed(node.id(), "信息已齐备", writes)
                    .withMessage(Message.user(reply));
        }
        return NodeResult.completed(node.id(), "信息已齐备", writes);
    }

    /**
     * 读用户补充：优先恢复输入写入的 {@code user_clarify} 槽位，其次本轮文本。
     *
     * @param context 节点上下文
     * @return 补充文本，无则空串
     */
    private String replyOf(NodeContext context) {
        String clarified = context.slots().getString(USER_CLARIFY_SLOT, "");
        if (!clarified.isBlank()) {
            return clarified;
        }
        return context.input() == null ? "" : context.input().text();
    }

    /**
     * 槽位问句（空则回退槽位名，对齐参考实现）。
     *
     * @param name 槽位名
     * @return 问句
     */
    private static String questionOf(String name) {
        return OpsSlotCatalog.ALL.stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .map(spec -> spec.question() == null || spec.question().isBlank() ? spec.name() : spec.question())
                .orElse(name);
    }
}
