package com.agentframework.engine.toolexecutor;

import com.agentframework.definition.tool.ToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认工具注册表：以工具 id 为键维护实现与定义。
 */
public final class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    @Override
    public void register(Tool tool) {
        if (tool == null || tool.id() == null) {
            return;
        }
        synchronized (tools) {
            tools.put(tool.id(), tool);
        }
    }

    @Override
    public void register(ToolDefinition definition, Tool tool) {
        if (definition == null || tool == null) {
            return;
        }
        synchronized (tools) {
            tools.put(definition.id(), tool);
            definitions.put(definition.id(), definition);
        }
    }

    @Override
    public Optional<Tool> resolve(String toolId, String version) {
        synchronized (tools) {
            return Optional.ofNullable(tools.get(toolId));
        }
    }

    @Override
    public List<Tool> list() {
        synchronized (tools) {
            return List.copyOf(tools.values());
        }
    }

    @Override
    public Optional<ToolDefinition> definition(String toolId) {
        synchronized (tools) {
            return Optional.ofNullable(definitions.get(toolId));
        }
    }

    @Override
    public List<ToolDefinition> definitions() {
        synchronized (tools) {
            return List.copyOf(definitions.values());
        }
    }

    /**
     * 注销工具。
     *
     * @param toolId 工具 id
     * @return 是否确实移除
     */
    public boolean unregister(String toolId) {
        synchronized (tools) {
            definitions.remove(toolId);
            return tools.remove(toolId) != null;
        }
    }

    /** @return 已注册工具数量 */
    public int size() {
        synchronized (tools) {
            return tools.size();
        }
    }
}
