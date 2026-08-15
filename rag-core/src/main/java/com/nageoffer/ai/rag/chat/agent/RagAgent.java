package com.nageoffer.ai.rag.chat.agent;

/**
 * RAG agent 统一接口。每种范式（Naive/ReAct）是一个实现，
 * 由 {@link AgentRegistry} 按 {@link #getType()} 注册，可插拔切换。
 *
 * <p><b>只插「检索编排层」</b>：本接口负责"检索什么、检索几次、何时停"的策略，
 * 返回最终上下文块 + 轨迹 + 裁决；回答/流式由 StreamChatPipeline 统一处理，与 agent 解耦。
 *
 * <p>实现必须是同步方法（内部 LLM 调用走 {@code .call()}、检索走同步 join），
 * 保证流式回答在 {@link #planAndRetrieve} 返回后才开始。
 */
public interface RagAgent {

    /** 范式标识，对齐 {@link RagParadigm#getCode()}（"naive" / "react"）。 */
    String getType();

    /**
     * 编排检索并返回最终上下文块 + 轨迹 + 裁决。
     *
     * @param request agent 入参
     * @return 检索结果（含 finalChunks / trace / verdict），永不返回 null
     */
    AgentRetrievalResult planAndRetrieve(AgentRequest request);
}
