package com.nageoffer.ai.rag.chat.retrieval;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 联网检索通道（桩实现）。
 * <p>
 * 当配置 rag.search.channels.web-search.enabled=true 时启用。
 * 当前为桩实现，返回空结果；实际接入时可对接 Serper / Bing Search / 百炼搜索 API。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.search.channels.web-search.enabled", havingValue = "true", matchIfMissing = false)
public class WebSearchChannel implements SearchChannel {

    @Override
    public String getName() {
        return "web-search";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.WEB_SEARCH;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long t0 = System.currentTimeMillis();
        log.debug("[WebSearchChannel] query={} (stub - 返回空)", context.getQuery());

        return SearchChannelResult.builder()
                .channelType(SearchChannelType.WEB_SEARCH)
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(System.currentTimeMillis() - t0)
                .build();
    }
}
