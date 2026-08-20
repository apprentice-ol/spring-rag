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

package com.nageoffer.ai.rag.ingestion.engine.parser.mineru;

import com.nageoffer.ai.rag.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * MinerU 共享轮询调度器
 * <p>
 * B-lite 异步模型核心组件：把 HTTP 轮询从业务消费者线程剥离到独立虚拟线程。
 * 全局 outstanding 并发由 {@link MinerUDocumentParser} 的分布式信号量控制。
 * <ul>
 *   <li>消费者线程仍阻塞 await（B-lite 本质，与真 B 区别）</li>
 *   <li>每个 outstanding 任务一个虚拟线程轮询循环（sleep + query）：
 *       此前 4 个共享调度线程跑<b>阻塞式</b> queryResult（HTTP 往返可达 30s），
 *       scheduleAtFixedRate 对阻塞任务本就不适配——单个慢响应即占死 1/4 调度能力，
 *       任务多时轮询间隔被拉长、deadline 误判超时。虚拟线程阻塞无代价，慢响应只占自己。</li>
 * </ul>
 */
@Slf4j
@Component
public class MinerUPollingExecutor {

    private final MinerUClient client;
    private final MinerUProperties properties;

    public MinerUPollingExecutor(MinerUClient client, MinerUProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 提交任务并阻塞 await 直到完成
     * <p>
     * 调用方业务线程在 {@code future.get()} 上阻塞，但不消耗任何 HTTP / sleep 资源
     *
     * @param batchId MinerU 分配的 batch_id
     * @param timeout 超时时长
     * @return CompletableFuture，完成时携带 DONE 状态的 MinerUStatus（含 zipUrl）
     */
    public CompletableFuture<MinerUStatus> submitAndAwait(String batchId, Duration timeout) {
        if (batchId == null || batchId.isBlank()) {
            CompletableFuture<MinerUStatus> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ServiceException("batchId 不能为空"));
            return failed;
        }

        CompletableFuture<MinerUStatus> future = new CompletableFuture<>();
        Instant deadline = Instant.now().plus(timeout);
        // 最小间隔 100ms（生产配置 5s，这里宽松下限让测试场景能用短间隔）
        long intervalMs = Math.max(100L, properties.getPollIntervalSeconds() * 1000L);

        Thread.ofVirtual().name("minerU-poll-", 0).start(() -> pollLoop(batchId, future, deadline, intervalMs));
        return future;
    }

    /** 单任务轮询循环：sleep 间隔 → query → 完成/失败/超时三态收束 */
    private void pollLoop(String batchId, CompletableFuture<MinerUStatus> future,
                          Instant deadline, long intervalMs) {
        try {
            while (!future.isDone() && Instant.now().isBefore(deadline)) {
                Thread.sleep(intervalMs);
                if (future.isDone()) {
                    return;
                }
                try {
                    MinerUStatus status = client.queryResult(batchId);
                    if (status.completed()) {
                        future.complete(status);
                        return;
                    }
                    if (status.failed()) {
                        future.completeExceptionally(new ServiceException(
                                "MinerU 任务失败 batchId=" + batchId + " err=" + status.errorMessage()));
                        return;
                    }
                } catch (Exception e) {
                    // 瞬时网络错误不立即终止，等下一轮重试；超时由循环条件兜底
                    log.warn("MinerU 轮询临时异常 batchId={}: {}", batchId, e.getMessage());
                }
            }
            future.completeExceptionally(new TimeoutException("MinerU 任务超时 batchId=" + batchId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(new ServiceException("MinerU 轮询被中断 batchId=" + batchId));
        }
    }
}
