package com.nageoffer.ai.rag.ingestion.service;

/** 入库结果。 */
public record IngestionResult(String docId, String name, int chunkCount, String status) {
}
