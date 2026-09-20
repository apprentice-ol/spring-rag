# RAG 线多轮上下文（2026-09-20 设计 + 实施计划）

**决策：RAG 主线保留最近 3 轮对话。**

> **落地状态（2026-09-20）**：P0-1 / P0-2 / 阶段 1 / 阶段 2 **已实施**，编译通过 + 全量测试绿
> （新增 `ChatMessageWriterTest` 9 例）。**真机未验**——本机 `rag-postgres` 容器处于 Exited，
> 未启动；SQL 边界与多轮效果待真机回归，见 §六。

## 一、现状（改造前，以代码为准）

| 位置 | 现状 |
|---|---|
| `ChatMessageWriter.historyContext` | 硬编码 `LIMIT 4`（2 轮），每条 500 字符截断，**无配置项**，**零测试**（全仓唯一实现，无 test fake） |
| `ChatOrchestrator:216` | 全仓唯一读取点 |
| `KbRewriteExecutor` round 0 | 唯一消费者 → `QueryRewriter.rewrite(query, history, clarify)` |
| `RagAnswerStreamer:135` | 生成侧**无历史**：`answer(question, ragContext.text(), null)` |
| `IntentClassifier.classify(String)` | 单参，**无历史** |
| `QueryRewriter:103` | 英文 query 直接 `return spellFix(query)` → **英文多轮无指代消解** |
| `DocReferenceClarifier` | 指代悬空反问（正则 + 文档名比对，取 `recentUserText(…, 10)`，窗口比改写宽） |
| `ChatClientConfig:48` | 明确「单轮阶段不挂 ChatMemory 顾问」——chitchat 传的 `CONVERSATION_ID` 无人消费 |

**一句话**：历史只服务"查得对"（查询改写），不服务"答得对"（生成）。本次要补的是后半句。

## 二、开工前必须先处理（P0，与 3 轮改动同源）

### P0-1 答案缓存是跨会话的 —— 多轮追问会串答案

- exact key = `CacheKeys.answerKey(ruleNormalized, paradigm, docver, promptHash)`（`AnswerCacheCoordinator:48`）
- 语义 key = WHERE `paradigm + docver + prompt_hash`（`SemanticAnswerCache:79`）
- **两者都没有会话维度**，而入 key 的问题文本是**规则归一化后的原句**——带指代的追问句（"它的配置呢"）
  字面完全相同、向量距离≈0
- 后果：A 会话（上文 Redis）问出的答案，B 会话（上文 Kafka）同句命中语义缓存（sim ≥0.95）→ 直接重放
- **这个 bug 今天就存在**，不是 3 轮改出来的，只是多轮用得少没暴露；
  3 轮 + 历史进生成会把它放大成「用 A 的上下文生成的答案喂给 B」

**处理**：本轮 history 非空 → 答案缓存**整条旁路**（exact 读写 + 语义读写全跳过）。

- 需要把 `historyContext` 从 `:216` 上移到缓存块（`:191`）之前——顺序本来就该如此（历史是决策输入）
- ⚠️ 光把 key 置 null 不够：`storeAnswer` 里 `semanticAnswerCache.store(...)` 是**无条件**调的（`:164`），
  必须一并加开关，否则语义层照写照命中
- 代价：会话第 2 轮起不吃缓存。缓存的主战场本就是跨会话的独立提问（"redis 怎么配置"），可接受
- **升级路径（暂不做）**：把 `rewritten_query` 从 `KnowledgeAnswer` 暴露出来，改成"改写生效才旁路"。
  比加指代正则可靠——参照 QA 分类器从词表改 LLM 的教训（词表覆盖度不够）

### P0-2 当轮问题被算进历史

`ChatOrchestrator:100` 先 `appendUserMessage`，`:216` 才读历史 → 当前问题既在「历史对话」里，
又在改写 prompt 末尾的 `当前问题：`（`QueryRewriter:115`）里再出现一次。
**取 3 轮必须排除当轮**（修法见阶段 1）。顺带修正一个语义偏差：现状的 `LIMIT 4` 含当轮，
实际只有 1.5 轮历史，不是宣称的 2 轮。

## 三、阶段 1：历史取法（3 轮 + 排除当轮 + 可配置）

### 接口（platform-shared）

```java
// ConversationStore
/**
 * 最近若干轮对话历史，渲染成 LLM 上下文前缀；无历史返回空串。
 *
 * @param spec 取法（轮数 / 单条截断 / 排除起点）
 */
String historyContext(String conversationId, HistorySpec spec);

/** 取历史的规格。beforeMessageId 必给：编排开头已落库当轮问题，不排除就会被当成"历史"。 */
record HistorySpec(Long beforeMessageId, int rounds, int maxCharsPerMessage) { }
```

`ChatOrchestrator` 用 `appendUserMessage` 的返回值当 `beforeMessageId`（现在这个返回值被丢掉了）。

