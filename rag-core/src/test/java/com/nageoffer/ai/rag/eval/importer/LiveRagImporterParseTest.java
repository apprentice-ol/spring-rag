package com.nageoffer.ai.rag.eval.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LiveRAG 导入解析逻辑单测（纯解析，无 Spring 上下文 / 无网络）。
 * 输入样例取自 LiveRAG/Benchmark parquet 经 DuckDB to_json 的真实输出。
 */
class LiveRagImporterParseTest {

    /** 真实样例：Supporting_Documents 是 struct[]，to_json 后为标准 JSON 数组（含转义换行/引号）。 */
    private static final String DOCS_JSON = """
            [{"content":"Life in the Trenches\\nThe ocean vehicle Nereus implodes on a six-mile-deep dive.\\nBy Mackenzie Gerringer \\u201912","doc_id":"<urn:uuid:a102a6cb-a608-493c-928f-d32a0da4dbf6>"},{"content":"Second doc body","doc_id":"<urn:uuid:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb>"}]
            """;

    private static final String CONFIG_JSON = """
            {"answer-control-categorization":"unspecified","answer-type-categorization":"factoid","formulation-categorization":"concise and natural"}
            """;

    @Test
    void parseSupportingDocs_extractsContents() {
        List<String> docs = LiveRagImporter.parseSupportingDocs(DOCS_JSON);
        assertEquals(2, docs.size());
        assertEquals("Life in the Trenches\nThe ocean vehicle Nereus implodes on a six-mile-deep dive.\nBy Mackenzie Gerringer ’12", docs.get(0));
        assertEquals("Second doc body", docs.get(1));
    }

    @Test
    void parseSupportingDocs_handlesEmptyAndMalformed() {
        assertEquals(List.of(), LiveRagImporter.parseSupportingDocs(null));
        assertEquals(List.of(), LiveRagImporter.parseSupportingDocs(""));
        assertEquals(List.of(), LiveRagImporter.parseSupportingDocs("not json"));
        assertEquals(List.of(), LiveRagImporter.parseSupportingDocs("[]"));
        // 缺 content 字段 / content 非文本的元素跳过
        assertEquals(List.of("only"), LiveRagImporter.parseSupportingDocs(
                "[{\"content\":\"only\"},{\"doc_id\":\"<urn:uuid:x>\"},{\"content\":123}]"));
    }

    @Test
    void parseCategory_extractsAnswerType() {
        assertEquals("factoid", LiveRagImporter.parseCategory(CONFIG_JSON));
    }

    @Test
    void parseCategory_fallsBackToQa() {
        assertEquals("qa", LiveRagImporter.parseCategory(null));
        assertEquals("qa", LiveRagImporter.parseCategory(""));
        assertEquals("qa", LiveRagImporter.parseCategory("bad json"));
        // 字段缺失（如 adversarial 题无 answer-type）回落 qa
        assertEquals("qa", LiveRagImporter.parseCategory("{\"premise-categorization\":\"without premise\"}"));
    }
}
