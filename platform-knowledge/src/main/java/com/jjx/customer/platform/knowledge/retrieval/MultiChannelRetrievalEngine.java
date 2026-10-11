package com.jjx.customer.platform.knowledge.retrieval;

import com.jjx.customer.platform.knowledge.postprocessor.SearchResultPostProcessor;
import com.jjx.customer.platform.common.util.TextPreviews;
import com.jjx.customer.platform.config.properties.ChatProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import jakarta.annotation.PostConstruct;

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
public class MultiChannelRetrievalEngine implements RetrievalEngine {

    private final List<SearchChannel> channels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final ChatProperties chatProperties;
    private final Executor ragContextExecutor;
    /** 软依赖：无 actuator/追踪装配时缺席，通道名册随之静默跳过 */
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

    /** 责任链顺序固定（getOrder 不随请求变），启动期排好一次即可（原实现每请求 filter+sorted） */
    @PostConstruct
    void sortProcessorChain() {
        postProcessors.sort(Comparator.comparingInt(SearchResultPostProcessor::getOrder));
    }

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
        long channelTimeoutMs = chatProperties.getRetrieval().getChannelTimeoutMs();
        // 通道结局名册：span 正常 OK 完成是降级设计使然，超时/失败的通道只在日志里的话，
        // 追踪侧一次静默降级与一次全量成功在 span 上同形（ragent 项目实测教训：批内失败不能被成功盖掉）
        Map<String, String> channelOutcomes = new ConcurrentHashMap<>();
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(ch -> CompletableFuture.supplyAsync(() -> {
                    try {
                        long ts = System.currentTimeMillis();
                        SearchChannelResult r = ch.search(context);
                        channelOutcomes.put(ch.getName(), "ok");
                        log.info("[检索通道][{}] 完成, 命中={}条, 耗时={}ms", ch.getName(), r.getChunks().size(), System.currentTimeMillis() - ts);
                        return r;
                    } catch (Exception e) {
                        channelOutcomes.put(ch.getName(), "failed");
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
                                    // 区分超时与真实故障：通道业务异常已在上面 catch，走到这里的基本是超时；
                                    // 但 orTimeout 不中断底层调用（JDBC/HTTP 仍占用池线程），日志须如实反映
                                    if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
                                        channelOutcomes.put(ch.getName(), "timeout");
                                        log.warn("[检索通道][{}] 超时({}ms), 丢弃该通道结果", ch.getName(), channelTimeoutMs);
                                    } else {
                                        channelOutcomes.put(ch.getName(), "failed");
                                        log.error("[检索通道][{}] 失败, 丢弃该通道结果", ch.getName(), ex);
                                    }
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

        recordChannelOutcomes(channelOutcomes, channelNameList);


        // 3. 合并所有 chunks
        List<RetrievedChunk> merged = allResults.stream()
                .flatMap(r -> r.getChunks().stream())
                .toList();

        log.info("[多通道检索] 原始召回合计 {} 条（{} 个通道）, 耗时={}ms", merged.size(), enabledChannels.size(), System.currentTimeMillis() - t0);

        // 4. 执行后处理器链（构造期已按 order 排好，此处只过滤 isEnabled）
        List<SearchResultPostProcessor> sortedProcessors = postProcessors.stream()
                .filter(p -> p.isEnabled(context))
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
                    src, TextPreviews.truncate(c.getContent(), 60));
        }
        if (processed.size() > 5) {
            log.info("[多通道检索]   ... 还有 {} 条", processed.size() - 5);
        }

        return new RetrievalResult(allResults, processed, System.currentTimeMillis() - t0);
    }

    /**
     * 把通道结局名册写进当前 step 的 span 属性（@TelemetryStep 的 observation scope 内）：
     * {@code rag.retrieval.channels}={@code name=state,...} 按启用顺序逐通道可读，
     * {@code rag.retrieval.degraded} 标记任一通道非 ok（低基数，可聚合降级率）。
     * 追踪未开（registry 缺席）或不在 step 上下文（current 为 null/noop）时静默跳过，不影响检索本身。
     */
    private void recordChannelOutcomes(Map<String, String> outcomes, List<String> channelOrder) {
        if (outcomes.isEmpty()) {
            return;
        }
        ObservationRegistry registry = observationRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Observation current = registry.getCurrentObservation();
        if (current == null || current.isNoop()) {
            return;
        }
        String roster = channelOrder.stream()
                .map(name -> name + "=" + outcomes.getOrDefault(name, "unknown"))
                .collect(Collectors.joining(","));
        boolean degraded = outcomes.values().stream().anyMatch(state -> !"ok".equals(state));
        current.getContext()
                .addHighCardinalityKeyValue(KeyValue.of("rag.retrieval.channels", roster))
                .addLowCardinalityKeyValue(KeyValue.of("rag.retrieval.degraded", String.valueOf(degraded)));
    }


    /**
     * 检索结果，包含原始通道结果和处理后的最终结果。
     * <p>@NoArgsConstructor 供缓存层 Jackson 反序列化（@Data setter 填充）；
     * {@link #isEmpty()} 是派生属性，@JsonIgnore 掉——否则 Jackson 按 bean 规约会把它
     * 序列化成 "empty" 字段，反序列化时因无 setter 报 Unrecognized field。</p>
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class RetrievalResult {
        private List<SearchChannelResult> channelResults;
        private List<RetrievedChunk> finalChunks;
        private long totalLatencyMs;

        public static RetrievalResult empty() {
            return new RetrievalResult(List.of(), List.of(), 0);
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isEmpty() {
            return finalChunks == null || finalChunks.isEmpty();
        }
    }
}
