package com.jjx.ai.llmobservability.observation.llm;

import com.jjx.ai.llmobservability.autoconfigure.TelemetryProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 调用成本估算：单价表（元/百万 token）× token 数，产出 {@code gen_ai.usage.cost.*} 数值串。
 *
 * <p>定价来源两条并集：内置默认表（常见模型的公开参考价，<b>仅供估算</b>）与
 * {@code telemetry.pricing.models} 配置（同名覆盖默认）。匹配规则：模型名先小写精确匹配，
 * 未命中再按前缀匹配（{@code deepseek-chat-v3.2} → {@code deepseek-chat}），都不命中则返回
 * {@code null}（不写成本属性，面板自然留空而不是写 0 误导）。</p>
 *
 * <p>2026-10-12 起：deepseek/百炼均已改按 token 计费且价格多变，本表默认值以公开买价为参考，
 * 请按实际账单在配置里校准。单位统一"元"，与 OO 面板展示的 token/模型维度拼接使用。</p>
 */
public final class LlmPricing {

    /** 内置参考价（元/百万 token）：2026-10 公开买价量级，可用 telemetry.pricing.models 同名覆盖。 */
    private static final Map<String, TelemetryProperties.ModelPrice> DEFAULTS = buildDefaults();

    private final boolean enabled;
    /** 合并后的定价表（key 已小写）。 */
    private final Map<String, TelemetryProperties.ModelPrice> prices;

    public LlmPricing(TelemetryProperties.Pricing pricing) {
        this.enabled = pricing == null || pricing.isEnabled();
        Map<String, TelemetryProperties.ModelPrice> merged = new LinkedHashMap<>(DEFAULTS);
        if (pricing != null && pricing.getModels() != null) {
            pricing.getModels().forEach((model, price) -> {
                if (model != null && !model.isBlank() && price != null) {
                    merged.put(model.trim().toLowerCase(), price);
                }
            });
        }
        this.prices = Map.copyOf(merged);
    }

    /**
     * 计算一次调用成本。
     *
     * @return null = 关闭 / 模型未定价 / 无 token 数据（调用方跳过写属性）
     */
    public UsageCost compute(String model, Integer inputTokens, Integer outputTokens) {
        if (!enabled || model == null || model.isBlank()) {
            return null;
        }
        TelemetryProperties.ModelPrice price = lookup(model.trim().toLowerCase());
        if (price == null) {
            return null;
        }
        int in = inputTokens == null ? 0 : Math.max(inputTokens, 0);
        int out = outputTokens == null ? 0 : Math.max(outputTokens, 0);
        if (in == 0 && out == 0) {
            return null;
        }
        double inputCost = in / 1_000_000d * price.getInput();
        double outputCost = out / 1_000_000d * price.getOutput();
        return new UsageCost(inputCost, outputCost, inputCost + outputCost);
    }

    private TelemetryProperties.ModelPrice lookup(String lowercaseModel) {
        TelemetryProperties.ModelPrice exact = prices.get(lowercaseModel);
        if (exact != null) {
            return exact;
        }
        // 前缀匹配：带版本后缀的模型名（deepseek-chat-v3.2 / qwen-vl-max-latest）归一到已知条目
        for (Map.Entry<String, TelemetryProperties.ModelPrice> entry : prices.entrySet()) {
            if (lowercaseModel.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, TelemetryProperties.ModelPrice> buildDefaults() {
        Map<String, TelemetryProperties.ModelPrice> defaults = new LinkedHashMap<>();
        // 主对话模型（参考价：2026-08 调价后量级，务必按账单校准）
        defaults.put("deepseek-chat", price(2.0, 8.0));
        // 响应侧实际路由名（2026-10-12 实测：请求 deepseek-chat，API 响应 metadata.model=deepseek-flash）
        defaults.put("deepseek-flash", price(1.0, 2.0));
        defaults.put("deepseek-reasoner", price(4.0, 16.0));
        // 百炼：向量 / 重排 / 视觉（embedding/rerank 只有输入计费）
        defaults.put("text-embedding-v3", price(0.5, 0));
        defaults.put("qwen3-rerank", price(0.4, 0));
        defaults.put("qwen-vl-max", price(3.0, 9.0));
        return defaults;
    }

    private static TelemetryProperties.ModelPrice price(double input, double output) {
        TelemetryProperties.ModelPrice p = new TelemetryProperties.ModelPrice();
        p.setInput(input);
        p.setOutput(output);
        return p;
    }

    /** 一次调用的成本拆分（元）。 */
    public record UsageCost(double input, double output, double total) {

        /** 属性值统一 6 位小数纯文本（单次调用成本极小，避免科学计数法不可读）。 */
        public String inputText() {
            return format(input);
        }

        public String outputText() {
            return format(output);
        }

        public String totalText() {
            return format(total);
        }

        private static String format(double value) {
            return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).toPlainString();
        }
    }
}
