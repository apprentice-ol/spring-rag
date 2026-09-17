package com.jjx.customer.platform.eval.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 评测参数快照测试：answerEval / perQuestion 开关的默认值与保留语义。
 */
class EvalParamSnapshotTest {

    @Test
    void legacyConstructorsDefaultSwitchesOff() {
        EvalParamSnapshot legacy = new EvalParamSnapshot(5, 0.6, 20, 40, 5, false, "naive");
        assertFalse(legacy.answerEvalEnabled());
        assertFalse(legacy.perQuestionEnabled());
    }

    @Test
    void newSwitchesEnabled() {
        EvalParamSnapshot snapshot = new EvalParamSnapshot(
                5, 0.6, 20, 40, 5, true, "naive", null,
                Boolean.TRUE, Boolean.TRUE);
        assertTrue(snapshot.answerEvalEnabled());
        assertTrue(snapshot.perQuestionEnabled());
    }

    @Test
    void withNotePreservesSwitches() {
        EvalParamSnapshot snapshot = new EvalParamSnapshot(
                5, 0.6, 20, 40, 5, false, "react", "note",
                Boolean.TRUE, Boolean.TRUE);
        EvalParamSnapshot noted = snapshot.withNote("抽样 10/100");
        assertEquals("抽样 10/100", noted.note());
        assertTrue(noted.answerEvalEnabled());
        assertTrue(noted.perQuestionEnabled());
        assertEquals("react", noted.effectiveParadigm());
    }
}
