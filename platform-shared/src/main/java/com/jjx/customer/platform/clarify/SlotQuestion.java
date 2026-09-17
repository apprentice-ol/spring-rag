package com.jjx.customer.platform.clarify;

/**
 * 一条待补槽位问题（追问卡片渲染单元）。
 *
 * @param slot     槽位名（如 environment / interface / time / payload）
 * @param question 面向用户的追问文本
 * @param hint     填写提示（如 "正式环境 / 测试环境"），可为空
 * @param required 是否必填
 */
public record SlotQuestion(String slot, String question, String hint, boolean required) {
}
