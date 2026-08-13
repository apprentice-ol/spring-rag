package com.nageoffer.ai.rag.chat.normalize;

import com.nageoffer.ai.rag.config.prompt.PromptStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.nageoffer.ai.rag.config.telemetry.TraceStep;
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
    @TraceStep("rag.query.rewrite")
    public String rewrite(String query, String historyContext) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        try {
            String systemPrompt = promptStore.raw("chat/query-rewrite");
            String userInput = !StringUtils.hasText(historyContext)
                    ? query
                    : historyContext + "\n当前问题：" + query;
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput)
                    .call()
                    .content();
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
