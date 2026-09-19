package com.agentframework.definition.codec;

import java.util.Map;

/**
 * 定义编解码扩展点：把定义对象与文档互相转换。
 *
 * <p>内置实现是 JSON（{@link JsonDefinitionCodec}）；YAML 等格式由插件实现本接口后
 * 通过 {@code EngineBuilder.extension(DefinitionCodec.class, "yaml", codec)} 注册。</p>
 */
public interface DefinitionCodec {

    /** @return 格式名，例如 {@code json} */
    String format();

    /**
     * 编码定义。
     *
     * @param kind       定义种类
     * @param definition 定义对象
     * @return 文档（Map / List / 基本类型）
     */
    Map<String, Object> encode(DefinitionKind kind, Object definition);

    /**
     * 解码定义。文档必须先通过 {@code kind} 与 {@code schemaVersion} 校验。
     *
     * @param kind     定义种类
     * @param document 文档
     * @return 定义对象
     */
    Object decode(DefinitionKind kind, Map<String, Object> document);

    /**
     * 文档的已知顶层字段，用于识别未识别字段（前向兼容提示）。
     *
     * @param kind 定义种类
     * @return 已知字段名
     */
    default java.util.Set<String> knownFields(DefinitionKind kind) {
        return java.util.Set.of("schemaVersion", "kind");
    }
}
