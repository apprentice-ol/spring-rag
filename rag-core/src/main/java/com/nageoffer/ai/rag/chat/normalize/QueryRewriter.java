package com.nageoffer.ai.rag.chat.normalize;

import com.nageoffer.ai.rag.config.prompt.PromptStore;
import com.nageoffer.ai.rag.chat.util.LlmCallGuard;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import org.springframework.util.StringUtils;

/**
 * 基于 LLM 的查询改写（归一化第二步）。
 * <p>规则归一化（{@link com.nageoffer.ai.rag.common.util.QueryNormalizer}）先做基础清洗，
 * 本组件再用 LLM 把 query 智能改写成适合检索的陈述句/名词短语（疑问→陈述、指代消解、去冗余），
 * 补上规则式覆盖不到的问法。保留专有名词与原意。</p>
 *
 * <p>LLM 调用失败/异常时降级为输入原样返回，不阻断检索。</p>
 */
@Slf4j
@Service
public class QueryRewriter {

    private static final Duration REWRITE_TIMEOUT = Duration.ofSeconds(30);

    private final ChatClient chatClient;
    private final PromptStore promptStore;

    public QueryRewriter(@Qualifier("ingestionChatClient") ChatClient chatClient, PromptStore promptStore) {
        this.chatClient = chatClient;
        this.promptStore = promptStore;
    }

    public String rewrite(String query) {
        return rewrite(query, "");
    }

    /**
     * 用 LLM 改写 query，带入历史对话上下文以消解指代词（它/这个/上面提到的）。
     * 失败或结果为空时返回原 query。
     *
     * @param query          规则归一化后的 query
     * @param historyContext 最近对话历史（可为空），格式如"用户：xxx\n助手：yyy"
     * @return LLM 改写后的 query（或原 query）
     */
    @TelemetryStep("rag.query.rewrite")
    public String rewrite(String query, String historyContext) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        // 英文 query 不走中文改写（净负优化：run57 vs run56 对照 recall@5 -3.5pp），
        // 但做保守的拼写纠正：只修错拼不换表述，输出贴近原文——针对 LiveRAG 故意错拼的 noisy query
        // （run59 失败题 57% 含错拼：mesurement acuracy bottld / beginer abot wat poool 等，BM25 字面直接 miss）
        if (!containsCjk(query)) {
            return spellFix(query);
        }
        try {
            String systemPrompt = promptStore.raw("chat/query-rewrite");
            String userInput = !StringUtils.hasText(historyContext)
                    ? query
                    : historyContext + "\n当前问题：" + query;
            String response = LlmCallGuard.call(() -> chatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput)
                    .call()
                    .content(), REWRITE_TIMEOUT, "查询改写");
            String rewritten = clean(response);
            if (!StringUtils.hasText(rewritten) || rewritten.equals(query)) {
                return query;
            }
            log.info("[查询改写][LLM] \"{}\" → \"{}\"", query, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("[查询改写][LLM] 异常，沿用输入: {}", e.getMessage());
            return query;
        }
    }

    /**
     * 清理 LLM 输出：去首尾配对引号、取首行（防多输出解释）、trim。
     */
    /** 是否含 CJK 表意字符（中日韩统一表意 + 扩展A）。 */
    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3400 && c <= 0x4DBF) {
                return true;
            }
        }
        return false;
    }

    /** 拼写纠正约束 prompt：只修错拼、绝不重述/扩写/翻译（输出贴近原文，负优化风险远小于语义改写）。 */
    private static final String SPELL_FIX_PROMPT = """
            Fix only the spelling and typos in the user's query.
            Rules: keep the original language, word order and meaning; do NOT rephrase,
            expand, answer, or translate; output ONLY the corrected query on a single line.
            If nothing is misspelled, output the query unchanged.""";

    /**
     * 英文 query 拼写纠正（保守）：失败/超时/输出异常时返回原文，不阻断检索。
     * 与被禁用的语义改写的区别：纠错输出与原文逐词对应，只替换错拼词。
     */
    private String spellFix(String query) {
        try {
            String response = LlmCallGuard.call(() -> chatClient.prompt()
                    .system(SPELL_FIX_PROMPT)
                    .user(query)
                    .call()
                    .content(), REWRITE_TIMEOUT, "拼写纠正");
            // 取首行、去引号与前后缀噪声；输出异常（空/超长/换行多段）一律回退原文
            String fixed = response == null ? "" : response.strip();
            int nl = fixed.indexOf('\n');
            if (nl >= 0) {
                fixed = fixed.substring(0, nl);
            }
            fixed = fixed.replaceAll("^[\"'`]+|[\"'`]+$", "").strip();
            if (fixed.isEmpty() || fixed.length() > query.length() * 3 + 20 || fixed.equalsIgnoreCase(query)) {
                return query;
            }
            if (!fixed.equals(query)) {
                log.info("[拼写纠正] \"{}\" → \"{}\"", query, fixed);
            }
            return fixed;
        } catch (Exception e) {
            log.warn("[拼写纠正] 失败降级原文: {}", e.getMessage());
            return query;
        }
    }

    private String clean(String response) {
        if (response == null) {
            return "";
        }
        String s = response.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')
                    || (first == '「' && last == '」') || (first == '“' && last == '”')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl).trim();
        }
        return s;
    }
}
