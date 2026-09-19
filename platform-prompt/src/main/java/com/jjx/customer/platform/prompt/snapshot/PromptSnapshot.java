package com.jjx.customer.platform.prompt.snapshot;

import java.util.Map;

/**
 * Prompt 生效快照（业务侧自有契约，字段与旧内核 {@code PromptSnapshot} 一致）。
 *
 * @param contents     key → 生效内容（绑定包覆盖优先，回退 classpath 基线）
 * @param identity     来源身份（如「基座包@r3 + 特化包@r1」）
 * @param contentHash  内容摘要（SHA-256 截断 24 hex；改任一层内容自动变）
 * @param releasesSpec 重建凭证（bundleId:releaseNo 对，{@code |} 分隔；基线为 null）
 */
public record PromptSnapshot(Map<String, String> contents, String identity,
                             String contentHash, String releasesSpec) {
}
