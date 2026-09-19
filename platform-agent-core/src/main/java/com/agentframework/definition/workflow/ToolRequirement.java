package com.agentframework.definition.workflow;

/**
 * 工具依赖声明：工作流期望宿主提供的工具，使装配问题在加载期而非运行期暴露。
 *
 * @param toolId       工具 id
 * @param versionRange 版本范围，缺省为 latest
 * @param optional     是否可选
 */
public record ToolRequirement(String toolId, String versionRange, boolean optional) {

    public ToolRequirement {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("tool requirement id is required");
        }
        versionRange = versionRange == null || versionRange.isBlank() ? "latest" : versionRange;
    }

    /**
     * @param toolId 工具 id
     * @return 必选依赖
     */
    public static ToolRequirement required(String toolId) {
        return new ToolRequirement(toolId, null, false);
    }

    /**
     * @param toolId 工具 id
     * @return 可选依赖
     */
    public static ToolRequirement optional(String toolId) {
        return new ToolRequirement(toolId, null, true);
    }
}
