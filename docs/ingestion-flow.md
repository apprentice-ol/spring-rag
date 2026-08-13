# springai-rag 文档入库完整流程

> 本文档梳理 springai-rag 从文件上传到向量落库的完整入库链路（`fetcher → parser → enhancer → chunker → enricher → indexer`），聚焦各环节实现机制与已知问题点。
>
> 来源：2026-07-29 入库效果排查会话，经代码通读后整理落盘，避免仅存于对话历史而丢失。代码以 `rag-core/.../ingestion/` 为准。

---

## 〇、总览

**入口**（都走 default pipeline `fetcher→parser→enhancer→chunker→enricher→indexer`）：
- 同步 `POST /docs/upload` → `IngestionService.ingest()`
- 异步 `POST /docs/upload-async` → RocketMQ → Consumer

**数据流转**（IngestionContext 字段）：
```
rawBytes/mimeType (Fetcher)
  → rawText + document.blocks (Parser)
  → enhancedText (Enhancer)          ⚠️ 见第2节断点
  → chunks[] (Chunker)
  → chunk.metadata.keywords/summary (Enricher)
  → pgvector (Indexer)
```

---

## 一、Parser（最详细）

### 1.1 路由机制

**ParserType 枚举**：`TIKA / MARKDOWN / EXCEL_POI / CSV / MINERU / IMAGE`。注意 `EXCEL_POI` 和 `IMAGE` **只是枚举占位，没有实现类**（预留扩展）。

**MimeTypeDetector**（`parser/reader/`）：封装 Apache Tika，**字节流 + 文件名双重判断**（有文件名时综合魔数与扩展名，更准）。ParserNode 仅在 mimeType 为空时探测。

**DocumentParserSelector**：Spring 注入所有 `DocumentParser`，按 `supports(mimeType)` 找第一个声明的。**v1.1 关键：不再静默兜底 Tika，无匹配直接返回 null → ParserNode 抛错。**

| MIME | 命中解析器 | 备注 |
|---|---|---|
| `text/markdown`、`text/x-markdown`、`text/plain` | MarkdownDocumentParser | 精确匹配 |
| `text/csv`、`application/csv` | CsvDocumentParser | 精确匹配 |
| `application/pdf`、`*wordprocessingml*`、`*msword*`、`*presentationml*`、`*powerpoint*` | **MinerUDocumentParser** | contains 匹配；**且 api-key 已配置**；Excel 不纳入 |
| `text/*`、json/xml/html/rtf、+ 上述 PDF/Word/PPT（MinerU 未配 key 时回退） | TikaDocumentParser | 兜底 |

**⚠️ Excel 没有任何 parser 声明 supports=true**——除非上层显式 `select("Csv")`，否则 Excel 落库会报"未找到解析器"。

### 1.2 MinerU 异步解析（完整时序，最复杂）

MinerU 是唯一的远程异步解析器，负责 PDF/Word/PPT 复杂版面。**B-lite 异步模型**：业务线程阻塞等结果，HTTP 轮询剥离到 4 线程共享调度池，跨实例并发用 Redisson 分布式信号量控制。

**配置**（`mineru.*`）：api-url、api-key（未配则不参与路由）、poll-interval=5s、timeout=300s、enable-table/formula=true、ocr=false、language=ch、**concurrency-limit=5**（分布式信号量许可数）、maxWait=30s、lease=900s。

**完整时序**（`MinerUDocumentParser.parseStructured`）：

```
1. 获取分布式许可
   Redisson RPermitExpirableSemaphore.tryAcquire(30s等, 900s租约)
   拿不到 → 抛"MinerU 解析任务过多"

2. 申请上传链接
   POST {apiUrl}/file-urls/batch  (Authorization: Bearer key)
   请求体: {enable_formula, enable_table, language, files:[{name,is_ocr,data_id}]}
   → 返回 batchId + uploadUrl(OSS预签名PUT, 24h有效)

3. 上传源文件
   PUT uploadUrl (裸字节, 不设Content-Type, 不加Authorization — MinerU要求)
   → MinerU 自动探测格式并提交解析任务

4. 轮询等待 (MinerUPollingExecutor)
   4个调度线程每5s调: GET {apiUrl}/extract-results/batch/{batchId}
   → MinerUStatus(state, full_zip_url, err_msg)
   状态机: done/success→DONE | failed/error→FAILED | waiting/pending/running/...→RUNNING(继续轮询) | 未知→当RUNNING
   · DONE → complete; FAILED → 异常; 超300s → TimeoutException
   · 瞬时网络错不终止，下轮重试（隐式重试，靠deadline兜底）
   业务线程 future.get(330s) 阻塞

5. 下载结果zip
   GET full_zip_url (预签名, 一次性, 拿到立即下)
   → byte[] zipBytes
   [调试: dump到 D:/mineru-dump/mineru_{docId}.zip]

6. 解包 (MinerUResultUnpacker)
   6a. 解zip: .md→markdown字符串; 图片(.png/.jpg/...)→Map<zipPath,byte[]>
   6b. 上传图片到RustFS: assets/{docId}/{UUID}.{ext} → getPublicUrl → imageUrlMap
   6c. commonmark(启用GFM Tables)解析markdown → AST
   6d. UnpackVisitor遍历AST → List<Block>

7. 合并metadata(minerU.batchId/zipUrl/parser/mimeType) → ParsedDocument

8. finally: semaphore.tryRelease(permitId)
```

