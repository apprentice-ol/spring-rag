package com.jjx.customer.platform.agent.framework.spi;

/**
 * 一次执行的骨架阶段——引擎按此顺序推进，{@link ExecutionListener} 按阶段收到回调。
 *
 * <p>骨架固定（横切语义与顺序不对外开放），每一步的"怎么做"由驱动与节点执行器决定（模板方法 + 策略）。</p>
 */
public enum ExecutionPhase {

    /** 装配执行计划：路由求值 → Agent/Workflow 绑定 → 能力解析 → prompt 快照 → 指纹。 */
    PLAN,

    /** 槽位准备：声明式归一 + LLM 抽槽（已确认值优先，只填空缺）。 */
    SLOT_PREPARE,

    /** 槽位校验：缺必填 ⇒ 一次问齐（CLARIFY 短路）。 */
    SLOT_VALIDATE,

    /** 阶段执行：每个阶段推进一次（detail = 阶段名）。 */
    STAGE,

    /** 阶段间 replan 裁决（detail = 刚完成的阶段名）。 */
    REPLAN,

    /** 结果装配：引用索引 / 生成规格 / 元数据贡献。 */
    ASSEMBLE,

    /** 出口：结果已成型，监听器落库/推送（会话、观测、审计）。 */
    DONE
}
