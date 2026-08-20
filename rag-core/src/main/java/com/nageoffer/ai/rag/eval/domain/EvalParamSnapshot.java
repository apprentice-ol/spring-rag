package com.nageoffer.ai.rag.eval.domain;

/**
 * 评测运行的实际检索参数快照，序列化为 JSON 存入 sa_eval_run.param_snapshot。
 *
 * <p>检索参数均支持 per-request 覆盖（见 SearchContext / RetrievalBudget）。
 * rewrite：是否启用查询改写（QueryRewriter），false=裸检索（默认，向后兼容）。
 * paradigm：agent 范式（naive/react），决定 EvalRunner 走哪个 RagAgent，
 * 默认 naive（等价改造前直接调 retrievalEngine）。rrf-k 与通道权重未纳入。</p>
 * perQuestion：per-question 检索模式（实验开关），检索限定在该题 expected_doc_ids 内。
 */
public record EvalParamSnapshot(
        int topK,
        double threshold,
        int recallBudget,
        int candidateLimit,
        int contextTopK,
        boolean rewrite,
        String paradigm,
        String note,
        Boolean answerEval,
        Boolean perQuestion
) {
    private static final String DEFAULT_PARADIGM = "naive";

    /** 兼容旧 7 参构造（无 note，等价 note=null）。 */
    public EvalParamSnapshot(int topK, double threshold, int recallBudget, int candidateLimit,
                             int contextTopK, boolean rewrite, String paradigm) {
        this(topK, threshold, recallBudget, candidateLimit, contextTopK, rewrite, paradigm, null, null, null);
    }

    /** 兼容旧 9 参构造（perQuestion=null，不限定期望文档）。 */
    public EvalParamSnapshot(int topK, double threshold, int recallBudget, int candidateLimit,
                             int contextTopK, boolean rewrite, String paradigm,
                             String note, Boolean answerEval) {
        this(topK, threshold, recallBudget, candidateLimit, contextTopK, rewrite, paradigm, note, answerEval, null);
    }

    /** 复制并附加抽样说明（EvalRunner 在"数量超范围全量评测"等场景写回 param_snapshot）。 */
    public EvalParamSnapshot withNote(String note) {
        return new EvalParamSnapshot(topK, threshold, recallBudget, candidateLimit,
                contextTopK, rewrite, paradigm, note, answerEval, perQuestion);
    }

    /** 是否启用答案质量评测（生成答案 + LLM-as-judge 打分）；null/false 仅跑检索指标。 */
    public boolean answerEvalEnabled() {
        return Boolean.TRUE.equals(answerEval);
    }

    /** 是否启用 per-question 检索模式：检索限定在该题 expected_doc_ids 内（模拟官方独立语料实验）。 */
    public boolean perQuestionEnabled() {
        return Boolean.TRUE.equals(perQuestion);
    }

    /** 规范化 paradigm（null/空 → naive），供 EvalRunner 选 agent 用。 */
    public String effectiveParadigm() {
        return (paradigm == null || paradigm.isBlank()) ? DEFAULT_PARADIGM : paradigm;
    }
}
