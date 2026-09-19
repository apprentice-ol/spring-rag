package com.agentframework.engine.toolexecutor;

import java.util.List;
import java.util.Optional;

/**
 * 工具来源扩展点：按 id 与版本解析工具实现。
 */
public interface ToolProvider {

    /**
     * 解析工具实现。
     *
     * @param toolId  工具 id
     * @param version 版本号，{@code latest} 表示最新
     * @return 工具实现
     */
    Optional<Tool> resolve(String toolId, String version);

    /** @return 全部工具实现 */
    List<Tool> list();
}
