package com.jjx.customer.platform.agent.framework.capability;

import com.jjx.customer.platform.agent.framework.capability.AgentCapabilityContractException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 能力三方模型求解：生效能力 = 声明 ∩ 供给 ∩ 开关。
 *
 * <ul>
 *   <li>声明（Agent）：能力上界，只有这里出现过的能力才可能存在；</li>
 *   <li>供给（Workflow）：本条流程实际能产出的元数据；</li>
 *   <li>开关（配置）：这次要不要真的执行；<b>只能收窄</b>——{@code null} 表示默认全开，
 *       非 null 时必须是声明的子集，越界即报错。</li>
 * </ul>
 *
 * <p>交集不足（声明且启用，但 Workflow 供不出）⇒ {@link AgentCapabilityContractException}，
 * 装配期即失败，避免"以为会流式/会写缓存"的静默偏差。</p>
 */
public final class AgentCapabilityResolution {

    private AgentCapabilityResolution() {
    }

    /**
     * @param declared 声明集合（Agent 能力上界）
     * @param supplied 供给集合（Workflow 能产出的元数据）
     * @param enabled  配置开关；null = 默认全部声明能力启用
     */
    public static Set<AgentCapability> resolve(Set<AgentCapability> declared,
                                               Set<AgentCapability> supplied,
                                               Set<AgentCapability> enabled) {
        Set<AgentCapability> declaredSet = declared == null ? Set.of() : Set.copyOf(declared);
        Set<AgentCapability> suppliedSet = supplied == null ? Set.of() : Set.copyOf(supplied);
        Set<AgentCapability> enabledSet = enabled == null ? declaredSet : Set.copyOf(enabled);
        Set<AgentCapability> expanded = new LinkedHashSet<>(enabledSet);
        expanded.removeAll(declaredSet);
        if (!expanded.isEmpty()) {
            throw new AgentCapabilityContractException(
                    "能力配置越界：开启了 Agent 未声明的能力 " + expanded + "（配置只能收窄，不能扩张）");
        }

        Set<AgentCapability> enabledWithinDeclared = new LinkedHashSet<>(enabledSet);
        enabledWithinDeclared.retainAll(declaredSet);

        Set<AgentCapability> missingSupply = new LinkedHashSet<>(enabledWithinDeclared);
        missingSupply.removeAll(suppliedSet);
        if (!missingSupply.isEmpty()) {
            throw new AgentCapabilityContractException(
                    "能力供给缺失：声明且已启用 " + missingSupply + "，但该 Workflow 供不出对应元数据"
                            + "（修 Workflow 供给，或关掉该能力开关）");
        }

        Set<AgentCapability> effective = new LinkedHashSet<>(enabledWithinDeclared);
        effective.retainAll(suppliedSet);
        return Set.copyOf(effective);
    }
}
