# 计划归档 · 入库与检索质量（2026-07-29 → 2026-09-18）

> 2026-10-11 文档整理：本文按时间顺序合并四份文档，原文完整保留，各部分以 `---` 分隔。
>
> - 2026-07-29 · 文档入库完整流程（入库链路诊断：CONTEXT_ENHANCE 断点等 7 个问题点）
> - 2026-08-17 · 检索层修复计划
> - 2026-09-18 · 召回策略优化方案
> - 2026-09-18 · 小块检索、大块生成（Parent-Child / Small-to-Big）

---

<!-- 原文件：docs/ingestion-flow.md -->

# customer-platform 文档入库完整流程

> 本文档梳理 customer-platform 从文件上传到向量落库的完整入库链路（`fetcher → parser → enhancer → chunker → enricher → indexer`），聚焦各环节实现机制与已知问题点。
>
> 来源：2026-07-29 入库效果排查会话，经代码通读后整理落盘，避免仅存于对话历史而丢失。代码以 `app/.../ingestion/` 为准。

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
| **2** | **向量只有原始正文** | 召回只靠正文语义，标题/摘要/关键词帮不上；ragent 靠 LightRAG 补，customer-platform 没有 | ⚠️⚠️ |
| **3** | **MinerU 表格成 HtmlBlock→ParagraphBlock** | 表格被当 HTML 文本段落，不进 TableChunker 的 key-value 优化；表格检索差 | ⚠️⚠️ |
| **4** | **MinerU 解析质量**（标题 `??` 乱码等） | 垃圾进垃圾出，污染 blocks→chunk→向量 | ⚠️⚠️ |
| **5** | **表格 embeddingText 未用于向量化** | 表格用 markdown 向量（位置对齐，模型读不懂列名↔值） | ⚠️ |
| 6 | Excel 无 parser（除非显式选） | Excel 文件落库报错 | ⚠️ |
| 7 | Tika PDFParserConfig 死代码 | 无实际影响 | — |

---

**建议先查 #1**（CONTEXT_ENHANCE 断点）——如果属实，要么让 Enhancer 作用于 blocks（重新整理 Block 列表而非 enhancedText）、要么直接去掉 Enhancer 省 LLM 开销。这是成本最低、可能收益最大的修复点。

---

<!-- 原文件：docs/plans/2026-08-17-retrieval-fix.md -->

# 检索层修复计划（2026-08-17 启动）

背景：分块层四项修复已完成并重灌验证（run55：巨块受害型分类 +5~6pp），但 precision@5 仍 0.217。
根因：检索无 collection 过滤（自传 PDF 污染）+ 混合语料 + rewrite 英文负优化。

## 任务清单

### #1 检索 collection 过滤（P0，本计划主线）
- [x] IndexerNode 入库时 metadata 写入 collection_id
- [x] 存量回填：990 篇 LiveRAG chunk 的 metadata.collection_id = 2（SQL）
- [x] VectorSearchChannel / Bm25KeywordSearchChannel / KeywordSearchChannel SQL 加过滤（collectionId 非空时）
- [x] SearchContext 加 collectionId 字段
- [x] EvalRunner 检索时按期望文档集合自动推断（resolveCollection：期望文档同集合才过滤）
- [x] 编译 + 重启 + run56 对比

**run56 结果（2026-08-17）**：功能验证通过（自传 PDF 被 run54 检索 7 次 → run56 0 次），
但 precision@5 0.2173→0.2140（噪声内）、recall@5 0.9467→0.9517。
**教训**：自传 PDF 只占库 0.87%，precision 0.22 的主体是 855 题同主题文档的题间竞争
（同 collection 内部，过滤不掉）。当初把 item 465 当代表性证据，实为稀有案例。
下一步要动 precision 只能：per-question 检索模式（对齐官方语义，仅作瓶颈验证用）
或转向 context 纯度（#4）与答案质量（#5）——评测是 hard mode，precision 0.22 附近
是混合语料的结构性水平。

### #2 rewrite 英文对照（已完成 2026-08-17）
- [x] run56(false) vs run57(true)：rewrite=true 使 recall@5 0.9517→0.9167（-3.5pp）、MRR -2.7pp、零命中 12→18 题
- [x] 修复：QueryRewriter 入口 CJK 检测，英文 query 跳过 LLM 改写（run58 验证：0.9383 回升，300 条跳过日志确认；与 run56 残差为抽题噪声≈1σ）

