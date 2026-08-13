package com.nageoffer.ai.rag.eval.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 评测配置，绑定 application.yaml 的 rag.eval.*。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.eval")
public class EvalProperties {

    /** Recall / Precision / nDCG 计算的 k 值列表（如 [5,10] → 产出 recall_at_5、precision_at_5、recall_at_10…） */
    private List<Integer> ks = List.of(5, 10);

    /** 跑批并发数（虚拟线程 + Semaphore 限流；单实例足够，多实例部署再换 Redisson 信号量） */
    private int concurrency = 8;

    /** LiveRAG 基准数据导入配置（rag.eval.liverag.*） */
    private LiveRag liverag = new LiveRag();

    /**
     * LiveRAG（HuggingFace 实时 RAG 基准，895 题带支持文档全文与标准答案）。
     * 国内默认 hf-mirror.com 镜像；网络可达官方时可切 https://huggingface.co。
     */
    @Data
    public static class LiveRag {

        /** HuggingFace 镜像地址（resolve 下载路径由它 + repo + parquet-file 拼出） */
        private String baseUrl = "https://hf-mirror.com";

        /** 数据集仓库 */
        private String repo = "LiveRAG/Benchmark";

        /** 数据集文件（快照版本名，升级基准时更新） */
        private String parquetFile = "LiveRAG_banchmark_20250910.parquet";

        /** 默认抽样题数（全量 895 题入库慢且 embedding 花费高，推荐先 50 试跑） */
        private int sampleSize = 50;

        /** 文档入库用 pipeline（DB 里 sa_ingestion_pipeline 的 id） */
        private String pipeline = "default";

        /** parquet 本地缓存目录（已下载则跳过，避免每次导入都拉取） */
        private String cacheDir = "data/liverag";

        /** 下载连接/读取超时（秒），镜像带宽波动大，默认 120s */
        private long timeoutSeconds = 120;
    }
}
