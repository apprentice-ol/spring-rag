package com.jjx.customer.platform.business.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 身份目录（静态声明，替代旧内核的 {@code AgentRegistry} 运行期注册表）。
 *
 * <p>新内核的 {@code AgentDefinition} 只描述执行绑定（workflow/model/策略），业务元数据
 * （label / 意图域 / 能力位 / prompt 分层清单）在此集中声明，供：</p>
 * <ul>
 *   <li>{@code PromptFingerprintResolver}——按范式取全层 key 算指纹；</li>
 *   <li>{@code PromptBindingService}——绑定必需 key 的来源（基线/绑定校验）；</li>
 *   <li>{@code AgentRegistryController}——/agent/registry 能力清单投影；</li>
 *   <li>{@code ChatOrchestrator}——agentType 字符串语义（与旧 ID 常量逐字一致）。</li>
 * </ul>
 */
public final class AgentCatalog {

    /** 链路级常驻 prompt key（跨域输出契约，参与指纹但不属于任何 agent）。 */
    public static final List<String> LINK_KEYS = List.of("chat/pipeline/rag-answer-system");

    /**
     * 目录条目。
     *
     * @param id                Agent 标识（= 路由 agentType = AgentDefinition id）
     * @param label             展示名
     * @param description       一句话职责
     * @param intentDomain      意图域（意图分类的输出值域）
     * @param workflowId        绑定的 WorkflowDefinition id
     * @param capabilities      能力位字符串清单（registry 投影用；运行行为由业务层自持，不再三方求交）
     * @param agentPromptKeys   Agent 人格层 prompt key
     * @param workflowPromptKeys Workflow 任务层 prompt key
     * @param answerPromptKey   生成段 prompt key（可空：ops 直答不需要）
     */
    public record Entry(String id, String label, String description, String intentDomain,
                        String workflowId, List<String> capabilities,
                        List<String> agentPromptKeys, List<String> workflowPromptKeys,
                        String answerPromptKey) {

        /** 指纹与绑定校验用的全层 key（agent 层 + workflow 层 + answer，不含 link 层）。 */
        public List<String> allPromptKeys() {
            List<String> keys = new ArrayList<>(agentPromptKeys);
            keys.addAll(workflowPromptKeys);
            if (answerPromptKey != null) {
                keys.add(answerPromptKey);
            }
            return List.copyOf(keys);
        }
    }

    /** 运维诊断（直答交付，无流式/引用能力位）。 */
    public static final Entry OPS = new Entry(
            "ops_diagnose", "运维诊断（框架主线）",
            "先追问补齐槽位，再按固定排查骨架分阶段调工具（查日志→查文档/生成报文→确定性校验）",
            "ops_diagnose", "ops_diagnose_v2", List.of(),
            List.of("agent/ops/investigate", "agent/ops/resolve", "agent/ops/verify"),
            List.of("workflow/ops_diagnose_v2/slot-extract", "workflow/ops_diagnose_v2/replan"),
            null);

    /** 知识问答（单次多通道检索直出）。 */
    public static final Entry KNOWLEDGE = new Entry(
            "knowledge", "知识问答（框架主线）",
            "单次多通道检索直出答案，速度最快（eval 基线）",
            "knowledge", "knowledge_qa",
            List.of("STREAMING", "CITATIONS", "ANSWER_CACHE", "SEMANTIC_CACHE", "RETRIEVAL_METRICS"),
            List.of(),
            List.of(),
            "chat/pipeline/rag-answer-kb");

    /** 工具循环检索（react_loop 轴）。 */
    public static final Entry REACT = new Entry(
            "react_loop", "工具循环检索（框架主线）",
            "模型自主循环：检索→评分→重排→决定何时停（工具循环节点）",
            "react_loop", "knowledge_qa_react",
            List.of("STREAMING", "CITATIONS", "ANSWER_CACHE", "SEMANTIC_CACHE", "RETRIEVAL_METRICS"),
            List.of("agent/react-loop"),
            List.of(),
            "chat/pipeline/rag-answer-kb");

    private static final List<Entry> ALL = List.of(OPS, KNOWLEDGE, REACT);

    /**
     * 历史范式别名：这些 id 已不在 {@link #ALL} 里，但旧评测参数快照、旧脚本、
     * 绕过前端的直接调用仍在传。在此解析到当前范式，**否则会静默降级成 knowledge**
     * （见 {@code KnowledgeRunner} 的 {@code byId(...).orElse(KNOWLEDGE)}）——
     * 传 react 却跑了知识检索，而结果里还记着 paradigm=react，最难查的一类问题。
     */
    private static final Map<String, String> ALIASES = Map.of(
            "naive", KNOWLEDGE.id(),
            "react", REACT.id());

    private AgentCatalog() {
    }

    /** @return 全部目录条目（声明顺序即 /agent/registry 展示顺序） */
    public static List<Entry> all() {
        return ALL;
    }

    /**
     * @param id Agent 标识（可为历史别名，见 {@link #ALIASES}）
     * @return 目录条目
     */
    public static Optional<Entry> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String resolved = ALIASES.getOrDefault(id, id);
        return ALL.stream().filter(e -> e.id().equals(resolved)).findFirst();
    }
}
