package com.nageoffer.ai.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI3 接口文档元信息配置。
 *
 * <p>UI 入口：{@code http://localhost:9081/api/rag/doc.html}
 * （knife4j 自动扫描所有 {@code @RestController}，无需逐个加注解）
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI springAiRagOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SpringAI RAG API")
                        .description("基于 Spring AI 的 RAG 文档入库与问答")
                        .version("v0.0.1"));
    }
}
