package com.jjx.customer.platform.business.orchestration.normalize;

import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.common.util.LlmCallGuard;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import org.springframework.util.StringUtils;

/**
 * 基于 LLM 的查询改写（归一化第二步）。
 * <p>规则归一化（{@link com.jjx.customer.platform.common.util.QueryNormalizer}）先做基础清洗，
 * 本组件再用 LLM 把 query 智能改写成适合检索的陈述句/名词短语（疑问→陈述、指代消解、去冗余），
 * 补上规则式覆盖不到的问法。保留专有名词与原意。</p>
 *
 * <p>LLM 调用失败/异常时降级为输入原样返回，不阻断检索。</p>
 */
@Slf4j
@Service
public class QueryRewriter {

    private static final Duration REWRITE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 反思轮的确定性扩词表（中文）：第 N 轮检索不充分时并入第 N 项提高召回。
     *
     * <p><b>按语言分流</b>：中文查这张表，英文查 {@link #EXPANSIONS_EN}。合着一张表用会让英文
     * query 变成中英夹杂（{@code what is X 处理 流程 步骤 规范}）—— 扩词的本意是"换个说法再查"，
     * 塞进另一种语言的词对 BM25 和 embedding 都是纯噪声，只会越扩越偏。</p>
     *
     * <p>与 agent-framework {@code QueryRewriter.EXPANSIONS} 同一张表——两边检索链的
     * 反思行为应当一致，否则同样的「查不到」在两边会走向不同的补救动作。</p>
     */
    static final List<String> EXPANSIONS_ZH = List.of(
            "处理 流程 步骤 规范",
            "配置 部署 环境 参数",
            "问题 排查 错误 异常");

    /**
     * 反思轮的确定性扩词表（英文）：与 {@link #EXPANSIONS_ZH} 逐项对应。
     *
     * <p>词表内容沿用运维诊断场景的语义（流程 / 配置 / 排查）。<b>这是场景相关的，不是"通用扩词"</b>：
     * 若该链路要服务百科型语料（LiveRAG 这类），这三项几乎不会带来增益，需要按语料重设。</p>
     */
    static final List<String> EXPANSIONS_EN = List.of(
            "process procedure steps guideline",
            "configuration deployment environment parameters",
            "troubleshooting error exception issue");

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
     * 改写（两参重载，无用户补充）。
     *
     * @param query          规则归一化后的 query
     * @param historyContext 最近对话历史（可为空），格式如"用户：xxx\n助手：yyy"
     * @return 改写后的 query（失败或结果为空时返回原 query）
     */
    public String rewrite(String query, String historyContext) {
        return rewrite(query, historyContext, null);
    }

    /**
     * 完整改写：结合历史上下文与用户补充消解指代。
     *
     * <p><b>本方法只负责"怎么改"，不负责"要不要改"。</b>调用方（图内的
     * {@code KbRewriteExecutor}）按改写策略（{@code RewritePolicy}）决定是否调到这里——
     * 线上首轮自包含问题一律透传（LLM 的"疑问→名词短语"重述会换掉 BM25 赖以命中的
     * 字面词），但评测跑对照实验时必须能强制走一次改写，否则"改写有没有用"这个
     * 问题永远测不出来。策略埋在改写器里就等于把对照组焊死了。</p>
     *
     * @param query          规则归一化后的 query
     * @param historyContext 最近对话历史（可为空）
     * @param clarification  用户在追问节点的补充（可为空）
     * @return 改写后的 query（失败或结果为空时返回原 query）
     */
    @TelemetryStep("rag.query.rewrite")
    public String rewrite(String query, String historyContext, String clarification) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        if (chatClient == null || promptStore == null) {
            // 无 LLM 装配（测试替身 / 降级装配）：不做改写，返回原句
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
            StringBuilder userInput = new StringBuilder();
            if (StringUtils.hasText(historyContext)) {
                userInput.append(historyContext).append('\n');
            }
            if (StringUtils.hasText(clarification)) {
                userInput.append("用户补充：").append(clarification.trim()).append('\n');
            }
            userInput.append("当前问题：").append(query);
            String response = LlmCallGuard.call(() -> chatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput.toString())
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
     * 反思轮扩词：检索不充分时并入扩词表提高召回。
     *
     * <p>与首轮的 LLM 改写是两种不同的手段，别混为一谈：首轮改写解决"读不懂"（消解指代），
     * 这里解决"查不到"（换更宽的说法再试一次）。扩词是确定性的——同一条查询在同样的轮次
     * 必然得到同样的结果，可复现、可预期，也不会因为模型这一拍心情不同而漂到别处。</p>
     *
     * <p>并入的词取自与 query <b>同语言</b>的那张表（见 {@link #EXPANSIONS_ZH} / {@link #EXPANSIONS_EN}）：
     * 英文 query 追中文词会把 embedding 拽离语料分布，反而比不扩更差。</p>
     *
     * @param query 上一轮改写后的查询
     * @param round 重写轮次（1 起；超出词表长度时复用最后一项）
     * @return 扩词后的查询
     */
    public String expand(String query, int round) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        List<String> table = containsCjk(query) ? EXPANSIONS_ZH : EXPANSIONS_EN;
        String expansion = table.get(Math.min(Math.max(round, 1) - 1, table.size() - 1));
        if (query.contains(expansion)) {
            return query;
        }
        return query + " " + expansion;
    }

    /**
     * 模型驱动的「拓展思路」（反思轮 round ≥ 1 的补救手段）。
     *
     * <p><b>与 {@link #expand} 的分工</b>：那里是确定性词表拼接——同一条查询在同样轮次必然得到
     * 同样结果，可复现，但扩的词与语料强相关（表里是运维场景的"流程/配置/排查"，服务 LiveRAG
     * 这类百科型语料几乎无增益）。这里让模型看着<b>原始问题</b>与<b>上一轮查询</b>换角度：
     * 换术语、换粒度、换表述。词表退居兜底。</p>
     *
     * <p><b>语言守卫</b>：模型输出与输入查询语言不一致（中英夹杂 / 翻译）一律丢弃并回落词表——
     * 扩词塞进另一种语言的词，对 BM25 和 embedding 都是纯噪声，比不扩更差。</p>
     *
     * @param query            上一轮实际使用的查询
     * @param originalQuestion 用户原始问题（可为空；给模型判断意图用）
     * @param round            重写轮次（1 起；回落到词表时按它取表项）
     * @return 拓展后的查询；模型不可用或输出不合格时回落到确定性词表
     */
    public String expandWithModel(String query, String originalQuestion, int round) {
        String byModel = expandByModel(query, originalQuestion);
        if (byModel != null && sameLanguage(query, byModel)) {
            log.info("[查询拓展][LLM] \"{}\" → \"{}\"", query, byModel);
            return byModel;
        }
        return expand(query, round);
    }

    /**
     * 调模型换角度重写；任何不合格输出（异常/空/与输入同文/超长）返回 null 交由调用方兜底。
     *
     * @param query            上一轮查询
     * @param originalQuestion 原始问题
     * @return 拓展后的查询；不合格时 null
     */
    private String expandByModel(String query, String originalQuestion) {
        if (!StringUtils.hasText(query) || chatClient == null || promptStore == null) {
            return null;
        }
        try {
            String systemPrompt = promptStore.raw("chat/query-expand");
            StringBuilder userInput = new StringBuilder();
            if (StringUtils.hasText(originalQuestion)) {
                userInput.append("原始问题：").append(originalQuestion.trim()).append('\n');
            }
            userInput.append("上一轮查询：").append(query);
            String response = LlmCallGuard.call(() -> chatClient.prompt()
                    .system(systemPrompt)
                    .user(userInput.toString())
                    .call()
                    .content(), REWRITE_TIMEOUT, "查询拓展");
            String expanded = clean(response);
            // 与输入相同 = 模型没换角度（原样再查一次结果不会变）；超长 = 输出跑成了段落
            if (!StringUtils.hasText(expanded) || expanded.equals(query)
                    || expanded.length() > Math.max(200, query.length() * 5)) {
                return null;
            }
            return expanded;
        } catch (Exception e) {
            log.warn("[查询拓展][LLM] 异常，回落确定性扩词: {}", e.getMessage());
            return null;
        }
    }

    /** 两种写法是否同语言（按是否含 CJK 判定）——跨语言输出会同时污染 BM25 字面与 embedding。 */
    private static boolean sameLanguage(String a, String b) {
        return containsCjk(a) == containsCjk(b);
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

    /**
     * 是否为英文（不含 CJK）查询 —— 供调用方按语言分流（扩词表选择、纠错分支）。
     *
     * @param query 待判定查询
     * @return true = 非空白且不含 CJK 表意字符
     */
    public static boolean isEnglish(String query) {
        return StringUtils.hasText(query) && !containsCjk(query);
    }

    /**
     * 英文查询的拼写纠正（<b>不受 {@code RewritePolicy} 约束</b>）。
     *
     * <p>策略管的是"要不要做语义改写"；拼写纠错不是改写——它逐词对应、只替换错拼词、不换表述。
     * 把纠错挂在策略下会漏掉最需要它的一类输入：自包含的 noisy query 在 {@code AUTO}（无上下文）
     * 与 {@code OFF} 下根本不进改写器，错拼原样送进 BM25 与 embedding，字面直接 miss。</p>
     *
     * <p>非英文输入原样返回（中文不走拼写纠正，按策略走 LLM 语义改写分支）。</p>
     *
     * @param query 规则归一化后的查询
     * @return 纠错后的查询
     */
    public String spellFixEnglish(String query) {
        if (!isEnglish(query)) {
            return query;
        }
        return spellFix(query);
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
