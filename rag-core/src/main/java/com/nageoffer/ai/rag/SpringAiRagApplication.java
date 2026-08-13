package com.nageoffer.ai.rag;

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
@MapperScan("com.nageoffer.ai.rag.**.mapper")
public class SpringAiRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiRagApplication.class, args);
    }
}
