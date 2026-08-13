package com.nageoffer.ai.rag.ingestion.engine.enums;

import lombok.Getter;

/** 文档来源类型（按用户范围：只 FILE/URL，不迁飞书）。 */
@Getter
public enum SourceType {

    FILE("file"),
    URL("url");

    private final String value;

    SourceType(String value) {
        this.value = value;
    }
}
