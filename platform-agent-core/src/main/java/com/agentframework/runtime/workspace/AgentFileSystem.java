package com.agentframework.runtime.workspace;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 工作区文件访问接口。
 *
 * <p>实现决定路径映射到内存、磁盘还是沙箱，因此同一份工具代码可以运行在不同隔离级别下。</p>
 */
public interface AgentFileSystem {

    /**
     * @param path    相对路径
     * @param content 文件内容
     */
    void write(String path, byte[] content);

    /**
     * @param path 相对路径
     * @return 文件内容
     */
    byte[] read(String path);

    /**
     * @param path    相对路径
     * @param content 文本内容
     */
    default void writeString(String path, String content) {
        write(path, content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param path 相对路径
     * @return 文本内容
     */
    default String readString(String path) {
        return new String(read(path), StandardCharsets.UTF_8);
    }

    /**
     * @param path 相对路径
     * @return 是否存在
     */
    boolean exists(String path);

    /**
     * @param directory 目录路径
     * @return 目录下的条目名列表
     */
    List<String> list(String directory);

    /**
     * @param path 相对路径
     * @return 是否确实删除了内容
     */
    boolean delete(String path);

    /** @param path 需要创建的目录路径 */
    void mkdirs(String path);
}
