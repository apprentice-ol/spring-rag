package com.agentframework.engine.persistence;

import com.agentframework.runtime.session.Cursor;
import java.util.Map;

/**
 * 回滚结果。
 *
 * <p><b>边界声明</b>：回滚只恢复引擎内部状态（游标、槽位、消息、循环计数与收敛观测）。
 * 已经发生的工具调用、人工审批、外部写入与模型费用<b>不会也不能</b>被撤销——
 * {@link #notice()} 会把这一点带回给调用方。</p>
 *
 * @param sessionId     会话 id
 * @param step          回滚到的步号
 * @param cursor        恢复后的游标
 * @param slots         恢复后的槽位快照
 * @param droppedEntries 被截断的后续检查点数量
 * @param notice        副作用边界提示
 */
public record RollbackResult(
        String sessionId,
        int step,
        Cursor cursor,
        Map<String, Object> slots,
        int droppedEntries,
        String notice) {

    /** 固定的副作用边界提示文案。 */
    public static final String SIDE_EFFECT_NOTICE =
            "回滚仅恢复引擎内部状态；已发生的工具调用、人工审批、外部写入与模型费用不可回滚。";

    public RollbackResult {
        // 槽位值允许为 null，不能用 Map.copyOf
        slots = slots == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(slots));
        notice = notice == null ? SIDE_EFFECT_NOTICE : notice;
    }
}
