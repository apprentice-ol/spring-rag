package com.nageoffer.ai.rag.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.ingestion.engine.NodeConfig;
import com.nageoffer.ai.rag.ingestion.engine.PipelineDefinition;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时若 DB 没有 default 流水线，插入一条默认（fetcher→parser→enhancer→chunker→indexer），
 * 方便首测（不必手动建 pipeline）。
 * <p>
 * 【2026-07-31 改动】跳过 enricher 节点：
 * EnricherNode（KEYWORDS + SUMMARY）对每个 chunk 调两次 LLM API，入库速度极慢
 * （100 chunks → 200 次 LLM 调用，耗时 100-300s），而 keyword 通道是次要检索通道、
 * summary 未被检索链路使用，收效与投入不成正比，因此移出默认流水线。
 * 如需恢复，在 DB 中添加 enricher 节点即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineBootstrap implements ApplicationRunner {

    private final IngestionPipelineService pipelineService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            PipelineDefinition existing = pipelineService.getDefinition("default");
            if (existing != null && existing.getNodes() != null && !existing.getNodes().isEmpty()) {
                log.info("[Bootstrap] default pipeline 已存在（{} 节点），跳过初始化", existing.getNodes().size());
                return;
            }
        } catch (Exception ignored) {
            // 表未建或无数据，继续插
        }
        // 含 enricher(KEYWORDS)：票种过滤（TicketTypeFilterPostProcessor）依赖 metadata.keywords——
        // 早期默认跳过 enricher 加速入库，但服务器新库无 keywords 导致票种过滤失效（查"增值税发票冲红"带出普通发票流程）
        log.info("[Bootstrap] 初始化 default pipeline（fetcher→parser→enhancer→chunker→enricher→indexer）");
        pipelineService.createPipeline(
                "default",
                "默认入库流水线",
                List.of(
                        NodeConfig.builder().nodeId("fetcher_1").nodeType("fetcher").nextNodeId("parser_1").build(),
                        NodeConfig.builder().nodeId("parser_1").nodeType("parser").nextNodeId("enhancer_1")
                                .settings(parserSettings()).build(),
                        NodeConfig.builder().nodeId("enhancer_1").nodeType("enhancer").nextNodeId("chunker_1")
                                .settings(enhancerSettings()).build(),
                        NodeConfig.builder().nodeId("chunker_1").nodeType("chunker").nextNodeId("enricher_1").build(),
                        NodeConfig.builder().nodeId("enricher_1").nodeType("enricher").nextNodeId("indexer_1")
                                .settings(enricherSettings()).build(),
                        NodeConfig.builder().nodeId("indexer_1").nodeType("indexer").build()));
    }

    /** enhancer 全文本增强：CONTEXT_ENHANCE 整理全文(→enhancedText，chunker 据此切，仅格式增强)。 */
    private JsonNode enhancerSettings() {
        return objectMapper.valueToTree(Map.of(
                "tasks", List.of(Map.of("type", "CONTEXT_ENHANCE"))));
    }

    /**
     * parser 走 MinerU content_list 解析路线：表格解析成 TableBlock（展开合并单元格），下游 TableChunker
     * 接管 key-value 嵌入 + 每块带表头，修复表格检索效果。markdown 旧路线保留，改此值即可切换。
     */
    private JsonNode parserSettings() {
        return objectMapper.valueToTree(Map.of("unpackMode", "content_list"));
    }

    /** enricher 逐块生成 keywords（票种过滤/关键词检索依赖 metadata.keywords） */
    private JsonNode enricherSettings() {
        return objectMapper.valueToTree(Map.of(
                "tasks", List.of(Map.of("type", "KEYWORDS"))));
    }
}
