package com.agentframework.engine.workflowruntime.executors;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.workflow.Expression;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.policy.PolicyAttributes;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolExecutor;
import com.agentframework.engine.toolexecutor.ToolInvocation;
import com.agentframework.engine.toolexecutor.ToolRegistry;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.Session;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具节点执行器：解析参数 → 调用工具执行器 → 把结果写入槽位。
 *
 * <p>参数支持字面量与 {@code ${表达式}} 引用，引用在运行时按当前槽位求值。</p>
 */
public final class ToolNodeExecutor implements NodeExecutor {

    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;

    /**
     * @param toolExecutor 工具执行器
     * @param toolRegistry 工具注册表
     */
    public ToolNodeExecutor(ToolExecutor toolExecutor, ToolRegistry toolRegistry) {
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public NodeType type() {
        return NodeType.TOOL;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        ToolNodeDefinition toolNode = (ToolNodeDefinition) node;
        if (toolRegistry.resolve(toolNode.toolRef(), toolNode.toolVersion()).isEmpty()) {
            return NodeResult.failed(node.id(), "未注册工具：" + toolNode.toolRef());
        }
        Session session = context.session();
        Map<String, Object> arguments = resolveArguments(toolNode.arguments(), context);
        ToolInvocation invocation = ToolInvocation.of(toolNode.toolRef(), arguments)
                .withVersion(toolNode.toolVersion())
                .withOwner(session.id(), node.id());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("toolId", toolNode.toolRef());
        attributes.put(InterceptorAttributes.TRACE, context.trace());
        attributes.put(InterceptorAttributes.TRACE_PARENT, context.parentSpan());
        if (context.agent() != null) {
            attributes.put(DefaultToolExecutor.TOOL_POLICY_ATTRIBUTE, context.agent().policies().tool());
            attributes.put(DefaultToolExecutor.QUOTA_POLICY_ATTRIBUTE, context.agent().policies().quota());
        }
        Object resolvedPolicy = context.attributes().get(PolicyAttributes.RESOLVED);
        if (resolvedPolicy != null) {
            attributes.put(PolicyAttributes.RESOLVED, resolvedPolicy);
        }
        ToolContext toolContext = ToolContext.of(session.id(), node.id())
                .withTraceId(session.traceId())
                .withWorkspace(context.workspace())
                .withSlots(context.slots())
                .withAttributes(attributes);
        ToolResult result = toolExecutor.execute(invocation, toolContext);
        if (!result.success()) {
            return NodeResult.failed(node.id(), "工具 " + toolNode.toolRef() + " 执行失败：" + result.error());
        }
        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put(toolNode.outputSlot(), result.output());
        if (!result.data().isEmpty()) {
            writes.put(toolNode.outputSlot() + WorkflowDefinition.DATA_SLOT_SUFFIX, result.data());
        }
        return NodeResult.completed(node.id(), result.output(), writes)
                .withMessage(Message.tool(toolNode.toolRef(), result.output()))
                .withMetadata("tool", toolNode.toolRef())
                .withMetadata("durationMs", result.duration().toMillis());
    }

    /**
     * 解析工具参数，支持 {@code ${...}} 引用。
     *
     * @param declared 声明的参数
     * @param context  节点上下文
     * @return 实际参数
     */
    private Map<String, Object> resolveArguments(Map<String, Object> declared, NodeContext context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        Map<String, Object> variables = context.variables();
        declared.forEach((key, value) -> resolved.put(key, resolveValue(value, variables)));
        return resolved;
    }

    /**
     * 解析单个参数值。
     *
     * @param value     声明值
     * @param variables 变量表
     * @return 解析结果
     */
    private Object resolveValue(Object value, Map<String, Object> variables) {
        if (value instanceof String text && text.startsWith("${") && text.endsWith("}")) {
            String expression = text.substring(2, text.length() - 1).trim();
            return Expression.value(expression, variables);
        }
        return value;
    }
}
