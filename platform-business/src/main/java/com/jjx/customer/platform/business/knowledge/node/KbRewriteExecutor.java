package com.jjx.customer.platform.business.knowledge.node;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.business.knowledge.RewritePolicy;
import com.jjx.customer.platform.business.knowledge.normalize.QueryRewriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 问题重写节点执行器：首轮消解指代，反思轮按「原生问题 → 错词纠正 → 拓展思路」递进补救。
 *
 * <p><b>轮次语义</b>（{@code rewrite_round} 槽位即轮次计数）：</p>
 * <ol>
 *   <li><b>原生问题</b>：round 0 且无可消解的上下文（{@code AUTO}）→ 原样透传，不做任何改写</li>
 *   <li><b>错词纠正</b>：round 1 的英文 query → 拼写纠错（错拼是 LiveRAG 失败题的主要形态，
 *       字面直接 miss）；纠不出东西再退到拓展思路</li>
 *   <li><b>拓展思路</b>：其余反思轮 → 模型看着原始问题换角度重写（换术语/换粒度/换表述），
 *       确定性词表只作兜底。见 {@link QueryRewriter#expandWithModel}</li>
 * </ol>
 *
 * <p>纠错与拓展都<b>按需触发</b>——只有检索不充分的题才走到这里，不像首轮无条件改写那样
 * 给每道题都加一次 LLM 调用。见 {@link QueryRewriter#spellFixEnglish}。</p>
 *
 * <p>产物 {@code rewritten_query} 是检索工具的唯一查询来源
 * （工具参数 {@code ${slots.rewritten_query}}），{@code rewrite_round} 同时作为
 * 反思循环的计数槽位。</p>
 *
 * <p>它在反思环里被<b>回边</b>再次进入，这正是"一次没查到就重写再查"的落点：
 * 没有这个节点，检索不充分时系统只能认命——那正是先前 springai-rag 检索效果
 * 不如 agent-framework 的结构性原因之一。</p>
 */
public class KbRewriteExecutor implements NodeExecutor {

    private final QueryRewriter rewriter;

    /**
     * @param rewriter 重写器（首轮 LLM 消解指代 / 反思轮确定性扩词）
     */
    public KbRewriteExecutor(QueryRewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        int round = context.slots().getInt(KnowledgeQaGraphFactory.REWRITE_ROUND_SLOT, 0);
        String source;
        String rewritten;
        boolean attempt;
        // 反思轮实际用的补救手段（写进轨迹文案，让"为什么又查一次"可读）
        String remedy = null;
        if (round == 0) {
            source = context.slots().getString(KnowledgeQaGraphFactory.NORMALIZED_QUERY_SLOT, "");
            String history = context.slots().getString(KnowledgeQaGraphFactory.HISTORY_SLOT, null);
            String clarify = context.slots().getString(KnowledgeQaGraphFactory.CLARIFY_SLOT, null);
            RewritePolicy policy = RewritePolicy.of(
                    context.slots().getString(KnowledgeQaGraphFactory.REWRITE_POLICY_SLOT, null));
            attempt = policy.shouldRewrite(hasText(history) || hasText(clarify));
            rewritten = attempt ? rewriter.rewrite(source, history, clarify) : source;
        } else {
            // 反思轮：上一轮不充分，换个说法重查（与策略无关——这一轮的意义就是补救）
            source = context.slots().getString(KnowledgeQaGraphFactory.REWRITTEN_QUERY_SLOT, "");
            String question = context.slots().getString(KnowledgeQaGraphFactory.QUESTION_SLOT, null);
            if (QueryRewriter.isEnglish(source) && round == 1) {
                // 英文第二轮优先纠错：错拼是 LiveRAG 失败题的主要形态（字面直接 miss），纠错比拓展对症。
                // 按需触发——只有检索不充分的题才走到这里，不像首轮无条件纠错那样给每道题都加一次
                // LLM 调用；纠不出东西（原文本就没错拼）再退到拓展思路。
                String fixed = rewriter.spellFixEnglish(source);
                boolean fixedApplied = !fixed.equals(source);
                rewritten = fixedApplied ? fixed : rewriter.expandWithModel(source, question, round);
                remedy = fixedApplied ? "拼写纠错" : "拓展思路";
            } else {
                // 第三轮起（以及非英文的第二轮）：让模型看着原始问题换角度拓展，
                // 确定性词表退居兜底（见 QueryRewriter#expandWithModel）
                rewritten = rewriter.expandWithModel(source, question, round);
                remedy = "拓展思路";
            }
            attempt = true;
        }

        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put(KnowledgeQaGraphFactory.REWRITTEN_QUERY_SLOT, rewritten);
        writes.put(KnowledgeQaGraphFactory.REWRITE_ROUND_SLOT, round + 1);

        return NodeResult.completed(node.id(), describe(context, round, source, rewritten, attempt, remedy),
                writes);
    }

    /**
     * 轨迹文案。
     *
     * <p>反思轮把「为什么又查一次」一并写出来——触发重查的是上一轮 {@code kb_critique} 的不充分判定，
     * 不写的话读者只看到查询被改了，看不出被谁、因何而改。这一环正是召回失败题的现场：
     * 首轮命中 1 条被判不充分 → 触发重查 → 若重查更差，出口层还可能被覆盖。</p>
     *
     * <p>首轮维持原样：只说明「改写未生效 / 原样透传」，免得读者以为改写器坏了。</p>
     */
    private static String describe(NodeContext context, int round, String source, String rewritten,
                                   boolean attempt, String remedy) {
        if (round > 0) {
            String reason = context.slots().getString(KnowledgeQaGraphFactory.CRITIQUE_REASON_SLOT, null);
            String why = hasText(reason) ? "上轮不充分（" + reason + "）" : "上轮检索不充分";
            return why + "\n→ " + (remedy == null ? "重写" : remedy) + "重查：" + rewritten;
        }
        // 产物即查询本身；透传时说清"没改写"
        return rewritten.equals(source) ? rewritten + (attempt ? "（改写未生效）" : "（原样透传）") : rewritten;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
