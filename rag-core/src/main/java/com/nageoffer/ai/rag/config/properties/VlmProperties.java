package com.nageoffer.ai.rag.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** VLM 图片多模态描述配置，绑定 rag.vlm.* */
@Data
@Component
@ConfigurationProperties(prefix = "rag.vlm")
public class VlmProperties {

    /** 是否启用 VLM 图片描述（需多模态模型，如百炼 qwen-vl-max；关闭则图片块退化为纯 URL 噪声，检索不到） */
    private boolean enabled = false;

    /** 生成描述的 prompt；默认读 prompts/vlm/describe-image.md，留此字段供运维覆盖 */
    private String prompt;

    /** 多模态模型 base-url（默认百炼 OpenAI 兼容模式；DeepSeek chat 不支持图片输入） */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";

    /** 多模态模型 API key（默认复用百炼 key，yaml 用 ${BAILIAN_API_KEY:...} 注入） */
    private String apiKey;

    /** 多模态模型名（需支持图片输入，默认 qwen-vl-max） */
    private String model = "qwen-vl-max";

    /** VLM 失败时抛错中断该文档入库（对齐 ragent：绝不产生"有图无描述"的残缺数据）；false=降级为无描述图片块 */
    private boolean failOnError = false;

    /** 图片描述并行调用的 VLM 并发数（虚拟线程 + 信号量限流；文档图片多时避免逐图串行秒级往返） */
    private int concurrency = 5;
}
