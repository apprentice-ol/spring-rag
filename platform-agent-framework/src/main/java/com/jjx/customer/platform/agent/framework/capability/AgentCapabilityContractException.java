package com.jjx.customer.platform.agent.framework.capability;

/**
 * 能力契约违反：配置扩张、或声明+启用但 Workflow 供不出对应元数据。
 *
 * <p>装配期/运行期快速失败——不静默降级是框架红线。</p>
 */
public class AgentCapabilityContractException extends RuntimeException {

    public AgentCapabilityContractException(String message) {
        super(message);
    }
}
