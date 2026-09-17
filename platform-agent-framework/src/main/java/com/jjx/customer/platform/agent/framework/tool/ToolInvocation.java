package com.jjx.customer.platform.agent.framework.tool;

import com.jjx.customer.platform.agent.framework.model.ToolCall;
import com.jjx.customer.platform.agent.framework.node.NodeContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工具调用：工具名 + 参数 + 节点上下文（槽位/请求/能力等运行态，不依赖 ThreadLocal）。
 */
public record ToolInvocation(String toolName, Map<String, Object> args, NodeContext context) {

    public ToolInvocation {
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    /**
     * 由模型声明的工具调用派生（Loop 节点路径）。
     *
     * <p>工具入参约定收口在这里：{@code input} 缺省时补用户原文，保证工具总能拿到用户原话；
     * 模型显式给的 {@code input} 优先（不覆盖）。</p>
     */
    public static ToolInvocation of(ToolCall call, NodeContext context) {
        return of(call.toolName(), call.args(), context);
    }

    /** 由工具名 + 参数派生（确定性节点路径）。约定同上：{@code input} 缺省补用户原文。 */
    public static ToolInvocation of(String toolName, Map<String, Object> args, NodeContext context) {
        Map<String, Object> merged = new LinkedHashMap<>(args == null ? Map.of() : args);
        if (context != null && context.request() != null && context.request().input() != null) {
            merged.putIfAbsent("input", context.request().input());
        }
        return new ToolInvocation(toolName, merged, context);
    }

    public String str(String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public int intOr(String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
