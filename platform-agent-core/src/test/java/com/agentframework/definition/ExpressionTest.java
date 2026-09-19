package com.agentframework.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.workflow.Expression;
import com.agentframework.definition.workflow.ExpressionException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 表达式引擎测试：覆盖比较、逻辑、函数与边界情形。 */
class ExpressionTest {

    @Test
    @DisplayName("依赖提取只返回 slots/slot 一级路径")
    void extractsSlotDependencies() {
        assertEquals(java.util.Set.of("quality", "retries"),
                Expression.dependencies("slots.quality == 'good' && slots.retries < 3"));
        assertEquals(java.util.Set.of("answer"),
                Expression.dependencies("exists(slot.answer) && size(slots.answer) > 0"));
        assertEquals(java.util.Set.of(),
                Expression.dependencies("'slots.fake' == input.text"));
        assertEquals(java.util.Set.of(),
                Expression.dependencies("slots.(bad)"));
    }

    private final Map<String, Object> variables = Map.of(
            "slots", Map.of(
                    "quality", "good",
                    "retries", 2,
                    "answer", "已找到 3 条结果",
                    "city", "Shanghai",
                    "tags", List.of("urgent", "vip"),
                    "score", 4.5,
                    "empty", ""));

    @Test
    @DisplayName("空表达式视为恒真")
    void blankExpressionIsTrue() {
        assertTrue(Expression.evaluate(null, variables));
        assertTrue(Expression.evaluate("   ", variables));
    }

    @Test
    @DisplayName("比较运算支持字符串、数字与点号路径")
    void comparisons() {
        assertTrue(Expression.evaluate("slots.quality == 'good'", variables));
        assertTrue(Expression.evaluate("slots.retries < 3", variables));
        assertTrue(Expression.evaluate("slots.retries >= 2 && slots.score > 4", variables));
        assertFalse(Expression.evaluate("slots.quality != 'good'", variables));
    }

    @Test
    @DisplayName("字符串与数字可跨类型比较")
    void crossTypeComparison() {
        Map<String, Object> vars = Map.of("slots", Map.of("count", "5"));
        assertTrue(Expression.evaluate("slots.count > 3", vars));
        assertTrue(Expression.evaluate("slots.count == 5", vars));
    }

    @Test
    @DisplayName("关键字与函数可用")
    void keywordsAndFunctions() {
        assertTrue(Expression.evaluate("slots.answer contains '3 条'", variables));
        assertTrue(Expression.evaluate("'urgent' in slots.tags", variables));
        assertTrue(Expression.evaluate("matches(slots.city, '^Shang')", variables));
        assertTrue(Expression.evaluate("exists(slots.answer)", variables));
        assertTrue(Expression.evaluate("empty(slots.empty)", variables));
        assertTrue(Expression.evaluate("size(slots.tags) == 2", variables));
        assertTrue(Expression.evaluate("slots.city startsWith 'Shang'", variables));
    }

    @Test
    @DisplayName("逻辑非与括号可用")
    void negationAndGrouping() {
        assertTrue(Expression.evaluate("!(slots.retries > 5)", variables));
        assertTrue(Expression.evaluate("(slots.retries == 2 || slots.retries == 3) && !empty(slots.answer)",
                variables));
    }

    @Test
    @DisplayName("value 返回原始值，供 ${} 引用使用")
    void valueExtraction() {
        assertEquals("good", Expression.value("slots.quality", variables));
        assertEquals(2, Expression.value("slots.retries", variables));
    }

    @Test
    @DisplayName("语法错误抛出 ExpressionException")
    void invalidExpression() {
        assertThrows(ExpressionException.class, () -> Expression.evaluate("slots.a ==", variables));
        assertThrows(ExpressionException.class, () -> Expression.evaluate("unknownFn(slots.a)", variables));
    }
}
