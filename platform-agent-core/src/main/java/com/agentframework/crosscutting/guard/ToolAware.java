package com.agentframework.crosscutting.guard;

/**
 * 工具调用载荷契约：任何需要被 {@code ToolPermissionGuard} 校验的载荷实现该接口即可。
 *
 * <p>契约定义在横切层，工具执行器实现它，从而避免横切层依赖引擎层。</p>
 */
public interface ToolAware {

    /** @return 被调用的工具 id */
    String toolId();
}
