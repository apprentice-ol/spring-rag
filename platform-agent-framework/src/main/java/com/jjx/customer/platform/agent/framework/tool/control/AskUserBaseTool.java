package com.jjx.customer.platform.agent.framework.tool.control;


import com.jjx.customer.platform.agent.framework.tool.BaseTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 追问工具（BaseTool，控制面）：中断执行转澄清，文案回传编排层落会话。
 */
@Component
public class AskUserBaseTool implements BaseTool {

    public static final String NAME = "ask_user";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "信息不足、无法继续排查时向用户追问。参数 question 为要问用户的话"
                + "（一次问齐所有缺失项，不挤牙膏）。调用本工具后本轮排查结束，等用户补充。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{
                  "question":{"type":"string","description":"向用户提出的问题（可含多条，逐条列出）"}
                },"required":["question"]}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String question = invocation.str("question", "");
        if (question.isBlank()) {
            return ToolResult.error("question 为必填");
        }
        return ToolResult.askUser(question);
    }
}
