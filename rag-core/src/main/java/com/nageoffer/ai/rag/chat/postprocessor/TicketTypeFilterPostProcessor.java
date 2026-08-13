package com.nageoffer.ai.rag.chat.postprocessor;

import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelResult;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 票种对立过滤后处理器（dedup 之后、fusion 之前）。
 *
 * <p>解决"查专票流程却带上普通发票流程"类问题：用户问题带明确票种限定时
 * （如"增值税发票冲红流程"→ 专票），块级 keywords 含<b>对立票种</b>的 chunk 直接过滤，
 * 不参与 RRF 融合与 Rerank。Rerank 对"同主题不同票种"的块仍会打高分
 * （如"普通发票红冲"与"专票冲红"都是冲红流程），检索侧不滤，LLM 就按文档顺序把旁支流程展开。</p>
 *
 * <p><b>【2026-08-03 禁用】</b>对齐 ragent（无此机制，回答反而更完整）："增值税发票冲红流程"
 * 这类大类 query 本身含混（可指普通 + 专用两套流程），过滤会误伤普通发票块、回答只剩一半；
 * ragent 靠向量召回 + Rerank 分数断层 + 提示词"多条路径全讲"给出完整回答。禁用后
 * {@code isEnabled} 恒为 false，保留实现供需要时恢复（改回 {@code detectQueryTicketType(...) != NONE}）。</p>
 *
 * <ul>
 *   <li><b>专票类特征词</b>：增值税 / 专用 / 专票</li>
 *   <li><b>普通类特征词</b>：普通 / 普票</li>
 *   <li>query 同时含两类特征（如"增值税普通发票"）→ 视为无票种限定，不过滤（豁免）</li>
 *   <li>无票种特征（如"发票冲红流程"）→ 不过滤，按原路径全展开</li>
 * </ul>
 */
@Slf4j
@Component
public class TicketTypeFilterPostProcessor implements SearchResultPostProcessor {

    /** 专票类 query 特征词 */
    private static final String[] SPEC_TYPE_TERMS = {"增值税", "专用", "专票"};
    /** 普通类 query 特征词 */
    private static final String[] NORMAL_TYPE_TERMS = {"普通", "普票"};

    /** 块 keywords 中的对立票种标记（目标=专票时过滤这些） */
    private static final String[] OPPOSITE_OF_SPEC = {"普通发票", "普票"};
    /** 块 keywords 中的对立票种标记（目标=普通时过滤这些） */
    private static final String[] OPPOSITE_OF_NORMAL = {"专用发票", "专票", "增值税专用"};

    /** 票种判定结果 */
    private enum TicketType { SPEC, NORMAL, NONE }

    @Override
    public String getName() {
        return "ticket-type";
    }

    @Override
    public int getOrder() {
        return 3; // dedup(1) → doc-scope(2) → ticket-type(3) → fusion(5) → rerank(10)
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 【2026-08-03 禁用】对齐 ragent：大类 query（"增值税发票冲红"）过滤对立票种 = 回答砍半，
        // 两类全讲 + 提示词分流才是完整回答。恢复时改回：
        // detectQueryTicketType(context.getRewrittenQuery() != null
        //         ? context.getRewrittenQuery() : context.getQuery()) != TicketType.NONE;
        return false;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }
        String query = context.getRewrittenQuery() != null
                ? context.getRewrittenQuery() : context.getQuery();
        TicketType target = detectQueryTicketType(query);
        if (target == TicketType.NONE) {
            return chunks;
        }

        List<RetrievedChunk> filtered = chunks.stream()
                .filter(c -> !isOppositeTicketType(c, target))
                .toList();

        int dropped = chunks.size() - filtered.size();
        if (dropped > 0) {
            log.info("[票种过滤] query 票种={}, 过滤对立票种块 {} 条（{} → {}）",
                    target, dropped, chunks.size(), filtered.size());
        } else {
            log.debug("[票种过滤] query 票种={}, 无对立票种块", target);
        }
        return filtered;
    }

    private TicketType detectQueryTicketType(String query) {
        if (query == null || query.isBlank()) {
            return TicketType.NONE;
        }
        boolean spec = containsAny(query, SPEC_TYPE_TERMS);
        boolean normal = containsAny(query, NORMAL_TYPE_TERMS);
        if (spec && !normal) {
            return TicketType.SPEC;
        }
        if (normal && !spec) {
            return TicketType.NORMAL;
        }
        // 两类特征同现（如"增值税普通发票"）视为无票种限定，豁免
        return TicketType.NONE;
    }

    private boolean isOppositeTicketType(RetrievedChunk chunk, TicketType target) {
        Map<String, Object> meta = chunk.getMetadata();
        if (meta == null) {
            return false;
        }
        Object kws = meta.get("keywords");
        if (!(kws instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        String[] oppositeTerms = target == TicketType.SPEC ? OPPOSITE_OF_SPEC : OPPOSITE_OF_NORMAL;
        for (Object kw : list) {
            if (kw == null) {
                continue;
            }
            String s = kw.toString();
            for (String term : oppositeTerms) {
                if (s.contains(term)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String[] terms) {
        for (String t : terms) {
            if (text.contains(t)) {
                return true;
            }
        }
        return false;
    }
}
