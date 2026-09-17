package com.jjx.customer.platform.agent.framework.result;

import java.util.List;
import java.util.Map;

/**
 * 追问（CLARIFY）的结构化原因：缺哪些槽位、停在哪个阶段、已确认哪些槽位。
 *
 * <p>由驱动在产出追问时携带（引擎据此落会话，交付层据此渲染追问卡片），
 * 使用方不必再用自己的槽位目录重算一遍缺失项。</p>
 *
 * @param stage        触发追问的位置（槽位澄清 = {@code collect_slots}；阶段内追问 = 阶段名）
 * @param slots        已确认槽位（只含流程声明的键）
 * @param missingSlots 尚缺的必填槽位
 */
public record ClarifyInfo(String stage, Map<String, String> slots, List<String> missingSlots) {

    public ClarifyInfo {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }
}
