package com.jjx.customer.platform.agent.framework.capability;

import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.capability.AgentCapabilityResolution;
import com.jjx.customer.platform.agent.framework.capability.AgentCapabilityContractException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCapabilityResolutionTest {

    private static final Set<AgentCapability> DECLARED = Set.of(
            AgentCapability.STREAMING, AgentCapability.CITATIONS,
            AgentCapability.ANSWER_CACHE, AgentCapability.RETRIEVAL_METRICS);

    private static final Set<AgentCapability> SUPPLIED = Set.of(
            AgentCapability.STREAMING, AgentCapability.CITATIONS, AgentCapability.RETRIEVAL_METRICS);

    @Test
    void 默认全开_声明与供给一致时取交集() {
        Set<AgentCapability> effective = AgentCapabilityResolution.resolve(
                Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS),
                Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS, AgentCapability.RETRIEVAL_METRICS),
                null);

        assertEquals(Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS), effective);
    }

    @Test
    void 默认全开但供给缺失_报错而非静默丢弃() {
        assertThrows(AgentCapabilityContractException.class,
                () -> AgentCapabilityResolution.resolve(DECLARED, SUPPLIED, null));
    }

    @Test
    void 显式关掉缺供给的能力_可以正常生效() {
        Set<AgentCapability> effective = AgentCapabilityResolution.resolve(DECLARED, SUPPLIED,
                Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS,
                        AgentCapability.RETRIEVAL_METRICS));

        assertEquals(Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS,
                AgentCapability.RETRIEVAL_METRICS), effective);
    }

    @Test
    void 配置越界_开启未声明能力_报错() {
        assertThrows(AgentCapabilityContractException.class, () -> AgentCapabilityResolution.resolve(
                Set.of(AgentCapability.STREAMING),
                Set.of(AgentCapability.STREAMING),
                Set.of(AgentCapability.STREAMING, AgentCapability.SEMANTIC_CACHE)));
    }

    @Test
    void 供给缺失_声明且启用但流程产不出_报错() {
        assertThrows(AgentCapabilityContractException.class, () -> AgentCapabilityResolution.resolve(
                Set.of(AgentCapability.STREAMING),
                Set.of(),
                Set.of(AgentCapability.STREAMING)));
    }

    @Test
    void 空集合视为无能力() {
        assertEquals(Set.of(), AgentCapabilityResolution.resolve(null, null, null));
    }
}
