package com.agentframework.infra.storage;

import com.agentframework.definition.ValidationProblem;
import com.agentframework.definition.ValidationReport;
import com.agentframework.definition.ValidationSeverity;
import com.agentframework.definition.codec.DefinitionKind;
import com.agentframework.definition.codec.JsonSupport;
import com.agentframework.runtime.persistence.DefinitionRecord;
import com.agentframework.runtime.persistence.DefinitionStatus;
import com.agentframework.runtime.persistence.DefinitionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 文件定义存储：按 {@code kind/id/version/status.json} 落盘，便于本地开发与演示。
 *
 * <p>文档与校验报告一起保存，保证重新加载后前端仍能看到当时的校验结论。</p>
 */
public final class FileDefinitionStore implements DefinitionStore {

    private final Path root;

    /** @param root 存储根目录，不存在会自动创建 */
    public FileDefinitionStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("创建定义存储目录失败：" + root, e);
        }
    }

    @Override
    public DefinitionRecord save(DefinitionRecord record) {
        Path file = fileFor(record);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonSupport.write(toDocument(record)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入定义失败：" + file, e);
        }
        return record;
    }

    @Override
    public Optional<DefinitionRecord> find(DefinitionKind kind, String id, String version,
            DefinitionStatus status) {
        return read(fileFor(kind, id, version, status));
    }

    @Override
    public List<DefinitionRecord> list(DefinitionKind kind, DefinitionStatus status) {
        Path kindRoot = root.resolve(kind.wireName());
        if (!Files.isDirectory(kindRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(kindRoot)) {
            return files.filter(path -> path.getFileName().toString().equals(status.name() + ".json"))
                    .map(this::read)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(DefinitionRecord::createdAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("列定义失败：" + kindRoot, e);
        }
    }

    @Override
    public List<DefinitionRecord> history(DefinitionKind kind, String id) {
        Path idRoot = root.resolve(kind.wireName()).resolve(safeName(id));
        if (!Files.isDirectory(idRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(idRoot)) {
            return files.filter(Files::isRegularFile)
                    .map(this::read)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(DefinitionRecord::createdAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取定义历史失败：" + idRoot, e);
        }
    }

    @Override
    public boolean delete(DefinitionKind kind, String id, String version, DefinitionStatus status) {
        try {
            return Files.deleteIfExists(fileFor(kind, id, version, status));
        } catch (IOException e) {
            throw new IllegalStateException("删除定义失败：" + id, e);
        }
    }

    /**
     * @param record 记录
     * @return 可落盘的文档
     */
    private Map<String, Object> toDocument(DefinitionRecord record) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", record.kind().wireName());
        document.put("id", record.id());
        document.put("version", record.version());
        document.put("status", record.status().name());
        document.put("author", record.author());
        document.put("createdAt", record.createdAt().toString());
        if (record.publishedAt() != null) {
            document.put("publishedAt", record.publishedAt().toString());
        }
        document.put("definition", record.document());
        document.put("problems", record.report().problems().stream().map(problem -> {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("code", problem.code());
            encoded.put("severity", problem.severity().name());
            encoded.put("location", problem.location());
            encoded.put("message", problem.message());
            encoded.put("candidates", problem.candidates());
            return encoded;
        }).toList());
        return document;
    }

    /**
     * @param path 文件路径
     * @return 记录，文件不存在或损坏时为空
     */
    @SuppressWarnings("unchecked")
    private Optional<DefinitionRecord> read(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> document = JsonSupport.parseObject(Files.readString(path, StandardCharsets.UTF_8));
            List<ValidationProblem> problems = new ArrayList<>();
            Object rawProblems = document.get("problems");
            if (rawProblems instanceof List<?> list) {
                for (Object element : list) {
                    Map<String, Object> problem = (Map<String, Object>) element;
                    Object candidates = problem.get("candidates");
                    problems.add(new ValidationProblem(
                            String.valueOf(problem.get("code")),
                            ValidationSeverity.valueOf(String.valueOf(problem.get("severity"))),
                            String.valueOf(problem.get("location")),
                            String.valueOf(problem.get("message")),
                            candidates instanceof List<?> candidateList
                                    ? candidateList.stream().map(String::valueOf).toList()
                                    : List.of()));
                }
            }
            return Optional.of(new DefinitionRecord(
                    DefinitionKind.fromWire(String.valueOf(document.get("kind"))).orElse(null),
                    String.valueOf(document.get("id")),
                    String.valueOf(document.get("version")),
                    DefinitionStatus.valueOf(String.valueOf(document.get("status"))),
                    document.get("definition") instanceof Map<?, ?> definitionMap
                            ? (Map<String, Object>) definitionMap
                            : Map.of(),
                    ValidationReport.of(problems),
                    document.get("author") == null ? null : String.valueOf(document.get("author")),
                    Instant.parse(String.valueOf(document.get("createdAt"))),
                    document.get("publishedAt") == null ? null
                            : Instant.parse(String.valueOf(document.get("publishedAt")))));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * @param record 记录
     * @return 文件路径
     */
    private Path fileFor(DefinitionRecord record) {
        return fileFor(record.kind(), record.id(), record.version(), record.status());
    }

    /**
     * @param kind    定义种类
     * @param id      定义 id
     * @param version 版本号
     * @param status  状态
     * @return 文件路径
     */
    private Path fileFor(DefinitionKind kind, String id, String version, DefinitionStatus status) {
        return root.resolve(kind.wireName())
                .resolve(safeName(id))
                .resolve(safeName(version))
                .resolve((status == null ? DefinitionStatus.DRAFT : status).name() + ".json");
    }

    /**
     * @param value 原始名称
     * @return 文件系统安全名称
     */
    private String safeName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
