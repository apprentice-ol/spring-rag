package com.jjx.customer.platform.agent.framework.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 三层增强装配器：把链路层 / Agent 人格层 / Workflow 任务层（可含节点层）
 * 按顺序<b>相加</b>成一个不可变快照。
 *
 * <p>规则：
 * <ol>
 *   <li>层内保持声明顺序，层间按传入顺序拼接（system 文本 = 各层依次相接）；</li>
 *   <li>跨层同 key 直接报错（key 命名空间本应互不重叠，出现重复即装配 bug）；</li>
 *   <li>内容 hash 覆盖全部层——任一层改动 ⇒ 缓存自动失效；</li>
 *   <li>identity 记录各层 release 凭证（可追溯到具体版本组合）。</li>
 * </ol>
 */
public final class PromptAssembler {

    private PromptAssembler() {
    }

    public static PromptSnapshot assemble(List<PromptLayer> layers) {
        List<PromptLayer> ordered = layers == null ? List.of() : List.copyOf(layers);
        Map<String, String> contents = new LinkedHashMap<>();
        Map<String, String> owner = new LinkedHashMap<>();
        for (PromptLayer layer : ordered) {
            for (Map.Entry<String, String> entry : layer.contents().entrySet()) {
                String previousLayer = owner.putIfAbsent(entry.getKey(), layer.id());
                if (previousLayer != null) {
                    throw new IllegalStateException("Prompt key 跨层重复: " + entry.getKey()
                            + "（层 " + previousLayer + " 与 " + layer.id() + "）——三层是补充增强，不允许覆盖");
                }
                contents.put(entry.getKey(), entry.getValue());
            }
        }
        String identity = ordered.isEmpty() ? "空快照"
                : ordered.stream().map(PromptLayer::identity).collect(Collectors.joining(" + "));
        String releasesSpec = ordered.stream()
                .filter(l -> l.release() != null && !l.release().isBlank())
                .map(l -> l.id() + ":" + l.release())
                .collect(Collectors.joining("|"));
        return new PromptSnapshot(contents, identity, hashOf(contents),
                releasesSpec.isBlank() ? null : releasesSpec);
    }

    /** 稳定内容摘要：按 key 排序后 key + SOH + content + STX 串联的 SHA-256 截断 24 hex。 */
    public static String hashOf(Map<String, String> contents) {
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
