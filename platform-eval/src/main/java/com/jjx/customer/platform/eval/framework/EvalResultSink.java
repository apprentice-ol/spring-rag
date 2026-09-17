package com.jjx.customer.platform.eval.framework;

import java.util.List;
import java.util.Map;

/**
 * 评测结果输出 SPI：评测结果的去向（落库 / Langfuse 推送 / 未来的报告回调…）。
 * <p>实现注册为 {@code @Component} 即生效（开闭：加去向 = 加 bean）。实现必须自吞异常——
 * 任何 sink 故障只记日志，绝不阻断跑批主流程（LangfusePushSink 在无凭据环境天然 no-op）。
 */
public interface EvalResultSink {

    /** run 启动通知（预同步/预热等；默认空实现）。 */
    default void onRunStart(EvalRunContext ctx) {
    }

    /** 单条 item 的结果分发（含失败样本：error 非 null 时 scores 为空）。 */
    void onItemResult(EvalSample sample, List<EvalScore> scores);

    /** run 收尾通知（聚合指标；默认空实现）。 */
    default void onRunFinished(EvalRunContext ctx, Map<String, Map<String, Double>> aggregate) {
    }
}
