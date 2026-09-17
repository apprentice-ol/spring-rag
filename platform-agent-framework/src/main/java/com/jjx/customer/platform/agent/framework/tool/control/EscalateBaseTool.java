package com.jjx.customer.platform.agent.framework.tool.control;


import com.jjx.customer.platform.agent.framework.tool.BaseTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 主动升级工具（BaseTool，控制面）：模型判断继续无望（超出能力/权限、
 * 缺只有用户才知道的信息）时终止流程并升级，理由交编排层决定交付形态。
 */
@Component
public class EscalateBaseTool implements BaseTool {

    public static final String NAME = "escalate";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "判断当前任务无法在本阶段能力内完成时调用：参数 reason 为升级理由"
                + "（缺什么信息 / 超出什么边界）。调用后流程终止并升级处理。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{
                  "reason":{"type":"string","description":"升级理由（缺什么信息、卡在什么上）"}
                },"required":["reason"]}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String reason = invocation.str("reason", "");
        if (reason.isBlank()) {
            return ToolResult.error("reason 为必填");
        }
        return ToolResult.escalate(reason);
    }
}
