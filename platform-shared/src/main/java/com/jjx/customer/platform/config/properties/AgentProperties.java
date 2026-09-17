package com.jjx.customer.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 范式配置，绑定 application.yaml 的 rag.chat.agent.*。
 * <p>与 {@link ChatProperties} 分离，职责清晰：ChatProperties 管检索参数，AgentProperties 管 agent 编排策略。
 * <p>全局默认范式由 {@link #paradigm} 决定；请求级覆盖（?agent=xxx）在 ChatOrchestrator 接入时支持。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.chat.agent")
public class AgentProperties {

    /** 默认 agent 范式（knowledge / ops_diagnose / react_loop；旧值 naive/react 自动映射） */
    private String paradigm = "knowledge";

    /** grade 相关性阈值：chunk score >= 该值视为 relevant */
    private double gradeThreshold = 0.5;

    /** ReAct 最大循环步数（防死循环的硬上限） */
    private int reactMaxSteps = 6;

    /** 工具循环协议：native = 原生 function calling；json = JSON 决策降级逃生门 */
    private String toolCallMode = "native";

    /** 阶段默认循环步数 */
    private int defaultMaxSteps = 4;

    /** 连续非法工具调用（未知工具/参数损坏）上限，超出强制出环 */
    private int maxInvalidToolCalls = 2;

    /** 单次 LLM 调用超时（秒） */
    private int loopCallTimeoutSeconds = 60;

    /** 追问会话状态 TTL（分钟，所有 workflow agent 通用）：超时未回复的 AWAITING_USER 会话过期。
     *  null = 未配置，回退 ops.session-ttl-minutes（旧位置兼容），再回退 60。 */
    private Integer sessionTtlMinutes;

    /** workflow 引擎通用配置（所有 WorkflowAgent 共享的横切默认，definition 可按位覆盖） */
    private Workflow workflow = new Workflow();

    /** 运维诊断（ops_diagnose）专属配置 */
    private Ops ops = new Ops();

    /** 会话 TTL 生效值：顶层 > ops 段（旧位置兼容）> 默认 60 分钟。 */
    public int sessionTtlMinutesEffective() {
        if (sessionTtlMinutes != null && sessionTtlMinutes > 0) {
            return sessionTtlMinutes;
        }
        return ops != null ? ops.getSessionTtlMinutes() : 60;
    }

    /** 缺省范式标识（非法/空白回退 knowledge）；主线按"意图域 → Agent"路由，此处作兜底与日志口径。 */
    public String paradigmCode() {
        return paradigm == null || paradigm.isBlank() ? "knowledge" : paradigm.trim();
    }

    /** workflow 引擎通用段（预算默认，definition 未声明位时生效）。 */
    @Data
    public static class Workflow {
        /** workflow 级 LLM 调用合计上限（防多阶段×多步失控；超限 ESCALATE 收尾） */
        private int maxLlmCalls = 24;
        /** workflow 级总时长上限（秒） */
        private int timeoutSeconds = 300;
    }

    /** ops 配置段。 */
    @Data
    public static class Ops {
        /** 追问会话状态 TTL（分钟），超时未回复的 AWAITING_USER 会话过期 */
        private int sessionTtlMinutes = 60;
        /** 生产环境写操作是否需要用户确认 */
        private boolean prodConfirmRequired = true;
        /** 阶段运行参数覆盖（空 = 代码默认；结构字段不可 yaml 改——代码即真相） */
        private java.util.List<StageConfig> stages = new java.util.ArrayList<>();
    }

    /**
     * 单阶段运行参数覆盖（yaml 用）。按 name 匹配代码默认阶段，只覆盖运行参数；
     * 未知 name 忽略并告警。结构字段（promptKey/工具白名单/when/guard）不在此。
     */
    @Data
    public static class StageConfig {
        private String name;
        private int maxSteps = 4;
        private boolean replanAfter = true;
        /** 失败策略：retry:2 / skip / escalate / fail / 空 = as-is（见 {@code ErrorPolicy#parse}） */
        private String errorPolicy;
    }
}
