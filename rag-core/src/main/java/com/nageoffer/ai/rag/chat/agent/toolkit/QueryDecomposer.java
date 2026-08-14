package com.nageoffer.ai.rag.chat.agent.toolkit;

import com.nageoffer.ai.rag.common.util.JsonResponseParser;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import com.nageoffer.ai.obs.TraceStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 查询分解器（Plan-Execute 范式用）。
 * <p>用 LLM 把一个多跳/复合问题拆成若干子查询，每条独立检索后合并。
 * 结构仿 {@link com.nageoffer.ai.rag.chat.normalize.QueryRewriter QueryRewriter}：
 * ingestionChatClient + PromptStore + JsonResponseParser，失败/单跳回退 {@code List.of(query)}。
 *
 * <p>注意：刻意不改 QueryRewriter 的返回类型（它是链路既有契约 String），decompose 是独立能力。
 */
@Slf4j
@Service
public class QueryDecomposer {

    private final ChatClient chatClient;
    private final PromptStore promptStore;

    public QueryDecomposer(@Qualifier("ingestionChatClient") ChatClient chatClient, PromptStore promptStore) {
        this.chatClient = chatClient;
        this.promptStore = promptStore;
    }

    /**
     * 把复合问题拆成子查询列表。
     *
     * @return 子查询列表；单跳/异常时返回 {@code List.of(query)}（永不返回空、永阻断检索）
     */
    @TraceStep("rag.query.decompose")
    public List<String> decompose(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            String system = promptStore.raw("agent/decompose");
            String response = chatClient.prompt()
                    .system(system)
                    .user(query)
                    .call()
                    .content();
            List<String> subs = JsonResponseParser.parseStringList(response);
            List<String> cleaned = subs.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .filter(s -> !s.equalsIgnoreCase(query.trim()))
                    .toList();
            if (cleaned.isEmpty()) {
                log.debug("[查询分解] 未拆出子查询（可能单跳），返回原 query: {}", query);
                return List.of(query);
            }
            log.info("[查询分解] \"{}\" → {}", query, cleaned);
            return cleaned;
        } catch (Exception e) {
            log.warn("[查询分解] 异常，返回原 query: {}", e.getMessage());
            return List.of(query);
        }
    }
}
