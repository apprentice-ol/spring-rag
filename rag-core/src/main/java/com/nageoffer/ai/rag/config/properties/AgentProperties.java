package com.nageoffer.ai.rag.config.properties;

import com.nageoffer.ai.rag.chat.agent.RagParadigm;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 范式配置，绑定 application.yaml 的 rag.chat.agent.*。
 * <p>与 {@link ChatProperties} 分离，职责清晰：ChatProperties 管检索参数，AgentProperties 管 agent 编排策略。
 * <p>全局默认范式由 {@link #paradigm} 决定；请求级覆盖（?agent=xxx）在 StreamChatPipeline 接入时支持。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.chat.agent")
public class AgentProperties {

    /** 默认 agent 范式（naive / crag / self_rag / react / plan_execute） */
    private String paradigm = "naive";

    /** CRAG/Self-RAG 检索不足时的重试次数上限 */
    private int maxRetries = 1;

    /** grade 相关性阈值：chunk score >= 该值视为 relevant */
    private double gradeThreshold = 0.5;

    /** 最小命中条数：少于该值视为检索不足（触发重试/兜底） */
    private int minHits = 2;

    /** ReAct 最大循环步数（防死循环的硬上限） */
    private int reactMaxSteps = 6;

    /** 是否启用 web 搜索兜底（WebSearchChannel 为 stub 时该开关无效，CRAG 退化为重试改写） */
    private boolean enableWebFallback = false;

    /** 解析 paradigm 字段为枚举，非法值回退 NAIVE。 */
    public RagParadigm paradigmEnum() {
        return RagParadigm.parse(paradigm, RagParadigm.NAIVE);
    }
}
