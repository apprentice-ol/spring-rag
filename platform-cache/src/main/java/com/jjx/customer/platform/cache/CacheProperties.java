package com.jjx.customer.platform.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 缓存层配置（prefix {@code rag.cache}）：Redis 持久化缓存（意图/检索/日志/答案/embedding 五层）。
 * <p>总开关 {@code enabled} 一键全关；每层独立 enabled + ttl。
 * 失效策略：文档版本号（docver）联动 + TTL 兜底，Redis 不可用时全链路优雅降级为 miss。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.cache")
public class CacheProperties {

    /** 总开关：false 时所有缓存层直通（装饰器全部旁路） */
    private boolean enabled = true;

    /** 意图分类缓存（key=归一化问题；只缓存 confidence>=0.7 的成功结果） */
    private Layer intent = new Layer(Duration.ofMinutes(30));

    /** 检索结果缓存（key=SearchContext 全参数 + docver；空结果不缓存） */
    private Layer retrieval = new Layer(Duration.ofMinutes(10), 2);

    /** 日志工具缓存（trace_id 精查 24h 历史不可变；时间窗查询用此 ttl） */
    private Layer logs = new Layer(Duration.ofSeconds(60));

    /** 精确答案缓存（key=问题+范式+docver；仅 exact 匹配，语义缓存不在本期） */
    private Layer answer = new Layer(Duration.ofHours(6), 2);

    /** 查询 embedding 缓存（key=modelTag+query；eval 参数扫描重复 embed 的大头） */
    private Layer embedding = new Layer(Duration.ofDays(7));

    /** trace_id 精查的专用 TTL（历史日志不可变，可长存） */
    private Duration logsTraceTtl = Duration.ofHours(24);

    /** embedding 模型标识（进 key：换 embedding 模型时自然失效） */
    private String embeddingModelTag = "default";

    // ===== 频率策略（Sentinel-lite：窗口计数 + 准入 + 热度 TTL）=====

    /** 频率统计的滚动窗口（"最近 freq-window 内出现次数"）；0/负 = 关闭频率准入与热度延长（=每次 miss 都写） */
    private Duration freqWindow = Duration.ofMinutes(15);

    /**
     * 频率统计的进程内 key 容量上限：环形窗口 map 超限时先清理窗口和为 0 的冷 key（60s 节流），
     * 仍满则新 key 按首次出现处理（不注册，fail-open）。单 key 16 槽 ≈ 130B，默认 1 万 ≈ 1.3MB。
     */
    private int freqMaxKeys = 10_000;

    /** 热度一档：窗口计数 >= 该值，命中时 TTL ×hot-multiplier */
    private int hotThreshold = 5;

    /** 热度一档倍数 */
    private int hotMultiplier = 4;

    /** 热度二档（顶格）：窗口计数 >= 该值，TTL ×max-ttl-multiplier */
    private int hotterThreshold = 20;

    /** 热度二档倍数（顶格） */
    private int maxTtlMultiplier = 10;

    /** Redis 断路器（熔断联动动态限流）：连续失败跳 OPEN 冷却，半开探测恢复 */
    private Circuit circuit = new Circuit();

    /** 语义答案缓存（pgvector 相似问题命中；失效靠 docver + TTL/容量清理，无反馈机制） */
    private Semantic semantic = new Semantic();

    @Data
    public static class Circuit {
        /** 连续失败多少次跳 OPEN */
        private int failureThreshold = 5;
        /** OPEN 冷却时长（期满转半开放探测） */
        private Duration cooldown = Duration.ofSeconds(30);
    }

    @Data
    public static class Semantic {
        /** 语义缓存开关（相似≠同义的误命中风险由 threshold 控制，出问题一键关） */
        private boolean enabled = true;
        /** 相似度阈值（cosine，1-距离；收得很紧防"相似≠同义"误命中） */
        private double threshold = 0.95;
        /** 行 TTL（清理任务删除依据） */
        private Duration ttl = Duration.ofDays(7);
        /** 容量上限（超出按 update_time 裁剪最旧） */
        private int maxRows = 1000;
    }

    @Data
    public static class Layer {
        /** 本层开关 */
        private boolean enabled = true;
        /** 本层 TTL */
        private Duration ttl;
        /**
         * 频率准入阈值：窗口内出现次数 >= 该值才写缓存（防长尾一次性查询污染）。
         * 1 = 无准入（每次 miss 都写，即频率策略关闭前的行为）。当前仅 retrieval/answer 层接线。
         */
        private int admissionThreshold = 1;

        public Layer() {
        }

        public Layer(Duration ttl) {
            this.ttl = ttl;
        }

        public Layer(Duration ttl, int admissionThreshold) {
            this.ttl = ttl;
            this.admissionThreshold = admissionThreshold;
        }
    }
}
