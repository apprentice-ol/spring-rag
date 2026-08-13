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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.common.exception.ServiceException;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.AssetRef;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.Block;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.CodeBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.HeadingBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ImageBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ListBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ParagraphBlock;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.ParsedDocument;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.Provenance;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.TableBlock;
import com.nageoffer.ai.rag.storage.domian.dto.StoredFileDTO;
import com.nageoffer.ai.rag.storage.service.FileStorageService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * MinerU 结果解包器：zip 字节流 → ParsedDocument。支持两条可切换路线（{@code unpackMode}）：
 *
 * <ul>
 *   <li><b>markdown</b>（默认，旧路线）：解析 {@code full.md}（commonmark + GFM 表格扩展）。
 *       MinerU 的表格在 full.md 里是 HTML {@code <table>}，commonmark 解析为 {@link HtmlBlock}，
 *       本路线将其原样写入 ParagraphBlock（<b>表格会丢失结构</b>，退化为装 HTML 源码的段落，
 *       TableChunker 不会接管，检索效果差）。</li>
 *   <li><b>content_list</b>（新路线）：解析 {@code content_list.json}，按 MinerU 的 {@code type}
 *       字段（text/code/image/table）结构化产出 Block；表格走 {@link Jsoup} 解析 {@code table_body}
 *       HTML 并展开合并单元格，产出真正的 {@link TableBlock}，下游 {@code TableChunker} 全套能力（key-value
 *       嵌入、每块带表头）生效——这是修复表格检索的关键路线。</li>
 * </ul>
 *
 * <p>两条路线共用：图片上传（{@link #uploadImages}）、跨页代码块合并（{@link #mergeSplitCodeBlocks}）。
 *
 * <p><b>跨页代码块合并</b>：MinerU 的 full.md 会把跨页的同一份代码切成多个相邻 ``` 围栏块（空行隔开），
 * 各自可能不完整（前段以半截注释结尾、括号未闭合）。{@link #mergeSplitCodeBlocks(List)} 做一次贪心扫描，
 * 相邻 CodeBlock 中前一段若括号未闭合（净开括号 &gt; 0）即判为被截断、与后一段合并；括号平衡的视为独立代码段，
 * 保持分开。不依赖 language（MinerU 常把后半段标成不同语言）。
 */
@Slf4j
@Component
public class MinerUResultUnpacker {

    /** commonmark 解析器（full.md 用），启用 GFM 表格扩展 */
    private static final Parser MARKDOWN_PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    /**
     * MinerU code 块 {@code sub_type} 常见语言白名单。命中则作为代码语言（决定 CodeChunker 围栏标记），
     * 未命中（如 {@code algorithm}/{@code formula}）置 null，避免把非语言标识当成代码语言误导前端高亮。
     */
    private static final Set<String> KNOWN_LANGUAGES = Set.of(
            "java", "javascript", "js", "typescript", "ts", "python", "py", "go", "rust", "rs",
            "c", "cpp", "c++", "cxx", "csharp", "cs", "c#", "objective-c", "objc",
            "php", "ruby", "rb", "kotlin", "scala", "swift", "sql", "bash", "shell", "sh", "zsh",
            "html", "css", "xml", "yaml", "yml", "json", "toml", "ini", "properties",
            "groovy", "gradle", "perl", "lua", "dart", "r", "matlab", "powershell", "dockerfile", "makefile"
    );

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public MinerUResultUnpacker(FileStorageService fileStorageService, ObjectMapper objectMapper) {
        this.fileStorageService = fileStorageService;
        this.objectMapper = objectMapper;
    }

    /**
     * 解包 MinerU zip 输出为 ParsedDocument（旧签名，默认走 markdown 路线，向后兼容）。
     */
    public ParsedDocument unpack(byte[] zipBytes, String sourceFile, String documentId) {
        return unpack(zipBytes, sourceFile, documentId, "markdown");
    }

    /**
     * 解包 MinerU zip 输出为 ParsedDocument，按 {@code unpackMode} 选路线。
     *
     * @param unpackMode {@code markdown}（默认）/ {@code content_list}
     */
    public ParsedDocument unpack(byte[] zipBytes, String sourceFile, String documentId, String unpackMode) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new ServiceException("MinerU zip 字节为空");
        }

        ZipContents contents = readZip(zipBytes);
        Map<String, String> imageUrlMap = uploadImages(contents.images(), documentId);
        Provenance prov = Provenance.ofFile(sourceFile);

        boolean wantContentList = "content_list".equalsIgnoreCase(unpackMode);
        String route;
        List<Block> blocks;
        if (wantContentList) {
            if (contents.contentList() == null) {
                log.warn("[MinerU] unpackMode=content_list 但 zip 中未找到 content_list.json，回退 markdown 路线");
                blocks = unpackByMarkdown(contents, prov, imageUrlMap);
                route = "markdown(fallback)";
            } else {
                blocks = unpackByContentList(contents, prov, imageUrlMap);
                route = "content_list";
            }
        } else {
            blocks = unpackByMarkdown(contents, prov, imageUrlMap);
            route = "markdown";
        }

        List<Block> merged = mergeSplitCodeBlocks(blocks);
        log.info("[MinerU] 解包完成: 路线={}, 块数={}（合并跨页代码后）, 图片={} 张",
                route, merged.size(), imageUrlMap.size());
        return ParsedDocument.of(merged, Map.of(
                "parser", "MinerU",
                "unpackRoute", route,
                "imagesUploaded", imageUrlMap.size(),
                "blocks", merged.size()
        ));
    }

    // ===================== 路线一：markdown（full.md + commonmark，旧逻辑） =====================

    /**
     * markdown 路线：用 commonmark 解析 full.md 产出 Block 列表。
     * <p>MinerU 的 HTML 表格会被解析为 {@link HtmlBlock}，{@link UnpackVisitor} 原样保留 HTML 文本写入
     * ParagraphBlock（表格丢失结构，仅作兜底/对比路线）。
     */
    private List<Block> unpackByMarkdown(ZipContents contents, Provenance prov, Map<String, String> imageUrlMap) {
        if (contents.markdown() == null) {
            throw new ServiceException("MinerU zip 中未找到 full.md");
        }
        Document doc = (Document) MARKDOWN_PARSER.parse(contents.markdown());
        UnpackVisitor visitor = new UnpackVisitor(prov, imageUrlMap);
        doc.accept(visitor);
        return visitor.getBlocks();
    }

    // ===================== 路线二：content_list（结构化 JSON） =====================

    /**
     * content_list 路线：解析 {@code content_list.json}，按 {@code type} 产出结构化 Block。
     * <p>JSON 解析失败或非数组时回退 markdown 路线，保证整篇文档仍可入库。
     */
    private List<Block> unpackByContentList(ZipContents contents, Provenance prov, Map<String, String> imageUrlMap) {
        List<Block> blocks = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(contents.contentList());
            if (!root.isArray()) {
                log.warn("[MinerU] content_list.json 顶层非数组，回退 markdown 路线");
                return unpackByMarkdown(contents, prov, imageUrlMap);
            }
            for (JsonNode item : root) {
                Block b = toBlock(item, prov, imageUrlMap);
                if (b != null) {
                    blocks.add(b);
                }
            }
        } catch (IOException e) {
            log.error("[MinerU] content_list.json 解析失败，回退 markdown 路线", e);
            return unpackByMarkdown(contents, prov, imageUrlMap);
        }
        return blocks;
    }

    /**
     * 单个 content_list 元素 → Block，按 {@code type} 分发；不识别或空内容返回 null（跳过）。
     */
    private Block toBlock(JsonNode item, Provenance prov, Map<String, String> imageUrlMap) {
        String type = item.path("type").asText("");
        return switch (type) {
            case "text" -> toTextBlock(item, prov, imageUrlMap);
            case "code" -> toCodeBlock(item, prov);
            case "image" -> toImageBlock(item, prov, imageUrlMap);
            case "table" -> toTableBlock(item, prov);
            default -> null;
        };
    }

    /**
     * text → HeadingBlock / ParagraphBlock。{@code text_level}∈[1,6] 视为标题层级，否则当正文段落。
     * <p>文本内嵌的 MinerU 图片引用行（裸路径 {@code images/xxx.jpg} 或 markdown
     * {@code ![](images/xxx.jpg)}）替换为 RustFS 公开 URL 的 markdown——否则 content 里是死链接，
     * 图片永远显示不出来（对齐 ragent markdown 路线：图片内联在正文流中，图文混排块可检索、可展示）。</p>
     */
    private Block toTextBlock(JsonNode item, Provenance prov, Map<String, String> imageUrlMap) {
        String text = item.path("text").asText("").strip();
        if (text.isEmpty()) {
            return null;
        }
        text = replaceInlineImages(text, imageUrlMap);
        JsonNode levelNode = item.path("text_level");
        if (levelNode.isNumber()) {
            int level = levelNode.asInt();
            if (level >= 1 && level <= 6) {
                return new HeadingBlock(uuid(), prov, List.of(), level, text);
            }
        }
        return new ParagraphBlock(uuid(), prov, List.of(), text);
    }

    /**
     * code → CodeBlock。{@code sub_type} 命中语言白名单才作为 language，否则置 null。
     */
    private Block toCodeBlock(JsonNode item, Provenance prov) {
        String code = item.path("code_body").asText("");
        if (code.isBlank()) {
            return null;
        }
        String language = normalizeLanguage(item.path("sub_type").asText(null));
        return new CodeBlock(uuid(), prov, List.of(), language, stripTrailingNewline(code));
    }

    /**
     * image → ImageBlock。{@code img_path} 经 imageUrlMap 解析为 RustFS 公开 URL，{@code image_caption} 作 caption。
     */
    private Block toImageBlock(JsonNode item, Provenance prov, Map<String, String> imageUrlMap) {
        String imgPath = item.path("img_path").asText("");
        if (imgPath.isBlank()) {
            return null;
        }
        String url = resolveImageUrl(imgPath, imageUrlMap);
        String caption = joinTextArray(item.path("image_caption"));
        String id = uuid();
        AssetRef asset = new AssetRef(url, inferMimeFromUrl(url), id);
        String captionText = StringUtils.hasText(caption) ? caption : imgPath;
        return new ImageBlock(id, prov, List.of(), asset, captionText, captionText, captionText);
    }

    /**
     * table → TableBlock。用 Jsoup 解析 {@code table_body} HTML 并展开合并单元格；
     * {@code table_caption} 作 captionText（检索时随表头一起进 embedding）。
     * 解析失败降级为 ParagraphBlock（保留 HTML 原文，不丢内容、不中断整篇）。
     */
    private Block toTableBlock(JsonNode item, Provenance prov) {
        String html = item.path("table_body").asText("");
        if (html.isBlank()) {
            return null;
        }
        String caption = joinTextArray(item.path("table_caption"));
        try {
            TableBlock table = parseHtmlTable(html, prov, caption);
            if (table != null) {
                return table;
            }
            log.warn("[MinerU] table_body 未解析出表格，降级为段落");
        } catch (Exception e) {
            log.warn("[MinerU] table_body HTML 解析失败，降级为段落: {}", e.getMessage());
        }
        return new ParagraphBlock(uuid(), prov, List.of(), html);
    }

    // ===================== HTML 表格 → TableBlock（Jsoup） =====================

    /**
     * 用 Jsoup 解析 MinerU table_body HTML → TableBlock，展开 rowspan/colspan 合并单元格。
     * <p>MinerU 表格 HTML 形如 {@code <table><tr><td rowspan=.. colspan=..>..</td></tr>...</table>}，
     * 无 thead，首个 tr 视为表头，其余为数据行。
     * <p><b>合并单元格展开</b>（对应资料"规范化五步法"之合并单元格）：值复制到 rowspan/colspan 覆盖的每个 cell，
     * 保证每个数据行自包含——分块后单行仍能看懂（否则切到合并区域中间会丢表头/丢值）。
     *
     * @return 解析出的 TableBlock；HTML 中无 table/tr 时返回 null
     */
    static TableBlock parseHtmlTable(String html, Provenance prov, String captionText) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        Element table = doc.selectFirst("table");
        if (table == null) {
            return null;
        }
        Elements trs = table.select("tr");
        if (trs.isEmpty()) {
            return null;
        }
        List<List<String>> grid = expandMergedCells(trs);
        if (grid.isEmpty()) {
            return null;
        }
        List<String> headers = grid.get(0);
        List<List<String>> rows = grid.size() > 1
                ? new ArrayList<>(grid.subList(1, grid.size()))
                : List.of();
        return new TableBlock(uuid(), prov, List.of(), headers, rows, captionText);
    }

    /**
     * 把 {@code <tr>/<td>} 展开为二维字符串网格，展开 rowspan/colspan：被合并覆盖的位置填入同一个值。
     * <p>算法：逐行扫描，列指针 c 从 0 递增；每个格子先查"被上方 rowspan 预占"的值（fill 表），命中则直接用；
     * 否则取下一个 td，按 colspan 向右、rowspan 向下把值预填进 fill 表（主位写当前行，跨行/跨列位写 fill），
     * 后续循环逐列自然消费这些预占值。
     */
    private static List<List<String>> expandMergedCells(Elements trs) {
        List<List<String>> grid = new ArrayList<>();
        Map<Long, String> fill = new HashMap<>();
        int maxCols = 0;
        for (int r = 0; r < trs.size(); r++) {
            Elements cells = trs.get(r).select("> td, > th");
            List<String> row = new ArrayList<>();
            int tdIdx = 0;
            int c = 0;
            while (true) {
                String preFilled = fill.get(gridKey(r, c));
                if (preFilled != null) {
                    row.add(preFilled);
                    c++;
                    continue;
                }
                if (tdIdx >= cells.size()) {
                    break;
                }
                Element cell = cells.get(tdIdx++);
                int colspan = Math.max(1, attrInt(cell, "colspan", 1));
                int rowspan = Math.max(1, attrInt(cell, "rowspan", 1));
                String text = cell.text();
                row.add(text);
                for (int dr = 0; dr < rowspan; dr++) {
                    for (int dc = 0; dc < colspan; dc++) {
                        if (dr == 0 && dc == 0) {
                            continue;
                        }
                        fill.put(gridKey(r + dr, c + dc), text);
                    }
                }
                c++;
            }
            maxCols = Math.max(maxCols, row.size());
            grid.add(row);
        }
        // 列对齐：所有行补齐到最大列宽，保证 headers/rows 按位置对齐
        for (List<String> row : grid) {
            while (row.size() < maxCols) {
                row.add("");
            }
        }
        return grid;
    }

    /** 行列坐标编码为 long key（列数 < 2^20 即可），供 fill 表索引预占格子 */
    private static long gridKey(int row, int col) {
        return ((long) row << 20) | col;
    }

    private static int attrInt(Element el, String attr, int defaultValue) {
        String v = el.attr(attr);
        if (!StringUtils.hasText(v)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ===================== content_list 辅助 =====================

    /** MinerU code 块 sub_type → 语言标识：命中白名单返回小写，否则 null */
    private static String normalizeLanguage(String subType) {
        if (!StringUtils.hasText(subType)) {
            return null;
        }
        String s = subType.trim().toLowerCase(Locale.ROOT);
        return KNOWN_LANGUAGES.contains(s) ? s : null;
    }

    /** 把 caption/footnote 等 JSON 字段（字符串数组）拼成单行文本，空则返回 null */
    private static String joinTextArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode n : node) {
                String s = n.asText("").strip();
                if (!s.isEmpty()) {
                    parts.add(s);
                }
            }
            return parts.isEmpty() ? null : String.join(" / ", parts);
        }
        String s = node.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static String stripTrailingNewline(String s) {
        if (s == null) {
            return "";
        }
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    // ===================== zip 读取 =====================

    /**
     * 单文件 zip 内容快照（markdown 路线用 markdown，content_list 路线用 contentList）
     */
    private record ZipContents(String markdown, byte[] contentList, Map<String, byte[]> images) {
    }

    private ZipContents readZip(byte[] zipBytes) {
        String markdown = null;
        byte[] contentList = null;
        Map<String, byte[]> images = new HashMap<>();

        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] data = readAll(zin);
                String lower = name.toLowerCase(Locale.ROOT);

                if (lower.endsWith(".md") && markdown == null) {
                    markdown = new String(data, StandardCharsets.UTF_8);
                } else if (lower.endsWith("_content_list.json") && contentList == null) {
                    // 命中 {uuid}_content_list.json；_content_list_v2.json 不以本后缀结尾，天然排除
                    contentList = data;
                } else if (isImage(name)) {
                    images.put(name, data);
                }
            }
        } catch (IOException e) {
            throw new ServiceException("MinerU zip 解压失败: " + e.getMessage());
        }
        return new ZipContents(markdown, contentList, images);
    }

    private static byte[] readAll(ZipInputStream zin) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zin.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    /**
     * 上传所有图片到 RustFS，返回 {zipPath → 公开访问 URL}
     */
    private Map<String, String> uploadImages(Map<String, byte[]> images, String documentId) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, byte[]> e : images.entrySet()) {
            String zipPath = e.getKey();
            byte[] data = e.getValue();
            String ext = extractExt(zipPath);
            String filename = "assets/" + documentId + "/" + UUID.randomUUID() + "." + ext;
            String mime = inferMime(ext);
            try {
                StoredFileDTO stored = fileStorageService.uploadAsset(data, filename, mime);
                String publicUrl = fileStorageService.getPublicUrl(stored.getUrl());
                result.put(zipPath, publicUrl);
            } catch (Exception ex) {
                log.error("MinerU 图片上传失败 zipPath={}", zipPath, ex);
                throw new ServiceException("MinerU 图片上传失败 " + zipPath + ": " + ex.getMessage());
            }
        }
        return result;
    }

    private static String extractExt(String path) {
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1).toLowerCase(Locale.ROOT) : "bin";
    }

    private static String inferMime(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    // ===================== 跨页代码块合并 =====================

    /**
     * 合并被 MinerU 跨页截断的代码块。
     * <p>
     * 贪心、链式扫描（一段代码可能被切成 ≥2 段）：相邻 CodeBlock 中，前段括号未闭合(净开&gt;0)且后段净闭
     * (净&lt;=0)——即后段正好补全前段缺失的闭括号——才判为同一份代码被截断、累积合并；不满足配平
     * 条件的视为独立代码段，断开。
     * 遇到非 CodeBlock 立即冲刷累积块、原样保留（不跨越标题/段落等中间内容）。
     *
     * @param blocks 解析产出的有序 Block 列表
     * @return 合并跨页代码后的 Block 列表
     */
    private static List<Block> mergeSplitCodeBlocks(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return blocks == null ? List.of() : blocks;
        }
        List<Block> result = new ArrayList<>(blocks.size());
        CodeBlock acc = null;
        for (Block b : blocks) {
            if (!(b instanceof CodeBlock cb)) {
                if (acc != null) {
                    result.add(acc);
                    acc = null;
                }
                result.add(b);
                continue;
            }
            if (acc == null) {
                acc = cb;
            } else if (shouldMergeSplitCode(acc, cb)) {
                acc = mergeCodeBlocks(acc, cb);
            } else {
                result.add(acc);
                acc = cb;
            }
        }
        if (acc != null) {
            result.add(acc);
        }
        return result;
    }

    /**
     * 是否应把后一段代码并入前一段：<b>栈匹配</b>判定——前段扫描后栈非空(有未闭合开括号 depth&gt;0)、
     * 且后段存在悬空闭括号(dangling&gt;0，即闭了一个"后段自己没开过的"括号)，二者正好配对，
     * 判为同一份代码被跨页截断的头尾两半。
     * <p>
     * 用栈而非净计数：栈能区分"开了没闭(depth&gt;0)"与"闭了没开(dangling&gt;0)"，语义更准；
     * "前段缺闭 + 后段补闭"才合并，既抓住硬截断(如方法从中间被切开)，又避免不完整摘录误吸后续代码、链式雪崩。
     * 不依赖 language —— MinerU 常把同一份代码的后半段标成不同语言（实测 java 段后接 javascript 段）。
     */
    private static boolean shouldMergeSplitCode(CodeBlock predecessor, CodeBlock successor) {
        int[] a = bracketStack(predecessor.code());
        int[] b = bracketStack(successor.code());
        if (a[0] > 0 && b[1] > 0) {
            return true; // 原有：前段括号未闭合、后段右括号未配对（跨页拆分的代码段）
        }
        // 新增：前段是"短注释/路径行"（无括号、无语句，如 MinerU 拆出的 bootstrap/.../SearchChannel.java:30）
        // 应并入后续代码体——否则注释行独立成块，真正的代码块失去说明上下文，
        // 检索/精排全环节判其不相关（实测接口代码块进不了上下文，回答只剩路径引用）
        String p = predecessor.code().strip();
        return a[0] == 0 && p.length() <= 80
                && !p.contains("{") && !p.contains("(") && !p.contains(";");
    }

    /**
     * 合并两段代码为一个 CodeBlock（record 不可变，新建）。
     * <ul>
     *   <li>code：前段 + "\n" + 后段</li>
     *   <li>language：前段非空取前段，否则取后段（前段多为正确语言，后段常被标错；前段为 indented 代码无 language 时回退后段）</li>
     *   <li>id：新生成（CodeBlock 无 sourceBlockIds 字段，无法溯源原两段 id，入库无害）</li>
     *   <li>provenance 取前段；outlinePath 为空（Block 层本就全空，真正路径由下游 HeadingHandler 在 VectorChunk 层累积）</li>
     * </ul>
     */
    private static CodeBlock mergeCodeBlocks(CodeBlock a, CodeBlock b) {
        String lang = StringUtils.hasText(a.language()) ? a.language() : b.language();
        String code = (a.code() == null ? "" : a.code()) + "\n" + (b.code() == null ? "" : b.code());
        return new CodeBlock(uuid(), a.provenance(), List.of(),
                StringUtils.hasText(lang) ? lang : null, code);
    }

    /**
     * 栈扫描代码括号（裸扫描，不解析字符串/注释内的括号）。
     *
     * @return {@code [depth, dangling]}：depth=结尾栈深度(未闭合的开括号数)，dangling=悬空闭括号数
     * (闭括号到来时栈空或栈顶类型不符，说明闭了一个"本段没开过的"括号)。两者皆为 0 即括号完全平衡。
     */
    private static int[] bracketStack(String code) {
        if (code == null || code.isEmpty()) {
            return new int[]{0, 0};
        }
        Deque<Character> stack = new ArrayDeque<>();
        int dangling = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                stack.push(c);
            } else if (c == ')' || c == ']' || c == '}') {
                char want = c == ')' ? '(' : (c == ']' ? '[' : '{');
                if (!stack.isEmpty() && stack.peek() == want) {
                    stack.pop();
                } else {
                    dangling++;
                }
            }
        }
        return new int[]{stack.size(), dangling};
    }

    // ===================== 图片 URL 解析（markdown / content_list 路线共用） =====================

    /** markdown 图片语法：![alt](images/xxx.jpg) */
    private static final Pattern MD_IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");
    /** 裸图片路径行：images/xxx.jpg（MinerU 文本流占位行） */
    private static final Pattern BARE_IMAGE_PATTERN = Pattern.compile("(?m)^(images/\\S+\\.(?:png|jpe?g|webp|gif))[ \\t]*$");

    /**
     * 把文本内嵌的 MinerU 图片引用替换为 RustFS 公开 URL 的 markdown 图片。
     * <p>MinerU content_list 的 text 元素可能内嵌图片行（裸路径或 markdown 语法），
     * 不替换则 content 里是死链接（实测为 {@code images/xxx.jpg}），检索回答都带不出图。
     * 未命中映射的路径原样保留（不丢内容）。</p>
     */
    private static String replaceInlineImages(String text, Map<String, String> imageUrlMap) {
        if (text == null || text.isEmpty() || imageUrlMap.isEmpty()) {
            return text;
        }
        String result = MD_IMAGE_PATTERN.matcher(text).replaceAll(m -> {
            String path = m.group(2);
            String alt = m.group(1).isBlank() ? baseName(path) : m.group(1);
            return "![" + alt + "](" + resolveImageUrl(path, imageUrlMap) + ")";
        });
        result = BARE_IMAGE_PATTERN.matcher(result).replaceAll(m ->
                "![" + baseName(m.group(1)) + "](" + resolveImageUrl(m.group(1), imageUrlMap) + ")");
        return result;
    }

    private static String baseName(String path) {
        int idx = path.lastIndexOf('/');
        String name = idx >= 0 ? path.substring(idx + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 把 MinerU 图片相对路径解析为 RustFS 公开 URL：精确匹配 → 去 {@code ./} 前缀再匹配 → 按文件名后缀匹配；
     * 都未命中返回原始路径（多模态/排障用）
     */
    private static String resolveImageUrl(String rawDest, Map<String, String> imageUrlMap) {
        if (rawDest == null) {
            return "";
        }
        String url = imageUrlMap.get(rawDest);
        if (url != null) {
            return url;
        }
        String norm = rawDest.replaceFirst("^\\./", "");
        url = imageUrlMap.get(norm);
        if (url != null) {
            return url;
        }
        int idx = norm.lastIndexOf('/');
        String fileName = idx >= 0 ? norm.substring(idx + 1) : norm;
        for (Map.Entry<String, String> e : imageUrlMap.entrySet()) {
            if (e.getKey().endsWith("/" + fileName) || e.getKey().equals(fileName)) {
                return e.getValue();
            }
        }
        return rawDest;
    }

    private static String inferMimeFromUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    // ===================== full.md + commonmark → Block（markdown 路线专用 Visitor） =====================

    /**
     * 遍历 markdown AST 输出 Block（full.md 路径）。
     * <p>
     * 与 MarkdownDocumentParser 的 Visitor 类似，但额外：
     * <ul>
     *   <li>剥离"段首 Image"，提升为 {@link ImageBlock} + RustFS AssetRef（剩余内容另起 ParagraphBlock）</li>
     *   <li>行内 Image 保留在 ParagraphBlock 中，链接已替换为 RustFS URL</li>
     * </ul>
     */
    private static final class UnpackVisitor extends AbstractVisitor {

        private final Provenance provenance;
        private final Map<String, String> imageUrlMap;
        private final List<Block> blocks = new ArrayList<>();

        UnpackVisitor(Provenance provenance, Map<String, String> imageUrlMap) {
            this.provenance = provenance;
            this.imageUrlMap = imageUrlMap;
        }

        List<Block> getBlocks() {
            return blocks;
        }

        @Override
        public void visit(Heading heading) {
            blocks.add(new HeadingBlock(
                    uuid(),
                    provenance,
                    List.of(),
                    heading.getLevel(),
                    extractInlineText(heading)
            ));
        }

        @Override
        public void visit(Paragraph paragraph) {
            if (paragraph.getParent() instanceof ListItem) {
                return;
            }

            Node rest = paragraph.getFirstChild();
            while (rest != null) {
                if (rest instanceof Image img) {
                    handleStandaloneImage(img);
                } else if (!isBlank(rest)) {
                    break;
                }
                rest = rest.getNext();
            }

            String text = extractInlineTextFrom(rest);
            if (!text.isEmpty()) {
                blocks.add(new ParagraphBlock(
                        uuid(),
                        provenance,
                        List.of(),
                        text
                ));
            }
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    uuid(),
                    provenance,
                    List.of(),
                    codeBlock.getInfo(),
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(IndentedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    uuid(),
                    provenance,
                    List.of(),
                    null,
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(BulletList bulletList) {
            blocks.add(buildListBlock(bulletList, false));
        }

        @Override
        public void visit(OrderedList orderedList) {
            blocks.add(buildListBlock(orderedList, true));
        }

        @Override
        public void visit(CustomBlock customBlock) {
            if (customBlock instanceof org.commonmark.ext.gfm.tables.TableBlock tableBlock) {
                handleTable(tableBlock);
                return;
            }
            super.visit(customBlock);
        }

        /**
         * 处理 HTML 块：MinerU 的表格等以原始 HTML（如 {@code <table>}）嵌在 markdown 里，
         * commonmark 解析为 HtmlBlock，这里原样保留 HTML 文本写入，避免内容被丢弃。
         * <p><b>注意</b>：此为 markdown 路线的兜底——表格会退化为 ParagraphBlock 丢失结构。
         * 表格检索请使用 content_list 路线（{@link #unpackByContentList}）。
         */
        @Override
        public void visit(HtmlBlock htmlBlock) {
            String html = htmlBlock.getLiteral() == null ? "" : htmlBlock.getLiteral().strip();
            if (html.isEmpty()) {
                return;
            }
            blocks.add(new ParagraphBlock(
                    uuid(),
                    provenance,
                    List.of(),
                    html
            ));
        }

        /**
         * 判断节点是否为可跳过的空白（换行或纯空白文本）
         */
        private static boolean isBlank(Node node) {
            return node instanceof SoftLineBreak
                    || node instanceof HardLineBreak
                    || (node instanceof Text t && t.getLiteral().trim().isEmpty());
        }

        private void handleStandaloneImage(Image image) {
            String rawDest = image.getDestination();
            String resolved = resolveImageUrl(rawDest, imageUrlMap);
            String caption = extractInlineText(image);
            String blockId = uuid();

            AssetRef asset = new AssetRef(
                    resolved,
                    inferMimeFromUrl(resolved),
                    blockId
            );
            blocks.add(new ImageBlock(
                    blockId, provenance, List.of(),
                    asset, caption, caption, caption
            ));
        }

        private ListBlock buildListBlock(Node listNode, boolean ordered) {
            List<String> items = new ArrayList<>();
            Node child = listNode.getFirstChild();
            while (child != null) {
                if (child instanceof ListItem) {
                    items.add(extractInlineText(child).trim());
                }
                child = child.getNext();
            }
            return new ListBlock(
                    uuid(),
                    provenance,
                    List.of(),
                    ordered,
                    items
            );
        }

        private void handleTable(org.commonmark.ext.gfm.tables.TableBlock tableBlock) {
            List<String> headers = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();

            Node child = tableBlock.getFirstChild();
            while (child != null) {
                if (child instanceof TableHead head) {
                    Node hr = head.getFirstChild();
                    if (hr instanceof TableRow tr) {
                        headers.addAll(extractCellTexts(tr));
                    }
                } else if (child instanceof TableBody body) {
                    Node tr = body.getFirstChild();
                    while (tr != null) {
                        if (tr instanceof TableRow row) {
                            rows.add(extractCellTexts(row));
                        }
                        tr = tr.getNext();
                    }
                }
                child = child.getNext();
            }

            blocks.add(new TableBlock(
                    uuid(),
                    provenance,
                    List.of(),
                    headers,
                    rows,
                    null
            ));
        }

        private List<String> extractCellTexts(TableRow row) {
            List<String> cells = new ArrayList<>();
            Node cell = row.getFirstChild();
            while (cell != null) {
                if (cell instanceof TableCell tc) {
                    cells.add(extractInlineText(tc).trim());
                }
                cell = cell.getNext();
            }
            return cells;
        }

        private String extractInlineText(Node parent) {
            return extractInlineTextFrom(parent.getFirstChild());
        }

        /**
         * 从指定兄弟节点起拼接 inline 文本（供段首剥离图片后渲染剩余内容）
         */
        private String extractInlineTextFrom(Node start) {
            StringBuilder sb = new StringBuilder();
            Node node = start;
            while (node != null) {
                appendInline(sb, node);
                node = node.getNext();
            }
            return sb.toString();
        }

        private void appendInline(StringBuilder sb, Node node) {
            if (node instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (node instanceof Code code) {
                sb.append('`').append(code.getLiteral()).append('`');
            } else if (node instanceof Link link) {
                String inner = extractInlineText(link);
                sb.append('[').append(inner).append("](").append(link.getDestination()).append(')');
            } else if (node instanceof Image img) {
                String alt = extractInlineText(img);
                String resolved = resolveImageUrl(img.getDestination(), imageUrlMap);
                sb.append("![").append(alt).append("](").append(resolved).append(')');
            } else if (node instanceof Emphasis || node instanceof StrongEmphasis) {
                sb.append(extractInlineText(node));
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                sb.append('\n');
            } else if (node.getFirstChild() != null) {
                Node child = node.getFirstChild();
                while (child != null) {
                    appendInline(sb, child);
                    child = child.getNext();
                }
            }
        }
    }
}
