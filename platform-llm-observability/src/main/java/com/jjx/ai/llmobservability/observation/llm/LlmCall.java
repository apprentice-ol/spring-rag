package com.jjx.ai.llmobservability.observation.llm;

import lombok.Data;

/**
 * 一次 LLM 调用的观测数据（框架无关）。
 *
 * <p><b>所属维度</b>：telemetry 共享（LLM 观测数据模型，不依赖任何 LLM 框架如 spring-ai）。</p>
 *
 * <p><b>职责</b>：承载 LLM 调用的请求/响应信息（model/prompt/completion/token 用量），由具体 LLM 框架集成
 * （如某 LLM SDK 的 ObservationFilter 适配器）从框架 Context 提取后填充，交给 {@link LlmTraceHandler} 记录。</p>
 *
 * <p><b>为何框架无关</b>：让 telemetry 的 LLM 观测扩展点（{@link LlmTraceHandler}）不绑定 spring-ai 等具体框架——
 * 换 LLM 框架时，只需写新的"框架 Context → LlmCall"提取器，{@link LlmTraceHandler} 与记录逻辑不变。</p>
 */
@Data
public class LlmCall {

    /** 模型提供方（OTel gen_ai.system，如 openai / deepseek / bailian）。 */
    private String system;

    /** 模型名（如 deepseek-chat）。 */
    private String model;

    /** 请求 prompt 原文（多轮消息拼接）。 */
    private String prompt;

    /** 响应 completion 原文。 */
    private String completion;

    /** prompt token 数。 */
    private Integer promptTokens;

    /** completion token 数。 */
    private Integer completionTokens;

    /** 总 token 数。 */
    private Integer totalTokens;
}
