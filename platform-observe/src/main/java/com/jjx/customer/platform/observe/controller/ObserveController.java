package com.jjx.customer.platform.observe.controller;

import com.jjx.ai.llmobservability.backends.openobserve.OpenObserveProperties;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 前端「链路追踪」页展示的 OO 登录账号。
     * <p>OO 社区版无 RBAC（无独立只读账号体系），未单独配置 OO_VIEWER_* 时自动回退
     * root 账号（ZO_ROOT_USER_*，即 compose 部署 OO 的那组）——此前 .env 里手写的
     * viewer 账号在 OO 中并不存在，前端照抄登录必然失败。</p>
     * <p>取值级联（{@link #firstNonBlank} 逐级回退，空串视为未配置）：
     * OO_VIEWER_* → ZO_ROOT_USER_* → dev 兜底假值。
     * 不用 @Value 嵌套默认（${A:${B:fallback}}）的原因：compose 透传缺省变量时注入的是
     * 空串而非「不存在」，空串会遮蔽嵌套默认导致页面展示空账号。</p>
     */
    @Value("${OO_VIEWER_EMAIL:}")
    private String viewerEmailProp;

    @Value("${OO_VIEWER_PASSWORD:}")
    private String viewerPasswordProp;

    @Value("${ZO_ROOT_USER_EMAIL:}")
    private String rootEmailProp;

    @Value("${ZO_ROOT_USER_PASSWORD:}")
    private String rootPasswordProp;

    /** dev 兜底假账号（仅本地完全未配置时展示；compose 部署 .env 必配 ZO_ROOT_USER_*） */
    private static final String DEV_FALLBACK_EMAIL = "viewer@dev.local";
    private static final String DEV_FALLBACK_PASSWORD = "DevOnlyViewer";

    /** 第一个非 blank 值，全空返回 null */
    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

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
                "traceUrl", base + "/web/traces?org_identifier=" + org,
                "logUrl", base + "/web/logs?org_identifier=" + org,
                // trace 详情深链模板（{traceId}/{from}/{to} 占位，from/to 为微秒时间戳，前端按消息时间生成窗口）：
                // 对话页消息气泡「OO 链路」按此跳转（缺 from/to 会被 OO 重定向到列表页）
                "traceDetailUrlTemplate", base + "/web/traces/trace-details?org_identifier=" + org
                        + "&stream=" + openobserveProperties.getTraceStream()
                        + "&trace_id={traceId}&from={from}&to={to}",
                "org", org,
                "email", firstNonBlank(viewerEmailProp, rootEmailProp, DEV_FALLBACK_EMAIL),
                "password", firstNonBlank(viewerPasswordProp, rootPasswordProp, DEV_FALLBACK_PASSWORD)
        );
    }
}
