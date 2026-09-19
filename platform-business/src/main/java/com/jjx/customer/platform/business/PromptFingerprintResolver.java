package com.jjx.customer.platform.business;

import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.prompt.service.PromptBindingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Prompt 指纹解析器：按范式（= Agent id）解析全层 prompt 的内容 hash——
 * 缓存 key 的先决信息（不变量 5/9：指纹先于流式与缓存读写）。
 *
 * <p>三层口径与旧 {@code PromptStoreSnapshotSource} 一致：link 层（{@link AgentCatalog#LINK_KEYS}）
 * + agent 人格层 + workflow 任务层（含 answerPromptKey）；每 key 生效内容 =
 * 绑定包覆盖优先，回退 classpath 基线。hash 算法逐字复刻旧 {@code PromptAssembler.hashOf}
 * （按 key 排序、SOH/STX 串联、SHA-256 前 24 hex）——改任一层 prompt 自动变 ⇒ 缓存自动失效。</p>
 */
@Slf4j
@Component
public class PromptFingerprintResolver {

    /** 解析失败的回退值（key 拼段稳定，缓存语义退化为不含指纹的旧口径）。 */
    public static final String UNKNOWN = "no-prompt-fingerprint";

    private final PromptStore promptStore;
    private final PromptBindingService bindingService;

    public PromptFingerprintResolver(PromptStore promptStore, PromptBindingService bindingService) {
        this.promptStore = promptStore;
        this.bindingService = bindingService;
    }

    /** 范式 → prompt 内容 hash（改任一层 prompt 自动变 ⇒ 缓存自动失效）。 */
    public String promptHash(String paradigm) {
        try {
            AgentCatalog.Entry entry = AgentCatalog.byId(paradigm).orElse(null);
            if (entry == null) {
                return UNKNOWN;
            }
            Map<String, String> overrides = bindingService.overridesFor(entry.id());
            Map<String, String> contents = new LinkedHashMap<>();
            for (String key : AgentCatalog.LINK_KEYS) {
                contents.put(key, contentOf(key, overrides));
            }
            for (String key : entry.allPromptKeys()) {
                contents.put(key, contentOf(key, overrides));
            }
            return hashOf(contents);
        } catch (Exception e) {
            log.warn("[指纹解析] 失败回退 {}: {}", UNKNOWN, e.getMessage());
            return UNKNOWN;
        }
    }

    /** key → 生效内容：绑定包覆盖优先，未命中回退 classpath。 */
    private String contentOf(String key, Map<String, String> overrides) {
        String override = overrides.get(key);
        return override != null ? override : promptStore.raw(key);
    }

    /** 稳定内容摘要（逐字复刻旧 PromptAssembler.hashOf）：key 排序后 key+SOH+content+STX 串联的 SHA-256 截断 24 hex。 */
    static String hashOf(Map<String, String> contents) {
        TreeMap<String, String> sorted = new TreeMap<>(contents == null ? Map.of() : contents);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((key, value) -> sb.append(key).append((char) 1).append(value).append((char) 2));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
