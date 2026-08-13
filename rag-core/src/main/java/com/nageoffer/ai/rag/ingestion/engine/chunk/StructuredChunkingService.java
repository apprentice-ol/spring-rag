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

package com.nageoffer.ai.rag.ingestion.engine.chunk;
import com.nageoffer.ai.rag.ingestion.engine.chunk.strategy.ChunkingMode;
import com.nageoffer.ai.rag.ingestion.engine.chunk.strategy.ChunkingStrategyFactory;
import com.nageoffer.ai.rag.ingestion.engine.chunk.strategy.ChunkingOptions;

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.rag.ingestion.engine.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.nageoffer.ai.rag.ingestion.engine.chunk.blockaware.BlockChunkConfig;
import com.nageoffer.ai.rag.ingestion.engine.parser.BlockTextRenderer;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.Block;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 结构化分块服务（统一分块入口）
 * <p>
 * 封装"<b>blocks 非空 → block-aware 分发；否则 → 纯文本 legacy 策略</b>"的唯一判断，
 * 供两条分块入口共用：
 * <ul>
 *   <li>{@code ingestion} 流水线的 ChunkerNode（Pipeline 模式）</li>
 *   <li>{@code knowledge} 文档的 KnowledgeDocumentServiceImpl（简单分块模式）</li>
 * </ul>
 * 两处曾各写各的，导致简单分块模式漏接 block-aware（表格被拍平成文本后随意切碎）；
 * 收口到此服务后单一真相源，杜绝再次漂移
 */
@Service
@RequiredArgsConstructor
public class StructuredChunkingService {

    private final BlockAwareChunkerDispatcher blockAwareChunkerDispatcher;
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    /**
     * 不分块哨兵：chunkSize/targetChars 取该值时整篇文档合成单个 chunk，不再切分
     */
    public static final int WHOLE_DOCUMENT_SENTINEL = -1;
    /**
     * 体量预算默认值（ChunkingOptions 未提供 size 键时用）
     */
    private static final int DEFAULT_MAX_CHARS = 512;
    /**
     * 表格每 chunk 最大数据行数（硬上限；实际块大小由体量预算驱动）
     */
    private static final int DEFAULT_ROWS_PER_CHUNK = 50;
    /**
     * 列表 atomic 阈值默认值
     */
    private static final int DEFAULT_MAX_LIST_ITEMS = 15;
    /**
     * 长列表每 chunk 项数默认值
     */
    private static final int DEFAULT_LIST_ITEMS_PER_CHUNK = 10;

    /**
     * 分块：blocks 非空走 block-aware，否则用 fallbackText 走 legacy 文本策略
     *
     * @param blocks       解析产出的结构化 Block，可空
     * @param fallbackText blocks 为空时的纯文本兜底
     * @param mode         legacy 文本策略类型（blocks 为空时使用）
     * @param options      legacy 文本策略参数，同时用于派生 block-aware 体量预算
     * @param rowsPerChunk block-aware 表格行上限，可空取默认
     * @return VectorChunk 列表（未嵌入）；blocks 与 fallbackText 都空时返回空列表
     */
    public List<VectorChunk> chunk(List<Block> blocks, String fallbackText,
                                   ChunkingMode mode, ChunkingOptions options, Integer rowsPerChunk) {
        // 预分块模式：文档已按 content 元素预先分好，每个 block 直接映射为一个 chunk
        if (mode == ChunkingMode.PRE_CHUNKED && blocks != null && !blocks.isEmpty()) {
            return oneChunkPerBlock(blocks);
        }
        // 不分块（chunkSize=-1）：整篇合成单个 chunk，优先于 block-aware / legacy 切分
        if (isWholeDocument(options)) {
            return wholeDocumentChunk(blocks, fallbackText);
        }
        if (blocks != null && !blocks.isEmpty()) {
            return blockAwareChunkerDispatcher.dispatch(blocks, toBlockChunkConfig(options, rowsPerChunk));
        }
        if (!StringUtils.hasText(fallbackText)) {
            return List.of();
        }
        return chunkingStrategyFactory.requireStrategy(mode).chunk(fallbackText, options);
    }

    /**
     * 判断是否为"不分块"：任一 size 键（chunkSize/targetChars）取哨兵值 {@code -1}
     */
    private static boolean isWholeDocument(ChunkingOptions options) {
        if (options == null) {
            return false;
        }
        Map<String, Integer> cfg = options.toConfigMap();
        for (String key : new String[]{"chunkSize", "targetChars"}) {
            Integer v = cfg.get(key);
            if (v != null && v == WHOLE_DOCUMENT_SENTINEL) {
                return true;
            }
        }
        return false;
    }

