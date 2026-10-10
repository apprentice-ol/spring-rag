# RAG + Agent 平台实现详解

> 本文讲清楚这个平台里的六件事**在代码里到底是怎么实现的**：多格式文档处理与结构化抽取、RAG 检索链路、
> Agent 架构与工作流编排、多智能体协作与横切治理、人在环交互与异常处理、评测与可观测闭环。
>
> 口径说明：全文事实以 **2026-09-22 的工作区代码**为准（行号、类名、配置默认值均逐条核对过）。
> 项目里的 `CLAUDE.md`、`plan/`、部分 `docs/` 描述的是**历史形态**（旧内核 `ToolLoopEngine` 一系、
> `ingestion-flow.md` 的 7 月版结论等），凡与之冲突处，以本文与代码为准。文末列出主要的口径校正点。

---

## 0. 全局：这个平台是什么，一条请求怎么走

### 0.1 定位与规模

基于 **Spring AI 1.1** 的企业 AI 平台，两条业务主线：

- **知识问答（RAG）**：多格式文档入库 → 多通道检索 → 融合精排 → 流式带引用回答；
- **运维诊断（Agent）**：对着业务日志排故障，槽位收集 → 三阶段工具循环 → 人在环中挂起/恢复 → 可追问的结论台账。

技术上有一处刻意的"不做"：**Spring AI 只当 infra-ai 用**（`ChatClient` / `EmbeddingModel` / `Advisor`），
而**向量的读写是手写 JDBC** 直操 `spring_ai_store_vector` 表（cosine + HNSW），没走 Spring AI 的 `VectorStore` 抽象。
原因在 §2.2 讲：集合过滤、索引路径固定、嵌入批量上限这三件事，抽象层拿不到控制权。

| 项 | 值 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5 · Spring AI 1.1 |
| 存储 | PostgreSQL 16 + pgvector + pg_search(BM25) · Redis 7 · RustFS(S3) |
| 模型 | DeepSeek（对话）· 百炼 text-embedding-v3（1024 维）· qwen3-rerank · qwen-vl-max（图片理解） |
| 模块 | 15 个 `platform-*` 子模块 + 父 POM |
| 代码量 | 主源码 719 个 Java 文件 + 80 个测试文件；内核 `platform-agent-core` 单模块 322 个 |
| 前端 | Vue 3 + TypeScript + Ant Design Vue（46 个源文件） |
| Prompt 资产 | 30 个 `.md`（rag / agent / ingestion / eval / vlm / workflow 六条链路） |

### 0.2 模块分层

```
customer-platform（父聚合 POM，包根 com.jjx.customer.platform）
├── 框架层（零业务依赖，可独立成仓）
│   └── platform-agent-core        图范式 Agent 内核（包根 com.agentframework）：
│                                  engine / definition / crosscutting / runtime / infra / extension / sdk
├── 基础设施层
│   ├── platform-common            工具类 / MyBatis 类型处理器 / 分页 DTO / 表达式与 Schema 校验
│   └── platform-shared            配置属性 / Prompt 装载 / 模型装配 + 跨域 SPI（delivery·conversation·document·routing·intent·clarify）
├── 能力域层
│   ├── platform-cache             CacheStore / Redis 装配 / 频率准入 / 断路器
│   ├── platform-observe           OpenObserve / Langfuse / Trace 采样
│   ├── platform-storage           S3 对象存储 + 文件元数据
│   ├── platform-knowledge         多通道检索 / 融合 / 重排 / 后处理 + RAG 生成
│   ├── platform-prompt            Prompt 资产 / 版本 / 能力包与绑定
│   └── platform-ingestion         解析 / 分块 / 增强 / 富化 + 文档目录
├── 业务层（调度 + 交付）
│   ├── platform-business          ★ engine（内核桥接）/ workflow（两域共用节点）/ knowledge（RAG 线）
│   │                              / ops（诊断线）/ orchestration（决策链）
│   ├── platform-delivery          SSE 通道实现 + 事件协议 / 会话消息持久化 / 入口薄壳
│   ├── platform-mcp               MCP 外部工具来源
│   └── platform-eval              评测：数据集 / 跑批 / 指标 / 对照 / 外部推送
└── 收口
    ├── platform-console           控制台视图 / 全局异常 / Agent 清单端点
    └── platform-bootstrap         唯一可执行装配点：启动类 / yaml / sql / static
```

依赖方向是硬约束：`bootstrap → 业务域 → 能力域 → shared → common`；
**业务域之间不互相 import 实现**，跨域只走 `platform-shared` 里定义的 SPI：
`DeliveryPort`（交付通道）、`ConversationStore`（会话事实）、`DocumentCatalog`（文档目录）、
`routing` / `intent` / `clarify` 契约。

### 0.3 一条 RAG 请求的完整调用链

```java
POST /chat/stream                                   // delivery.rest.ChatController（@TelemetryStep("rag.chat")）
 └─ ChatService → ChatOrchestrator<SseEmitter>.execute(...)       // business.orchestration，泛型 S = 传输载体
     ├─ 0   ResumeCoordinator.findResumable(...)                  // 有挂起/已结论的运维任务？有则直接转诊断
     ├─ 1   RouteEvaluator.evaluateFirstPass(...)                 // 规则短路（traceId 正则），零 LLM
     ├─ 1.5 显式范式 ?agentChoice=ops_diagnose → 直进诊断
     ├─ 2   DocReferenceClarifier.isDanglingDocReference(...)     // "这篇文档"没有先行对象 → 反问
     ├─ 3   组装 SearchContext（topK / threshold / budget）
     ├─ 6.4 ConversationStore.historyContext(...)                 // 两段查询取最近 3 轮
     ├─ 6.5 AnswerCacheCoordinator：精确缓存 → 语义缓存（pgvector ≥0.95）
     ├─ 7   KnowledgeRunner.retrieve(question, searchCtx, paradigm, RunContext)
     │        └─ 引擎跑 knowledge_qa 图：
     │            kb_normalize → kb_classify → kb_route → kb_route_gate
     │            → kb_rewrite → kb_retrieve（TOOL 节点 = 检索工具）→ kb_critique
     │                 ↑_______________________ 反思环（不足则回 kb_rewrite，上限 N 轮）
     │            → kb_done
     ├─ 8.3 闲聊/非检索意图 → ChitchatResponder 直答
     ├─ 9   chunks 为空 → 空检索降级文案（不进 LLM）
     └─ 10  RagAnswerStreamer：RagContextAssembler 组装上下文 → emitCitations（引用先行）
                → 流式生成 → 完成回调（落库 + 轨迹 + 缓存写回）
```

这条链上每一步都有短路出口——这是整个编排层的设计基调，§4.2 展开。

### 0.4 阅读地图

| 章 | 主题 | 核心类 |
|---|---|---|
| 1 | 文档处理与结构化抽取 | `IngestionEngine` / `DocumentParserSelector` / `StructuredChunkingService` |
| 2 | RAG 检索链路 | `MultiChannelRetrievalEngine` / `FusionPostProcessor` / `BaiLianRerankClient` / `RagContextAssembler` |
| 3 | Agent 架构与工作流 | `DefaultWorkflowRuntime` / `WorkflowBuilder` / `ActExecutor` |
| 4 | 多 Agent 协作与横切治理 | `AgentCatalog` / `ChatOrchestrator` / `Guard`·`Filter`·`Interceptor` |
| 5 | 人在环与异常处理 | `HumanRequest` / `AskMissingExecutor` / `HumanResponseInterpreter` / `RunOutcomeMapper` |
| 6 | 评测与可观测 | `EvalRunner` / `LangfusePushSink` / `@TelemetryStep` |

---

## 1. 复杂文档处理与结构化抽取

入库链路解决的是一个很物理的问题：**各种格式的原始文件，怎么变成"能被人看懂、也能被模型检索"的知识块**。
这条链在代码里是一串可配置的节点：`fetcher → parser → enhancer → chunker → enricher → indexer`。

### 1.1 入库是一条可配置的节点链

```java
// IngestionEngine：节点按 nodeType 注册成 Map，DB 里的 pipeline 配置决定跑哪些、什么顺序
public interface IngestionNode {
    String getNodeType();
    NodeResult execute(IngestionContext context, NodeConfig config);
}
```

四件事让它区别于"写死的 ETL"：

- **节点可插拔**：构造器注入 `List<IngestionNode>`，按 `getNodeType()` 收成 Map；
  `sa_ingestion_pipeline_node.node_type` 字符串直接查 Map 匹配 bean——**改 DB 配置即可调序/增删节点，不改代码**；
- **链式执行带环检测**：沿 `nextNodeId` 走一遍链，`path.contains(current)` 即抛"流水线存在环"；
  运行期还有 `executedCount > maxNodes` 防死循环；
- **条件求值**：条件为空直接过；文本条件走 SpEL（root = context，异常返 false）；
  对象型条件支持 `all/any/not/field` 组合，字段规则经 BeanWrapper 反射取值，
  operator 支持 `eq/ne/in/contains/regex/gt/gte/lt/lte/exists`；不满足时返回 `NodeResult.skip`，
  **但仍然写一条 success 日志**（审计链完整——"跳过了什么"和"做了什么"一样重要）；
- **默认流水线自举**：`PipelineBootstrap`（`ApplicationRunner`）在 DB 里没有 `default` 流水线时写入默认配置。

> 一个必须诚实说的现状：默认链里挂着 `enhancer` 节点，但 `rag.ingestion.enhancer-enabled: false`，
> 它每次只返回"跳过增强"就过去了。而且即便打开，`StructuredChunkingService` 的 block-aware 分支
> **只吃 `blocks`，不消费 `enhancedText`**——这是 7 月就发现、至今未修的一个断点：
> 文档级 LLM 增强在当前结构下没有生效路径。

### 1.2 解析：四种解析器按 MIME 分流

解析器只有一个必须实现的方法：

```java
public interface DocumentParser {
    String getParserType();
    boolean supports(String mimeType);
    ParsedDocument parseStructured(byte[] bytes, String mimeType, Map<String, Object> options);
}
```

选择逻辑短得出奇，但**关键在"不兜底"**：

```java
public DocumentParser selectByMimeType(String mimeType) {
    return strategies.stream().filter(parser -> parser.supports(mimeType))
            .findFirst().orElse(null);        // v1.1：不再静默兜底 Tika，无匹配返回 null
}
```

调用方 `ParserNode` 在 null 时**显式失败**——"这个格式我处理不了"要好过"用通用解析器糊出一堆垃圾"。

排序靠 `@Order` + Spring 的 `List` 注入顺序：

| 实现类 | @Order | supports 规则 |
|---|---|---|
| `MinerUDocumentParser` | `HIGHEST_PRECEDENCE` | pdf / wordprocessingml / msword / presentationml / powerpoint，**且 api-key 已配置** |
| `CsvDocumentParser` | `+10` | `text/csv`、`application/csv`、`text/comma-separated-values` |
| `MarkdownDocumentParser` | `+20` | `text/markdown`、`text/x-markdown`、**`text/plain`** |
| `TikaDocumentParser` | `LOWEST_PRECEDENCE` | `text/*` + json/xml/xhtml/rtf + PDF/Word/PPT 兜底；**显式排除 markdown / csv / excel** |

三个容易忽略的细节：

- **MinerU 的 supports 依赖配置**：没配 api-key 时它直接返回 false，PDF 自动回落到 Tika——
  这是"能力随配置启停"，不是两套代码；
- **`text/plain` 归 Markdown 解析器**：纯文本按 Markdown 语法解析，标题/列表/表格能被识别出来，
  比 Tika 一律拍成段落强；
- **Excel 没有解析器**：`ParserType` 枚举里确实有 `EXCEL_POI` 和 `IMAGE`，但**全仓没有对应的实现类**，
  且 Tika 明确排除了 spreadsheet 类 MIME。所以上传 Excel 会在解析这一步直接失败。
  这条要在介绍能力时如实讲：**当前支持的是 PDF / Word / PPT（MinerU）与 Markdown / CSV / 纯文本（本地解析）**，
  图片不是靠解析器而是靠 MinerU 抽取 + VLM 描述进入知识库的。

### 1.3 MinerU：远程异步解析与结构化还原

复杂版面的 PDF/Word/PPT 交给 MinerU（远程服务，整个过程是"申请上传 → 轮询 → 下载结果"）。
跨实例并发用 **Redisson 分布式信号量**控制（`concurrency-limit: 5`，`tryAcquire` 等 30s、租约 900s）：

```java
// 拿不到许可直接抛"解析任务过多"——宁可排队失败，也不把远端打挂
RPermitExpirableSemaphore.tryAcquire(maxWaitSeconds, TimeUnit.SECONDS, leaseSeconds, TimeUnit.SECONDS)
```

拿到结果 zip 后解包，**默认路线是 `content_list`**（不是 Markdown）：

```java
String unpackMode = extractString(options, OPT_UNPACK_MODE, "content_list");
...
if (wantContentList) {
    if (contents.contentList() == null) { blocks = unpackByMarkdown(...); route = "markdown(fallback)"; }
    else { blocks = unpackByContentList(...); route = "content_list"; }
}
List<Block> merged = mergeSplitCodeBlocks(blocks);       // 跨页代码合并
```

content_list 是 MinerU 给的结构化清单，按 `type` 分派成 `text / code / image / table` 四类块：
`text_level ∈ [1,6]` → 标题块；`code` 带语言白名单判定；`table` 的 `table_body` 是 HTML，交给 Jsoup 解析。

**表格合并单元格展开**（这是工业文档里表格能不能用的关键）：

```java
private static List<List<String>> expandMergedCells(Elements trs) {
    Map<Long, String> fill = new HashMap<>();                    // gridKey(row,col) → 已填值
    ...
    int colspan = Math.max(1, attrInt(cell, "colspan", 1));
    int rowspan = Math.max(1, attrInt(cell, "rowspan", 1));
    for (int dr = 0; dr < rowspan; dr++)
        for (int dc = 0; dc < colspan; dc++) {
            if (dr == 0 && dc == 0) continue;
            fill.put(gridKey(r + dr, c + dc), text);             // 合并格的值复制到每个被覆盖的格子
        }
}
```

效果是**每一行都自包含**：原来靠 `rowspan` 视觉对齐的行，展开后每行都把值写全——
这对下游的"每行渲染成 key-value 喂 embedding"是前提。展开失败时降级为段落块、保留 HTML 原文。

**跨页代码合并**解决的是 PDF 分页把一段代码切成两块的问题。判定不看行数，看括号配对：

