package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.capability.AgentCapability;

import com.jjx.customer.platform.agent.framework.workflow.Workflow;

import java.util.Set;
import java.util.List;

/**
 * Agent 形态根接口（抽象侧）：身份 + 能力上界 + 组合持有的 Workflow。
 *
 * <p><b>刻意不声明执行方法</b>：执行权在引擎（{@code engine.WorkflowEngine}），因此
 * "凡是 workflow 就自动获得同一套横切能力（错误处理/观测/校验/预算/会话）"是结构保证而非约定。</p>
 *
 * <p>与 Workflow 是<b>组合</b>关系（Bridge），不是实现关系：两棵树独立扩展，
 * 靠绑定（配置）组合出范式；范式 = {@code (Agent, Workflow)} 绑定。</p>
 */
public interface Agent {

    /** 全局唯一标识（路由、trace 指纹、缓存 key 用）。 */
    String id();

    /** 展示名（控制台/前端）。 */
    default String label() {
        return id();
    }

    /** 能力描述（控制台/前端范式选择器展示；空串回退展示 label）。 */
    default String description() {
        return "";
    }

    /** 意图域：引擎路由的匹配键（域清单由各 Agent 注册后动态拼装）。 */
    String intentDomain();

    /** 能力上界：可交付能力集合。实际生效 = 声明 ∩ Workflow 供给 ∩ 配置开关。 */
    Set<AgentCapability> capabilities();

    /**
     * Agent 人格层 prompt key（角色、语气、交付风格）；与 Workflow 任务层是<b>补充增强</b>关系，
     * 装配时按"链路 → Agent → Workflow"顺序相加，跨层同 key 装配期报错。
     */
    default List<String> promptKeys() {
        return List.of();
    }

    /** 组合（Bridge）：本 Agent 持有的业务 Workflow。 */
    Workflow workflow();
}
