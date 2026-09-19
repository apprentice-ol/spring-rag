package com.jjx.customer.platform.eval.sink;

import com.jjx.ai.llmobservability.backends.langfuse.LangfuseDatasetClient;
import com.jjx.ai.llmobservability.backends.langfuse.LangfuseScoreClient;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseRunItem;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseRunItemLink;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseScoreSubmission;
import com.jjx.customer.platform.eval.config.EvalProperties;
import com.jjx.customer.platform.eval.framework.EvalRunContext;
import com.jjx.customer.platform.eval.framework.EvalResultSink;
import com.jjx.customer.platform.eval.framework.EvalSample;
import com.jjx.customer.platform.eval.framework.EvalScore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Langfuse 推送 sink（官方 dataset-run canonical 模式的编排层）：
 * 每条 item 完成后 —— ① {@code linkRunItem} 把 item 的 eval trace 挂进 dataset run
 * （runName=eval-run-{runId}，Langfuse 自动建 run）；② 每个指标 {@code submitScore}
 * （挂 traceId + datasetRunId，comment 带文件名比对明细）。Langfuse Datasets → Runs
 * 即可看跨 run 的实验对比报表。
 * <p>推送 fire-and-forget 不拖慢跑批；无凭据（服务器部署）/禁用/条目同步失败
 * 均静默跳过；任何异常 warn 节流，绝不阻断。traceId 为空（trace 未开）的条目跳过。
 *
 * <p><b>线程与内存（2026-09-18）</b>：原先每条 item 现开一个虚拟线程、闭包直接捕获整个
 * {@link EvalSample}（含 finalChunks / 完整 context / 22KB agentTrace）。Langfuse 一旦变慢，
 * 895 条 item 就是 895 个线程各自钉住一份样本——无界、无背压，评测跑批期堆内存的主要放大器之一。
 * 改为固定 {@value #WORKER_COUNT} 个 worker + 有界队列，且任务只捕获推送必需的小字段。</p>
 */
@Slf4j
@Component
public class LangfusePushSink implements EvalResultSink {

    private static final long WARN_THROTTLE_MS = 60_000L;

    /** 推送队列容量：Langfuse 正常（毫秒级响应）时一次全量 run（895 条）全程不丢；只有外推严重阻塞才丢。 */
    private static final int QUEUE_CAPACITY = 4096;

    /** 推送 worker 数（虚拟线程，阻塞在 HTTP 上）：8 路足以把一次全量 run 的 ~9000 次调用压在秒级。 */
    private static final int WORKER_COUNT = 8;

    /** 停机时等待在途推送的上限 */
    private static final long SHUTDOWN_WAIT_SECONDS = 10L;

    private final ObjectProvider<LangfuseScoreClient> scoreClientProvider;
    private final ObjectProvider<LangfuseDatasetClient> datasetClientProvider;
    private final LangfuseDatasetSyncer syncer;
    private final EvalProperties evalProperties;

    /** 有界队列 + 固定 worker：队列满即丢弃（记数 + 节流告警），绝不反压跑批、也不无界堆积。 */
    private final ThreadPoolExecutor pushExecutor;
    private final AtomicLong droppedCount = new AtomicLong();

    private volatile long lastWarnAt = 0L;

    public LangfusePushSink(ObjectProvider<LangfuseScoreClient> scoreClientProvider,
                            ObjectProvider<LangfuseDatasetClient> datasetClientProvider,
                            LangfuseDatasetSyncer syncer,
                            EvalProperties evalProperties) {
        this.scoreClientProvider = scoreClientProvider;
        this.datasetClientProvider = datasetClientProvider;
        this.syncer = syncer;
        this.evalProperties = evalProperties;
        this.pushExecutor = new ThreadPoolExecutor(WORKER_COUNT, WORKER_COUNT,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                Thread.ofVirtual().name("langfuse-push-", 0).factory(),
                (task, executor) -> this.onQueueFull());
    }

    /**
     * 推送任务：只捕获推送必需的小字段。
     * <p>刻意不持有 {@link EvalSample}——样本带着 finalChunks / 完整 context / agentTrace
     * （单条可达数百 KB），捕获它等于在队列和 worker 里钉住整份评测轨迹。</p>
     */
    private record PushTask(String runName, String traceId, String datasetName, Long itemId,
                            String question, String expectedAnswer,
                            List<String> expectedDocIds, List<String> expectedDocNames,
                            List<EvalScore> scores) {
    }

    @Override
    public void onRunStart(EvalRunContext ctx) {
        if (!pushEnabled()) {
            return;
        }
        // 预热：确保 dataset 存在（异步，不挡跑批启动）
        Thread.ofVirtual().name("langfuse-sync-", 0).start(() ->
                syncer.ensureLoaded(ctx.datasetName()));
    }

    @Override
    public void onItemResult(EvalSample sample, List<EvalScore> scores) {
        if (!pushEnabled() || scores.isEmpty() || sample.traceId() == null) {
            return;
        }
        PushTask task = new PushTask(
                "eval-run-" + sample.runId(), sample.traceId(), sample.datasetName(), sample.itemId(),
                sample.question(), sample.expectedAnswer(),
                sample.expectedDocIds(), sample.expectedDocNames(), scores);
        // 包一层提交：PushTask 只承载推送字段，真正外推由固定 worker 执行 push()
        pushExecutor.execute(() -> push(task));
    }

    /** worker 执行体：挂 dataset run + 逐指标提交 score，全异常自吞（推送失败绝不阻断跑批）。 */
    private void push(PushTask task) {
        try {
            LangfuseScoreClient scoreClient = scoreClientProvider.getIfAvailable();
            LangfuseDatasetClient datasetClient = datasetClientProvider.getIfAvailable();
            if (scoreClient == null || datasetClient == null) {
                return;
            }
            String lfItemId = syncer.resolve(task.datasetName(), task.itemId(), task.question(),
                    task.expectedAnswer(), task.expectedDocIds(), task.expectedDocNames());
            if (lfItemId == null) {
                return;
            }
            LangfuseRunItem runItem = datasetClient.linkRunItem(
                    new LangfuseRunItemLink(task.runName(), lfItemId, task.traceId(), null));
            String datasetRunId = runItem != null ? runItem.datasetRunId() : null;
            for (EvalScore score : task.scores()) {
                scoreClient.submitScore(new LangfuseScoreSubmission(
                        score.name(), score.value(), score.comment(), datasetRunId, task.traceId(), null));
            }
        } catch (Exception e) {
            warnThrottled(task, e);
        }
    }

    /** 队列满：丢弃该条推送（Langfuse 是外部可视化，指标已落库；丢推送不影响评测结果），节流告警。 */
    private void onQueueFull() {
        long dropped = droppedCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_THROTTLE_MS) {
            lastWarnAt = now;
            log.warn("[LangfusePush] 推送队列已满（容量 {}），累计丢弃 {} 条推送；"
                            + "Langfuse 侧数据将不完整，评测指标本身不受影响。请检查 Langfuse 可达性与响应速度",
                    QUEUE_CAPACITY, dropped);
        }
    }

    private boolean pushEnabled() {
        return evalProperties.getLangfuse().isEnabled() && syncer.available();
    }

    private void warnThrottled(PushTask task, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_THROTTLE_MS) {
            lastWarnAt = now;
            log.warn("[LangfusePush] 推送失败（60s 内不重复告警）item={}: {}",
                    task.itemId(), e.getMessage());
        }
    }

    /** 停机：给在途推送留一点时间，超时放弃（不阻塞应用关闭）。 */
    @PreDestroy
    void shutdown() throws InterruptedException {
        pushExecutor.shutdown();
        if (!pushExecutor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("[LangfusePush] 停机：{}s 内仍有 {} 条推送未完成，放弃",
                    SHUTDOWN_WAIT_SECONDS, pushExecutor.getQueue().size());
            pushExecutor.shutdownNow();
        }
    }
}