```java
/** @return [depth, dangling]：depth = 未闭合的开括号数，dangling = 多余的闭括号数 */
private static int[] bracketStack(String code) { ... }

private static boolean shouldMergeSplitCode(CodeBlock a, CodeBlock b) {
    int[] x = bracketStack(a.code()); int[] y = bracketStack(b.code());
    if (x[0] > 0 && y[1] > 0) return true;        // 前段"开了没闭" + 后段"闭了没开"
    ...
}
```

为什么不直接数字符个数？因为净计数分不清两种"未配对"：`f(a, b` （缺闭）和 `a), b)`（多闭）
代数和可能都是 0，但语义完全不同。栈扫描能分别给出 depth 与 dangling，只有
"前段有未闭合 + 后段有多余闭合"才合并。

### 1.4 统一 Block 模型

所有解析器的产出统一成六种块（sealed interface，编译期穷尽）：

```java
public sealed interface Block permits HeadingBlock, ParagraphBlock, TableBlock,
        ImageBlock, CodeBlock, ListBlock {
    String id();
    Provenance provenance();        // (sourceFile, sheetName)
    List<String> outlinePath();     // 大纲路径（真实值由 Chunker 的 HeadingHandler 累积）
}

public record HeadingBlock(..., int level, String text) { }
public record TableBlock(..., List<String> headers, List<List<String>> rows, String captionText) { }
public record ImageBlock(..., AssetRef asset, String caption, String description) { }   // description 由 VLM 填
public record CodeBlock(..., String language, String code) { }
public record ListBlock(..., boolean ordered, List<String> items) { }
```

`TableChunker` 拿 `headers + rows` 而不是拿已经渲染好的 Markdown 网格——**结构化的块让下游能做结构化的事**
（按行切、每块带表头、渲染 key-value），这是"先统一模型再分块"的全部意义。

### 1.5 分块：按类型分流 + 段落贪心打包

分块只有一个入口 `StructuredChunkingService.chunk(...)`，内部四个分支：

```java
if (mode == PRE_CHUNKED && blocks 非空) return oneChunkPerBlock(blocks);   // ① 预分块 1:1
if (chunkSize == -1) return wholeDocumentChunk(blocks, fallbackText);      // ② 整篇单块（哨兵值）
if (blocks 非空) {                                                          // ③ block-aware（主路线）
    var sanitized = blockSanitizer.sanitize(blocks);
    int overlap = overlapForLanguage(languageSample(sanitized.blocks(), fallbackText));
    List<VectorChunk> chunks = blockAwareChunkerDispatcher.dispatch(sanitized.blocks(), config);
    return chunkJunkFilter.filter(chunks);
}
return chunkingStrategyFactory.requireStrategy(mode).chunk(fallbackText, options);   // ④ legacy 纯文本
```

主路线的流水是六步：

```
BlockSanitizer 清洗 → overlapForLanguage 定重叠 → HeadingHandler 维护章节栈
   → 六种 BlockChunker 分类型处理 → ChunkPacker 装箱 → ChunkJunkFilter 过滤残片
```

**① 清洗（宁漏杀不误杀）**：`BlockSanitizer` 只做三件事——删 U+FFFD 乱码字符、
去相邻重复标题、过滤导航行（同时满足"短 + 无句末标点 + 命中'返回../'特征"才杀）。

**② 分语言定重叠**：采样前 4000 字符，CJK 占比 ≥ 10% 取 **100 字符**重叠，否则 **200**。
理由是中文信息密度高（同样字符数 token 数约为英文的 4~5 倍），重叠预算减半即可。

**③ 章节栈**：`HeadingHandler` 维护 `outlinePath`（如 `["部署","环境要求"]`）；
语义化的 `H_N` 保留前 N-1 级。有个细节：**"实现代码 / 代码 / 示例 / Code"这类子标题不压栈**——
它们是章节内的示例小节，压栈会把一个完整章节切得粉碎。

**④ 六种块分别处理**：

| 块类型 | 策略 |
|---|---|
| Heading | 产出 `blockType=HEADING` 的块，交给 packer 向下合并（把标题和它的正文放一起） |
| Paragraph | 长段落交 `BoundaryAwareSplitter` 按边界切 |
| Table | 按行切，**每块重新渲染全表头** |
| Image | 原子（一块一 chunk） |
| Code | 原子，**永不切分** |
| List | 短列表原子（≤15 项且 ≤1800 字符），长列表按预算分组 |

**⑤ ChunkPacker：段落贪心打包**——这是"碎块"与"巨块"之间的平衡器：

```java
private static final Set<String> MERGEABLE_TYPES = Set.of("PARAGRAPH", "LIST", "IMAGE", "HEADING");
// 预算：target 1400 / min 600 / max 1800 字符

boolean atomic = !isMergeable(c, maxChars) || looksLikeCode(c);      // CODE/TABLE/含围栏的块 → 原子
if (!sameSection(buffer.get(0), c) && bufferLen >= minChars && !atomic) { flush(...); }   // 章节边界
if (afterAdd <= targetChars) { 继续累加; }
else if (bufferLen < minChars) { 忍一次并强制 flush; }               // 宁愿超一点，也不产碎块
```

三个设计点：

- **CODE/TABLE 原子**：合并会破坏结构（代码块中间插进一段说明文字，读者与模型都懵）；
- **章节边界 flush 有例外**：跨章节但缓冲区还没到 `minChars(600)` 时**忍一次**——
  "当前章节只有 200 字"就切出去会产出一个语义不完整的碎块；
- **`sameSection` 用 `outlinePath` 前缀关系判定**，不是比字符串相等。

**⑥ 碎块过滤**：`ChunkJunkFilter` 只在两种情况下丢块（CODE/TABLE 豁免）：
纯符号块（< 100 字符且字母数字占比 < 30%，典型是 MinerU 抽出的页码水印）、
无 CJK 的短残片（< 50 字符）。
**中文短块不按长度丢**——17 个汉字可能承载一条完整业务规则。

最底层还有个原子能力 `BoundaryAwareSplitter`（`ParagraphChunker`、legacy 分块、列表兜底、子块展开四处共用），
按 **6 级边界递归降级**找切点：

```
① 空行  → ② 单换行 → ③ 中文句末（。！？…）→ ④ 英文句末（.!? 且后接空白，防切 URL）→ ⑤ 分号 → ⑥ 逗号
        → 全级别都切不动才字符硬切
```

"永不抛异常、永不死循环"是它写在接口上的承诺——分块器是入库链最后一环，它挂掉整篇文档就白解析了。

### 1.6 表格、图片、代码的专门处理

**表格：一份内容，两种文本**

```java
String markdown    = renderMarkdownTable(headers, rows);                       // content：完整 markdown 表
String embeddingText = buildEmbeddingText(headers, rows, sectionContext, kvCache);  // key-value 串
return VectorChunk.builder().content(markdown).embeddingText(embeddingText)
        .blockType("TABLE").sectionContext(sectionContext).build();
```

- `content`（给人看、给模型看）= 完整 Markdown 表格，**每块都从 `block.headers()` 重新渲染表头**
  （不是从上一块复制，所以每块都能独立读懂）；
- `embeddingText`（给向量模型算）= `sectionContext + "列名: 值; 列名: 值"` 的 key-value 串。
  为什么？Markdown 表格的向量表示里"列名"和"值"的位置关系很弱，模型很难学到
  "这个 1024 对应的是 dimensions 字段"；渲染成 `dimensions: 1024` 就直白得多；
- `sectionContext`（`sheet=xxx; caption=...; headers=a, b, c`）与章节标题一起注入，补足脱离上下文后的语义。

切块预算按 **key-value 渲染长度**累加（而不是行数），`rowsPerChunk`（默认 50）退化成硬上限。

**图片：双文本策略**

```java
// ImageChunker：一个 ImageBlock = 一个 chunk
embeddingText = 描述（VLM 生成）      // 可检索
content       = 原图 Markdown 链接     // 可展示
```

不这么做的话，图片块在向量库里就是一串 URL 噪声，永远检索不到。VLM 描述由 `VlmClient`
（qwen-vl-max）生成，**在分块前用虚拟线程 + Semaphore 并发预生成**所有图片描述
（此前串行处理 20 张图要拖几分钟），失败按 `rag.vlm.fail-on-error` 决定是降级还是中断整篇入库。

**代码：永不切分**，保留 ``` 语言围栏；**列表**按字符预算分组，尾组过小且能并进上一组就并。

### 1.7 小块检索、大块生成（入库侧的另一半）

§2.4 讲了检索侧怎么用父子块，这里补入库侧怎么产出它们：

```java
// IndexerNode：父块切子块（target 400 / max 500 / min 120 / overlap 60），
// 切不出多片的父块不展开——小父块本身就是合格子块，强行挂 parent_key 只会让父块表与向量表双写同一份内容
Expanded expand = expandToChildren(chunks, metas);
childMeta.put("parent_key", parentKey);           // doc_id:chunk_index
```

子块继承父块全部 metadata（`doc_id` / `collection_id` / `outline_path` / `keywords`）再加 `parent_key` + `child_index`；
父块原文写进 `sa_chunk_parent`（`ON CONFLICT (parent_key) DO NOTHING`，重灌幂等）。

还有一个容易踩的坑值得记：**metadata 里的 `doc_id` 必须用 `docId` 不能用 `taskId`**。
写错的表现极其隐蔽——向量都入库了、检索也正常，只有评测的 `expected_doc_ids` 全是 0。
（task 与 doc 是双身份，平台里这两个 id 一直是分开的。）

### 1.8 "结构化抽取"这条描述，在代码里的真实落点

需求描述里的这条——"通过 LLM 字段抽取 + JSON 校验完成数据标准化，设计引用溯源、置信度分级过滤与未知项管理"——
**不是一个模块的能力，而是分散在三条链上的四类机制**。逐条对照代码，说清楚哪条成立、哪条不成立：

| 能力 | 真实落点 | 状态 |
|---|---|---|
| LLM 字段抽取（入库侧） | `EnricherNode`：逐 chunk 调 LLM 产 `keywords` / `summary` / `metadata` | 存在，但**宽松解析**：`JsonResponseParser` 剥围栏 + 取首尾括号 + LENIENT，**无 schema 校验**，解析失败静默返回空 |
| LLM 字段抽取（诊断侧） | `OpsSlotExtractor` / `AutoResolveExecutor`：把自由文本抽成槽位键值，带 `confidence` 字段 | 存在，且**有置信度闸门**（L2 ≥0.7 / L3 ≥0.5，低于阈值丢弃） |
| JSON 校验 + 回环修正 | `JsonSchemaValidator`（零依赖子集校验器）被 `ActExecutor.gateOutput` 调用 | 存在，但**只在 ops 的 resolve 阶段**（schema `agent/schemas/invoice_reverse.json`）；校验失败不是丢弃，而是把违规项回喂模型重来；**知识问答链传 `null`，不生效** |
| 引用溯源 | 检索侧：`RagContextAssembler` 产出 `<content ref="N">` + `Citation`，经 SSE `citations` 事件下发（§2.6） | 存在，属检索/生成链，不属入库链 |
| 置信度分级过滤 | 四道阈值：rerank 分数（0.1）、向量相似度（0.6）、意图置信度（0.7 才缓存 / 0.8 才进诊断）、证据充分性（topScore ≥0.3） | 存在，**全部在检索与 Agent 侧**；入库模块里没有任何 confidence 字段 |
| 未知项管理 | 槽位缺失台账（`missing_count`）+ `pending_ask` 追问挂起 + 空检索降级文案 | 存在，属 Agent/检索侧；**没有一个叫"未知项管理器"的东西** |

**诚实的结论**：入库链上的 LLM 抽取是"尽力而为"的富化（失败即丢弃，不影响入库），
真正带校验与置信度治理的抽取发生在**运维诊断线**（槽位 + schema 护栏 + 来源台账）。
把这套能力笼统写成"文档处理模块的字段抽取与校验"，面试时被追问代码会答不上来；
但拆开讲——"入库侧富化 + 诊断侧结构化抽取与校验"——每一条都有扎实的落点，反而是加分项。

### 1.9 可靠性与工程配套

- **三层幂等**：① 引擎开头 `DELETE FROM spring_ai_store_vector WHERE metadata->>'doc_id' = ?`
  并同批清理 `DELETE FROM sa_chunk_parent WHERE doc_id = ?`（父块存档与向量表同生命周期）；
  ② MQ 消费用 CAS 抢占（`UPDATE ... SET status='PROCESSING' WHERE task_id=? AND status NOT IN ('PROCESSING','DONE')`，
  `claimed=0` 直接跳过），防 at-least-once 重投并发跑两遍；③ docId 已有 `sa_document` 记录直接回 DONE。
- **MQ 双实现**：`rag.mq.type` 在 RocketMQ（含事务消息）与 Redis Stream（消费组，
  consumer 名 = hostname-pid 防多实例互抢，毒消息也 ACK 防死循环）之间切换；
  消费端 `Semaphore(5)` 限并发，与 MinerU 信号量、DB 连接池对齐。
- **嵌入与写入的批量**：嵌入批量固定 10（百炼 text-embedding-v3 的硬上限，超限 400），
  整批失败回退逐块、单块失败跳过；INSERT 批量 50（multi-row VALUES + `?::vector`），
  单批失败只跳该批，**成功数 > 0 即节点成功**——部分成功好过全量重跑。
- **docver 版本化失效**：入库落库后（含 FAILED，保守失效）双 INCR（全库 `_all` + collection），
  缓存 key 拼版本号自然换新，不做 `KEYS`/`SCAN` 扫删。

---

## 2. RAG 检索链路：从提问到带引用的回答

这一章按数据流顺序讲：**查询理解 → 双通道召回 → 融合精排 → 生成与引用**，
最后单独说两件事：检索怎么变成 Agent 工具，以及"答案可信"这条线在代码里到底靠什么守。

### 2.1 查询理解：先清洗、再改写、再分类

**归一化（纯函数，零 LLM）**——`platform-common` 的 `QueryNormalizer`：

```java
public static String normalize(String raw) {
    String s = toHalfWidth(raw);                                  // 1. 全角 → 半角
    s = WHITESPACE.matcher(s).replaceAll(" ").trim();             // 2. 压缩空白
    String prev;
    do { prev = s; s = NOISE_PREFIX.matcher(s).replaceFirst(""); } // 3. 循环去无意义前缀
    while (!s.equals(prev));                                      //    "请问一下/麻烦问下/帮我看下…"可叠加
    s = LEAD_PUNCT.matcher(s).replaceFirst("");                   // 4. 去首尾标点
    s = TAIL_PUNCT.matcher(s).replaceFirst("");
    return s.trim();
}
```

它**只清洗不改语义**：早年的"疑问句→陈述句"规则表已经删掉，语义改写全部交给 LLM——
规则改写会把"怎么处理"变成"处理方法"，看着更规范，实际把 BM25 赖以命中的字面词换掉了。

**改写（LLM）**——`QueryRewriter` 有三条实测驱动的策略，都体现在代码分支里：

```java
// rewrite(query, history, clarification)
if (!containsCjk(query)) {                    // ① 英文不走中文语义改写（实测 recall@5 -3.5pp）
    return spellFix(query);                   //    只做保守错拼纠正（rag/spell-fix 资产）
}
...
if (!StringUtils.hasText(rewritten) || rewritten.equals(query)) return query;  // ② 空/同文 → 原句
catch (Exception e) { return query; }                                          // ③ 异常 → 原句
```

第三条看似平淡，其实是**兜底原则**：改写永远不比不改更差。第一条则引出 `RewritePolicy` 三档：

```java
public enum RewritePolicy {
    AUTO,   // 无历史/无补充 → 原样透传；有上下文才改写（线上默认）
    OFF,    // 一律透传
    FORCE;  // 一律改写（评测对照用）
}
public boolean shouldRewrite(boolean hasContext) { ... }        // AUTO → hasContext
```

AUTO 档首轮透传是实测结论：自包含问题被 LLM 重述后，字面词被换掉，BM25 通道直接少了命中点。
真正需要改写的是**带指代的追问**（"它的超时时间呢"），那时 `hasContext=true` 才放行。

**意图分类**——`DefaultIntentClassifier` 输出 `{domain, confidence, needs_web_search, reason}`，
三个实现细节值得说：

```java
String userText = promptStore.raw("rag/intent/classify-user")   // 基础域：knowledge/greeting/chitchat/web_search
        + dynamicDomainSection()                                // 动态域：RouteRegistry.domainDescriptors()
        + historySection(recentHistory)                         // 历史尾段 600 字，标注"仅供判断话题延续"
        + "\n\n用户问题：" + question;
