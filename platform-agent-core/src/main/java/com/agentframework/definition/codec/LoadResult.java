package com.agentframework.definition.codec;

import com.agentframework.definition.ValidationReport;
import java.util.Map;

/**
 * 定义加载结果：定义对象 + 规范化文档 + 校验报告。
 *
 * <p>{@link #document()} 是解码后再编码的规范形态，可直接入库；{@link #report()} 里有 ERROR 时
 * {@link #accepted()} 为 false，定义不得发布。</p>
 *
 * @param kind       定义种类
 * @param definition 定义对象，加载失败时为 null
 * @param document   规范化文档，加载失败时为空表
 * @param report     校验报告
 */
public record LoadResult(
        DefinitionKind kind,
        Object definition,
        Map<String, Object> document,
        ValidationReport report) {

    public LoadResult {
        // 定义文档里存在大量 null 字段（未设置的默认值），不能用 Map.copyOf
        document = document == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(document));
        report = report == null ? ValidationReport.empty() : report;
    }

    /** @return 是否通过校验并可用于入库 */
    public boolean accepted() {
        return definition != null && !report.hasErrors();
    }

    /**
     * @param type 期望的类型
     * @param <T>  类型参数
     * @return 定义对象
     * @throws IllegalStateException 加载失败或类型不符时抛出
     */
    public <T> T require(Class<T> type) {
        if (!type.isInstance(definition)) {
            throw new IllegalStateException("定义未成功加载：" + report.errorMessages());
        }
        return type.cast(definition);
    }
}
