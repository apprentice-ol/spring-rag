package com.jjx.customer.platform.config;

import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 空密码兜底：starter 会无条件把 spring.data.redis.password 透传给底层连接（空字符串也发 AUTH），
 * 连无 requirepass 的 Redis（本地开发 / docker compose 默认部署）直接报
 * "ERR AUTH called without any password configured" 拒连。此处把空密码归一为 null 跳过 AUTH；
 * Redis 设了密码时走 .env 的 REDIS_PASSWORD，原样保留。
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonAutoConfigurationCustomizer redissonEmptyPasswordGuard(RedisProperties redisProperties) {
        // 仅单机模式归一化（sentinel/cluster 时动 useSingleServer() 会新建配置导致 Config 校验失败）
        boolean standalone = redisProperties.getSentinel() == null && redisProperties.getCluster() == null;
        return config -> {
            if (!standalone) {
                return;
            }
            SingleServerConfig single = config.useSingleServer();
            if (!StringUtils.hasText(single.getPassword())) {
                single.setPassword(null);
            }
        };
    }
}
