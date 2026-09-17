package com.jjx.customer.platform.eval.sink;

import com.jjx.ai.llmobservability.backends.langfuse.LangfuseDatasetClient;
import com.jjx.ai.llmobservability.backends.langfuse.LangfuseScoreClient;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseDatasetItem;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseDatasetRun;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseRunItem;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseRunItemLink;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseScoreSubmission;
import com.jjx.customer.platform.eval.config.EvalProperties;
import com.jjx.customer.platform.eval.framework.EvalSample;
import com.jjx.customer.platform.eval.framework.EvalScore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Langfuse 推送 sink 单测（fake client + fake syncer，latch 等待 fire-and-forget 虚拟线程）：
 * link+score 携带 datasetRunId/traceId / 禁用与不可用 noop / traceId 空跳过 / 异常静默。
 */
class LangfusePushSinkTest {

    static class RecordingScoreClient implements LangfuseScoreClient {
        final List<LangfuseScoreSubmission> submissions = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile CountDownLatch latch = new CountDownLatch(0);

        @Override
        public String submitScore(LangfuseScoreSubmission submission) {
            submissions.add(submission);
            latch.countDown();
            return "ok";
        }
    }

    static class RecordingDatasetClient implements LangfuseDatasetClient {
        final List<LangfuseRunItemLink> links = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean failLinks;

        @Override
        public List<LangfuseDatasetItem> listDatasetItems(String datasetName, int pageSize) {
            return List.of();
        }

        @Override
        public LangfuseRunItem linkRunItem(LangfuseRunItemLink link) {
            if (failLinks) {
                throw new RuntimeException("langfuse down");
            }
            links.add(link);
            return new LangfuseRunItem("ri-1", "dr-123", link.runName(),
                    link.datasetItemId(), link.traceId(), null, null);
        }

        @Override
        public List<LangfuseDatasetRun> listRuns(String datasetName, int pageSize) {
            return List.of();
        }
    }

    static class FakeSyncer extends LangfuseDatasetSyncer {
        boolean available = true;
        String resolveResult = "lf-item-9";

        FakeSyncer() {
            super(null, null);
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String resolve(String datasetName, Long localItemId, String question, String expectedAnswer,
                              List<String> expectedDocIds, List<String> expectedDocNames) {
            return resolveResult;
        }

        @Override
        public void ensureLoaded(String datasetName) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        return (ObjectProvider<T>) new ObjectProvider<Object>() {
            @Override
            public Object getObject(Object... args) {
                if (value == null) {
                    throw new IllegalStateException("no bean");
                }
                return value;
            }

            @Override
            public Object getObject() {
                if (value == null) {
                    throw new IllegalStateException("no bean");
                }
                return value;
            }

            // getIfAvailable() 的默认实现走 getObject() 且只吞 NoSuchBean 例外——须直接覆写
            @Override
            public Object getIfAvailable() {
                return value;
            }
        };
    }

    private static EvalSample sample(String traceId) {
        return EvalSample.builder()
                .runId(42L).itemId(7L).datasetName("golden").traceId(traceId)
                .question("发票冲红")
                .build();
    }

    private LangfusePushSink sink(RecordingScoreClient score, RecordingDatasetClient dataset,
                                  FakeSyncer syncer, boolean enabled) {
        EvalProperties props = new EvalProperties();
        props.getLangfuse().setEnabled(enabled);
        return new LangfusePushSink(providerOf(score), providerOf(dataset), syncer, props);
    }

    @Test
    void 推送_链接与分数携带datasetRunId与traceId() throws Exception {
        RecordingScoreClient score = new RecordingScoreClient();
        RecordingDatasetClient dataset = new RecordingDatasetClient();
        score.latch = new CountDownLatch(2);
        LangfusePushSink s = sink(score, dataset, new FakeSyncer(), true);

        s.onItemResult(sample("trace-abc"), List.of(
                new EvalScore("context_recall", 0.5, "期望: 发票.md✓"),
                new EvalScore("recall_at_5", 1.0, null)));

        assertTrue(score.latch.await(3, TimeUnit.SECONDS), "推送应完成");
        assertEquals(1, dataset.links.size());
        LangfuseRunItemLink link = dataset.links.get(0);
        assertEquals("eval-run-42", link.runName());
        assertEquals("lf-item-9", link.datasetItemId());
        assertEquals("trace-abc", link.traceId());
        assertEquals(2, score.submissions.size());
        for (LangfuseScoreSubmission submission : score.submissions) {
            assertEquals("dr-123", submission.datasetRunId());
            assertEquals("trace-abc", submission.traceId());
            assertNull(submission.observationId());
        }
        assertTrue(score.submissions.stream().anyMatch(x -> "context_recall".equals(x.name())));
    }

    @Test
    void 禁用时noop() {
        RecordingScoreClient score = new RecordingScoreClient();
        LangfusePushSink s = sink(score, new RecordingDatasetClient(), new FakeSyncer(), false);
        s.onItemResult(sample("trace-1"), List.of(EvalScore.of("mrr", 0.5)));
        assertEquals(0, score.submissions.size());
    }

    @Test
    void 无凭据或syncer不可用noop() {
        RecordingScoreClient score = new RecordingScoreClient();
        FakeSyncer syncer = new FakeSyncer();
        syncer.available = false;
        LangfusePushSink s = sink(score, new RecordingDatasetClient(), syncer, true);
        s.onItemResult(sample("trace-1"), List.of(EvalScore.of("mrr", 0.5)));
        assertEquals(0, score.submissions.size());

        // 无凭据（provider 返回 null bean）同样跳过
        LangfusePushSink noCred = new LangfusePushSink(providerOf(null), providerOf(null),
                new FakeSyncer(), new EvalProperties());
        noCred.onItemResult(sample("trace-1"), List.of(EvalScore.of("mrr", 0.5)));
        assertEquals(0, score.submissions.size());
    }

    @Test
    void traceId为空或无分数跳过() {
        RecordingScoreClient score = new RecordingScoreClient();
        LangfusePushSink s = sink(score, new RecordingDatasetClient(), new FakeSyncer(), true);
        s.onItemResult(sample(null), List.of(EvalScore.of("mrr", 0.5)));
        s.onItemResult(sample("trace-2"), List.of());
        assertEquals(0, score.submissions.size());
    }

    @Test
    void 推送异常静默不外抛() throws Exception {
        RecordingScoreClient score = new RecordingScoreClient();
        RecordingDatasetClient dataset = new RecordingDatasetClient();
        dataset.failLinks = true;
        score.latch = new CountDownLatch(1);
        LangfusePushSink s = sink(score, dataset, new FakeSyncer(), true);

        s.onItemResult(sample("trace-x"), List.of(EvalScore.of("mrr", 0.5)));
        // 无异常外抛；link 失败 → 无分数推送
        Thread.sleep(300);
        assertEquals(0, score.submissions.size());
    }
}