...
.options(ChatOptions.builder().temperature(0.0).build())        // 分类必须确定性
```

- **温度按调用点覆盖**：`ingestionChatClient` 是共享 bean（默认温度非零），实测同一问题 4 次能判出
  2 次 knowledge / 2 次 chitchat——所以这里显式把温度降到 0，而不是去改共享 bean。
- **域清单动态拼装**：新增一个 Agent 只要注册 `RouteRule.domainDescriptor()`，分类器自动知道有这个域，**零代码改动**。
- **兜底恒定 knowledge/0.5**：解析异常、字段缺失都落回知识检索——宁可多检索一次，不可乱路由。

### 2.2 双通道召回：手写 JDBC 的两个通道

**通道契约**很短：

```java
public interface SearchChannel {
    String getName();                      // "vector" / "keyword"
    SearchChannelType getType();           // 融合时用于取权重
    boolean isEnabled(SearchContext context);
    SearchChannelResult search(SearchContext context);
}
```

实现类由 Spring 收集成 `List<SearchChannel>` 注入 `MultiChannelRetrievalEngine`——加通道不改引擎。

**向量通道**（`VectorSearchChannel`）是手写 JDBC 最集中的地方：

```sql
SELECT content, metadata, 1 - (embedding <=> ?::vector) AS similarity
FROM spring_ai_store_vector
WHERE metadata->>'collection_id' = ?         -- 集合过滤
ORDER BY embedding <=> ?::vector LIMIT ?
```

执行前在同一连接上钉三个 GUC：

```java
st.setQueryTimeout(queryTimeoutSeconds);
st.execute("SET hnsw.ef_search = " + efSearch);          // 默认 200
st.execute("SET hnsw.iterative_scan = relaxed_order");
st.execute("SET enable_seqscan = off");                  // 关键：钉死 HNSW 索引路径
// finally：RESET 三个 GUC，防止污染连接池里的会话
```

为什么要 `enable_seqscan=off`？pgvector 的 HNSW 代价模型**随 ef_search 线性放大**：
语料涨到两万行以后，`ef=200` 的估算代价超过全表顺序扫描，规划器就翻到 Seq Scan——
热查询 150~200ms、冷缓存 6.7s。这不是"调优"，是**行为翻转**，所以要在会话内显式禁掉顺序扫描。
GUC 是会话级的，`SET` 和 `SELECT` 必须同连接；`SET LOCAL` 对 `iterative_scan` 在部分版本不生效，故用 `SET` + `finally RESET`。

**关键词通道**（`Bm25KeywordSearchChannel`）走 ParadeDB 的 `pg_search`（Tantivy 内核）：

```sql
SELECT content, metadata, pdb.score(content) AS bm25_score
FROM spring_ai_store_vector
WHERE content @@@ ?                 -- 空格 = OR；无空格连续串按短语匹配
ORDER BY pdb.score(content) DESC LIMIT ?
```

两个工程细节：

- `@PostConstruct` 启动期校验扩展与索引，缺索引就 `CREATE INDEX ... USING bm25`，失败置 `available=false`
  **降级为空结果**而不是让应用起不来；
- 拆词后逐词加引号转义——`14:00`、`2026-09-13` 这类串会让 pg_search 解析失败。

**三级检索预算**（`RetrievalBudget`）是这块的核心旋钮：

| 参数 | 含义 | 代码默认 | 线上配置 |
|---|---|---|---|
| `recallBudget` | 每通道召回条数 | 20 | **60** |
| `candidateLimit` | 融合后送精排的候选池 | 15 | **40** |
| `contextTopK` | 精排后给 LLM 的条数 | 5 | **10** |

> 引用配置值时要区分"代码默认"与"线上覆盖"——`application.yaml` 覆盖了全部三个，且注释里写明了每次调整的实测依据。

**通道隔离**：每个通道丢进固定线程池，带超时：

```java
CompletableFuture.supplyAsync(() -> ch.search(context), ragContextExecutor)
        .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)     // 默认 5000ms
        .exceptionally(ex -> 空结果);                            // 超时/异常 = 该通道退化为空
```

两个设计点：

1. **单通道失败不炸整体**——向量通道挂了还有关键词通道兜底；
2. `ragContextExecutor` 固定池（core=max=8、队列 16、`CallerRunsPolicy`）**本身就是 bulkhead**，
   配 `ContextPropagatingTaskDecorator` 把 MDC 和 OTel Context 带过线程（否则检索 span 会散成独立 trace）。

注意"超时"的语义：对向量通道来说超时不是"变慢"，是**整条通道消失**——
服务器实测过一次 HNSW 查询读盘 7.6s 撞上 8s 硬编码超时，通道整条挂掉、检索质量断崖，
所以现在把它配到 30s 并在 yaml 注释里标明"这是通道存亡开关"。

### 2.3 融合与精排：责任链上的七道工序

后处理是一条**按 order 排序的责任链**（`SearchResultPostProcessor.getOrder()`，启动期排一次）：

| order | 处理器 | 作用 | 状态 |
|---|---|---|---|
| 0 | `SiblingExpansionPostProcessor` | 同源扩展 | 代码在，默认关 |
| 1 | `DeduplicationPostProcessor` | 去重 | 生效 |
| 2 | `DocScopePostProcessor` | 文档范围过滤 | 生效 |
| 3 | `TicketTypeFilterPostProcessor` | 票据类型过滤 | 生效 |
| 5 | `FusionPostProcessor` | **RRF 融合** | 生效 |
| 10 | `RerankPostProcessor` | **交叉编码器精排** | 生效 |
| 11 | `ParentAggregationPostProcessor` | 小块→父块折叠 | 生效 |
| 15 | `DocumentDiversityPostProcessor` | MMR 多样性 | 代码在，默认关 |

**去重的键**必须带 `doc_id`：

```java
// ChunkIdentity = doc_id + "#" + sha256(content)
```

只用内容哈希会出事：LiveRAG 语料里同一篇文章挂在多个 `doc_id` 下，纯内容哈希会把期望文档和它的孪生副本合并掉，
而评测是按 `doc_id` 打分的——表现就是"明明检索到了内容，却记 0 分"。

**RRF 融合**（无参融合，解决"多通道分数不可比"）：

```java
score(c) = Σᵢ wᵢ / (k + rankᵢ(c))          // k = rrf-k（线上 20，代码兜底 60）
private double getChannelWeight(SearchChannelType type) {
    return switch (type) {
        case VECTOR -> 1.0;
        case KEYWORD -> 0.8;
        case GRAPH -> 0.5;
        case WEB_SEARCH -> 0.4;
    };
}
```

它只看**位次**不看分数，所以向量余弦相似度和 BM25 分数可以放进同一个池子排序。单通道时直接跳过融合。

**精排（Rerank）**用百炼 qwen3-rerank，这里踩过一个完整的坑，值得展开：

> **现象**：期望文档被整批砍光。
> **根因**：qwen3-rerank 的 `relevance_score` **随请求总长衰减**——同一篇文档在 12k 字符的请求里得 0.349，
> 喂到 55k 字符时掉到 0.208。109 个候选一次喂 47k，期望文档拿到 0.2822，被 `min-relevance-score: 0.3` 一刀切掉。
> **修法**：分批调用 + 跨批按分合并。

```java
private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN) {
    if (batchSize <= 0 || candidates.size() <= batchSize) return doRerankBatch(query, candidates, topN);
    List<RetrievedChunk> merged = new ArrayList<>();
    for (int from = 0; from < candidates.size(); from += batchSize) {
        List<RetrievedChunk> batch = candidates.subList(from, Math.min(from + batchSize, candidates.size()));
        merged.addAll(doRerankBatch(query, batch, batch.size()));   // 每批自己就是个"瘦请求"
    }
    merged.sort(降序);
    return merged.size() <= topN ? merged : new ArrayList<>(merged.subList(0, topN));
}
```

同一组候选、同一批数据，分批后：期望文档 0.3857（过阈），全池过阈值条数从 6 条涨到 60 条。

另外两个细节：**端点双段 URL**（官方新端点要 `.../text-rerank/text-rerank`，缺哪段补哪段，
否则 400 静默退化回向量序）；**失败 fallback** 是候选前 topN 的拷贝（`List.copyOf`，
避免 `subList` 视图与原列表联动）。批次大小线上取 20（≈8.8k 字符，距衰减拐点 12k 还有 1.4 倍余量）。

### 2.4 小块检索、大块生成

这是检索效果最关键的一次改造，思路一句话：**把"打分排序的粒度"和"生成上下文的粒度"解耦**。

- 入库时父块（600~1600 字结构感知块）再切成**子块**（target 400 / max 500 / min 120 / overlap 60 字符）进向量表，
  子块 metadata 带 `parent_key = doc_id:chunk_index`；
- 父块原文存独立表 `sa_chunk_parent`；
- 检索照常按子块打分，**精排之后**做一次折叠：同父的子块合并成一个席位，内容换回父块原文，分数取子块最高分。

```java
// ParentAggregationPostProcessor，order = 11（必须在 rerank 之后）
if (byParent.isEmpty()) return chunks;                  // 无 parent_key 的存量数据直通（自门控灰度）
meta.put("parent", true);
meta.put("child_hits", children.size());                // 轨迹里能看到"这个父块被几个子块命中"
RetrievedChunk parent = new RetrievedChunk(parentContent, best.getScore(), meta, best.getChannelType());
```

**为什么放在 rerank 之后**：打分发生在子块级——语义集中、分数区分度高；交叉编码器吃短文本效果更好；
聚合只是"折叠席位、换回长文本"。放前面就等于让 rerank 去打长父块，前面那个"分数被稀释"的坑会重现。

对照实验（run 83 四道零命中题 + 一道半命中，普查非抽样）：

| 题号 | 整块期望分 | 子块期望分 | 抬升 |
|---|---|---|---|
| 1954 燃烧三要素 | 0.4260 | 0.5106 | +0.085 |
| 1834 business operations | 0.4240 | 0.5084 | +0.084 |
| 2685 computrs/hart（错拼） | 0.3767 | 0.4850 | +0.108 |
| 1916 acupuncture | 0.4907 | 0.5941 | +0.104 |
| 2299 音乐归档对比 | 0.5598 | 0.6930 | +0.133 |

期望内容平均 **+0.103**（5/5 为正），干扰项只 +0.051——区分度是真的拉开了，不是整体通胀。
全量回填（970 篇 → 5005 父块 + 20099 子块）后复测，**5 道题 0/5 → 5/5 全部命中**。

代价也如实记录：embedding 成本约 ×4（5122 父块 → 2 万子块）；候选池放大后精排延迟上升，
所以 `candidate-limit` 后来从 120 降回 40（4 批 → 每批 20，单条检索 avg 3747ms → 2034ms，且不掉召回）。

### 2.5 检索是"一个工具"

这是把 RAG 和 Agent 两条线缝在一起的关键决定：**检索不是编排层的一个 if 分支，而是一个注册在工具表里的工具**。

工具定义（`RetrievalTool`）：

```java
public static final String TOOL_ID = "retrieve_knowledge";
public ToolSchema schema() {
    return ToolSchema.of(TOOL_ID, "在知识库中检索操作手册、接口文档、报文样例等资料…",
            ToolParameter.required("query", "string"),
            ToolParameter.of("topN", "number", defaultTopK));
}
```

在图里它就是一个 TOOL 节点：

```java
.node(ToolNodeDefinition.of(RETRIEVE_NODE, RetrievalTool.TOOL_ID, CHUNKS_SLOT)
        .withArgument("query", "${slots.rewritten_query}"))     // 参数从槽位取值
