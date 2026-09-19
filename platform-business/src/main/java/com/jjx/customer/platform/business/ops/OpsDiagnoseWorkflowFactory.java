package com.jjx.customer.platform.business.ops;

import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.workflow.Edge;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.jjx.customer.platform.business.ops.stages.IntakeStageModule;
import com.jjx.customer.platform.business.ops.stages.StageModule;
import com.jjx.customer.platform.business.ops.stages.TerminalStageModule;
import com.jjx.customer.platform.business.ops.stages.ToolLoopStageModule;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ops 故障诊断工作流骨架（复刻 springai-rag 的 OpsDiagnoseWorkflow，验收基准 O1–O11）。
 *
 * <p>本类只做三件事：持有跨阶段共享的 id 常量、按序串联阶段模块的图声明、
 * 装配期「图 ↔ 模块」对账。每个阶段的图原语与引擎装配都收敛在
 * {@code stages/} 包的对应模块里——读懂一个阶段只需读一个文件：</p>
 *
 * <ul>
 *   <li>{@link IntakeStageModule}：入口问齐环 + 自主补全（HUMAN_IN_LOOP）</li>
 *   <li>{@link ToolLoopStageModule}：think → act → decide 工具循环（定位 / 纠正 / 校验三份实例）</li>
 *   <li>{@link TerminalStageModule}：升级与直答终态</li>
 * </ul>
 *
 * <p>模块顺序即 {@code then} 链：前向边串联阶段，真正的迭代（问齐环、工具环、
 * replan adjust 回跳）都落在声明了循环治理的 Region 内，未治理回边由内核
 * build 期 {@code GRAPH_BACKEDGE_UNGOVERNED} 校验兜底。</p>
 *
 * <pre>
 * extract_slots → slots_gate ─┬─（缺必填）→ auto_resolve → auto_gate ─┬─（补齐）→ inv_think
 *                             │        （规则→LLM→反查 三层自救）      └─（仍缺）→ collect_slots ⇄ slots_regate（问齐兜底）
 *                             └─（齐备）→ inv_think → inv_act → inv_decide ─┬─（有调用）→ inv_think
 *                                                                          └─→ replan_1 ─┬ continue → res_think
 *                                                                                        ├ adjust → inv_think
 *                                                                                        └ escalate → escalate_node
 * res_think → res_act → res_decide → replan_2 →（continue）→ ver_think → ver_act → ver_decide → conclude
 * </pre>
 */
public final class OpsDiagnoseWorkflowFactory {

    /**
     * 工作流 id。
     */
    public static final String WORKFLOW_ID = "ops_diagnose_v2";

    // ---- 节点 id ----

    /**
     * 槽位抽取节点 id。
     */
    public static final String EXTRACT_SLOTS_NODE = "extract_slots";
    /**
     * 槽位门禁节点 id（缺必填 → 自主补全）。
     */
    public static final String SLOTS_GATE_NODE = "slots_gate";
    /**
     * 自主补全节点 id（规则 → LLM → 日志反查，问用户之前的自救层）。
     */
    public static final String AUTO_RESOLVE_NODE = "auto_resolve";
    /**
     * 自主补全后再判定节点 id（补齐 → 主链；仍缺 → 问齐兜底）。
     */
    public static final String AUTO_GATE_NODE = "auto_gate";
    /**
     * 一次问齐节点 id（挂起等用户补充，恢复即重入）。
     */
    public static final String COLLECT_SLOTS_NODE = "collect_slots";
    /**
     * 补答后再判定节点 id。
     */
    public static final String SLOTS_REGATE_NODE = "slots_regate";
    /**
     * 阶段 1 think/act/decide。
     */
    public static final String INV_THINK_NODE = OpsDiagnosisStages.INVESTIGATE.thinkNode();
    /**
     * 阶段 1 act。
     */
    public static final String INV_ACT_NODE = OpsDiagnosisStages.INVESTIGATE.actNode();
    /**
     * 阶段 1 decide。
     */
    public static final String INV_DECIDE_NODE = OpsDiagnosisStages.INVESTIGATE.decideNode();
    /**
     * 阶段 1 → 2 replan。
     */
    public static final String REPLAN_1_NODE = OpsDiagnosisStages.INVESTIGATE.replanNode();
    /**
     * 阶段 2 think。
     */
    public static final String RES_THINK_NODE = OpsDiagnosisStages.RESOLVE.thinkNode();
    /**
     * 阶段 2 act。
     */
    public static final String RES_ACT_NODE = OpsDiagnosisStages.RESOLVE.actNode();
    /**
     * 阶段 2 decide。
     */
    public static final String RES_DECIDE_NODE = OpsDiagnosisStages.RESOLVE.decideNode();
    /**
     * 阶段 2 → 3 replan。
     */
    public static final String REPLAN_2_NODE = OpsDiagnosisStages.RESOLVE.replanNode();
    /**
     * 阶段 3 think。
     */
    public static final String VER_THINK_NODE = OpsDiagnosisStages.VERIFY.thinkNode();
    /**
     * 阶段 3 act。
     */
    public static final String VER_ACT_NODE = OpsDiagnosisStages.VERIFY.actNode();
    /**
     * 阶段 3 decide。
     */
    public static final String VER_DECIDE_NODE = OpsDiagnosisStages.VERIFY.decideNode();
    /**
     * 升级终态节点 id。
     */
    public static final String ESCALATE_NODE = "escalate_node";
    /**
     * 直答收尾节点 id（终态）。
     */
    public static final String CONCLUDE_NODE = "conclude";

