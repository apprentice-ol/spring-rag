package com.jjx.customer.platform.business.workflow.common;

/**
 * 升级终态的共享常量：节点 id / 执行器注册名 / 升级原因槽位。
 *
 * <p>原先散在三处（ops 图工厂的 {@code ESCALATE_NODE}/{@code ESCALATE_EXECUTOR}、
 * 出口映射的 {@code ESCALATE_NODE_ID}/{@code ESCALATE_REASON_SLOT}、执行器里的字面量），
 * 三处字面量收敛为一处——这些都是引擎对账键，值一个字都不能变。</p>
 */
public final class EscalateTerminal {

    /** 升级终态节点 id（图与出口映射共用）。 */
    public static final String NODE_ID = "escalate_node";

    /** 升级执行器注册名（引擎对账键，注册名不可变）。 */
    public static final String EXECUTOR_ID = "ops-escalate";

    /** 升级原因槽位（执行器写入，出口映射据此判定 ESCALATE）。 */
    public static final String REASON_SLOT = "escalate_reason";

    /** 阶段产出槽位（后覆盖前）：升级文案取最后非空阶段产出（ConcludeExecutor 同源）。 */
    public static final String[] STAGE_OUTPUT_SLOTS =
            {"inv_stage_output", "res_stage_output", "ver_stage_output"};

    private EscalateTerminal() {
    }
}
