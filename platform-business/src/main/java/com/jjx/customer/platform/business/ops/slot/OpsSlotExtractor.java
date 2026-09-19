package com.jjx.customer.platform.business.ops.slot;

import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 槽位抽取器：一次 LLM 调用把自由文本解析成槽位键值，已确认值优先、只填空缺（Merger 语义）。
 *
 * <p>对齐 springai-rag {@code DefaultWorkflowDriver.extractSlotsIfNeeded} 的解析契约：
 * 模型输出一行 JSON 对象（槽位名为 key，识别不出填 null）；解析失败按已知槽位继续，不阻断。
 * 无可用模型时跳过抽取（零 LLM 成本），与参考实现「模型不可用即不抽」一致。</p>
 */
public class OpsSlotExtractor {

    /**
     * 抽槽 system prompt。
     *
     * <p>与参考实现的 {@code workflow/ops_diagnose_v2/slot-extract} 的一处**有意偏离**：
     * 参考实现的第一条规则是「只抽取消息中明确给出的信息，宁缺勿猜」，实测过于保守——
     * 用户说"发票冲红接口报错"时，模型会因为"发票冲红"不是接口路径而放过 {@code interface}，
     * 于是回头追问用户刚说过的东西。改为「能确定的线索一律抽出来，宁多勿漏」，
     * 把模糊线索也抽出来交给后续阶段（真要不准，第二阶段还能用 {@code ask_user} 纠正）。</p>
     */
    static final String SLOT_EXTRACT_PROMPT = """
            你是流程的信息收集器，目标是从用户消息里**尽可能多地**提取已知线索，减少后续追问。
            输出一行 JSON（不要 markdown 围栏、不要解释）：以各槽位名为 key，确实无法确定的槽位填 null。

            ## 规则
            1. **能从消息推断出来的就填**：宁多勿漏。用户用口语描述（"发票冲红接口""昨天下午"）也算线索
            2. 接口的**业务名称**与路径同等有效：用户说"发票冲红""下单接口"就照原话填 interface
            3. 相对时间照原样填（"最近1小时""今天下午2点"），不要换算、不要丢弃
            4. 多条信息并存时全部抽取
            5. 已在「已确认槽位」中的值不要重复抽取（保持原值，历史轮次已确认的优先）
            6. 只有用户**完全没提**、也无法从上下文推断的槽位才填 null
            7. 槽位取值说明见「槽位目录」""";

    private static final Logger log = LoggerFactory.getLogger(OpsSlotExtractor.class);

    private final SingleTurnModel model;

    private final ObjectMapper objectMapper;

    /**
     * @param model        模型入口（null 表示不可用，抽取直接跳过）
     * @param objectMapper JSON 解析
     */
    public OpsSlotExtractor(SingleTurnModel model, ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
    }

    /** @return 模型是否可用 */
    public boolean available() {
        return model != null;
    }

    /**
     * 从自由文本抽取槽位，只填当前为空的槽位（已确认值优先）。
     *
     * @param text      用户消息（原始问题或补充答复）
     * @param confirmed 当前已确认的槽位值（不会被覆盖）
     * @return 待写入的槽位键值（已应用归一化；失败或不可用时返回空 Map，不阻断）
     */
    public Map<String, String> extract(String text, Map<String, String> confirmed) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean anyBlank = OpsSlotCatalog.ALL.stream()
                .anyMatch(spec -> isBlank(confirmed.get(spec.name())));
        if (model == null || isBlank(text) || !anyBlank) {
            return out;
        }
        String system = SLOT_EXTRACT_PROMPT
                + "\n\n## 槽位目录\n" + OpsSlotCatalog.renderForExtraction()
                + "\n\n## 已确认槽位（不要重复抽取，保持原值）\n" + toJson(confirmed);
        Map<String, Object> extracted;
        try {
            extracted = parseOneLineJson(model.ask(system, text));
        } catch (Exception e) {
            log.warn("[ops-slots] 抽槽失败（按已知槽位继续）：{}", e.getMessage());
            return out;
        }
        if (extracted == null) {
            return out;
        }
        for (OpsSlotCatalog.Spec spec : OpsSlotCatalog.ALL) {
            if (!isBlank(confirmed.get(spec.name()))) {
                continue;
            }
            Object value = extracted.get(spec.name());
            if (value == null || isNullLiteral(value)) {
                continue;
            }
            String normalized = spec.normalize(String.valueOf(value));
            if (normalized != null && !normalized.isBlank()) {
                out.put(spec.name(), normalized);
            }
        }
        return out;
    }

    /**
     * 解析一行 JSON 对象（剥离 markdown 围栏后解析首个 JSON 对象）。
     *
     * @param raw 模型输出
     * @return 键值表，无法解析返回 null
     */
    private Map<String, Object> parseOneLineJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            return null;
        }
        try {
            return objectMapper.readValue(text.substring(braceStart, braceEnd + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Map<String, String> confirmed) {
        try {
            return objectMapper.writeValueAsString(confirmed == null ? Map.of() : confirmed);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean isNullLiteral(Object value) {
        return value instanceof String s && "null".equalsIgnoreCase(s.trim());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
