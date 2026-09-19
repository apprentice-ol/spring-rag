package com.jjx.customer.platform.clarify;

import java.util.List;

/**
 * 一条待补槽位问题（追问卡片渲染单元）；带 {@code value} 时表示「机器已补全、等你确认」。
 *
 * <p>人在环中扩展（2026-09-19 P1）：{@code options} 非空时前端渲染为点选标签
 * （点击即发送该值，等价于输入框作答）。</p>
 *
 * <p>P3 扩展：{@code value}/{@code provenance} 非空 = 这一槽是系统自动补全的
 * （缺省值 / 模型推断 / 日志反查 / 规则提取），卡片渲染成「值 + 来源角标 + 纠正入口」；
 * 用户纠正回传 {@code #override:<slot>=<value>}。新增字段全部可空，旧事件零影响。</p>
 *
 * @param slot       槽位名（如 environment / interface / time / payload）
 * @param question   面向用户的追问文本
 * @param hint       填写提示（如 "正式环境 / 测试环境"），可为空
 * @param required   是否必填
 * @param options    候选值（目录声明；点击直接作为回答发送），可为空
 * @param value      机器已补全的值（null = 该槽仍缺失，需要用户补）
 * @param provenance 补全来源（rule / default / llm / log_query / user_override）
 * @param evidence   来源依据（如「日志反查关键字「订单接口」命中」）——卡片上回答「这值哪来的」
 */
public record SlotQuestion(String slot, String question, String hint, boolean required,
                           List<String> options, String value, String provenance, String evidence) {

    /** 兼容旧构造（无候选值，缺失槽）。 */
    public SlotQuestion(String slot, String question, String hint, boolean required) {
        this(slot, question, hint, required, List.of(), null, null, null);
    }

    /** 兼容旧构造（有候选值，缺失槽）。 */
    public SlotQuestion(String slot, String question, String hint, boolean required,
                        List<String> options) {
        this(slot, question, hint, required, options, null, null, null);
    }

    /** 兼容构造（P3：带值带来源，无依据说明）。 */
    public SlotQuestion(String slot, String question, String hint, boolean required,
                        List<String> options, String value, String provenance) {
        this(slot, question, hint, required, options, value, provenance, null);
    }

    public SlotQuestion {
        options = options == null ? List.of() : options;
    }

    /** @return 是否已由系统补全（交付层据此分流「待补问题」与「已补待确认」两组） */
    public boolean hasValue() {
        return value != null && !value.isBlank();
    }
}
