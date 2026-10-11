package com.jjx.ai.llmobservability.backends.langfuse;

import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseDatasetItem;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseDatasetRun;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseRunItem;
import com.jjx.ai.llmobservability.backends.langfuse.dto.LangfuseRunItemLink;
import java.util.List;

/**
 * Langfuse 数据集编排能力（黄金数据 ↔ 模型运行记录关联）。
 *
 * <p>接口化是为了后端升级可替换实现：当前 v3 由 {@link LangfuseApiClient} 实现，
 * 未来 v4 数据模型变化时新增实现并替换 Bean 即可，调用方不感知。</p>
 */
public interface LangfuseDatasetClient {

    /**
     * 分页拉取数据集条目（黄金数据：input/expectedOutput）。
     *
     * @param datasetName 数据集名
     * @param limit       返回条数上限（大于 0）
     * @return 数据集条目列表（不会超过 limit）
     */
    List<LangfuseDatasetItem> listDatasetItems(String datasetName, int limit);

    /**
     * 把黄金数据条目与模型运行记录关联到数据集 run。
     *
     * @param link 关联请求（runName + datasetItemId + traceId + observationId）
     * @return 创建/更新后的 run item
     */
    LangfuseRunItem linkRunItem(LangfuseRunItemLink link);

    /**
     * 列出指定数据集的 run（实验）。
     *
     * @param datasetName 数据集名
     * @param limit       返回条数上限（大于 0）
     * @return run 列表
     */
    List<LangfuseDatasetRun> listRuns(String datasetName, int limit);
}
