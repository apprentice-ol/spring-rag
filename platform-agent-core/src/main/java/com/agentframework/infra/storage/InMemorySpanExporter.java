package com.agentframework.infra.storage;

import com.agentframework.crosscutting.trace.Span;
import com.agentframework.crosscutting.trace.SpanExporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内存 span 导出器：把追踪结果留在内存中，便于测试断言与本地调试。
 */
public final class InMemorySpanExporter implements SpanExporter {

    private final List<Span> spans = new ArrayList<>();

    @Override
    public void export(Span span) {
        if (span != null) {
            synchronized (spans) {
                spans.add(span);
            }
        }
    }

    /** @return 已导出的 span 快照 */
    public List<Span> spans() {
        synchronized (spans) {
            return List.copyOf(spans);
        }
    }

    /**
     * @param name span 名称
     * @return 匹配名称的 span 列表
     */
    public List<Span> spansNamed(String name) {
        return spans().stream().filter(span -> span.name().equals(name)).toList();
    }

    /**
     * @param kind span 类型
     * @return 匹配类型的 span 数量
     */
    public long countOfKind(com.agentframework.crosscutting.trace.SpanKind kind) {
        return spans().stream().filter(span -> span.kind() == kind).count();
    }

    /** @return 用于日志输出的摘要列表 */
    public List<Map<String, Object>> summaries() {
        return spans().stream().map(Span::summary).toList();
    }

    /** 清空已导出内容。 */
    public void reset() {
        synchronized (spans) {
            spans.clear();
        }
    }
}
