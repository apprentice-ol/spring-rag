package com.jjx.customer.platform.agent.framework.result;

/**
 * 单条引用映射：[N] → 来源。
 *
 * @param ref     引用编号
 * @param source  来源标识（文档名 / 日志服务等）
 * @param locator 定位信息（页码 / 段落 / traceId 等，可空）
 */
public record Citation(int ref, String source, String locator) {
}
