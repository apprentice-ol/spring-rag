package com.agentframework.definition.region;

/**
 * 范式标签：Region 描述性画像的取值。
 *
 * <p>范式不参与路由，只用于治理、评估与观测的归类。</p>
 */
public enum Paradigm {

    /** 意图分类与分支选择。 */
    ROUTER,

    /** 多源并行检索与汇聚。 */
    PARALLEL_RETRIEVAL,

    /** 反思、批判与重写循环。 */
    REFLECTION,

    /** 需要人工审批或输入的环节。 */
    HUMAN_IN_LOOP,

    /** 工具调用密集环节。 */
    TOOL_CALL,

    /** 自定义范式。 */
    CUSTOM
}
