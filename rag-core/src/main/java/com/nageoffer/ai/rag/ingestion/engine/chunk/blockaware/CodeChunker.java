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
import com.nageoffer.ai.rag.ingestion.engine.parser.model.CodeBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码块 chunker：每个 CodeBlock 产生一个 atomic VectorChunk
 * <p>
 * 永不切分 —— 代码块语法对完整性敏感（缺少 fence 或半截行会破坏前端渲染与 LLM 理解）
 * 渲染为标准 markdown 代码块 ``` 围栏
 */
@Component
public class CodeChunker implements BlockChunker<CodeBlock> {

    @Override
    public List<VectorChunk> chunk(CodeBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        String language = block.language() == null ? "" : block.language();
        String code = block.code() == null ? "" : block.code();
        // MinerU 的 code_body 常自带 ``` 围栏（markdown 格式），再包一层会双重围栏、
        // 前端渲染错乱、LLM 展示异常——code 已含围栏标记（可能不在开头，如合并了路径注释行）则原样保留
        String markdown = code.contains("```")
                ? code
                : "```" + language + "\n" + code + "\n```";
        // 章节标题注入：代码块的章节归属是确定性的，而 mergeLeadingContext 合并的紧邻说明
        // 可能主题错位（如"组件一"章节的代码块前面却是"为什么需要多通道"段落）——
        // 章节名让 BM25/向量/rerank 全链路命中代码块的真正主题。
        // 取"最近的有语义章节"：跳过"实现代码/代码/示例"等通用子标题（MinerU 常把代码归到
        // ## 实现代码 下），继承其上级章节（如"RRF 融合（核心算法）"）
        String section = resolveSection(ctx.outlinePath());
        if (section != null && !section.isBlank() && !markdown.startsWith("## " + section)) {
            markdown = "## " + section + "\n\n" + markdown;
        }

        VectorChunk chunk = VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(ctx.startIndex())
                .content(markdown)
                .blockType("CODE")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .build();

        return List.of(chunk);
    }

    /**
     * 取 outline_path 中"最近的有语义章节"：从后往前跳过通用代码标题（实现代码/代码/示例等），
     * 继承其上级章节。通用标题无主题词，注入它们对检索无增益（"实现代码"命中不了"RRF 融合"）。
     */
    static String resolveSection(List<String> outlinePath) {
        for (int i = outlinePath.size() - 1; i >= 0; i--) {
            String s = outlinePath.get(i);
            if (s == null || s.isBlank()) {
                continue;
            }
            String t = s.replaceAll("^#+\\s*", "").trim();
            if (!GENERIC_CODE_HEADINGS.contains(t)) {
                return s;
            }
        }
        return outlinePath.isEmpty() ? null : outlinePath.get(outlinePath.size() - 1);
    }

    /** 无主题词的通用代码子标题（MinerU 常把代码归到这些标题下） */
    private static final java.util.Set<String> GENERIC_CODE_HEADINGS = java.util.Set.of(
            "实现代码", "代码", "代码示例", "示例", "Code", "Implementation", "代码实现");
}
