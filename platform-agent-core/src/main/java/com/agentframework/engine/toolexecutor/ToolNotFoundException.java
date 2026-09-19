package com.agentframework.engine.toolexecutor;

/** 工具未注册异常：调用了注册表中不存在的工具。 */
public class ToolNotFoundException extends RuntimeException {

    /** @param toolId 工具 id */
    public ToolNotFoundException(String toolId) {
        super("工具未注册：" + toolId);
    }
}
