package com.jjx.customer.platform.eval.sink;

import com.jjx.ai.llmobservability.backends.langfuse.LangfuseDatasetClient;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseDatasetItem;
import com.jjx.customer.platform.observe.langfuse.LangfuseDatasetAdminClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地评测集 ↔ Langfuse dataset 的同步器：把 sa_eval_item 惰性同步到 Langfuse，
 * 维护 localItemId → Langfuse datasetItemId 映射。
 * <p>匹配策略：input（问题文本）精确匹配既有 Langfuse 条目；miss 则创建
 * （expectedOutput=标准答案，metadata 带 localItemId/标准召回 docIds+文件名）。
 * 映射进程内缓存（Langfuse datasetItemId 稳定，run 间复用）。
 * <p>注意：既有条目索引用 jar 的 listDatasetItems 单页拉取（pageSize=500），
 * 数据集超过 500 条时建议预先人工同步或分批——当前黄金集规模远小于此。
 */
@Slf4j
@Component
public class LangfuseDatasetSyncer {

    private static final int INDEX_PAGE_SIZE = 500;

    private final LangfuseDatasetAdminClient adminClient;
    private final ObjectProvider<LangfuseDatasetClient> datasetClientProvider;

    /** datasetName →（question → langfuseDatasetItemId）既有条目索引（每数据集加载一次） */
    private final Map<String, Map<String, String>> questionIndex = new ConcurrentHashMap<>();
    /** localItemId → langfuseDatasetItemId（Langfuse id 稳定，进程内持久复用） */
    private final Map<Long, String> localItemMap = new ConcurrentHashMap<>();
    private final Set<String> ensuredDatasets = ConcurrentHashMap.newKeySet();

    public LangfuseDatasetSyncer(LangfuseDatasetAdminClient adminClient,
                                 ObjectProvider<LangfuseDatasetClient> datasetClientProvider) {
        this.adminClient = adminClient;
        this.datasetClientProvider = datasetClientProvider;
    }

    /** 是否具备同步条件（凭据 + list client 就绪）。 */
    public boolean available() {
        return adminClient.isAvailable() && datasetClientProvider.getIfAvailable() != null;
    }

    /** 预热：确保 dataset 存在并加载既有条目索引（run 启动时异步调用，失败静默）。 */
    public void ensureLoaded(String datasetName) {
        try {
            ensureDataset(datasetName);
            index(datasetName);
        } catch (Exception e) {
            log.warn("[LangfuseSync] dataset 预热失败（首次条目同步时重试）: {}: {}", datasetName, e.getMessage());
        }
    }

    /**
     * 解析（必要时创建）本地 item 对应的 Langfuse datasetItemId。
     *
     * @return Langfuse datasetItemId；同步失败返回 null（调用方跳过该条的 run 关联）
     */
    public String resolve(String datasetName, Long localItemId, String question, String expectedAnswer,
                          List<String> expectedDocIds, List<String> expectedDocNames) {
        String cached = localItemMap.get(localItemId);
        if (cached != null) {
            return cached;
        }
        try {
            ensureDataset(datasetName);
            Map<String, String> index = index(datasetName);
            String existing = index.get(question);
            if (existing != null) {
                localItemMap.put(localItemId, existing);
                return existing;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("localItemId", localItemId);
            metadata.put("expectedDocIds", expectedDocIds);
            metadata.put("expectedDocNames", expectedDocNames);
            String created = adminClient.createDatasetItem(datasetName, question, expectedAnswer, metadata);
            if (created == null || created.isBlank()) {
                log.warn("[LangfuseSync] 创建 dataset item 未返回 id: dataset={}, item={}", datasetName, localItemId);
                return null;
            }
            index.put(question, created);
            localItemMap.put(localItemId, created);
            return created;
        } catch (Exception e) {
            log.warn("[LangfuseSync] dataset item 同步失败（跳过该条 run 关联）: dataset={}, item={}: {}",
                    datasetName, localItemId, e.getMessage());
            return null;
        }
    }

    private void ensureDataset(String datasetName) {
        if (ensuredDatasets.add(datasetName)) {
            adminClient.ensureDataset(datasetName);
            log.info("[LangfuseSync] dataset 已就绪: {}", datasetName);
        }
    }

    private Map<String, String> index(String datasetName) {
        return questionIndex.computeIfAbsent(datasetName, this::loadIndex);
    }

    private Map<String, String> loadIndex(String datasetName) {
        Map<String, String> map = new ConcurrentHashMap<>();
        try {
            List<LangfuseDatasetItem> items = datasetClientProvider.getIfAvailable()
                    .listDatasetItems(datasetName, INDEX_PAGE_SIZE);
            for (LangfuseDatasetItem it : items) {
                if (it.input() != null) {
                    map.put(String.valueOf(it.input()), it.id());
                }
            }
            log.info("[LangfuseSync] dataset={} 既有条目索引: {} 条", datasetName, map.size());
        } catch (Exception e) {
            log.warn("[LangfuseSync] 拉取 dataset 条目索引失败（按空索引处理）: {}: {}", datasetName, e.getMessage());
        }
        return map;
    }
}