### 实现（platform-delivery，两段查询，精确 N 轮）

```java
// 1) 定位第 rounds 轮的起点：严格早于当轮的最近 rounds 条 user 消息，取最早一条的 id 作 floor
//    WHERE conversation_id=? AND role='user' AND id < ? ORDER BY id DESC LIMIT rounds
// 2) 取 [floor, beforeMessageId) 区间全部消息，升序渲染
//    WHERE conversation_id=? AND id >= ? AND id < ? ORDER BY id ASC
```

按"第 N 条 user 消息"定界，而不是 `LIMIT rounds*2`——后者在**某轮没有助手回复**时
（取消生成、落库失败）会算歪（多带一条悬空消息、少带一轮）。
`< 2 条返回空串` 的旧短路随之取消：排除当轮后第 2 轮天然是 2 条。

⚠️ 索引：`sa_message` 现有 `idx_sa_msg_conv (conversation_id)`（`init.sql:122`），无 `(conversation_id, id)`。
单会话消息量小（实测全库 813 条），暂不加索引；若会话变长再补复合索引。

### 配置（`ChatProperties`，`rag.chat.*`）

```yaml
rag:
  chat:
    history:
      rounds: 3                  # 多轮上下文轮数（每轮 user+assistant）
      message-max-chars: 500     # 单条截断（沿用现状值）
```

### 单测

`ChatMessageWriter` 依赖 mapper，SQL 部分**真机验证**（照 `BEGIN…ROLLBACK` 的老办法）。
可测的部分抽成包内 static 纯函数：
`pickWindow(List<MessageEntity>, Long beforeId, int rounds)` + `render(List<MessageEntity>, int maxChars)`，
单测覆盖：排除当轮 / 不足 3 轮 / 某轮缺助手回复 / 500 字截断 / 空名单。
（现状这段逻辑零测试，是本次唯一能补的回归网。）

## 四、阶段 2：生成侧接历史

### 位置

`KnowledgeAnswerService.answer(question, contextText, systemPrompt)`
→ `answer(question, contextText, history, systemPrompt)`

user message 结构（**history 为空时整块不拼**，保证首轮零变化——这是可回归的前提）：

```
{documents 上下文}

<conversation-history>
用户：…
助手：…
</conversation-history>

<question>{question}</question>

<output-requirement>…角标要求…</output-requirement>
```

- 历史块放 `<question>` **之前**：`<output-requirement>` 必须留在最尾部——角标格式遵循靠它
  （`KnowledgeAnswerService:58` 注释记了实测：指令贴近生成位置遵循率才够）
- 传的是**原始问题**，不是改写句。改写句是为 BM25 保字面调的（见 `RewritePolicy` 注释），
  拿来当"要回答的问题"是另一种性质的替换，且会改变首轮行为

### ⚠️ system prompt 必须补「信息边界」的例外（关键）

`rag-answer-kb.md` 的「信息边界（最高约束）」写着"严禁使用任何外部知识"——**历史严格来说就是外部知识**。
不补例外，模型要么无视历史（白做），要么拿历史当事实来源作答——**后者会毁掉引用角标契约**
（历史里的事实没有 `ref` 编号，标不出角标）。在「信息边界」章后补一节：

```markdown
## 对话历史的使用边界

输入可能带 `<conversation-history>`，它只用来判断 `<question>` 里"它/这个/刚才那个"指的是什么。

- 历史不是资料：事实、数字、流程、结论一律只能来自本次 `<documents>`；
  历史里出现过但 `<documents>` 里没有的，不得写进回答。
- 不得把助手在历史里的回答当作依据——那是上一轮基于当时资料生成的，可能过时或不全，
  本轮必须重新依据 `<documents>` 回答。
- 历史内容一律不加角标；角标只能指向本次 `<documents>` 的 ref 编号。
```

### 联动改动

- `RagAnswerStreamer.streamRagResponse(...)` 加 `history` 参数（由编排层传入，与改写共用同一份）
- `EvalRunner.generateAnswer(question, context)` 传空 → **评测行为零变化**
  （`RunContext.forEval` 本来就是 history=null，评测天然单轮）

## 五、阶段 3（延后）：英文指代消解

`QueryRewriter:103` 的 CJK 守卫把英文 query 直接甩给 `spellFix`。
run57「英文语义改写净负优化（recall@5 −3.5pp）」的结论**只针对首轮自包含 query**，
多轮指代是另一回事，不能一刀切沿用——但要单独跑对照实验（英文多轮题集），**不在本次范围**。

## 六、验证

