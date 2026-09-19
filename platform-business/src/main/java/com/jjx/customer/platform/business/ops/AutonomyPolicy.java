package com.jjx.customer.platform.business.ops;

import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;

/**
 * 自主性合成策略（人在环中 P3）：**目录声明 × 会话档位 → 本次允许的自主边界**。
 *
 * <p>交集偏保守：{@link OpsSlotCatalog.Spec} 声明的是能力上限（该槽"允许"被自动补全），
 * 档位是本次会话"愿意"放到多松，两者取与——档位只能收紧目录声明，不能突破它。
 * 这样"自主性从哪来"始终只有一个真源（目录），档位只是会话级音量旋钮。</p>
 *
 * <p>策略是纯函数（无状态、无 IO）：由执行器按当前档位现造，便于单测矩阵化。</p>
 *
 * @param level 会话档位
 */
public record AutonomyPolicy(AutonomyLevel level) {

    /** 缺省策略（L2：与 P3 之前的行为逐字一致）。 */
    public static final AutonomyPolicy DEFAULT = new AutonomyPolicy(AutonomyLevel.L2);

    /** L3 的推断置信门槛（L2 为 0.7——与原 {@code AutoResolveExecutor.CONFIDENCE_THRESHOLD} 同口径）。 */
    private static final double CONFIDENCE_L2 = 0.7;

    private static final double CONFIDENCE_L3 = 0.5;

    /** L3 的日志反查次数上限（L2 为 2——与原实现同口径）。 */
    private static final int RESOLVE_CALLS_L2 = 2;

    /** L3：恰好多一轮——关键字查无果时去掉时间窗重试（额度写实，不留用不上的余量）。 */
    private static final int RESOLVE_CALLS_L3 = 3;

    public AutonomyPolicy {
        if (level == null) {
            level = AutonomyLevel.L2;
        }
    }

    /**
     * @param raw 档位串（槽位原值，可空）
     * @return 策略（空/无法识别 → L2）
     */
    public static AutonomyPolicy of(String raw) {
        return new AutonomyPolicy(AutonomyLevel.parse(raw));
    }

    /**
     * 目录缺省值是否生效（L1 连"最近30分钟"这种兜底也不代填——用户没给时间就问用户）。
     *
     * @return true = 允许用目录声明的缺省值
     */
    public boolean allowsCatalogDefault() {
        return level != AutonomyLevel.L1;
    }

    /**
     * 该槽位是否允许 LLM 推断（目录 {@code inferable} ∩ 档位）。
     *
     * @param spec 槽位契约
     * @return true = 允许
     */
    public boolean allowsInfer(OpsSlotCatalog.Spec spec) {
        return spec != null && spec.inferable() && level != AutonomyLevel.L1;
    }

    /**
     * 该槽位是否允许日志反查补全（目录 {@code resolvable} ∩ 档位）。
     *
     * @param spec 槽位契约
     * @return true = 允许
     */
    public boolean allowsResolve(OpsSlotCatalog.Spec spec) {
        return spec != null && spec.resolvable() && level != AutonomyLevel.L1;
    }

    /** @return LLM 推断的置信门槛（L3 更敢猜） */
    public double inferConfidence() {
        return level == AutonomyLevel.L3 ? CONFIDENCE_L3 : CONFIDENCE_L2;
    }

    /** @return 日志反查次数上限（L3 多一轮换条件重试） */
    public int maxResolveCalls() {
        return level == AutonomyLevel.L3 ? RESOLVE_CALLS_L3 : RESOLVE_CALLS_L2;
    }
}
