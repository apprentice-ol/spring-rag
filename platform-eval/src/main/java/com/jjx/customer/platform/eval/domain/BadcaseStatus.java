package com.jjx.customer.platform.eval.domain;

/**
 * bad case 归集状态（{@code sa_eval_item.badcase_status}）。
 *
 * <p>生命周期：{@link #DRAFT}（归集池中，待补 ground truth，不参与跑批）
 * → {@link #READY}（已转正到领域数据集，可被 EvalRunner 抽中）或 {@link #IGNORED}（判定无需处理）。</p>
 *
 * <p>非 badcase 条目（人工构造 / LiveRAG 导入）该列为 NULL。</p>
 */
public enum BadcaseStatus {
    /** 待补标准答案（归集池中） */
    DRAFT,
    /** 已转正（可评测） */
    READY,
    /** 已忽略 */
    IGNORED
}
