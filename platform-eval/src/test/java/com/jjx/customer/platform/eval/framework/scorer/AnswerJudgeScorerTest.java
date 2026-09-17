package com.jjx.customer.platform.eval.framework.scorer;

import com.jjx.customer.platform.eval.framework.EvalScore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答案 judge 解析单测（纯函数 parseVerdict）：正常 JSON / 围栏包裹 / 坏输出三态。
 */
class AnswerJudgeScorerTest {

    @Test
    void 正常JSON解析归一() {
        List<EvalScore> scores = AnswerJudgeScorer.parseVerdict(
                "{\"correctness\": 8, \"faithfulness\": 10, \"reason\": \"关键事实一致\"}");
        assertEquals(2, scores.size());
        assertEquals("answer_correctness", scores.get(0).name());
        assertEquals(0.8, scores.get(0).value(), 1e-9);
        assertEquals("answer_faithfulness", scores.get(1).name());
        assertEquals(1.0, scores.get(1).value(), 1e-9);
        assertEquals("关键事实一致", scores.get(0).comment());
    }

    @Test
    void 围栏与正文包裹容错() {
        List<EvalScore> scores = AnswerJudgeScorer.parseVerdict(
                "评估结果如下：\n```json\n{\"correctness\": 6, \"faithfulness\": 5, \"reason\": \"缺关键数字\"}\n```\n以上。");
        assertEquals(0.6, scores.get(0).value(), 1e-9);
        assertEquals(0.5, scores.get(1).value(), 1e-9);
    }

    @Test
    void 坏输出与null返回空() {
        assertTrue(AnswerJudgeScorer.parseVerdict(null).isEmpty());
        assertTrue(AnswerJudgeScorer.parseVerdict("完全不是 JSON").isEmpty());
        assertTrue(AnswerJudgeScorer.parseVerdict("{\"correctness\": \"abc\"}").isEmpty()
                || AnswerJudgeScorer.parseVerdict("{\"correctness\": \"abc\"}").size() == 2,
                "类型异常字段按 0 兜底，不抛异常");
    }

    @Test
    void 缺字段按0兜底() {
        List<EvalScore> scores = AnswerJudgeScorer.parseVerdict("{\"reason\": \"没有分数\"}");
        assertEquals(0.0, scores.get(0).value(), 1e-9);
        assertEquals(0.0, scores.get(1).value(), 1e-9);
    }
}
