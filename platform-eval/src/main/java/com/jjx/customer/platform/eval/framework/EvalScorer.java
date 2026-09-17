package com.jjx.customer.platform.eval.framework;

import java.util.List;

/**
 * 评测维度 SPI：一个 scorer 产出一个或多个指标（如 answer judge 一次产出 correctness + faithfulness）。
 * <p>实现注册为 {@code @Component} 即被 {@code EvalScorerRegistry} 收集（开闭：加维度 = 加 bean，
 * 与 AgentTool / IngestionNode 同款模式）。无适用输入时返回空列表 = 跳过（如答案未生成时
 * AnswerJudgeScorer 不出分），保持各维度的开关语义互不干扰。
 * <p>实现应尽量纯函数（吃 {@link EvalSample} 出 {@link EvalScore}）；确需 LLM 调用的
 * （judge 类）自行降级——异常返回空列表，绝不阻断跑批。
 */
public interface EvalScorer {

    /** scorer 标识（日志/排序用；指标名以 EvalScore.name 为准）。 */
    String name();

    /** 对一条样本打分。 */
    List<EvalScore> score(EvalSample sample);
}
