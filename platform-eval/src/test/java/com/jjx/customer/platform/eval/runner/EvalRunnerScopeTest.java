package com.jjx.customer.platform.eval.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jjx.customer.platform.eval.dao.entity.EvalItemEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 评测范围与抽样四规则测试（锁死 2026-08 修复的条件反转 bug）：
 * ① 选范围+无数量 → 范围全量；② 选范围+数量在范围内 → 抽 N；
 * ③ 选范围+数量超范围 → 全量+说明；④ 无范围 → 全量抽 N / 无数量按比例至少 1 条。
 */
class EvalRunnerScopeTest {

    private static List<EvalItemEntity> items(int factoid, int comparison) {
        List<EvalItemEntity> list = new ArrayList<>();
        for (int i = 0; i < factoid; i++) {
            list.add(item(100 + i, "factoid"));
        }
        for (int i = 0; i < comparison; i++) {
            list.add(item(200 + i, "comparison"));
        }
        return list;
    }

    private static EvalItemEntity item(long id, String category) {
        EvalItemEntity e = new EvalItemEntity();
        e.setId(id);
        e.setCategory(category);
        e.setEnabled(1);
        return e;
    }

    @Test
    void rule1_scopeWithoutLimit_runsFullScope() {
        // 100 factoid + 30 comparison，选 comparison 不选数量 → 只跑 30 条 comparison
        EvalRunner.ScopeResult r = EvalRunner.applyScopeAndSample(items(100, 30), "comparison", null);
        assertEquals(30, r.items().size());
        assertTrue(r.items().stream().allMatch(i -> "comparison".equals(i.getCategory())));
        assertNull(r.note());
    }

    @Test
    void rule2_scopeWithSmallerLimit_samplesWithinScope() {
        EvalRunner.ScopeResult r = EvalRunner.applyScopeAndSample(items(100, 30), "factoid", 10);
        assertEquals(10, r.items().size());
        assertTrue(r.items().stream().allMatch(i -> "factoid".equals(i.getCategory())));
        assertNull(r.note());
    }

    @Test
    void rule3_limitExceedsScope_runsFullScopeWithNote() {
        EvalRunner.ScopeResult r = EvalRunner.applyScopeAndSample(items(100, 30), "comparison", 50);
        assertEquals(30, r.items().size(), "数量超范围必须全量评测该范围");
        assertNotNull(r.note());
        assertTrue(r.note().contains("50") && r.note().contains("30"), "说明应含请求数量与范围条数: " + r.note());
    }

    @Test
    void rule3empty_scopeWithNoItems_notesZero() {
        EvalRunner.ScopeResult r = EvalRunner.applyScopeAndSample(items(100, 0), "comparison", 10);
        assertEquals(0, r.items().size());
        assertNotNull(r.note());
        assertTrue(r.note().contains("无启用条目"));
    }

    @Test
    void rule4_noScopeWithLimit_samplesFromAll() {
        EvalRunner.ScopeResult r = EvalRunner.applyScopeAndSample(items(100, 30), null, 20);
        assertEquals(20, r.items().size());
        assertNull(r.note());
    }

    @Test
    void rule4_noScopeNoLimit_samplesByRatioAtLeastOne() {
        // 130 条 × 10% = 13 条
        EvalRunner.ScopeResult r = EvalRunner.applyScopeAndSample(items(100, 30), null, null);
        assertEquals(13, r.items().size());
        assertNotNull(r.note());
        assertTrue(r.note().contains("13/130"));
    }

    @Test
    void rule4_tinyDataset_samplesAtLeastOne() {
        // 3 条 × 10% = 0.3 → ceil = 1
        EvalRunner.ScopeResult r = EvalRunner.applyScopeAndSample(items(3, 0), null, null);
        assertEquals(1, r.items().size());
        assertNotNull(r.note());
    }

    @Test
    void rule4_singleItem_samplingNotLoseIt() {
        EvalRunner.ScopeResult r = EvalRunner.applyScopeAndSample(items(1, 0), null, null);
        assertEquals(1, r.items().size());
    }

    @Test
    void rule4_noScopeLimitExceedsTotal_runsAll() {
        EvalRunner.ScopeResult r = EvalRunner.applyScopeAndSample(items(100, 30), "", 999);
        assertEquals(130, r.items().size());
        assertNull(r.note());
    }
}
