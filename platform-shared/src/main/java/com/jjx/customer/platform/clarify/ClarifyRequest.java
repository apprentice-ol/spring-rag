package com.jjx.customer.platform.clarify;

import java.util.List;

/**
 * 追问请求（agent 中断等用户补充的产物；人在环中扩展后兼载决策移交）。
 *
 * <p>新增字段全部可空（旧事件/旧构造不带时按缺省渲染，SSE 前端镜像同步 optional）——
 * 普通问齐挂起只有 summary + questions；DECIDE 决策移交（人在环中）带 kind/options/evidence，
 * 前者渲染问题清单，后者渲染证据摘要 + 点选按钮。</p>
 *
 * @param sessionId     会话状态记录 id（无会话场景为 null）
 * @param summary       给用户的一句话说明（为什么需要补充 / 决策问句）
 * @param questions     缺失槽位问题列表（一次问齐，不挤牙膏；DECIDE 时为空）
 * @param reviewed      已自动补全、等用户确认的槽位（P3：带值 + 来源角标，可点选纠正）
 * @param kind          请求类别（null/空 = CLARIFY 兼容旧事件；DECIDE = 决策移交）
 * @param options       点选项（DECIDE；空 = 纯文本回答；点击回传 #decision:&lt;value&gt;）
 * @param evidence      决策上下文要点（DECIDE；已查明事实，让人不必重看全程就能决策）
 * @param allowFreeText 是否允许自由文本回复（null = true 兼容）
 */
public record ClarifyRequest(String sessionId, String summary, List<SlotQuestion> questions,
                             List<SlotQuestion> reviewed, String kind,
                             List<ClarifyChoice> options, List<String> evidence,
                             Boolean allowFreeText) {

    /** 兼容旧构造（纯问齐，无人在环扩展字段）。 */
    public ClarifyRequest(String sessionId, String summary, List<SlotQuestion> questions) {
        this(sessionId, summary, questions, List.of(), null, List.of(), List.of(), null);
    }

    /** 兼容 Jackson 反序列化缺省字段（record 全参构造不适用可缺省 JSON）。 */
    public ClarifyRequest {
        questions = questions == null ? List.of() : questions;
        reviewed = reviewed == null ? List.of() : reviewed;
        kind = kind == null || kind.isBlank() ? "CLARIFY" : kind;
        options = options == null ? List.of() : options;
        evidence = evidence == null ? List.of() : evidence;
    }

    /** @return 是否为决策移交请求（前端渲染证据 + 选项而非问题清单） */
    public boolean isDecision() {
        return "DECIDE".equalsIgnoreCase(kind);
    }

    /**
     * 点选项（DECIDE 决策移交）。
     *
     * @param value       回传值（前端点击发送 #decision:value）
     * @param label       按钮文案
     * @param description 行为说明（可空）
     */
    public record ClarifyChoice(String value, String label, String description) {
    }
}
