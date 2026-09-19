package com.agentframework.extension.sandbox;

import com.agentframework.extension.manifest.Isolation;

/** 沙箱不支持异常：请求的隔离级别在当前运行时不可用。 */
public class SandboxNotSupportedException extends RuntimeException {

    /** @param isolation 请求的隔离级别 */
    public SandboxNotSupportedException(Isolation isolation) {
        super("当前运行时未提供 " + isolation + " 隔离实现，请通过扩展注册表注册自定义 Sandbox");
    }
}
