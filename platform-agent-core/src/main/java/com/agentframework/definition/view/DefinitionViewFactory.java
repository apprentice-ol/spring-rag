package com.agentframework.definition.view;

import com.agentframework.definition.ValidationReport;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * 定义视图装配器：把工作流定义转换成前端可直接渲染的只读视图。
 *
 * <p>只做装配，不修改定义，也不触发任何执行。</p>
 */
public final class DefinitionViewFactory {

    private DefinitionViewFactory() {
    }

    /**
     * @param workflow 工作流定义
     * @return 定义视图
     */
    public static DefinitionView of(WorkflowDefinition workflow) {
        return of(workflow, workflow.validateReport());
    }

    /**
     * @param workflow 工作流定义
     * @param report   校验报告
     * @return 定义视图
     */
    public static DefinitionView of(WorkflowDefinition workflow, ValidationReport report) {
        List<NodeView> nodes = new ArrayList<>();
        for (NodeDefinition node : workflow.nodes()) {
            nodes.add(NodeView.of(node, workflow.regionOf(node.id()).orElse(null)));
        }
        List<EdgeView> edges = new ArrayList<>();
        workflow.edges().forEach(edge -> edges.add(EdgeView.of(edge)));
        List<DynamicView> dynamic = new ArrayList<>();
        workflow.dynamicPolicy().nodes().forEach(from -> {
            List<String> targets = workflow.dynamicPolicy().targetsOf(from);
            dynamic.add(new DynamicView(from, targets));
            targets.forEach(target -> edges.add(EdgeView.dynamic(from, target)));
        });
        List<RegionView> regions = new ArrayList<>();
        for (RegionDefinition region : workflow.regions()) {
            regions.add(RegionView.of(region));
        }
        return new DefinitionView("workflow", workflow.id(), workflow.version(), nodes, edges, regions, dynamic,
                report);
    }
}
