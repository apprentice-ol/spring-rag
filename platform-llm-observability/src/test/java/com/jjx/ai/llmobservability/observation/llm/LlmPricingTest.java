package com.jjx.ai.llmobservability.observation.llm;

import com.jjx.ai.llmobservability.autoconfigure.TelemetryProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 成本估算回归（2026-10-12）：定价表匹配与计算口径。
 *
 * <p>锁住三类行为：① 内置参考价 + 配置覆盖的合并；② 模型名小写归一与前缀匹配
 * （带版本后缀的模型名要归一到已知条目）；③ 未命中/无 token 时返回 null——
 * 宁可面板留空，不写 0 误导。</p>
 */
class LlmPricingTest {

    private static TelemetryProperties.Pricing config() {
        return new TelemetryProperties.Pricing();
    }

    @Test
    void 内置价_精确匹配_deepseek() {
        LlmPricing pricing = new LlmPricing(config());
        // deepseek-chat 默认 2 元/8 元 每百万：100 万输入 + 50 万输出 = 2 + 4 = 6 元
        LlmPricing.UsageCost cost = pricing.compute("deepseek-chat", 1_000_000, 500_000);
        assertNotNull(cost);
        assertEquals("4.000000", cost.outputText());
        assertEquals("6.000000", cost.totalText());
    }

    @Test
    void 响应侧路由名_deepseek_flash() {
        // 2026-10-12 实测：请求模型 deepseek-chat，API 响应 metadata.model=deepseek-flash——
        // 响应名与请求名都要能定价（filter 侧双保险回退）
        LlmPricing pricing = new LlmPricing(config());
        assertNotNull(pricing.compute("deepseek-flash", 1000, 1000));
    }

    @Test
    void 配置覆盖内置价() {
        TelemetryProperties.Pricing pricing = config();
        TelemetryProperties.ModelPrice custom = new TelemetryProperties.ModelPrice();
        custom.setInput(10);
        custom.setOutput(20);
        pricing.getModels().put("deepseek-chat", custom);

        LlmPricing.UsageCost cost = new LlmPricing(pricing).compute("deepseek-chat", 1_000_000, 0);
        assertNotNull(cost);
        assertEquals("10.000000", cost.inputText());
    }

    @Test
    void 前缀匹配_带版本后缀的模型名() {
        LlmPricing pricing = new LlmPricing(config());
        // deepseek-chat-v3.2 → 命中 deepseek-chat 条目；大小写不敏感
        assertNotNull(pricing.compute("deepseek-chat-v3.2", 1000, 1000));
        assertNotNull(pricing.compute("DeepSeek-Chat", 1000, 1000));
        // qwen-vl-max-latest → 命中 qwen-vl-max
        assertNotNull(pricing.compute("qwen-vl-max-latest", 1000, 1000));
    }

    @Test
    void 未命中与无token_返回null不写成本() {
        LlmPricing pricing = new LlmPricing(config());
        assertNull(pricing.compute("some-unknown-model", 1000, 1000));
        assertNull(pricing.compute("deepseek-chat", 0, 0));
        assertNull(pricing.compute(null, 1000, 1000));
    }

    @Test
    void 总开关关闭() {
        TelemetryProperties.Pricing pricing = config();
        pricing.setEnabled(false);
        assertNull(new LlmPricing(pricing).compute("deepseek-chat", 1000, 1000));
    }

    @Test
    void embedding仅输入计费() {
        LlmPricing pricing = new LlmPricing(config());
        // text-embedding-v3 默认 0.5 元/百万输入：200 万 tokens = 1 元
        LlmPricing.UsageCost cost = pricing.compute("text-embedding-v3", 2_000_000, 0);
        assertNotNull(cost);
        assertEquals("1.000000", cost.totalText());
    }
}