### #3 中文 PDF 重传（已完成 2026-08-17）
- [x] 3 篇重传（RustFS 取原文件→MinerU→新代码）
- [x] 验证：乱码块 0（补丁：LIST/CODE 块也做 U+FFFD 字符级删除）、导航行 0、碎块 0；全库 5381 块全干净

### #4 Reducer 落地（核查完成 2026-08-17：机制已存在，勿重复造）
- [x] **阈值过滤已存在**：BaiLianRerankClient 的 min-relevance-score=0.3（application.yaml:106，"宁可少给不塞弱相关块"）——本轮加的 RerankPostProcessor 层（rerank-score-threshold=0.1）是双保险（覆盖客户端降级/RRF 分数路径），run59 验证无回归（0.9317 vs 0.9383 噪声内，rerank 输出 4~10 条本已被 0.3 过滤）
- [x] **最强前置已存在**：buildContextText 按文档分组、组间保持"首次出现=相关性顺序"（最强文档组在最前）
- [x] **通道标注主动搁置**：buildContextText 有明确反注入设计（"刻意不注入文档名与分数，会诱导偏向高分块"）；confirmed 标注同理有诱导风险，待 #5 LLM-judge 能验证生成质量时再议

### #5 eval Phase 2（已完成 2026-08-17 实现，待联网实例终验）
- [x] expected_answer 全链路打通：LiveRAG 导入已写；手工添加补上 EvalItemRequest/服务层/前端表单（API 验证存取 OK）
- [x] LLM-as-judge 实现 + 修复：judge 提示词补喂检索上下文（原实现只给黄金答案，faithfulness 语义偏成"与参考答案一致"），正确性/忠实度双维 0~1 分
- [x] 编译 + 21 单测 + vue-tsc 通过
- [x] 联网实例终验（run69，8 题）：新 judge（带检索上下文）7/7 打分成功，correctness 0.7143（0.3~1.0 能区分事实符合度）、faithfulness 0.9857（0.9~1.0，答案均基于上下文）；1 题缺分=921 typo 题检索空召回（与 judge 无关）

### #6（已完成 2026-08-17）per-question 检索模式验证
- [x] 开关链路完整（选项→快照→restrictedDocIds→三通道 SQL 过滤），前端触发弹窗可勾选
- [x] 固定 50 题对照（run63 基线 vs run64 限定）：run64 检索结果 0 篇非期望文档；recall@5 0.92→0.96、MRR 0.90→0.96、nDCG@5 0.904→0.96；precision@5 0.204→0.208 基本不动
- [x] **结论**：混合语料干扰是 recall/MRR 的次要瓶颈（约 4~6pp，4 题由 0 召回被救回）；precision@5≈0.2 是**指标结构性天花板**（45/50 题只有 1 篇期望文档，分母固定 k=5，理论上限 0.22），不是检索污染——此前"precision 0.22 靠 per-question 可动"的假设证伪，动 precision 只能靠指标定义/题集设计
- [x] 附注：911/916 在 run64 偶发空召回，run67 单独重测 recall=1.0 → 并发下检索层抖动，与 per-question 无关

## 备注
- 对话主链（ChatOrchestrator）暂不绑死 collection（线上单库场景），过滤仅在显式传入 collectionId 时生效
- 3 篇自传 PDF 建议移入独立 collection 或保持 NULL（NULL 语义 = 不属于任何集合，评测时不检索）

---

<!-- 原文件：docs/plans/2026-09-18-recall-optimization.md -->

# 召回策略优化方案

> 状态:待 review ｜ 2026-09-18 ｜ 数据基线截至 2026-09-18

## 0. 一句话

召回问题拆成 A(候选不足)/ B(覆盖不足)/ C(排序不足)三个子问题,实测 **B 体量最大、C 基本不存在**;
先用**零代码的参数实验**测出 B 的收益上限,再决定要不要投入改造。多查询扩展治 A、不治 B,排在后面。

---

## 1. 问题拆解

