package com.agentframework.infra.storage;

import com.agentframework.runtime.workspace.AgentFileSystem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存文件系统：工作区内的文件读写全部留在内存中。
 *
 * <p>适合测试与无副作用场景；需要真实落盘时替换为实现 {@link AgentFileSystem} 的扩展。</p>
 */
public final class InMemoryAgentFileSystem implements AgentFileSystem {

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    @Override
    public synchronized void write(String path, byte[] content) {
        requirePath(path);
        files.put(normalize(path), content == null ? new byte[0] : content.clone());
    }

    @Override
    public synchronized byte[] read(String path) {
        byte[] content = files.get(normalize(path));
        if (content == null) {
            throw new IllegalStateException("文件不存在：" + path);
        }
        return content.clone();
    }

    @Override
    public synchronized boolean exists(String path) {
        return files.containsKey(normalize(path));
    }

    @Override
    public synchronized List<String> list(String directory) {
        String prefix = normalize(directory);
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        List<String> result = new ArrayList<>();
        for (String path : files.keySet()) {
            if (path.startsWith(prefix)) {
                String remainder = path.substring(prefix.length());
                int slash = remainder.indexOf('/');
                result.add(slash < 0 ? remainder : remainder.substring(0, slash));
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    @Override
    public synchronized boolean delete(String path) {
        return files.remove(normalize(path)) != null;
    }

    @Override
    public synchronized void mkdirs(String path) {
        write(normalize(path) + "/.keep", new byte[0]);
    }

    /** @return 全部文件路径快照 */
    public synchronized Map<String, String> files() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        files.forEach((path, content) -> snapshot.put(path, new String(content, StandardCharsets.UTF_8)));
        return snapshot;
    }

    /** 用给定内容整体覆盖文件系统。 */
    public synchronized void restore(Map<String, String> content) {
        files.clear();
        if (content != null) {
            content.forEach((path, text) -> files.put(normalize(path), text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** @return 文件数量 */
    public synchronized int size() {
        return files.size();
    }

    /** 校验路径合法性。 */
    private void requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
    }

    /** 归一化路径：去掉首尾斜杠与重复斜杠。 */
    private String normalize(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
