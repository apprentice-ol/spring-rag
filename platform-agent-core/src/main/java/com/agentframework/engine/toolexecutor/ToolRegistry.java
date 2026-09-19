package com.agentframework.engine.toolexecutor;

import com.agentframework.definition.tool.ToolDefinition;
import java.util.List;
import java.util.Optional;

/**
 * 工具注册表：实现与定义的绑定中心。
 */
public interface ToolRegistry extends ToolProvider {

    /**
     * 注册工具实现。
     *
     * @param tool 工具实现
     */
    void register(Tool tool);

    /**
     * 注册工具实现并绑定定义。
     *
     * @param definition 工具定义
     * @param tool       工具实现
     */
    void register(ToolDefinition definition, Tool tool);

    /**
     * 读取工具定义。
     *
     * @param toolId 工具 id
     * @return 工具定义
     */
    Optional<ToolDefinition> definition(String toolId);

    /** @return 全部工具定义 */
    List<ToolDefinition> definitions();
}