| 子问题 | 定义 | 实测表现 | 数据依据 |
|---|---|---|---|
| **A. 候选不足** | 期望文档根本没进候选池(40 chunks) | 23 条零召回 | `retrieved_doc_ids` 为空或完全无关 |
| **B. 覆盖不足** | 进了池子,但被同文档 chunk 挤掉 | 38 条部分失败,平均只覆盖 **1.6 篇**文档 | `recall@5 ≡ recall@10`,分布完全相同 |
| **C. 排序不足** | 进了最终集合但排位靠后 | **基本不存在** | @5 与 @10 的零分/部分/满分分布逐项相同 |

**C 不存在**这件事本身就说明:rerank 与排序不是当前瓶颈,力气全部该花在 A 和 B 上。
**B 的体量(38)大于 A(23)**,且 B 的机理已被完全解释(见 §3)。

### 为什么 B 会发生

`contextTopK=10` 截的是 **chunk**,而 `recall@k` 算的是 **doc**。
同主题文档(如 `LiveRAG-XXX-doc1` / `doc2`)的 chunk 在向量空间里扎堆,互相挤占那 10 个名额,去重后只剩 1.6 篇。

38 条部分失败**无一例外**是这个形态:

```
期望 LiveRAG-25-doc1 + 25-doc2   →  只中了 25-doc1
期望 LiveRAG-499-doc1 + 499-doc2 →  中了 228-doc2, 499-doc2   ← 无关文档挤掉 499-doc1
```

---

## 2. 基线数据(执行前必读)

⚠️ **数据集已于 2026-09-18 从 856 条补齐到 895 条**(与服务器一致)。旧基线 `run 81`(856 条 / recall@5 = 0.9521)**已作废,不可与新结果对比**。

**第 0 步开始前,必须先跑一次 895 条 + 当前参数,作为新基线。**

已知的旧基线(仅供理解形态,不可用于对比):

| 指标 | 值 | 备注 |
|---|---|---|
| recall@5 | 0.9521 | |
| recall@10 | 0.9521 | **与 @5 相同 → 该指标当前无区分度** |
| precision@5 | 0.2161 | 恒定;单文档期望下 = 1/5,**该指标无信息量** |
| 召回 doc 数分布 | 0→4, **1→499**, 2→223, 3→94, 4~5→35, 8~9→2 | 58% 只覆盖 1 篇 |

---

## 3. 实施路线

### 第 0 步:参数实验(零代码,先做)

**目的**:在动任何代码之前,回答"修 B 到底能拿回多少"。

`contextTopK` / `candidateLimit` / `recallBudget` 都是评测参数,前端可调,**不改一行代码**。

| 参数 | 当前 | 实验值 |
|---|---|---|
| `contextTopK` | 10 | **30** |
| `candidateLimit` | 40 | **120** |
| `recallBudget` | 20 | **60** |

> 三个必须**一起调**。只抬 `recallBudget` 不抬 `candidateLimit`,放大的候选在 RRF 后又被砍回 40,白做。

**需要跑两次评测**:

1. 895 条 + **当前参数** → 新基线
2. 895 条 + **放大参数** → 实验组

**观察与判定**:

| 观察 | 说明什么 | 下一步 |
|---|---|---|
| 召回 doc 数分布变宽(1.6 → ≥3),38 条部分失败改善 | **B 可治**,第二篇文档确实在候选池里 | 做第 2 步 |
| 覆盖变宽,但 38 条部分失败**没改善** | 那 38 条的第二篇**不在候选池** → 其实是 A 问题 | 跳过第 2 步,直接做第 3 步 |
| 覆盖**没变** | 候选池不是瓶颈,假设被推翻 | 停下来重新归因 |
| 23 条零召回有改善 | 原属 A 的那部分其实是候选池太小 | 并入第 2 步收益 |

**这一步同时给出 A/B 的迁移量**,避免后续做无用功。

**验收标准**:能明确回答"召回 doc 数 = 1 的占比从 58% 降到多少"。

---

### 第 1 步:补观测能力(前置,不做则后续实验不可复现)

