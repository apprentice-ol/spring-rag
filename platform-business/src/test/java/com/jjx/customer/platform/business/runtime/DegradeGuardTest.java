package com.jjx.customer.platform.business.runtime;

import com.jjx.customer.platform.cache.RedisHealth;
import com.jjx.customer.platform.config.properties.ChatProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级闸单测（fake RedisHealth 控制降级状态）：健康直通零成本 / 降级期并发上限 /
 * 拒绝后归还可复用 / Lease close 幂等。
 */
class DegradeGuardTest {

    /** 可切换降级状态的 fake 断路器。 */
    static class SwitchHealth extends RedisHealth {
        volatile boolean down;

        SwitchHealth() {
            super(new com.jjx.customer.platform.cache.CacheProperties(), null);
        }

        @Override
        public boolean isDown() {
            return down;
        }
    }

    private DegradeGuard guard(SwitchHealth health, int limit) {
        ChatProperties chatProperties = new ChatProperties();
        chatProperties.setDegradeLimit(limit);
        return new DegradeGuard(health, chatProperties, null);
    }

    @Test
    void 健康期直通_无并发上限() {
        SwitchHealth health = new SwitchHealth();
        DegradeGuard g = guard(health, 2);
        for (int i = 0; i < 100; i++) {
            assertTrue(g.tryAcquire().isPresent(), "健康期不应有限制");
        }
    }

    @Test
    void 降级期并发受限_超出拒绝() {
        SwitchHealth health = new SwitchHealth();
        DegradeGuard g = guard(health, 2);
        health.down = true;

        assertTrue(g.tryAcquire().isPresent());
        assertTrue(g.tryAcquire().isPresent());
        assertFalse(g.tryAcquire().isPresent(), "降级期超过 limit 应拒绝");
    }

    @Test
    void 归还后可复用() throws Exception {
        SwitchHealth health = new SwitchHealth();
        DegradeGuard g = guard(health, 1);
        health.down = true;

        DegradeGuard.Lease lease = g.tryAcquire().orElseThrow();
        assertFalse(g.tryAcquire().isPresent());
        lease.close();
        assertTrue(g.tryAcquire().isPresent(), "归还后许可应可复用");
    }

    @Test
    void lease关闭幂等_重复close不多还() throws Exception {
        SwitchHealth health = new SwitchHealth();
        DegradeGuard g = guard(health, 2);
        health.down = true;

        DegradeGuard.Lease lease = g.tryAcquire().orElseThrow();
        g.tryAcquire().orElseThrow(); // 占满 2
        lease.close();
        lease.close(); // 重复关
        // 只应归还 1 个许可：再取 1 个成功、第 2 个失败
        assertTrue(g.tryAcquire().isPresent());
        assertFalse(g.tryAcquire().isPresent());
    }

    @Test
    void 健康期noopLease重复close无副作用() throws Exception {
        SwitchHealth health = new SwitchHealth();
        DegradeGuard g = guard(health, 2);
        Optional<DegradeGuard.Lease> lease = g.tryAcquire();
        assertTrue(lease.isPresent());
        lease.get().close();
        lease.get().close();
        assertTrue(g.tryAcquire().isPresent());
    }
}
