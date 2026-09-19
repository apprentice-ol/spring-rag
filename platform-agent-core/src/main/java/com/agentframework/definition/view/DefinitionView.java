package com.agentframework.definition.view;

import com.agentframework.definition.ValidationProblem;
import com.agentframework.definition.ValidationReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定义视图：一份文档即可画出完整拓扑（节点 / 三类边 / 区域 / 循环 / 动态边）与校验结论。
 *
 * @param kind    定义种类（当前固定为 workflow）
 * @param id      工作流 id
 * @param version 版本号
 * @param nodes   节点
 * @param edges   边（含动态边）
 * @param regions 区域
 * @param dynamic 动态路由声明
 * @param report  校验报告
 */
public record DefinitionView(
        String kind,
        String id,
        String version,
        List<NodeView> nodes,
        List<EdgeView> edges,
        List<RegionView> regions,
        List<DynamicView> dynamic,
        ValidationReport report) {

    public DefinitionView {
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        edges = List.copyOf(edges == null ? List.of() : edges);
        regions = List.copyOf(regions == null ? List.of() : regions);
        dynamic = List.copyOf(dynamic == null ? List.of() : dynamic);
        report = report == null ? ValidationReport.empty() : report;
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", kind);
        document.put("id", id);
        document.put("version", version);
        document.put("nodes", nodes.stream().map(NodeView::toDocument).toList());
        document.put("edges", edges.stream().map(EdgeView::toDocument).toList());
        document.put("regions", regions.stream().map(RegionView::toDocument).toList());
        document.put("dynamic", dynamic.stream().map(DynamicView::toDocument).toList());
        document.put("report", reportDocument(report));
        return document;
    }

    /**
     * @param report 校验报告
     * @return 报告文档
     */
    static Map<String, Object> reportDocument(ValidationReport report) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("errorCount", report.errors().size());
        document.put("warningCount", report.warnings().size());
        document.put("problems", report.problems().stream().map(DefinitionView::problemDocument).toList());
        return document;
    }

    /**
     * @param problem 问题
     * @return 问题文档
     */
    private static Map<String, Object> problemDocument(ValidationProblem problem) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("code", problem.code());
        document.put("severity", problem.severity().name());
        document.put("location", problem.location());
        document.put("message", problem.message());
        document.put("candidates", problem.candidates());
        return document;
    }
}
