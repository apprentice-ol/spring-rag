package com.jjx.customer.platform.agent.framework.tool;

import com.jjx.customer.platform.agent.framework.tool.AgentTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolKind;
import com.jjx.customer.platform.agent.framework.agent.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static final ToolInvocation INVOCATION = new ToolInvocation("t", Map.of(), null);

    @Test
    void 扩展工具撞保留字_启动即失败() {
        AgentTool duplicated = TestFixtures.ext("finish", com.jjx.customer.platform.agent.framework.tool.ToolResult.ok("x"));

        assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(TestFixtures.base("finish"), duplicated)));
    }

    @Test
    void 未注册工具_返回错误而不是抛异常() {
        ToolRegistry registry = new ToolRegistry(List.of());

        assertFalse(registry.invoke(INVOCATION).ok());
    }

    @Test
    void 工具抛异常_被收口为错误结果() {
        ToolRegistry registry = new ToolRegistry(List.of(TestFixtures.throwingExt("bad")));

        var result = registry.invoke(new ToolInvocation("bad", Map.of(), null));

        assertFalse(result.ok());
        assertTrue(result.asObservation().contains("工具炸了"));
    }

    @Test
    void 按类别查询_两类不混() {
        ToolRegistry registry = new ToolRegistry(List.of(
                TestFixtures.base("finish"), TestFixtures.ext("retrieve", null)));

        assertEquals(ToolKind.BASE, registry.baseTools().get(0).kind());
        assertEquals(ToolKind.EXTENSION, registry.extensionTools().get(0).kind());
    }
}
