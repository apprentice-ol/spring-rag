package com.nageoffer.ai.rag.diagnose.dto;

/**
 * 匹配校验结果：用户业务问题 vs traceId 报错 是否可能同一问题。
 *
 * <ul>
 *   <li>{@code relevant}：true=相关（放行完整诊断）；false=明显不相关（应反问）</li>
 *   <li>{@code errorBrief}：一句话概括报错本质（如"客户端连接中断"），用于反问提示</li>
 *   <li>{@code reason}：判断理由</li>
 * </ul>
 * <p>宽松策略：不确定/异常一律 {@code relevant=true}（宁可放过，不误杀正确 traceId）。
 */
public record MatchResult(
        boolean relevant,
        String errorBrief,
        String reason
) {
}
