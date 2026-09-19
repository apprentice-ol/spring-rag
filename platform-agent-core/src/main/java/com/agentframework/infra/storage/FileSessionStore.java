package com.agentframework.infra.storage;

import com.agentframework.runtime.persistence.SessionStore;
import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.MessageRole;
import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.session.SessionState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 文件会话存储：把会话记录写入属性文件，重启进程后仍可恢复。
 *
 * <p>刻意不依赖第三方序列化库：属性值为文本，消息按行编码。复杂对象槽位请配合
 * {@code SlotCodec} 使用专门实现。</p>
 */
public final class FileSessionStore implements SessionStore {

    private final Path root;

    /** @param root 存储根目录，不存在会自动创建 */
    public FileSessionStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("创建会话存储目录失败：" + root, e);
        }
    }

    @Override
    public void save(SessionRecord record) {
        if (record == null || record.sessionId() == null) {
            return;
        }
        Properties properties = new Properties();
        put(properties, "session.id", record.sessionId());
        put(properties, "session.tenantId", record.tenantId());
        put(properties, "session.userId", record.userId());
        put(properties, "session.agentId", record.agentId());
        put(properties, "session.agentVersion", record.agentVersion());
        put(properties, "session.workflowId", record.workflowId());
        put(properties, "session.workflowVersion", record.workflowVersion());
        put(properties, "session.state", record.state().name());
        put(properties, "session.cursor.nodeId", record.cursor().nodeId());
        put(properties, "session.cursor.step", String.valueOf(record.cursor().step()));
        put(properties, "session.cursor.lastEdge", record.cursor().lastEdge());
        record.cursor().loopCounters().forEach((nodeId, count) ->
                put(properties, "session.cursor.loop." + nodeId, String.valueOf(count)));
        record.cursor().state().forEach((key, value) -> {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                put(properties, "session.cursor.state." + key, String.valueOf(value));
            }
        });
        put(properties, "session.traceId", record.traceId());
        put(properties, "session.createdAt", record.createdAt().toString());
        put(properties, "session.updatedAt", record.updatedAt().toString());
        put(properties, "session.output", record.output());
        put(properties, "session.error", record.error());
        for (int i = 0; i < record.messages().size(); i++) {
            Message message = record.messages().get(i);
            put(properties, "message." + i, message.role().name() + "|" + nullSafe(message.name())
                    + "|" + message.content().replace("\n", "\\n"));
        }
        Path file = fileFor(record.sessionId());
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "agent-framework session");
        } catch (IOException e) {
            throw new IllegalStateException("写入会话失败：" + file, e);
        }
    }

    @Override
    public Optional<SessionRecord> load(String sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取会话失败：" + file, e);
        }
        List<Message> messages = new ArrayList<>();
        for (int i = 0; ; i++) {
            String raw = properties.getProperty("message." + i);
            if (raw == null) {
                break;
            }
            String[] parts = raw.split("\\|", 3);
            String content = parts.length > 2 ? parts[2].replace("\\n", "\n") : "";
            messages.add(new Message(
                    MessageRole.valueOf(parts[0]),
                    content,
                    parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null,
                    null,
                    null,
                    null));
        }
        java.util.Map<String, Integer> loopCounters = new java.util.LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith("session.cursor.loop.")) {
                continue;
            }
            String nodeId = name.substring("session.cursor.loop.".length());
            try {
                loopCounters.put(nodeId, Integer.parseInt(properties.getProperty(name)));
            } catch (NumberFormatException ignored) {
                // 非法计数忽略，避免单个字段损坏导致整个会话不可恢复
            }
        }
        java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("session.cursor.state.")) {
                state.put(name.substring("session.cursor.state.".length()),
                        parseState(properties.getProperty(name)));
            }
        }
        if (!loopCounters.isEmpty()) {
            state.put(Cursor.LOOP_COUNTERS, java.util.Map.copyOf(loopCounters));
        }
        Cursor cursor = new Cursor(
                properties.getProperty("session.cursor.nodeId"),
                Long.parseLong(properties.getProperty("session.cursor.step", "0")),
                properties.getProperty("session.cursor.lastEdge"),
                state.isEmpty() ? null : state);
        SessionRecord record = new SessionRecord(
                properties.getProperty("session.id"),
                properties.getProperty("session.tenantId"),
                properties.getProperty("session.userId"),
                properties.getProperty("session.agentId"),
                properties.getProperty("session.agentVersion"),
                properties.getProperty("session.workflowId"),
                properties.getProperty("session.workflowVersion"),
                SessionState.valueOf(properties.getProperty("session.state", SessionState.CREATED.name())),
                cursor,
                messages,
                properties.getProperty("session.traceId"),
                null,
                Instant.parse(properties.getProperty("session.createdAt", Instant.now().toString())),
                Instant.parse(properties.getProperty("session.updatedAt", Instant.now().toString())),
                properties.getProperty("session.output"),
                properties.getProperty("session.error"));
        return Optional.of(record);
    }

    @Override
    public List<SessionRecord> listByTenant(String tenantId) {
        return list().stream().filter(record -> tenantId == null || tenantId.equals(record.tenantId())).toList();
    }

    @Override
    public List<SessionRecord> list() {
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(path -> load(stripExtension(path)))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(SessionRecord::createdAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("列出会话失败：" + root, e);
        }
    }

    @Override
    public boolean delete(String sessionId) {
        try {
            return Files.deleteIfExists(fileFor(sessionId));
        } catch (IOException e) {
            throw new IllegalStateException("删除会话失败：" + sessionId, e);
        }
    }

    /** @return 会话文件路径 */
    private Path fileFor(String sessionId) {
        return root.resolve(safeName(sessionId) + ".properties");
    }

    /** @return 由文件名还原的会话 id */
    private String stripExtension(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - ".properties".length());
    }

    /** 过滤文件系统不允许的字符。 */
    private String safeName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 空值安全的字符串转换。 */
    private String nullSafe(String value) {
        return value == null ? "" : value.replace("|", "/").replace("\n", "\\n");
    }

    /** 仅在值非空时写入属性。 */
    private void put(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }

    /**
     * 还原游标状态值：优先按布尔、整数、长整数、小数解析，失败时保留字符串。
     *
     * @param raw 属性文本
     * @return 还原后的值
     */
    private Object parseState(String raw) {
        if (raw == null) {
            return "";
        }
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.valueOf(raw);
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ignored) {
            // 继续尝试其它数值类型
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ignored) {
            // 继续尝试小数
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }
}