**UnpackVisitor 产出的 Block**（commonmark 节点 → Block）：

| commonmark 节点 | Block | 字段 |
|---|---|---|
| Heading | HeadingBlock | level=#数(1-6), text=inline文本 |
| Paragraph | ParagraphBlock / ImageBlock | **段首图片剥离提升为 ImageBlock**；剩余文本→ParagraphBlock |
| FencedCodeBlock | CodeBlock | language=info, code |
| BulletList/OrderedList | ListBlock | ordered, items |
| GFM TableBlock | TableBlock | headers, rows |
| **HtmlBlock** | **ParagraphBlock** | ⚠️ MinerU 的表格常以 `<table>` HTML 嵌在 md 里，commonmark 解析为 HtmlBlock，**原样保留 HTML 文本当段落**（不进 TableBlock 流程） |

### 1.3 其他解析器

- **MarkdownDocumentParser**：commonmark 解析，Heading→HeadingBlock(level=getLevel)，# =level1。不处理 standalone image 提升、不处理 HtmlBlock。
- **TikaDocumentParser**（兜底）：`Tika.parseToString` 提取平文本 → 清理 → 按空行分段 → **仅 ParagraphBlock**（无结构）。⚠️ 配了 `PDFParserConfig` 但**没绑定到 Tika 实例（死代码）**。
- **CsvDocumentParser**：RFC4180 手写解析（支持引号/转义），首行=headers，产出单个 **TableBlock**。

### 1.4 Block 模型体系（`parser/model/`）

`Block` 是 **sealed interface**（permits 6 个子类型），方法：`id() / provenance() / outlinePath()`。

| Block | 关键字段 |
|---|---|
| HeadingBlock | level(1-6), text |
| ParagraphBlock | text（纯文本） |
| TableBlock | headers, rows, captionText |
| ImageBlock | asset(AssetRef), caption, altText, description(VLM填,P6) |
| CodeBlock | language, code |
| ListBlock | ordered, items |

- **Provenance**：sourceFile, sheetName（Excel用）
- **AssetRef**：publicUrl（RustFS公开读）, mime, sourceBlockId
- ⚠️ **所有解析器产出的 Block.outlinePath 都是 `List.of()`**——大纲路径在 Block 层面未计算，由下游 Chunker 的 HeadingHandler 累积

### 1.5 ParserNode

1. 校验 rawBytes 非空
2. mimeType 探测（空才探测）
3. 校验文件类型（按配置 rules）
4. `selectByMimeType` 路由，null 则抛错
5. 注入 options：`sourceFile`=fileName、`documentId`=taskId（资产 key 命名）
6. **调 `parseStructured(rawBytes, mimeType, options)` → ParsedDocument**
7. `rawText = BlockTextRenderer.render(blocks)`（从 blocks 渲染纯文本）
8. `document = StructuredDocument(text, blocks, metadata)`

**产出写回**：`context.rawText`、`context.document`。**注意：rawText 是 blocks 渲染来的，不是原始文件文本。**

---

## 二、Enhancer（⚠️ 致命断点）

**配置** `[CONTEXT_ENHANCE]`：对 `context.rawText`（Parser 渲染的纯文本）调 LLM 整理格式（修表格/断行/乱码，**禁止改内容**），产出 `context.enhancedText`。PDF 走专用 `pdf-format-guard`。

### ⚠️⚠️⚠️ 断点：CONTEXT_ENHANCE 对正常入库完全无效

`ChunkerNode` 的逻辑：
```java
blocks = context.getDocument().getBlocks();     // Parser 产的 blocks（MinerU/Markdown 都非空）
text   = enhancedText ?? rawText;               // enhancedText 当 fallbackText
structuredChunkingService.chunk(blocks, text, …);
```
而 `StructuredChunkingService`：**blocks 非空 → 走 block-aware（dispatch 用 blocks），fallbackText（enhancedText）根本不传入**。只有 blocks 为空（legacy）或 wholeDocument 才用 enhancedText。

