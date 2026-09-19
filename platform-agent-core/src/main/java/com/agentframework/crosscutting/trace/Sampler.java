package com.agentframework.crosscutting.trace;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 采样器：决定一次链路是否需要被记录，用于控制追踪开销。
 */
@FunctionalInterface
public interface Sampler {

    /**
     * @param name       链路名称
     * @param attributes 链路属性
     * @return 是否采样
     */
    boolean shouldSample(String name, Map<String, Object> attributes);

    /**
     * @return 全部采样
     */
    static Sampler alwaysOn() {
        return (name, attributes) -> true;
    }

    /**
     * @return 全部不采样
     */
    static Sampler alwaysOff() {
        return (name, attributes) -> false;
    }

    /**
     * @param ratio 采样比例，取值 0~1
     * @return 按比例采样
     */
    static Sampler ratio(double ratio) {
        double bounded = Math.max(0d, Math.min(1d, ratio));
        return (name, attributes) -> ThreadLocalRandom.current().nextDouble() < bounded;
    }
}
