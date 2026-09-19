package com.jjx.customer.platform.business.ops.node;

import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.slot.Slots;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 直答收尾单测：结论取自最后非空阶段产出，但**交付前要剥掉 {@code {"answer":"…"}} 外壳**——
 * 模型带换行的结论会让一行 JSON 解析失败、原文直通到交付，用户看到的就是花括号和 \n 转义（实测踩到）。
 */
class ConcludeExecutorTest {

    private static NodeResult run(String verOutput) {
        Map<String, Object> slots = new LinkedHashMap<>();
        if (verOutput != null) {
            slots.put("ver_stage_output", verOutput);
        }
        NodeContext context = new NodeContext(null, null, null, null, new Slots(slots), null,
                new Input("", Map.of(), Map.of()), null, null, null, null);
        return new ConcludeExecutor().execute(CustomNodeDefinition.of("conclude", "ops-conclude", null),
                context);
    }

    @Test
    void 带换行的answer外壳_交付前剥掉() {
        String raw = "{\"answer\":\"结论：invoiceCode 传空。\n\n```json\n{\\\"invoiceCode\\\":\\\"044\\\"}\n```\"}";

        String text = run(raw).output();

        assertFalse(text.startsWith("{"), "不该把 JSON 外壳交给用户，实际=" + text);
        assertTrue(text.startsWith("结论：invoiceCode 传空。"));
        assertTrue(text.contains("\n"), "换行要还原成真换行而不是 \n 字面量");
        assertTrue(text.contains("```json"), "代码块要保留");
    }

    @Test
    void 普通结论与空产出_原样处理() {
        assertEquals("就是普通的结论", run("就是普通的结论").output());
        assertTrue(run(null).output().contains("未产出结论"), "没产出就如实说明");
        assertEquals("{\"other\":1}", run("{\"other\":1}").output(), "非 answer 外壳不剥");
    }
}
