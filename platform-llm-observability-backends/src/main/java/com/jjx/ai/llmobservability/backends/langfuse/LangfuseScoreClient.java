package com.jjx.ai.llmobservability.backends.langfuse;

import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseScoreSubmission;

/**
 * Langfuse 评分写入能力。
 *
 * <p>接口化是为了后端升级可替换实现：当前 v3 由 {@link LangfuseApiClient} 实现，
 * 未来 v4 评分 API 变化时新增实现并替换 Bean 即可，调用方不感知。</p>
 */
public interface LangfuseScoreClient {

    /**
     * 提交一条评分（写回数据集 run / trace）。
     *
     * @param submission 评分内容
     * @return 创建的评分 id
     */
    String submitScore(LangfuseScoreSubmission submission);
}
