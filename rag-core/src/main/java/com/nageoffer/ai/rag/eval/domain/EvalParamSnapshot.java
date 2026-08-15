package com.nageoffer.ai.rag.eval.domain;

/**
 * 评测运行的实际检索参数快照，序列化为 JSON 存入 sa_eval_run.param_snapshot。
 *
 * <p>检索参数均支持 per-request 覆盖（见 SearchContext / RetrievalBudget）。
 * rewrite：是否启用查询改写（QueryRewriter），false=裸检索（默认，向后兼容）。
 * paradigm：agent 范式（naive/react），决定 EvalRunner 走哪个 RagAgent，
 * 默认 naive（等价改造前直接调 retrievalEngine）。rrf-k 与通道权重未纳入。</p>
 */
public record EvalParamSnapshot(
        int topK,
        double threshold,
        int recallBudget,
        int candidateLimit,
        int contextTopK,
        boolean rewrite,
        String paradigm
) {
    private static final String DEFAULT_PARADIGM = "naive";

    /** 规范化 paradigm（null/空 → naive），供 EvalRunner 选 agent 用。 */
    public String effectiveParadigm() {
        return (paradigm == null || paradigm.isBlank()) ? DEFAULT_PARADIGM : paradigm;
    }
}
