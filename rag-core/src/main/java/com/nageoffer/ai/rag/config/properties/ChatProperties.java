package com.nageoffer.ai.rag.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对话相关配置，绑定 application.yaml 的 rag.chat.*
 *
 * <p>包含检索参数、Rerank、多通道搜索等配置项。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.chat")
public class ChatProperties {

    /** 向量检索召回条数 */
    private int topK = 5;

    /** 相似度阈值（0~1，低于此分数不召回） */
    private double similarityThreshold = 0.6;

    /** 系统提示词（约束模型只依据上下文回答） */
    private String systemPrompt;

    /** 空检索时的降级回复；默认读 prompts/chat/pipeline/empty-retrieval.md，留此字段供运维覆盖 */
    private String emptyRetrievalMsg;

    /** Rerank 后最终给 LLM 的条数（与召回 topK 解耦：召回宽、精排严，默认 5） */
    private int contextTopK = 5;

    /** 每通道召回预算（fan-out 基数，想大保召回）；对标 ragent rag.search.recall-budget（20） */
    private int recallBudget = 20;

    /** RRF 融合后送入 Rerank 的候选池上限（成本天花板）；对标 ragent rag.search.fusion.rerank-candidate-limit（40） */
    private int candidateLimit = 40;
}
