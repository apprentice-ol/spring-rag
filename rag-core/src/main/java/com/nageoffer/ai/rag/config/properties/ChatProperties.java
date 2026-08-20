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

    /**
     * Rerank 相关性分数阈值：精排后低于该分的 chunk 不进 LLM 上下文（宁缺毋滥，防低相关块稀释注意力）。
     * 0 = 关闭过滤（保持旧行为）。百炼 rerank 分数 0~1，相关通常 >0.3、不相关 <0.05，0.1 为保守线。
     */
    private double rerankScoreThreshold = 0.1;

    /** 检索执行参数（通道并行池 / 超时） */
    private Retrieval retrieval = new Retrieval();

    @Data
    public static class Retrieval {

        /**
         * 单通道检索超时（毫秒）。注意 orTimeout 从任务<b>提交</b>起算（含排队），
         * 超时通道的结果被整体丢弃、其余通道照常融合。
         */
        private long channelTimeoutMs = 5000;

        /**
         * 检索并行执行器线程数（core=max 同值）。应 ≥ 峰值并发请求数 × 每请求通道数：
         * 通道任务是短阻塞 IO（JdbcTemplate + HTTP rerank），池小了任务在队列里排队，
         * orTimeout 会在执行开始前就把结果判超时丢弃（检索质量静默退化）。
         */
        private int executorPoolSize = 8;
    }
}
