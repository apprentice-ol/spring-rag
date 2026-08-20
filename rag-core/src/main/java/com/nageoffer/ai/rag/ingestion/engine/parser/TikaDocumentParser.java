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

package com.nageoffer.ai.rag.ingestion.engine.parser;

import com.nageoffer.ai.rag.ingestion.engine.parser.model.Block;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ParagraphBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ParsedDocument;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.Provenance;
import com.nageoffer.ai.rag.common.exception.ServiceException;
import com.nageoffer.ai.rag.common.util.TextCleanupUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Apache Tika 文档解析器
 * <p>
 * 支持多种文档格式：PDF、Word、Excel、PPT、HTML、XML 等
 * 使用 Apache Tika 库进行文档解析和文本提取
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TikaDocumentParser implements DocumentParser {

    private static final Tika TIKA = new Tika();

    /** 空行分段（预编译） */
    private static final Pattern BLANK_LINE_SPLIT = Pattern.compile("\\n{2,}");

    /**
     * 单文档提取字符上限。Tika parseToString(is) 单参版默认 10 万字符静默截断，
     * 长文档后半内容无声丢失；显式放开到 100 万（内存可控），超出仍截断并打 warn。
     */
    @Value("${rag.ingestion.tika-max-chars:1000000}")
    private int tikaMaxChars;

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    /**
     * 结构化解析:按 {@code \n\n+} 空行分段输出 ParagraphBlock 列表
     * <p>
     * Tika 输出是平文本,无章节标题/表格等结构信息可挖,故只产 ParagraphBlock
     * 复杂版面文档(PDF / Word / PPT)应路由到 MinerU 解析器,不走 Tika 路径
     */
    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        String text;
        try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
            // 带 writeLimit 的重载：单参版默认 10 万字符静默截断
            text = TIKA.parseToString(is, new Metadata(), tikaMaxChars);
            text = TextCleanupUtil.cleanup(text);
        } catch (Exception e) {
            log.error("Tika 结构化解析失败，MIME 类型: {}", mimeType, e);
            throw new ServiceException("文档解析失败: " + e.getMessage());
        }
        if (text.length() >= tikaMaxChars) {
            log.warn("Tika 提取达到字符上限被截断: maxChars={}, 源类型={}", tikaMaxChars, mimeType);
        }

        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        List<Block> blocks = new ArrayList<>();
        for (String segment : BLANK_LINE_SPLIT.split(text)) {
            String trimmed = segment.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            blocks.add(new ParagraphBlock(UUID.randomUUID().toString(), prov, List.of(), trimmed));
        }
        return ParsedDocument.of(blocks, Map.of("parser", getParserType(), "mimeType", mimeType == null ? "" : mimeType));
    }

    private String extractSourceFile(Map<String, Object> options) {
        if (options == null) {
            return "";
        }
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase(java.util.Locale.ROOT);

        // Markdown 交给专属解析器
        if (lower.startsWith("text/markdown") || lower.startsWith("text/x-markdown")) {
            return false;
        }
        // CSV 交给 CsvDocumentParser 产 key-val 表格，不走 Tika 平文本
        if (lower.equals("text/csv") || lower.equals("application/csv")
                || lower.equals("text/comma-separated-values")) {
            return false;
        }
        // Excel 交给 CsvDocumentParser 处理
        if (lower.contains("spreadsheetml") || lower.contains("excel")) {
            return false;
        }

        // text/* 基础纯文本类型
        if (lower.startsWith("text/")) {
            return true;
        }
        // 常见纯文本 application 类型
        if (lower.equals("application/json")
                || lower.equals("application/xml")
                || lower.equals("application/xhtml+xml")
                || lower.equals("application/rtf")) {
            return true;
        }
        // 兜底：PDF / Word / PPT — 当 MinerU 未配置时作为 fallback
        return lower.contains("pdf")
                || lower.contains("msword") || lower.contains("wordprocessingml")
                || lower.contains("powerpoint") || lower.contains("presentationml");
    }
}