```

执行权在内核的 `ToolNodeExecutor`：解析 `${...}` 表达式 → 从槽位求值 → 调工具 → 写回输出槽 +
派生 `_data` 槽（结构化数据）+ 记 trace 消息。这条设计带来三个直接好处：

1. **改写真正作用于检索**——`kb_rewrite` 写 `rewritten_query`，`kb_retrieve` 读同一槽位，中间没有隐式耦合；
2. 同一个工具在 **ops 诊断线**里同样可被模型调用（`inv_act` 阶段白名单含 `retrieve_knowledge`）——
   知识库不只是"问答用的"，也是诊断 Agent 的一个信息源；
3. 对照实验里换"检索范式"（单次 TOOL 节点 vs think→act→decide 工具循环）只需换图，工具本体零改动。

### 2.6 生成与引用溯源

**上下文组装**（`RagContextAssembler`）决定喂给模型长什么样：

```java
Map<String, List<RetrievedChunk>> byDoc = new LinkedHashMap<>();   // 保持首现顺序 = 相关性顺序
...
group.sort(Comparator.comparingInt(RagContextAssembler::chunkIndexOf));   // 组内按 chunk_index 还原阅读序
sb.append("<content ref=\"").append(idx).append("\">\n");
// 一个 <content ref="N"> = 一份文档的全部命中块拼接
```

几个刻意的取舍：

- **按文档分组而不是平铺 chunk**：平铺会让同一文档的相邻块被拆成若干"独立资料"，模型引用时容易张冠李戴；
- **不注入文档名与分数**：会诱导模型写"根据《XX 手册》"这种话术，也会让它偏向高分块；
- 产出 `RagContext(text, docCount, citations)`，`Citation` 含 `ref/docId/docName/chunkCount/preview/sourceLocation`
  （来源链接通过 `DocumentCatalog` SPI 批量预查，查不到不影响主链路）。

**引用先行**：流式回答开始**之前**就把 citations 事件下发，前端先把角标面板渲染出来，再收正文：

```java
RagContext ragContext = ragContextAssembler.buildContextText(chunks);
String citationsJson = toJsonOrNull(ragContext.citations());
DeliveryPort port = deliveryPortFactory.beginStream(...);
port.emitCitations(conversationId, citationsJson);          // 引用映射先行下发
```

**SSE 事件协议**五个事件（`SseEventSender`，事件名与顺序就是前端 `chat.ts` 的消费契约）：

| 事件 | 时机 | 载荷 |
|---|---|---|
| `trace` | 流式前（一次性）+ 流末（完整版） | 执行轨迹 `TraceView` |
| `citations` | 流式前（一次性） | 引用映射数组 |
| `message` | 流式中（逐块） | 正文增量 |
| `meta` | 流末 | `messageId` / `traceId` / `paradigm` |
| `clarify` | 需要人回答时 | 结构化卡片（§5） |

`meta` 里的 `traceId` 是"消息 ↔ 轨迹 ↔ 全链路 span"三方关联的锚点：前端气泡拿它去查轨迹、跳 OpenObserve（§6.6）。

**引用角标的两道防线**（注意：都在提示词与前端，**服务端没有校验**）：

- prompt 资产 `rag-answer-kb.md` 把"信息边界"列为最高约束（"严禁使用任何外部知识…资料中没有的内容不得写入"），
  并要求逐段贴 `[N](#cite-N)`；调用侧还有一段贴近生成位置的 `<output-requirement>` 强指令——
  实测只在 system 里写，模型往往只标末尾一处；
- 前端渲染时**只保留 citations 里真实存在的 ref**（`ChatView.vue`），模型编出来的编号直接丢掉。

### 2.7 "答案可信"这条线：四重机制的代码落点

需求描述里的"Prompt 约束、工具调用约束、证据约束、输出校验四重机制"，
在代码里是**四类分散的落点**（不是某个统一组件），且强度不一，逐条讲清楚：

**① Prompt 约束**——真实存在且是第一道防线：

```markdown
# 信息边界（最高约束）
- 你只能基于本次输入中 <documents> 内可见的内容回答…
- 严禁使用任何外部知识、行业常识、个人经验或推测来补充事实。
- 资料能讲多少就讲多少。信息不足时，先回答能确认的部分，再说明边界，绝不编造。
```

**② 工具调用约束**——两层，都在引擎侧强制：

- 阶段白名单（`ActExecutor.allowedTools`）：不在白名单的工具**连 prompt 里都不会渲染**，
  模型根本不知道它存在；越权调用会被回喂 `[工具错误] 阶段 xxx 不允许使用工具 yyy`；
- Agent 级 `ToolPolicy.only(...)`：ops 四个工具、knowledge/react 只有 `retrieve_knowledge`。

**③ 证据约束**——三处：

- 空召回**不进 LLM**：`if (chunks.isEmpty()) { handleRetrievalEmpty(...); return; }` 直接走降级文案；
- 反思环判据 `kb_critique`：`累计命中数 ≥ requiredHits(1) && 最高分 ≥ minTopScore(0.3)`，
  不达标就走回边重写重检（不是重试同一个查询）；
- `RetrievalTool` 对空结果做归因（"通道有候选但被精排清零" vs "通道压根没召回"），写进观测文本。

**④ 输出校验**——**要如实说明边界**：`JsonSchemaValidator` 这个出口护栏只在 ops 的
resolve 阶段（`outputGate=true`）与 `validate_request` 工具上生效，**RAG 生成侧没有接线**
（构造 `ActExecutor` 时 `outputSchema=null`）。答案侧全部防线就是 ①②③ + 前端 ref 过滤。
任何"答案不合规则重新生成/拦截"的说法，代码里没有对应物。

### 2.8 缓存：五层键设计（当前总开关关闭）

键的构造集中在 `CacheKeys`，三个原则：

```java
private static final char SEP = (char) 1;      // 段间不可见分隔符，防拼接歧义
// SHA-256 前 12 字节 → 24 hex

public static String retrievalKey(SearchContext ctx, long docver) {
    parts.add(ctx.getQuery()); parts.add(ctx.getRewrittenQuery());
    parts.add(String.valueOf(ctx.getTopK())); parts.add(String.valueOf(ctx.getThreshold()));
    // budget 三个数、collectionId、restrictedDocIds（排序后）、intent、docver 全部入 key
}

public static String answerKey(String question, String paradigm, long docver, String promptHash) {
    return PREFIX_ANSWER + hash(question, paradigm, String.valueOf(docver), promptHash);
}
```

- **检索 key 必含 `SearchContext` 全参数**：评测要用同一套引擎做多参数扫描，
  参数不进 key 就会"跑不同参数返回同一个缓存结果"，指标全废；
- **`promptHash` 进答案 key**：改任何一层 prompt，精确缓存与语义缓存自动失效，不需要任何清缓存操作；
- **`docver` 版本化失效**：入库后 `DocumentVersionStamp.bump()` 双 INCR（全库 `_all` + collection），
  旧 key 不删、等 TTL 漂走（不做 `KEYS`/`SCAN` 扫删）。

> **诚实标注**：`rag.cache.enabled` 当前为 `false`（2026-09-17 排查时关闭，各层配置留档），
> 即默认部署下检索缓存 / 答案缓存 / 语义缓存**全部旁路**，改回 `true` 即恢复。

语义缓存在 `sa_cache_answer`（pgvector 1024 维 + HNSW），阈值 0.95 收得很紧，且 WHERE 里必须同时匹配
`paradigm + docver + prompt_hash`——防的是"相似 ≠ 同义"（"怎么冲红"和"冲错了怎么撤销"向量距离很近，答案完全不同）。
还有一条正确性决策：**带历史的追问整条旁路答案缓存**（缓存 key 没有会话维度，
"它的配置呢"这种句子不同会话字面相同，重放就会串答案）。

---

## 3. Agent 架构与工作流编排

这是整个平台最重的部分：一个**零业务依赖的图范式内核**，加上三张业务图。

### 3.1 为什么要自研内核

四个需求逼出来的（都能对应到真实痛点）：

1. **要挂起-恢复**：诊断 Agent 必须能"问用户一句、等半天、回来继续跑"。Spring AI 没有 workflow 概念，
   通用 Agent 框架也少有"跨进程检查点 + 按节点恢复"的原生能力；
2. **要声明式图而不是硬编码循环**：诊断流程是"三阶段 × 每阶段 think→act→decide × 阶段间 replan"，
   写成代码就是三层嵌套循环加一堆 flag；写成图，流程成了**可声明的数据**；
3. **要三层横切分离**：权限、缓存、熔断、超时、脱敏这些能力要在**所有节点、所有 Agent** 上统一挂载（§4.3）；
4. **要零业务依赖**：内核包根刻意用 `com.agentframework` 与业务包根 `com.jjx.customer.platform` 分开，
   目标是可以独立成仓发布。

### 3.2 图原语：节点、边、Region、动态边

四类声明式构件（都在 `definition` 包，纯数据 record，无状态）：

```java
public record WorkflowDefinition(
        String id, String version,
        List<NodeDefinition> nodes, List<Edge> edges,
        SlotsSchema slotsSchema, SlotPolicy slotPolicy,
        List<ToolRequirement> toolRequirements, PromptPolicy promptPolicy,
        List<RegionDefinition> regions,        // 显式 Region：治理锚点，不参与路由
        DynamicPolicy dynamicPolicy,           // 动态路由白名单
        Map<String, Object> metadata) { ... }

public record Edge(String from, String to, String condition, String guardRef, int priority, String label) { ... }

public record RegionDefinition(String id, Paradigm paradigm, List<String> nodeIds,
        RegionPolicy policy, RegionLoop loop, String slotPrefix, Map<String, Object> metadata) { ... }

public record RegionLoop(String entry, String exit, int maxIterations, String counterSlot,
        LoopConvergence convergence) { ... }
```

- **节点**是 sealed interface，恰好 7 种：LLM / TOOL / HUMAN / CONDITION / PARALLEL / SUB_WORKFLOW / CUSTOM，
  每种对应一个执行器（`NodeExecutorRegistry` 双索引：按 `NodeType`、按自定义名字）；
- **边**可以带条件表达式（`Expression`）、守卫引用、优先级；
- **Region** 是"循环 + 局部槽命名空间"的载体：`RegionLoop` 声明入口/出口/最大轮次/计数槽/收敛判据，
  `slotPrefix` 让阶段内有自己的 scratchpad（`inv_scratchpad` / `res_scratchpad` / `ver_scratchpad` 互不污染）；
- **动态边**是"跑起来才知道去哪"的出口，但受 `DynamicPolicy` 白名单约束——
  模型说要去某个节点，白名单里没有就拒绝并发事件，回退静态边（§3.4）。

**条件表达式是自研的零依赖引擎**（`Expression`，598 行，不是 SpEL）：

```
slots.quality == 'good'
slots.retries < 3 && !empty(slots.answer)
matches(slots.city, '^Shang')
'urgent' in slots.tags
```

支持字面量、点号路径、`== != > >= < <=`、`&& || !`、括号、集合字面量，
比较关键字 `contains / startsWith / endsWith / matches / in`，以及 `exists / empty / size / len / lower / upper / str / num / bool` 等函数。
选自研的理由和内核定位一致：**零依赖、可独立成仓**，且表达式是图的一部分，语义要完全可控。

> 一个容易踩的点：条件求值的**变量表有两套**。节点条件（`ConditionNodeExecutor`）拿的是
> `{slots, slot, input, sessionId, nodeId}`；而**边条件**只有 `{slots, slot}`。
> 写边条件时引用 `input` 会静默取不到值。

### 3.3 游标驱动的主循环

内核的心脏是 `DefaultWorkflowRuntime.run(WorkflowExecution)`，逐帧看（简化后）：

```java
Cursor cursor = session.cursor();
if (!cursor.started()) { cursor = Cursor.at(workflow.entryNode().id()); updateCursor(session, cursor); }
save(session, cursor, slots);                              // 初始检查点
List<String> visited = new ArrayList<>();
while (cursor.nodeId() != null) {
    if (visited.size() >= config.maxSteps()) return FAILED("超过最大步数限制：" + config.maxSteps());
    if (session.state() == SessionState.CANCELLED) { save(...); return CANCELLED; }   // 节点边界的取消检查
    NodeDefinition node = workflow.node(cursor.nodeId()).orElse(null);
    cursor = countIteration(cursor, node, resolved);        // Region 循环只在入口计数
    NodeResult result = invokeNode(node, context);          // 守卫 → 拦截链 → 执行器 → 过滤 → 守卫
    visited.add(node.id());
    slots.putAll(expandRegionSlotWrites(workflow, node.id(), result.slotWrites()));    // 短名 → prefix_name
    ...
    if (result.isSuspended()) { save(session, cursor, slots); return SUSPENDED(...); } // 游标不推进
    if (result.isFailed())    { return FAILED(result.error()); }
    Routing routing = resolveNext(result, node, workflow, slots, session, resolved, breakReason);
    if (routing.next() == null) { save(...); return COMPLETED(result.output()); }
    cursor = cursor.advanceTo(routing.next()).arriveVia(node.id() + "->" + routing.next());
    save(session, cursor, slots);                           // 每推进一步落一次检查点
}
```

三个设计点：

1. **逐节点 checkpoint**：每推进一步就把 `Session`（含游标）与 `Slots` 快照各 UPSERT 一行
   （`ops_engine_session` / `ops_engine_slots`）。挂起或崩溃后恢复到**节点粒度**，不是"从头再来"；
2. **挂起时游标不推进**：`suspended` 分支直接 `return`，`cursor.advanceTo` 只在正常路由时执行——
   所以恢复时会**重入同一个节点**，由节点自己做幂等判断（"有输入就完成，没输入再挂起"）；
3. **合并槽位写入**：Region 内用短名读写的槽，在写回时展开成全名 `{prefix}_{name}`——
   图声明用短名（可读），存储用全名（不冲突）。

**路由三级优先**（`resolveNext`）：

```java
String dynamic = result.dynamicNextNodeId();            // ① 动态边
if (dynamic != null) {
    String rejection = rejectionReason(node, dynamic, workflow, resolved, breakReason);
    if (rejection == null) { publish(ROUTE_DYNAMIC); return new Routing(dynamic, ...); }
    publish(Topics.ROUTE_REJECTED, ...);                // 拒绝不中断，回退静态边
}
String next = result.nextNodeId() != null               // ② 执行器显式指定的下一节点
        ? result.nextNodeId()
        : nextNode(workflow, node, slots, session, ...); // ③ 静态出边：priority + 条件 + 边守卫
```

动态边白名单校验就一行：

```java
if (!workflow.dynamicPolicy().allows(node.id(), target)) return "target_not_allowed";
```

**循环治理**是两层叠加：`RegionLoop.maxIterations`（图声明）+ 命名守卫
（`EngineBuilder.loopGuard(name, max)` 注册，节点 `NodeMeta.withGuards(name)` 引用）——
业务侧由 `OpsDiagnosisStages.Stage.maxSteps` 一个字段同时喂给这两处，改一处即可。
超限时抛 `LoopBreakException`，主循环捕获后**只允许走出 Region 的边**，
语义是"优雅收尾"（`SessionState.COMPLETED`），不是失败。

### 3.4 统一意图协议：把四种"意图"拉平

旧内核用过原生 function calling（手动驱动 `toolCalls`），第三代内核改成了**固定 JSON 文本协议**。
协议块不是写在 `.md` 资产里的，而是由 Java 侧渲染进 prompt（因为它要从工具 schema 派生）：

```java
// OpsPrompts.compose 渲染出的协议段
## 工具使用协议
需要调用工具时，只输出一行 JSON：{"tool":"工具名","args":{...}}
需要向用户补充信息时，只输出一行 JSON：{"ask_user":"要问的问题","slots":["希望用户补充的槽位名",...]}
判断继续查也无法推进（缺用户才知道的信息）时，只输出一行 JSON：{"escalate":"原因"}
可以直接给出结论时，只输出一行 JSON：{"answer":"最终结论"}
不要输出 markdown 围栏、不要输出多余解释。
```

**为什么放弃原生 function calling**（这是个会被追问的取舍）：

1. **控制流意图无法表达**：原生只能表达"调工具"，而"问用户 / 升级人工 / 直接回答"这三种意图
   在诊断场景里和"调工具"是**平级**的——它们是循环的出口，不是工具。硬塞进工具列表会让模型分不清
   "工具返回结果"和"结束这轮工作"；
2. **协议要跟着 prompt 走资产化**：协议块是 prompt 的一部分，进指纹、进版本、进缓存 key（§4.5），
   用原生协议这部分就游离在体系外；
3. 模型侧遵循率经联调验证可靠，且有兜底：解析失败**把原文当 ANSWER 终稿**——
   模型已经给出内容了，不因格式丢掉它。

解析器（`ActExecutor.parseLLMThink`）的容错顺序：

```java
Intent parseLLMThink(String raw) {
    Map<String, Object> parsed = parseOneLineJson(raw);      // 剥 ``` 围栏 → 取首 { 到末 } → Jackson
    if (parsed == null) return Intent.answer(raw.trim());    // 解析失败 = 直答
    if (parsed.get("tool") instanceof String tool && !tool.isBlank()) { ... return TOOL; }
    if (nonBlank(parsed.get("ask_user"))) return Intent.askUser(...);
    if (nonBlank(parsed.get("escalate"))) return Intent.escalate(...);
    if (nonBlank(parsed.get("answer")))   return Intent.answer(...);
    return Intent.answer(raw.trim());
}
```

四类意图的处理（`ActExecutor.execute` 的主 switch）：

| 意图 | 行为 |
|---|---|
| TOOL | 白名单校验 → 执行 → Thought/Action/Observation 追加进 scratchpad → `has_calls=1` |
| ANSWER | 过出口护栏（JSON 块按 schema 校验）→ 写 `stage_output` → `has_calls=0` 出环 |
| ASK_USER | 写 `pending_ask` + 结构化 `HumanRequest` → `NodeResult.suspended(...)` 挂起 |
| ESCALATE | `NodeResult.dynamic(node.id(), reason, ESCALATE_NODE)` 走动态边直达升级终态 |

**内核保留了原生通路**（`ModelGateway.withTools(...)` + `LlmNodeExecutor` 写 `_tool_calls` 派生槽），
只是当前装配的 `SpringAiModelProvider` 不投影工具契约——换一个 ModelProvider 实现即可切回。
"不是不能，是不选"。

### 3.5 三张业务图

**（1）ops_diagnose_v2：诊断主线，单图五模块**

```
extract_slots → auto_resolve（规则 → 日志反查 → LLM 推断三层自救）→ auto_gate
  ├ 齐备 → confirm_slots（确认门，CONFIRM 卡）→[动态边]→ inv_think
  └ 缺失 → collect_slots ⇄ slots_regate（问齐环 ≤3 轮）

