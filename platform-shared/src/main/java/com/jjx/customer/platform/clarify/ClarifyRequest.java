package com.jjx.customer.platform.clarify;

import java.util.List;

/**
 * 追问请求（agent 中断等用户补充的产物）。
 *
 * @param sessionId 会话状态记录 id（无会话场景为 null）
 * @param summary   给用户的一句话说明（为什么需要补充）
 * @param questions 缺失槽位问题列表（一次问齐，不挤牙膏）
 */
public record ClarifyRequest(String sessionId, String summary, List<SlotQuestion> questions) {
}
