package com.nageoffer.ai.rag.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.ingestion.domain.entity.DocumentEntity;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionTaskNodeEntity;
import com.nageoffer.ai.rag.ingestion.engine.IngestionContext;
import com.nageoffer.ai.rag.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.rag.ingestion.engine.NodeLog;
import com.nageoffer.ai.rag.ingestion.engine.PipelineDefinition;
import com.nageoffer.ai.rag.ingestion.engine.enums.IngestionStatus;
import com.nageoffer.ai.rag.ingestion.engine.fetcher.DocumentSource;
import com.nageoffer.ai.rag.ingestion.mapper.DocumentMapper;
import com.nageoffer.ai.rag.ingestion.mapper.IngestionTaskNodeMapper;
import com.nageoffer.ai.rag.storage.domian.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 入库引擎服务：封装 engine.execute + 任务状态落库（新旧链路整合的枢纽）。
 *
 * <p>建 IngestionContext → 拿 PipelineDefinition → engine.execute → 写 sa_document → 返回结果。
 * 节点级日志（sa_ingestion_task_node）持久化见 P7。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionEngineService {

    private final IngestionEngine engine;
    private final IngestionPipelineService pipelineService;
    private final com.nageoffer.ai.rag.storage.service.FileStorageService fileStorageService;
    private final DocumentMapper documentMapper;
    private final IngestionTaskNodeMapper taskNodeMapper;

    /**
     * 执行入库任务。
     * @param pipelineId 管道 ID
     * @param source 文档源
     * @param bytes 文档字节
     * @param mimeType MIME 类型
     * @param collectionId 集合 ID
     * @return 入库结果
     */
    public IngestionResult executeTask(String pipelineId, DocumentSource source, byte[] bytes, String mimeType, Long collectionId, Boolean plainText) {
        String taskId = UUID.randomUUID().toString();
        long t0 = System.currentTimeMillis();
        log.info("[INGEST] 开始: pipelineId={}, taskId={}, mimeType={}, plainText={}", pipelineId, taskId, mimeType, plainText);

        IngestionContext ctx = IngestionContext.builder()
                .taskId(taskId)
                .pipelineId(pipelineId)
                .source(source)
                .rawBytes(bytes)
                .mimeType(mimeType)
                .plainTextChunking(plainText)
                .build();

        PipelineDefinition pipeline = pipelineService.getDefinition(pipelineId);


        IngestionContext result = engine.execute(pipeline, ctx);

        String status = result.getStatus() == IngestionStatus.COMPLETED ? "DONE" : "FAILED";
        int chunkCount = result.getChunks() == null ? 0 : result.getChunks().size();

        DocumentEntity entity = new DocumentEntity();
        entity.setDocId(taskId);
        entity.setName(source.getFileName());
        entity.setMimeType(mimeType);
        entity.setSourceType(source.getType() == null ? null : source.getType().getValue());
        // 保存原始文件到 RustFS，写入公开 URL 供前端直连预览
        try {
            StoredFileDTO stored = fileStorageService.upload(taskId, bytes, source.getFileName(), mimeType);
            String kbPublicUrl = fileStorageService.getKbPublicUrl(stored.getUrl());
            entity.setSourceLocation(kbPublicUrl);
        } catch (Exception e) {
            log.warn("[INGEST] 保存原始文件失败（不影响入库）: taskId={}, {}", taskId, e.getMessage());
        }
        entity.setCollectionId(collectionId);
        entity.setChunkCount(chunkCount);
        entity.setStatus(status);
        if (result.getError() != null) {
            entity.setErrorMsg(result.getError().getMessage());
            log.info("[INGEST] 入库失败: taskId={}, {}", taskId, result.getError().getMessage());
        }
        documentMapper.insert(entity);

        // 这里使用虚拟线程异步保存节点日志
        Thread.ofVirtual().start(() -> saveNodeLogs(result, taskId));

        log.info("[INGEST] 完成: taskId={}, chunks={}, status={}, 总耗时 {}ms", taskId, chunkCount, status, System.currentTimeMillis() - t0);
        return new IngestionResult(taskId, source.getFileName(), chunkCount, status);
    }

    /**
     * 保存节点日志。
     */
    private void saveNodeLogs(IngestionContext context, String taskId) {
        if (context.getLogs() == null) {
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        for (NodeLog log : context.getLogs()) {
            IngestionTaskNodeEntity node = new IngestionTaskNodeEntity();
            node.setTaskId(taskId);
            node.setNodeId(log.getNodeId());
            node.setNodeType(log.getNodeType());
            node.setStatus(log.isSuccess() ? "success" : "failed");
            node.setDurationMs(log.getDurationMs());
            node.setMessage(log.getMessage());
            node.setErrorMessage(log.getError());
            try {
                node.setOutputJson(log.getOutput() == null ? null : mapper.writeValueAsString(log.getOutput()));
            } catch (Exception ignored) {
            }
            taskNodeMapper.insert(node);
        }
    }
}
