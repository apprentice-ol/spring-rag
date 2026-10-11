package com.jjx.customer.platform.eval.domain;

/**
 * bad case 归集渠道（{@code sa_eval_item.origin}）。
 *
 * <p>与 {@code source}（builtin / liverag / feedback）的关系：source 是来源大类，
 * origin 是 badcase 内部更细的归集渠道。</p>
 */
public enum BadcaseOrigin {
    /** 聊天页点踩 */
    ONLINE_DISLIKE("online_dislike"),
    /** 评测 run 低分勾选归集 */
    EVAL_LOW("eval_low"),
    /** 会话/轨迹面板巡检挑选 */
    TRACE_PICK("trace_pick"),
    /** 管理页手工录入 */
    MANUAL("manual");

    private final String code;

    BadcaseOrigin(String code) {
        this.code = code;
    }

    /** DB 存储值（snake_case）。 */
    public String code() {
        return code;
    }
}