    // ---- 执行器注册名 ----

    /**
     * 槽位抽取执行器注册名。
     */
    public static final String SLOT_EXTRACT_EXECUTOR = "ops-slot-extract";
    /**
     * 自主补全执行器注册名。
     */
    public static final String AUTO_RESOLVE_EXECUTOR = "ops-auto-resolve";
    /**
     * 一次问齐执行器注册名。
     */
    public static final String ASK_MISSING_EXECUTOR = "ops-ask-missing";
    /**
     * 阶段 1 工具循环 Act 执行器注册名。
     */
    public static final String ACT_INV_EXECUTOR = OpsDiagnosisStages.INVESTIGATE.actExecutorName();
    /**
     * 阶段 2 工具循环 Act 执行器注册名。
     */
    public static final String ACT_RES_EXECUTOR = OpsDiagnosisStages.RESOLVE.actExecutorName();
    /**
     * 阶段 3 工具循环 Act 执行器注册名。
     */
    public static final String ACT_VER_EXECUTOR = OpsDiagnosisStages.VERIFY.actExecutorName();
    /**
     * replan 三态裁决执行器注册名（阶段 1 → 2，按阶段各一实例）。
     */
    public static final String REPLAN_INV_EXECUTOR = OpsDiagnosisStages.INVESTIGATE.replanExecutorName();
    /**
     * replan 三态裁决执行器注册名（阶段 2 → 3）。
     */
    public static final String REPLAN_RES_EXECUTOR = OpsDiagnosisStages.RESOLVE.replanExecutorName();
    /**
     * 升级终态执行器注册名。
     */
    public static final String ESCALATE_EXECUTOR = "ops-escalate";
    /**
     * 直答收尾执行器注册名。
     */
    public static final String CONCLUDE_EXECUTOR = "ops-conclude";

    // ---- 迭代守卫名（每阶段一个，上限不同） ----

    /**
     * 问齐环迭代守卫名。
     */
    public static final String INTAKE_ITERATION_GUARD = "ops-max-intake";
    /**
     * 阶段 1 迭代守卫名（对齐参考实现 maxSteps=4）。
     */
    public static final String INV_ITERATION_GUARD = OpsDiagnosisStages.INVESTIGATE.guardName();
    /**
     * 阶段 2 迭代守卫名（对齐参考实现 maxSteps=5）。
     */
    public static final String RES_ITERATION_GUARD = OpsDiagnosisStages.RESOLVE.guardName();
    /**
     * 阶段 3 迭代守卫名（对齐参考实现 maxSteps=3）。
     */
    public static final String VER_ITERATION_GUARD = OpsDiagnosisStages.VERIFY.guardName();

    // ---- Region id ----

    /**
     * 入口问齐区域 id。
     */
    public static final String INTAKE_REGION = "r0_intake";
    /**
     * 阶段 1（查日志定位）区域 id。
     */
    public static final String INV_REGION = OpsDiagnosisStages.INVESTIGATE.regionId();
    /**
     * 阶段 2（生成/纠正报文）区域 id。
     */
    public static final String RES_REGION = OpsDiagnosisStages.RESOLVE.regionId();
    /**
     * 阶段 3（确定性校验）区域 id。
     */
    public static final String VER_REGION = OpsDiagnosisStages.VERIFY.regionId();

    // ---- Prompt 资产 id（D1：统一到 workflow/ops_diagnose_v2 命名空间） ----

    /**
     * 阶段 1 system prompt 资产 id。
     */
    public static final String INVESTIGATE_PROMPT = OpsDiagnosisStages.INVESTIGATE.promptAssetId();
    /**
     * 阶段 2 system prompt 资产 id。
     */
    public static final String RESOLVE_PROMPT = OpsDiagnosisStages.RESOLVE.promptAssetId();
    /**
     * 阶段 3 system prompt 资产 id。
     */
    public static final String VERIFY_PROMPT = OpsDiagnosisStages.VERIFY.promptAssetId();

