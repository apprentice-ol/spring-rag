package com.agentframework.engine.promptmanager;

import com.agentframework.runtime.session.Message;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置模板渲染器：支持 {@code {{变量}}} 与 {@code {{变量|默认值}}}。
 *
 * <p>解析顺序：内置变量（messages / sessionId / nodeId）→ 上下文变量 → 点号路径取值；
 * 未解析到的变量替换为空串，避免占位符泄漏给模型。</p>
 */
public final class TemplatePromptRenderer implements PromptRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    @Override
    public String name() {
        return "template";
    }

    @Override
    public String render(Prompt prompt, PromptContext context) {
        String template = prompt == null ? "" : prompt.template();
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String expression = matcher.group(1);
            String fallback = "";
            int separator = expression.indexOf('|');
            if (separator >= 0) {
                fallback = expression.substring(separator + 1).trim();
                expression = expression.substring(0, separator);
            }
            Object value = resolve(expression.trim(), context);
            String replacement = value == null ? fallback : String.valueOf(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 解析变量。
     *
     * @param path    变量路径
     * @param context 渲染上下文
     * @return 变量值，未解析时返回 null
     */
    private Object resolve(String path, PromptContext context) {
        if ("sessionId".equals(path)) {
            return context.sessionId();
        }
        if ("nodeId".equals(path)) {
            return context.nodeId();
        }
        if ("messages".equals(path)) {
            return joinMessages(context.messages());
        }
        Map<String, Object> variables = context.variables();
        if (variables.containsKey(path)) {
            return variables.get(path);
        }
        String[] parts = path.split("\\.");
        Object current = variables.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * 拼接会话消息文本。
     *
     * @param messages 消息列表，可为 null
     * @return 拼接结果
     */
    private String joinMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            builder.append(message.role().name().toLowerCase()).append(": ")
                    .append(message.content()).append('\n');
        }
        return builder.toString().stripTrailing();
    }
}
