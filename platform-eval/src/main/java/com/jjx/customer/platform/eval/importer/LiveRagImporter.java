package com.jjx.customer.platform.eval.importer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.common.exception.ClientException;
import com.jjx.customer.platform.eval.config.EvalProperties;
import com.jjx.customer.platform.eval.dao.entity.EvalDatasetEntity;
import com.jjx.customer.platform.eval.dao.entity.EvalItemEntity;
import com.jjx.customer.platform.eval.dao.mapper.EvalDatasetMapper;
import com.jjx.customer.platform.eval.dao.mapper.EvalItemMapper;
import com.jjx.customer.platform.ingestion.collection.service.DocCollectionService;
import com.jjx.customer.platform.ingestion.engine.enums.SourceType;
import com.jjx.customer.platform.ingestion.engine.fetcher.DocumentSource;
import com.jjx.customer.platform.ingestion.service.IngestionEngineService;
import com.jjx.customer.platform.ingestion.service.IngestionResult;
import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** parquet 字段解析共享 mapper（static 方法用；此前每行每字段 new 一个 ObjectMapper） */
    private static final ObjectMapper FIELD_MAPPER = new ObjectMapper();

    private final EvalProperties evalProperties;
    private final IngestionEngineService engineService;
    private final EvalDatasetMapper datasetMapper;
    private final EvalItemMapper itemMapper;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final DocCollectionService docCollectionService;

    public LiveRagImporter(EvalProperties evalProperties, IngestionEngineService engineService,
                           EvalDatasetMapper datasetMapper, EvalItemMapper itemMapper,
                           ObjectMapper objectMapper, DocCollectionService docCollectionService,
                           @org.springframework.beans.factory.annotation.Qualifier("syncHttpClient") OkHttpClient sharedClient) {
        this.evalProperties = evalProperties;
        this.engineService = engineService;
        this.datasetMapper = datasetMapper;
        this.itemMapper = itemMapper;
        this.objectMapper = objectMapper;
        this.docCollectionService = docCollectionService;
        // 从共享 client 派生（共享连接池/dispatcher），仅覆盖镜像下载的长超时——
        // 此前自建独立 client，多一份连接池与线程
        long timeoutSeconds = Math.max(evalProperties.getLiverag().getTimeoutSeconds(), 30L);
        this.httpClient = sharedClient.newBuilder()
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
        // 语料归集：入库时即带 collectionId（向量 metadata 与 sa_document 同步写对），
        // 未指定集合则按数据集名查/建同名文档集合，与评测数据集一一对应；
        // 评测跑批 resolveSafeCollection 按期望文档归属定位到该集合 → 检索范围隔离自动生效
        long collectionId;
        String collectionName;
        if (request.collectionId() != null) {
            collectionName = docCollectionService.requireCollection(request.collectionId());
            collectionId = request.collectionId();
        } else {
            collectionName = datasetName;
            collectionId = docCollectionService.ensureCollection(datasetName,
                    "LiveRAG 基准语料（评测数据集「" + datasetName + "」导入器自动归集）");
        }
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

        // 一次 IN 查询预取已有 itemKey（此前每题一次 selectCount，50 题 = 50 次 DB 往返）
        java.util.Set<String> sampleKeys = sampleRows.stream()
                .map(r -> "LiveRAG-" + r.index()).collect(java.util.stream.Collectors.toSet());
        // 并发集合：importOneItem 的 4 个工作线程会并发 add 占位（普通 HashSet 并发写会坏结构）
        java.util.Set<String> existingKeys = itemMapper.selectList(new LambdaQueryWrapper<EvalItemEntity>()
                        .eq(EvalItemEntity::getDatasetId, datasetId)
                        .in(!sampleKeys.isEmpty(), EvalItemEntity::getItemKey, sampleKeys)
                        .select(EvalItemEntity::getItemKey))
                .stream().map(EvalItemEntity::getItemKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.concurrent.ConcurrentHashMap::newKeySet));

        // 跨题共享的幂等表：源 urn doc_id → 已入库的 taskId。
        // 同一篇文章会被多道题引用（实测 1032 条引用只对应 970 篇唯一文档），
        // 不共享这张表就会把同一内容入库成多份、各带一个 doc_id，评测的 expected_doc_ids
        // 指哪份全看运气，指错即 0 分。
        Map<String, String> ingestedByUrn = new ConcurrentHashMap<>();

        // 文档命名：按 urn 归拢全部引用方（题号d序号），列出而非只挂一个——
        // "一篇文章被多题引用"是检索基准的常态，名字如实反映它，别冒充某一道题的专属文档。
        Map<String, List<String>> refsByUrn = new LinkedHashMap<>();
        for (Row row : sampleRows) {
            for (int i = 0; i < row.documents().size(); i++) {
                String urn = row.documents().get(i).docId();
                if (StringUtils.hasText(urn)) {
                    refsByUrn.computeIfAbsent(urn, k -> new ArrayList<>()).add(row.index() + "d" + (i + 1));
                }
            }
        }
        Map<String, String> docNameByUrn = new LinkedHashMap<>();
        refsByUrn.forEach((urn, refs) -> {
            refs.sort(Comparator.comparingLong(r -> Long.parseLong(r.substring(0, r.indexOf('d')))));
            docNameByUrn.put(urn, "LiveRAG-" + urnShort(urn) + "-" + String.join("-", refs) + ".md");
        });
        ImportPlan plan = new ImportPlan(docNameByUrn, readIrtQuartiles(parquetFile));
        log.info("[LiveRAG] 唯一文档 {} 篇；其中被多题共享 {} 篇；irt_diff 四分位切点 {}",
                docNameByUrn.size(),
                docNameByUrn.size() - refsByUrn.values().stream().filter(v -> v.size() == 1).count(),
                plan.irtQuartiles().length == 3
                        ? String.format("%.3f / %.3f / %.3f",
                                plan.irtQuartiles()[0], plan.irtQuartiles()[1], plan.irtQuartiles()[2])
                        : "(不可用)");

        try (ExecutorService executor = Executors.newFixedThreadPool(INGEST_CONCURRENCY)) {
            List<Future<?>> futures = new ArrayList<>(sampleRows.size());
            for (Row row : sampleRows) {
                futures.add(executor.submit(() ->
                        importOneItem(datasetId, collectionId, row, existingKeys, ingestedByUrn, plan,
                                imported, skipped, docsIngested, docsFailed, itemsSkipped)));
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

        log.info("[LiveRAG] 文档去重：唯一源文档 {} 篇，实际入库 {} 篇、失败 {} 篇（差额即多题引用的复用）",
                ingestedByUrn.size(), docsIngested.get(), docsFailed.get());

        // 回写数据集条目数（导入器直接 insert item，未走 EvalService.addItems 的计数累计）
        Long itemCount = itemMapper.selectCount(new LambdaQueryWrapper<EvalItemEntity>()
                .eq(EvalItemEntity::getDatasetId, datasetId));
        EvalDatasetEntity datasetUpdate = new EvalDatasetEntity();
        datasetUpdate.setId(datasetId);
        datasetUpdate.setItemCount(itemCount.intValue());
        datasetMapper.updateById(datasetUpdate);

        return new LiveRagImportResult(datasetId, sampleSize, imported.get(), skipped.get(),
                docsIngested.get(), docsFailed.get(), itemsSkipped.get(), collectionId, collectionName,
                System.currentTimeMillis() - startTimeMillis);
    }

    /**
     * 导入单题：支持文档逐个入库（带 collectionId 归集）→ 全成功或部分成功则写 sa_eval_item（expected_doc_ids = 各 taskId）。
     * 已存在（itemKey 判重，预取的 existingKeys）则跳过。
     */
    private void importOneItem(Long datasetId, long collectionId, Row row, java.util.Set<String> existingKeys,
                               Map<String, String> ingestedByUrn, ImportPlan plan,
                               AtomicInteger imported, AtomicInteger skipped,
                               AtomicInteger docsIngested, AtomicInteger docsFailed, AtomicInteger itemsSkipped) {
        String itemKey = "LiveRAG-" + row.index();
        if (!existingKeys.add(itemKey)) {
            // 已存在（或本批并发占位）：跳过重复导入
            skipped.incrementAndGet();
            return;
        }

        EvalProperties.LiveRag liveragConfig = evalProperties.getLiverag();
        List<String> taskIds = new ArrayList<>();
        for (int docIndex = 0; docIndex < row.documents().size(); docIndex++) {
            SupportingDoc doc = row.documents().get(docIndex);
            if (!StringUtils.hasText(doc.content())) {
                continue;
            }
            // 名字按 urn 取：同一篇源文档只入一次，名字对**全部**引用方都一致（不会再出现
            // "题 867 的文档叫 687-doc2"这种名不副实）。无 urn 的条目退回「题号-序号」。
            String fallbackName = itemKey + "-doc" + (docIndex + 1) + ".md";
            String fileName = StringUtils.hasText(doc.docId())
                    ? plan.docNameByUrn().getOrDefault(doc.docId(), fallbackName) : fallbackName;
            String taskId = ingestDocument(doc, fileName, ingestedByUrn, liveragConfig, collectionId,
                    docsIngested, docsFailed, itemKey);
            if (StringUtils.hasText(taskId)) {
                taskIds.add(taskId);
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
        // 难度：原始 IRT 标注 + 由 irt_diff 四分位定的档（irt_diff 越大越难）
        item.setIrtDiff(row.irtDiff() == null ? null : BigDecimal.valueOf(row.irtDiff()));
        item.setIrtDisc(row.irtDisc() == null ? null : BigDecimal.valueOf(row.irtDisc()));
        item.setAcs(row.acs() == null ? null : BigDecimal.valueOf(row.acs()));
        item.setAcsStd(row.acsStd() == null ? null : BigDecimal.valueOf(row.acsStd()));
        item.setDifficulty(difficultyOf(row.irtDiff(), plan.irtQuartiles()));
        item.setSource("liverag");
        item.setEnabled(1);
        item.setCreateTime(LocalDateTime.now());
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.insert(item);
        imported.incrementAndGet();
    }

    /**
     * 入库一条支撑文档，**按源 urn doc_id 幂等**：同一篇源文档只入库一次，多题共享同一个 taskId。
     *
     * <p>并发安全用 {@code computeIfAbsent}——同一 urn 被多线程同时命中时只有一个真正入库，
     * 其余线程阻塞等它的结果（不同 urn 互不阻塞）；映射函数返回 null 时不会写缓存，失败可重试。</p>
     *
     * <p>没有 urn 的条目退化为逐条入库：幂等无从谈起，但也不该因此把文档丢掉。</p>
     *
     * @return 入库返回的 taskId；失败返回 null
     */
    private String ingestDocument(SupportingDoc doc, String fileName, Map<String, String> ingestedByUrn,
                                  EvalProperties.LiveRag liveragConfig, long collectionId,
                                  AtomicInteger docsIngested, AtomicInteger docsFailed, String itemKey) {
        String urn = doc.docId();
        if (!StringUtils.hasText(urn)) {
            return ingestOnce(doc.content(), fileName, null, liveragConfig, collectionId,
                    docsIngested, docsFailed, itemKey);
        }
        // doc_id 直接用源 urn 的 uuid 段：文档身份 = 内容身份。这样引擎自带的幂等锚点
        // （同 docId 重跑先清残留向量）也生效，expected_doc_ids 存的就是稳定可追溯的源身份。
        String sourceDocId = urnUuid(urn);
        AtomicBoolean freshlyIngested = new AtomicBoolean(false);
        String taskId = ingestedByUrn.computeIfAbsent(urn, key -> {
            String id = ingestOnce(doc.content(), fileName, sourceDocId, liveragConfig, collectionId,
                    docsIngested, docsFailed, itemKey);
            freshlyIngested.set(id != null);
            return id;
        });
        if (!freshlyIngested.get() && StringUtils.hasText(taskId)) {
            log.info("[LiveRAG] {} 文档 {} 与已有 urn 相同，复用已入库文档（不再重复入库）: {}",
                    itemKey, fileName, urn);
        }
        return taskId;
    }

    /**
     * 单次入库：走入库引擎，返回 taskId。计数与失败日志在此收口。
     *
     * @param sourceDocId 调用方指定的 doc_id（这里是源 urn 的 uuid 段）；null = 由引擎生成
     */
    private String ingestOnce(String docContent, String fileName, String sourceDocId,
                              EvalProperties.LiveRag liveragConfig,
                              long collectionId, AtomicInteger docsIngested, AtomicInteger docsFailed,
                              String itemKey) {
        DocumentSource source = DocumentSource.builder()
                .type(SourceType.FILE)
                .location(fileName)
                .fileName(fileName)
                .build();
        try {
            IngestionResult result = engineService.executeTask(liveragConfig.getPipeline(), source,
                    docContent.getBytes(StandardCharsets.UTF_8), "text/markdown", collectionId, null, sourceDocId);
            if (StringUtils.hasText(result.docId())) {
                docsIngested.incrementAndGet();
                return result.docId();
            }
            docsFailed.incrementAndGet();
            return null;
        } catch (Exception ex) {
            log.warn("[LiveRAG] {} 文档 {} 入库失败: {}", itemKey, fileName, ex.getMessage());
            docsFailed.incrementAndGet();
            return null;
        }
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
                + "to_json(\"DataMorgana_Config\") AS config, "
                + "\"ACS [-2 : 1]\" AS acs, \"ACS_Std\" AS acs_std, "
                + "\"IRT-diff [-6 : 6]\" AS irt_diff, \"IRT-disc [-0.6 : 1.4]\" AS irt_disc "
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

    /**
     * 全量 irt_diff 的四分位切点（用于难度定档）。
     *
     * <p>取<b>全量</b>而非抽样：抽样的切点会随 sampleSize 漂移，同一道题这次算 E、下次算 M，
     * 按难度切片的统计就没法跨 run 比。官方也是在全量 895 题上按四分位分档（每档约 224 题）。</p>
     *
     * @return {P25, P50, P75}；读取失败返回空数组（调用方回退为不分档）
     */
    private double[] readIrtQuartiles(Path parquetFile) {
        String filePath = parquetFile.toAbsolutePath().toString().replace('\\', '/');
        String sql = "SELECT quantile_cont(\"IRT-diff [-6 : 6]\", 0.25), "
                + "quantile_cont(\"IRT-diff [-6 : 6]\", 0.50), "
                + "quantile_cont(\"IRT-diff [-6 : 6]\", 0.75) "
                + "FROM read_parquet('" + filePath + "') WHERE \"IRT-diff [-6 : 6]\" IS NOT NULL";
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                return new double[] {resultSet.getDouble(1), resultSet.getDouble(2), resultSet.getDouble(3)};
            }
        } catch (Exception ex) {
            log.warn("[LiveRAG] irt_diff 四分位读取失败，本次导入不分难度档: {}", ex.getMessage());
        }
        return new double[0];
    }

    /**
     * 难度定档：按 irt_diff 的四分位切 E / M / D / HD。
     *
     * <p><b>irt_diff 越大越难</b>（IRT 的 b 参数语义）。官方资料对此有矛盾说法，本项目用数据自证：
     * 按 irt_diff 四分位的平均 ACS 为 1.428 / 1.039 / 0.713 / 0.202，皮尔逊相关 <b>-0.970</b>
     * —— irt_diff 越高，各参赛系统的正确率越低。</p>
     *
     * @param irtDiff    IRT 难度参数
     * @param quartiles  {P25, P50, P75}；为空表示不分档
     * @return E / M / D / HD；无法定档返回 null
     */
    static String difficultyOf(Double irtDiff, double[] quartiles) {
        if (irtDiff == null || quartiles == null || quartiles.length != 3) {
            return null;
        }
        if (irtDiff < quartiles[0]) {
            return "E";
        }
        return irtDiff < quartiles[1] ? "M" : (irtDiff < quartiles[2] ? "D" : "HD");
    }

    /** 把 ResultSet 当前行解析成 Row（Question/Answer/Supporting_Documents/DataMorgana_Config + IRT 标注）。 */
    private Row parseRow(ResultSet resultSet) throws Exception {
        long index = resultSet.getLong("Index");
        String question = resultSet.getString("Question");
        String answer = resultSet.getString("Answer");
        List<SupportingDoc> supportingDocs = parseSupportingDocs(resultSet.getString("docs"));
        String category = parseCategory(resultSet.getString("config"));
        return new Row(index, question, answer, supportingDocs, category,
                asDouble(resultSet, "irt_diff"), asDouble(resultSet, "irt_disc"),
                asDouble(resultSet, "acs"), asDouble(resultSet, "acs_std"));
    }

    /**
     * 读可空的数值列：按 {@link Number} 判定而不是直接 cast。
     *
     * <p>parquet 里这四列是 {@code double}，DuckDB JDBC 正常返回 {@link Double}；
     * 但若上游类型变了（float/decimal），硬 cast 会 ClassCastException 让整批导入挂掉。
     * 这里只取 doubleValue，类型变了也不会炸。</p>
     */
    private static Double asDouble(ResultSet resultSet, String column) throws Exception {
        Object value = resultSet.getObject(column);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /**
     * Supporting_Documents → 支撑文档列表，**保留源 urn doc_id**。
     *
     * <p><b>urn doc_id 必须保留</b>：它是源侧的<b>稳定文档身份</b>。实测 1032 条引用只对应
     * 970 篇唯一文档——同一篇文章会被多道题引用。丢掉它、改用"题号-序号"另起身份，同一内容
     * 就会以不同 doc_id 重复入库；检索按内容召回时命中哪份是随机的，而评测按 doc_id 打分，
     * 指错即 0 分（实测 62 个冗余副本 / 38 条"召回了内容却记 0 分"的用例）。</p>
     */
    static List<SupportingDoc> parseSupportingDocs(String json) {
        List<SupportingDoc> docs = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return docs;
        }
        try {
            JsonNode docsArray = FIELD_MAPPER.readTree(json);
            if (!docsArray.isArray()) {
                return docs;
            }
            for (JsonNode docNode : docsArray) {
                JsonNode content = docNode.get("content");
                if (content != null && content.isTextual()) {
                    JsonNode docId = docNode.get("doc_id");
                    docs.add(new SupportingDoc(
                            docId != null && docId.isTextual() ? docId.asText() : null,
                            content.asText()));
                }
            }
        } catch (Exception ex) {
            log.warn("[LiveRAG] Supporting_Documents 解析失败: {}", ex.getMessage());
        }
        return docs;
    }

    /**
     * 一条支撑文档。
     *
     * @param docId   源 urn doc_id（稳定身份，用于跨题幂等入库；缺省 null 时退化为逐条入库）
     * @param content 正文
     */
    record SupportingDoc(String docId, String content) {
    }

    /**
     * 本次导入的全量约定（读完全部抽样行后才定得下来，故单独传）。
     *
     * @param docNameByUrn urn → 文档名（名单列出引用它的全部 题号d序号）
     * @param irtQuartiles 全量 irt_diff 的 {P25,P50,P75}，用于难度定档；空数组表示不分档
     */
    private record ImportPlan(Map<String, String> docNameByUrn, double[] irtQuartiles) {
    }

    /** {@code <urn:uuid:9de87fbb-42b0-…>} → {@code 9de87fbb}（文档名里用的短码）。 */
    static String urnShort(String urn) {
        String uuid = urnUuid(urn);
        int dash = uuid.indexOf('-');
        return dash > 0 ? uuid.substring(0, dash) : uuid;
    }

    /** {@code <urn:uuid:9de87fbb-42b0-…>} → {@code 9de87fbb-42b0-…}（作为 doc_id 落库，与源身份一一对应）。 */
    static String urnUuid(String urn) {
        if (urn == null) {
            return "";
        }
        return urn.replace("<urn:uuid:", "").replace(">", "").trim();
    }

    /** DataMorgana_Config → category（answer-type-categorization，如 factoid；缺省 qa）。 */
    static String parseCategory(String json) {
        if (json == null || json.isBlank()) {
            return "qa";
        }
        try {
            JsonNode configNode = FIELD_MAPPER.readTree(json);
            String type = configNode.path("answer-type-categorization").asText("");
            return StringUtils.hasText(type) ? type : "qa";
        } catch (Exception ex) {
            return "qa";
        }
    }

    /** 按名查/建数据集，返回 datasetId（同名复用）。并发首建的 check-then-insert 冲突按唯一约束兜底重查。 */
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
        try {
            datasetMapper.insert(dataset);
            return dataset.getId();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发导入同名数据集：另一请求已建，重查复用
            return datasetMapper.selectOne(new LambdaQueryWrapper<EvalDatasetEntity>()
                            .eq(EvalDatasetEntity::getName, name))
                    .getId();
        }
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
    private record Row(long index, String question, String answer, List<SupportingDoc> documents,
                       String category,
                       Double irtDiff, Double irtDisc, Double acs, Double acsStd) {
    }
}
