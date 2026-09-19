package com.agentframework.definition.workflow;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 人机协作决议的确定性解析单测：前端点选回传（{@code #decision:} / {@code #override:}）
 * 必须零歧义解析——这条路不经过模型，格式错就该当普通文本，绝不静默丢信息。
 */
class HumanResponseTest {

    @Test
    void decision协议_点选项与附带说明() {
        HumanResponse response = HumanResponse.parse("#decision:terminate");

        assertEquals("terminate", response.decision());
        assertEquals("", response.directive());
        assertTrue(response.mergedFills().isEmpty());
    }

    @Test
    void override协议_解析成槽位纠正() {
        HumanResponse response = HumanResponse.parse("#override:environment=test");

        assertEquals(Map.of("environment", "test"), response.overrides());
        assertTrue(response.slotFills().isEmpty());
        assertNull(response.decision());
        assertEquals("test", response.mergedFills().get("environment"));
        assertFalse(response.isEmpty());
    }

    @Test
    void override协议_值里带等号或空格也照收() {
        HumanResponse response = HumanResponse.parse("#override: payload = {\"a\":1}");

        assertEquals("{\"a\":1}", response.mergedFills().get("payload"));
    }

    @Test
    void fill协议_卡片里选中的多项一次提交() {
        HumanResponse response = HumanResponse.parse(
                "#fill:{\"environment\":\"prod\",\"time\":\"最近1小时\",\"symptoms\":\"\"}");

        assertEquals("prod", response.mergedFills().get("environment"));
        assertEquals("最近1小时", response.mergedFills().get("time"));
        assertEquals("", response.mergedFills().get("symptoms"), "空串 = 清空该项");
        assertTrue(HumanResponse.isProtocolText("#fill:{}"));
    }

    @Test
    void fill协议_值里带逗号引号也不截断() {
        HumanResponse response = HumanResponse.parse(
                "#fill:{\"payload\":\"{\\\"a\\\":1,\\\"b\\\":\\\"x,y\\\"}\"}");

        assertEquals("{\"a\":1,\"b\":\"x,y\"}", response.mergedFills().get("payload"));
    }

    @Test
    void fill协议_格式不对按普通文本兜底() {
        HumanResponse response = HumanResponse.parse("#fill:not-json");

        assertTrue(response.mergedFills().isEmpty());
        assertEquals("#fill:not-json", response.directive(), "解析不了就当普通文本，不静默丢");
    }

    @Test
    void override协议_空值表示清空该槽() {
        HumanResponse response = HumanResponse.parse("#override:payload=");

        assertEquals(Map.of("payload", ""), response.overrides());
        assertEquals("", response.mergedFills().get("payload"), "空值即清空（机器猜错的值要能抹掉）");
    }

    @Test
    void override协议_格式不对按普通文本兜底() {
        HumanResponse noSlot = HumanResponse.parse("#override 环境是测试的");
        assertEquals("#override 环境是测试的", noSlot.directive(), "缺冒号即非协议文本");
    }

    @Test
    void isProtocolText_只认两种点选前缀() {
        assertTrue(HumanResponse.isProtocolText("#decision:redirect"));
        assertTrue(HumanResponse.isProtocolText("  #OVERRIDE:time=abc"));
        assertTrue(HumanResponse.isProtocolText("#override:environment=test"));
        assertFalse(HumanResponse.isProtocolText("环境是测试的"));
        assertFalse(HumanResponse.isProtocolText(null));
    }
}
