package com.jjx.customer.platform.agent.framework.result;

import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;

import java.util.Map;

/**
 * 结果元数据扩展点（设计 §2.1/§5）：开发者经本接口向 {@link ExecutionResult#metadata()}
 * 追加自定义元数据（自定义能力位随元数据契约版本化）。
 *
 * <p>贡献时机：引擎装配终态结果时收集（观测扩展，不参与流程控制）；抛异常只丢该贡献者，不阻断执行。</p>
 */
public interface MetadataContributor {

    /** 贡献标识（进 metadata 的 key）。 */
    String key();

    /** 贡献内容（null = 本次无贡献）。 */
    Map<String, Object> contribute(ExecutionPlan plan, ExecutionResult result);
}