| 缺口 | 位置 | 现状 | 影响 |
|---|---|---|---|
| **参数快照不全** | `EvalParamSnapshot` | 不记录 `rrf-k` 与通道权重(硬编码在 `FusionPostProcessor`:向量1.0/关键词0.8/GRAPH0.5/WEB0.4,rrf-k=20) | 改动后**无法从 `param_snapshot` 复现实验** |
| **chunk 级指标未落库** | `sa_eval_metric` | 只存去重后的 `retrieved_doc_ids` | 看不到 10 个 chunk 来自哪几篇文档 → **B 无法归因** |

**多查询一旦引入二维权重(query × 通道),这个缺口会更大。**

**验收标准**:任取一次 run,能仅凭 `param_snapshot` + 明细表复算出全部指标。

---

### 第 2 步:文档级聚合(治 B)

**落点**:`RerankPostProcessor` 之后新增一步 doc 级聚合。

**不要简单按 doc 分组取 topN**,推荐:

1. 按 doc 聚合分数(`max(chunk score)` 或 top2 平均)
2. 每篇 doc 保留 **topM 个 chunk**(M = 1~3)
3. 全局取 topN 篇 doc
4. doc 内再按 chunk 分数取最终 topK

**⚠️ 关键约束**:评测的 context 由 **`RagContextAssembler`(线上同款)** 组装,`context_recall` 直接吃它的输出。
所以 **M 和 topK 同时决定"喂给 LLM 的上下文"** —— 这个参数**不能只按评测指标调优**,要兼顾线上答案质量(上下文长度、token 成本、噪声)。

**对照组**:`DocumentDiversityPostProcessor`(MMR,现成,`isEnabled()` 当前返回 false)。
MMR 保留 chunk 粒度排序信息,但对精确查询可能降权"正确但重复"的结果。**作为对照,不作首选。**

**验收标准**:召回 doc 数 = 1 的占比下降;38 条部分失败显著改善;**同时** chunk 级指标不劣化。

---

### 第 3 步:确定性多路(治 A,零 LLM 成本)

**形态**:原查询 + 纠错 + 关键词抽取,**保留原查询且权重最高**。

**落点**:

- `MultiChannelRetrievalEngine` 接收 `List<String>`,内部展开成 **多 query × 多通道** 矩阵 → **统一 RRF**
- ⚠️ **不能在 `RetrievalTool` 层循环调用**:那样每路各自 RRF 再合并,分数不可比,融合语义混乱
- `RetrievalTool` 的查询来源要从"单一 `rewritten_query`"(其类注释明确的设计前提)改为"一组"

**权重设计**:引入二维权重后**必须归一化**(`∑w_q = 1`),否则多查询退化成"多投票"——弱相关文档因多路出现而虚高。

**监控**:单路冠军掉出率(多路累加可能压掉单路精确冠军)。

**验收标准**:零召回条数下降,且原查询单独一路的召回结果不被稀释。

---

### 第 4 步:LLM 多查询扩展(治 A,成本乘法增长)

**仅在 knowledge 链路开启**。

**ops 诊断链路保持单查询或仅确定性纠错**——那条链路的 query 短且精确(`发票冲红失败怎么处理`),
含错误码 / 堆栈片段 / 日志原文 / 版本号,属于**不该重写**的场景。

**分类路由的基础设施已有**:`kb_classify` 节点 + `CachingIntentClassifier`,复用即可,不必新建分类器。

**成本**:多 query × 多通道 = embedding 次数、检索次数、延迟均为乘法增长。895 条评测的耗时需实测。

---

## 4. 已实施的独立小改动

| 改动 | 落点 | 状态 |
|---|---|---|
| 扩词语言分流 | `QueryRewriter.expand` | ✅ 已实施 |
| 英文纠错改为按需触发 | `KbRewriteExecutor` 反思轮 round 1 | ✅ 已实施 |
| 新增用例 | `QueryRewriterTest`(6 个) | ✅ 已实施 |

**① 扩词语言分流**

原先英文 query 会被追加**中文**扩词(`what is X 处理 流程 步骤 规范`)→ embedding 跑偏。
现在中文查 `EXPANSIONS_ZH`、英文查 `EXPANSIONS_EN`,按 query 语言选表。

> ⚠️ `EXPANSIONS_EN` 目前沿用运维诊断场景的语义(流程/配置/排查)。**这是场景相关的,不是通用扩词**——
> 若该链路服务百科型语料(LiveRAG 这类),这三项几乎无增益,需按语料重设。

