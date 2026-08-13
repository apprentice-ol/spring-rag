package com.nageoffer.ai.rag.chat.agent;

/**
 * RAG agent 范式枚举。每种范式对应一个 {@link RagAgent} 实现，可插拔切换。
 * <p>覆盖 agent 设计空间的主干谱系：线性 → 路由 → 反思 → 自主 → 规划。
 */
public enum RagParadigm {

    /** 单次检索 → 回答（baseline，等价改造前的线性流水线） */
    NAIVE("naive"),
    /** Corrective RAG：检索 → 评估 → 三档路由 → 纠正重试 */
    CRAG("crag"),
    /** Self-RAG：密集反思，逐 chunk 评估 + 答案 grounded 判断 */
    SELF_RAG("self_rag"),
    /** ReAct：LLM 自主 reason+act 循环（function calling） */
    REACT("react"),
    /** Plan-and-Execute：先规划子查询 → 执行 → 合并精排 */
    PLAN_EXECUTE("plan_execute");

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
