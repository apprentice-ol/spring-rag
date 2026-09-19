package com.agentframework.definition.codec;

import com.agentframework.definition.ValidationProblem;
import com.agentframework.definition.ValidationReport;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 定义加载器：解析 → 校验 → 规范化，是"外部文档进入框架"的唯一入口。
 *
 * <p>顺序是刻意的：先校验文档头（schemaVersion / kind），再解码，最后跑定义层校验。
 * 有 ERROR 一律不接受入库；WARNING 随结果返回给前端展示。</p>
 */
public final class DefinitionLoader {

    /** 当前支持的文档版本。 */
    public static final int SCHEMA_VERSION = 1;

    private final DefinitionCodec codec;

    /** 使用内置 JSON 编解码构造。 */
    public DefinitionLoader() {
        this(new JsonDefinitionCodec());
    }

    /**
     * @param codec 编解码实现
     */
    public DefinitionLoader(DefinitionCodec codec) {
        if (codec == null) {
            throw new IllegalArgumentException("definition codec is required");
        }
        this.codec = codec;
    }

    /** @return 当前编解码实现 */
    public DefinitionCodec codec() {
        return codec;
    }

    /**
     * 由 JSON 文本加载定义。
     *
     * @param kind 期望的定义种类
     * @param json JSON 文本
     * @return 加载结果
     */
    public LoadResult load(DefinitionKind kind, String json) {
        try {
            return load(kind, JsonSupport.parseObject(json));
        } catch (DefinitionDocumentException e) {
            return rejected(kind, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return rejected(kind, "DEFINITION_PARSE_ERROR", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 由文档加载定义。
     *
     * @param kind     期望的定义种类
     * @param document 文档
     * @return 加载结果
     */
    public LoadResult load(DefinitionKind kind, Map<String, Object> document) {
        if (kind == null) {
            throw new IllegalArgumentException("definition kind is required");
        }
        if (document == null || document.isEmpty()) {
            return rejected(kind, "DEFINITION_PARSE_ERROR", "文档为空");
        }
        Object schemaVersion = document.get("schemaVersion");
        int version = schemaVersion instanceof Number number ? number.intValue() : -1;
        if (version != SCHEMA_VERSION) {
            return rejected(kind, "DEFINITION_SCHEMA_UNSUPPORTED",
                    "不支持的 schemaVersion：" + schemaVersion + "（当前支持 " + SCHEMA_VERSION + "）");
        }
        Optional<DefinitionKind> declared = DefinitionKind.fromWire(
                document.get("kind") == null ? null : String.valueOf(document.get("kind")));
        if (declared.isEmpty() || declared.get() != kind) {
            return rejected(kind, "DEFINITION_KIND_MISMATCH",
                    "文档 kind 与请求不一致：声明=" + document.get("kind") + "，请求=" + kind.wireName());
        }
        Object definition;
        try {
            definition = codec.decode(kind, document);
        } catch (DefinitionDocumentException e) {
            return rejected(kind, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return rejected(kind, "DEFINITION_FIELD_INVALID", String.valueOf(e.getMessage()));
        }
        Map<String, Object> canonical;
        try {
            canonical = codec.encode(kind, definition);
        } catch (RuntimeException e) {
            return rejected(kind, "DEFINITION_ENCODE_ERROR", String.valueOf(e.getMessage()));
        }
        ValidationReport report = validate(kind, definition)
                .merge(ValidationReport.of(unknownFieldWarnings(kind, document)));
        return new LoadResult(kind, definition, canonical, report);
    }

    /**
     * 定义层校验：工作流有完整规则，其余种类做结构校验（深度校验发生在引擎装载期）。
     *
     * @param kind       定义种类
     * @param definition 定义对象
     * @return 报告
     */
    private ValidationReport validate(DefinitionKind kind, Object definition) {
        if (kind == DefinitionKind.WORKFLOW && definition instanceof WorkflowDefinition workflow) {
            return workflow.validateReport();
        }
        return ValidationReport.empty();
    }

    /**
     * @param kind     定义种类
     * @param document 原始文档
     * @return 未识别字段警告
     */
    private List<ValidationProblem> unknownFieldWarnings(DefinitionKind kind, Map<String, Object> document) {
        Set<String> known = codec.knownFields(kind);
        List<ValidationProblem> problems = new ArrayList<>();
        for (String key : new LinkedHashMap<>(document).keySet()) {
            if (!known.contains(key)) {
                problems.add(ValidationProblem.warning("DEFINITION_FIELD_UNKNOWN", "document/" + key,
                        "未识别的顶层字段：" + key));
            }
        }
        return problems;
    }

    /**
     * @param kind    定义种类
     * @param code    错误码
     * @param message 说明
     * @return 拒绝结果
     */
    private LoadResult rejected(DefinitionKind kind, String code, String message) {
        return new LoadResult(kind, null, Map.of(),
                ValidationReport.of(ValidationProblem.error(code, "document", message)));
    }
}
