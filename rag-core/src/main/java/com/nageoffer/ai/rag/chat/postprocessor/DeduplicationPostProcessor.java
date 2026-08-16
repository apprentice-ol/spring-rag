package com.nageoffer.ai.rag.chat.postprocessor;

import cn.hutool.crypto.digest.DigestUtil;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelResult;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;

/**
 * 检索结果去重处理器（责任链第一环）。
 * <p>
 * 按内容全文 SHA-256 去重，保留首次出现的条目（不用前缀截断，避免前缀相同的同章节段被误判重复）。
 * 多通道检索时同一文档可能被多个通道命中，需要去重后再融合。
 * </p>
 */
@Component
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "dedup";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    @TelemetryStep("rag.postproc")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        return chunks.stream()
                .filter(c -> seen.add(key(c.getContent())))
                .toList();
    }

    private String key(String content) {
        return DigestUtil.sha256Hex(content == null ? "" : content);
    }
}
