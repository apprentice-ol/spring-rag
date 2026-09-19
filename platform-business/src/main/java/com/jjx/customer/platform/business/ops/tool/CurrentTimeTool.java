package com.jjx.customer.platform.business.ops.tool;

import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

/**
 * 当前时间查询工具：给 think 节点的模型提供时间锚点——用户说的是相对/口语时间
 * （「昨天下午」「一小时前」）而 query_logs 要 ISO 时间窗时，先查本工具再换算，
 * 不要凭训练记忆猜「今天几号」。
 */
public class CurrentTimeTool implements Tool {

    /** 工具 id。 */
    public static final String TOOL_ID = "get_time";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(TOOL_ID,
                "查询服务器当前时间（ISO-8601、星期、时区）。换算相对/口语时间（昨天下午、一小时前）"
                        + "为 query_logs 的时间窗前先调用，不要凭记忆猜当前日期。",
                new ToolParameter[0]);
    }

    @Override
    public ToolResult invoke(ToolInput input, ToolContext context) {
        LocalDateTime now = LocalDateTime.now();
        ZoneId zone = ZoneId.systemDefault();
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
        return ToolResult.ok("当前时间 " + now.format(ISO) + "（" + weekday + "，时区 " + zone.getId() + "）",
                Map.of("now", now.format(ISO), "today", now.toLocalDate().toString(),
                        "weekday", weekday, "timezone", zone.getId()));
    }
}
