package com.jjx.customer.platform.agent.framework.result;

import java.util.Map;

/**
 * 证据片段（检索命中 / 工具产出）。
 *
 * @param ref      引用编号（生成文本里的 [N] 与 CitationIndex 对应）
 * @param content  片段正文
 * @param source   来源标识（文档名 / 日志服务等）
 * @param score    相关度分数（可空）
 * @param channel  来源通道（向量 / 关键词 / 联网 / 日志…，可空）
 * @param metadata 附加元数据
 */
public record ContextArtifact(int ref,
                              String content,
                              String source,
                              Double score,
                              String channel,
                              Map<String, Object> metadata) {

    public ContextArtifact {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