**② 英文纠错按需触发**

`spellFix` 原先藏在 `rewrite()` 内部,而 `RewritePolicy` 在 `AUTO`(无上下文)/`OFF` 下根本不调改写器
→ 英文自包含问题拿不到纠错。

但**首轮无条件纠错**会给每道题加一次 LLM 调用(895 题 × 1 次 = 成本过高)。
最终方案:**只在反思轮 round 1、且为英文 query 时触发**,纠不出东西(原文无错拼)再退到扩词。

> 成本从 O(全部题) 降到 O(失败题);代价是失败题多一轮检索。
> ⚠️ **实际节省取决于反思环触发率**——若触发率很高(如 >50%),收益有限。需从日志或 OpenObserve
> trace 实测确认(`kb_rewrite` 进入次数 / 总题数)。
>
> 注:`RewritePolicy` 管的是"要不要语义改写",拼写纠错不是改写(逐词对应、不换表述)。
> 英文语义改写早已被验证为净负优化(run57 vs run56 recall@5 **-3.5pp**),保持关闭。

---

## 5. 已知约束与风险

1. **改 chunk 选取会连带影响线上**:`RagContextAssembler` 是线上同款,评测改动即线上改动。
2. **`recall@10` 当前不可信**:去重后 doc 池 ≤5 的占绝大多数,`limit = min(k, size)` 让 @5/@10 看到同一集合。**任何要用 @10 的结论都必须先修这个口径。**
3. **`precision@K` 在当前数据集上无信息量**(单文档期望 → 命中 1 条即为 1/K)。
4. **参数实验的边界**:放大候选池必然增加检索耗时与上下文长度,实验组要一并记录延迟,避免"指标涨了但不可用"。
5. **已修复但未验证**:数据集补齐到 895 条后,**尚无新基线**,第 0 步产出的才是。

---

## 6. 度量口径

| 类别 | 指标 |
|---|---|
| A | 零召回率、候选池命中率、每路召回率 |
| B | **召回 doc 数分布**、平均覆盖 doc 数、**doc 级 recall** |
| C | MRR、nDCG@5/@10、单路冠军掉出率 |
| 成本 | 检索延迟、候选池大小、LLM 调用次数 |
| 人工 | 抽检失败案例:真相是否在候选池里但被挤掉 |

**必须同时看 doc 级与 chunk 级**——文档级聚合可能提升 doc 覆盖却降低 chunk 级 recall。

---

## 7. 明确不做的事

- ❌ 不把"召回差"笼统归因于重写——**`run 81` 的 `rewrite=false`,失败依然存在**
- ❌ 不用单一重写替代原查询
- ❌ 不上 MMR 作为首选方案
- ❌ 不在 ops 链路上 LLM 多查询
- ❌ 不在第 0 步结论出来前动手改检索主链路

---

<!-- 原文件：docs/plans/2026-09-18-small-to-big-retrieval.md -->

# 小块检索、大块生成（Parent-Child / Small-to-Big）

> 状态:实施中 ｜ 2026-09-18 ｜ 前置分析: 本文「召回策略优化方案」（本方案是其第 2 步的细粒度版本）

## 0. 一句话

把"打分排序的粒度"和"生成上下文的粒度"解耦：**子块（300~400 字）进检索与 rerank，命中后取父块（现役 600~1600 字结构感知块）组装上下文**。

## 1. 为什么要做（证据链，2026-09-18 实测）

### 1.1 四层稀释把"正确答案"的分数压到了阈值下

| 层 | 现象 | 实测数据 |
|---|---|---|
| ① 语料本质 | LiveRAG 期望文档多主题混杂，答案只占文档一小部分，部分相关是物理上限 | 全场最高分（acupuncture 直接对症）仅 0.5583 |
| ② 大块稀释（主因） | 长块语义平均化：embedding 全库压成窄带；rerank 被无关内容摊薄 | 全库 cosine 中位 0.31 / max 0.57，正确文档与第 10 名差 **0.006**；块均长 1438 字（p90 1827） |
| ③ 胖请求衰减 | rerank 分数随请求总长下降 | 同一文档：瘦批(12k 字符) 0.349 → 胖批(55k) 0.208 |
| ④ 阈值错位 | 0.3 落在"部分相关带(0.2~0.45)"正中间 | run 83 零命中 4 题全死于此 |

