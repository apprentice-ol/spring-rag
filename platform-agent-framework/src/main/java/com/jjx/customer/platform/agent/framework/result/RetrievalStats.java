package com.jjx.customer.platform.agent.framework.result;

import java.util.List;

/**
 * 检索明细（eval 的 Recall@k 等指标用）。
 *
 * @param recalled  召回条数
 * @param returned  进上下文条数
 * @param channels  参与的检索通道
 * @param topScore  最高分（可空）
 */
public record RetrievalStats(int recalled, int returned, List<String> channels, Double topScore) {

    public RetrievalStats {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }
}
