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

/**
 * BlockAwareChunker 切分配置
 * <p>
 * 提供常用的切分参数；具体 chunker 按需读取自己关心的字段
 *
 * @param maxChars          单个 chunker 切分上限（ParagraphChunker / CodeChunker 长块切分时用）
 * @param overlapChars      chunk 重叠字符数（block-aware 路径恒 0）
 * @param packTargetChars   packer 贪心合并目标：累加到该值附近即倾向于断开，尽量贴合（1400）
 * @param packMinChars      packer 合并下限：当前 chunk 不足该值时允许"忍一次超限"吸入下一块（600）
 * @param packMaxChars      packer 合并硬上限：单个 chunk 不可超过此值（1800）
 * @param rowsPerChunk      TableChunker 每个 chunk 包含的数据行数
 * @param maxListItems      ListChunker 短列表 atomic 的阈值
 * @param listItemsPerChunk 长列表每个 chunk 的列表项数
 */
public record BlockChunkConfig(
        int maxChars,
        int overlapChars,
        int packTargetChars,
        int packMinChars,
        int packMaxChars,
        int rowsPerChunk,
        int maxListItems,
        int listItemsPerChunk
) {

    /**
     * 默认配置（用于测试 / 早期未配置场景）。
     * chunker 上限 1000；packer 目标 1400 / 下限 600 / 上限 1800
     */
    public static BlockChunkConfig defaults() {
        return new BlockChunkConfig(1000, 0, 1400, 600, 1800, 5, 15, 10);
    }

    public BlockChunkConfig {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be > 0, got " + maxChars);
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("overlapChars must be in [0, maxChars), got " + overlapChars);
        }
        if (packTargetChars <= 0) {
            throw new IllegalArgumentException("packTargetChars must be > 0, got " + packTargetChars);
        }
        if (packMinChars <= 0) {
            throw new IllegalArgumentException("packMinChars must be > 0, got " + packMinChars);
        }
        if (packMaxChars <= 0) {
            throw new IllegalArgumentException("packMaxChars must be > 0, got " + packMaxChars);
        }
        if (packMinChars > packTargetChars) {
            throw new IllegalArgumentException("packMinChars must be <= packTargetChars, got min="
                    + packMinChars + " target=" + packTargetChars);
        }
        if (packTargetChars > packMaxChars) {
            throw new IllegalArgumentException("packTargetChars must be <= packMaxChars, got target="
                    + packTargetChars + " max=" + packMaxChars);
        }
        if (rowsPerChunk <= 0) {
            throw new IllegalArgumentException("rowsPerChunk must be > 0, got " + rowsPerChunk);
        }
        if (maxListItems <= 0) {
            throw new IllegalArgumentException("maxListItems must be > 0, got " + maxListItems);
        }
        if (listItemsPerChunk <= 0) {
            throw new IllegalArgumentException("listItemsPerChunk must be > 0, got " + listItemsPerChunk);
        }
    }
}