INVESTIGATE(≤4步)：inv_think → inv_act(get_time/query_logs/retrieve_knowledge) → inv_decide
  └ has_calls>0 回 inv_think；否则 → replan_1 ─┬ continue → RESOLVE
                                              ├ adjust(额度1) → inv_think
                                              └ escalate → escalate_node
RESOLVE(≤5步，出口护栏)：res_think → res_act(retrieve_knowledge/validate_request) → res_decide → replan_2
VERIFY(≤3步)：ver_think（四段式结论）→ ver_act → ver_decide → conclude
（任一阶段 act 都有动态边 → escalate_node [ESCALATE 终态]）
```

**单一事实源**是这块最值得学的一招。`OpsDiagnosisStages` 里一个 `Stage` record 派生所有命名：

```java
public String thinkNode()  { return prefix + "_think"; }        // inv_think / res_think / ver_think
public String actExecutorName()    { return "ops-act-" + prefix; }
public String guardName()          { return "ops-max-" + prefix; }
public String promptAssetId()      { return PROMPT_NAMESPACE + "/" + name; }   // workflow/ops_diagnose_v2/investigate
public String counterSlot()        { return prefix + "_round"; }
```

改阶段只改一个文件。配套的是**启动期对账**（`OpsDiagnoseWorkflowFactory.reconcile`）：
从 start 节点 BFS 收集图可达节点引用的执行器，与各 `StageModule.provides()` 的联集比对——

```java
if (!missing.isEmpty() || !orphan.isEmpty())
    throw new IllegalStateException("阶段模块与图脱节（图是唯一事实源）："
            + "图引用但无模块提供=" + missing + "，模块提供但图未引用=" + orphan);
```

**任意一侧失配直接启动失败**——图与装配永远不会悄悄漂移。

**（2）knowledge_qa：RAG 主线**

```java
return withQueryUnderstanding(WorkflowBuilder.create(WORKFLOW_ID, "3.0.0"), REWRITE_NODE, shortCircuitDomains)
        .node(CustomNodeDefinition.of(REWRITE_NODE, REWRITE_EXECUTOR, REWRITTEN_QUERY_SLOT))
        .node(ToolNodeDefinition.of(RETRIEVE_NODE, RetrievalTool.TOOL_ID, CHUNKS_SLOT)
                .withArgument("query", "${slots." + REWRITTEN_QUERY_SLOT + "}"))
        .node(new CustomNodeDefinition(CRITIQUE_NODE, CRITIQUE_EXECUTOR, Map.of(),
                CRITIQUE_REASON_SLOT, NodeMeta.empty().withGuards(ITERATION_GUARD)))
        .node(new CustomNodeDefinition(DONE_NODE, KbFinishExecutor.EXECUTOR_REF, Map.of(),
                FINAL_OUTPUT_SLOT, NodeMeta.empty().withAttribute("terminal", true)))
        .edge(REWRITE_NODE, RETRIEVE_NODE).edge(RETRIEVE_NODE, CRITIQUE_NODE)
        .edge(CRITIQUE_NODE, DONE_NODE)
        .edge(CRITIQUE_NODE, REWRITE_NODE)              // 回边：不足则重来
        .region(RegionDefinition.of(REFLECTION_REGION, Paradigm.REFLECTION,
                        RETRIEVE_NODE, CRITIQUE_NODE, REWRITE_NODE)
                .withLoop(RegionLoop.of(RETRIEVE_NODE, CRITIQUE_NODE, Math.max(1, maxRewriteRounds))
                        .withConvergence(LoopConvergence.of(SUFFICIENCY_SLOT, 0.05))   // 收敛出口
                        .withCounterSlot(REWRITE_ROUND_SLOT)))
        .dynamic(CRITIQUE_NODE, DONE_NODE, REWRITE_NODE)   // 动态白名单
        .build();
```

反思环有三个"刹车"：**轮次上限**（RegionLoop）、**收敛观测**（`sufficiency` 增量 < 0.05 提前收）、
**计数槽**（`rewrite_round`）。

这里还修过一个事故：TOOL 节点每轮会覆盖写派生槽 `kb_chunks_data`，导致**反思轮空手会抹掉首轮命中**。
修法是引入跨轮累积槽 `kb_chunks_all`：critique 每轮按 `ChunkIdentity` 去重并入、按分降序，
判据也改成吃累积值而不是单轮值。

**（3）knowledge_qa_react：对照轴**

同一套查询理解链（抽成 `withQueryUnderstanding(...)` 静态构件，**两图逐字一致**——
"同一句『你好』一条轴走闲聊、另一条掉进工具循环"是对照实验不可接受的），
差别只在检索形态：`react_think → react_act → react_decide` 的工具循环（≤6 步），
每轮命中 append 进 `react_tool_chunks`。

### 3.6 状态分层：Cursor 管"走到哪"，Slots 管"知道什么"

内核没有大而全的 `AgentContext`，状态拆成四层：

| 层 | 类 | 职责 |
|---|---|---|
| 节点执行上下文 | `NodeContext`（record） | 执行器能读到的全部运行态：agent/session/workflow/cursor/slots/input/trace/branchInvoker |
| 槽位容器 | `Slots` | **工作流读写状态的唯一出口**；put 递增 per-slot version；`snapshot(sessionId)` 产出可持久化快照 |
| 游标 | `Cursor(nodeId, step, lastEdge, state)` | 断点；Region 循环计数存在 `state.LOOP_COUNTERS` |
| 会话 | `Session` | id/state/agentId/workflowId/messages/traceId…，`SessionState` = CREATED/RUNNING/**SUSPENDED**/COMPLETED/FAILED/CANCELLED |

**业务的四层上下文**（跨轮、跨天的组织方式）在表结构上是分开的：

| 表 | 归属 | 生命周期 | 存什么 |
|---|---|---|---|
| `sa_conversation` / `sa_message` | 交付层 | 对话 | 消息 + citations + clarify 卡片 JSON |
| `sa_agent_task` | 业务层 | **任务（工作边界）** | status(open/running/suspended/concluded/…)、stage、slots、autonomy_level、chainTraceId、attempt_count |
| `ops_engine_session` | 引擎 | **执行（游标）** | 整份 `SessionRecord` JSONB |
| `ops_engine_slots` | 引擎 | 执行 | `SlotSnapshot` JSONB |

一个 attempt 一个引擎会话：`attemptIdOf(taskId, n) = "ops-" + taskId + "#" + n`。
这样"同一次诊断追问三轮"是 task 不变、attempt 递增，每次执行各自有游标与槽快照，互不覆盖。

一个**真机才暴露的坑**值得单列：`ephemeral`（不落库的临时会话）原先只在 `startSession` 处检查，
而运行期**每节点 checkpoint 直接写 store，绕过了检查**——单测全绿，库里却在无限堆积孤儿会话
（实测 4141 个、76MB）。修法是把判定接到 `PersistenceManager.checkpoint` 上，
并在守卫测试里加反向断言（普通会话必须照常落库）。

---

## 4. 多智能体协作与横切治理

### 4.1 单引擎多 Agent：共享的是引擎，不是会话

三个 Agent 各自是一份声明（`AgentCatalog` 静态目录，取代了早期的运行期注册表）：

| id | label | workflowId | 意图域 | 能力位 | prompt 分层 |
|---|---|---|---|---|---|
| `ops_diagnose` | 运维诊断 | `ops_diagnose_v2` | ops_diagnose | — | 人格层 investigate/resolve/verify + 任务层 slot-extract/replan/auto-resolve/human-response |
| `knowledge` | 知识问答（eval 基线） | `knowledge_qa` | knowledge | STREAMING / CITATIONS / ANSWER_CACHE / SEMANTIC_CACHE / RETRIEVAL_METRICS | 任务层 spell-fix；生成段 rag-answer-kb |
| `react_loop` | 工具循环检索（对照轴） | `knowledge_qa_react` | react_loop | 同 knowledge | 人格层 react-loop；生成段共用 |

```java
private static final Map<String, String> ALIASES = Map.of(
        "naive", KNOWLEDGE.id(),        // 旧范式名兼容
        "react", REACT.id());
```

装配上，**一个 `Engine` bean 注册三份 AgentDefinition + 三份 WorkflowDefinition**：

```java
EngineBuilder.create()
        .toolRegistry(sharedRegistry)           // 同一个工具注册表
        .modelGateway(gateway)                  // 同一个模型网关
        .promptProvider(promptProvider)         // 同一个 Prompt 源
        .tracer(otelSpanTracer)                 // 同一个 OTel 桥
        .sessionStore(new PgEngineSessionStore(...))
        .slotStore(new PgEngineSlotStore(...))
        .workflow(opsWorkflow).agent(OPS_AGENT)
        .workflow(knowledgeQa).workflow(knowledgeReact)
        .agent(KNOWLEDGE_AGENT).agent(REACT_AGENT)
        .loopGuard(...)
        .build();
```

**"共享"的准确含义**（这里容易说错）：共享的是**引擎级组件**——同一条中间件管道、
同一个工具注册表、同一套会话/槽位存储表、同一个模型网关与 Prompt 源。
**会话状态是按 agent 语义分开的**：

- ops 用 `"ops-" + taskId + "#" + attemptNo` 的持久会话（可跨重启恢复）；
- knowledge / react_loop **每次提问都是 `asEphemeral()` 的单轮随机会话**（不落库）：

```java
// KnowledgeRunner：必须显式 startSession + asEphemeral()——
// agentEngine.run(agentId, input) 内部走 StartOptions.defaults()（ephemeral=false），
// 于是每一次知识问答都会生成一个随机 UUID 会话，往引擎两表各写一行且永不删除
Session singleTurn = agentEngine.startSession(
        agentEngine.loadAgent(entry.id(), "latest"),
        StartOptions.defaults().asEphemeral().withTraceId(ctx.traceId()));
