package com.nageoffer.ai.rag.eval.importer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.eval.config.EvalProperties;
import com.nageoffer.ai.rag.eval.dao.entity.EvalDatasetEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalItemEntity;
import com.nageoffer.ai.rag.eval.dao.mapper.EvalDatasetMapper;
import com.nageoffer.ai.rag.eval.dao.mapper.EvalItemMapper;
import com.nageoffer.ai.rag.ingestion.engine.enums.SourceType;
import com.nageoffer.ai.rag.ingestion.engine.fetcher.DocumentSource;
import com.nageoffer.ai.rag.ingestion.service.IngestionEngineService;
import com.nageoffer.ai.rag.ingestion.service.IngestionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * LiveRAG 基准导入器：从 HuggingFace（默认 hf-mirror 镜像）拉取 parquet → DuckDB 随机抽样
 * → 支持文档文本走入库引擎（taskId 即 chunk.metadata["doc_id"]）→ 写 sa_eval_item。
 *
 * <p>与评测闭环对齐：{@code expected_doc_ids} 存入库返回的 taskId，与检索结果
 * {@code chunk.metadata["doc_id"]} 同源，导入后即可直接触发 EvalRunner 算 Recall@k。</p>
 *
 * <p>幂等：数据集按名复用；条目按 item_key（LiveRAG-{Index}）判重，重复导入自动跳过；
 * 单文档入库失败只跳过该文档（其余文档仍入 expected_doc_ids），整题文档全失败才跳过该题。</p>
 *
 * <p>并发：文档入库走 4 线程小并发（embedding 是瓶颈，串行 50 题约 5-8 分钟）。
 * parquet 本地缓存（cacheDir），forceRefresh 可强制重拉。</p>
 */
@Slf4j
@Service
public class LiveRagImporter {

    /** 文档入库并发度（embedding API 是瓶颈，4 并发约 2 分钟跑完 50 题） */
    private static final int INGEST_CONCURRENCY = 4;

