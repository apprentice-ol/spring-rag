package com.jjx.customer.platform.business.runtime;

import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import com.jjx.customer.platform.cache.RedisHealth;
import com.jjx.customer.platform.config.properties.ChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 降级闸（熔断联动 load shedding）：Redis 断路器 OPEN 期间，昂贵路径（LLM 流式生成 / ops 诊断）
 * 的并发上限收紧到 {@code rag.chat.degrade-limit}——缓存保护消失时给下游 API 留活口，
 * 宁可礼貌拒绝也不全体排队拖垮。断路器恢复后自动解除（健康期零成本直通，无信号量开销）。
 * <p>检索侧不另设闸：{@code ragContextExecutor} 固定线程池已是天然 bulkhead。
 * 拒绝量计数进日志与 tag，供观测降级期间的 shedding 规模。
 * <p>归属：请求准入策略属于业务编排运行时（编排层直接使用；看板由 delivery 的缓存统计接口读取计数）。
 */
@Slf4j
@Component
public class DegradeGuard {

    private final RedisHealth redisHealth;
    private final ChatProperties chatProperties;
    private final TelemetryTemplate obsTemplate;

    private final Semaphore permits;
    private final AtomicLong rejected = new AtomicLong();

    public DegradeGuard(RedisHealth redisHealth, ChatProperties chatProperties,
                        TelemetryTemplate obsTemplate) {
        this.redisHealth = redisHealth;
        this.chatProperties = chatProperties;
        this.obsTemplate = obsTemplate;
        this.permits = new Semaphore(Math.max(1, chatProperties.getDegradeLimit()));
    }

    /** 累计拒绝次数（看板用）。 */
    public long rejectedTotal() {
        return rejected.get();
    }

    /** 当前可用许可数（看板用；健康期为满额语义值）。 */
    public int availablePermits() {
        return permits.availablePermits();
    }

    /**
     * 尝试获得通行许可。
     *
     * @return empty = 降级期并发已满，调用方应发降级提示并结束；Lease 须在流收尾时 close（幂等）
     */
    public Optional<Lease> tryAcquire() {
        if (!redisHealth.isDown()) {
            return Optional.of(Lease.noop());
        }
        if (permits.tryAcquire()) {
            return Optional.of(new Lease(permits));
        }
        long total = rejected.incrementAndGet();
        try {
            obsTemplate.tag("cache.shedding", total);
        } catch (Exception ignore) {
            // 打点 best-effort
        }
        log.warn("[DegradeGuard] 降级期间并发已满（limit={}），累计拒绝 {} 次",
                chatProperties.getDegradeLimit(), total);
        return Optional.empty();
    }

    /** 通行许可：noop（健康期）或信号量租约（降级期）；close 幂等。 */
    public static final class Lease implements AutoCloseable {

        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Semaphore permits) {
            this.permits = permits;
        }

        static Lease noop() {
            return new Lease(null);
        }

        @Override
        public void close() {
            if (permits != null && released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
