package com.nageoffer.ai.rag.chat.agent;

import java.util.List;

/**
 * ReAct agent 三步（retrieve/grade/rerank）的结构化轨迹产物，塞进 {@link AgentStep#detail()}。
 * <p>把原本被丢弃的"动作产物"暴露出来：检索命中的片段、逐条评分理由、重排顺序变化，
 * 供前端按 action 类型展开渲染（折叠详情区）。其他范式暂不填充（detail=null）。
 *
 * <p>序列化约定：作为 {@code Object detail} 被 Jackson 序列化成嵌套 JSON，前端 TS 镜像字段名对齐。
 */
public final class AgentStepDetails {

    private AgentStepDetails() {
    }

    /** retrieve 步产物：查询词 + 命中片段逐条详情。 */
    public record Retrieve(String query, List<ChunkHit> chunks) {
    }

    /** grade 步产物：聚合裁决（ALL_RELEVANT/PARTIAL/ALL_IRRELEVANT）+ 相关数/总数/均分 + 逐条评分理由。 */
    public record Grade(String verdict, int relevant, int total, double avgScore, List<GradeRow> grades) {
    }

    /**
     * rerank 步产物：目标 topN + 重排前 ref 序 + 重排后 ref/score 序。
     * {@code before} 有而 {@code after} 无的 ref 即被淘汰（前端标灰）。
     */
    public record Rerank(int topN, List<Integer> before, List<RerankRow> after) {
    }

    /** retrieve 命中的单条片段（ref 是 workspace 编号，前端可据此关联 grade 的引用）。 */
    public record ChunkHit(int ref, Double score, Double originalScore, String docName, String channel, String preview) {
    }

    /** grade 评估的单条结果（reason 来自 LLM，grade prompt 要求 ≤20 字）。 */
    public record GradeRow(int ref, double score, boolean relevant, String reason) {
    }

    /** rerank 重排后的单条（ref 匹配不到原 workspace 时为 -1，前端显示占位）。 */
    public record RerankRow(int ref, Double score) {
    }
}
