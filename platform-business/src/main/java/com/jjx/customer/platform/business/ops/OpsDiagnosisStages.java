package com.jjx.customer.platform.business.ops;

import java.util.List;
import java.util.Set;

/**
 * 诊断三阶段的<b>单一事实源</b>：一个阶段的名字、Prompt 正文、工具协议清单、循环上限、
 * 出口护栏与流式标记全部在此单点声明。
 *
 * <ul>
 *   <li>{@link OpsDiagnoseWorkflowFactory} 由此派生图的节点 / 边 / Region / 槽位模板；</li>
 *   <li>{@link OpsDiagnosisAgent} 由此派生 Prompt 资产、Act / Replan 执行器与 loopGuard 注册。</li>
 * </ul>
 *
 * <p>新增或调整一个阶段<b>只改本文件</b>；节点 id、执行器注册名、守卫名、Region id、
 * Prompt 资产 id、计数槽全部由 {@code prefix} / {@code name} 按规约派生，
 * 不再有需要两处手工对齐的字符串。循环上限（maxSteps）只在这里出现一次，
 * Region 声明与 loopGuard 注册共用同一个值。</p>
 */
public final class OpsDiagnosisStages {

    /** Prompt 资产命名空间前缀（D1）。 */
    static final String PROMPT_NAMESPACE = "workflow/ops_diagnose_v2";

    /**
     * Prompt 协议块的一行工具说明：优先取人写描述（hint 非 null 时），
     * 否则由装配期用工具 schema 渲染（{@code OpsPrompts.describeTool}）。
     */
    public record ToolLine(String toolId, String hint) {

        static ToolLine described(String toolId) {
            return new ToolLine(toolId, null);
        }
    }

    /**
     * 一个工具循环阶段的全部声明性要素（带外部依赖的执行器实例由 Agent 按属性构造）。
     *
     * @param prefix       阶段前缀：派生节点 id（{p}_think/act/decide）、计数槽（{p}_round）、
     *                     Region 局部槽物理名（{p}_scratchpad/...）、执行器名（ops-act-{p}）与守卫名（ops-max-{p}）
     * @param name         英文短名：派生 Region id（{regionId} 显式传入）与 Prompt 资产 id（PROMPT_NAMESPACE/{name}）
     * @param title        中文阶段名（Prompt / 日志 / replan 裁决文案使用）
     * @param regionId     Region id（r1_investigate 等，显式声明保持既有 id 稳定）
     * @param systemPrompt system prompt 正文（{@link OpsPrompts} 常量）
     * @param tools        Prompt 协议块的工具行（顺序即渲染顺序），其 toolId 集合即执行器工具白名单
     * @param maxSteps     阶段内工具循环上限（Region 声明与 loopGuard 注册共用）
     * @param replanNode   replan 裁决节点 id（中间阶段），null 表示末阶段（decide 直出收尾）
     * @param outputGate   出口护栏：产出 JSON 过 schema 校验，不过则回环修正
     * @param streamThink  think 节点是否走内核流式通道逐段外发（O10）
     */
    public record Stage(String prefix, String name, String title, String regionId,
                        String systemPrompt, List<ToolLine> tools, int maxSteps,
                        String replanNode, boolean outputGate, boolean streamThink) {

        /** 中间阶段：带 replan 三态出口（continue → 下一阶段 / adjust → 回本阶段 / escalate）。 */
        public static Stage middle(String prefix, String name, String title, String regionId,
                String systemPrompt, List<ToolLine> tools, int maxSteps, String replanNode,
                boolean outputGate) {
            return new Stage(prefix, name, title, regionId, systemPrompt, tools, maxSteps,
                    replanNode, outputGate, false);
        }

        /** 末阶段：无 replan，decide 无工具调用即直答收尾。 */
        public static Stage last(String prefix, String name, String title, String regionId,
                String systemPrompt, List<ToolLine> tools, int maxSteps) {
            return new Stage(prefix, name, title, regionId, systemPrompt, tools, maxSteps,
                    null, false, true);
        }

        // ---- 节点 id（与 OpsDiagnoseWorkflowFactory 公开常量一致）----

        public String thinkNode()   { return prefix + "_think"; }
        public String actNode()     { return prefix + "_act"; }
        public String decideNode()  { return prefix + "_decide"; }

        // ---- 注册名 / 资产 id / 守卫名 / 计数槽 ----

        public String actExecutorName()     { return "ops-act-" + prefix; }
        public String replanExecutorName()  { return "ops-replan-" + prefix; }
        public String guardName()           { return "ops-max-" + prefix; }
        public String promptAssetId()       { return PROMPT_NAMESPACE + "/" + name; }
        public String counterSlot()         { return prefix + "_round"; }

        /** decide 回环表达式：Region 局部槽短名（变量表在带 slotPrefix 的 Region 内暴露短名）。 */
        public String hasCallsExpr()        { return "slots.has_calls > 0"; }

        public boolean hasReplan()          { return replanNode != null; }

        /** 执行器工具白名单（协议块 toolId 集合）。 */
        public Set<String> toolIds() {
            return Set.copyOf(tools.stream().map(ToolLine::toolId).toList());
        }
    }

    /** 阶段 1：查日志定位（O4/O5），replan_1 三态裁决后进阶段 2。 */
    public static final Stage INVESTIGATE = Stage.middle(
            "inv", "investigate", "查日志定位", "r1_investigate",
            OpsPrompts.INVESTIGATE,
            List.of(ToolLine.described("get_time"),
                    ToolLine.described("query_logs"),
                    new ToolLine("retrieve_knowledge",
                            "retrieve_knowledge(query, topK?)：检索知识库，返回带引用编号的资料分片")),
            4, "replan_1", false);

    /** 阶段 2：生成/纠正报文（出口护栏开启：产出 JSON 过 validate_request schema），replan_2 裁决后进阶段 3。 */
    public static final Stage RESOLVE = Stage.middle(
            "res", "resolve", "生成/纠正报文", "r2_resolve",
            OpsPrompts.RESOLVE,
            List.of(new ToolLine("retrieve_knowledge",
                            "retrieve_knowledge(query, topK?)：检索知识库，返回带引用编号的资料分片"),
                    ToolLine.described("validate_request")),
            5, "replan_2", true);

    /** 阶段 3：确定性校验收尾（末阶段：无 replan；结论流式外发 O10）。 */
    public static final Stage VERIFY = Stage.last(
            "ver", "verify", "确定性校验", "r3_verify",
            OpsPrompts.VERIFY,
            List.of(ToolLine.described("validate_request")),
            3);

    /** 阶段顺序（诊断主链：定位 → 纠正 → 校验）。 */
    public static final List<Stage> ALL = List.of(INVESTIGATE, RESOLVE, VERIFY);

    private OpsDiagnosisStages() {
    }
}
