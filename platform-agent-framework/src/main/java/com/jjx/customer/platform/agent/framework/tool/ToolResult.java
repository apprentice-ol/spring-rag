package com.jjx.customer.platform.agent.framework.tool;

import com.jjx.customer.platform.agent.framework.result.ContextArtifact;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果：文本产出（回喂模型）+ 证据片段（进上下文/引用）+ 失败表达。
 *
 * @param ok       是否成功
 * @param content  成功时的文本产出
 * @param error    失败原因（成功时为 null）
 * @param artifacts 证据片段（检索/查询类工具产出；非此类工具为空）
 * @param details  结构化明细（可空；落 trace / 前端展开用）
 * @param control  控制面信号（BaseTool 专用；数据面工具恒为 NONE）
 */
public record ToolResult(boolean ok, String content, String error, List<ContextArtifact> artifacts,
                        Map<String, Object> details, ToolControl control) {

    public ToolResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        details = details == null ? Map.of() : Map.copyOf(details);
        control = control == null ? ToolControl.NONE : control;
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null, List.of(), Map.of(), ToolControl.NONE);
    }

    public static ToolResult ok(String content, List<ContextArtifact> artifacts) {
        return new ToolResult(true, content, null, artifacts, Map.of(), ToolControl.NONE);
    }

    public static ToolResult ok(String content, List<ContextArtifact> artifacts, Map<String, Object> details) {
        return new ToolResult(true, content, null, artifacts, details, ToolControl.NONE);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, null, message, List.of(), Map.of(), ToolControl.NONE);
    }

    /** 出环：以 content 作为本节点终稿。 */
    public static ToolResult finish(String content) {
        return new ToolResult(true, content, null, List.of(), Map.of(), ToolControl.FINISH);
    }

    /** 转澄清：content 为追问文案。 */
    public static ToolResult askUser(String content) {
        return new ToolResult(true, content, null, List.of(), Map.of(), ToolControl.ASK_USER);
    }

    /** 主动升级：content 为升级理由（引擎产出 ESCALATE 结果）。 */
    public static ToolResult escalate(String content) {
        return new ToolResult(true, content, null, List.of(), Map.of(), ToolControl.ESCALATE);
    }

    /** 回喂给模型的文本（失败也回喂，让模型自行纠偏）。 */
    public String asObservation() {
        return ok ? (content == null ? "" : content) : "[工具错误] " + error;
    }
}
