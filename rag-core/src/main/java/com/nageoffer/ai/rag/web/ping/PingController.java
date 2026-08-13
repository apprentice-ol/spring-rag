package com.nageoffer.ai.rag.web.ping;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点。
 * <p>
 * 前端 IngestPanel 通过 {@code GET /ping/health} 检测后端是否在线。
 * </p>
 */
@RestController
@RequestMapping("/ping")
public class PingController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString()
        );
    }
}
