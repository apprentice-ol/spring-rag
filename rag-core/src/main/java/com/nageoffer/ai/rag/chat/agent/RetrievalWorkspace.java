package com.nageoffer.ai.rag.chat.agent;

import com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 检索工作区（普通可变类，agent 方法内 new）。
 * <p>承载一次 agent 编排过程中累积的检索结果：ref→chunk 映射 + 已选中的 finalChunks + 工具调用计数。
 * <p>ReAct 范式经 {@code ToolContext} 把它传给 @Tool 方法，保证并发对照（同进程跑 naive+react）互不污染。
 */
public class RetrievalWorkspace {

    /** ref 编号 → chunk（ref 从 1 递增，给 LLM/grade 引用） */
    private final Map<Integer, RetrievedChunk> byRef = new LinkedHashMap<>();

    /** 已选入最终上下文的 chunk（去重） */
    private final List<RetrievedChunk> selected = new ArrayList<>();

    /** 最近一次 retrieve 的原始通道结果（给前端展示通道命中） */
    private MultiChannelRetrievalEngine.RetrievalResult lastRetrieval;

    /** 工具调用计数（ReAct maxSteps 守卫用） */
    private int toolCallCount = 0;

    /** ReAct finish 标记 */
    private boolean finished = false;

    /** 注册一个 chunk，返回分配的 ref 编号。 */
    public int register(RetrievedChunk c) {
        int ref = byRef.size() + 1;
        byRef.put(ref, c);
        return ref;
    }

    public RetrievedChunk get(int ref) {
        return byRef.get(ref);
    }

    /** 反查 chunk 的 ref（identity 匹配），找不到返回 -1。 */
    public int refOf(RetrievedChunk c) {
        for (Map.Entry<Integer, RetrievedChunk> e : byRef.entrySet()) {
            if (e.getValue() == c) {
                return e.getKey();
            }
        }
        return -1;
    }

    public Collection<RetrievedChunk> all() {
        return byRef.values();
    }

    public void select(RetrievedChunk c) {
        if (!selected.contains(c)) {
            selected.add(c);
        }
    }

    /** 最终上下文快照（new List，外部安全使用）。 */
    public List<RetrievedChunk> snapshot() {
        return new ArrayList<>(selected);
    }

    /** 清空已选片段（ReAct rerank 后重建 selected 用）。 */
    public void clearSelected() {
        selected.clear();
    }

    public MultiChannelRetrievalEngine.RetrievalResult getLastRetrieval() {
        return lastRetrieval;
    }

    public void setLastRetrieval(MultiChannelRetrievalEngine.RetrievalResult lastRetrieval) {
        this.lastRetrieval = lastRetrieval;
    }

    public int incrementToolCall() {
        return ++toolCallCount;
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }
}
