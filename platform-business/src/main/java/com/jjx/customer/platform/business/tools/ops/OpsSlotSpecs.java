package com.jjx.customer.platform.business.tools.ops;


import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** ops 诊断的槽位目录（流程输入契约）：前 5 项必填 = 排障最低要求。 */
public final class OpsSlotSpecs {

    public static final String ENVIRONMENT = "environment";
    public static final String INTERFACE = "interface";
    public static final String TIME = "time";
    public static final String ERROR = "error";
    public static final String PAYLOAD = "payload";
    public static final String SYMPTOMS = "symptoms";
    public static final String TRACE_ID = "trace_id";

    public static final List<SlotSpec> ALL = List.of(
            new SlotSpec(ENVIRONMENT, true, "环境是正式还是测试？", "prod / test / dev / uat",
                    OpsSlotSpecs::normalizeEnvironment,
                    "环境。取值限定 prod / test / dev / uat（\"正式/生产/线上\"= prod；\"测试\"= test；\"开发\"= dev；\"预发/uat\"= uat）"),
            new SlotSpec(INTERFACE, true, "调用的是哪个接口？", "接口路径或名称，如 /api/invoice/reverse",
                    null, "出问题的接口（路径或名称，如 /api/invoice/reverse）"),
            new SlotSpec(TIME, true, "报错大约发生在什么时间？", "如 2026-08-27 14:00",
                    null, "报错发生时间（ISO-8601；\"今天下午2点\"这类相对时间按当前对话时间换算）"),
            new SlotSpec(ERROR, true, "报错信息是什么？", "错误码 / 错误消息 / 堆栈片段",
                    null, "报错信息原文（错误码/错误消息/异常堆栈片段）"),
            new SlotSpec(PAYLOAD, true, "能提供完整的请求报文吗？", "JSON 文本",
                    null, "完整请求报文（JSON 文本原样保留；只有片段时照原样保留，完整性由后续校验判断）"),
            new SlotSpec(SYMPTOMS, false, "还有什么补充现象？", "如：偶发 / 全部失败 / 返回超时",
                    null, "补充现象描述（\"偶发\"\"全部失败\"\"返回超时\"等）"),
            new SlotSpec(TRACE_ID, false, "有 traceId 吗？（可从应用日志 [traceId,spanId] 复制）", "32 位 hex",
                    null, null));

    public static final Set<String> NAMES = ALL.stream().map(SlotSpec::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private OpsSlotSpecs() {
    }

    /** 只保留目录内槽位（外部预填如 traceId 走这里，避免垃圾进上下文）。 */
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

    /** environment 别名归一（正式/生产/线上 → prod 等）；无法识别返回 null。 */
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
}
