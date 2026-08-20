package com.nageoffer.ai.rag.ingestion.service;

import com.baomidou.mybatisplus.extension.toolkit.Db;
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
import com.nageoffer.ai.rag.storage.service.FileStorageService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 入库引擎服务：封装 engine.execute + 任务状态落库（新旧链路整合的枢纽）。
 *
 * <p>建 IngestionContext → 拿 PipelineDefinition → engine.execute → 写 sa_document → 返回结果。
 * 节点级日志（sa_ingestion_task_node）持久化见 P7。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionEngineService {

    private final IngestionEngine engine;
    private final IngestionPipelineService pipelineService;
    private final FileStorageService fileStorageService;
    private final DocumentMapper documentMapper;
    private final IngestionTaskNodeMapper taskNodeMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 向量表名（与 spring.ai.vectorstore.pgvector.table-name 同源，避免硬编码漂移） */
    @Value("${spring.ai.vectorstore.pgvector.table-name:spring_ai_store_vector}")
    private String vectorTable;

    /**
     * 节点日志异步落库执行器（虚拟线程，受管关闭）。
     * <p>替代裸 {@code Thread.ofVirtual().start(...)} fire-and-forget：那种方式异常被吞、
     * 停机时 daemon 虚拟线程直接被杀。close() 停机时等待在途落库完成。</p>
     */
    private final ExecutorService nodeLogExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ingest-node-log-", 0).factory());

    @PreDestroy
    void shutdownNodeLogExecutor() {
        nodeLogExecutor.close();
    }

    /**
     * 执行入库任务。
     *
     * @param pipelineId 管道 ID
     * @param source     文档源
     * @param bytes      文档字节
     * @param mimeType   MIME 类型
     * @param collectionId 集合 ID
     * @param docId      文档标识；空则生成（同步链）。MQ 重投链路传入同一 docId 时，
     *                   开头的幂等 DELETE 会清掉半途中断的残留向量再重跑，保证不重复
     */
    public IngestionResult executeTask(String pipelineId, DocumentSource source, byte[] bytes,
                                       String mimeType, Long collectionId, Boolean plainText, String docId) {
        String taskId = UUID.randomUUID().toString();
        String effectiveDocId = docId != null && !docId.isBlank() ? docId : taskId;
        long t0 = System.currentTimeMillis();
        log.info("[INGEST] 开始: pipelineId={}, taskId={}, docId={}, mimeType={}, plainText={}",
                pipelineId, taskId, effectiveDocId, mimeType, plainText);

        // 幂等锚点：同一 docId 重复执行（MQ at-least-once 重投 / 上次半途中断）先清残留向量，
        // 否则重跑会产生同一文档的多份向量（doc_id 相同、id 不同，检索侧无法去重）
        int removed = jdbcTemplate.update(
                "DELETE FROM " + vectorTable + " WHERE metadata->>'doc_id' = ?", effectiveDocId);
        if (removed > 0) {
            log.info("[INGEST] 幂等清理: docId={} 删除残留向量 {} 条", effectiveDocId, removed);
        }

        IngestionContext ctx = IngestionContext.builder()
                .taskId(taskId)
                .pipelineId(pipelineId)
                .source(source)
                .rawBytes(bytes)
                .mimeType(mimeType)
                .plainTextChunking(plainText)
                .collectionId(collectionId)
                .build();

        PipelineDefinition pipeline = pipelineService.getDefinition(pipelineId);

        IngestionContext result = engine.execute(pipeline, ctx);

        String status = result.getStatus() == IngestionStatus.COMPLETED ? "DONE" : "FAILED";
        int chunkCount = result.getChunks() == null ? 0 : result.getChunks().size();

        DocumentEntity entity = new DocumentEntity();
        entity.setDocId(effectiveDocId);
        entity.setName(source.getFileName());
        entity.setMimeType(mimeType);
        entity.setSourceType(source.getType() == null ? null : source.getType().getValue());
        // 保存原始文件到 RustFS，写入公开 URL 供前端直连预览
        try {
            StoredFileDTO stored = fileStorageService.upload(effectiveDocId, bytes, source.getFileName(), mimeType);
            String kbPublicUrl = fileStorageService.getKbPublicUrl(stored.getUrl());
            entity.setSourceLocation(kbPublicUrl);
        } catch (Exception e) {
            log.warn("[INGEST] 保存原始文件失败（不影响入库）: docId={}, {}", effectiveDocId, e.getMessage());
        }
        entity.setCollectionId(collectionId);
        entity.setChunkCount(chunkCount);
        entity.setStatus(status);
        if (result.getError() != null) {
            entity.setErrorMsg(result.getError().getMessage());
            log.info("[INGEST] 入库失败: docId={}, {}", effectiveDocId, result.getError().getMessage());
        }
        documentMapper.insert(entity);

        String docIdForLog = effectiveDocId;
        nodeLogExecutor.execute(() -> saveNodeLogs(result, taskId, docIdForLog));

        log.info("[INGEST] 完成: taskId={}, docId={}, chunks={}, status={}, 总耗时 {}ms",
                taskId, effectiveDocId, chunkCount, status, System.currentTimeMillis() - t0);
        return new IngestionResult(effectiveDocId, source.getFileName(), chunkCount, status);
    }

    /**
     * 保存节点日志（批量 insert；异常记日志不外抛）。
     */
    private void saveNodeLogs(IngestionContext context, String taskId, String docId) {
        List<NodeLog> logs = context.getLogs();
        if (logs == null || logs.isEmpty()) {
            return;
        }
        try {
            List<IngestionTaskNodeEntity> nodes = new ArrayList<>(logs.size());
            for (NodeLog logEntry : logs) {
                IngestionTaskNodeEntity node = new IngestionTaskNodeEntity();
                node.setTaskId(taskId);
                node.setNodeId(logEntry.getNodeId());
                node.setNodeType(logEntry.getNodeType());
                node.setStatus(logEntry.isSuccess() ? "success" : "failed");
                node.setDurationMs(logEntry.getDurationMs());
                node.setMessage(logEntry.getMessage());
                node.setErrorMessage(logEntry.getError());
                try {
                    node.setOutputJson(logEntry.getOutput() == null ? null
                            : objectMapper.writeValueAsString(logEntry.getOutput()));
                } catch (Exception e) {
                    log.warn("[INGEST] 节点日志序列化失败: nodeId={}, {}", logEntry.getNodeId(), e.getMessage());
                }
                nodes.add(node);
            }
            Db.saveBatch(nodes);
        } catch (Exception e) {
            log.error("[INGEST] 节点日志落库失败: taskId={}, docId={}, {} 条日志丢失",
                    taskId, docId, logs.size(), e);
        }
    }
}
