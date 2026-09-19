package com.agentframework.extension.spi;

import java.util.List;

/**
 * SPI 加载器：把外部扩展来源（ServiceLoader 文件、配置文件、插件包）统一成装配记录。
 */
public interface SpiLoader {

    /** @return 来源描述，用于日志与审计 */
    String source();

    /** @return 加载到的扩展记录 */
    List<SpiEntry> load();
}
