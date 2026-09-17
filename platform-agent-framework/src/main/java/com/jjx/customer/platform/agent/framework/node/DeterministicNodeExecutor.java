package com.jjx.customer.platform.agent.framework.node;

import com.jjx.customer.platform.agent.framework.node.NodeContext;
import com.jjx.customer.platform.agent.framework.result.ContextArtifact;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.agent.framework.node.NodeKind;
import com.jjx.customer.platform.agent.framework.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性节点：按声明顺序直接调用扩展工具，零 LLM 决策（单次检索、固定校验等）。
 *
 * <p>工具参数 = 请求输入 + 预填属性（业务侧通过 inputMapping 语义在属性里传参）。</p>
 */
public class DeterministicNodeExecutor implements NodeExecutor {

    private final ToolRegistry toolRegistry;

    public DeterministicNodeExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public NodeKind nodeKind() {
        return NodeKind.DETERMINISTIC;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        Map<String, Object> args = new LinkedHashMap<>(context.plan().prefill());

        StringBuilder text = new StringBuilder();
        List<ContextArtifact> artifacts = new ArrayList<>();
        int toolCalls = 0;
        for (String toolName : context.stage().extensionTools()) {
            ToolResult result = toolRegistry.invoke(ToolInvocation.of(toolName, args, context));
            toolCalls++;
            if (!result.ok()) {
                throw new IllegalStateException("确定性节点工具失败(" + toolName + "): " + result.error());
            }
            if (result.content() != null) {
                text.append(result.content()).append('\n');
            }
            artifacts.addAll(result.artifacts());
        }
        return NodeResult.ok(text.toString().stripTrailing(), 0, toolCalls, artifacts);
    }
}