历史注脚：`application.yaml` 曾观测到"块合并到 600-1600 后部分相关块 rerank 被稀释(0.3~0.45)"，当时以阈值 0.4→0.3 适配——治标埋下病根。

### 1.2 对照实验（同内容、同字符预算、只变粒度；瘦批次 rerank）

题目 = run 83 **全部** 4 道零命中题 + 1 道半命中（普查非抽样；"6 条零命中"实为 4 题，1834 被重试计 3 次）：

| 题号 | 问题 | 整块期望分 | 子块期望分 | 抬升 | 干扰抬升 |
|---|---|---|---|---|---|
| 1954 | 燃烧三要素 | 0.4260 | 0.5106 | +0.085 | +0.028 |
| 1834 | business operations | 0.4240 | 0.5084 | +0.084 | +0.086 |
| 2685 | computrs/hart（错拼） | 0.3767 | 0.4850 | +0.108 | +0.070 |
| 1916 | acupuncture | 0.4907 | 0.5941 | +0.104 | +0.013 |
| 2299 | 音乐归档对比 | 0.5598 | 0.6930 | +0.133 | +0.060 |

- 期望内容平均 **+0.103**（5/5 为正）；干扰项仅 +0.051 → **非通胀，区分度真实拉开**；
- 子块后 5 题期望分全部 **≥0.485**，越过现有 0.3 阈值——**4/4 零命中题可救，且不需要动阈值**；
- 复合收益：子块化同时把 40 候选的 rerank 请求从 ~58k 字符缩到 ~16k（避开③的衰减带），真实管线总分抬升 **+0.2~0.35**。

诚实边界：干扰项是随机文档（非近似主题竞品），"间隔拉大"是下界；n=5 但覆盖零命中全集。

## 2. 设计

### 2.1 架构（改动面）

```
入库（platform-ingestion）
  chunker 产物 = 父块（不变，600~1600 结构感知块）
  → IndexerNode 内切子块（300~400 字，句子边界 + 重叠，复用 BoundaryAwareSplitter）
  → 子块写 spring_ai_store_vector（metadata 增加 parent_key，继承 doc_id/collection/doc_name/outline_path/keywords）
  → 父块写新表 sa_chunk_parent（parent_key = doc_id + 内容哈希前缀，确定性，重灌幂等）
  → 重灌删除路径同步清 sa_chunk_parent

检索（platform-knowledge）
  通道/RRF/rerank 零改动（它们只看到"行"，行变子块而已；子块短 → rerank 请求自然变瘦）
  → 新增 ParentAggregationPostProcessor（order=11，rerank 之后）：
      按 parent_key 聚合，父分 = max(子块 rerank 分)
      父块内容从 sa_chunk_parent 取回（JdbcTemplate）
      topK 父块 + 每父块命中小块数进 metadata
      无 parent_key 的块（存量未重灌数据）直通 —— 自门控，支持新旧混合灰度

生成（platform-business RagContextAssembler）
  零改动 —— 聚合器已把 content 换成父块，引用/编号按文档口径本就不受影响
```

零改动面：检索通道、引擎、编排、五层缓存（docver 自动失效）、eval 指标（按 doc_id 打分）。

### 2.2 配置

```yaml
rag:
  ingestion:
    child-chunk:
      enabled: true        # 入库切子块
      size: 400            # 子块目标字符
      overlap-sentences: 1 # 相邻子块重叠句数
  search:
    parent:
      enabled: true        # 检索侧父块聚合（无 parent_key 的块自动直通）
      max-children-cited: 3 # 每父块最多携带的命中小块数（进 metadata 供轨迹）
```

### 2.3 灰度与回退

- 代码先上、数据后灌：部署后检索行为不变（存量块无 parent_key → 直通）；
- 重灌哪篇哪篇生效（新旧混合安全）；
- 出问题关 `rag.search.parent.enabled` → 回到子块直出（上下文变碎但可用）；关 `child-chunk.enabled` 重灌 → 完全回到旧世界。

## 3. 代价与风险

