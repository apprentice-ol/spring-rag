package com.nageoffer.ai.rag.chat.agent.toolkit;

import com.nageoffer.ai.rag.chat.agent.ChunkGrade;
import com.nageoffer.ai.rag.chat.agent.GradeVerdict;
import com.nageoffer.ai.rag.chat.agent.GradingResult;
import com.nageoffer.ai.rag.chat.normalize.QueryRewriter;
import com.nageoffer.ai.rag.chat.rerank.NoopRerankClient;
import com.nageoffer.ai.rag.chat.rerank.RerankClient;
import com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannel;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import com.nageoffer.ai.rag.chat.util.TextPreviews;
import com.nageoffer.ai.rag.chat.retrieval.WebSearchChannel;
import com.nageoffer.ai.rag.common.util.JsonResponseParser;
import com.nageoffer.ai.rag.config.properties.AgentProperties;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 共享原子工具箱（所有范式复用，对应 agent 的"工具知识"）。
 * <p>把现有能力包装成 6 个可组合的工具：retrieve / grade / rewrite / decompose / webSearch / rerank。
 * 不同范式以不同方式组合它们：
 * <ul>
 *   <li>Naive: retrieve</li>
 *   <li>CRAG/Self-RAG: retrieve → grade → (rewrite/webSearch/rerank)（代码固定调度）</li>
 *   <li>Plan-Execute: decompose → retrieve×N → rerank</li>
 *   <li>ReAct: LLM 自主调（见 ReactToolkit，走原生 function calling，不直接用本类）</li>
 * </ul>
 *
 * <p>所有 LLM 决策步一律 ingestionChatClient + JsonResponseParser（贴现有范式，零 .entity()）。
 * 工具内部对异常一律降级（不阻断检索），失败时返回"最宽松"结果（如 grade 全 relevant）。
 */
@Slf4j
@Component
public class AgentToolkit {

    private final MultiChannelRetrievalEngine retrievalEngine;
    private final QueryRewriter queryRewriter;
    private final QueryDecomposer queryDecomposer;
    private final ObjectProvider<WebSearchChannel> webSearchChannelProvider;
    private final ObjectProvider<RerankClient> rerankClientProvider;
    private final ChatClient ingestionChatClient;
    private final PromptStore promptStore;

    // 手写构造器：@Qualifier 必须在参数上才生效（@RequiredArgsConstructor 不搬字段注解，会注入成 @Primary 的 ragChatClient）
    public AgentToolkit(MultiChannelRetrievalEngine retrievalEngine,
                        QueryRewriter queryRewriter,
                        QueryDecomposer queryDecomposer,
                        ObjectProvider<WebSearchChannel> webSearchChannelProvider,
                        ObjectProvider<RerankClient> rerankClientProvider,
                        @Qualifier("ingestionChatClient") ChatClient ingestionChatClient,
                        PromptStore promptStore) {
        this.retrievalEngine = retrievalEngine;
        this.queryRewriter = queryRewriter;
        this.queryDecomposer = queryDecomposer;
        this.webSearchChannelProvider = webSearchChannelProvider;
        this.rerankClientProvider = rerankClientProvider;
        this.ingestionChatClient = ingestionChatClient;
        this.promptStore = promptStore;
    }

    // ==================== retrieve ====================

    @TelemetryStep("rag.agent.retrieve")
    public MultiChannelRetrievalEngine.RetrievalResult retrieve(SearchContext ctx) {
        return retrievalEngine.retrieve(ctx);
    }

    // ==================== grade（LLM 批量评估 chunk 相关性） ====================

    /**
     * 评估 chunk 列表对问题的相关性，返回逐条分数 + 聚合裁决。
     * 失败时降级为"全 relevant"（不阻断检索）。
     */
    @TelemetryStep("rag.agent.grade")
    public GradingResult grade(String query, List<RetrievedChunk> chunks, AgentProperties opts) {
        if (chunks == null || chunks.isEmpty()) {
            return GradingResult.empty();
        }
        try {
            String system = promptStore.raw("agent/grade");
            String user = buildGradeUser(query, chunks);
            String response = ingestionChatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .content();
            GradingResult result = parseGrade(response, chunks, opts.getGradeThreshold());
            log.info("[agent.grade] query=\"{}\" 命中={} relevant={} verdict={}",
                    query, chunks.size(), result.relevantCount(), result.verdict());
            return result;
        } catch (Exception e) {
            log.warn("[agent.grade] 异常，默认全 relevant: {}", e.getMessage());
            return allRelevantFallback(chunks, opts.getGradeThreshold());
        }
    }

