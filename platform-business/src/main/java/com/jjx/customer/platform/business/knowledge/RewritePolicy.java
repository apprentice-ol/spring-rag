package com.jjx.customer.platform.business.knowledge;

/**
 * 首轮查询改写的策略：决定"要不要改写"，与 {@code QueryRewriter}（决定"怎么改"）分工。
 *
 * <p>这个枚举存在的理由只有一个：<b>没有它，"改写到底有没有用"这个问题就永远测不出来。</b>
 * 策略一旦埋进改写器内部（"无上下文就透传"），任何调用方——包括评测——都无法强制跑一次
 * 改写来做对照，只能眼睁睁看着两组实验的输入完全一样。</p>
 *
 * <p>线上默认 {@link #AUTO}：自包含的首轮问题透传（LLM 的"疑问→名词短语"重述会换掉
 * BM25 赖以命中的字面词），多轮追问才消解指代。评测跑对照实验时用 {@link #FORCE}
 * 与 {@link #OFF} 各跑一遍，拿到的差异才是改写本身的贡献。</p>
 */
public enum RewritePolicy {

    /** 自动：无历史、无用户补充时透传，有上下文才改写（线上默认）。 */
    AUTO,

    /** 一律透传：不做任何改写，直接用归一化查询检索。 */
    OFF,

    /** 一律改写：无上下文也强制走一次改写（评测对照用）。 */
    FORCE;

    /**
     * 解析策略字符串；无法识别或为空时回退 {@link #AUTO}。
     *
     * <p>不抛异常：策略来自槽位（可被外部设置），一个拼错的策略名不该让整轮问答失败——
     * 按最保守的默认走，行为仍是线上那套。</p>
     *
     * @param value 策略名（大小写不敏感），可空
     * @return 策略
     */
    public static RewritePolicy of(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        for (RewritePolicy policy : values()) {
            if (policy.name().equalsIgnoreCase(value.trim())) {
                return policy;
            }
        }
        return AUTO;
    }

    /**
     * 本轮是否真的调用改写器。
     *
     * @param hasContext 是否有会话历史或用户补充（指代消解的前提）
     * @return true = 调改写器
     */
    public boolean shouldRewrite(boolean hasContext) {
        return switch (this) {
            case FORCE -> true;
            case OFF -> false;
            case AUTO -> hasContext;
        };
    }
}
