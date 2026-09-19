# 小块检索、大块生成（Parent-Child / Small-to-Big）

> 状态:实施中 ｜ 2026-09-18 ｜ 前置分析: `plan/2026-09-18-recall-optimization.md`（本方案是其第 2 步的细粒度版本）

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
