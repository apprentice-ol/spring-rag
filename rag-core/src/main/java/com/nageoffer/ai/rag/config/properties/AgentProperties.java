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

    /** 默认 agent 范式（naive / react） */
    private String paradigm = "naive";

    /** grade 相关性阈值：chunk score >= 该值视为 relevant */
    private double gradeThreshold = 0.5;

    /** ReAct 最大循环步数（防死循环的硬上限） */
    private int reactMaxSteps = 6;

    /** 解析 paradigm 字段为枚举，非法值回退 NAIVE。 */
    public RagParadigm paradigmEnum() {
        return RagParadigm.parse(paradigm, RagParadigm.NAIVE);
    }
}