    private String buildGradeUser(String query, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(query).append("\n\n待评估文档片段：\n");
        for (int i = 0; i < chunks.size(); i++) {
            sb.append('[').append(i + 1).append("] ")
                    .append(preview(chunks.get(i).getContent())).append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private GradingResult parseGrade(String response, List<RetrievedChunk> chunks, double threshold) {
        Map<String, Object> obj = JsonResponseParser.parseObject(response);
        if (obj.isEmpty()) {
            return allRelevantFallback(chunks, threshold);
        }
        List<ChunkGrade> grades = new ArrayList<>();
        int relevant = 0;
        double sum = 0;
        Object gradesObj = obj.get("grades");
        if (gradesObj instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                int ref = asInt(m.get("ref"), -1);
                double score = asDouble(m.get("score"), 0.0);
                boolean rel = m.containsKey("relevant")
                        ? asBool(m.get("relevant"), score >= threshold)
                        : score >= threshold;
                String reason = asStr(m.get("reason"), "");
                grades.add(new ChunkGrade(ref, score, rel, reason));
                sum += score;
                if (rel) {
                    relevant++;
                }
            }
        }
        if (grades.isEmpty()) {
            return allRelevantFallback(chunks, threshold);
        }
        double avg = sum / grades.size();
        GradeVerdict verdict = (relevant == 0) ? GradeVerdict.ALL_IRRELEVANT
                : (relevant == grades.size() ? GradeVerdict.ALL_RELEVANT : GradeVerdict.PARTIAL);
        return new GradingResult(grades, relevant, avg, verdict);
    }

    private GradingResult allRelevantFallback(List<RetrievedChunk> chunks, double threshold) {
        List<ChunkGrade> grades = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            double s = (c != null && c.getScore() != null) ? c.getScore() : threshold;
            grades.add(new ChunkGrade(i + 1, s, true, "grade 降级，默认 relevant"));
        }
        return new GradingResult(grades, grades.size(), threshold, GradeVerdict.ALL_RELEVANT);
    }

    // ==================== rewrite / decompose ====================

    public String rewrite(String query, String history) {
        return queryRewriter.rewrite(query, history);
    }

    public List<String> decompose(String query) {
        return queryDecomposer.decompose(query);
    }

    // ==================== webSearch（stub 下返回空） ====================

    /** 联网检索；通道未启用/异常/ stub 时返回空列表（不抛异常）。 */
    public List<RetrievedChunk> webSearch(SearchContext ctx) {
        WebSearchChannel ch = webSearchChannelProvider.getIfAvailable();
        if (ch == null || !ch.isEnabled(ctx)) {
            return List.of();
        }
        try {
            return ch.search(ctx).getChunks();
        } catch (Exception e) {
            log.warn("[agent.webSearch] 异常，返回空: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== rerank（无 bean/noop 时截断） ====================

    /**
     * 按 query 精排 chunk 列表取前 topN。
     * rerank 未启用（NoopRerankClient）或异常时，按原序截断返回前 topN（不阻断）。
     */
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topN) {
        if (chunks == null || chunks.isEmpty() || topN <= 0) {
            return List.of();
        }
        RerankClient client = rerankClientProvider.getIfAvailable();
        if (client == null || client instanceof NoopRerankClient) {
            return chunks.size() <= topN ? List.copyOf(chunks) : List.copyOf(chunks.subList(0, topN));
        }
        try {
            return client.rerank(query, chunks, topN);
        } catch (Exception e) {
            log.warn("[agent.rerank] 异常，按原序截断: {}", e.getMessage());
            return chunks.size() <= topN ? List.copyOf(chunks) : List.copyOf(chunks.subList(0, topN));
        }
    }

    // ==================== 小工具 ====================

    static String preview(String content) {
        return TextPreviews.preview(content, 120);
    }

    private static int asInt(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? fallback : Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double asDouble(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? fallback : Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean asBool(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        return o == null ? fallback : Boolean.parseBoolean(o.toString());
    }

    private static String asStr(Object o, String fallback) {
        return o == null ? fallback : o.toString();
    }
}
