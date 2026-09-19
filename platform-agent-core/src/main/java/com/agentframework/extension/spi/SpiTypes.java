package com.agentframework.extension.spi;

/**
 * 扩展点名称常量。
 *
 * <p>按用途分组：定义型、执行型、数据型、控制型、基础设施型、治理型。</p>
 */
public final class SpiTypes {

    // 定义型
    public static final String PROMPT_PROVIDER = "PromptProvider";
    public static final String WORKFLOW_DSL = "WorkflowDSL";
    public static final String AGENT_TEMPLATE = "AgentTemplate";

    // 执行型
    public static final String NODE_EXECUTOR = "NodeExecutor";
    public static final String TOOL_PROVIDER = "ToolProvider";
    public static final String MODEL_PROVIDER = "ModelProvider";

    // 数据型
    public static final String FILTER = "Filter";
    public static final String SERIALIZER = "Serializer";
    public static final String MEMORY_PROVIDER = "MemoryProvider";
    public static final String SLOT_CODEC = "SlotCodec";

    // 控制型
    public static final String INTERCEPTOR = "Interceptor";
    public static final String GUARD = "Guard";
    public static final String ROUTER = "Router";

    // 基础设施型
    public static final String CACHE_STORE = "CacheStore";
    public static final String TRACE_EXPORTER = "TraceExporter";
    public static final String WORKSPACE_PROVIDER = "WorkspaceProvider";
    public static final String SESSION_STORE = "SessionStore";

    // 治理型
    public static final String POLICY = "Policy";
    public static final String QUOTA = "Quota";
    public static final String AUDIT = "Audit";
    public static final String EVENT_HANDLER = "EventHandler";

    private SpiTypes() {
    }
}
