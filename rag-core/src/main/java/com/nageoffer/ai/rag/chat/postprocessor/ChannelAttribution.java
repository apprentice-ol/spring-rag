package com.nageoffer.ai.rag.chat.postprocessor;

import com.nageoffer.ai.rag.chat.retrieval.SearchChannelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 通道溯源信息，标记每个 chunk 的来源通道。
 * <p>
 * FusionPostProcessor 做 RRF 融合时，为每个 chunk 记录来自哪些通道及其在各通道中的排名，
 * 供后续处理或调试使用。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class ChannelAttribution {

    /** 来源通道类型 */
    private SearchChannelType channelType;

    /** 在该通道中的排名（1-based） */
    private int rankInChannel;

    /** 在该通道中的原始分数 */
    private Double originalScore;
}
