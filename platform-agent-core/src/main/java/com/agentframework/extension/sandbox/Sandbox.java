package com.agentframework.extension.sandbox;

import com.agentframework.extension.manifest.Isolation;

/**
 * 沙箱扩展点：插件代码执行时的隔离边界。
 *
 * <p>内核默认只提供进程内实现；更强隔离由扩展通过 {@code Sandbox} 扩展点提供。</p>
 */
public interface Sandbox {

    /** @return 隔离级别 */
    Isolation level();

    /**
     * 执行请求。
     *
     * @param request 沙箱请求
     * @return 执行结果
     */
    SandboxResult execute(SandboxRequest request);
}
