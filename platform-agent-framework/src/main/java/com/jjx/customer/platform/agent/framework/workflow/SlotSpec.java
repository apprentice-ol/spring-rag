package com.jjx.customer.platform.agent.framework.workflow;

import java.util.function.Function;

/**
 * 槽位声明：流程需要的一项输入（名称 / 是否必填 / 追问话术 / 归一化 / 抽取提示）。
 *
 * <p>槽位目录归 Workflow（D1）；归一化与抽取提示随目录声明，引擎在槽位校验前应用。</p>
 *
 * @param normalizer    声明式归一（如环境别名 "正式"→"prod"）；null = 不归一。
 *                      返回 null 视为无法识别，保留原值（宽松）
 * @param extractionHint 抽槽提示（取值说明，注入抽槽 prompt 的槽位目录；null = 用 question）
 */
public record SlotSpec(String name, boolean required, String question, String hint,
                       Function<String, String> normalizer, String extractionHint) {

    public SlotSpec(String name, boolean required, String question, String hint) {
        this(name, required, question, hint, null, null);
    }
}
