package com.jjx.customer.platform.ingestion.engine.parser.model;

/** 资产引用（图片等，publicUrl 浏览器可直连，sourceBlockId 关联 Block.id）。 */
public record AssetRef(
        String publicUrl,
        String mime,
        String sourceBlockId) {
}
