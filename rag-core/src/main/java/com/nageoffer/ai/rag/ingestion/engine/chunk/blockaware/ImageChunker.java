package com.nageoffer.ai.rag.ingestion.engine.chunk.blockaware;

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.rag.config.VlmClient;
import com.nageoffer.ai.rag.config.properties.VlmProperties;
import com.nageoffer.ai.rag.ingestion.engine.chunk.VectorChunk;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.AssetRef;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ImageBlock;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 图片 chunker：每个 ImageBlock 产生一个 atomic VectorChunk。
 *
 * <p>若 VLM 启用且 ImageBlock 无 description（如 MinerU 抽图未描述），
 * 调 {@link VlmClient#describe(String)} 生成文字描述，提升 IMAGE chunk 检索召回。
 */
@Slf4j
@Component
public class ImageChunker implements BlockChunker<ImageBlock> {

    private final VlmClient vlmClient;
    private final VlmProperties vlmProperties;

    public ImageChunker(VlmClient vlmClient, VlmProperties vlmProperties) {
        this.vlmClient = vlmClient;
        this.vlmProperties = vlmProperties;
    }

    @Override
    public List<VectorChunk> chunk(ImageBlock block, ChunkContext ctx) {
        if (block == null || block.asset() == null) {
            return List.of();
        }
        AssetRef asset = block.asset();

        // VLM 描述补充：description 为空（MinerU 默认无 image_caption）时用 VLM 生成。
        // 对齐 ragent ImageDocumentParser 双文本策略：description 进 embeddingText（可检索）、
        // 原图 URL 留 content（可展示）；无描述图片块 = 纯 URL 噪声，检索永远命中不了
        String description = block.description();
        if (description == null || description.isBlank()) {
            if (vlmProperties.isEnabled()) {
                description = vlmClient.describe(asset.publicUrl());
                if (description != null && !description.isBlank()) {
                    log.info("[ImageChunker] VLM 生成图片描述 {} 字: url={}", description.length(), asset.publicUrl());
                } else if (vlmProperties.isFailOnError()) {
                    throw new com.nageoffer.ai.rag.common.exception.ServiceException(
                            "VLM 图片描述失败，拒绝产生残缺数据: " + asset.publicUrl());
                } else {
                    log.warn("[ImageChunker] VLM 未生成描述（failOnError=false 降级为无描述图片块）: url={}", asset.publicUrl());
                }
            } else {
                log.warn("[ImageChunker] 图片块无描述且 VLM 未启用（设置 rag.vlm.enabled=true 可开启）: url={}", asset.publicUrl());
            }
        }

        String visible = pickCaption(block);
        String markdown = "![" + visible + "](" + asset.publicUrl() + ")";

        boolean hasDescription = description != null && !description.isBlank();
        String content = hasDescription
                ? description.strip() + "\n\n" + markdown
                : markdown;

        String embeddingText = hasDescription ? description.strip() : null;

        VectorChunk chunk = VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(ctx.startIndex())
                .content(content)
                .embeddingText(embeddingText)
                .blockType("IMAGE")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .assets(List.of(asset))
                .sectionContext(buildSectionContext(block))
                .build();

        return List.of(chunk);
    }

    private String pickCaption(ImageBlock block) {
        if (block.caption() != null && !block.caption().isEmpty()) {
            return block.caption();
        }
        if (block.altText() != null && !block.altText().isEmpty()) {
            return block.altText();
        }
        return "";
    }

    private String buildSectionContext(ImageBlock block) {
        if (block.provenance() == null || block.provenance().sheetName() == null) {
            return null;
        }
        return "sheet=" + block.provenance().sheetName();
    }
}