- embedding 成本 ×4~5（5122 父块 → 约 2 万子块）；向量表/HNSW 2 万行无压力；
- 父块聚合后 contextTopK=10 变成 10 个父块席位，上下文总量上涨，`RagContextAssembler` 预算需观察；
- rerank 阈值 0.3 在子块世界可能重新合理（期望分带 0.49~0.69），但要用 eval 重新标定；
- 评测口径修正：`eval-recall-diagnosis.sql` 零命中统计 `count(*)` 应改 `count(DISTINCT item_id)`（重试重复计数的坑）。

## 4. 与 recall-optimization 计划的关系

- 本方案 = 该计划第 2 步（文档级聚合）的细粒度版本（parent=章节而非整篇文档）；
- 第 0 步参数实验（recallBudget 20→60 等）降级为本方案的对照组——它不碰四层稀释中的任何一层；
- 第 3/4 步（多路查询/LLM 多查询）与本方案正交，可后行。

## 5. 验收

1. 单测：子块切分（边界/重叠/短块直通）、聚合（max 语义、topK、无 parent_key 直通）；
2. 全模块测试绿；
3. 重灌后跑 eval：零命中 4 题（1954/1834/2685/2299）应 ≥3 题转命中；recall@5 / doc 覆盖数不劣化；
4. 轨迹 out 行可读：子块命中的父块聚合条数与分数。

## 6. 实施记录（2026-09-18 完成，待重灌验证）

| 改动 | 落点 | 状态 |
|---|---|---|
| 子块切分配置 | `IngestionProperties.ChildChunk`（enabled/target 400/max 500/min 120/overlap 60） | ✅ |
| 父块存 parent_key + 子块双写 | `IndexerNode.expandToChildren`（单块切不出多片则不展开——小父块本身就是合格子块） | ✅ |
| 父块存档表 + 反查索引 | `init.sql`：`sa_chunk_parent`(+doc_id 索引) + `spring_ai_store_vector(parent_key)` 部分索引；活库幂等验证通过 | ✅ |
| 存档生命周期 | `IngestionEngineService` 幂等清理、`IngestionController` 删除文档，两处 `DELETE FROM sa_chunk_parent` 镜像 | ✅ |
| 父块聚合 | `ParentAggregationPostProcessor`（order=11；父分=max 子分；父块原文批量 IN 取回，缺失回退子块内容；无 parent_key 早退直通） | ✅ |
| rerank 超采 | `RerankPostProcessor`：候选含子块时 topK×3（`rag.search.parent.rerank-overfetch`），防父块折叠吃掉席位 | ✅ |
| 生成侧组装 | `RagContextAssembler` **零改动**（用 `c.getContent()`，聚合器已换成父块原文） | ✅ |
| 单测 | `ParentAggregationPostProcessorTest`(4) + `IndexerNodeChildChunkTest`(3)；全仓 `mvn test` BUILD SUCCESS | ✅ |
| 重灌 + eval 复测 | 待执行 | ⬜ |

**运营口径**：代码先上不改变检索行为（存量块无 parent_key 一律直通）；**按篇重灌即按篇生效**，新旧可混合。回退：关 `rag.search.parent.enabled`（子块直出）或关 `rag.ingestion.child-chunk.enabled` 重灌（回旧世界）。

### 6.1 小样本验证（2026-09-18，7 篇期望文档回填后）

用 `ParentChildBackfillTool` 把 5 道测试题的 7 篇期望文档就地展开（41 父块 → 150 子块 + 40 父块存档），跑 `requisite triad flame combustion mechanism`：

| | 改造前 | 回填后 |
|---|---|---|
| kb_retrieve | 未检索到（40 候选 0 条达标）| **命中 1 条** |
| 命中文档 | — | **期望文档 LiveRAG-a1b2421e-163d1.md** |
| rerank 分数 | 0.23~0.29（全被 0.3 砍）| **0.4215**（过阈值）|
| 命中内容 | — | "This will allow oxygen to draft in for combustion to occur…"（燃烧三要素证据句）|

首轮原样透传即命中，**不需要靠重写救**。检索环节验证通过。

### 6.2 实施中发现并修掉的两个坑

