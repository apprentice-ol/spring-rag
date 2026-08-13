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
import com.nageoffer.ai.rag.ingestion.engine.chunk.strategy.BoundaryAwareSplitter;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ParagraphBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落 chunker：长段落按 {@link BoundaryAwareSplitter} 做边界感知切分（空行/换行/句末标点优先，字符兜底），
 * 不再纯字符硬切；预算取自 {@link BlockChunkConfig} 的 target/min/max
 * <p>
 * 不跨 heading 的约束由 ChunkerNode 主流程保证（HeadingBlock 不通过 ParagraphChunker，
 * 会更新 outlinePath 但不破坏单个 ParagraphChunker 调用的 atomicity）
 */
@Component
public class ParagraphChunker implements BlockChunker<ParagraphBlock> {

    private final BoundaryAwareSplitter boundaryAwareSplitter;

    public ParagraphChunker(BoundaryAwareSplitter boundaryAwareSplitter) {
        this.boundaryAwareSplitter = boundaryAwareSplitter;
    }

    @Override
    public List<VectorChunk> chunk(ParagraphBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        String text = block.text() == null ? "" : block.text();
        if (text.isEmpty()) {
            return List.of();
        }

        BlockChunkConfig cfg = ctx.config();
        // 长段落按边界降级切（空行/换行/句末标点优先，字符兜底），不再纯字符硬切；
        // 预算复用 packer 的 target/min，与后续 ChunkPacker 融合阶段保持一致
        List<String> pieces = boundaryAwareSplitter.split(
                text, cfg.packMinChars(), cfg.packTargetChars(), cfg.maxChars(), cfg.overlapChars());

        List<VectorChunk> result = new ArrayList<>(pieces.size());
        int chunkIndex = ctx.startIndex();
        for (String piece : pieces) {
            VectorChunk chunk = VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(chunkIndex++)
                    .content(piece)
                    .blockType("PARAGRAPH")
                    .outlinePath(new ArrayList<>(ctx.outlinePath()))
                    .sourceBlockIds(List.of(block.id()))
                    .build();
            result.add(chunk);
        }
        return result;
    }

}
