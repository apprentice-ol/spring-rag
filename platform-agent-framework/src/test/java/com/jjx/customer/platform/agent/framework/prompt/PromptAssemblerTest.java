package com.jjx.customer.platform.agent.framework.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblerTest {

    private static PromptLayer link() {
        return new PromptLayer("link", "r1", "chat/",
                Map.of("chat/output-contract", "输出契约"));
    }

    private static PromptLayer agentLayer() {
        return new PromptLayer("agent:knowledge", "r3", "agent/knowledge/",
                Map.of("agent/knowledge/persona", "你是知识助手"));
    }

    private static PromptLayer workflowLayer() {
        return new PromptLayer("workflow:knowledge_qa", "r2", "workflow/knowledge_qa/",
                Map.of("workflow/knowledge_qa/answer", "仅基于资料回答"));
    }

    @Test
    void 三层增强_顺序拼接_身份与凭证可追溯() {
        PromptSnapshot snapshot = PromptAssembler.assemble(List.of(link(), agentLayer(), workflowLayer()));

        assertEquals("输出契约\n\n你是知识助手\n\n仅基于资料回答", snapshot.systemText());
        assertEquals("link@r1 + agent:knowledge@r3 + workflow:knowledge_qa@r2", snapshot.identity());
        assertEquals("link:r1|agent:knowledge:r3|workflow:knowledge_qa:r2", snapshot.releasesSpec());
        assertTrue(snapshot.prompt("workflow/knowledge_qa/answer").contains("资料"));
    }

    @Test
    void 改任一层内容_指纹变化() {
        PromptSnapshot base = PromptAssembler.assemble(List.of(link(), agentLayer()));
        PromptSnapshot changed = PromptAssembler.assemble(List.of(link(),
                new PromptLayer("agent:knowledge", "r4", "agent/knowledge/",
                        Map.of("agent/knowledge/persona", "你是知识助手（v2）"))));

        assertNotEquals(base.contentHash(), changed.contentHash());
    }

    @Test
    void 跨层同key_装配期报错() {
        PromptLayer duplicated = new PromptLayer("workflow:x", "r1", "",
                Map.of("chat/output-contract", "重复内容"));

        assertThrows(IllegalStateException.class,
                () -> PromptAssembler.assemble(List.of(link(), duplicated)));
    }

    @Test
    void key越层前缀_构造期报错() {
        assertThrows(IllegalArgumentException.class, () -> new PromptLayer(
                "agent:knowledge", "r1", "agent/knowledge/",
                Map.of("workflow/other/key", "越层")));
    }
}
