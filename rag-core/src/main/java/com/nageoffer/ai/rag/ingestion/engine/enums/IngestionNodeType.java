package com.nageoffer.ai.rag.ingestion.engine.enums;

import java.util.Arrays;
import lombok.Getter;

/** 节点类型（与 NodeConfig.nodeType 的字符串值对应）。 */
@Getter
public enum IngestionNodeType {

    FETCHER("fetcher"),
    PARSER("parser"),
    ENHANCER("enhancer"),
    CHUNKER("chunker"),
    ENRICHER("enricher"),
    INDEXER("indexer");

    private final String value;

    IngestionNodeType(String value) {
        this.value = value;
    }

    public static IngestionNodeType fromValue(String value) {
        return Arrays.stream(values())
                .filter(t -> t.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知节点类型: " + value));
    }
}
