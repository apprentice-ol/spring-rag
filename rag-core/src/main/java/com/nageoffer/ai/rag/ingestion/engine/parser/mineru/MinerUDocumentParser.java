/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.rag.ingestion.engine.parser.mineru;

import com.nageoffer.ai.rag.ingestion.engine.parser.DocumentParser;
import com.nageoffer.ai.rag.ingestion.engine.parser.ParserType;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ParsedDocument;
import com.nageoffer.ai.rag.common.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MinerU 文档解析器(PDF / Word / PPT / Excel)
 * <p>
 * 走 MinerU 官方"本地文件批量上传解析"，串联各组件实现 B-lite 异步解析:
 * <ol>
 *   <li>{@link RPermitExpirableSemaphore} 获取跨实例解析许可，限制 MinerU outstanding 任务数</li>
 *   <li>{@link MinerUClient#requestUpload} 申请上传链接，拿 batchId + 上传 URL</li>
 *   <li>{@link MinerUClient#uploadFile} 把源文件字节 PUT 上传到 MinerU OSS</li>
 *   <li>{@link MinerUPollingExecutor#submitAndAwait} 阻塞等待完成</li>
 *   <li>{@link MinerUClient#downloadZip} 下载 zip</li>
 *   <li>{@link MinerUResultUnpacker#unpack} 解包为 Block 列表(图片自动上传 RustFS)</li>
 * </ol>
 * <p>
 * 本地上传链路不依赖任何公网可达的源文件 URL，适配内网/本地部署
 * 配置项见 {@link MinerUProperties}
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MinerUDocumentParser implements DocumentParser {

    /**
     * options 字段:文件名,写入 Provenance.sourceFile
     */
    public static final String OPT_SOURCE_FILE = "sourceFile";

    /**
     * options 字段:文档 ID,用于资产 key 命名;不传时自动生成 UUID
     */
    public static final String OPT_DOCUMENT_ID = "documentId";

    /**
     * options 字段:MinerU 结果解包路线,{@code markdown}(默认,走 full.md + commonmark) /
     * {@code content_list}(走 content_list.json 结构化,表格解析成 TableBlock)
     */
    public static final String OPT_UNPACK_MODE = "unpackMode";

    /**
     * ParsedDocument.metadata 字段:MinerU 分配的 batchId(排障 + B 升级用)
     */
    public static final String META_BATCH_ID = "minerU.batchId";

    /**
     * ParsedDocument.metadata 字段:zip 下载 URL
     */
    public static final String META_ZIP_URL = "minerU.zipUrl";

    private final MinerUClient minerUClient;
    private final MinerUPollingExecutor pollingExecutor;
    private final MinerUResultUnpacker resultUnpacker;
    private final MinerUProperties properties;
    private final RedissonClient redissonClient;

    public MinerUDocumentParser(MinerUClient minerUClient,
                                MinerUPollingExecutor pollingExecutor,
                                MinerUResultUnpacker resultUnpacker,
                                MinerUProperties properties,
                                RedissonClient redissonClient) {
        this.minerUClient = minerUClient;
        this.pollingExecutor = pollingExecutor;
        this.resultUnpacker = resultUnpacker;
        this.properties = properties;
        this.redissonClient = redissonClient;
    }

    @PostConstruct
    void initSemaphore() {
        if (!isApiKeyConfigured()) {
            log.warn("MinerU API key 未配置，跳过信号量初始化。PDF/Word/PPT 将走 Tika 解析");
            return;
        }
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(properties.getSemaphoreName());
        semaphore.setPermits(properties.getConcurrencyLimit());
        log.info("MinerU 分布式解析限流初始化: semaphoreName={}, maxConcurrent={}",
                properties.getSemaphoreName(), properties.getConcurrencyLimit());
    }

    @Override
    public String getParserType() {
        return ParserType.MINERU.getType();
    }

    @Override
    public boolean supports(String mimeType) {
        if (!isApiKeyConfigured()) {
            return false;
        }
        if (mimeType == null) {
            return false;
        }
        // Excel 不纳入 MIME 自动路由：默认走 POI 简单 key-val，复杂版面由上层显式选择 MinerU
        String lower = mimeType.toLowerCase(Locale.ROOT);
        return lower.contains("pdf")
                || lower.contains("wordprocessingml") || lower.contains("msword")
                || lower.contains("presentationml") || lower.contains("powerpoint");
    }

    /**
     * 检查 MinerU API key 是否已配置（环境变量或配置文件）。
     */
    private boolean isApiKeyConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            throw new ServiceException("MinerU 解析输入字节为空");
        }

        String permitId = null;
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(properties.getSemaphoreName());
        try {
            permitId = semaphore.tryAcquire(
                    properties.getMaxWaitSeconds(),
                    properties.getLeaseSeconds(),
                    TimeUnit.SECONDS
            );
            if (permitId == null) {
                throw new ServiceException("MinerU 解析任务过多，请稍后重试");
            }
            return doParseStructured(content, mimeType, options);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("MinerU 获取解析许可被中断");
        } finally {
            if (permitId != null) {
                boolean released = semaphore.tryRelease(permitId);
                if (!released) {
                    log.warn("MinerU parse permit already expired or released, permitId={}", permitId);
                }
            }
        }
    }

    private ParsedDocument doParseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        String sourceFile = extractString(options, OPT_SOURCE_FILE, "");
        String documentId = extractString(options, OPT_DOCUMENT_ID, UUID.randomUUID().toString());
        // 默认走 content_list 结构化路线：表格→TableBlock→TableChunker（表头保障、整表不切碎）。
        // markdown 路线会把表格 HTML 当文本按段落切分，产生 "<td colspan 被从中间截断" 的碎块
        // （实测接口文档 32 块碎表格），仅作 content_list 缺失/失败时的兜底。
        String unpackMode = extractString(options, OPT_UNPACK_MODE, "content_list");
        // MinerU 靠 name 扩展名识别格式,缺文件名时按 mimeType 补全
        String uploadName = resolveUploadName(sourceFile, mimeType, documentId);

        // 1. 申请上传链接(只提交元信息,不带 url)
        BatchSubmitRequest request = buildSubmitRequest(uploadName, documentId);
        BatchUploadTicket ticket = minerUClient.requestUpload(request);

        // 2. 把源文件字节直接 PUT 上传到 MinerU OSS
        minerUClient.uploadFile(ticket.uploadUrl(), content);
        log.info("MinerU 源文件上传完毕 documentId={} batchId={}", documentId, ticket.batchId());

        // 3. 阻塞 await 完成(上传后 MinerU 自动提交解析)
        MinerUStatus status;
        try {
            status = pollingExecutor
                    .submitAndAwait(ticket.batchId(), Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .get(properties.getTimeoutSeconds() + 30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ServiceException("MinerU 等待超时(包含调度缓冲)batchId=" + ticket.batchId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("MinerU 等待被中断 batchId=" + ticket.batchId());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ServiceException("MinerU 等待异常 batchId=" + ticket.batchId() + ": " + cause.getMessage());
        }

        // 4. 下载 zip
        byte[] zipBytes = minerUClient.downloadZip(status.zipUrl());

        // [调试] 本地保存一份 zip，查看 MinerU 返回结构（看完可删除此调用）
        dumpZipLocally(zipBytes, documentId);

        // 5. 解包为 ParsedDocument（按 unpackMode 选路线：markdown 旧路线 / content_list 结构化表格）
        ParsedDocument parsed = resultUnpacker.unpack(zipBytes, sourceFile, documentId, unpackMode);

        // 6. 注入 batchId + zipUrl 到 metadata,供 ParserNode/IngestionContext 持久化(排障 + 重试幂等)
        Map<String, Object> mergedMeta = new HashMap<>(parsed.metadata() == null ? Map.of() : parsed.metadata());
        mergedMeta.put(META_BATCH_ID, ticket.batchId());
        mergedMeta.put(META_ZIP_URL, status.zipUrl());
        mergedMeta.put("parser", getParserType());
        mergedMeta.put("mimeType", mimeType == null ? "" : mimeType);

        return ParsedDocument.of(parsed.blocks(), mergedMeta);
    }

    /**
     * [调试] 把 MinerU 返回的 zip 保存到本地，便于查看其内部结构（markdown + 图片）
     * <p>
     * 默认存到 {@code D:/mineru-dump/mineru_{documentId}.zip}，存盘失败不影响解析流程。
     * 看完 zip 结构后可删除本方法及其调用（dumpZipLocally）。
     */
    private void dumpZipLocally(byte[] zipBytes, String documentId) {
        try {
            Path dir = Paths.get("D:", "mineru-dump");
            Files.createDirectories(dir);
            Path file = dir.resolve("mineru_" + documentId + ".zip");
            Files.write(file, zipBytes);
            log.info("[调试] MinerU zip 已保存到本地: {} ({} 字节，解压后可见 .md 和图片)",
                    file.toAbsolutePath(), zipBytes.length);
        } catch (Exception e) {
            log.warn("[调试] MinerU zip 本地保存失败（不影响解析）: {}", e.getMessage());
        }
    }

    /**
     * 计算上传到 MinerU 的文件名,确保带正确扩展名(MinerU 靠它识别格式)
     * <p>
     * 有原始文件名直接用,否则按 mimeType 合成 {@code doc-{documentId}{ext}}
     */
    private String resolveUploadName(String sourceFile, String mimeType, String documentId) {
        if (sourceFile != null && !sourceFile.isBlank()) {
            return sourceFile;
        }
        return "doc-" + documentId + extFromMime(mimeType);
    }

    private BatchSubmitRequest buildSubmitRequest(String fileName, String documentId) {
        return new BatchSubmitRequest(
                fileName,
                documentId,
                properties.isOcr(),
                properties.isEnableTable(),
                properties.isEnableFormula(),
                properties.getLanguage()
        );
    }

    private static String extFromMime(String mimeType) {
        if (mimeType == null) {
            return ".bin";
        }
        String lower = mimeType.toLowerCase(Locale.ROOT);
        if (lower.contains("pdf")) return ".pdf";
        if (lower.contains("wordprocessingml")) return ".docx";
        if (lower.contains("msword")) return ".doc";
        if (lower.contains("presentationml")) return ".pptx";
        if (lower.contains("powerpoint")) return ".ppt";
        if (lower.contains("spreadsheetml")) return ".xlsx";
        if (lower.contains("ms-excel") || lower.contains("excel")) return ".xls";
        return ".bin";
    }

    private static String extractString(Map<String, Object> options, String key, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        Object v = options.get(key);
        return (v == null || v.toString().isBlank()) ? defaultValue : v.toString();
    }
}