```

**为什么要单引擎**：会话与恢复是引擎级的。如果知识问答一个引擎、诊断另一个，
"诊断追问跨轮"这件事就得在两个引擎之间同步状态——而追问链断一次，用户看到的就是"重新开始盘问"。

### 4.2 统一决策链：ChatOrchestrator

编排层是一个泛型骨架 + 8 个各管一步的协作类：

```java
public class ChatOrchestrator<S> {          // S = 传输载体，对编排层完全不透明
    ...
}
```

| 协作类 | 职责 |
|---|---|
| `ResumeCoordinator` | 查活动任务：本轮消息归入既有诊断任务，还是开新流程 |
| `RouteEvaluator` | 规则表第一次求值（意图为 null，正则短路类可命中） |
| `DocReferenceClarifier` | "这篇文档"指代悬空检测（纯正则 + 两次轻查询）→ 反问 |
| `AgentBranchDispatcher` | workflow 型 agent 统一入口：合并槽位 → 降级闸 → 跑 Agent → 按 Outcome 分派交付 |
| `AnswerCacheCoordinator` | 精确/语义两级答案缓存：准入、重放、命中回写、收尾写回 |
| `RagAnswerStreamer` | 组装上下文、引用先行、流式生成、完成回调落库 |
| `ChitchatResponder` | 非检索意图直答 |
| `DegradeGate` | Redis 降级期并发闸（三处共用） |

**六层路由优先级**（从确定性到概率性排）——注意其中只有两层是"规则表"里的规则类：

```
① 会话恢复（最高）   ResumeCoordinator — SUSPENDED 恒归任务；CONCLUDED 默认归（除非换目标强信号）
② traceId 短路       TraceIdShortCircuitRule(priority=10) — 正则命中直进诊断 + 预填 trace_id 槽，省 2 次 LLM
③ 用户显式选择       ?agentChoice=ops_diagnose → 跳过意图分类直进诊断（用户主权 > 概率判断）
④ ?agent= 兼容语义   仅 RAG 链内编排（旧客户端默认携带 agent=knowledge，不得被当显式选择）
⑤ 意图识别           OpsIntentRouteRule(priority=50) — domain 匹配 + 压制劫持 + 置信度门槛
⑥ 默认知识检索
```

```java
// OpsIntentRouteRule.match —— 三条判据缺一不可
if (ctx.intent() == null || !DOMAIN.equals(ctx.intent().getDomain())) return Optional.empty();
if (AgentCatalog.KNOWLEDGE.id().equals(ctx.agentChoice())) return Optional.empty();      // 显式选择压制劫持
if (ctx.intent().getConfidence() < chatProperties.getDiagnoseMinConfidence()) return Optional.empty();
return Optional.of(RouteDecision.to(AgentCatalog.OPS.id()));
```

`diagnoseMinConfidence` 线上从 0.6 收紧到 **0.8**，是一次实测驱动的调整：
分类器对裸关键词的猜测（`reason` 自称"疑似"）常给 0.6，正好卡着旧门槛放行——
实测一次追问 "Refresh-Token" 被判进诊断，答非所问。**"疑似"级置信应该落回知识检索。**

每一次求值都是"命中即返回、未命中降级"：规则表 `RouteRegistry` 按 priority 升序取首个命中。
设计理念是**"用户想问什么"与"由谁来答"解耦**，且每一步都有短路出口。

### 4.3 拦截器 / 过滤器 / 守卫：三层横切

三个接口解决三个不同的问题，这是全平台最容易混淆、也最见功底的一处设计：

| | Interceptor 拦截器 | Filter 过滤器 | Guard 守卫 |
|---|---|---|---|
| 回答的问题 | **怎么执行**（流程控制） | **数据变成什么样**（数据变换） | **能不能做**（准入决策） |
| 形态 | 环绕：`intercept(Invocation::proceed)`，可以选择不继续 | 管道：`filter(input)` 串行直通变换 | 谓词：`check(ctx)` 返回决策 |
| 接口签名 | `<T> T intercept(Invocation<T>, InterceptorContext)` | `O filter(I input, FilterContext)` | `GuardDecision check(GuardContext)` |
| 挂载点 | **6 个** AROUND_*（AGENT/WORKFLOW/NODE/PROMPT/LLM/TOOL） | **9 个**（BEFORE_AGENT_INPUT / PROMPT / BEFORE_LLM / AFTER_LLM / BEFORE_TOOL_CALL / AFTER_TOOL_RESULT / AFTER_NODE_OUTPUT / BEFORE_OUTPUT / AFTER_OUTPUT） | **13 个**（BEFORE/AFTER × AGENT/WORKFLOW/NODE/PROMPT/LLM/TOOL/OUTPUT + ON_ERROR） |
| 短路 | 不调 `proceed()` 即中断 | 无短路，只改数据 | Deny / AskApproval / BreakLoop 短路；Transform 改写载荷后放行 |

**链的折叠方式**是经典洋葱模型：

```java
// InterceptorChain：order 小的在外层
Invocation<T> current = terminal;
for (int i = applicable.size() - 1; i >= 0; i--) {
    Interceptor interceptor = applicable.get(i);
    Invocation<T> next = current;
    current = () -> interceptor.intercept(next, context);
}
return current.proceed();
```

`GuardDecision` 是 sealed interface 五变体，把"拒绝"细分到了可运营的粒度：

```java
public sealed interface GuardDecision
        permits Allow, Deny, Transform, AskApproval, BreakLoop {
    default boolean allowed() { return this instanceof Allow || this instanceof Transform; }
}
```

- `Deny` / `AskApproval` → 抛 `GuardDeniedException` → 引擎转 `FAILED`；
- `BreakLoop` → 抛 `LoopBreakException` → 引擎 `completeEarly`（`COMPLETED` + `LOOP_BREAK` 事件），
  **是优雅收尾不是失败**——用于"预算烧完/迭代到顶，但已有产出"的场景。

**执行主链**（`DefaultEngine.execute`）：

```
守卫(BEFORE_AGENT) → 输入过滤(BEFORE_AGENT_INPUT) → 拦截链(AROUND_AGENT)
  → 工作流运行时（节点级：BEFORE_NODE 守卫 → AROUND_NODE 拦截 → 执行器 → AFTER_NODE_OUTPUT 过滤 → AFTER_NODE 守卫）
  → 输出过滤(BEFORE_OUTPUT) → 守卫(BEFORE_OUTPUT) → 状态落库 → 事件与追踪导出
```

### 4.4 内置构件与"实际生效"的清单

框架自带的实现（`Interceptors` / `Filters` / `Guards` 三个工厂类）：

| 拦截器 | order | 要点 |
|---|---|---|
| `Retry` | 5 | 指数退避；`supports` 要求 `RetryPolicy.enabled()` |
| `Trace` | 10 | 开/关 span，异常也记 |
| `Timeout` | 15 | **虚拟线程 + `future.get(timeout)`**；必须经 `TaskPropagation.wrap` 才不丢 trace 上下文 |
| `MetricsInterceptor` | 20 | calls / errors / duration_ms 直方图 |
| `CircuitBreaker` | 25 | 进程内 `ConcurrentHashMap<String, State>`，按 key 分组 |
| `Cache` | 30 | 键 = `keyBuilder.build(namespace, operation, payload)`；命中发 `CACHE_HIT` 事件 |
| `Logging` | 1000 | 成功 DEBUG / 失败 WARNING |

过滤器：`Format(5)` / `Pii(10，邮箱·手机·身份证·银行卡正则掩码)` / `ContextWindow(20，保 SYSTEM + 最近 N 条)` /
`Dedup(30)` / `Length(40)`。
守卫：`AllowAll(0)` / `MaxIterations(1)` / `CostBudget(2)` / `ToolPermission(5)` / `Content(10)` /
`Rbac(20)` / `OutputCompliance(30)`。

> **诚实清单（很重要）**：框架把这些都实现了，但**业务侧装配只注册了其中一部分**。
> `AgentEngineConfiguration` 里没有任何 `.guard(...)` / `.filter(...)` / `.interceptor(...)` 调用，
> 实际生效的是 `EngineBuilder.buildMiddleware` 的默认集合：`AllowAll` + Trace + Metrics + Retry +
> Timeout + CircuitBreaker + Logging 注册进管道，业务侧的 loopGuard 由节点 `withGuards` 引用启用。
> 具体到每一条：
> - `Filters.*` 六个**全部未注册**；
> - `Guards.Content / Rbac / OutputCompliance / CostBudget` **未注册**；
> - `Guards.ToolPermission` 以**硬编码检查**的形式生效（不经管道）：`ToolNodeExecutor` 把 Agent 的
>   `ToolPolicy` 写进调用属性，`DefaultToolExecutor` 里直接判定——但这不改变它是"权限有强制"的事实；
> - `Interceptors.Cache` 注册了但**不生效**（`CachePolicy` 默认 disabled）；
> - `Interceptors.Retry` 对 LLM 不生效（`RetryPolicy.none()` 默认）；
> - **工具面的中间件整体不生效**：业务装配 `new DefaultToolExecutor(registry, null, null, null)`，
>   工具级守卫/过滤/拦截整段被跳过。
>
> 换句话说：**"三层横切架构"是真的，"全部构件都在线上跑"不是**。
> 这是一个可以诚实讲的取舍——框架提供能力，业务按需接线，未接线的部分是明确的技术债而不是隐藏的假设。

### 4.5 横切能力怎么"统一挂载"

**权限**：`AgentDefinition.policies().tool()` 声明 `ToolPolicy.only(...)`，
工具节点执行时把策略写进调用属性，`DefaultToolExecutor` 判定。工具白名单在 **Agent 级**，
阶段白名单（`allowedTools`）在**阶段级**，两层的交集才是模型实际能看到的工具。

**缓存**：`InterceptorAttributes` 定义标准属性键，LLM 节点与工具执行器各自填：

```java
// LlmNodeExecutor 写入
CACHE_INPUT     = model + "|" + prompt
CACHE_NAMESPACE = session.id()
CACHE_OPERATION = "llm:" + node.id()
CIRCUIT_KEY     = "llm:" + provider
TIMEOUT_POLICY  = TimeoutPolicy.of(model.timeout())
```

`InterceptorAttributes` 的完整键集（`cache.policy` / `cache.input` / `cache.namespace` / `cache.operation` /
`retry.policy` / `timeout.policy` / `circuit.key` / `trace.context` / `trace.parent` / `trace.span.name` /
`trace.span.kind` / `agent.id` / `node.id` / `tool.id` / `model.name`）就是横切层的"公共语言"。

**熔断**：引擎侧 `Interceptors.CircuitBreaker`（进程内、按 key 分组、无半开探测）
与业务侧 `RedisHealth`（保护 Redis 调用、有半开探测）是**两套独立的东西**，代码互不引用——
文章里若混为一谈就错了。业务侧真正影响用户体验的是后者（§5.6）。

**超时**：引擎侧 `TimeoutPolicy`（LLM 默认 60s，工具默认不限）；
业务侧另有 workflow 级墙钟配额 `QuotaPolicy.of(2_000_000, 300s, 60)`（token / 墙钟 / 工具次数）。

### 4.6 装配期校验：让配置失效"响亮地失败"

平台有一条贯穿始终的红线（§7 展开）：**配置悄悄失效不可接受**。落到装配期就是三处硬校验：

- `OpsDiagnoseWorkflowFactory.reconcile`：图与模块对账，缺/多任一即启动失败；
- `AgentValidator.checkBindings`：策略里引用的组件名必须在 catalog 注册过，否则 `POLICY_REF_UNKNOWN`；
- Prompt 能力包切换前 probe：合并后 `requiredKeys ⊆ contents`，缺 key 列明细抛异常。

---

## 5. 人在环交互与异常处理

### 5.1 统一原语：挂起 = 发出请求，恢复 = 交回决议

内核早就有了挂起/恢复原语（`NodeResult.suspended` → checkpoint → `engine.resume` 重入同一节点）。
人在环的四种形态**全部复用这一套**，没有另造机制：

```
挂起 = 引擎向人发出 HumanRequest（结构化请求决议）
恢复 = 人交回 HumanResponse（结构化决议），引擎消费后继续
```

协议件在 `com.agentframework.definition.workflow`：

```java
public record HumanRequest(Kind kind, String prompt, List<SlotAsk> slots,
                           DecisionContext context, List<Choice> options, boolean allowFreeText) {
    public enum Kind { CLARIFY, DECIDE, APPROVE, CONFIRM }
    public static final String PENDING_SLOT = "pending_human_request";
}

public record HumanResponse(Map<String, String> slotFills, Map<String, String> overrides,
                            String directive, String decision, Integer autonomyHint) {
    public static final String DECISION_PREFIX = "#decision:";
    public static final String OVERRIDE_PREFIX = "#override:";
    public static final String FILL_PREFIX     = "#fill:";
}
```

三个协议前缀都是**确定性回传**（零模型调用）：

| 前缀 | 语义 | 例 |
|---|---|---|
| `#decision:<value>` | 点选回传（选项 value） | `#decision:confirm` |
| `#override:<slot>=<value>` | 就地纠正某个槽（**空值 = 清空**） | `#override:time=` |
| `#fill:{json}` | 卡片多项打包（手写小解析器，不引 Jackson 依赖） | `#fill:{"environment":"prod"}` |

无前缀的整句进 `directive`（软指令）。设计上每个字段都可选、可并存——**抗分类误差**：
最坏情况退化成"整句当用户回复"，不会因为分错类丢信息。

### 5.2 四种形态与真实的生产路径

| Kind | 语义 | 生产代码路径 |
|---|---|---|
| `CLARIFY` | 问信息 | `AskMissingExecutor`（缺必填槽）、`ActExecutor` 的环内 `ask_user` |
| `CONFIRM` | 关键确认 | `ConfirmExecutor`（诊断开跑前的确认单） |
| `DECIDE` | 决策移交 | `ReplanExecutor`（replan 的 ask_human / escalate / 额度耗尽） |
| `APPROVE` | 计划审批 | **只有枚举定义与交付契约透传，没有执行器构造它**（P4 待做） |

**挂起的引擎语义**很干净：执行器返回 `NodeResult.suspended`，主循环保存检查点后返回
`RunOutcome(SUSPENDED)` 且**不推进游标**；恢复时重入同一节点，由节点自己幂等地消费用户的回复。

```java
// HumanNodeExecutor：有输入则完成，无输入再挂起
Object answer = resolveAnswer(human, context);
if (answer != null) {
    return NodeResult.completed(node.id(), String.valueOf(answer), Map.of(human.outputSlot(), answer))
            .withMessage(Message.user(String.valueOf(answer)));
}
return NodeResult.suspended(node.id(), renderPrompt(human, context));
```

> 注意口径：**内核的 `HumanNodeExecutor` 并不产出 `HumanRequest`**，它只挂起一段文本 prompt。
> 真正构造 `HumanRequest` 的是业务侧的三个执行器（AskMissing / Confirm / Replan），
> 它们通过 `withSlotWrite("pending_human_request", json)` 把结构化请求暂存进槽位。
> 两者是"语义同构"，不是同一份代码。

### 5.3 业务侧问用户：三个场景的细节

**（1）`AskMissingExecutor`——一次问齐，且有自救前置**

```java
// 重入分支：先解释器 → 再兜底抽槽 → 再重跑日志反查
if (!priorAsk.isBlank() && !reply.isBlank()) {
    writes.put(USER_CLARIFY_SLOT, reply);
    Interpretation interpretation = interpret(reply, context, confirmed);   // ① 语义通道（LLM 解释器）
    writes.putAll(interpretation.writes());
    if (!interpretation.filledSlots()) {                                    // ② 解释器没抽到槽才回落抽槽器
        Map<String, String> extracted = extractor.extract(reply, confirmed);
        writes.putAll(extracted);
    }
    rerunLogBackfill(node, context, confirmed, writes);                     // ③ 带新信息重跑反查
}
```

- **一次问齐**：缺 3 项只问一次（按目录序 join），不挤牙膏。真机反馈还催生了一个改动——
  **反查未命中时把 `trace_id`/`time` 这类关键槽摊开成核对项**（"请确认你给的信息是否准确"），
  因为实测出现过"连问 4 轮没人能改单号"；
- **解释器优先**：同一份目录 + 同一个模型，抽槽器再抽一次是重复付费；
- **反查重跑的时间边界**：`LogBackfill.rerun` 方法本身无条件，
  边界由**调用点**保证——只有 intake 阶段（信息收集/确认门）的挂起恢复才重跑。

**（2）`ConfirmExecutor`——确认单**

槽位齐备后**不直接开诊断**，先把"我理解到的信息"逐项摊开（值 + 来源 + 依据，可逐项改/清空），
点"确认，开始排查"才进阶段 1。放行判定：

```java
private boolean isConfirmed(String reply, HumanResponse response) {
    // decision=confirm 优先；但本轮改了任何东西一律不放行（刷新确认单再问一轮）
    // 否则才认保守确认语（确认/没问题/可以/开始排查…）
}
```

