package com.jjx.customer.platform.delivery.rest;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.knowledge.retrieval.CacheKeys;
import com.jjx.customer.platform.cache.CacheMetrics;
import com.jjx.customer.platform.cache.CacheProperties;
import com.jjx.customer.platform.cache.RedisHealth;
import com.jjx.customer.platform.knowledge.retrieval.SemanticAnswerCache;
import com.jjx.customer.platform.business.runtime.DegradeGuard;
import com.jjx.customer.platform.config.properties.ChatProperties;
import com.jjx.customer.platform.common.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缓存看板（GET /cache/stats）：缓存使用的针对性优化依据——各层命中率（哪层白干）、
 * 键分布（哪层占内存）、docver 翻转、语义缓存热点问题榜、断路器/降级状态。
 * <p>命中计数为进程内自启动累计（重启清零）；Redis 段失败仅置 redisDown=true，其余照常返回。
 * 手动刷新式查询，不做推送/轮询。
 */
@Slf4j
@RestController
@RequestMapping("/cache")
@RequiredArgsConstructor
public class CacheStatsController {

    /** SCAN 保护上限（rag:cache:* 全量扫描的最多键数） */
    private static final int SCAN_LIMIT = 20_000;

    private final CacheProperties cacheProperties;
    private final ChatProperties chatProperties;
    private final CacheMetrics cacheMetrics;
    private final RedisHealth redisHealth;
    private final DegradeGuard degradeGuard;
    private final SemanticAnswerCache semanticAnswerCache;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private static final Set<String> REDIS_ENTRY_LAYERS = Set.of("intent", "retr", "logs", "ans", "emb");

    /** 单层使用情况（命中计数为进程内累计；window* 为最近 1h 滑动窗口口径）。 */
    public record LayerStat(String layer, boolean enabled, String ttl,
                            Integer admissionThreshold, long hits, long misses, double hitRate,
                            long windowHits, long windowMisses, double windowHitRate) {
    }

    /** 热点问题行。 */
    public record TopQuestion(String question, String paradigm, long hitCount, String updatedAt) {
    }

    // ===== 运行时开关（进程内生效，重启恢复 yaml 配置） =====

    /**
     * 开关缓存：body {layer?: string, enabled: boolean}。layer 缺省 = 总开关；
     * 可选值 intent/retrieval/logs/answer/embedding/semantic。
     */
    @PostMapping("/toggle")
    public Map<String, Object> toggle(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String layer = body.get("layer") == null ? null : String.valueOf(body.get("layer")).trim();
        if (layer == null || layer.isBlank()) {
            cacheProperties.setEnabled(enabled);
        } else {
            switch (layer) {
                case "intent" -> cacheProperties.getIntent().setEnabled(enabled);
                case "retrieval" -> cacheProperties.getRetrieval().setEnabled(enabled);
                case "logs" -> cacheProperties.getLogs().setEnabled(enabled);
                case "answer" -> cacheProperties.getAnswer().setEnabled(enabled);
                case "embedding" -> cacheProperties.getEmbedding().setEnabled(enabled);
                case "semantic" -> cacheProperties.getSemantic().setEnabled(enabled);
                default -> throw new ClientException("未知缓存层: " + layer);
            }
        }
        log.info("[CacheStats] 缓存开关: layer={}, enabled={}（运行时生效，重启恢复配置）", layer, enabled);
        return Map.of("layer", layer == null ? "" : layer, "enabled", enabled);
    }

    // ===== 语义缓存记录 =====

