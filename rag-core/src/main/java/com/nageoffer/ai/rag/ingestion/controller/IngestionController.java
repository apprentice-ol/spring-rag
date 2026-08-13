package com.nageoffer.ai.rag.ingestion.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.rag.config.properties.RagStorageProperties;
import com.nageoffer.ai.rag.ingestion.mq.IngestionMessage;
import com.nageoffer.ai.rag.ingestion.mq.IngestionProducer;
import com.nageoffer.ai.rag.ingestion.service.IngestionService;
import com.nageoffer.ai.rag.storage.client.ObjectStorageClient;
import com.nageoffer.ai.rag.storage.service.impl.LocalFileStorageService;
import com.nageoffer.ai.rag.ingestion.service.IngestionResult;
import com.nageoffer.ai.rag.ingestion.domain.dto.PageResult;
import com.nageoffer.ai.rag.ingestion.domain.entity.DocumentEntity;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionTaskEntity;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionTaskNodeEntity;
import com.nageoffer.ai.rag.ingestion.mapper.DocumentMapper;
import com.nageoffer.ai.rag.ingestion.mapper.IngestionTaskMapper;
import com.nageoffer.ai.rag.ingestion.mapper.IngestionTaskNodeMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档入库入口。
 *
 * <p>接口：
 * <ul>
 *   <li>{@code POST /docs/upload} —— 同步入库</li>
 *   <li>{@code POST /docs/upload-async} —— 异步入库</li>
 *   <li>{@code GET /docs} —— 文档列表</li>
 *   <li>{@code GET /docs/{docId}} —— 文档详情</li>
 *   <li>{@code DELETE /docs/{docId}} —— 删除文档（含向量数据）</li>
 *   <li>{@code GET /docs/ingestion/tasks/{taskId}} —— 任务状态</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/docs")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;
    private final LocalFileStorageService fileStorageService;
    private final IngestionProducer ingestionProducer;
    private final DocumentMapper documentMapper;
    private final IngestionTaskMapper taskMapper;
    private final IngestionTaskNodeMapper taskNodeMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorageClient objectStorageClient;
    private final RagStorageProperties storageProperties;

    /** 向量表名（与 spring.ai.vectorstore.pgvector.table-name 同源，避免硬编码漂移） */
    @Value("${spring.ai.vectorstore.pgvector.table-name:spring_ai_store_vector}")
    private String vectorTable;

    /** 同步入库。plainText=true 走纯文本切分（不保 block 元数据）；缺省/false 走语义感知（block-aware）。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionResult upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(required = false) Long collectionId,
                                  @RequestParam(required = false) Boolean plainText) {
        String filename = file.getOriginalFilename();
        String mimeType = resolveMime(file, filename);
        long size = file.getSize();

        log.info("[上传] 开始同步入库: file={}, size={}, mimeType={}, plainText={}", filename, formatSize(size), mimeType, plainText);

        if (file.isEmpty()) {
            log.warn("[上传] 空文件: {}", filename);
            throw new IllegalArgumentException("上传文件为空: " + filename);
        }

        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            IngestionResult result = ingestionService.ingest(resource, filename, mimeType, collectionId, plainText);
            log.info("[上传] 同步入库完成: file={}, docId={}, chunks={}",
                    filename, result.docId(), result.chunkCount());
            return result;
        } catch (MaxUploadSizeExceededException e) {
            log.warn("[上传] 文件超过大小限制: file={}, size={}, limit={}",
                    filename, formatSize(size), formatSize(e.getMaxUploadSize()));
            throw e;
        } catch (IOException e) {
            log.error("[上传] 读取文件流失败: file={}, size={}", filename, formatSize(size), e);
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[上传] 同步入库异常: file={}, size={}", filename, formatSize(size), e);
            throw e;
        }
    }

    /** 异步入库。 */
    @PostMapping(value = "/upload-async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadAsync(@RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) Long collectionId) {
        String filename = file.getOriginalFilename();
        String mimeType = resolveMime(file, filename);
        long size = file.getSize();
        String docId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();

        log.info("[上传-异步] 开始: file={}, size={}, docId={}, taskId={}",
                filename, formatSize(size), docId, taskId);

        if (file.isEmpty()) {
            log.warn("[上传-异步] 空文件: {}", filename);
            throw new IllegalArgumentException("上传文件为空: " + filename);
        }

        try {
            Path saved = fileStorageService.save(file, docId);
            log.debug("[上传-异步] 文件已保存: path={}", saved);

            IngestionTaskEntity task = new IngestionTaskEntity();
            task.setTaskId(taskId);
            task.setDocId(docId);
            task.setStatus("PENDING");
            task.setProgress(0);
            taskMapper.insert(task);
            log.debug("[上传-异步] 任务已创建: taskId={}, docId={}", taskId, docId);

            ingestionProducer.send(new IngestionMessage(taskId, docId, saved.toString(), filename, mimeType, collectionId));
            log.info("[上传-异步] MQ 已发送: taskId={}, docId={}", taskId, docId);

            return Map.of("taskId", taskId, "docId", docId, "status", "PENDING");
        } catch (MaxUploadSizeExceededException e) {
            log.warn("[上传-异步] 文件超过大小限制: file={}, size={}", filename, formatSize(size));
            throw e;
        } catch (Exception e) {
            log.error("[上传-异步] 创建任务异常: file={}, size={}", filename, formatSize(size), e);
            throw e;
        }
    }

    // ==================== 文档列表 / 详情 ====================

    /** 文档列表（包含失败的文档，前端可据此清理）。 */
    @GetMapping
    public List<DocumentEntity> listDocuments() {
        List<DocumentEntity> docs = documentMapper.selectList(
                new LambdaQueryWrapper<DocumentEntity>()
                        .orderByDesc(DocumentEntity::getCreatedAt));
        log.debug("[文档列表] 总数={}", docs.size());
        return docs;
    }

    /**
     * 文档分页查询（管理后台文档管理用）。
     * keyword 模糊匹配文件名；sourceType / status 精确过滤。size 上限 100。
     */
    @GetMapping("/page")
    public PageResult<DocumentEntity> pageDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long collectionId,
            @RequestParam(required = false) Boolean unassigned) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        // count 用无 orderBy 的干净 wrapper（MP selectCount 会保留 ORDER BY，PG 下 COUNT(*) ... ORDER BY 报错）
        long total = documentMapper.selectCount(buildDocWrapper(keyword, sourceType, status, collectionId, unassigned));
        List<DocumentEntity> records = documentMapper.selectList(
                buildDocWrapper(keyword, sourceType, status, collectionId, unassigned)
                        .orderByDesc(DocumentEntity::getCreatedAt)
                        .last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size));
        log.debug("[文档分页] page={}, size={}, keyword={}, sourceType={}, status={}, collectionId={}, unassigned={} → total={}",
                page, size, keyword, sourceType, status, collectionId, unassigned, total);
        return new PageResult<>(total, records);
    }

    private static LambdaQueryWrapper<DocumentEntity> buildDocWrapper(String keyword, String sourceType, String status,
                                                                     Long collectionId, Boolean unassigned) {
        LambdaQueryWrapper<DocumentEntity> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.like(DocumentEntity::getName, keyword);
        }
        if (StringUtils.hasText(sourceType)) {
            qw.eq(DocumentEntity::getSourceType, sourceType);
        }
        if (StringUtils.hasText(status)) {
            qw.eq(DocumentEntity::getStatus, status);
        }
        // 集合归属过滤（两个正交参数，避免单参数三态歧义）：
        //   unassigned=true → 仅独立文件（collection_id IS NULL）
        //   collectionId≠null → 仅该集合内
        //   都不传 → 全部（独立 + 集合内）
        if (Boolean.TRUE.equals(unassigned)) {
            qw.isNull(DocumentEntity::getCollectionId);
        } else if (collectionId != null) {
            qw.eq(DocumentEntity::getCollectionId, collectionId);
        }
        return qw;
    }

    /** 文档详情。 */
    @GetMapping("/{docId}")
    public DocumentEntity getDocument(@PathVariable String docId) {
        DocumentEntity doc = documentMapper.selectOne(
                new LambdaQueryWrapper<DocumentEntity>()
                        .eq(DocumentEntity::getDocId, docId));
        if (doc == null) {
            log.warn("[文档详情] 不存在: docId={}", docId);
        }
        return doc;
    }

    /**
     * 文档预览内容——从向量库中按 chunk_index 顺序取出该文档的所有文本块，
     * 合并为完整文本供前端预览。
     */
    @GetMapping("/{docId}/content")
    public Map<String, Object> previewContent(@PathVariable String docId) {
        DocumentEntity doc = documentMapper.selectOne(
                new LambdaQueryWrapper<DocumentEntity>()
                        .eq(DocumentEntity::getDocId, docId));

        String sql = "SELECT content, metadata->>'chunk_index' AS ci, metadata->>'block_type' AS bt"
                + " FROM spring_ai_store_vector"
                + " WHERE metadata->>'doc_id' = ?"
                + " ORDER BY coalesce((metadata->>'chunk_index')::int, 0) ASC";

        List<Map<String, Object>> chunks = jdbcTemplate.query(sql, new Object[]{docId},
                (rs, rowNum) -> Map.of(
                        "content", rs.getString("content") != null ? rs.getString("content") : "",
                        "chunkIndex", rs.getObject("ci") != null ? rs.getInt("ci") : rowNum,
                        "blockType", rs.getString("bt") != null ? rs.getString("bt") : ""
                ));

        log.info("[文档预览] docId={}, name={}, chunks={}", docId, doc != null ? doc.getName() : "?", chunks.size());
        return Map.of(
                "docId", docId,
                "docName", doc != null ? doc.getName() : "",
                "chunks", chunks
        );
    }

    /**
     * 下载源文档原始文件（用于前端预览/下载）。
     * <p>PDF→iframe 预览、图片→img 标签、其他→浏览器下载。</p>
     */
    @GetMapping("/{docId}/file")
    public ResponseEntity<Resource> getDocumentFile(@PathVariable String docId) {
        DocumentEntity doc = documentMapper.selectOne(
                new LambdaQueryWrapper<DocumentEntity>().eq(DocumentEntity::getDocId, docId));
        if (doc == null || doc.getSourceLocation() == null) {
            log.warn("[文件下载] 文档不存在或源文件未保存: docId={}", docId);
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = readSourceBytes(doc.getSourceLocation());
            String mime = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
            log.info("[文件下载] docId={}, size={}b, mime={}", docId, bytes.length, mime);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mime))
                    .body(new ByteArrayResource(bytes));
        } catch (Exception e) {
            log.warn("[文件下载] 失败: docId={}, location={}, {}", docId, doc.getSourceLocation(), e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * sourceLocation 入库时存的是公开 URL（{@code http://host:port/{kbBucket}/{key}}），
     * 而 openStream 期望对象 key —— 从 URL 中解析出 key；已是 key 的（历史/local 模式）原样返回。
     */
    private String resolveSourceKey(String location) {
        try {
            java.net.URI uri = java.net.URI.create(location);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return location; // 非 URL（已是 key 或本地路径）
            }
            String path = uri.getPath(); // /springai-rag-kb/{docId}/{hash}.md
            String bucketPrefix = "/" + storageProperties.getKbBucket() + "/";
            return path.startsWith(bucketPrefix) ? path.substring(bucketPrefix.length()) : path.substring(1);
        } catch (Exception e) {
            return location;
        }
    }

    /**
     * 读取文档源文件字节：kb 桶公共读，匿名 HTTP GET 内部 endpoint（后端必达 RustFS）。
     * <p>不走 {@code fileStorageService.openStream}（SDK getObject）—— AWS SDK v2 对 RustFS 的
     * GetObject 流式 checksum 校验存在兼容性问题：文件实际存在（直连 200）但 SDK 下载抛异常，
     * 被 {@link #getDocumentFile} 吞成 404。HTTP GET 公共读 URL 绕开整个 SDK 签名 + checksum 管线。</p>
     */
    private byte[] readSourceBytes(String sourceLocation) throws IOException {
        String key = resolveSourceKey(sourceLocation);
        String endpoint = storageProperties.getS3().getEndpoint();
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        java.net.URI uri = java.net.URI.create(base + "/" + storageProperties.getKbBucket() + "/" + key);
        try (InputStream is = uri.toURL().openStream()) {
            return is.readAllBytes();
        }
    }

    // ==================== 文档删除 ====================

    /**
     * 删除文档（级联清理）。
     * <p>
     * 删除关联数据：sa_document → sa_ingestion_task → sa_ingestion_task_node → 向量表（向量）
     * </p>
     */
    @DeleteMapping("/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable String docId) {
        log.info("[删除] 开始: docId={}", docId);

        // 1. 查询文档
        DocumentEntity doc = documentMapper.selectOne(
                new LambdaQueryWrapper<DocumentEntity>()
                        .eq(DocumentEntity::getDocId, docId));
        if (doc == null) {
            log.warn("[删除] 文档不存在: docId={}", docId);
            return Map.of("docId", docId, "status", "NOT_FOUND");
        }

        // 2. 删除关联的入库任务节点日志
        List<IngestionTaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<IngestionTaskEntity>()
                        .eq(IngestionTaskEntity::getDocId, docId));
        for (IngestionTaskEntity task : tasks) {
            int nodeDeleted = taskNodeMapper.delete(
                    new LambdaQueryWrapper<IngestionTaskNodeEntity>()
                            .eq(IngestionTaskNodeEntity::getTaskId, task.getTaskId()));
            log.debug("[删除] 清除任务节点: taskId={}, 删除={}条", task.getTaskId(), nodeDeleted);
        }

        // 3. 删除入库任务
        int taskDeleted = taskMapper.delete(
                new LambdaQueryWrapper<IngestionTaskEntity>()
                        .eq(IngestionTaskEntity::getDocId, docId));
        log.debug("[删除] 清除任务: docId={}, 删除={}条", docId, taskDeleted);

        // 4. 删除向量数据（向量表 metadata->>'doc_id' = docId；表名取自 pgvector 配置，避免硬编码漂移）
        try {
            int vectorDeleted = jdbcTemplate.update(
                    "DELETE FROM " + vectorTable + " WHERE metadata->>'doc_id' = ?", docId);
            log.info("[删除] 清除向量: docId={}, table={}, 删除={}条", docId, vectorTable, vectorDeleted);
        } catch (Exception e) {
            log.warn("[删除] 向量清除异常: {}", e.getMessage());
        }

        // 5. 删除文档记录
        documentMapper.deleteById(doc.getId());

        log.info("[删除] 完成: docId={}, name={}", docId, doc.getName());
        return Map.of("docId", docId, "name", doc.getName(), "status", "DELETED");
    }

    // ==================== 任务状态 ====================

    /** 查询异步入库任务状态。 */
    @GetMapping("/ingestion/tasks/{taskId}")
    public IngestionTaskEntity getTask(@PathVariable String taskId) {
        log.debug("[任务查询] taskId={}", taskId);
        IngestionTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<IngestionTaskEntity>()
                .eq(IngestionTaskEntity::getTaskId, taskId));
        if (task == null) {
            log.warn("[任务查询] 任务不存在: taskId={}", taskId);
        } else {
            log.debug("[任务查询] taskId={}, status={}, progress={}",
                    taskId, task.getStatus(), task.getProgress());
        }
        return task;
    }

    // ==================== 资产代理（RustFS 图片/附件） ====================

    /**
     * 代理 RustFS/MinIO 中的资产文件（图片等），避免前端直连 S3 需处理认证。
     * 前端 markdown 渲染时把图片 URL 替换为此接口。
     */
    @GetMapping("/assets/{fileName}")
    public ResponseEntity<Resource> getAsset(@PathVariable String fileName) {
        String bucket = storageProperties.getAssetBucket();
        try {
            log.info("[资产代理] 尝试读取: bucket={}, file={}", bucket, fileName);
            InputStream is = objectStorageClient.getObject(bucket, fileName);
            byte[] bytes = is.readAllBytes();
            String contentType = resolveAssetMime(fileName);
            log.info("[资产代理] 成功: bucket={}, file={}, size={}b", bucket, fileName, bytes.length);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(new ByteArrayResource(bytes));
        } catch (Exception e) {
            log.warn("[资产代理] 读取失败: bucket={}, file={}, 原因: {}", bucket, fileName, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    // ==================== 工具方法 ====================

    private String resolveMime(MultipartFile file, String filename) {
        String ct = file.getContentType();
        if (ct != null && !ct.isBlank() && !"application/octet-stream".equals(ct)) {
            return ct;
        }
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".doc")) return "application/msword";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    /** 资产文件扩展名 → MIME（资产代理主要服务图片；FileTypeDetector 返回的是类型标识如 "jpg"，不是合法 MIME） */
    private static String resolveAssetMime(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0B";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }
}