1. **回填工具会删掉未展开的短父块**（`expandToChildren` 对切不出多片的父块 `continue`，工具的删除却没排除它）→ 内容丢失（实测 `fd34f40c` 丢了 1 块）。已改为「只删 sa_chunk_parent 里确有存档的父块」，短父块留在库里走直通分支。
2. **聚合器 `size() <= 1` 早退**导致单条命中时不做父块内容替换，LLM 只拿到 400 字子块片段。已改为仅空表早退，并补回归测试。

### 6.4 全量回填与验证（2026-09-18）

**回填结果**：970 篇 → 5005 父块存档 + **20099 子块**（116 个短父块按设计留作直通），耗时约 25 分钟。

**检索验证（5 道题）**：

| 题号 | 改造前 | 回填后 | 期望文档命中 |
|---|---|---|---|
| 1834 business operations | 零命中 | 48 条 / 17 文档 | **1/1** ✅ |
| 2685 computrs/hart | 零命中 | 56 条 / 20 文档 | **1/1** ✅ |
| 2299 音乐归档 | 零命中 | 28 条 / 6 文档 | **2/2** ✅ |
| 1916 acupuncture | 半命中 | 38 条 / 6 文档 | **2/2** ✅ |
| 1954 燃烧三要素 | 零命中 | 28 条 / 8 文档 | 0/1 ❌ |

**4/5 命中**（改造前 0/5）。1954 的回归有明确机理，见下。

**1954 根因：语料放大 4 倍后召回预算被稀释**

子块化把语料从 5122 → 20099 行，每通道固定取 `recallBudget=20` 的覆盖比例从 0.39% 掉到 0.10%。实测期望文档子块落在 **BM25 第 49 名**（该 query 的 5 个词太常见，BM25 有 107 条命中），根本进不了候选池。

**参数与策略对照实验**（1954，其余口径与线上一致）：

| 方案 | 候选池 | rerank 请求 | 期望文档最高分 | 全池过阈值条数 |
|---|---|---|---|---|
| A 现状 20/40 一次调用 | 37 | 16.7k 字符 | **不在池里** | 21 |
| B 放宽 60/120 一次调用 | 109 | 47.2k 字符 | **0.2822（被砍）** | 6 |
| **C 放宽 60/120 + 分批 12 条/批** | 109 | 每批瘦请求 | **0.3857（过阈值）** | **60** |

**放宽召回与分批 rerank 缺一不可**：不放宽答案进不了池（A）；放宽但一次喂 47k 字符会把全池分数压低（B，正是 §1.1 ③ 的长请求衰减）；分批后同样的池子分数标定恢复正常（C）。

**据此落地**（2026-09-18）：

- `rag.chat.recall-budget` 20 → **60**、`candidate-limit` 40 → **120**（原注释"100 噪声淹没步骤块"是父块时代结论，子块语义集中且有分批兜底）
- `rag.rerank.batch-size: 12` 新增，`BaiLianRerankClient.doRerank` 分批调用 + 跨批按分数合并取 topN

**重启后复测（5 道题全绿）**：

| 题号 | 期望命中 | 最高分 | 父块替换 | 通道召回 |
|---|---|---|---|---|
| 1954 燃烧三要素 | **1/1** | 0.4739 | 38/38 | keyword 60 / vector 60 |
| 1834 business operations | **1/1** | 0.5523 | 46/46 | 60 / 60 |
| 2685 computrs/hart | **1/1** | 0.5239 | 28/28 | 60 / 60 |
| 2299 音乐归档 | **2/2** | 0.6247 | 38/38 | 60 / 60 |
| 1916 acupuncture | **2/2** | 0.7510 | 40/40 | 60 / 60 |

**0/5 → 5/5**；父块替换全覆盖（聚合器生效，LLM 拿到的是父块上下文而非 400 字片段）。

### 6.3 回填工具（一次性，非常规测试）

`platform-ingestion/src/test/java/.../migration/ParentChildBackfillTool.java`——复用生产 `IndexerNode`（真实切分器 + 父块存档），**跳过解析与富集**（存量行的 keywords 已在 metadata 里），只花子块 embedding 的钱。默认跳过，须显式开开关：

```
BAILIAN_API_KEY=xxx mvn -pl platform-ingestion test -Dtest=ParentChildBackfillTool \
    -Dmigration.parentChild=true [-Dmigration.docIds=id1,id2] [-Dmigration.dryRun=true]
```
