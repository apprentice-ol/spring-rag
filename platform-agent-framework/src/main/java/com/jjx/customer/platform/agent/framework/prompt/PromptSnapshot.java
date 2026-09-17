package com.jjx.customer.platform.agent.framework.prompt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 快照：一次执行的不可变内容包（三层增强后的结果）。
 *
 * <p>三层是<b>补充增强</b>关系，不是覆盖：链路级常驻段 + Agent 人格段（基础）+
 * Workflow 任务段（增强）+ 节点段；key 按构件分域，跨层同名即装配错误。</p>
 *
 * @param contents     全部 key → 内容
 * @param identity     人类可读身份（如 "基线包" / "platform@r1 + ops@r3"）
 * @param contentHash  内容摘要（进缓存 key 与执行指纹）
 * @param releasesSpec 可重建凭证（各层 release 指纹；无绑定为 null）
 */
public record PromptSnapshot(Map<String, String> contents,
                             String identity,
                             String contentHash,
                             String releasesSpec) {

    public PromptSnapshot {
        // 保留声明顺序：三层增强的拼接顺序必须稳定（prompt cache 前缀稳定性依赖它）
        contents = Collections.unmodifiableMap(new LinkedHashMap<>(contents == null ? Map.of() : contents));
    }

    public static PromptSnapshot empty() {
        return new PromptSnapshot(Map.of(), "空快照", "", null);
    }

    /**
     * 拼接后的 system 文本：按层装配顺序（链路 → Agent 人格 → Workflow 任务 → 节点段）依次相接。
     * 易变内容（资料/检索结果）不进 system，保证前缀稳定。
     */
    public String systemText() {
        return String.join("\n\n", contents.values());
    }

    /** 严格取：骨架契约 key，缺即报错（绝不静默 fallback）。 */
    public String prompt(String key) {
        String value = contents.get(key);
        if (value == null) {
            throw new IllegalStateException("PromptSnapshot 缺 key: " + key + "（identity=" + identity + "）");
        }
        return value;
    }

    /** 宽松取：工具实现细节 key，缺返回 null（调用方回退代码默认）。 */
    public String promptOrNull(String key) {
        return contents.get(key);
    }
}
