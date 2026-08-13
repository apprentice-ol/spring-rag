package com.nageoffer.ai.rag.config.prompt;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Prompt 文本统一加载器。
 *
 * <p>所有 prompt 文本集中在 {@code classpath:prompts/} 下（按模块分目录，文件名即功能）。
 * 本组件按 key（相对路径，不含后缀）读取并缓存文本，资源文件是 prompt 的唯一默认来源；
 * 代码 / yaml 仅保留"开关 + 运维覆盖"入口。</p>
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

    /** 全部已知 prompt key，启动时预校验存在性（fail fast）。 */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "ingestion/enhancer/context-enhance",
            "ingestion/enhancer/keywords",
            "ingestion/enhancer/questions",
            "ingestion/enhancer/metadata",
            "ingestion/pdf-format-guard",
            "ingestion/enricher/chunk-keywords",
            "ingestion/enricher/chunk-summary",
            "ingestion/enricher/chunk-metadata",
            "chat/intent/classify-system",
            "chat/intent/classify-user",
            "chat/query-rewrite",
            "chat/pipeline/rag-answer-system",
            "chat/pipeline/rag-answer-kb",
            "chat/pipeline/chitchat-system",
            "chat/pipeline/empty-retrieval",
            "chat/pipeline/context-block",
            "agent/grade",
            "agent/decompose",
            "agent/react-system",
            "agent/self-rag-reflect",
            "vlm/describe-image");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

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
        for (String key : KNOWN_KEYS) {
            raw(key);
        }
        log.info("[PromptStore] 预加载 {} 个 prompt 文本（classpath:{}）", KNOWN_KEYS.size(), BASE);
    }
}
