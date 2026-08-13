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

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.rag.ingestion.engine.chunk.VectorChunk;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.HeadingBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 标题 chunker：把 {@link HeadingBlock} 渲染成 markdown 标题，产出 blockType=HEADING 的 chunk。
 * <p>
 * 标题不再仅作 outlinePath 虚拟注入，而是进 content（markdown {@code # 标题}），由 {@link ChunkPacker}
 * <b>向下合并</b>到其后的内容块——标题先断开上方 buffer，再与下方内容并入同一 chunk。
 * outlinePath 仍由 HeadingHandler 在 dispatcher 累积，写入 metadata.outline_path。
 */
@Component
public class HeadingChunker implements BlockChunker<HeadingBlock> {

    @Override
    public List<VectorChunk> chunk(HeadingBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        int level = Math.max(1, block.level());
        String text = block.text() == null ? "" : block.text();
        String markdown = "#".repeat(level) + " " + text;

        VectorChunk chunk = VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(ctx.startIndex())
                .content(markdown)
                .blockType("HEADING")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .build();
        return List.of(chunk);
    }
}
