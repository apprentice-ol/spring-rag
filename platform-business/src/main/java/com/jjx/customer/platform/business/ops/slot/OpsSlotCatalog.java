package com.jjx.customer.platform.business.ops.slot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * ops 诊断的槽位目录（流程输入契约）。
 *
 * <p><b>必填按阶段分级</b>（相对参考实现的有意偏离）：入口只必填
 * {@code environment / interface / time} —— 这三项是"能去查日志"的最低要求；
 * {@code error / payload} 降为选填，它们真正被需要是在第二阶段（生成/纠正报文），
 * 届时由模型用 {@code ask_user} 追问。参考实现把 5 项全设为入口必填，
 * 会让用户为了"查个日志"先贴一大段报文，实测体验很差。</p>
 *
 * <p><b>自主补全策略</b>（auto-resolve 层，问用户之前先自救）：每个槽位声明
 * {@code defaultValue}（缺失时的确定缺省值）、{@code inferable}（允许 LLM 从上下文推断）、
 * {@code resolvable}（允许反查日志补全）。三者全关的槽位（如 symptoms）只能问用户——
 * 自主性的边界由目录声明，不散落在执行器代码里。</p>
 *
 * <p>目录顺序即一次问齐的拼接顺序；{@code options} 非空的槽位在前端渲染为点选项。</p>
 */
public final class OpsSlotCatalog {

    /** 槽位名：环境。 */
    public static final String ENVIRONMENT = "environment";
    /** 槽位名：接口。 */
    public static final String INTERFACE = "interface";
    /** 槽位名：时间。 */
    public static final String TIME = "time";
    /** 槽位名：错误信息。 */
    public static final String ERROR = "error";
    /** 槽位名：请求报文。 */
    public static final String PAYLOAD = "payload";
    /** 槽位名：响应报文（业务系统返回了什么）。 */
    public static final String RESPONSE = "response";
    /** 槽位名：补充现象。 */
    public static final String SYMPTOMS = "symptoms";
    /** 槽位名：链路 id。 */
    public static final String TRACE_ID = "trace_id";

    /**
     * 目录内全部槽位名（顺序同 {@link #ALL}）。
     *
     * <p>给 {@code ActExecutor}（workflow/common 共用层）做 ask_user 合法性校验用——
     * 共用层只认这个名字清单，不反向依赖 ops 目录类型。</p>
     *
     * @return 槽位名列表（不可变）
     */
    public static List<String> askableNames() {
        return ALL.stream().map(Spec::name).toList();
    }

    /** 完整目录（顺序 = 一次问齐的拼接顺序）。 */
    public static final List<Spec> ALL = List.of(
            new Spec(ENVIRONMENT, true, "环境是正式还是测试？", "prod / test / dev / uat",
                    OpsSlotCatalog::normalizeEnvironment,
                    "环境。取值限定 prod / test / dev / uat（\"正式/生产/线上\"= prod；\"测试\"= test；\"开发\"= dev；\"预发/uat\"= uat）",
                    List.of("prod", "test", "dev", "uat"))
                    .withAuto(null, true, false),          // INFER：「线上出问题」→ prod
            new Spec(INTERFACE, true, "调用的是哪个接口？", "接口路径或业务名称，如 /api/invoice/reverse 或 发票冲红",
                    null,
                    "出问题的接口。**路径（/api/invoice/reverse）或业务名称（\"发票冲红接口\"\"下单接口\"）都算**，"
                            + "用户怎么叫就怎么填；能从上下文推断出接口名时也要填",
                    List.of())
                    .withAuto(null, true, true),           // INFER + RESOLVE：报文/错误提路径；日志反查接口名
            new Spec(TIME, true, "报错大约发生在什么时间？", "如 2026-09-15 14:00，也可写「最近1小时」", null,
                    "报错发生时间（ISO-8601；\"今天下午2点\"\"最近半小时\"这类相对时间照原样抽取，由后续阶段换算）",
                    List.of("最近1小时", "最近24小时"))
                    .withAuto("最近30分钟", true, false),   // DEFAULT + INFER：缺省最近30分钟；「昨天」等换算成 ISO 窗口
            new Spec(ERROR, false, "报错信息是什么？（选填）", "错误码 / 错误消息 / 堆栈片段", null,
                    "报错信息原文（错误码/错误消息/异常堆栈片段）；用户没提就别猜",
                    List.of())
                    .withAuto(null, true, false),          // INFER：从报文/日志错误行提取
            new Spec(PAYLOAD, false, "有请求报文吗？（选填，也可稍后提供）", "JSON 文本", null,
                    "完整请求报文（JSON 文本原样保留）；**不确定就不要填**，报文在生成/纠正阶段才会被要求",
                    List.of())
                    .withAuto(null, false, true),          // RESOLVE：trace_id 精查日志提取请求报文
            new Spec(RESPONSE, false, "业务系统返回了什么？（选填）", "响应报文 / 返回码 / 返回消息", null,
                    "业务系统的响应原文（返回报文/返回码/返回消息）；**只有日志里明确是响应才填**，"
                            + "没挖到就不要猜——诊断要看「系统真实返回了什么」而不是用户以为返回了什么",
                    List.of())
                    .withAuto(null, false, true),          // RESOLVE：日志里写明「响应/返回」的行
            new Spec(SYMPTOMS, false, "还有什么补充现象？", "如：偶发 / 全部失败 / 返回超时", null,
                    "补充现象描述（\"偶发\"\"全部失败\"\"返回超时\"等）",
                    List.of()),                            // 不自主补全：主观补充只能问用户
            new Spec(TRACE_ID, false, "有可反查日志的关键数据吗？", "traceId / 流水号 / orderNo / requestId 等唯一性标识，粘贴任一都行", null,
                    "任何能精准反查日志的唯一性标识：traceId（32 位 hex，可从应用日志 [traceId,spanId] 复制）、"
                            + "业务流水号、orderNo、requestId 等；是标准 traceId 走全链路精查，其他键当关键字模糊查",
                    List.of())
                    .withAuto(null, false, true));         // RESOLVE：接口名/错误码当关键字反查日志

