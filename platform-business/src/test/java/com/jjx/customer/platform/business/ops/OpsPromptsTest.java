package com.jjx.customer.platform.business.ops;

import com.agentframework.engine.promptmanager.Prompt;
import com.agentframework.engine.promptmanager.PromptContext;
import com.agentframework.engine.promptmanager.TemplatePromptRenderer;
import com.jjx.customer.platform.business.workflow.common.ActExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * think 模板组合的渲染位单测：人在环中的用户方向指令（{@code user_directive}）必须真的
 * 进入阶段 think prompt——没有这一位，directive 就只是落了个槽位，模型永远看不见。
 */
class OpsPromptsTest {

    private static final TemplatePromptRenderer RENDERER = new TemplatePromptRenderer();

    private static String render(Map<String, Object> slots) {
        String template = OpsPrompts.compose("阶段正文", List.of("query_logs(trace_id?)：查日志"), "scratchpad");
        return RENDERER.render(Prompt.of("inv", "latest", template),
                PromptContext.of(Map.of("slots", slots)));
    }

    @Test
    void 用户方向指令渲染位存在_无指令时渲染为占位而不是漏出模板语法() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("scratchpad", "");

        String rendered = render(slots);

        assertTrue(rendered.contains("用户补充说明"), "阶段模板应带「用户补充说明」段");
        assertTrue(rendered.contains("（无）"), "无指令时应渲染成占位文案");
        assertFalse(rendered.contains("{{"), "不得把模板语法漏给模型");
    }

    @Test
    void 用户方向指令在场时原样进入阶段prompt() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(ActExecutor.USER_DIRECTIVE_SLOT, "别再查日志了，直接给我正确报文");

        String rendered = render(slots);

        assertTrue(rendered.contains("别再查日志了，直接给我正确报文"), "directive 应随 think prompt 下发");
        assertFalse(rendered.contains("{{"), "不得把模板语法漏给模型");
    }
}