**（3）`ReplanExecutor`——决策移交**

replan 的四态：`continue` / `adjust`（每阶段额度 1）/ `ask_human` / `escalate`。
关键改动是：**`escalate` 从死亡终点变成了 DECIDE 挂起的一个选项**——
把竞争假设连同"判别动作"一起摊开给用户，终止只是选项之一：

```java
public boolean actionable() {                       // Hypothesis
    return !claim.isBlank() && !nextAction.isBlank();   // 说不出判别动作的假设，不许进决策卡
}
```

额度耗尽、ask_human、escalate 三种情况统一收敛为 DECIDE 挂起；解析失败/模型不可用则按 `continue`（保守放行）。

### 5.4 回复解释器：三条防线

用户回复是自由文本，既要理解它、又不能被它带偏。`HumanResponseInterpreter` 的防线是层层收缩的：

```java
String text = reply.trim();
HumanResponse deterministic = HumanResponse.parse(text);
if (text.isEmpty() || HumanResponse.isProtocolText(text)) {
    return new Outcome(deterministic, false);          // ① 点选/协议回传：零模型调用
}
...
try {
    String raw = model.ask(base + "## 槽位目录\n" + OpsSlotCatalog.renderForExtraction(),
                           userPrompt(pending, slots, text));
    Map<String, Object> parsed = parseOneLineJson(raw);
    if (parsed == null) return new Outcome(deterministic, llmCalled);        // ② 模型输出不可解析 → 退化 parse
    HumanResponse interpreted = reconcile(parsed, pending, slots);           // ③ 白名单 + 按当前值调和
    if (interpreted.isEmpty() && text.length() >= MIN_FALLBACK_LENGTH) {
        return new Outcome(deterministic, llmCalled);   // 模型判空但用户写了一段话：整句进 directive，不丢信息
    }
    return new Outcome(interpreted, llmCalled);
} catch (Exception e) {
    return new Outcome(deterministic, llmCalled);        // 模型不可用 → 退化到纯协议时代
}
```

防线的核心是 `reconcile`：

```java
Set<String> allowed = allowedNames(pending);                     // 目录槽 ∪ 本次问到的动态槽（编造的名字丢掉）
claimed.putAll(slotMap(parsed.get("slots"), allowed));
claimed.putAll(slotMap(parsed.get("overrides"), allowed));       // 显式纠正优先
claimed.forEach((name, value) -> {
    String existing = slots.get(name);
    if (existing != null && !existing.isBlank()) {
        if (!existing.equals(value)) overrides.put(name, value); // 已有值 → 归 overrides（是"纠正"）
    } else {
        fills.put(name, value);                                  // 空值 → 归 fills（是"补充"）
    }
});
```

按"当前有没有值"来分流，而不是信模型自己选的分桶——这一条把模型的分类误差压掉了。
另外：`decision` 只认本次 pending 声明过的选项（防越权路由）；槽值截断 2000 字符，再过目录归一化。

### 5.5 自主档位与来源台账

**档位**是会话级旋钮（L1 多问我 / L2 默认 / L3 少问我），差异只有三个参数：

```java
private static final double CONFIDENCE_L2 = 0.7, CONFIDENCE_L3 = 0.5;
private static final int RESOLVE_CALLS_L2 = 2, RESOLVE_CALLS_L3 = 3;

public boolean allowsInfer(Spec spec)  { return spec != null && spec.inferable()  && level != L1; }
public boolean allowsResolve(Spec spec){ return spec != null && spec.resolvable() && level != L1; }
```

**"只能收紧不能突破"的实现就是这么朴素**：L1 关掉推断与反查，L2/L3 与目录声明等值，
L3 的"放开"只体现在置信度门槛（0.7→0.5）与反查次数（2→3）。
目录那一侧是声明式的（`OpsSlotCatalog.Spec` 的 `inferable/resolvable` 字段）——
**自主性的边界由目录声明，不散落在执行器代码里**。

取值优先级上要按代码事实说：显式 `?autonomy=` 在分发层写进槽位；
`autonomy_hint`（用户回复里顺带调档）由执行器**当轮直接写入槽位**，所以
**同一轮里 hint 会覆盖显式参数**，次轮起固化进任务表。所谓"优先级链"是"同槽位后写覆盖"的涌现结果，
没有一个集中仲裁函数。

**来源台账**（`SlotProvenance`）是"用户主权显式化"的落地：

```
rule（确定性提取）→ default（目录缺省）→ llm（推断）→ log_query（日志反查）→ user_override（终态）
```

存储沿用 `inferred_slots` 的 JSON 数组（`[{slot,value,method,evidence}]`，同槽覆盖只留最新），
交付时映射到 `SlotQuestion{value, provenance, evidence}`——卡片上渲染成
「槽位 = 值 + 来源角标 + 依据 + 改成…」，用户随时可以把机器补的值推翻。

### 5.6 异常处理：五种"不静默"

**（1）预算三闸——全部"显式升级，绝不静默截断"**

| 闸 | 阈值 | 触发后 |
|---|---|---|
| LLM 调用数 | `max-llm-calls: 24` | `ActExecutor` 走动态边到 `escalate_node`，带原因 |
| 单次运行步数 | `EngineConfig.maxSteps = 100` | `FAILED`（防图失控） |
| 资源配额 | `QuotaPolicy.of(200万 token, 300s, 60 次工具)` | 抛 `QuotaExceededException` |

```java
int used = llmCalls(context);
if (maxLlmCalls > 0 && used >= maxLlmCalls) {
    String reason = "执行预算受限中断：LLM 调用数触及上限 " + maxLlmCalls + "（阶段 " + stageName + "）…";
    return NodeResult.dynamic(node.id(), reason, EscalateTerminal.NODE_ID)
            .withSlotWrite("escalate_reason", reason)
            .withSlotWrite("has_calls", 0);
}
```

**（2）工具失败隔离——失败是"结果对象"，不是异常**

```java
// DefaultToolExecutor：任何异常都转成 ToolResult.failed，不向上抛
try { result = middleware == null ? terminal.proceed() : ...; }
catch (Exception e) { result = ToolResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage()); }
```

失败文本回喂给模型（`[工具错误] ...`），模型下一轮看得到、可以换策略；
轨迹里另外下发结构化的 `{name, arguments, ok, error}`，不必再从观测文本里认前缀反推成败。

**（3）引擎态 → 业务出口的映射，保守优先**

```java
public static OutcomeKind kindOf(RunResult result) {
    if (result == null) return OutcomeKind.ESCALATE;                       // 保守：不确定就当要人工
    if (result.suspended() || result.suspendedNode() != null) return OutcomeKind.CLARIFY;
    if (escalated(result)) return OutcomeKind.ESCALATE;                    // 轨迹终态 → 到访 escalate 节点 → 槽含原因
    if (result.state() == CANCELLED) return OutcomeKind.CANCELLED;         // 用户中止：没有结论，单独成值
    if (result.state() == FAILED)    return OutcomeKind.ESCALATE;          // 失败不静默截断，交人工
    return OutcomeKind.DIRECT;
}
```

`CANCELLED` 单独成一个出口值是刻意的：**中止没有结论**，如果和 `DIRECT` 混在一起，
一次"用户点了停止"会被当成"正常收尾"落库，下次追问时系统会以为上次给出过结论。

**（4）取消：交付层与引擎的双保险**

交付层的 SSE `dispose` 与引擎侧是两回事——引擎并不知道 SSE 断没断。所以取消是两条路一起走：

```java
@PostMapping("/cancel")
public Map<String, Object> cancel(@RequestParam String conversationId) {
    boolean streamCancelled = activeStreamRegistry.cancel(conversationId);   // 停 SSE 流
    boolean diagnosisCancelled = opsRunner.cancelDiagnosis(conversationId);  // 引擎会话置 CANCELLED
    ...
}
```

引擎侧在**节点边界**检查状态（正在执行的节点打断不了，但不会开下一个），
任务 `release` 放回可恢复态，**绝不把中止记成 CONCLUDED**。

**（5）降级：断路器 + 动态限流**

```java
public Optional<Lease> tryAcquire() {
    if (!redisHealth.isDown()) return Optional.of(Lease.noop());   // 健康期零成本直通
    if (permits.tryAcquire())  return Optional.of(new Lease(permits));
    rejected.incrementAndGet();                                     // 降级期并发有上限
    return Optional.empty();                                        // 拿不到租约 → 礼貌拒绝
}
```

`RedisHealth` 是共享断路器：连续失败 5 次开路、冷却 30s、**半开探测**（放一次请求试水，
成功回 CLOSED，失败继续降级）。它保护的是 Redis 调用（缓存 get/put/touch、docver 读写），
故障时全链路旁路——`get` 失败当 miss，主链路零感知。

降级期间 `DegradeGuard` 把昂贵路径（LLM 流式 / ops 诊断）的并发收到 8，拿不到许可就发一条
"系统当前繁忙（缓存服务降级中），请稍后重试"——**宁可礼貌拒绝，也不让全体排队拖垮服务**。
注意降级文案不在 `DegradeGuard` 里（它只 log），而在交付层 `SseDeliveryPort.emitDegraded`。

### 5.7 澄清卡片：落库与重渲染

结构化卡片不能只是"发出去就完"——刷新页面后要能重新渲染。所以卡片是**双写**的：

```java
public void emitClarify(String conversationId, ClarifyRequest clarify, ...) {
    String askText = buildAskText(clarify);
    sseSender.sendClarifyEvent(emitter, clarify);                  // ① SSE 实时下发
    Long msgId = messageWriter.saveMessage(conversationId, "assistant", askText, null, toJson(clarify));  // ② 落库
    ...
}
```

落库列是 `sa_message.clarify JSONB`（`ALTER TABLE ... ADD COLUMN IF NOT EXISTS clarify JSONB`），
历史接口原样返回，前端拿到 JSON 重新渲染成卡片（正文文本还原不出选项与来源角标）。
序列化失败时返回 null → 只显示正文，**绝不阻断对话**——"卡片降级成文本"是可以接受的，"对话挂了"不可以。

---

## 6. 评测体系与可观测闭环

### 6.1 评测三件套：SPI + `@Component` 即注册

```
EvalScorer   打分器：name() + score(EvalSample) → List<EvalScore>
EvalResultSink 结果槽：onItemResult(sample, scores)（另有 onRunStart / onRunFinished 默认方法）
EvalRunner   跑批：异步 + 定并发
```

样本是四元组（对齐 RAGAS / DeepEval 的形状）：

```java
public record EvalSample(Long runId, Long itemId, int attempt, String remark, ...,
                         String question,
                         List<String> expectedDocIds, String expectedAnswer,
                         List<String> retrievedDocIds,
                         List<RetrievedChunk> finalChunks,
                         RagContextAssembler.RagContext context,        // 线上同款组装器的产物
                         String generatedAnswer, String agentTrace, String traceId,
                         long latencyMs, String error) { ... }
```

注册靠 Spring 收集：`EvalScorerRegistry(List<EvalScorer> componentScorers, ...)` 把容器里的 bean
与按 `ks` 配置构造的文档集打分器合并——**新增一个指标 = 新写一个 `@Component`**。

> 诚实标注：`EvalResultSink.onRunFinished` 定义了但**全仓没有调用点**（聚合指标由 `EvalRunner`
> 自己算完写 `sa_eval_run.aggregate_metrics`）。它是个预留接口，不是运行路径。

### 6.2 指标口径：每一行都值得看

| 指标 | 实现 |
|---|---|
| Recall@k | `前 k 命中数 / |expected|`（期望为空记 1.0） |
| Precision@k | `前 k 命中数 / k`（**分母固定 k**，缺位视为不相关） |
| MRR | 首个命中 `1/(i+1)`，无命中 0 |
| nDCG@k | `DCG = Σ rel/log2(i+2)`，分母 IDCG 的位次上限取 `min(|expected|, k)` |
| context_recall | `citations ∩ expected / |expected|`——吃的是 **RagContext 的 citations**，不是候选序列 |
| context_precision | `命中 / |contextDocIds|` |
| answer_correctness / faithfulness | LLM-as-judge（0-10 ÷ 10 归一） |

**nDCG 的分母是个真实的坑，值得单说**：

```java
// IDCG 的位次上限是 k（理想排序下最多 min(|expected|, k) 条相关），**不是 limit**。
// 用 limit 会让分母随"实际召回条数"缩水：期望 2 篇、只召回 1 篇且命中时
// limit=1 → IDCG=1 → nDCG=1.0——漏掉一篇反而拿满分，且召回越少越容易得高分。
// 实测 run82：期望2/召回1 的用例 nDCG@5 均值 0.833 = 命中率，与召回率 0.417 背离。
int idealHits = Math.min(expected.size(), k);
```

`context_recall` 与 `recall_at_k` 的差值也有明确含义：前者吃"最终进上下文的文档"，
后者吃"检索引擎返回的前 k 条"，**差值就是 `contextTopK` 裁剪 + 同文档折叠带来的损耗**。

指标名由 `EvalScore.name()` 决定（落库 `metric_name`），失败的条目由 sink 写一行
`metric_name = "error", score = -1`——**失败可见，不参与均值**。

### 6.3 "评测即真实"的三处同一

评测与线上共用：

```java
// EvalRunner —— 注入的是同一个 Spring bean
private final KnowledgeRunner frameworkKnowledgeRunner;      // 线上 ChatOrchestrator 注入的也是它
...
KnowledgeRunner.KnowledgeAnswer answer = frameworkKnowledgeRunner.retrieve(
        question, searchContext, params.effectiveParadigm(),
        KnowledgeRunner.RunContext.forEval(rewritePolicy));
```

- 同一个 `KnowledgeRunner`（同一条图、同一套节点）；
- 同一个 `RetrievalTool`（参数同源于 `ChatProperties`）；
- 同一个 `RagContextAssembler`（"评测吃到的上下文 = 线上推给 LLM 的上下文"）。

`RunContext.forEval(rewritePolicy)` 钉死两件事：`forceRetrieval=true`（不让领域路由把题目转走——
"被判成问候就跳过检索"的题会以"没检索过"的样子混进检索分里）与显式传入的改写策略。

> 但**"唯一差异点"这个说法经不起核对**，实际差异至少有四处：
> ① `forEval` 的 forceRetrieval 与改写策略；② 评测给 `SearchContext` 额外设了
> `collectionId` / `restrictedDocIds` / `metadata{intent=EVAL}`（线上这三个都为空）；
> ③ 评测的答案生成用 `eval/answer-gen` 资产 + 裸 client（基准要跨 run 可比，与线上系统提示不同）；
> ④ 单条重评走 HTTP trace 而非独立 root span。讲"评测即真实"时应该说"**关键路径同一**"，而不是"完全相同"。

