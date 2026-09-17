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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Langfuse 推送 sink（官方 dataset-run canonical 模式的编排层）：
 * 每条 item 完成后 —— ① {@code linkRunItem} 把 item 的 eval trace 挂进 dataset run
 * （runName=eval-run-{runId}，Langfuse 自动建 run）；② 每个指标 {@code submitScore}
 * （挂 traceId + datasetRunId，comment 带文件名比对明细）。Langfuse Datasets → Runs
 * 即可看跨 run 的实验对比报表。
 * <p>推送 fire-and-forget 虚拟线程（HTTP 不拖慢跑批）；无凭据（服务器部署）/禁用/条目同步失败
 * 均静默跳过；任何异常 warn 节流，绝不阻断。traceId 为空（trace 未开）的条目跳过。
 */
@Slf4j
@Component
public class LangfusePushSink implements EvalResultSink {

    private static final long WARN_THROTTLE_MS = 60_000L;

    private final ObjectProvider<LangfuseScoreClient> scoreClientProvider;
    private final ObjectProvider<LangfuseDatasetClient> datasetClientProvider;
    private final LangfuseDatasetSyncer syncer;
    private final EvalProperties evalProperties;

    private volatile long lastWarnAt = 0L;

    public LangfusePushSink(ObjectProvider<LangfuseScoreClient> scoreClientProvider,
                            ObjectProvider<LangfuseDatasetClient> datasetClientProvider,
                            LangfuseDatasetSyncer syncer,
                            EvalProperties evalProperties) {
        this.scoreClientProvider = scoreClientProvider;
        this.datasetClientProvider = datasetClientProvider;
        this.syncer = syncer;
        this.evalProperties = evalProperties;
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
        LangfuseScoreClient scoreClient = scoreClientProvider.getIfAvailable();
        LangfuseDatasetClient datasetClient = datasetClientProvider.getIfAvailable();
        if (scoreClient == null || datasetClient == null) {
            return;
        }
        String traceId = sample.traceId();
        String runName = "eval-run-" + sample.runId();
        Thread.ofVirtual().name("langfuse-push-", 0).start(() -> {
            try {
                String lfItemId = syncer.resolve(sample.datasetName(), sample.itemId(), sample.question(),
                        sample.expectedAnswer(), sample.expectedDocIds(), sample.expectedDocNames());
                if (lfItemId == null) {
                    return;
                }
                LangfuseRunItem runItem = datasetClient.linkRunItem(
                        new LangfuseRunItemLink(runName, lfItemId, traceId, null));
                String datasetRunId = runItem != null ? runItem.datasetRunId() : null;
                for (EvalScore score : scores) {
                    scoreClient.submitScore(new LangfuseScoreSubmission(
                            score.name(), score.value(), score.comment(), datasetRunId, traceId, null));
                }
            } catch (Exception e) {
                warnThrottled(sample, e);
            }
        });
    }

    private boolean pushEnabled() {
        return evalProperties.getLangfuse().isEnabled() && syncer.available();
    }

    private void warnThrottled(EvalSample sample, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_THROTTLE_MS) {
            lastWarnAt = now;
            log.warn("[LangfusePush] 推送失败（60s 内不重复告警）run={}, item={}: {}",
                    sample.runId(), sample.itemId(), e.getMessage());
        }
    }
}
