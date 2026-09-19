package com.jjx.customer.platform.eval.framework;
import com.jjx.customer.platform.business.knowledge.rag.RagContextAssembler;

import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.business.knowledge.rag.RagContextAssembler;

import java.util.List;

/**
 * 评测样本（对齐行业四元组 question / contexts / answer / ground_truth——RAGAS 与 DeepEval
 * 的标准数据形状）：一条 item 跑完检索（与可选答案生成）后的全部事实，供 Scorer 打分、Sink 分发。
 *
 * <p>{@code context} = 线上同款 {@link RagContextAssembler} 组装的"最后推送 LLM 的数据"
 * （含 citations 引用映射）——context 级比对（context_recall 等）以此为准，与生产真实输入一致。
 *
 * @param runId / itemId / attempt / remark   定位与重评信息（remark 批量跑为 null）
 * @param datasetName                         数据集名（Langfuse dataset 同步用）
 * @param paradigm / rewrite / perQuestion / category   参数与分类投影（落库口径字段）
 * @param question                            问题
 * @param expectedDocIds / expectedDocNames   标准召回（doc_id 列表 + 对应文件名，平行列表）
 * @param expectedAnswer                      标准答案（可空）
 * @param retrievedDocIds / retrievedDocNames  实际召回（文档级去重保序）
 * @param finalChunks                         检索引擎最终块（与线上 streamRagResponse 同源）
 * @param context                             线上同款组装上下文（检索成功且非空时存在，否则 null）
 * @param generatedAnswer                     生成的系统答案（answerEval 开关控制，可空）
 * @param agentTrace / traceId                agent 轨迹 JSON / item root trace 的 traceId
 * @param latencyMs / error                   耗时 / 检索失败原因（error 非 null = 该条失败）
 */
public record EvalSample(Long runId, Long itemId, int attempt, String remark,
                         String datasetName, String paradigm, boolean rewrite, boolean perQuestion,
                         String category, String question,
                         List<String> expectedDocIds, List<String> expectedDocNames, String expectedAnswer,
                         List<String> retrievedDocIds, List<String> retrievedDocNames,
                         List<RetrievedChunk> finalChunks, RagContextAssembler.RagContext context,
                         String generatedAnswer,
                         String agentTrace, String traceId,
                         long latencyMs, String error) {

    /** 该条是否有效召回（聚合 retrievedCount 与 run FAILED 判定用）。 */
    public boolean retrieved() {
        return error == null && retrievedDocIds != null && !retrievedDocIds.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long runId;
        private Long itemId;
        private int attempt;
        private String remark;
        private String datasetName;
        private String paradigm;
        private boolean rewrite;
        private boolean perQuestion;
        private String category;
        private String question;
        private List<String> expectedDocIds = List.of();
        private List<String> expectedDocNames = List.of();
        private String expectedAnswer;
        private List<String> retrievedDocIds = List.of();
        private List<String> retrievedDocNames = List.of();
        private List<RetrievedChunk> finalChunks = List.of();
        private RagContextAssembler.RagContext context;
        private String generatedAnswer;
        private String agentTrace;
        private String traceId;
        private long latencyMs;
        private String error;

        public Builder runId(Long v) { this.runId = v; return this; }
        public Builder itemId(Long v) { this.itemId = v; return this; }
        public Builder attempt(int v) { this.attempt = v; return this; }
        public Builder remark(String v) { this.remark = v; return this; }
        public Builder datasetName(String v) { this.datasetName = v; return this; }
        public Builder paradigm(String v) { this.paradigm = v; return this; }
        public Builder rewrite(boolean v) { this.rewrite = v; return this; }
        public Builder perQuestion(boolean v) { this.perQuestion = v; return this; }
        public Builder category(String v) { this.category = v; return this; }
        public Builder question(String v) { this.question = v; return this; }
        public Builder expectedDocIds(List<String> v) { this.expectedDocIds = v; return this; }
        public Builder expectedDocNames(List<String> v) { this.expectedDocNames = v; return this; }
        public Builder expectedAnswer(String v) { this.expectedAnswer = v; return this; }
        public Builder retrievedDocIds(List<String> v) { this.retrievedDocIds = v; return this; }
        public Builder retrievedDocNames(List<String> v) { this.retrievedDocNames = v; return this; }
        public Builder finalChunks(List<RetrievedChunk> v) { this.finalChunks = v; return this; }
        public Builder context(RagContextAssembler.RagContext v) { this.context = v; return this; }
        public Builder generatedAnswer(String v) { this.generatedAnswer = v; return this; }
        public Builder agentTrace(String v) { this.agentTrace = v; return this; }
        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder latencyMs(long v) { this.latencyMs = v; return this; }
        public Builder error(String v) { this.error = v; return this; }

        public EvalSample build() {
            return new EvalSample(runId, itemId, attempt, remark, datasetName, paradigm, rewrite,
                    perQuestion, category, question, expectedDocIds, expectedDocNames, expectedAnswer,
                    retrievedDocIds, retrievedDocNames, finalChunks, context, generatedAnswer,
                    agentTrace, traceId, latencyMs, error);
        }
    }
}
