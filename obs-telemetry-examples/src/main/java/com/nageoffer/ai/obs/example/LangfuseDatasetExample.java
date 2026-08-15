package com.nageoffer.ai.obs.example;

import com.nageoffer.ai.obs.backends.langfuse.LangfuseDatasetClient;
import com.nageoffer.ai.obs.backends.langfuse.LangfuseScoreClient;
import com.nageoffer.ai.obs.backends.langfuse.dto.LangfuseDatasetItem;
import com.nageoffer.ai.obs.backends.langfuse.dto.LangfuseRunItem;
import com.nageoffer.ai.obs.backends.langfuse.dto.LangfuseRunItemLink;
import com.nageoffer.ai.obs.backends.langfuse.dto.LangfuseScoreSubmission;
import com.nageoffer.ai.obs.example.support.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Langfuse 数据集/评分客户端接口的使用示例。
 *
 * <p>展示三个原语（列条目 / 关联 run item / 提交评分），以及一个综合编排参考实现
 * {@link #runDatasetEvaluation}——框架只提供原语，编排（跑问答、定位 trace、调 LLM 判分）
 * 由应用通过 {@link AnswerProvider} / {@link TraceLinkResolver} / {@link ScoreJudge} 注入。</p>
 *
 * <p><b>注入方式</b>：客户端是条件 Bean，未配置凭据时不注册，因此用
 * {@link ObjectProvider} 获取并在缺失时给出明确提示（不会导致启动失败）。</p>
 */
@Component
public class LangfuseDatasetExample {

    private static final Logger log = LoggerFactory.getLogger(LangfuseDatasetExample.class);

    private final ObjectProvider<LangfuseDatasetClient> datasetClientProvider;
    private final ObjectProvider<LangfuseScoreClient> scoreClientProvider;

    /**
     * 构造示例组件。
     *
     * @param datasetClientProvider 数据集客户端提供器（条件 Bean）
     * @param scoreClientProvider   评分客户端提供器（条件 Bean）
     */
    public LangfuseDatasetExample(ObjectProvider<LangfuseDatasetClient> datasetClientProvider,
                                  ObjectProvider<LangfuseScoreClient> scoreClientProvider) {
        this.datasetClientProvider = datasetClientProvider;
        this.scoreClientProvider = scoreClientProvider;
    }

    /**
     * 示例：列出数据集条目（黄金数据：input / expectedOutput）。
     *
     * @param datasetName 数据集名
     * @param limit       返回条数上限
     * @return 数据集条目列表
     */
    public List<LangfuseDatasetItem> exampleListItems(String datasetName, int limit) {
        LangfuseDatasetClient client = requireDatasetClient();
        return client.listDatasetItems(datasetName, limit);
    }

    /**
     * 示例：把一条模型运行记录关联到数据集 run。
     *
     * @param runName        数据集 run 名（不存在时自动创建）
     * @param datasetItemId  黄金数据集条目 id
     * @param traceId        模型运行 trace id
     * @param observationId  模型回答所在 observation id（可为 null）
     * @return 创建/更新后的 run item
     */
    public LangfuseRunItem exampleLinkRunItem(String runName, String datasetItemId,
                                              String traceId, String observationId) {
        LangfuseDatasetClient client = requireDatasetClient();
        return client.linkRunItem(new LangfuseRunItemLink(runName, datasetItemId, traceId, observationId));
    }

    /**
     * 示例：提交一条评分（写回数据集 run / trace）。
     *
     * @param name           评分名（如 Answer Correctness）
     * @param value          数值分数
     * @param comment        评分理由
     * @param datasetRunId   数据集 run id
     * @param traceId        trace id
     * @param observationId  observation id（可为 null）
     * @return 创建的评分 id
     */
    public String exampleSubmitScore(String name, double value, String comment,
                                     String datasetRunId, String traceId, String observationId) {
        LangfuseScoreClient client = requireScoreClient();
        return client.submitScore(new LangfuseScoreSubmission(
                name, value, comment, datasetRunId, traceId, observationId));
    }

    /**
     * 综合编排参考实现：跑数据集 → 关联 run item → LLM 打分写回。
     *
     * <p>这是“问题 + 模型回答 vs 黄金答案，LLM 打分”在 v3 上的完整落地骨架：
     * 应用注入三个回调即可复用（回答来自真实 RAG、trace 定位来自真实运行、判分来自真实 LLM）。</p>
     *
     * @param datasetName       数据集名
     * @param runName           数据集 run 名（实验名）
     * @param limit             本次跑多少条
     * @param answerProvider    问题 → 模型回答（替换为真实 RAG 调用）
     * @param traceLinkResolver 问题 → 运行记录定位（替换为真实 trace 反查）
     * @param scoreJudge        问题/回答/黄金答案 → 判分结果（替换为真实 LLM judge）
     */
    public void runDatasetEvaluation(String datasetName, String runName, int limit,
                                     AnswerProvider answerProvider,
                                     TraceLinkResolver traceLinkResolver,
                                     ScoreJudge scoreJudge) {
        LangfuseDatasetClient datasetClient = requireDatasetClient();
        LangfuseScoreClient scoreClient = requireScoreClient();
        List<LangfuseDatasetItem> items = datasetClient.listDatasetItems(datasetName, limit);
        log.info("[示例] 数据集 {} 取 {} 条，run={}", datasetName, items.size(), runName);
        for (LangfuseDatasetItem item : items) {
            String question = String.valueOf(item.input());
            String answer = answerProvider.apply(question);
            TraceLink link = traceLinkResolver.apply(question);
            LangfuseRunItemLink langfuseRunItemLink = new LangfuseRunItemLink(runName, item.id(), link.traceId(), link.observationId());

            LangfuseRunItem runItem = datasetClient.linkRunItem(langfuseRunItemLink);

            ScoreVerdict verdict = scoreJudge.judge(question, answer, String.valueOf(item.expectedOutput()));

            String scoreId = scoreClient.submitScore(new LangfuseScoreSubmission(
                    "Answer Correctness", verdict.value(), verdict.comment(),
                    runItem.datasetRunId(), link.traceId(), link.observationId()));
            log.info("[示例] item={} 已关联 trace={}，评分 id={}，score={}",
                    item.id(), link.traceId(), scoreId, verdict.value());
        }
    }

    private LangfuseDatasetClient requireDatasetClient() {
        LangfuseDatasetClient client = datasetClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("LangfuseDatasetClient 未注册：请配置 obs.langfuse.auth（或 public-key/secret-key）");
        }
        return client;
    }

    private LangfuseScoreClient requireScoreClient() {
        LangfuseScoreClient client = scoreClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("LangfuseScoreClient 未注册：请配置 obs.langfuse.auth（或 public-key/secret-key）");
        }
        return client;
    }
}