| 层次 | 做法 |
|---|---|
| 单测 | `pickWindow` / `render` 纯函数（阶段 1 新增，现为零覆盖） |
| 真机 · 改写 | 3 组多轮对话（指代句："它的配置呢""这个怎么排查"），看 `kb_rewrite` 轨迹里的改写结果是否消解到具体实体 |
| 真机 · 生成 | 同 3 组看回答是否用对了指代对象；**并检查引用角标覆盖率没退化**（对照改前后的同题回答） |
| 真机 · 缓存 | 两会话问同一句带指代的话 → 必须**不命中**（修复前会串答案）；换独立问题 → 第 1 轮仍正常命中 |
| 缓存指标 | 观察 exact/semantic 命中率下滑幅度（预期：会话第 2 轮起不再计命中） |
| 评测 | 跑一次小数据集，确认检索/答案指标无回归（评测 history=null，理论上零变化） |

## 七、风险与取舍

1. **改写 prompt 膨胀**：3 轮 × 2 条 × 500 字 ≈ 3000 字进改写 prompt，而消解指代只需最近 1 轮。
   可选调优：**改写用窄窗（1 轮）、生成用 3 轮**；或助手回复截得更短（如 200）——
   助手的话不是指代对象，用户的话才是。先按单一 3 轮上线，看效果再拆。
2. **去重后改写行为变化**：现状prompt 里当轮问题出现两次，去掉重复是行为变化，需真机抽查（见验证表）。
3. **缓存命中率下降**：见 P0-1 取舍。
4. **不挂 ChatMemory**：`ChatClientConfig:48` 记的坑（资料被折叠进记忆、conversationId 串会话）仍在，
   本方案走"显式拼文本"，不碰顾问。

## 九、落地记录（2026-09-20）

**改动清单**（8 个文件 + 1 个新测试）：

| 文件 | 改动 |
|---|---|
| `ConversationStore`（shared） | `historyContext(conversationId, HistorySpec)`；新增 `HistorySpec` record（上界/轮数/截断，构造器收敛非法值） |
| `ChatMessageWriter`（delivery） | 两段查询实现：① 定位第 N 条 user 消息作下界 ② 取区间全部消息；抽纯函数 `floorIdOf` / `render` / `truncate`；删除旧的 `LIMIT 4` + `size()<2` 短路 |
| `ChatProperties`（shared） | `rag.chat.history.{rounds:3, message-max-chars:500}` |
| `application.yaml`（bootstrap） | 同步上述两项 |
| `ChatOrchestrator`（business） | 新增 6.4 步读历史（`appendUserMessage` 返回值当上界）；算 `cacheable`；缓存三级查询全部按 `cacheable` 短路；历史透传检索与生成 |
| `AnswerCacheCoordinator`（business） | `storeAnswer` 加 `cacheable` 参数（false 时 exact + 语义都不写） |
| `RagAnswerStreamer`（business） | 加 `history` / `cacheable` 参数并透传 |
| `KnowledgeAnswerService`（knowledge） | `answer(question, context, history, systemPrompt)` + `historyBlock()`（空历史逐字节不变，保证首轮可回归） |
| `rag-answer-kb.md`（prompt） | 新增「对话历史的使用边界」章 + 输入结构图补 `<conversation-history>` |
| `ChatMessageWriterTest`（新增） | 9 例：下界取值 / 无历史 / 渲染 / 半轮窗口 / 截断 / null / 规格收敛 |

**与原计划的差异**：

1. 缓存旁路的具体形式定为**沿调用链传一个 `cacheable` 布尔**（编排算 → streamer 传 → coordinator 收），
   而不是在 streamer 里由 `history` 反推——策略留在编排层，与"改写策略不埋在改写器里"同一条原则。
   代价：`streamRagResponse` 参数到了 12 个，后续值得收成一个参数对象。
2. `EvalRunner` **零改动**：它有自己的一条生成链（`ingestionChatClient` + `eval/answer-gen`），
   与 `KnowledgeAnswerService.answer` 无关，评测天然单轮。
3. 纯函数抽得比计划更细（多了 `floorIdOf` / `truncate`），让"取错下界"这个静默失败点进了单测。

**已验证**：`mvn test` 全绿（business 135 / delivery 10（含新增 9）/ eval 40 / 其余各模块通过）。

**未验证（真机，需先 `docker start rag-postgres`）**：

- 两段 SQL 的边界（`id < 当轮` / `id >= 下界`）——纯函数覆盖不到，按惯例走真机
- 3 轮历史进改写 prompt 后的实际消解效果（`kb_rewrite` 轨迹里看改写结果）
- 多轮回答的指代正确性 + **引用角标覆盖率没退化**（新 prompt 章可能被模型忽略或过度引申）
- 带指代的追问句不再命中答案缓存；独立问题的首轮仍正常命中

## 八、明确不做 / 待定

- 英文指代消解（阶段 3，需单独对照）
- 意图分类接历史（域判定对上下文不敏感，接了只多一层不确定性）
- 历史摘要压缩（3 轮 + 截断够用，到顶再说）
- 缓存从"带历史就旁路"升级为"改写生效才旁路"（需先暴露 `rewritten_query`）