    /**
     * 问齐环补问上限（超过后带现有信息继续，环内仍可再问）。
     */
    public static final int INTAKE_MAX_ROUNDS = 3;
    /**
     * 阶段 1 工具循环上限（对齐参考实现 maxSteps）。
     */
    public static final int INV_MAX_STEPS = OpsDiagnosisStages.INVESTIGATE.maxSteps();
    /**
     * 阶段 2 工具循环上限。
     */
    public static final int RES_MAX_STEPS = OpsDiagnosisStages.RESOLVE.maxSteps();
    /**
     * 阶段 3 工具循环上限。
     */
    public static final int VER_MAX_STEPS = OpsDiagnosisStages.VERIFY.maxSteps();

    /**
     * 工具 id：知识检索（复用 RAG 既有工具）。
     */
    public static final String RETRIEVE_TOOL_ID = "retrieve_knowledge";
    /**
     * 工具 id：日志查询。
     */
    public static final String QUERY_LOGS_TOOL_ID = "query_logs";
    /**
     * 工具 id：报文确定性校验。
     */
    public static final String VALIDATE_TOOL_ID = "validate_request";

    private OpsDiagnoseWorkflowFactory() {
    }

    /**
     * @return 全部阶段模块（键 = 语义键 {@code intake / investigate / resolve / verify / terminal}，
     *         与物理前缀 inv/res/ver 解耦以兼容已落库的会话快照与模板引用；顺序 = 主链 then 顺序）
     */
    public static Map<String, StageModule> modules() {
        Map<String, StageModule> modules = new LinkedHashMap<>();
        modules.put(IntakeStageModule.KEY, new IntakeStageModule());
        List<OpsDiagnosisStages.Stage> stages = OpsDiagnosisStages.ALL;
        for (int i = 0; i < stages.size(); i++) {
            OpsDiagnosisStages.Stage next = i + 1 < stages.size() ? stages.get(i + 1) : null;
            ToolLoopStageModule module = new ToolLoopStageModule(stages.get(i), next);
            modules.put(module.key(), module);
        }
        modules.put(TerminalStageModule.KEY, new TerminalStageModule());
        return modules;
    }

    /**
     * @return 诊断工作流定义（build 期完成结构校验：显式图、槽位策略、回边治理）
     */
    public static WorkflowDefinition create() {
        WorkflowBuilder workflow = WorkflowBuilder.create(WORKFLOW_ID, "1.0.0");
        // 模块顺序即主链：intake →（inv → res → ver 工具循环）→ terminal
        modules().values().forEach(module -> module.declareGraph(workflow));
        workflow.requireTool(RETRIEVE_TOOL_ID)                  // 工具契约（Agent 装配期校验）
                .requireTool(QUERY_LOGS_TOOL_ID)
                .requireTool(VALIDATE_TOOL_ID);
        return workflow.build();
    }

    /**
     * 装配期对账（图是唯一事实源）：图可达节点引用的执行器集合与模块
     * {@link StageModule#provides()} 联集必须完全一致——图引用而模块未提供
     * （运行期会踩「执行器未注册」）或模块提供而图未引用（模块与图脱节的死代码）
     * 都在启动期直接失败。两个模块声明同一执行器名同样视为装配冲突。
     *
     * @param workflow 工作流定义
     * @param modules  全部阶段模块
     */
    public static void reconcile(WorkflowDefinition workflow, Collection<StageModule> modules) {
        Set<String> referenced = new LinkedHashSet<>();         // 图可达节点实际引用的执行器
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(
                workflow.startNodes().stream().map(NodeDefinition::id).toList());
        while (!pending.isEmpty()) {
            String nodeId = pending.poll();
            if (!visited.add(nodeId)) {
                continue;
            }
            workflow.node(nodeId).ifPresent(node -> {
                if (node instanceof CustomNodeDefinition custom) {
                    referenced.add(custom.executorRef());
                }
                for (Edge edge : workflow.outgoing(nodeId)) {
                    pending.offer(edge.to());
                }
            });
        }

        Set<String> provided = new LinkedHashSet<>();           // 模块声明的执行器
        for (StageModule module : modules) {
            for (String name : module.provides()) {
                if (!provided.add(name)) {
                    throw new IllegalStateException("多个阶段模块声明同一执行器：'" + name + "'");
                }
            }
        }

        Set<String> missing = new LinkedHashSet<>(referenced);
        missing.removeAll(provided);
        Set<String> orphan = new LinkedHashSet<>(provided);
        orphan.removeAll(referenced);
        if (!missing.isEmpty() || !orphan.isEmpty()) {
            throw new IllegalStateException("阶段模块与图脱节（图是唯一事实源）："
                    + "图引用但无模块提供=" + missing + "，模块提供但图未引用=" + orphan);
        }
    }
}
