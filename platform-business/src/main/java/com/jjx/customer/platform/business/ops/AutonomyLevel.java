package com.jjx.customer.platform.business.ops;

/**
 * 会话自主档位（人在环中 P3）：把"系统替用户做多少决定"从静态目录声明提升为**会话级旋钮**。
 *
 * <p>与目录声明的关系是<b>交集、偏保守</b>：{@code OpsSlotCatalog.Spec} 的
 * {@code default/inferable/resolvable} 是能力上限（这一槽"允许"被自动补全），
 * 档位是本次会话"愿意"放到多松；两者取交集，任一为否即不自动补。
 * 所以档位只能收紧、不能突破目录声明——目录仍是自主性的真源。</p>
 *
 * <p>传递方式：随 {@code Input.slots} 走 {@link #SLOT} 槽位（与 {@code user_clarify} 同通道），
 * 来源优先级 = 本轮请求参数 &gt; 用户回复里顺带调的档（{@code autonomy_hint}）&gt; 会话记录 &gt; 缺省 L2。</p>
 */
public enum AutonomyLevel {

    /** L1 多问我：不自动补全——缺什么问什么，连目录缺省值也不代填。 */
    L1("多问我"),
    /** L2 默认：目录缺省 + 高置信推断（≥0.7）+ 日志反查（≤2 次）——P3 之前的既有行为。 */
    L2("默认"),
    /** L3 少问我：推断门槛降到 0.5、反查多一轮换条件重试（目录能力边界内的最大化自主）。 */
    L3("少问我");

    /** 档位槽位名（会话级旋钮随 Input.slots 传递）。 */
    public static final String SLOT = "autonomy_level";

    private final String label;

    AutonomyLevel(String label) {
        this.label = label;
    }

    /** @return 面向用户的档位名 */
    public String label() {
        return label;
    }

    /**
     * 解析档位串（容忍 {@code L1/l1/1} 三种写法与前后空白）。
     *
     * @param raw 原始串（可空）
     * @return 档位；空/无法识别一律回缺省 {@link #L2}（不认识的值绝不放大自主性）
     */
    public static AutonomyLevel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return L2;
        }
        String value = raw.trim().toUpperCase();
        if (value.length() == 1) {
            value = "L" + value;
        }
        for (AutonomyLevel level : values()) {
            if (level.name().equals(value)) {
                return level;
            }
        }
        return L2;
    }

    /**
     * 协议里的 {@code autonomy_hint}（1=多确认 2=默认 3=自主）→ 档位。
     *
     * @param hint 用户回复里顺带调的档（可空）
     * @return 档位；越界/缺省返回 {@code null}（= 不改档）
     */
    public static AutonomyLevel fromHint(Integer hint) {
        if (hint == null) {
            return null;
        }
        return switch (hint) {
            case 1 -> L1;
            case 2 -> L2;
            case 3 -> L3;
            default -> null;
        };
    }
}