    /**
     * 预分块模式：每个 block 直接映射为一个 chunk（1:1），不做合并也不经 dispatcher+packer。
     * 适用于上游已将文档按语义预切分的场景（如 XML {@code <content>} 元素）。
     *
     * @param blocks 解析产出的 Block 列表
     * @return 等长的 VectorChunk 列表，每个 block 一个 chunk
     */
    private List<VectorChunk> oneChunkPerBlock(List<Block> blocks) {
        List<VectorChunk> chunks = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            String text = BlockTextRenderer.render(List.of(b));
            chunks.add(VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(i)
                    .content(text)
                    .embeddingText(text)
                    .blockType(blockTypeName(b))
                    .sourceBlockIds(List.of(b.id()))
                    .build());
        }
        return chunks;
    }

    /** 从 Block 实例反推 blockType 字符串（与 BlockAwareChunkerDispatcher 中各 chunker 设置的保持一致）。 */
    private static String blockTypeName(Block b) {
        if (b instanceof com.nageoffer.ai.rag.ingestion.engine.parser.model.HeadingBlock) return "HEADING";
        if (b instanceof com.nageoffer.ai.rag.ingestion.engine.parser.model.ParagraphBlock) return "PARAGRAPH";
        if (b instanceof com.nageoffer.ai.rag.ingestion.engine.parser.model.TableBlock) return "TABLE";
        if (b instanceof com.nageoffer.ai.rag.ingestion.engine.parser.model.ImageBlock) return "IMAGE";
        if (b instanceof com.nageoffer.ai.rag.ingestion.engine.parser.model.CodeBlock) return "CODE";
        if (b instanceof com.nageoffer.ai.rag.ingestion.engine.parser.model.ListBlock) return "LIST";
        return "PARAGRAPH";
    }

    /**
     * 整篇合成单个 chunk：内容取 fallbackText（解析渲染或增强后的全文），缺失时回退到 blocks 渲染
     *
     * @return 单元素列表；全文为空时返回空列表
     */
    private List<VectorChunk> wholeDocumentChunk(List<Block> blocks, String fallbackText) {
        String whole = StringUtils.hasText(fallbackText)
                ? fallbackText
                : (blocks != null && !blocks.isEmpty() ? BlockTextRenderer.render(blocks) : "");
        if (!StringUtils.hasText(whole)) {
            return List.of();
        }
        List<String> sourceBlockIds = blocks == null ? List.of()
                : blocks.stream().map(Block::id).filter(Objects::nonNull).toList();
        VectorChunk chunk = VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(0)
                .content(whole)
                .embeddingText(whole)
                .blockType("DOCUMENT")
                .sourceBlockIds(sourceBlockIds)
                .build();
        return List.of(chunk);
    }

    /**
     * 从 legacy ChunkingOptions 派生 BlockChunkConfig，使 block-aware 与文本策略共用同一组体量参数
     * <p>
     * maxChars 预算优先取 chunkSize（固定大小）/ targetChars（语义感知）；overlap 同理
     * rowsPerChunk 由调用方透传，缺省取硬上限默认值
     */
    private BlockChunkConfig toBlockChunkConfig(ChunkingOptions options, Integer rowsPerChunk) {
        // 单段切分上限与 packMax 对齐（1800），不再消费 chunk-size(512)：
        // 否则长段落会被 512 预切成碎片，packer 合并不充分，块数虚高（比 ragent 多一倍）。
        int maxChars = 1800;
        // block-aware 结构化分块不做 overlap（B 计划）：相邻 chunk 按语义边界切分，不需要字符重叠。
        // ChunkerSettings.overlapSize 配置项保留但 block-aware 路径不再消费
        int overlap = 0;
        int rows = (rowsPerChunk != null && rowsPerChunk > 0) ? rowsPerChunk : DEFAULT_ROWS_PER_CHUNK;
        // packer 合并窗口：标准 target=1400（段落累加目标）、min=600（不足时忍一次吸入）、max=1800（硬上限）。
        // CODE/TABLE 原子块不参与合并（对齐 ragent：MERGEABLE_TYPES 仅 PARAGRAPH/LIST/IMAGE），
        // 段落块在 1400 附近断开、最长不超 1800。
        return new BlockChunkConfig(maxChars, overlap,
                1400, 600, 1800,   // packTarget / packMin / packMax
                rows, DEFAULT_MAX_LIST_ITEMS, DEFAULT_LIST_ITEMS_PER_CHUNK);
    }

    /**
     * 按 keys 顺序取第一个存在且为正的值，否则返回默认
     */
    private static int firstPositive(Map<String, Integer> cfg) {
        for (String key : new String[]{"chunkSize", "targetChars", "maxChars"}) {
            Integer v = cfg.get(key);
            if (v != null && v > 0) {
                return v;
            }
        }
        return StructuredChunkingService.DEFAULT_MAX_CHARS;
    }
}
