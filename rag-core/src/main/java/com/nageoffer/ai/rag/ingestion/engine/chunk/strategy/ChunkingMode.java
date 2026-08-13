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

/** 切分策略模式枚举。 */
public enum ChunkingMode {

    /** 固定大小切分 */
    FIXED_SIZE,
    /** 文本边界切分 */
    TEXT_BOUNDARY,
    /** 结构感知切分 */
    STRUCTURE_AWARE,
    /** 混合模式 */
    HYBRID,
    /** 预分块模式：文档已按 content 元素预先分好，每个 block 直接映射为一个 chunk，不经过 dispatcher+packer */
    PRE_CHUNKED;

    /**
     * 根据默认参数创建对应模式的 Options 实例。
     *
     * @param chunkSize   目标块大小
     * @param overlapSize 重叠大小
     * @return 对应模式的 Options 对象
     */
    public ChunkingOptions createDefaultOptions(int chunkSize, int overlapSize) {
        return switch (this) {
            case FIXED_SIZE -> new FixedSizeOptions(chunkSize, overlapSize);
            case TEXT_BOUNDARY -> new TextBoundaryOptions(chunkSize, overlapSize, chunkSize * 2, overlapSize / 2);
            case STRUCTURE_AWARE, HYBRID -> new TextBoundaryOptions(
                    chunkSize, overlapSize, chunkSize * 2, Math.max(50, chunkSize / 4));
            case PRE_CHUNKED -> new TextBoundaryOptions(chunkSize, overlapSize, chunkSize * 2, Math.max(50, chunkSize / 4));
        };
    }
}
