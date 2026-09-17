package com.jjx.customer.platform.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 意图分类结果（2026-09-12 框架化批次 2 去域感知改造）。
 *
 * <p>只保留与具体能力域解耦的字段：域标识 + 置信度 + 联网标记。域清单可动态扩展——
 * workflow 域由各域 RouteRule.domainDescriptor() 注册（自动进分类 prompt），新增域零字段改动。
 * 旧布尔字段（needsRetrieval/needsDiagnose）废除：检索需求是域的派生属性，
 * 诊断路由由 ops 域规则按 domain 值判断。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentResult {

    /** 基础域：知识检索（默认兜底域） */
    public static final String DOMAIN_KNOWLEDGE = "knowledge";
    /** 基础域：联网搜索（实时信息） */
    public static final String DOMAIN_WEB_SEARCH = "web_search";
    /** 基础域：问候 */
    public static final String DOMAIN_GREETING = "greeting";
    /** 基础域：闲聊 */
    public static final String DOMAIN_CHITCHAT = "chitchat";

    /** 非检索域（直接回复类）；knowledge/web_search 与各 workflow 域均需检索或自治编排 */
    private static final Set<String> NON_RETRIEVAL_DOMAINS = Set.of(DOMAIN_GREETING, DOMAIN_CHITCHAT);

    /** 意图域：knowledge / web_search / greeting / chitchat + 各 workflow 域（如 ops_diagnose） */
    private String domain;

    /** LLM 给出的置信度分数（0~1） */
    private double confidence;

    /** 是否需要联网搜索（检索模式参数，非域——进检索缓存 key） */
    private boolean needsWebSearch;

    /** 分类理由说明 */
    private String reason;

    /** 是否需要知识库检索（派生：域不在非检索域集合即 true；null 域兜底 true 走默认链）。 */
    public boolean needsRetrieval() {
        return domain == null || !NON_RETRIEVAL_DOMAINS.contains(domain);
    }
}
