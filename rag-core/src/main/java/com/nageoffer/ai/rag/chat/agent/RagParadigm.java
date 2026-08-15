package com.nageoffer.ai.rag.chat.agent;

/**
 * RAG agent 范式枚举。每种范式对应一个 {@link RagAgent} 实现，可插拔切换。
 * <p>当前保留两个范式：线性 baseline 与自主循环。历史 run 记录中的旧范式（crag/self_rag/plan_execute）
 * 经 {@link #parse} 的容错回退到 NAIVE，不报错。
 */
public enum RagParadigm {

    /** 单次检索 → 回答（baseline，等价改造前的线性流水线） */
    NAIVE("naive"),
    /** ReAct：LLM 自主 reason+act 循环（function calling） */
    REACT("react");

    private final String code;

    RagParadigm(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 容错解析：null/空白/未知值回退到 fallback。 */
    public static RagParadigm parse(String s, RagParadigm fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        String trimmed = s.trim();
        for (RagParadigm p : values()) {
            if (p.code.equalsIgnoreCase(trimmed)) {
                return p;
            }
        }
        return fallback;
    }
}
