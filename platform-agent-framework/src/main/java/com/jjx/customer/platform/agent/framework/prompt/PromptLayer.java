package com.jjx.customer.platform.agent.framework.prompt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 层（三层增强之一）：链路级 / Agent 人格级 / Workflow 任务级。
 *
 * <p><b>增强而非覆盖</b>：各层内容按装配顺序<b>相加</b>；key 按构件分域
 * （{@code chat/*}、{@code agent/{agentId}/*}、{@code workflow/{workflowId}/*}、
 * {@code tool/{name}/desc}），跨层同名即装配错误。</p>
 *
 * @param id        层标识（如 "link" / "agent:knowledge" / "workflow:ops_diagnose"）
 * @param release   层版本凭证（如 "r3"；无版本管理时为 null）
 * @param keyPrefix 本层 key 前缀（非空时校验所有 key 必须落在此前缀下）
 * @param contents  key → 内容（保留顺序）
 */
public record PromptLayer(String id, String release, String keyPrefix, Map<String, String> contents) {

    public PromptLayer {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("PromptLayer.id 不能为空");
        }
        Map<String, String> ordered = new LinkedHashMap<>(contents == null ? Map.of() : contents);
        if (keyPrefix != null && !keyPrefix.isBlank()) {
            for (String key : ordered.keySet()) {
                if (!key.startsWith(keyPrefix)) {
                    throw new IllegalArgumentException(
                            "Prompt key 越层：" + key + " 不在层 " + id + " 的前缀 " + keyPrefix + " 下");
                }
            }
        }
        contents = Collections.unmodifiableMap(ordered);
    }

    public static PromptLayer of(String id, String keyPrefix, Map<String, String> contents) {
        return new PromptLayer(id, null, keyPrefix, contents);
    }

    /** 身份片段（进快照 identity，如 "agent:knowledge@r3"）。 */
    public String identity() {
        return release == null || release.isBlank() ? id : id + "@" + release;
    }
}
