package com.jjx.customer.platform.business.ops.stages;

import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.engine.core.EngineBuilder;
import java.util.Optional;
import java.util.Set;

/**
 * 诊断流程的一个业务阶段模块：该阶段的图声明（节点/边/Region/槽）与引擎装配
 * （执行器/Prompt/守卫）全部收敛在一个实现类里——读懂一个阶段只需读一个文件。
 *
 * <p>两个贡献方法的生命周期不同：{@link #declareGraph} 是纯数据（build 期、无外部依赖，
 * 静态可测）；{@link #wireRuntime} 是装配期（需要 {@link SharedDeps} 运行环境）。
 * 组装顺序用 then 链表达（图上前向边），真正的迭代（问齐环、工具环、replan 回跳）
 * 必须落在声明了循环治理的 Region 内（内核 build 期 GRAPH_BACKEDGE_UNGOVERNED 校验兜底）。</p>
 */
public interface StageModule {

    /**
     * @return 语义键（intake / investigate / resolve / verify / terminal），装配 Map 与对账使用
     */
    String key();

    /**
     * @return 本阶段的图入口节点（下一阶段 replan continue 的接线目标）；非循环阶段为空
     */
    default Optional<String> entryNode() {
        return Optional.empty();
    }

    /**
     * @return 本模块认领的执行器注册名（装配期与图中实际引用对账，多退少补直接启动失败）
     */
    default Set<String> provides() {
        return Set.of();
    }

    /**
     * 声明图：节点、边、动态白名单、Region、槽位（纯数据，无外部依赖）。
     *
     * @param wf 工作流构建器
     */
    void declareGraph(WorkflowBuilder wf);

    /**
     * 装配引擎侧：执行器注册、Prompt 资产、迭代守卫。
     *
     * @param eb   引擎构建器
     * @param deps 运行环境依赖
     */
    default void wireRuntime(EngineBuilder eb, SharedDeps deps) {
        // 图专属阶段（如终态）可无运行装配
    }
}