    private final EvalProperties evalProperties;
    private final IngestionEngineService engineService;
    private final EvalDatasetMapper datasetMapper;
    private final EvalItemMapper itemMapper;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public LiveRagImporter(EvalProperties evalProperties, IngestionEngineService engineService,
                           EvalDatasetMapper datasetMapper, EvalItemMapper itemMapper,
                           ObjectMapper objectMapper) {
        this.evalProperties = evalProperties;
        this.engineService = engineService;
        this.datasetMapper = datasetMapper;
        this.itemMapper = itemMapper;
        this.objectMapper = objectMapper;
        long timeoutSeconds = Math.max(evalProperties.getLiverag().getTimeoutSeconds(), 30L);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 导入入口：拉取 → 抽样 → 文档入库 → 评测条目入库。
     *
     * @param request 导入参数（sampleSize/forceRefresh/datasetName，均可空走配置默认）
     * @return 导入统计（数据集 id / 抽样数 / 导入条目 / 跳过 / 文档入库成功失败 / 耗时）
     */
    public LiveRagImportResult importSample(LiveRagImportRequest request) {
        EvalProperties.LiveRag liveragConfig = evalProperties.getLiverag();
        int sampleSize = request.sampleSize() != null ? request.sampleSize() : liveragConfig.getSampleSize();
        if (sampleSize <= 0) {
            throw new ClientException("sampleSize 必须大于 0");
        }
        boolean forceRefresh = Boolean.TRUE.equals(request.forceRefresh());
        String datasetName = StringUtils.hasText(request.datasetName()) ? request.datasetName() : "LiveRAG";

        long startTimeMillis = System.currentTimeMillis();
        Path parquetFile = downloadParquet(liveragConfig, forceRefresh);

        long datasetId = ensureDataset(datasetName);
        List<Row> sampleRows = readSampleRows(parquetFile, sampleSize);
        log.info("[LiveRAG] 数据集 {} 抽样 {} 题（共抽样 {} 行）", datasetName, sampleSize, sampleRows.size());
        if (sampleRows.isEmpty()) {
            throw new ClientException("parquet 无数据，无法导入");
        }

        AtomicInteger imported = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger docsIngested = new AtomicInteger();
        AtomicInteger docsFailed = new AtomicInteger();
        AtomicInteger itemsSkipped = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(INGEST_CONCURRENCY)) {
            List<Future<?>> futures = new ArrayList<>(sampleRows.size());
            for (Row row : sampleRows) {
                futures.add(executor.submit(() ->
                        importOneItem(datasetId, row, imported, skipped, docsIngested, docsFailed, itemsSkipped)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception ex) {
                    log.warn("[LiveRAG] 题处理异常: {}", ex.getMessage());
                    itemsSkipped.incrementAndGet();
                }
            }
        }

        // 回写数据集条目数（导入器直接 insert item，未走 EvalService.addItems 的计数累计）
        Long itemCount = itemMapper.selectCount(new LambdaQueryWrapper<EvalItemEntity>()
                .eq(EvalItemEntity::getDatasetId, datasetId));
        EvalDatasetEntity datasetUpdate = new EvalDatasetEntity();
        datasetUpdate.setId(datasetId);
        datasetUpdate.setItemCount(itemCount.intValue());
        datasetMapper.updateById(datasetUpdate);

        return new LiveRagImportResult(datasetId, sampleSize, imported.get(), skipped.get(),
                docsIngested.get(), docsFailed.get(), itemsSkipped.get(),
                System.currentTimeMillis() - startTimeMillis);
    }

    /**
     * 导入单题：支持文档逐个入库 → 全成功或部分成功则写 sa_eval_item（expected_doc_ids = 各 taskId）。
     * 已存在（itemKey 判重）则跳过。
     */
    private void importOneItem(Long datasetId, Row row, AtomicInteger imported, AtomicInteger skipped,
                               AtomicInteger docsIngested, AtomicInteger docsFailed, AtomicInteger itemsSkipped) {
        String itemKey = "LiveRAG-" + row.index();
        Long existingCount = itemMapper.selectCount(new LambdaQueryWrapper<EvalItemEntity>()
                .eq(EvalItemEntity::getDatasetId, datasetId)
                .eq(EvalItemEntity::getItemKey, itemKey));
        if (existingCount != null && existingCount > 0) {
            skipped.incrementAndGet();
            return;
        }

        EvalProperties.LiveRag liveragConfig = evalProperties.getLiverag();
        List<String> taskIds = new ArrayList<>();
        for (int docIndex = 0; docIndex < row.documents().size(); docIndex++) {
            String docContent = row.documents().get(docIndex);
            if (!StringUtils.hasText(docContent)) {
                continue;
            }
            String fileName = itemKey + "-doc" + (docIndex + 1) + ".md";
            DocumentSource source = DocumentSource.builder()
                    .type(SourceType.FILE)
                    .location(fileName)
                    .fileName(fileName)
                    .build();
            try {
                IngestionResult result = engineService.executeTask(liveragConfig.getPipeline(), source,
                        docContent.getBytes(StandardCharsets.UTF_8), "text/markdown", null, null);
                if (StringUtils.hasText(result.docId())) {
                    taskIds.add(result.docId());
                    docsIngested.incrementAndGet();
                } else {
                    docsFailed.incrementAndGet();
                }
            } catch (Exception ex) {
                log.warn("[LiveRAG] {} 文档 {} 入库失败: {}", itemKey, fileName, ex.getMessage());
                docsFailed.incrementAndGet();
            }
        }

        if (taskIds.isEmpty()) {
            log.warn("[LiveRAG] {} 全部支持文档入库失败，跳过该题", itemKey);
            itemsSkipped.incrementAndGet();
            return;
        }

        EvalItemEntity item = new EvalItemEntity();
        item.setDatasetId(datasetId);
        item.setItemKey(itemKey);
        item.setCategory(row.category());
        item.setQuestion(row.question());
        item.setExpectedDocIds(toJson(taskIds));
        item.setExpectedAnswer(row.answer());
        item.setSource("liverag");
        item.setEnabled(1);
        item.setCreateTime(LocalDateTime.now());
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.insert(item);
        imported.incrementAndGet();
    }

    // ---------- parquet 获取与读取 ----------

    /**
     * 下载 parquet 到缓存目录（已有且非 forceRefresh 则复用）。
     *
     * @param liveragConfig LiveRAG 配置（baseUrl/repo/parquetFile/cacheDir）
     * @param forceRefresh  true 强制重拉（忽略缓存）
     * @return parquet 本地路径
     */
    private Path downloadParquet(EvalProperties.LiveRag liveragConfig, boolean forceRefresh) {
        try {
            Path cacheDir = Paths.get(liveragConfig.getCacheDir()).toAbsolutePath();
            Files.createDirectories(cacheDir);
            Path parquetFile = cacheDir.resolve(liveragConfig.getParquetFile());
            if (!forceRefresh && Files.exists(parquetFile) && Files.size(parquetFile) > 0) {
                log.info("[LiveRAG] 使用缓存 parquet: {}", parquetFile);
                return parquetFile;
            }
            String downloadUrl = liveragConfig.getBaseUrl() + "/datasets/" + liveragConfig.getRepo()
                    + "/resolve/main/" + liveragConfig.getParquetFile();
            log.info("[LiveRAG] 下载 parquet: {}", downloadUrl);
            Request httpRequest = new Request.Builder().url(downloadUrl).build();
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    throw new ClientException("parquet 下载失败: HTTP " + response.code());
                }
                byte[] fileBytes = response.body() == null ? new byte[0] : response.body().bytes();
                if (fileBytes.length == 0) {
                    throw new ClientException("parquet 下载为空");
                }
                Files.write(parquetFile, fileBytes);
                log.info("[LiveRAG] parquet 已保存: {} ({} bytes)", parquetFile, fileBytes.length);
                return parquetFile;
            }
        } catch (IOException ex) {
            throw new ClientException("parquet 下载失败: " + ex.getMessage());
        }
    }

    /**
     * DuckDB 读 parquet 并随机抽样。
     * Supporting_Documents / DataMorgana_Config 是 struct[]/struct 列，to_json 转标准 JSON 供 Jackson 解析
     * （CAST AS VARCHAR 输出的是 Python repr 风格单引号，非法 JSON）。
     *
     * @param parquetFile parquet 本地路径
     * @param limit       抽样条数
     * @return 抽样行列表
     */
    private List<Row> readSampleRows(Path parquetFile, int limit) {
        String filePath = parquetFile.toAbsolutePath().toString().replace('\\', '/');
        String sql = "SELECT \"Index\", \"Question\", \"Answer\", "
                + "to_json(\"Supporting_Documents\") AS docs, "
                + "to_json(\"DataMorgana_Config\") AS config "
                + "FROM read_parquet('" + filePath + "') ORDER BY random() LIMIT " + limit;
        List<Row> sampleRows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    while (resultSet.next()) {
                        sampleRows.add(parseRow(resultSet));
                    }
                }
            }
        } catch (Exception ex) {
            throw new ClientException("parquet 读取失败: " + ex.getMessage());
        }
        return sampleRows;
    }

    /** 把 ResultSet 当前行解析成 Row（Question/Answer/Supporting_Documents/DataMorgana_Config）。 */
    private Row parseRow(ResultSet resultSet) throws Exception {
        long index = resultSet.getLong("Index");
        String question = resultSet.getString("Question");
        String answer = resultSet.getString("Answer");
        List<String> supportingDocs = parseSupportingDocs(resultSet.getString("docs"));
        String category = parseCategory(resultSet.getString("config"));
        return new Row(index, question, answer, supportingDocs, category);
    }

    /**
     * Supporting_Documents → content 列表。
     * doc_id 是 LiveRAG 侧 urn，系统用入库返回的 taskId，故只取 content。
     */
    static List<String> parseSupportingDocs(String json) {
        List<String> contents = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return contents;
        }
        try {
            JsonNode docsArray = new ObjectMapper().readTree(json);
            if (!docsArray.isArray()) {
                return contents;
            }
            for (JsonNode docNode : docsArray) {
                JsonNode content = docNode.get("content");
                if (content != null && content.isTextual()) {
                    contents.add(content.asText());
                }
            }
        } catch (Exception ex) {
            log.warn("[LiveRAG] Supporting_Documents 解析失败: {}", ex.getMessage());
        }
        return contents;
    }

    /** DataMorgana_Config → category（answer-type-categorization，如 factoid；缺省 qa）。 */
    static String parseCategory(String json) {
        if (json == null || json.isBlank()) {
            return "qa";
        }
        try {
            JsonNode configNode = new ObjectMapper().readTree(json);
            String type = configNode.path("answer-type-categorization").asText("");
            return StringUtils.hasText(type) ? type : "qa";
        } catch (Exception ex) {
            return "qa";
        }
    }

    /** 按名查/建数据集，返回 datasetId（同名复用）。 */
    private long ensureDataset(String name) {
        EvalDatasetEntity existingDataset = datasetMapper.selectOne(new LambdaQueryWrapper<EvalDatasetEntity>()
                .eq(EvalDatasetEntity::getName, name));
        if (existingDataset != null) {
            return existingDataset.getId();
        }
        EvalDatasetEntity dataset = new EvalDatasetEntity();
        dataset.setName(name);
        dataset.setDescription("LiveRAG 基准（HuggingFace 实时 RAG 问答集，自动导入）");
        dataset.setItemCount(0);
        datasetMapper.insert(dataset);
        return dataset.getId();
    }

    /** 对象 → JSON 字符串；序列化失败返回 null（不抛异常，避免阻断导入）。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    /** parquet 行（解析后）。 */
    private record Row(long index, String question, String answer, List<String> documents, String category) {
    }
}
