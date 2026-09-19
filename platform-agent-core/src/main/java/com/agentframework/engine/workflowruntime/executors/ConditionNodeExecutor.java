package com.agentframework.engine.workflowruntime.executors;

import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.workflow.Expression;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import java.util.Map;

/**
 * 条件节点执行器：求值分支表达式并返回路由结果。
 *
 * <p>纯路由步骤，不产生输出、不写槽位，便于把控制流与数据流分开。</p>
 */
public final class ConditionNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.CONDITION;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        ConditionNodeDefinition condition = (ConditionNodeDefinition) node;
        Map<String, Object> variables = context.variables();
        for (ConditionNodeDefinition.Branch branch : condition.branches()) {
            if (branch.isDefault() || Expression.evaluate(branch.expression(), variables)) {
                return NodeResult.route(node.id(), branch.target());
            }
        }
        return NodeResult.failed(node.id(), "条件节点没有任何分支命中，请检查表达式或补充默认分支");
    }
}
