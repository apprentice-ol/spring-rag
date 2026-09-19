package com.jjx.customer.platform.config.properties;

import java.util.ArrayList;
import java.util.List;
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

    /** ReAct 最大循环步数（防死循环的硬上限；新内核映射为 react Region 的 loopGuard） */
    private int reactMaxSteps = 6;

    /**
     * 检索反思轮上限：命中不足时最多扩词重查几次（knowledge 线反思 Region 的 loopGuard）。
     * 默认 3，与 agent-framework {@code RagSearchAgent} 的 {@code maxRounds} 同值——
     * 两边用同一个内核，这一拍的补救力度也不该有两套。
     */
    private int maxRewriteRounds = 3;

    /**
     * 短路直答的意图域清单：命中这些域的问题不进检索链，直接由编排层回复。
     *
     * <p><b>默认只有 {@code greeting}，刻意不含 {@code chitchat}</b>——这不是遗漏。
     * 分类器的域是按本仓语料（发票/运维/接口文档）划的，域外的问题（"影视拍摄技巧"
     * "水龙头一直滴水"）没有归属的域，会落到 {@code chitchat} 这个兜底上并给出不低的
     * 置信度。一旦 chitchat 也短路，这类问题就<b>一条资料都不检索</b>，直接由闲聊路径
     * 打发掉；而它本可以走检索、命中不到再如实说"资料里没有"。</p>
     *
     * <p>评测里这个差异是决定性的：LiveRAG 是通用知识问题集，86 道题里有 6 道被判成
     * chitchat 短路，直接记 0 分——分数掉的是分类器的域覆盖，不是检索质量。</p>
     *
     * <p>想完全关掉短路把它设成空清单；想恢复旧行为加上 {@code chitchat} 即可。</p>
     */
    private List<String> shortCircuitDomains = new ArrayList<>(List.of("greeting"));

    /** 追问会话状态 TTL（分钟，所有 workflow agent 通用）：超时未回复的 AWAITING_USER 会话过期。
     *  null = 未配置，回退 ops.session-ttl-minutes（旧位置兼容），再回退 60。 */
    private Integer sessionTtlMinutes;

    /** workflow 引擎通用配置（所有 WorkflowAgent 共享的横切默认，definition 可按位覆盖） */
    private Workflow workflow = new Workflow();

    /** 运维诊断（ops_diagnose）专属配置 */
    private Ops ops = new Ops();

    /** 检索反思环的充分性判据配置 */
    private Critique critique = new Critique();

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
    }

    /**
     * 充分性判据（critique）配置段——决定"检索到什么程度算够，不用再重查"。
     *
     * <p><b>条数下限是语料相关的，所以做成配置而不是常量。</b>各语料的"每题平均支撑文档数"
     * 差异极大：单跳语料（NQ / TriviaQA / PopQA）为 1，HotpotQA 2.4、MuSiQue 2.6、
     * 2WikiMultihopQA 4.9。Adaptive-RAG 的实测更直接：SQuAD 上单步检索胜出（27.80 vs 24.40 EM，
     * 且省 9 倍成本），MuSiQue 上排序完全反转（13.80 vs 23.00）——<b>同一套判据在两个语料上
     * 一个该停、一个该继续</b>，没有通用常数。</p>
     */
    @Data
    public static class Critique {

        /**
         * 充分所需的最少命中条数。
         *
         * <p>LiveRAG（本仓评测语料）取 <b>1</b>：实测 895 题，进入反思环的 97 题里
         * <b>93 题每题只需 1 篇文档</b>，命中 1 条本就是满分；7 道进环且 0 分的题里，
         * 5 道首轮就命中了且相关度都在下限之上（最高 0.8321），没有一道是"首轮真的不够"。
         * 原值与 agent-framework 同为 2，是把这个常数当成了与问题信息需求无关的充分性判据。</p>
         *
         * <p>⚠️ <b>换语料务必重估</b>：多跳语料要调高，否则会在只拿到第 1 跳文档时就停下。
         * 另注：这一项是"基准特化"的权宜（LiveRAG 调优），与"跨轮累积"那种结构性修复不同——
         * 后者让判错不具破坏性，与语料无关。</p>
         */
        private int requiredHits = 1;

        /**
         * 首条命中的相关度下限（精排之后的分数）。
         *
         * <p>条数判据放宽到 1 之后，这条是主要的精度防线——与精排的
         * {@code rag.rerank.min-relevance-score} 共同把关"召回的东西本身像不像"。</p>
         */
        private double minTopScore = 0.3;
    }
}
