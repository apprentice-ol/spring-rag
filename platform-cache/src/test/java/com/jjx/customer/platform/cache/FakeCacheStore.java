package com.jjx.customer.platform.cache;


import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用内存 CacheStore（记录 TTL / touch 调用供断言）。非 *Test 命名，surefire 不会当用例跑。
 */
public class FakeCacheStore implements CacheStore {

    public final Map<String, String> data = new HashMap<>();
    public final Map<String, Duration> ttls = new HashMap<>();
    public final List<String> touched = new ArrayList<>();

    @Override
    public Optional<String> get(String key, String layer) {
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public void put(String key, String json, Duration ttl, String layer) {
        data.put(key, json);
        ttls.put(key, ttl);
    }

    @Override
    public void touch(String key, Duration ttl, String layer) {
        touched.add(key);
        ttls.put(key, ttl);
    }
}
