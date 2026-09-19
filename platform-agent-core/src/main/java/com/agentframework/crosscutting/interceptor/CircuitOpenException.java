package com.agentframework.crosscutting.interceptor;

/** 熔断器打开异常：目标连续失败后，管道在冷却期内直接拒绝调用。 */
public class CircuitOpenException extends RuntimeException {

    /**
     * @param key           熔断分组键
     * @param remainingMillis 距离开启状态结束的剩余毫秒数
     */
    public CircuitOpenException(String key, long remainingMillis) {
        super("熔断器已打开，拒绝调用：" + key + "（剩余冷却 " + remainingMillis + " ms）");
    }
}
