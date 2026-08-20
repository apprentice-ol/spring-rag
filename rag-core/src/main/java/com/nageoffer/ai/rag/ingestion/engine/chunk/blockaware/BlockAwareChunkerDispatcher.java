/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.rag.ingestion.engine.chunk.blockaware;
import com.nageoffer.ai.rag.config.VlmClient;
import com.nageoffer.ai.rag.config.properties.VlmProperties;
import com.nageoffer.ai.rag.ingestion.engine.chunk.VectorChunk;

import com.nageoffer.ai.rag.ingestion.engine.parser.model.Block;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.CodeBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.HeadingBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ImageBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ListBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ParagraphBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.TableBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * BlockAwareChunker 调度器
 * <p>
 * 把 {@code List<Block>} 分发到各类型专属 chunker。HeadingBlock 经 HeadingChunker 产出 HEADING chunk
 * (标题进 content), 同时 HeadingHandler 累积 outlinePath 注入后续 chunker 的 ChunkContext
 * <p>
 * 注：Java 17 中 sealed switch pattern 仍是 preview，用 instanceof pattern 链替代
 * Java 21 升级后可改回 switch 表达式
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockAwareChunkerDispatcher {

    private final HeadingHandler headingHandler;
    private final HeadingChunker headingChunker;
    private final ParagraphChunker paragraphChunker;
    private final TableChunker tableChunker;
    private final ImageChunker imageChunker;
    private final CodeChunker codeChunker;
    private final ListChunker listChunker;
    private final VlmClient vlmClient;
    private final VlmProperties vlmProperties;
    private final ChunkPacker chunkPacker;

    /**
     * 把 Block 列表分发到对应 chunker,返回有序 VectorChunk 列表
     *
     * @param blocks 解析器产出的 Block 列表
     * @param config 切分配置
     * @return VectorChunk 列表，index 单调递增
     */
    public List<VectorChunk> dispatch(List<Block> blocks, BlockChunkConfig config) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }

        blocks = pregenerateImageDescriptions(blocks);

        List<String> outlinePath = List.of();
        List<VectorChunk> result = new ArrayList<>();
        int chunkIndex = 0;

        for (Block b : blocks) {
            if (b instanceof HeadingBlock h) {
                outlinePath = headingHandler.update(outlinePath, h);
                // 不 continue：标题也产出 chunk(HeadingChunker)，进 content 供 ChunkPacker 向下合并
            }

            ChunkContext ctx = ChunkContext.of(outlinePath, config, chunkIndex);
            List<VectorChunk> chunks = chunkOne(b, ctx);
            result.addAll(chunks);
            chunkIndex += chunks.size();
        }
        // 后处理: 把相邻文本小块贪心打包到 maxChars, 断块处按 overlapChars 做块级重叠(单块 chunker 只拆不并)
        int beforePack = result.size();
        List<VectorChunk> packed = chunkPacker.pack(result,
                config.packTargetChars(), config.packMinChars(), config.packMaxChars(), config.overlapChars());
        log.info("[BlockAware] 贪心块融合: {} → {} 块 (target={}, min={}, max={}, overlap={})",
                beforePack, packed.size(), config.packTargetChars(), config.packMinChars(),
                config.packMaxChars(), config.overlapChars());
        return packed;
    }

    /**
     * VLM 图片描述并行预生成：无描述的 ImageBlock 按信号量限流并发调 VLM，结果以重建 record 写回。
     * <p>此前在分发循环内逐图<b>串行同步</b>调 VLM（秒级/次），20 张图拖慢整个 Chunker 节点数分钟。
     * 预生成失败的块保持原样——分发阶段 {@link ImageChunker} 会再试一次，
     * 失败语义（failOnError 抛错中断）仍由它兜底。</p>
     */
    private List<Block> pregenerateImageDescriptions(List<Block> blocks) {
        if (!vlmProperties.isEnabled()) {
            return blocks;
        }
        List<ImageBlock> pending = blocks.stream()
                .filter(ImageBlock.class::isInstance)
                .map(ImageBlock.class::cast)
                .filter(b -> b.asset() != null && (b.description() == null || b.description().isBlank()))
                .toList();
        if (pending.isEmpty()) {
            return blocks;
        }

        Semaphore limiter = new Semaphore(vlmProperties.getConcurrency());
        Map<String, ImageBlock> replaced = new ConcurrentHashMap<>();
        long t0 = System.currentTimeMillis();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?>[] futures = pending.stream()
                    .map(b -> CompletableFuture.runAsync(() -> {
                        try {
                            limiter.acquire();
                            try {
                                String desc = vlmClient.describe(b.asset().publicUrl());
                                if (desc != null && !desc.isBlank()) {
                                    replaced.put(b.id(), new ImageBlock(b.id(), b.provenance(), b.outlinePath(),
                                            b.asset(), b.caption(), b.altText(), desc));
                                }
                            } finally {
                                limiter.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, executor))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
        }
        log.info("[BlockAware] VLM 图片描述并行预生成: {}/{} 张成功, 并发={}, 耗时={}ms",
                replaced.size(), pending.size(), vlmProperties.getConcurrency(),
                System.currentTimeMillis() - t0);

        if (replaced.isEmpty()) {
            return blocks;
        }
        List<Block> out = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            if (b instanceof ImageBlock img && replaced.containsKey(img.id())) {
                out.add(replaced.get(img.id()));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private List<VectorChunk> chunkOne(Block b, ChunkContext ctx) {
        if (b instanceof HeadingBlock h) {
            return headingChunker.chunk(h, ctx);
        }
        if (b instanceof ParagraphBlock p) {
            return paragraphChunker.chunk(p, ctx);
        }
        if (b instanceof TableBlock t) {
            return tableChunker.chunk(t, ctx);
        }
        if (b instanceof ImageBlock i) {
            return imageChunker.chunk(i, ctx);
        }
        if (b instanceof CodeBlock c) {
            return codeChunker.chunk(c, ctx);
        }
        if (b instanceof ListBlock l) {
            return listChunker.chunk(l, ctx);
        }
        throw new IllegalStateException("Unsupported Block type: " + b.getClass().getName());
    }
}
