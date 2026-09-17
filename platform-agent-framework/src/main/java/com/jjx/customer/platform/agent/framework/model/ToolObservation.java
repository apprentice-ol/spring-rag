package com.jjx.customer.platform.agent.framework.model;

/**
 * 工具执行结果的结构化观测（原生 function calling 的 tool 消息内容）。
 *
 * @param id       调用关联标识（原生协议的 tool_call_id；由循环节点生成，assistant 调用与结果一一对应）
 * @param toolName 工具名
 * @param content  回喂内容（失败也回喂，模型自行纠偏）
 */
public record ToolObservation(String id, String toolName, String content) {
}
