package com.jjx.customer.platform.agent.framework.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存后端契约（业务实现：Redis / 进程内 / 其他）。
 *
 * <p>缓存 key 的组装（含执行指纹）在管线层，本契约只抽象存取。</p>
 */
public interface CacheBackend {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);
}