    /** 记录分页（update_time 倒序）。 */
    @GetMapping("/semantic/records")
    public SemanticAnswerCache.RecordPage semanticRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 50);
        return semanticAnswerCache.pageRecords(page, size);
    }

    /** 删除单条记录（手动失效）。 */
    @DeleteMapping("/semantic/{id}")
    public Map<String, Object> deleteSemantic(@PathVariable long id) {
        return Map.of("deleted", semanticAnswerCache.deleteById(id));
    }

    /** 清空全部语义缓存记录。 */
    @DeleteMapping("/semantic")
    public Map<String, Object> clearSemantic() {
        return Map.of("deleted", semanticAnswerCache.clearAll());
    }

    // ===== Redis 键记录（哈希键不可逆，ans 层附答案预览） =====

    /** Redis 单层键记录：key 哈希尾缀 + 剩余 TTL + ans 层的答案预览。 */
    public record RedisEntry(String key, Long ttlSeconds, String preview) {
    }

    @GetMapping("/redis-entries")
    public List<RedisEntry> redisEntries(@RequestParam String layer,
                                         @RequestParam(defaultValue = "50") int limit) {
        String seg = switch (layer) {
            case "intent" -> "intent";
            case "retrieval" -> "retr";
            case "logs" -> "logs";
            case "answer" -> "ans";
            case "embedding" -> "emb";
            default -> throw new ClientException("未知缓存层: " + layer);
        };
        if (!REDIS_ENTRY_LAYERS.contains(seg)) {
            throw new ClientException("该层不落 Redis 键: " + layer);
        }
        limit = Math.min(Math.max(limit, 1), 200);
        List<RedisEntry> out = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match("rag:cache:" + seg + ":*").count(500).build())) {
            while (cursor.hasNext() && out.size() < limit) {
                String key = cursor.next();
                Long ttl = ttlOf(key);
                out.add(new RedisEntry(tail(key), ttl, "ans".equals(seg) ? answerPreview(key) : null));
            }
        } catch (Exception e) {
            log.warn("[CacheStats] Redis 键记录查询失败: {}", e.getMessage());
        }
        return out;
    }

    private Long ttlOf(String key) {
        try {
            return redis.getExpire(key);
        } catch (Exception e) {
            return null; // TTL 命令不可用时跳过该列
        }
    }

    /** ans 层载荷里的答案前 80 字（JSON 解析失败返回 null）。 */
    private String answerPreview(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(json);
            String answer = node.path("answer").asText(null);
            if (answer == null) {
                return null;
            }
            return answer.length() > 80 ? answer.substring(0, 80) + "…" : answer;
        } catch (Exception e) {
            return null;
        }
    }

    private static String tail(String key) {
        int i = key.lastIndexOf(':');
        return i >= 0 ? key.substring(i + 1) : key;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, long[]> counters = cacheMetrics.snapshot();
        Map<String, long[]> windowed = cacheMetrics.windowSnapshot(Duration.ofHours(1));
        List<LayerStat> layers = new ArrayList<>();
        layers.add(layer("intent", cacheProperties.getIntent().isEnabled(),
                cacheProperties.getIntent().getTtl(), cacheProperties.getIntent().getAdmissionThreshold(),
                counters, windowed));
        layers.add(layer("retrieval", cacheProperties.getRetrieval().isEnabled(),
                cacheProperties.getRetrieval().getTtl(), cacheProperties.getRetrieval().getAdmissionThreshold(),
                counters, windowed));
        layers.add(layer("logs", cacheProperties.getLogs().isEnabled(),
                cacheProperties.getLogs().getTtl(), cacheProperties.getLogs().getAdmissionThreshold(),
                counters, windowed));
        layers.add(layer("answer", cacheProperties.getAnswer().isEnabled(),
                cacheProperties.getAnswer().getTtl(), cacheProperties.getAnswer().getAdmissionThreshold(),
                counters, windowed));
        layers.add(layer("embedding", cacheProperties.getEmbedding().isEnabled(),
                cacheProperties.getEmbedding().getTtl(), cacheProperties.getEmbedding().getAdmissionThreshold(),
                counters, windowed));
        layers.add(layer("semantic", cacheProperties.getSemantic().isEnabled(),
                cacheProperties.getSemantic().getTtl(), null, counters, windowed));

        Map<String, Object> redisSection = redisSection();
        Map<String, Object> semanticSection = semanticSection();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cacheProperties.isEnabled());
        out.put("layers", layers);
        out.put("circuit", Map.of(
                "state", redisHealth.state(),
                "down", redisHealth.isDown(),
                "failureThreshold", cacheProperties.getCircuit().getFailureThreshold(),
                "cooldown", fmt(cacheProperties.getCircuit().getCooldown())));
        out.put("degrade", Map.of(
                "limit", chatProperties.getDegradeLimit(),
                "rejected", degradeGuard.rejectedTotal(),
                "availablePermits", degradeGuard.availablePermits()));
        out.putAll(redisSection);
        out.put("semantic", semanticSection);
        return out;
    }

    private static LayerStat layer(String name, boolean enabled, Duration ttl,
                                   Integer admission, Map<String, long[]> counters,
                                   Map<String, long[]> windowed) {
        long[] c = counters.get(name);
        long hits = c == null ? 0 : c[0];
        long misses = c == null ? 0 : c[1];
        long total = hits + misses;
        double rate = total == 0 ? 0 : (double) hits / total;
        long[] w = windowed.get(name);
        long windowHits = w == null ? 0 : w[0];
        long windowMisses = w == null ? 0 : w[1];
        long windowTotal = windowHits + windowMisses;
        double windowRate = windowTotal == 0 ? 0 : (double) windowHits / windowTotal;
        return new LayerStat(name, enabled, fmt(ttl), admission, hits, misses,
                Math.round(rate * 10000) / 10000.0, windowHits, windowMisses,
                Math.round(windowRate * 10000) / 10000.0);
    }

    /** Redis 键分布 + docver（失败仅置 redisDown）。 */
    private Map<String, Object> redisSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        Map<String, Long> keyCounts = new LinkedHashMap<>();
        Map<String, Long> docvers = new LinkedHashMap<>();
        boolean redisDown = false;
        try {
            try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                    .match(CacheKeys.PREFIX_ALL + "*").count(500).build())) {
                int scanned = 0;
                while (cursor.hasNext() && scanned < SCAN_LIMIT) {
                    String key = cursor.next();
                    scanned++;
                    String[] parts = key.split(":");
                    if (parts.length < 3) {
                        continue;
                    }
                    if ("docver".equals(parts[2])) {
                        String v = redis.opsForValue().get(key);
                        if (v != null) {
                            docvers.put(parts[parts.length - 1], Long.parseLong(v));
                        }
                    } else {
                        keyCounts.merge(parts[2], 1L, Long::sum);
                    }
                }
            }
        } catch (Exception e) {
            redisDown = true;
            log.warn("[CacheStats] Redis 键分布统计失败: {}", e.getMessage());
        }
        section.put("redisDown", redisDown);
        section.put("keyCounts", keyCounts);
        section.put("docver", docvers);
        return section;
    }

    /** 语义缓存概况 + 热点问题榜。 */
    private Map<String, Object> semanticSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS rows, COALESCE(SUM(hit_count), 0) AS hits FROM sa_cache_answer");
            section.put("rows", ((Number) row.get("rows")).longValue());
            section.put("totalHits", ((Number) row.get("hits")).longValue());
            section.put("threshold", cacheProperties.getSemantic().getThreshold());
            List<TopQuestion> top = semanticAnswerCache.topQuestions(10).stream()
                    .map(t -> new TopQuestion(t.question(), t.paradigm(), t.hitCount(),
                            t.updatedAt() == null ? null : String.valueOf(t.updatedAt())))
                    .toList();
            section.put("topQuestions", top);
        } catch (Exception e) {
            log.warn("[CacheStats] 语义缓存统计失败: {}", e.getMessage());
        }
        return section;
    }

    private static String fmt(Duration d) {
        if (d == null) {
            return null;
        }
        long s = d.getSeconds();
        if (s % 86400 == 0) return (s / 86400) + "d";
        if (s % 3600 == 0) return (s / 3600) + "h";
        if (s % 60 == 0) return (s / 60) + "m";
        return s + "s";
    }
}
