package com.nageoffer.ai.rag.observe.controller;

import com.nageoffer.ai.rag.config.properties.OpenObserveProperties;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链路追踪 / 可观测性入口：为前端「链路追踪」页提供 OpenObserve 跳转链接与只读账号。
 *
 * <p>链接由后端按 {@code openobserve.web-url}（浏览器可达的 OO Web UI 地址）构建，
 * 前端不维护地址/账号，生产换部署地址只改配置即可。</p>
 */
@RestController
@RequestMapping("/observe")
@RequiredArgsConstructor
public class ObserveController {

    private final OpenObserveProperties openobserveProperties;

    /** 链路追踪跳转链接 + 只读账号（前端 TraceView 调用） */
    @GetMapping("/links")
    public Map<String, Object> links() {
        String org = "default";
        String base = openobserveProperties.getWebUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // OO 社区版无 RBAC，role 只能 admin；此账号供「查看」场景共享（看 trace/logs 本身只读）
        return Map.of(
                "traceUrl", base + "/web/traces?tab=traces?org_identifier" + org,
                "logUrl", base + "/web/logs?org_identifier" + org,
                "org", org,
                "email", "viewer@springai-rag.com",
                "password", "Viewer@rag2026"
        );
    }
}