    /** 目录内全部槽位名（外部预填白名单）。 */
    public static final Set<String> NAMES = ALL.stream().map(Spec::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private OpsSlotCatalog() {
    }

    /**
     * environment 别名归一（正式/生产/线上 → prod 等）；无法识别返回 null。
     *
     * @param value 原始值
     * @return 归一键（prod/test/dev/uat），无法识别返回 null
     */
    public static String normalizeEnvironment(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase();
        if (Set.of("prod", "production", "正式", "生产", "线上", "正式环境", "生产环境").contains(v)) {
            return "prod";
        }
        if (Set.of("test", "testing", "测试", "测试环境", "sit").contains(v)) {
            return "test";
        }
        if (Set.of("dev", "develop", "development", "开发", "开发环境").contains(v)) {
            return "dev";
        }
        if (Set.of("uat", "预发", "预生产", "灰度").contains(v)) {
            return "uat";
        }
        return null;
    }

    /**
     * 把业务七槽声明进工作流（全流程输入契约随目录归位，不再散在工厂方法里）。
     *
     * @param wf 工作流构建器
     */
    public static void declareBusinessSlots(com.agentframework.definition.workflow.WorkflowBuilder wf) {
        wf.slot(ENVIRONMENT, com.agentframework.definition.workflow.SlotType.STRING)   // 业务七槽（全流程输入契约）
          .slot(INTERFACE, com.agentframework.definition.workflow.SlotType.STRING)
          .slot(TIME, com.agentframework.definition.workflow.SlotType.STRING)
          .slot(ERROR, com.agentframework.definition.workflow.SlotType.STRING)
          .slot(PAYLOAD, com.agentframework.definition.workflow.SlotType.STRING)
          .slot(RESPONSE, com.agentframework.definition.workflow.SlotType.STRING)
          .slot(SYMPTOMS, com.agentframework.definition.workflow.SlotType.STRING)
          .slot(TRACE_ID, com.agentframework.definition.workflow.SlotType.STRING);
    }

    /**
     * 只保留目录内槽位（外部预填如 traceId 走这里，避免垃圾进上下文）。
     *
     * @param raw 外部原始键值
     * @return 过滤后的保序键值（不做归一化，归一在执行器侧应用）
     */
    public static Map<String, String> sanitized(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((key, value) -> {
                if (NAMES.contains(key) && value != null && !value.isBlank()) {
                    out.put(key, value);
                }
            });
        }
        return out;
    }

    /**
     * 渲染抽槽 prompt 的槽位目录（每行 {@code - name（必填|选填）：取值说明}）。
     *
     * @return 目录文本
     */
    public static String renderForExtraction() {
        return ALL.stream()
                .map(spec -> "- " + spec.name() + (spec.required() ? "（必填）" : "（选填）")
                        + "：" + (spec.extractionHint() != null && !spec.extractionHint().isBlank()
                        ? spec.extractionHint() : spec.question()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * 单个槽位契约。
     *
     * @param name          槽位名
     * @param required      是否必填（入口阶段的判定依据）
     * @param question      问句（一次问齐的文案来源，空则回退槽位名）
     * @param hint          输入提示
     * @param normalizer    声明式归一（null = 不归一；返回 null 视为无法识别，保留原值）
     * @param extractionHint 抽槽提示（null = 用 question）
     * @param options       候选值（非空时前端渲染为点选按钮，用户不必打字）
     * @param defaultValue  缺省值（缺失时直接生效，不问用户不耗 LLM；null = 无缺省）
     * @param inferable     允许 LLM 从上下文推断（高置信才生效，落 provenance）
     * @param resolvable    允许反查日志补全（query_logs 关键字反查 / trace_id 精查）
     */
    public record Spec(String name, boolean required, String question, String hint,
                       UnaryOperator<String> normalizer, String extractionHint, List<String> options,
                       String defaultValue, boolean inferable, boolean resolvable) {

        /** 兼容构造：无自主补全（只能问用户）。 */
        public Spec(String name, boolean required, String question, String hint,
                UnaryOperator<String> normalizer, String extractionHint, List<String> options) {
            this(name, required, question, hint, normalizer, extractionHint, options, null, false, false);
        }

        /**
         * 声明自主补全策略（auto-resolve 层消费）。
         *
         * @param defaultValue 缺省值（null = 无）
         * @param inferable    允许 LLM 推断
         * @param resolvable   允许日志反查
         * @return 带策略的槽位契约
         */
        public Spec withAuto(String defaultValue, boolean inferable, boolean resolvable) {
            return new Spec(name, required, question, hint, normalizer, extractionHint, options,
                    defaultValue, inferable, resolvable);
        }

        /** @return 归一化后的值（无 normalizer 或无法识别时返回原值） */
        public String normalize(String value) {
            if (normalizer == null || value == null) {
                return value;
            }
            String normalized = value.isBlank() ? value : normalizer.apply(value.trim());
            return normalized == null ? value : normalized;
        }
    }
}
