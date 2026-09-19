package com.agentframework.definition.node;

/**
 * 工作流中的一个步骤（纯定义）。
 *
 * <p>运行时由引擎按 {@link #type()} 选择对应的 {@code NodeExecutor} 执行；
 * 使用密封接口保证新增节点类型时必须显式声明，避免定义与执行器脱节。</p>
 */
public sealed interface NodeDefinition
        permits LlmNodeDefinition, ToolNodeDefinition, ConditionNodeDefinition, ParallelNodeDefinition,
        HumanNodeDefinition, SubWorkflowNodeDefinition, CustomNodeDefinition {

    /** @return 节点 id，在同一 Workflow 内唯一 */
    String id();

    /** @return 节点类型 */
    NodeType type();

    /** @return 横切信息（属性、守卫、过滤器） */
    NodeMeta meta();

    /** @return 是否被标记为终止节点 */
    default boolean isTerminal() {
        return meta().booleanAttribute("terminal", false);
    }
}
