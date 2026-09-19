package com.agentframework.extension.spi;

import com.agentframework.extension.registry.ExtensionMeta;

/**
 * 一条 SPI 装配记录。
 *
 * @param meta            扩展元信息
 * @param extensionPoint  扩展点类型（接口），可为 null
 * @param implementation  实现实例
 */
public record SpiEntry(ExtensionMeta meta, Class<?> extensionPoint, Object implementation) {

    public SpiEntry {
        if (meta == null || implementation == null) {
            throw new IllegalArgumentException("SPI 记录必须包含元信息与实现实例");
        }
    }
}
