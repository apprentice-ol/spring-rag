package com.agentframework.runtime.event;

/** 框架内置事件主题常量。 */
public final class Topics {

    /** 通配主题：订阅全部事件。 */
    public static final String ALL = "*";
    public static final String SESSION_STARTED = "session.started";
    public static final String SESSION_RESUMED = "session.resumed";
    public static final String SESSION_SUSPENDED = "session.suspended";
    public static final String SESSION_COMPLETED = "session.completed";
    public static final String SESSION_FAILED = "session.failed";
    public static final String SESSION_CANCELLED = "session.cancelled";
    public static final String NODE_STARTED = "node.started";
    public static final String NODE_COMPLETED = "node.completed";
    public static final String NODE_FAILED = "node.failed";
    public static final String TOOL_INVOKED = "tool.invoked";
    public static final String TOOL_COMPLETED = "tool.completed";
    public static final String GUARD_DENIED = "guard.denied";
    /** 守卫失败开放：守卫异常但策略声明 ALLOW，必须留痕。 */
    public static final String GUARD_FAILED_OPEN = "guard.failed_open";
    /** 循环被守卫中断：跳过回边并沿前向边继续。 */
    public static final String LOOP_BREAK = "loop.break";
    /** 动态路由被采纳。 */
    public static final String ROUTE_DYNAMIC = "route.dynamic";
    /** 动态路由被拒绝，回退到静态路由。 */
    public static final String ROUTE_REJECTED = "route.rejected";
    /** 模型流式 token 片段（payload: nodeId, text）。仅声明了流式的 LLM 节点发布。 */
    public static final String MODEL_TOKEN = "model.token";
    /** 定义草稿保存。 */
    public static final String DEFINITION_SAVED = "definition.saved";
    /** 定义发布。 */
    public static final String DEFINITION_PUBLISHED = "definition.published";
    /** 定义归档。 */
    public static final String DEFINITION_ARCHIVED = "definition.archived";
    public static final String WORKFLOW_COMPLETED = "workflow.completed";
    public static final String CACHE_HIT = "cache.hit";
    public static final String PLUGIN_LOADED = "plugin.loaded";

    private Topics() {
    }
}
