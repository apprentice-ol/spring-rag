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

package com.nageoffer.ai.rag.ingestion.engine.chunk.strategy;

import java.util.List;

/**
 * 边界感知文本切分器：把一段（可能超长的）文本切成不超过 {@code maxChars} 的片段，
 * 优先在更"自然"的语义边界处断开，避免从句子/词中间生硬切断。
 * <p>
 * 这是分块链路中最底层的"原子能力"——给定一段文本与体量预算，返回若干 {@code <= maxChars} 的片段。
 * block-aware 路径的 {@code ParagraphChunker}（长段落二次切）与 legacy 路径的
 * {@code StructureAwareTextChunker}（超长 PARA 块二次切）共用同一个实现，避免硬切逻辑各写各的。
 *
 * @see RecursiveBoundarySplitter 默认实现（递归降级，参考 LangChain RecursiveCharacterTextSplitter）
 */
public interface BoundaryAwareSplitter {

    /**
     * 把文本切成不超过 {@code maxChars} 的片段。
     * <ul>
     *   <li>优先在高级别边界断开（空行段落 / 换行 / 句末标点 ……），前一级能凑进 {@code [minChars, targetChars]} 就不降级；</li>
     *   <li>所有边界都无法满足预算时，才退回字符硬切兜底（永不抛异常、永不死循环）；</li>
     *   <li>{@code overlapChars > 0} 时相邻片段以前一片尾部重叠开头（在边界点对齐，不在词中间复制）。</li>
     * </ul>
     *
     * @param text         待切文本，空白或 {@code null} 返回空列表；长度 {@code <= maxChars} 时原样单元素返回
     * @param minChars     片段期望下限，过小的尾块会尝试并入前一片（须 {@code > 0}）
     * @param targetChars  片段目标大小，累加到该值附近即倾向于断开（须 {@code >= minChars}）
     * @param maxChars     片段硬上限，任何返回片段长度 {@code <= maxChars}（须 {@code >= targetChars > 0}）
     * @param overlapChars 相邻片段尾部重叠字符数，{@code 0} 表示不重叠
     * @return 切分后的片段列表，保持原文顺序；永不为 {@code null}
     */
    List<String> split(String text, int minChars, int targetChars, int maxChars, int overlapChars);
}
