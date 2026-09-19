package com.jjx.customer.platform.business.orchestration.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 查询改写器的语言分流与拼写纠错边界。
 *
 * <p>这两条都踩过坑：英文 query 被追加中文扩词会让 embedding 拽离语料分布；而拼写纠错挂在
 * {@code RewritePolicy} 下时，自包含的错拼 query（{@code AUTO} 无上下文 / {@code OFF}）根本
 * 进不了改写器，错拼原样送检索。用例把这两条钉住。</p>
 */
class QueryRewriterTest {

    /** 无 LLM 装配的改写器：走降级路径，不需要 Spring 上下文。 */
    private static QueryRewriter rewriter() {
        return new QueryRewriter(null, null);
    }

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff\\u3400-\\u4dbf]");

    @Test
    void 英文查询扩词后不应含中文() {
        String query = "what is cellular respiration equation reactants";
        String expanded = rewriter().expand(query, 1);

        assertFalse(CJK.matcher(expanded).find(),
                "英文 query 扩词后混入了中文，会污染 embedding。实际=" + expanded);
        assertTrue(expanded.startsWith(query), "原查询必须完整保留在扩词结果里。实际=" + expanded);
        assertTrue(expanded.length() > query.length(), "应当追加了英文扩词。实际=" + expanded);
    }

    @Test
    void 中文查询扩词应追加中文() {
        String expanded = rewriter().expand("发票冲红失败怎么处理", 1);
        assertTrue(expanded.contains("处理 流程 步骤 规范"), "实际=" + expanded);
    }

    @Test
    void 扩词按轮次取表_超出长度复用最后一项() {
        QueryRewriter rewriter = rewriter();
        assertTrue(rewriter.expand("发票冲红失败怎么处理", 2).contains("配置 部署 环境 参数"));
        assertTrue(rewriter.expand("发票冲红失败怎么处理", 9).contains("问题 排查 错误 异常"));

        assertTrue(rewriter.expand("how to parse invoice", 2)
                .contains("configuration deployment environment parameters"));
        assertTrue(rewriter.expand("how to parse invoice", 9)
                .contains("troubleshooting error exception issue"));
    }

    @Test
    void 无LLM装配时拓展思路回落确定性词表_英文不混中文() {
        String query = "what is cellular respiration equation reactants";
        String expanded = rewriter().expandWithModel(query, query, 1);

        assertFalse(CJK.matcher(expanded).find(),
                "模型不可用时回落词表，同样不得混入中文。实际=" + expanded);
        assertTrue(expanded.startsWith(query), "原查询必须完整保留。实际=" + expanded);
    }

    @Test
    void 无LLM装配时拓展思路回落确定性词表_中文按轮次取表() {
        String query = "发票冲红失败怎么处理";
        assertTrue(rewriter().expandWithModel(query, query, 2).contains("配置 部署 环境 参数"));
        assertTrue(rewriter().expandWithModel(query, query, 1).contains("处理 流程 步骤 规范"));
    }

    @Test
    void 语言判定() {
        assertTrue(QueryRewriter.isEnglish("what is cellular respiration"));
        assertFalse(QueryRewriter.isEnglish("什么是细胞呼吸"));
        assertFalse(QueryRewriter.isEnglish("What is 发票"), "中英混排按非英文处理");
        assertFalse(QueryRewriter.isEnglish(""));
        assertFalse(QueryRewriter.isEnglish(null));
    }

    @Test
    void 中文查询不走拼写纠正() {
        String query = "发票冲红失败怎么处理";
        assertEquals(query, rewriter().spellFixEnglish(query));
    }

    @Test
    void 无LLM装配时英文纠错降级为原文() {
        String query = "what is cellular respiration";
        assertEquals(query, rewriter().spellFixEnglish(query),
                "chatClient 为 null 时应降级返回原文，不得抛异常或返回空");
    }
}
