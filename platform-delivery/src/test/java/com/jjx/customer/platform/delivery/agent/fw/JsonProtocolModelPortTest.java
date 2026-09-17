package com.jjx.customer.platform.business;
import com.jjx.customer.platform.config.llm.JsonProtocolModelPort;

import com.jjx.customer.platform.agent.framework.model.ModelReply;
import com.jjx.customer.platform.agent.framework.model.ModelRequest;
import com.jjx.customer.platform.agent.framework.model.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonProtocolModelPortTest {

    private static final List<ToolSchema> TOOLS = List.of(new ToolSchema(
            "query_logs", "按 traceId 查日志", "{\"type\":\"object\",\"properties\":{\"traceId\":{\"type\":\"string\"}}}"));

    @Test
    void 模型请求工具_解析为结构化工具调用() {
        JsonProtocolModelPort port = new JsonProtocolModelPort((system, user) ->
                "{\"tool\":\"query_logs\",\"args\":{\"traceId\":\"abc123\"}}");

        ModelReply reply = port.complete(new ModelRequest("阶段提示", "问题", TOOLS, List.of()));

        assertTrue(reply.hasToolCalls());
        assertEquals("query_logs", reply.toolCalls().get(0).toolName());
        assertEquals("abc123", reply.toolCalls().get(0).args().get("traceId"));
    }

    @Test
    void 模型给结论_解析为终稿() {
        JsonProtocolModelPort port = new JsonProtocolModelPort((system, user) ->
                "```json\n{\"answer\":\"结论：报文合法\"}\n```");

        ModelReply reply = port.complete(new ModelRequest("阶段提示", "问题", TOOLS, List.of()));

        assertFalse(reply.hasToolCalls());
        assertEquals("结论：报文合法", reply.text());
    }

    @Test
    void 工具说明与观测注入system与user() {
        AtomicReference<String> capturedSystem = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        JsonProtocolModelPort port = new JsonProtocolModelPort((system, user) -> {
            capturedSystem.set(system);
            capturedUser.set(user);
            return "{\"answer\":\"ok\"}";
        });

        port.complete(new ModelRequest("阶段提示", "问题", TOOLS, List.of("[query_logs] 命中 3 条")));

        assertTrue(capturedSystem.get().contains("query_logs"), "工具清单必须进 system");
        assertTrue(capturedSystem.get().contains("工具使用协议"));
        assertTrue(capturedUser.get().contains("已执行的工具结果"), "工具观测必须回喂");
    }

    @Test
    void 非JSON输出_按终稿处理不打断链路() {
        JsonProtocolModelPort port = new JsonProtocolModelPort((system, user) -> "直接给出的结论");

        ModelReply reply = port.complete(new ModelRequest("阶段提示", "问题", List.of(), List.of()));

        assertEquals("直接给出的结论", reply.text());
    }
}
