package com.jjx.customer.platform.eval.framework.scorer;

import com.google.gson.JsonObject;
import com.jjx.customer.platform.common.util.JsonUtil;
import com.jjx.customer.platform.eval.framework.EvalSample;
import com.jjx.customer.platform.eval.framework.EvalScore;
import com.jjx.customer.platform.eval.framework.EvalScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 答案质量 scorer（LLM-as-judge，从 EvalRunner.judgeAnswer 平移）：对「生成的系统答案 vs
 * 标准答案」打 correctness / faithfulness 两维。sample.generatedAnswer 为空（answerEval 未开 /
 * 无标准答案 / 生成失败）时返回空列表 = 跳过——开关语义保持在编排层（是否生成），scorer 只看输入存在性。
 * <p>judge 失败（异常/坏 JSON）返回空列表，绝不阻断跑批。
 */
@Slf4j
@Component
public class AnswerJudgeScorer implements EvalScorer {

    /** judge system prompt 资产 key（正文 = prompts/eval/answer-judge.md；classpath 管理，不在 binding 体系）。 */
    public static final String JUDGE_ASSET = "eval/answer-judge";

    private final ChatClient ingestionChatClient;

    private final com.jjx.customer.platform.config.prompt.PromptStore promptStore;

    public AnswerJudgeScorer(@Qualifier("ingestionChatClient") ChatClient ingestionChatClient,
            com.jjx.customer.platform.config.prompt.PromptStore promptStore) {
        this.ingestionChatClient = ingestionChatClient;
        this.promptStore = promptStore;
    }

    @Override
    public String name() {
        return "answer_judge";
    }

    @Override
    public List<EvalScore> score(EvalSample sample) {
        String generated = sample.generatedAnswer();
        String expected = sample.expectedAnswer();
        if (sample.error() != null || generated == null || generated.isBlank()
                || expected == null || expected.isBlank()) {
            return List.of();
        }
        String contextText = sample.context() != null ? sample.context().text() : "";
        try {
            String response = ingestionChatClient.prompt()
                    .system(promptStore.raw(JUDGE_ASSET))
                    .user("Question: " + sample.question()
                            + "\n\nRetrieved documents:\n" + contextText
                            + "\n\nReference answer: " + expected
                            + "\n\nGenerated answer: " + generated)
                    .call()
                    .content();
            return parseVerdict(response);
        } catch (Exception ex) {
            log.warn("[Eval] 答案 judge 失败 item={}: {}", sample.itemId(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * judge 输出解析（包级静态纯函数便于单测）：统一走 JsonUtil 的平衡花括号提取
     * （抗正文干扰，容错围栏/尾逗号）；0-10 整数归一为 0~1。坏输出返回空列表。
     */
    static List<EvalScore> parseVerdict(String response) {
        JsonObject node = response == null ? null : JsonUtil.firstJsonObject(response);
        if (node == null) {
            return List.of();
        }
        double correctness = numOr(node, "correctness", 0) / 10.0;
        double faithfulness = numOr(node, "faithfulness", 0) / 10.0;
        String reason = node.has("reason") && !node.get("reason").isJsonNull()
                ? node.get("reason").getAsString() : "";
        return List.of(
                new EvalScore("answer_correctness", correctness, reason),
                new EvalScore("answer_faithfulness", faithfulness, reason));
    }

    private static double numOr(JsonObject node, String key, double def) {
        try {
            return node.has(key) && !node.get(key).isJsonNull() ? node.get(key).getAsDouble() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
