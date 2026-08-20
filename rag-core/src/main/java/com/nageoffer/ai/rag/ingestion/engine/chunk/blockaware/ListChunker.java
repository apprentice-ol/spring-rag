/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
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

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.rag.ingestion.engine.chunk.VectorChunk;
import com.nageoffer.ai.rag.ingestion.engine.chunk.strategy.BoundaryAwareSplitter;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ListBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表 chunker：短列表 atomic、长列表按<b>字符预算</b>分组，任何产出片段不超过 packMaxChars。
 * <p>
 * 修复记录（2026-08）：旧版只按条数分组（≤15 条 atomic / 每 10 条一组），对字符总量零约束，
 * liveRag 英文 md 的"每条一段话"列表产出 144 个 avg 7482 字符的巨块（4 倍于 packer 上限），
 * embedding 语义被稀释、context 预算被挤占。现改为：
 * <ul>
 *   <li>短列表 atomic：条数 ≤ maxListItems <b>且</b> 渲染长度 ≤ packMaxChars 才整块；</li>
 *   <li>长列表：按累积字符凑 packTargetChars 起组（条数不再参与约束），尾组不足 packMinChars
 *       且并入前组不超 packMaxChars 时并入前组；</li>
 *   <li>渲染后仍超 packMaxChars 的组（单条 item 本身超长）过 {@link BoundaryAwareSplitter}
 *       兜底切分——与 ParagraphChunker 对齐，任何 chunker 产出必须过硬上限。</li>
 * </ul>
 * 渲染为标准 markdown 列表（{@code -} 或 {@code 1.}），有序编号保持全局连续。
 */
@Component
@RequiredArgsConstructor
public class ListChunker implements BlockChunker<ListBlock> {

    /** 渲染一条 item 的固定开销（"- "/"1. " 前缀 + 换行） */
    private static final int ITEM_OVERHEAD_CHARS = 4;

    private final BoundaryAwareSplitter boundaryAwareSplitter;

    @Override
    public List<VectorChunk> chunk(ListBlock block, ChunkContext ctx) {
        if (block == null || block.items() == null || block.items().isEmpty()) {
            return List.of();
        }
        List<String> items = block.items();
        BlockChunkConfig cfg = ctx.config();

        // 短列表 atomic：条数达标且字符不超上限，整列表一个 chunk（保持列表完整性）
        if (items.size() <= cfg.maxListItems() && renderedChars(items) <= cfg.packMaxChars()) {
            return List.of(buildChunk(items, 1, block, ctx, ctx.startIndex()));
        }

        // 长列表（或短列表但字符超限）：字符预算驱动分组
        List<List<String>> groups = groupByBudget(items, cfg);
        List<VectorChunk> result = new ArrayList<>(groups.size());
        int chunkIndex = ctx.startIndex();
        int consumed = 0;
        for (List<String> group : groups) {
            // 组的起始全局编号（ordered 列表编号保持原文连续）
            int startNumber = consumed + 1;
            consumed += group.size();
            String content = render(group, startNumber, block.ordered());
            if (content.length() <= cfg.packMaxChars()) {
                result.add(buildChunkFromContent(content, block, ctx, chunkIndex++));
            } else {
                // 单条 item 超长导致整组超限：过边界感知切分兜底（句边界优先，字符硬上限保证）
                List<String> pieces = boundaryAwareSplitter.split(
                        content, cfg.packMinChars(), cfg.packTargetChars(), cfg.maxChars(), 0);
                for (String piece : pieces) {
                    result.add(buildChunkFromContent(piece, block, ctx, chunkIndex++));
                }
            }
        }
        return result;
    }

    /**
     * 按字符预算把 items 贪心分组：累积到 packTargetChars 起下一组；
     * 尾组不足 packMinChars 且并入前组不超 packMaxChars 时并入（对齐 splitter 尾块合并语义）。
     */
    private static List<List<String>> groupByBudget(List<String> items, BlockChunkConfig cfg) {
        int target = cfg.packTargetChars();
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentChars = 0;
        for (String item : items) {
            int len = itemLength(item);
            if (!current.isEmpty() && currentChars + len > target) {
                groups.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(item);
            currentChars += len;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        // 尾组过小：并入前组（不超硬上限）
        if (groups.size() >= 2) {
            List<String> last = groups.get(groups.size() - 1);
            List<String> prev = groups.get(groups.size() - 2);
            if (groupChars(last) < cfg.packMinChars()
                    && groupChars(prev) + groupChars(last) + ITEM_OVERHEAD_CHARS <= cfg.packMaxChars()) {
                prev.addAll(last);
                groups.remove(groups.size() - 1);
            }
        }
        return groups;
    }

    /** 渲染整组为 markdown 列表文本（ordered 时编号从 startNumber 连续递增）。 */
    private static String render(List<String> items, int startNumber, boolean ordered) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (ordered) {
                sb.append(startNumber + i).append(". ");
            } else {
                sb.append("- ");
            }
            sb.append(items.get(i)).append('\n');
        }
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    /** 构造列表 chunk（atomic / 单组路径）。 */
    private VectorChunk buildChunk(List<String> items, int startNumber, ListBlock block,
                                   ChunkContext ctx, int chunkIndex) {
        return buildChunkFromContent(render(items, startNumber, block.ordered()), block, ctx, chunkIndex);
    }

    /** 由已渲染文本构造 LIST chunk（splitter 兜底切出的片段同样走这里）。 */
    private VectorChunk buildChunkFromContent(String content, ListBlock block, ChunkContext ctx, int chunkIndex) {
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(chunkIndex)
                .content(content)
                .blockType("LIST")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .build();
    }

    /** 渲染后的总字符数（含前缀与换行开销）。 */
    private static int renderedChars(List<String> items) {
        return groupChars(items);
    }

    private static int groupChars(List<String> items) {
        int len = 0;
        for (String item : items) {
            len += itemLength(item);
        }
        return len;
    }

    private static int itemLength(String item) {
        return (item == null ? 0 : item.length()) + ITEM_OVERHEAD_CHARS;
    }
}
