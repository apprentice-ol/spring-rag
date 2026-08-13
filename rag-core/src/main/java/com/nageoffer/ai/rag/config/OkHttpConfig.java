package com.nageoffer.ai.rag.config;

import com.google.gson.Gson;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OkHttp 客户端装配（给 HttpClientHelper / HttpUrlFetcher 用）。
 *
 * <p>对应原 ragent infra-ai 装配的 syncHttpClient bean；srag 无 infra-ai 模块，这里直接暴露。
 */
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient syncHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public Gson gson() {
        return new Gson();
    }
}
