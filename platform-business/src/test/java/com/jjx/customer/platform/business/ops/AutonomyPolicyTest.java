package com.jjx.customer.platform.business.ops;

import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 自主档位策略单测（P3）：档位是会话级音量旋钮，目录声明是能力上限——两者**取交集**，
 * 档位只能收紧不能放宽（未知值一律回缺省 L2，绝不因为不认识的值就放大自主性）。
 */
class AutonomyPolicyTest {

    private static OpsSlotCatalog.Spec spec(String name) {
        return OpsSlotCatalog.ALL.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void 解析容错_三种写法与未知值() {
        assertEquals(AutonomyLevel.L1, AutonomyLevel.parse("L1"));
        assertEquals(AutonomyLevel.L1, AutonomyLevel.parse("l1"));
        assertEquals(AutonomyLevel.L1, AutonomyLevel.parse(" 1 "));
        assertEquals(AutonomyLevel.L3, AutonomyLevel.parse("L3"));
        assertEquals(AutonomyLevel.L2, AutonomyLevel.parse(null));
        assertEquals(AutonomyLevel.L2, AutonomyLevel.parse(""));
        assertEquals(AutonomyLevel.L2, AutonomyLevel.parse("L9"), "认不出的档位必须回缺省，不能当 L3 放行");
        assertEquals(AutonomyLevel.L2, AutonomyPolicy.of(null).level());
    }

    @Test
    void 顺带调档_协议hint映射档位() {
        assertEquals(AutonomyLevel.L1, AutonomyLevel.fromHint(1));
        assertEquals(AutonomyLevel.L2, AutonomyLevel.fromHint(2));
        assertEquals(AutonomyLevel.L3, AutonomyLevel.fromHint(3));
        assertEquals(null, AutonomyLevel.fromHint(null), "没提就不改档");
        assertEquals(null, AutonomyLevel.fromHint(9), "越界不改档");
    }

    @Test
    void L1只做用户原文的确定性提取_不代填不推断不反查() {
        AutonomyPolicy l1 = new AutonomyPolicy(AutonomyLevel.L1);

        assertFalse(l1.allowsCatalogDefault(), "L1 缺时间就问用户，不代填「最近30分钟」");
        assertFalse(l1.allowsInfer(spec(OpsSlotCatalog.ENVIRONMENT)), "environment 目录允许推断也要被档位收紧");
        assertFalse(l1.allowsResolve(spec(OpsSlotCatalog.TRACE_ID)), "trace_id 目录允许反查也要被档位收紧");
    }

    @Test
    void L2保持既有行为_L3只放宽额度不突破目录() {
        AutonomyPolicy l2 = AutonomyPolicy.DEFAULT;
        AutonomyPolicy l3 = new AutonomyPolicy(AutonomyLevel.L3);

        assertTrue(l2.allowsCatalogDefault());
        assertTrue(l2.allowsInfer(spec(OpsSlotCatalog.ENVIRONMENT)));
        assertFalse(l2.allowsInfer(spec(OpsSlotCatalog.PAYLOAD)), "payload 目录不允许推断（只能日志反查）");
        assertFalse(l3.allowsInfer(spec(OpsSlotCatalog.PAYLOAD)), "L3 也不得突破目录声明");
        assertTrue(l3.allowsResolve(spec(OpsSlotCatalog.PAYLOAD)));

        assertEquals(0.7, l2.inferConfidence());
        assertEquals(0.5, l3.inferConfidence());
        assertEquals(2, l2.maxResolveCalls());
        assertEquals(3, l3.maxResolveCalls());
        assertEquals(AutonomyLevel.L2, AutonomyPolicy.of("").level(), "槽位缺失时按缺省 L2");
    }
}
