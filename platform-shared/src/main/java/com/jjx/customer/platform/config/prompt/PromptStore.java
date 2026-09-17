package com.jjx.customer.platform.config.prompt;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Prompt 文本统一加载器。
 *
 * <p>所有 prompt 文本集中在 {@code classpath:prompts/} 下（按模块分目录，文件名即功能）。
 * 本组件按 key（相对路径，不含后缀）读取并缓存文本，资源文件是 prompt 的开发态默认来源；
 * 代码 / yaml 仅保留"开关 + 运维覆盖"入口。</p>
 *
 * <p><b>2026-09-12 P1 资产化改造</b>：目录扫描替代硬编码 KNOWN_KEYS 清单（新增 prompt 文件
 * 自动被预加载与 {@link #allKeys()} 暴露，忘记登记的维护债消除）；基线导入
 * （{@code chat/prompt/PromptAssetService}）消费 {@link #allKeys()} 与 {@link #raw}。</p>
 *
 * <p><b>关于 Spring AI 模板渲染：</b>Spring AI 的 StringTemplate 渲染是<b>条件渲染</b>——
 * {@code ChatClient.system/user} 只有在调用时传了 {@code .param()} 才会进入 ST 解析，否则文本原样发送。
 * 本组件 {@link #raw(String)} 返回纯文本、不做渲染；调用方若需注入变量（如用户问题），应在 Java 侧
 * 自行拼接（见 {@code DefaultIntentClassifier}），避免含 JSON 花括号的 prompt 被 ST 误解析。</p>
 */
@Slf4j
@Component
public class PromptStore {

    private static final String BASE = "prompts/";
    private static final String SUFFIX = ".md";
    private static final String SCAN_PATTERN = "classpath*:" + BASE + "**/*" + SUFFIX;

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Set<String> scannedKeys = new TreeSet<>();

    /** 读取原始文本（不渲染），按 {@code prompts/<key>.md} 加载并缓存。 */
    public String raw(String key) {
        return cache.computeIfAbsent(key, this::readResource);
    }

    /** 作为 Spring Resource 返回（用于 {@code chatClient.system(Resource)} 等场景）。 */
    public Resource resource(String key) {
        return new ClassPathResource(BASE + key + SUFFIX);
    }

    /**
     * 覆盖型获取：{@code override} 非空则用 override，否则回退到资源文件默认值。
     * 供 Enhancer/Enricher 等支持 DB 配置覆盖 system prompt 的节点使用。
     */
    public String rawOrOverride(String key, String override) {
        return (override != null && !override.isBlank()) ? override : raw(key);
    }

    /** classpath 下全部 prompt key（目录扫描，启动时固化；基线导入的种子全集）。 */
    public Set<String> allKeys() {
        return Set.copyOf(scannedKeys);
    }

    private String readResource(String key) {
        Resource resource = resource(key);
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载 prompt 失败: classpath:" + BASE + key + SUFFIX, e);
        }
    }

    @PostConstruct
    void preload() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(SCAN_PATTERN);
            for (Resource r : resources) {
                String url = r.getURL().toString();
                int at = url.indexOf(BASE);
                if (at < 0) {
                    continue;
                }
                String key = url.substring(at + BASE.length())
                        .replace(SUFFIX, "")
                        .replace('\\', '/');
                raw(key);
                scannedKeys.add(key);
            }
        } catch (Exception e) {
            throw new IllegalStateException("扫描 prompts/ 目录失败", e);
        }
        log.info("[PromptStore] 目录扫描预加载 {} 个 prompt（classpath:{}）", scannedKeys.size(), BASE);
    }
}