### 6.4 LiveRAG 评测集导入

- 用 **DuckDB JDBC（内存库）读 parquet**，`ORDER BY random() LIMIT n` 在 SQL 层抽样——
  不引第三方 parquet 库；
- **支撑文档走真实入库链路**（`IngestionEngineService.executeTask(...)`）而不是直接塞向量，
  所以评测语料的分块、metadata、集合归属与生产完全一致；
- **按源 urn 跨题共享入库**：`ingestedByUrn.computeIfAbsent(...)` + 真实来源 doc_id
  （`<urn:uuid:9de87fbb-…>` 取 uuid 段）。实测 1032 条引用只对应 **970 篇唯一文档**，
  不去重会造成 62 个冗余副本 + 38 条"召回了内容却记 0 分"的用例；
- `expected_doc_ids` 存的就是入库返回的 `docId`，且 doc_id = 源身份——稳定、可追溯、重跑幂等。

评测数据模型五张表：`sa_eval_dataset` → `sa_eval_item`（含 `irt_diff/irt_disc/acs` 等 IRT 列）→
`sa_eval_run`（`param_snapshot` JSONB + `aggregate_metrics`）→ `sa_eval_metric`（每条×每指标一行）
+ `sa_eval_item_trace`（轨迹专表）。

**拆表是一次实实在在的性能修复**：轨迹原先随每个指标行复制一份（9 个指标 × 22KB），
表长到 395MB 时**其中 352MB（89%）是冗余副本**；拆成 `sa_eval_item_trace` 后
每 run+item+attempt 一行，且"先写指标、后写轨迹、轨迹独立吞异常"——轨迹是旁证，不能拖累指标。

### 6.5 跑批与推送：并发与背压

```java
// 固定 worker + 虚拟线程工厂（不做"每条一个虚拟线程"——895 条各挂一份未结束 root span 会压垮堆）
try (ExecutorService executor = Executors.newFixedThreadPool(
        workers, Thread.ofVirtual().name("eval-item-", 0).factory())) { ... }

// 全局单例信号量：多个 run 共享同一份并发预算
private Semaphore itemLimiter;   // new Semaphore(concurrency)，concurrency=8
```

run 级还有一层 fail-fast 闸（`maxConcurrentRuns=2`，超过直接拒绝并提示）。
失败隔离三层：单条失败记 error 指标不中断、单个 scorer 异常跳过、全部零召回才把 run 判 FAILED。

**Langfuse dataset run 推送**（`LangfusePushSink`）解决的是"评测结果只能在本系统看"的问题：

```java
// runName = eval-run-{runId}；score 同时挂 traceId 与 datasetRunId
LangfuseRunItem runItem = datasetClient.linkRunItem(new LangfuseRunItemLink(task.runName(), lfItemId, task.traceId(), null));
String datasetRunId = runItem != null ? runItem.datasetRunId() : null;
for (EvalScore score : task.scores()) {
    scoreClient.submitScore(new LangfuseScoreSubmission(score.name(), score.value(), score.comment(),
            datasetRunId, task.traceId(), null));
}
```

- **惰性同步**：条目按问题文本精确匹配既有 dataset item，miss 才创建（`expectedOutput` = 标准答案）；
- 推送**必须不拖累评测**：8 个虚拟 worker + 有界队列 4096，队满直接丢弃并 60s 节流告警
  （"指标已落库，丢推送不影响结果"）；`PushTask` 只捕获小字段，不钉住含数百 KB 轨迹的样本；
- 无凭据自动 no-op；自建 `LangfuseDatasetAdminClient` 是为了补外部 jar"只有 list/link/score、没有 create"的缺口。

跨版本对比的真实支撑点说清楚：**代码只负责写入**（dataset item + run link + score），
"跨 run 对比"是 Langfuse UI 侧的能力；本仓没有"版本 A vs 版本 B"的专门功能。
同 run 内做参数对照（单条重评 attempt）、多范式同题对照（传同一组 `itemIds`）倒是都有实现。

### 6.6 可观测：埋点、双发与三方关联

**埋点门面**来自自研 starter（`com.jjx.ai.llmobservability`，JitPack 坐标）：

```java
@TelemetryStep("rag.rewrite")                             // 注解式：AOP 自动开关 span
public String rewrite(String query, String history, String clarification) { ... }

@TelemetryStep(value = "rag.answer", captureOutput = true)  // 流式：span 延迟到首次订阅
public Flux<String> answer(String question, String context, String history) { ... }
```

生命周期按返回类型分派：普通返回值直接包裹；`Flux` 延迟到首次订阅才开 span、
`doFinally` 收尾（顺带采集 TTFT）；`SseEmitter` 走完成回调。AOP 覆盖不到的地方
（静态方法、同类内部调用）用 `TelemetryTemplate.step(...)` 手动包一层。

全仓 16 处注解埋点覆盖：`rag.chat`（入口）/ `rag.query.rewrite` / `rag.intent.classify` /
`rag.retrieve` / `rag.channel.*`（每个通道自动展开）/ `rag.postproc.*`（五个后处理器）/
`rag.rerank.call` / `rag.answer` / `rag.chitchat`。
维度由观测层从返回值自动提取，业务管道不申报：

```java
obsTemplate.dimensionOnOutput(IntentResult.class, r -> Map.of("intent", r.getDomain()));
```

数据流：span → processor 链（摘要 → 截断）→ exporter 链 → OTLP → 后端。
双后端由 `telemetry.collector.enabled` 切换：`true` 经 otel-collector 扇出（traces → OO + Langfuse），
`false` 直连。LLM 的 `gen_ai` span 与 token 用量由 Spring AI 原生观测输出，无需手工埋点。

**降量三道闸**（实测教训驱动：不降量时单条 trace 22~36MB、日志包 5~8MB，
OpenObserve 单请求要吞 8~17s，磁盘 I/O 打满、top wa 93%，最终 OO 崩溃退出）：

1. **导出白名单**：只导出 `/chat/stream` 前缀的 SERVER span，其余 HTTP（CRUD/上传/actuator）整条不发。
   为什么不用 Sampler？micrometer bridge 在 `startSpan` **之后**才补 span 名与属性，
   采样时点只有 `<unspecified span name>` ——过滤必须发生在导出侧。
2. **内容限额**：`max-span-io: 8192` + 摘要 + 截断；
3. **eval 独立 root trace**：每条 item 一个无父 root span，不与会话链纠缠。

**三方关联**（消息 ↔ 轨迹 ↔ 全链路 span）靠三样东西串起来：

```
SSE meta 事件（messageId / traceId / paradigm）
   → 前端气泡绑定
气泡查轨迹：本轮走内存 TraceView（SSE trace 事件）；历史走 GET /agent/traces/by-message
OO 深链：/web/traces/trace-details?...&trace_id={traceId}&from={from}&to={to}
         （from/to 是**微秒**时间戳，前端按消息时间 ±10 分钟生成窗口）
```

`TraceView` 是**唯一轨迹契约**：SSE 的 trace 事件、`sa_agent_trace` 表、eval 的轨迹 JSONB
三处共用同一个结构（`EngineTraceMapper` 的类注释把它列为不变量）。

最后一个坑：**跨线程必须搬运上下文**。LLM 调用走独立的虚拟线程且限时等待，
内核的 Timeout 拦截器也会把节点丢到新虚拟线程——不显式搬运，MDC 与 OTel Context 都跟不过去，
表现是"一次问答的 trace 断成好几截"。修法是两处：
`LlmCallGuard` 在切线程前 `ContextSnapshotFactory.captureAll()`、worker 内 `setThreadLocals()`；
内核侧 `TaskPropagation.install(ContextPropagator::wrap)`。

---

## 7. 贯穿全平台的设计原则

把六章里的决策抽出来，能看到一套反复出现的原则——它们比技术选型更能解释"为什么是这样写的"：

1. **宁可失败得响亮，不可降级得无声**
   图与模块对账失配直接启动失败；`PgEngineSessionStore` 读失败抛异常而不是返回 empty
   （empty 会被当成"没有会话"，于是重新开一次诊断）；预算触顶显式升级；模型要 escalate 时若已有工具证据则降级为直答。
   **反例才是灾难**：配置悄悄失效、状态悄悄丢失、结论悄悄截断。

2. **单一事实源 + 派生**
   `OpsDiagnosisStages` 一处声明派生全部节点名/prompt/守卫/计数槽；`FindingsText` 是主张编号渲染的唯一真身；
   `TraceView` 三处共用；图是唯一事实源，模块只提供执行器。

3. **正确性进 key，TTL 只是内存旋钮**
   检索 key 含 `SearchContext` 全参数、答案 key 含 promptHash、缓存 key 含 docver——
   失效是"结构上必然发生"，不是"定时发生"。

4. **机制与策略分离**
   内核只有机制（游标、检查点、挂起、守卫、拦截），策略全在业务声明（阶段目录、自主档位、图）。
   统计与决策分离：`FrequencyTracker` 只计数，`CacheFrequencyPolicy` 是纯函数。

5. **用户主权显式化**
   显式选择 > 意图识别；`#override` 确定性协议零模型调用；来源角标让"机器补了什么"一目了然。

6. **自我一致性问题要闭环**
   日志反查要排除平台自身 service_name（否则把自己打进日志的 prompt 当业务报文抓回来）；
   反思轮要跨轮累积（否则空手的一轮会抹掉首轮命中）；带历史的答案缓存整条旁路（否则跨会话串答案）。

7. **实测驱动，每个非常规决策背后有一个数字**
   rerank 分批（分数随请求长度衰减：12k→0.349 / 55k→0.208）、改写 AUTO 透传（英文语义改写 recall@5 -3.5pp）、
   分类温度 0（同一问题 4 次判出 2 种答案）、诊断置信度门槛 0.8（0.6 会把"疑似"级误判放行）。

8. **观测不成为可用性风险**
   增强类事件（trace / citations / meta）发送失败只记 debug，绝不阻断回答；
   推送队列有界、队满丢弃；轨迹落库独立吞异常。

9. **每一步都可短路，每一次失败都有输出**
   决策链每步设出口；升级卡带着"此前各阶段产出"一起交付——升级是转人工，不是放弃已得结论。

10. **诚实标注边界**
    哪些是桩（`WebSearchChannel` 返回空、`EnhancerNode` 空跑）、哪些默认关闭
    （`rag.cache.enabled=false`、MMR、`mcp.enabled=false`）、哪些能力框架有但业务没接线
    （Filters 全家桶、工具面中间件）——这些在文档里写清楚，比假装全都在跑更可信。

---

## 附：与常见文档口径的差异（写作与答疑时的校正清单）

| 常见说法 | 代码事实 |
|---|---|
| `ToolLoopEngine` / `StageSpec` / `LoopExitChecks` / `tool-call-mode` | **已不存在**，属旧业务层内核；现役是图范式内核（`com.agentframework`）+ `RegionLoop` |
| "三个 Agent 共享会话与状态" | 共享的是**引擎级组件**（管道/工具表/存储表）；会话按 agent 语义分开：ops 是 attempt 级持久会话，knowledge/react 是单轮 `ephemeral` |
| "六层 `RouteRule`" | `implements RouteRule` 全仓只有 **2 个**（traceId 短路、意图域）；其余四层是编排层分支 |
| "检索出口护栏保护 RAG 答案" | `JsonSchemaValidator` 只在 **ops 的 resolve 阶段**与 `validate_request` 工具生效；RAG 生成侧未接线，答案侧守卫靠 prompt + 前端 ref 过滤 |
| "answer 侧输出校验 / citation 强制校验" | 服务端**没有**校验；是 prompt 强指令 + 前端丢弃不存在的 ref |
| "五层缓存正在生效" | 代码齐备，但 `rag.cache.enabled: false`，默认部署下全部旁路 |
| "召回率提升 5%" | 全仓无出处。可引用的真实数字：小块检索对照实验期望分 +0.103（5/5 为正）、全量回填后 5 题 0/5→5/5、LiveRAG 去重 1032→970、nDCG 分母修复（run82 自证） |
| "评测与线上唯一差异是 forceRetrieval" | 至少四处差异（forceRetrieval / 检索范围隔离 / 答案生成 prompt / 重评 trace 形态） |
| "全仓 17 处埋点" | 16 处 `@TelemetryStep` 注解，分布于 15 个文件 |
| `SpanIoLimits = 8192` | 那是**服务器档**配置；starter 默认 20000 |
| "`EvalResultSink.onRunFinished` 在 run 收尾分发聚合" | 该方法是**死接口**，无调用点 |
| `LangfuseEvalPublisher` | 类不存在，实际是 `LangfusePushSink` + `LangfuseDatasetSyncer` |
| "支持 PDF / Excel / Word / 图片多格式" | **Excel 没有解析器**：`EXCEL_POI` 只是枚举占位（无实现类），Tika 显式排除 spreadsheet 类 MIME，上传 Excel 会在解析步直接失败。图片不是走解析器，而是 MinerU 抽取 + VLM 描述 |
| "通过 LLM 字段抽取 + JSON 校验完成数据标准化" | 入库链只有 `EnricherNode` 的宽松 JSON 富化（无 schema、失败静默丢弃）；JSON Schema 校验 + 回环修正在 ops 的出口护栏（`ActExecutor.gateOutput`），知识链传 `null` 不生效 |
| `rag.ingestion.chunk-size: 512` 决定分块 | 该配置在 block-aware 路线上**已不再被消费**；真实预算是 `ChunkPacker` 的 target 1400 / min 600 / max 1800 |
| "ChunkPacker 有块级 overlap" | `pack(..., overlapChars)` 参数**未接线**（`overlapTail` 无调用点）；实际生效的 overlap 只有段落切分的中文 100 / 英文 200 字符 |
| "MinerU 走 Markdown 路线，表格退化成 HTML 段落" | 已过期：默认 `content_list` 路线，表格经 Jsoup 解析成 `TableBlock` 并展开合并单元格；Markdown 只是 content_list 缺失时的兜底 |
| "表格 embeddingText 未用于向量化" | 已过期：`IndexerNode` 明确 `embeddingText` 优先、`content` 兜底 |
| "Heading 不产 chunk，只更新 outlinePath" | 已过期：`HeadingChunker` 会产 `blockType=HEADING` 的块，由 packer 向下合并 |
| "置信度分级过滤 / 未知项管理在文档处理链上" | 入库模块**零 confidence 字段**；阈值过滤（rerank / 向量 / 意图 / 证据充分性 / 槽位推断）与"缺失项追问"都在检索与 Agent 侧 |

> 本文所有行号与类名基于 2026-09-22 工作区代码核对。若后续代码演进，以代码为准。
