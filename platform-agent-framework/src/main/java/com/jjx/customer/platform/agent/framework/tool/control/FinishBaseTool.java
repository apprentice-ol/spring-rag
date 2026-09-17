package com.jjx.customer.platform.agent.framework.tool.control;


import com.jjx.customer.platform.agent.framework.tool.BaseTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 出环工具（BaseTool，控制面）：以 answer 作为本阶段终稿，立即结束工具循环。
 */
@Component
public class FinishBaseTool implements BaseTool {

    public static final String NAME = "finish";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "本阶段已有明确产出时调用：参数 answer 为终稿文本（结论/校验结果/修正后的报文）。"
                + "调用后本阶段立即结束。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{
                  "answer":{"type":"string","description":"终稿文本"}
                },"required":["answer"]}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String answer = invocation.str("answer", "");
        if (answer.isBlank()) {
            return ToolResult.error("answer 为必填");
        }
        return ToolResult.finish(answer);
    }
}
