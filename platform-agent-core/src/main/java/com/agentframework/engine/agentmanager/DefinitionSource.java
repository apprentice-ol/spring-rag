package com.agentframework.engine.agentmanager;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Optional;

/**
 * 定义来源：Agent / Workflow / Prompt / Tool 定义的读取出口。
 *
 * <p>实现可来自内存、数据库、配置中心或代码仓库；引擎只按 {@code id + version} 取用。</p>
 */
public interface DefinitionSource {

    /**
     * @param id      Agent id
     * @param version 版本号，{@code latest} 表示最新版本
     * @return Agent 定义
     */
    Optional<AgentDefinition> agent(String id, String version);

    /**
     * @param id      工作流 id
     * @param version 版本号，{@code latest} 表示最新版本
     * @return 工作流定义
     */
    Optional<WorkflowDefinition> workflow(String id, String version);

    /**
     * @param id      Prompt id
     * @param version 版本号
     * @return Prompt 定义
     */
    Optional<PromptDefinition> prompt(String id, String version);

    /**
     * @param id      工具 id
     * @param version 版本号
     * @return 工具定义
     */
    Optional<ToolDefinition> tool(String id, String version);

    /** @return 全部 Agent 定义 */
    List<AgentDefinition> agents();
}
