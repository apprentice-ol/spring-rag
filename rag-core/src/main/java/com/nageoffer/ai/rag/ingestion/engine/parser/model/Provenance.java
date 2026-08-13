package com.nageoffer.ai.rag.ingestion.engine.parser.model;

/** 来源信息（sourceFile 文件名/ID，sheetName 仅 Excel 非空）。 */
public record Provenance(
        String sourceFile,
        String sheetName) {

    /** 便捷工厂（仅文件来源，sheetName 为空，对应原 ragent Provenance.ofFile）。 */
    public static Provenance ofFile(String sourceFile) {
        return new Provenance(sourceFile, null);

    }
}
