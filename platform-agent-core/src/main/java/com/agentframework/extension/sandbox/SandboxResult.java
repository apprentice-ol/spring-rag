package com.agentframework.extension.sandbox;

/**
 * 沙箱调用结果。
 *
 * @param success  是否成功
 * @param output   输出值
 * @param error    错误信息，成功时为 null
 * @param durationMillis 耗时毫秒
 */
public record SandboxResult(boolean success, Object output, String error, long durationMillis) {

    /**
     * @param output         输出值
     * @param durationMillis 耗时毫秒
     * @return 成功结果
     */
    public static SandboxResult ok(Object output, long durationMillis) {
        return new SandboxResult(true, output, null, durationMillis);
    }

    /**
     * @param error          错误信息
     * @param durationMillis 耗时毫秒
     * @return 失败结果
     */
    public static SandboxResult failed(String error, long durationMillis) {
        return new SandboxResult(false, null, error, durationMillis);
    }
}
