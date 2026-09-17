package com.jjx.customer.platform.agent.framework.tool;

import com.jjx.customer.platform.agent.framework.tool.AgentTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolKind;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表与调用收口（引擎内建）。
 *
 * <p>硬约束：BaseTool 名是保留字，ExtensionTool 撞名 = 启动即失败；调用统一经本类
 * （兜 Throwable + 便于台账/观测），业务侧不直接调工具实现。</p>
 */
public class ToolRegistry {

    private final Map<String, AgentTool> byName = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> tools) {
        List<AgentTool> ordered = new ArrayList<>(tools == null ? List.of() : tools);
        // 先注册 BaseTool（保留字），再注册 ExtensionTool，撞名即失败
        ordered.sort((a, b) -> a.kind() == ToolKind.BASE && b.kind() != ToolKind.BASE ? -1
                : a.kind() != ToolKind.BASE && b.kind() == ToolKind.BASE ? 1 : 0);
        for (AgentTool tool : ordered) {
            AgentTool previous = byName.putIfAbsent(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("工具名冲突（BaseTool 名为保留字）: " + tool.name()
                        + "（" + previous.getClass().getName() + " vs " + tool.getClass().getName() + "）");
            }
        }
    }

    public Optional<AgentTool> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** 全部 BaseTool（引擎按阶段控制开关注入，不由白名单管理）。 */
    public List<AgentTool> baseTools() {
        return byName.values().stream().filter(t -> t.kind() == ToolKind.BASE).toList();
    }

    /** 全部 ExtensionTool。 */
    public List<AgentTool> extensionTools() {
        return byName.values().stream().filter(t -> t.kind() == ToolKind.EXTENSION).toList();
    }

    /** 统一调用收口：未注册与异常都转成 {@link ToolResult#error(String)}（错误也是观测）。 */
    public ToolResult invoke(ToolInvocation invocation) {
        AgentTool tool = byName.get(invocation.toolName());
        if (tool == null) {
            return ToolResult.error("工具未注册: " + invocation.toolName());
        }
        try {
            ToolResult result = tool.execute(invocation);
            return result == null ? ToolResult.error("工具返回 null: " + invocation.toolName()) : result;
        } catch (Exception e) {
            return ToolResult.error("工具执行异常(" + invocation.toolName() + "): " + e.getMessage());
        } catch (Throwable t) {
            throw t;
        }
    }
}
