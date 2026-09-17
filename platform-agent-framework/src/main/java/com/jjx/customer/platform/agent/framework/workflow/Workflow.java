package com.jjx.customer.platform.agent.framework.workflow;

import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;

import java.util.List;
import java.util.Set;

/**
 * Workflow（实现侧）：业务流程 = 范式的载体。
 *
 * <p>只声明，不执行、不产生副作用、不直接调用工具：工具白名单写在阶段里，
 * 执行全部由引擎驱动。</p>
 */
public interface Workflow {

    /** 流程标识（trace 指纹、路由、prompt 能力包绑定用）。 */
    String id();

    /** 输入契约：流程需要的槽位目录（缺必填项由引擎触发一次问齐）。 */
    List<SlotSpec> slots();

    /** 阶段序列：骨架 = 顺序 + when 跳过 + replan 检查点。 */
    List<WorkflowStageSpec> stages();

    /** 流程级 prompt key（阶段 system / replan / 抽槽）；详细装配见设计文档 §8。 */
    default List<String> promptKeys() {
        return List.of();
    }

    /**
     * 能力供给：本条流程实际能产出的元数据（能力三方模型的中间项）。
     * 产不出的能力不要声明——声明即承诺，交集不足时装配期报错。
     */
    default Set<AgentCapability> suppliedCapabilities() {
        return Set.of();
    }

    /** 答案生成 prompt 的 key（声明 STREAMING 能力时必须提供；管线据此流式生成）。 */
    default String answerPromptKey() {
        return null;
    }

    /**
     * 抽槽 prompt 的 key（null = 不抽槽）。
     *
     * <p>声明后引擎在槽位校验前经一次 LLM 调用从用户消息抽取空缺槽位
     * （已确认值优先，抽取只填空缺），使"补充轮不重问已给信息"闭环（O1/O2）。
     * 槽位目录与已确认值由引擎注入 system，prompt 只写抽取规则。</p>
     */
    default String slotExtractPromptKey() {
        return null;
    }

    /**
     * 阶段间 replan 检查点的 prompt key（null = 无 replan，纯顺序骨架）。
     *
     * <p>声明后每个非末阶段完成后经一次 LLM 三态裁决：
     * continue（按骨架继续）/ adjust（携带修正要求重跑本阶段，上限 {@link #maxAdjustRetries()}）/
     * escalate（升级追问）。裁决调用计入流程预算。</p>
     */
    default String replanPromptKey() {
        return null;
    }

    /** adjust 重跑次数上限（replan 检查点；默认 1，超限转升级）。 */
    default int maxAdjustRetries() {
        return 1;
    }

    /** 流程级 LLM 调用预算（0 或负数 = 不限）。 */
    default int maxLlmCalls() {
        return 0;
    }

    /** 流程级超时（秒；0 或负数 = 不限）。 */
    default int timeoutSeconds() {
        return 0;
    }
}
