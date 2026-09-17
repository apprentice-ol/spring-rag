package com.jjx.customer.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SpringAI RAG 启动类。
 *
 * <p>能力范围（对齐 ragent 的两大核心）：文档入库 + 问题查询。限流、链路追踪、节点日志、MCP、意图树等
 * 不在本次重写范围内。
 */
@SpringBootApplication
@MapperScan("com.jjx.customer.platform.**.mapper")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
