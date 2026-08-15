package com.nageoffer.ai.obs.example;

import com.nageoffer.ai.obs.backends.langfuse.dto.LangfuseDatasetItem;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 只读演示执行器。
 *
 * <p>由 {@code obs.example.enabled=true} 开启（默认关闭）：列数据集条目（前 3 条摘要）、
 * 按 {@code obs.example.trace-id} 查日志与 span。写操作演示见 {@link LangfuseDatasetExample}
 * 的参考实现，需要真实回答/trace/判分回调，不在本执行器自动触发。</p>
 */
@Component
public class ExampleRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExampleRunner.class);

    private final LangfuseDatasetExample langfuseExample;
    private final OpenObserveQueryExample openobserveExample;

    @Value("${obs.example.enabled:false}")
    private boolean enabled;

    @Value("${obs.example.dataset:}")
    private String datasetName;

    @Value("${obs.example.trace-id:}")
    private String traceId;

    @Value("${obs.example.limit:3}")
    private int limit;

    /**
     * 构造执行器。
     *
     * @param langfuseExample      Langfuse 示例组件
     * @param openobserveExample   OpenObserve 示例组件
     */
    public ExampleRunner(LangfuseDatasetExample langfuseExample, OpenObserveQueryExample openobserveExample) {
        this.langfuseExample = langfuseExample;
        this.openobserveExample = openobserveExample;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[示例] obs.example.enabled=false，跳过只读演示（置 true 开启）");
            return;
        }
        if (hasText(datasetName)) {
            List<LangfuseDatasetItem> items = langfuseExample.exampleListItems(datasetName, limit);
            log.info("[示例] 数据集 {} 取 {} 条", datasetName, items.size());
            for (LangfuseDatasetItem item : items) {
                log.info("[示例]   item={} | input={} | expectedOutput={}",
                        item.id(), preview(item.input()), preview(item.expectedOutput()));
            }
        }
        if (hasText(traceId)) {
            openobserveExample.showLogs(traceId);
            openobserveExample.showSpans(traceId);
        }
    }

    private String preview(Object value) {
        String text = String.valueOf(value);
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 80) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
