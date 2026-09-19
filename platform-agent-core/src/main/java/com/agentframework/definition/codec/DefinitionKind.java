package com.agentframework.definition.codec;

import java.util.Locale;
import java.util.Optional;

/** 定义种类：文档中的 {@code kind} 字段取值。 */
public enum DefinitionKind {

    AGENT,
    WORKFLOW,
    PROMPT,
    TOOL;

    /** @return 文档中使用的名称（小写） */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param wireName 文档中的名称
     * @return 对应种类
     */
    public static Optional<DefinitionKind> fromWire(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        String normalized = wireName.trim().toUpperCase(Locale.ROOT);
        for (DefinitionKind kind : values()) {
            if (kind.name().equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
