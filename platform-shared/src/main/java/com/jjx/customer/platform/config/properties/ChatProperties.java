package com.jjx.customer.platform.config.properties;

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

    /** 多轮上下文（历史对话）取数规格 */
    private History history = new History();

    /** Redis 断路器降级期间，昂贵路径（LLM 流式 / ops 诊断）的并发上限（DegradeGuard） */
    private int degradeLimit = 8;

    /**
     * 意图路由进运维诊断的置信度门槛：自动档下 needs_diagnose=true 但置信度低于此值不劫持，
     * 落回知识检索（"识别不到 = 知识检索"的兜底）。显式选择范式不受此门槛影响。
     */
    private double diagnoseMinConfidence = 0.6;

    /**
     * 多轮上下文（历史对话）取数规格。
     *
     * <p>同时喂给<b>查询改写</b>（消解指代去检索）与<b>答案生成</b>（知道"它"指什么）。
     * 两处用同一个窗口是有意的：窗口不一致时会出现"检索按 3 轮理解、生成按 1 轮理解"的割裂，
     * 排查起来只能靠猜。真要紧缩，也先紧缩到同一份规格上。</p>
     */
    @Data
    public static class History {

        /** 保留轮数（一轮 = 用户 + 助手各一条）。0 或负数按 1 处理。 */
        private int rounds = 3;

        /**
         * 历史<b>总字符预算</b>（不是单条上限）。装不下时从最早的整条丢弃；
         * 只有最新一条允许截断（并带显式截断标注）。
         *
         * <p>曾经是"每条 500 字"——那条规则只适合"历史当线索"的查询改写，而历史同时要喂给
         * 生成与闲聊（那时它是<b>操作对象</b>：翻译、总结上一轮回复）。按条切会让模型拿到
         * 半截内容还得照做，实测一条 703 字的回答被砍在第 4 条中间，模型只能回"原文被截断了"。
         * 默认 4000 是一个够装 5~6 条 700 字回答的量级。</p>
         */
        private int totalChars = 4000;
    }

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
