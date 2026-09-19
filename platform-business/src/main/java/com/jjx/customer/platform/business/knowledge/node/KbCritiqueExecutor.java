package com.jjx.customer.platform.business.knowledge.node;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.knowledge.retrieval.ChunkIdentity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索充分性判定节点执行器：命中数与最高相关度达标即收敛，否则走扩词重写。
 *
 * <p>判定口径与 agent-framework {@code CritiqueExecutor} 一致（同样的阈值、同样的
 * {@code sufficiency = min(1, 命中数/阈值条数)} 公式）——两边用的是同一个内核，
 * 这一拍的判据就不该有两套。{@code sufficiency} 同时作为反思 Region 的收敛观测量：
 * 它不再变化（&lt;0.05 增量）时循环提前收敛，不必撞到迭代上限。</p>
 *
 * <p>通过动态边在 {@code kb_done} 与 {@code kb_rewrite} 间自决（白名单由工作流声明，
 * 被拒时运行时回退静态边）。</p>
 */
public class KbCritiqueExecutor implements NodeExecutor {

    private final int requiredHits;

    private final double minTopScore;

    /**
     * 判据由配置注入（{@code rag.chat.agent.critique.*} → AgentProperties.Critique），
     * <b>不再硬编码</b>——条数下限是语料相关的，硬编码会让"换语料"变成"改代码"。
     *
     * @param requiredHits 充分所需的最少命中条数。LiveRAG（本仓评测语料）取 <b>1</b>：
     *                     实测 895 题中，进入反思环的 97 题里 <b>93 题每题只需 1 篇文档</b>，
     *                     命中 1 条本就是满分；7 道进环且 0 分的题里，5 道首轮就命中了且相关度
     *                     都在下限之上（最高 0.8321），没有一道是"首轮真的不够"。
     *                     另注：条数还被分块粒度污染——同一篇文档出一个还是五个 chunk 过阈值
     *                     取决于分块与精排，与"信息够不够"无关。
     *                     ⚠️ 多跳语料要调高（HotpotQA 均值 2.4 / MuSiQue 2.6 / 2Wiki 4.9），
     *                     否则会在只拿到第 1 跳文档时就停下。
     * @param minTopScore  首条命中的相关度下限（Rerank 之后的分数）。条数判据放宽后，
     *                     这条是主要的精度防线，与精排 {@code rag.rerank.min-relevance-score}
     *                     共同把关"召回的东西本身像不像"。
     */
    public KbCritiqueExecutor(int requiredHits, double minTopScore) {
        this.requiredHits = Math.max(1, requiredHits);
        this.minTopScore = minTopScore;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        List<?> fresh = hitsOf(context);
        // 先并入跨轮累积，再判定：kb_retrieve 是 TOOL 节点，每轮**覆盖写** kb_chunks_data，
        // 出口若读单轮产物，反思轮一旦空手而归就把首轮正确命中一并抹掉。
        // 判定同样看累积集——单轮命中数会因改写变准而**下降**（错拼查询靠词形巧合凑数，
        // 纠错后只剩真正相关的一条），只看单轮会出现"检索更准了反而判不充分"。
        List<Object> all = accumulate(context, fresh);
        double topScore = maxScoreOf(all);
        double sufficiency = Math.min(1.0, all.size() / (double) requiredHits);
        boolean sufficient = all.size() >= requiredHits && topScore >= minTopScore;
        String reason = String.format("累计命中 %d 条（本轮 %d 条），最高相关度 %.4f（下限 %.2f，条数下限 %d）",
                all.size(), fresh.size(), topScore, minTopScore, requiredHits);

        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put(KnowledgeQaGraphFactory.SUFFICIENCY_SLOT, sufficiency);
        writes.put(KnowledgeQaGraphFactory.CRITIQUE_REASON_SLOT, reason);
        writes.put(KnowledgeQaGraphFactory.CHUNKS_ALL_SLOT, all);
        return NodeResult.completed(node.id(), reason, writes)
                .withDynamicNext(sufficient
                        ? KnowledgeQaGraphFactory.DONE_NODE
                        : KnowledgeQaGraphFactory.REWRITE_NODE);
    }

    /** 本轮检索命中：知识轴 = {@code kb_chunks_data} 的 chunks（检索工具的结构化 data 通道）。 */
    private static List<?> hitsOf(NodeContext context) {
        Object data = context.slots().get(KnowledgeQaGraphFactory.CHUNKS_DATA_SLOT);
        if (data instanceof Map<?, ?> map && map.get("chunks") instanceof List<?> chunks) {
            return chunks;
        }
        return List.of();
    }

    /**
     * 本轮命中并入跨轮累积集：按分片标识去重（{@link ChunkIdentity}，与去重/融合同口径），
     * 同一分片重复命中时以本轮为准（改写后的相关度更可信），最后按分数降序——
     * 出口层直接按此顺序组装上下文，与单轮时的有序性一致。
     */
    private static List<Object> accumulate(NodeContext context, List<?> fresh) {
        Map<String, Object> byKey = new LinkedHashMap<>();
        Object prior = context.slots().get(KnowledgeQaGraphFactory.CHUNKS_ALL_SLOT);
        if (prior instanceof List<?> list) {
            for (Object hit : list) {
                String key = keyOf(hit);
                if (key != null) {
                    byKey.put(key, hit);
                }
            }
        }
        for (Object hit : fresh) {
            String key = keyOf(hit);
            if (key != null) {
                byKey.put(key, hit);
            }
        }
        List<Object> merged = new ArrayList<>(byKey.values());
        merged.sort(Comparator.comparingDouble(KbCritiqueExecutor::scoreOf).reversed());
        return merged;
    }

    /** 分片标识；非结构化元素返回 null（跳过，不参与累积也不占位）。 */
    @SuppressWarnings("unchecked")
    private static String keyOf(Object hit) {
        if (!(hit instanceof Map<?, ?> chunk) || chunk.get("content") == null) {
            return null;
        }
        Object meta = chunk.get("metadata");
        Map<String, Object> metadata = meta instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        return ChunkIdentity.of(metadata, String.valueOf(chunk.get("content")));
    }

    /** 相关度分：Rerank 之后 {@code score} 即相关度；缺失按 0（不能当成"达标"）。 */
    private static double scoreOf(Object hit) {
        if (hit instanceof Map<?, ?> chunk && chunk.get("score") instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    /** 累积集的最高相关度（各轮内部有序，跨轮必须取最大而不是取首条）。 */
    private static double maxScoreOf(List<?> hits) {
        double max = 0.0;
        for (Object hit : hits) {
            max = Math.max(max, scoreOf(hit));
        }
        return max;
    }
}
