package com.nageoffer.ai.rag.config;

import com.nageoffer.ai.rag.config.properties.VlmProperties;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * VLM 多模态客户端：给图片 URL → 调多模态大模型（百炼 qwen-vl）生成文本描述（用于知识库检索）。
 * <p>走独立 {@code vlmChatClient}（DeepSeek chat 不支持图片输入）；图片字节由本地 {@link UrlResource}
 * 读取后以 multipart 上传——不依赖云端可访问图片 URL，localhost/内网部署同样可用。</p>
 */
@Slf4j
@Component
public class VlmClient {

    private final ChatClient chatClient;
    private final VlmProperties props;
    private final PromptStore promptStore;

    public VlmClient(@Qualifier("vlmChatClient") ChatClient chatClient,
                     VlmProperties props,
                     PromptStore promptStore) {
        this.chatClient = chatClient;
        this.props = props;
        this.promptStore = promptStore;
    }

    /** 生成图片描述，失败返回 null（调用方决定降级或抛错，见 {@code rag.vlm.fail-on-error}）。 */
    public String describe(String imageUrl) {
        if (!props.isEnabled()) {
            return null;
        }
        try {
            String prompt = StringUtils.hasText(props.getPrompt())
                    ? props.getPrompt()
                    : promptStore.raw("vlm/describe-image");
            UrlResource resource = new UrlResource(imageUrl);
            String content = chatClient.prompt()
                    .user(u -> u.text(prompt)
                            .media(MediaType.IMAGE_PNG, resource))
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                log.warn("[VLM] 模型返回空描述: url={}", imageUrl);
                return null;
            }
            return content.strip();
        } catch (IOException e) {
            log.warn("[VLM] 图片读取失败（URL 不可达?）: url={}, err={}", imageUrl, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[VLM] 图片描述生成失败: url={}, err={}", imageUrl, e.getMessage());
            return null;
        }
    }
}
