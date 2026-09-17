package com.jjx.customer.platform.agent.framework.capability;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;

import java.util.Optional;
import java.util.Set;

/**
 * 能力开关配置 SPI：本次启用哪些能力。
 *
 * <p>返回 {@link Optional#empty()} = 默认全部声明能力启用；返回非空集合必须是声明的子集
 * （配置只能收窄，不能扩张——越界由能力求解抛错）。按 Agent 声明收窄，
 * 实现 typically 用「声明集 - 全局禁用集」的方式返回。</p>
 */
public interface CapabilityConfig {

    Optional<Set<AgentCapability>> enabledFor(Agent agent);

    /** 默认：全开。 */
    CapabilityConfig DEFAULT = agent -> Optional.empty();
}