**后果**：MinerU/Markdown 都产出 blocks → block-aware → **enhancedText 没人消费**。EnhancerNode 白调一次 LLM（耗时 + 钱），CONTEXT_ENHANCE 对正常入库零效果。**这是"效果不好"的确定嫌疑点 #1。**

---

## 三、Chunker

**StructuredChunkingService.chunk(blocks, text)**：
- blocks 非空 → `BlockAwareChunkerDispatcher.dispatch(blocks)`（正常路径）
- blocks 空 → legacy 文本切分（用 text）

**block-aware 流程**：
1. **HeadingHandler** 累积 `outlinePath`（从 HeadingBlock 的 level/text，如 `["文档解析","组件三"]`）——这是 outline_path 的真正来源
2. 各 BlockChunker 切：
   - Heading → 不产 chunk，更新 outlinePath
   - Paragraph → ParagraphChunker（按 maxChars，**overlap=0**）
   - Table → TableChunker（按行切，每块带表头；**content=markdown，embeddingText=key-value**）
   - Image → ImageChunker（atomic；content=描述+链接，embeddingText=描述）
   - Code/List → atomic/分组
3. **ChunkPacker**：贪心合并相邻小块到 maxChars

**配置**：chunkSize=1000, **overlap=0**, rowsPerChunk=50。

**产出 VectorChunk**：content(原始正文) / embeddingText(表格key-value|图片描述,段落null) / outlinePath(HeadingHandler算) / blockType / sectionContext。

---

## 四、Enricher

**配置** `[KEYWORDS, SUMMARY]`：逐 chunk 调 LLM，产出 `chunk.metadata.keywords`(List) / `chunk.metadata.summary`(String)。

**⚠️ 这些只进 metadata，不进 content/embedding** → **对向量召回无直接帮助**，仅检索后展示。每 chunk 2 次 LLM，入库慢。

---

## 五、Indexer

**Document.content = chunk.getContent()**（原始正文）→ `PgVectorStore.add()` 自动对 content 调百炼 text-embedding-v3（1024维）。

**metadata**：doc_id / doc_name / chunk_index / block_type / **outline_path** / section_context / assets + 白名单(**keywords/summary**)。每批 10 条。

**关键设计**（参考 ragent）：content=原始正文（干净），辅助信息全走 metadata，**不堆进向量**。

**⚠️ 表格 embeddingText(key-value) 当前未用于向量化**——content=原始 markdown，Spring AI 单 content 限制 + 要 content 原始，导致表格用 markdown 向量化（召回差）。

---

## 六、入库后数据形态

```
content   = 原始正文（段落文本 / 表格markdown / 图片描述+链接）
metadata  = {doc_id, doc_name, chunk_index, block_type, outline_path,
             section_context, keywords, summary, ...}
embedding = content 的向量（1024维，仅原始正文语义）
```

**核心：向量里只有原始正文。标题(outline_path)、摘要、关键词全在 metadata，不参与向量化。**

---

## 七、入库侧影响「效果」的问题点（诊断重点）

按嫌疑排序：

| # | 问题 | 影响 | 严重度 |
|---|---|---|---|
| **1** | **CONTEXT_ENHANCE 无效**（enhancedText 不被 block-aware 消费） | Enhancer 白调 LLM，格式修复零效果；PDF 的格式问题（乱码/错位）一路传到向量 | ⚠️⚠️⚠️ |
| **2** | **向量只有原始正文** | 召回只靠正文语义，标题/摘要/关键词帮不上；ragent 靠 LightRAG 补，springai-rag 没有 | ⚠️⚠️ |
| **3** | **MinerU 表格成 HtmlBlock→ParagraphBlock** | 表格被当 HTML 文本段落，不进 TableChunker 的 key-value 优化；表格检索差 | ⚠️⚠️ |
| **4** | **MinerU 解析质量**（标题 `??` 乱码等） | 垃圾进垃圾出，污染 blocks→chunk→向量 | ⚠️⚠️ |
| **5** | **表格 embeddingText 未用于向量化** | 表格用 markdown 向量（位置对齐，模型读不懂列名↔值） | ⚠️ |
| 6 | Excel 无 parser（除非显式选） | Excel 文件落库报错 | ⚠️ |
| 7 | Tika PDFParserConfig 死代码 | 无实际影响 | — |

---

**建议先查 #1**（CONTEXT_ENHANCE 断点）——如果属实，要么让 Enhancer 作用于 blocks（重新整理 Block 列表而非 enhancedText）、要么直接去掉 Enhancer 省 LLM 开销。这是成本最低、可能收益最大的修复点。
