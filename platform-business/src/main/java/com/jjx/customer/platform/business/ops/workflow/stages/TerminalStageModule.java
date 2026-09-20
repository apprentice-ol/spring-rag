package com.jjx.customer.platform.business.ops.workflow.stages;

import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.TerminalKind;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.engine.core.EngineBuilder;
import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;
import com.jjx.customer.platform.business.ops.node.ConcludeExecutor;
import com.jjx.customer.platform.business.workflow.common.EscalateExecutor;
import java.util.Map;
import java.util.Set;

/**
 * 终态阶段：升级（带原因）与直答收尾。图上仅两个 terminal 节点，无循环、无槽位契约。
 */
public final class TerminalStageModule implements StageModule {

    /** 装配键。 */
    public static final String KEY = "terminal";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Set<String> provides() {
        return Set.of(OpsDiagnoseWorkflowFactory.ESCALATE_EXECUTOR,
                OpsDiagnoseWorkflowFactory.CONCLUDE_EXECUTOR);
    }

    @Override
    public void declareGraph(WorkflowBuilder workflowBuilder) {
        // terminalKind 声明是出口推导的依据（RunOutcomeMapper 优先读它，节点命名只是兜底）：
        // escalate = 移交（输出是移交说明而非答案），conclude = 常规收尾
        workflowBuilder.node(new CustomNodeDefinition(OpsDiagnoseWorkflowFactory.ESCALATE_NODE,
                        OpsDiagnoseWorkflowFactory.ESCALATE_EXECUTOR, Map.of(),
                        "escalate_reason", NodeMeta.empty().withAttribute("terminal", true)
                                .withAttribute(TerminalKind.META_KEY, TerminalKind.ESCALATE.name())))
                .node(new CustomNodeDefinition(OpsDiagnoseWorkflowFactory.CONCLUDE_NODE,
                        OpsDiagnoseWorkflowFactory.CONCLUDE_EXECUTOR, Map.of(),
                        "final_output", NodeMeta.empty().withAttribute("terminal", true)
                                .withAttribute(TerminalKind.META_KEY, TerminalKind.FINISH.name())));
    }

    @Override
    public void wireRuntime(EngineBuilder eb, SharedDeps deps) {
        eb.nodeExecutor(OpsDiagnoseWorkflowFactory.ESCALATE_EXECUTOR, new EscalateExecutor())
                .nodeExecutor(OpsDiagnoseWorkflowFactory.CONCLUDE_EXECUTOR, new ConcludeExecutor());
    }
}
