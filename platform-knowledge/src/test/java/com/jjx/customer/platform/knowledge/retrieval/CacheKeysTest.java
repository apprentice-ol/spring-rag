package com.jjx.customer.platform.knowledge.retrieval;

import com.jjx.customer.platform.knowledge.retrieval.RetrievalBudget;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 缓存 key 构造单测：核心是 eval 参数扫描正确性——同参数必同 key，任一影响结果的参数变必换 key。
 */
class CacheKeysTest {

    private SearchContext ctx() {
        return SearchContext.builder()
                .query("发票冲红怎么操作")
                .rewrittenQuery("增值税发票 冲红 流程")
                .topK(10)
                .threshold(0.0)
                .budget(RetrievalBudget.builder()
                        .recallBudget(20).candidateLimit(40).contextTopK(10).build())
                .collectionId(null)
                .metadata(Map.of("intent", "knowledge_query", "needsWebSearch", false))
                .build();
    }

    @Test
    void 同参数同key() {
        assertEquals(CacheKeys.retrievalKey(ctx(), 1), CacheKeys.retrievalKey(ctx(), 1));
    }

    @Test
    void topK变key变() {
        SearchContext other = ctx();
        other.setTopK(8);
        assertNotEquals(CacheKeys.retrievalKey(ctx(), 1), CacheKeys.retrievalKey(other, 1));
    }

    @Test
    void threshold变key变() {
        SearchContext other = ctx();
        other.setThreshold(0.35);
        assertNotEquals(CacheKeys.retrievalKey(ctx(), 1), CacheKeys.retrievalKey(other, 1));
    }

    @Test
    void budget变key变() {
        SearchContext other = ctx();
        other.setBudget(RetrievalBudget.builder()
                .recallBudget(100).candidateLimit(40).contextTopK(10).build());
        assertNotEquals(CacheKeys.retrievalKey(ctx(), 1), CacheKeys.retrievalKey(other, 1));
    }

    @Test
    void collectionId变key变() {
        SearchContext other = ctx();
        other.setCollectionId(7L);
        assertNotEquals(CacheKeys.retrievalKey(ctx(), 1), CacheKeys.retrievalKey(other, 1));
    }

    @Test
    void restrictedDocIds变key变且顺序无关() {
        SearchContext a = ctx();
        Set<String> s1 = new LinkedHashSet<>(java.util.List.of("d1", "d2", "d3"));
        Set<String> s2 = new LinkedHashSet<>(java.util.List.of("d3", "d1", "d2"));
        a.setRestrictedDocIds(s1);
        SearchContext sameDifferentOrder = ctx();
        sameDifferentOrder.setRestrictedDocIds(s2);
        // 顺序无关：Set 语义一致 → 同 key
        assertEquals(CacheKeys.retrievalKey(a, 1), CacheKeys.retrievalKey(sameDifferentOrder, 1));
        // 内容变 → key 变
        SearchContext other = ctx();
        other.setRestrictedDocIds(new LinkedHashSet<>(java.util.List.of("d1", "d2")));
        assertNotEquals(CacheKeys.retrievalKey(a, 1), CacheKeys.retrievalKey(other, 1));
    }

    @Test
    void intent元数据变key变() {
        SearchContext other = ctx();
        other.setMetadata(Map.of("intent", "EVAL", "needsWebSearch", false));
        assertNotEquals(CacheKeys.retrievalKey(ctx(), 1), CacheKeys.retrievalKey(other, 1));
    }

    @Test
    void docver变key变() {
        assertNotEquals(CacheKeys.retrievalKey(ctx(), 1), CacheKeys.retrievalKey(ctx(), 2));
    }

    @Test
    void query或改写变key变() {
        SearchContext q = ctx();
        q.setQuery("另一个问题");
        assertNotEquals(CacheKeys.retrievalKey(ctx(), 1), CacheKeys.retrievalKey(q, 1));
        SearchContext r = ctx();
        r.setRewrittenQuery("改写后");
        assertNotEquals(CacheKeys.retrievalKey(ctx(), 1), CacheKeys.retrievalKey(r, 1));
    }

    @Test
    void hash_null与空串不同key() {
        assertNotEquals(CacheKeys.hash(null, "a"), CacheKeys.hash("", "a"));
        assertEquals(CacheKeys.hash(null, "a"), CacheKeys.hash(null, "a"));
    }
}
