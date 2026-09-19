package com.agentframework.engine.workflowruntime.executors;

import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import java.util.Optional;
import java.util.function.Function;

/**
 * 自定义节点执行器：按 {@code executorRef} 委托给已注册的执行器。
 *
 * <p>解析函数在运行时才被调用，因此注册顺序不受限制（避免构造期循环依赖）。</p>
 */
public final class CustomNodeExecutor implements NodeExecutor {

    private final Function<String, Optional<NodeExecutor>> resolver;

    /**
     * @param resolver 名称到执行器的解析函数
     */
    public CustomNodeExecutor(Function<String, Optional<NodeExecutor>> resolver) {
        this.resolver = resolver;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        CustomNodeDefinition custom = (CustomNodeDefinition) node;
        NodeExecutor delegate = resolver.apply(custom.executorRef()).orElse(null);
        if (delegate == null) {
            return NodeResult.failed(node.id(), "未注册自定义节点执行器：" + custom.executorRef());
        }
        return delegate.execute(node, context);
    }
}
