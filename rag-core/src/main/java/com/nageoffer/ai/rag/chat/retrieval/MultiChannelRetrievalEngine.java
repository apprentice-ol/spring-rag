package com.nageoffer.ai.rag.chat.retrieval;

import com.nageoffer.ai.rag.chat.postprocessor.SearchResultPostProcessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;

/**
 * 多通道检索引擎。
 * <p>
 * 负责并行执行所有启用的 {@link SearchChannel}，然后将结果依次送入
 * {@link SearchResultPostProcessor} 责任链（按 order 升序）。
 * </p>
 *
 * <p>处理流程：</p>
 * <ol>
 *   <li>收集所有 isEnabled=true 的通道</li>
 *   <li>通过 ragContextExecutor 并行执行 search</li>
 *   <li>合并各通道 chunks 为统一列表</li>
 *   <li>按 order 升序执行后处理器链（去重 → RRF 融合 → Rerank）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> channels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final Executor ragContextExecutor;

    /**
     * 执行多通道检索 + 后处理链。
     *
     * @param context 检索上下文
     * @return 包含所有通道结果 + 后处理结果的检索结果
     */
    @TelemetryStep("rag.retrieve")
    public RetrievalResult retrieve(SearchContext context) {
        long t0 = System.currentTimeMillis();

        // 1. 收集启用的通道
        List<SearchChannel> enabledChannels = channels.stream()
                .filter(c -> c.isEnabled(context))
                .toList();

        if (enabledChannels.isEmpty()) {
            log.warn("[Retrieval] 没有启用的检索通道");
            return RetrievalResult.empty();
        }

        // 2. 并行执行各通道
        List<String> channelNameList = enabledChannels.stream().map(SearchChannel::getName).toList();
        log.info("[多通道检索] 启用通道: {}", channelNameList);
        long channelTimeoutMs = 5000;
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(ch -> CompletableFuture.supplyAsync(() -> {
                    try {
                        long ts = System.currentTimeMillis();
                        SearchChannelResult r = ch.search(context);
                        log.info("[检索通道][{}] 完成, 命中={}条, 耗时={}ms", ch.getName(), r.getChunks().size(), System.currentTimeMillis() - ts);
                        return r;
                    } catch (Exception e) {
                        log.error("[检索通道][{}] 异常", ch.getName(), e);
                        return SearchChannelResult.builder()
                                .channelType(ch.getType())
                                .channelName(ch.getName())
                                .chunks(List.of())
                                .latencyMs(0)
                                .build();
                    }
                }, ragContextExecutor)
                                .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                                .exceptionally(ex -> {
                                    log.error("[检索通道][{}] 超时({}ms), 丢弃该通道结果", ch.getName(), channelTimeoutMs);
                                    return SearchChannelResult.builder()
                                            .channelType(ch.getType())
                                            .channelName(ch.getName())
                                            .chunks(List.of())
                                            .latencyMs(0)
                                            .build();
                                })
                )
                .toList();

        List<SearchChannelResult> allResults = futures.stream()
                .map(CompletableFuture::join)
                .toList();


        // 3. 合并所有 chunks
        List<RetrievedChunk> merged = allResults.stream()
                .flatMap(r -> r.getChunks().stream())
                .toList();

        log.info("[多通道检索] 原始召回合计 {} 条（{} 个通道）, 耗时={}ms", merged.size(), enabledChannels.size(), System.currentTimeMillis() - t0);

        // 4. 执行后处理器链
        List<SearchResultPostProcessor> sortedProcessors = postProcessors.stream()
                .filter(p -> p.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        List<String> enabledProcessorNames = sortedProcessors.stream().map(SearchResultPostProcessor::getName).toList();
        log.info("[多通道检索] 后处理器链: {}", enabledProcessorNames);

        List<RetrievedChunk> processed = new ArrayList<>(merged);
        for (SearchResultPostProcessor processor : sortedProcessors) {
            long tp = System.currentTimeMillis();
            int inputSize = processed.size();
            try {
                processed = processor.process(processed, allResults, context);
                log.info("[后处理][{}] 输入={}条, 输出={}条, 耗时={}ms",
                        processor.getName(), inputSize, processed.size(),
                        System.currentTimeMillis() - tp);
            } catch (Exception e) {
                log.error("[后处理][{}] 异常", processor.getName(), e);
            }
        }

        // 5. 附上排序序号
        for (int i = 0; i < processed.size(); i++) {
            processed.get(i).setRank(i + 1);
        }

        log.info("[多通道检索] 最终结果 {} 条, 总耗时={}ms", processed.size(), System.currentTimeMillis() - t0);
        for (int i = 0; i < Math.min(processed.size(), 5); i++) {
            RetrievedChunk c = processed.get(i);
            String src = c.getMetadata() != null ? (String) c.getMetadata().getOrDefault("doc_name", "?") : "?";
            log.info("[多通道检索]   #{} 相关度={} RRF分={} 来源={} 预览=\"{}\"",
                    c.getRank(),
                    c.getOriginalScore() != null ? String.format("%.4f", c.getOriginalScore()) : "N/A",
                    c.getScore() != null ? String.format("%.4f", c.getScore()) : "N/A",
                    src, truncate(c.getContent(), 60));
        }
        if (processed.size() > 5) {
            log.info("[多通道检索]   ... 还有 {} 条", processed.size() - 5);
        }

        return new RetrievalResult(allResults, processed, System.currentTimeMillis() - t0);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 检索结果，包含原始通道结果和处理后的最终结果。
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class RetrievalResult {
        private List<SearchChannelResult> channelResults;
        private List<RetrievedChunk> finalChunks;
        private long totalLatencyMs;

        public static RetrievalResult empty() {
            return new RetrievalResult(List.of(), List.of(), 0);
        }

        public boolean isEmpty() {
            return finalChunks == null || finalChunks.isEmpty();
        }
    }
}
