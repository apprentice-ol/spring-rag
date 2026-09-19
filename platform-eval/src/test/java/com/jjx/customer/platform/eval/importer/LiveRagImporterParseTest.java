package com.jjx.customer.platform.eval.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void parseSupportingDocs_extractsContentAndUrnDocId() {
        List<LiveRagImporter.SupportingDoc> docs = LiveRagImporter.parseSupportingDocs(DOCS_JSON);
        assertEquals(2, docs.size());
        assertEquals("Life in the Trenches\nThe ocean vehicle Nereus implodes on a six-mile-deep dive.\nBy Mackenzie Gerringer ’12",
                docs.get(0).content());
        // urn doc_id 必须保留——它是跨题幂等入库的键（同一篇文章会被多道题引用）
        assertEquals("<urn:uuid:a102a6cb-a608-493c-928f-d32a0da4dbf6>", docs.get(0).docId());
        assertEquals("Second doc body", docs.get(1).content());
        assertEquals("<urn:uuid:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb>", docs.get(1).docId());
    }

    @Test
    void parseSupportingDocs_handlesEmptyAndMalformed() {
        assertEquals(List.of(), LiveRagImporter.parseSupportingDocs(null));
        assertEquals(List.of(), LiveRagImporter.parseSupportingDocs(""));
        assertEquals(List.of(), LiveRagImporter.parseSupportingDocs("not json"));
        assertEquals(List.of(), LiveRagImporter.parseSupportingDocs("[]"));
        // 缺 content 字段 / content 非文本的元素跳过；缺 doc_id 的保留文档但 docId 为 null（退化为逐条入库）
        List<LiveRagImporter.SupportingDoc> docs = LiveRagImporter.parseSupportingDocs(
                "[{\"content\":\"only\"},{\"doc_id\":\"<urn:uuid:x>\"},{\"content\":123}]");
        assertEquals(1, docs.size());
        assertEquals("only", docs.get(0).content());
        assertNull(docs.get(0).docId());
    }

    @Test
    void urnHelpers() {
        // 源 urn 的 uuid 段直接作为 doc_id 落库（文档身份 = 内容身份）
        assertEquals("9de87fbb-42b0-41e0-aa91-1ddbc92879e9",
                LiveRagImporter.urnUuid("<urn:uuid:9de87fbb-42b0-41e0-aa91-1ddbc92879e9>"));
        // 文档名里用前 8 位短码
        assertEquals("9de87fbb", LiveRagImporter.urnShort("<urn:uuid:9de87fbb-42b0-41e0-aa91-1ddbc92879e9>"));
        assertEquals("", LiveRagImporter.urnUuid(null));
    }

    @Test
    void difficultyBucketsByIrtQuartiles() {
        // 全量 895 题的四分位切点（实测）
        double[] q = {-2.145, -0.962, 0.238};
        assertEquals("E", LiveRagImporter.difficultyOf(-5.0, q));
        assertEquals("M", LiveRagImporter.difficultyOf(-1.5, q));
        assertEquals("D", LiveRagImporter.difficultyOf(0.0, q));
        assertEquals("HD", LiveRagImporter.difficultyOf(3.0, q));
        // 边界：切点本身归上一档（< 判定）
        assertEquals("M", LiveRagImporter.difficultyOf(-2.145, q));
        assertEquals("HD", LiveRagImporter.difficultyOf(0.238, q));
        // 缺值 / 无切点 → 不定档，不抛异常
        assertNull(LiveRagImporter.difficultyOf(null, q));
        assertNull(LiveRagImporter.difficultyOf(1.0, new double[0]));
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
