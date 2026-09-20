package com.agentframework.definition.workflow;

import java.util.List;

/**
 * 人机协作协议：引擎向人请求一次决议（挂起时随事件透出，恢复时由人交回 {@link HumanResponse}）。
 *
 * <p>统一原语：问信息（CLARIFY）/ 决策移交（DECIDE）/ 计划审批（APPROVE）/ 关键确认（CONFIRM）
 * 都是「挂起-恢复」的变体——执行器经 {@code NodeResult.suspended} 挂起时把本请求暂存进槽位
 * （约定键 {@code pending_human_request}，JSON 文本），恢复重入时配对消费后清除。</p>
 *
 * <p>骨架零业务：本协议不感知任何域的槽位目录；{@link SlotAsk} 由调用方从自己的目录声明渲染。</p>
 *
 * @param kind          请求类别
 * @param prompt        问句正文（前端无结构化渲染能力时的兜底文本）
 * @param slots         涉及的槽位（CLARIFY 问信息时非空；DECIDE 通常为空）
 * @param context       决策上下文（DECIDE/APPROVE：证据摘要，让人不必重看全程就能决策）
 * @param options       点选项（空 = 纯文本回答；非空时前端渲染按钮，点击回传 {@code #decision:<value>}）
 * @param allowFreeText 是否允许自由文本（CONFIRM 场景可关闭）
 */
public record HumanRequest(Kind kind, String prompt, List<SlotAsk> slots,
                           DecisionContext context, List<Choice> options, boolean allowFreeText) {

    /** 挂起暂存槽位名（非空即「已向人请求决议，等回复」；与 pending_ask 模式同构）。 */
    public static final String PENDING_SLOT = "pending_human_request";

    public HumanRequest {
        if (kind == null) {
            throw new IllegalArgumentException("human request kind is required");
        }
        prompt = prompt == null ? "" : prompt;
        slots = List.copyOf(slots == null ? List.of() : slots);
        options = List.copyOf(options == null ? List.of() : options);
    }

    /** 请求类别。 */
    public enum Kind {
        /** 问信息：缺失槽位一次问齐（AskMissing / 环内 ask_user）。 */
        CLARIFY,
        /** 决策移交：模型卡住/证据矛盾时把决策权交给人，人的回复让流程继续而非终止。 */
        DECIDE,
        /** 计划审批：阶段计划先给人批准/修改再执行（低自主档位）。 */
        APPROVE,
        /** 关键确认：高影响动作执行前的确认。 */
        CONFIRM
    }

    /**
     * 问信息请求（CLARIFY）。
     *
     * @param prompt 问句正文
     * @param slots  缺失槽位清单
     * @return 请求实例
     */
    public static HumanRequest clarify(String prompt, List<SlotAsk> slots) {
        return new HumanRequest(Kind.CLARIFY, prompt, slots, null, List.of(), true);
    }

    /**
     * 问信息请求（CLARIFY），带补充说明（已自动补全项等，随卡片透出供用户纠错）。
     *
     * @param prompt  问句正文
     * @param slots   缺失槽位清单
     * @param context 补充说明（可为 null）
     * @return 请求实例
     */
    public static HumanRequest clarify(String prompt, List<SlotAsk> slots, DecisionContext context) {
        return new HumanRequest(Kind.CLARIFY, prompt, slots, context, List.of(), true);
    }

    /**
     * 决策移交请求（DECIDE）：带证据上下文与点选项；终止是选项之一而非默认结局。
     *
     * @param prompt  决策问句
     * @param context 证据上下文（可为 null）
     * @param options 点选项
     * @return 请求实例
     */
    public static HumanRequest decide(String prompt, DecisionContext context, List<Choice> options) {
        return new HumanRequest(Kind.DECIDE, prompt, List.of(), context, options, true);
    }

    /** CLARIFY 的单条槽位问询（目录声明或模型运行期综合判断声明的渲染投影）。 */
    public record SlotAsk(String name, String label, String hint, String value,
                          String provenance, List<String> options, boolean dynamic, String evidence) {

        /** 兼容构造（无提示/无候选，目录槽）。 */
        public SlotAsk(String name, String label, String value, String provenance, List<String> options) {
            this(name, label, null, value, provenance, options, false, null);
        }

        /** 兼容构造（无提示，目录槽）。 */
        public SlotAsk(String name, String label, String hint, String value,
                       String provenance, List<String> options) {
            this(name, label, hint, value, provenance, options, false, null);
        }

        /**
         * 兼容构造（带来源、不带依据）。
         *
         * @param name       槽位名
         * @param label      展示问句
         * @param hint       填写提示
         * @param value      已补全的值（null = 缺失待补）
         * @param provenance 来源（见 SlotProvenance）
         * @param options    候选值
         * @param dynamic    是否模型运行期声明的动态槽
         */
        public SlotAsk(String name, String label, String hint, String value,
                       String provenance, List<String> options, boolean dynamic) {
            this(name, label, hint, value, provenance, options, dynamic, null);
        }

        public SlotAsk {
            options = List.copyOf(options == null ? List.of() : options);
        }

        /** @return 值是否非空（已补全的槽位以值形态展示） */
        public boolean hasValue() {
            return value != null && !value.isBlank();
        }
    }

    /**
     * DECIDE/APPROVE 的决策上下文：让人不必重看全程就能决策的最小信息集。
     *
     * <p>{@code hypotheses}（2026-09-20）：决策移交时外化的<b>竞争假设</b>——每个带验证状态与
     * 判别动作，让人看到的是完整假设空间而不是一段"卡住了"的糊状描述。可选：
     * 旧数据 / 无假设时为空列表，前端按现状渲染证据文本（降级安全）。</p>
     */
    public record DecisionContext(String summary, List<String> evidence,
                                  List<Hypothesis> hypotheses) {

        /** 兼容构造（无假设）：存量调用点与旧 JSON 反序列化产物走这里。 */
        public DecisionContext(String summary, List<String> evidence) {
            this(summary, evidence, List.of());
        }

        public DecisionContext {
            summary = summary == null ? "" : summary;
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            hypotheses = List.copyOf(hypotheses == null ? List.of() : hypotheses);
        }
    }

    /**
     * 单个竞争假设（决策移交时外化，供人判断与点选方向）。
     *
     * @param claim     假设内容（如「知识库缺该接口的字段表」）
     * @param status    验证状态：verified（已证实）/ disproved（已排除）/ unverified（待验证）
     * @param evidence  支撑或排除它的事实（一句话；空 = 尚无证据）
     * @param nextAction 判别动作——什么操作能验证/排除它。说不出判别动作的假设不允许进入
     *                  决策卡（不可证伪的假设对决策没有增量），由生产方负责过滤
     */
    public record Hypothesis(String claim, String status, String evidence, String nextAction) {

        public Hypothesis {
            claim = claim == null ? "" : claim;
            status = status == null ? "" : status.trim().toLowerCase();
            evidence = evidence == null ? "" : evidence;
            nextAction = nextAction == null ? "" : nextAction;
        }

        /** @return 是否可进入决策卡（claim 与判别动作都在，才谈得上让人裁决） */
        public boolean actionable() {
            return !claim.isBlank() && !nextAction.isBlank();
        }
    }

    /** 点选项：前端渲染按钮，点击回传 {@code #decision:<value>}；自由文本始终等效可选。 */
    public record Choice(String value, String label, String description) {

        public Choice {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("choice value is required");
            }
        }
    }
}
