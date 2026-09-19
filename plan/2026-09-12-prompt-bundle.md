# Prompt 能力包执行计划（2026-09-12 启动）

## 背景与设计契约（三轮讨论定稿，2026-09-12）

诉求 → 机制：

| 诉求 | 机制 |
|---|---|
| prompt 好管理 | 资产化：逻辑 key 与内容版本分离 |
| 修改有历史 | 版本表：只增不改，带 change note |
| 能比对 | 版本间 / 包间 / release 间 / **effective set（绑定合并结果）** 四种 diff |
| 一组 prompt → agent 能力、灵活组合 | 能力包：基座+特化两层 merge，绑定即生效 |

### key 空间约定（命名即分层）

```
agent/common/*       公共区：replan 裁决、回答风格、工具使用规范（基座包主体）
agent/{workflow}/*   骨架特有：agent/ops/* 、agent/inspect/*（特化包主体）
chat/*               链路级：意图分类、query 改写、RAG 回答人格、闲聊
tool/{name}/desc     工具描述（唯一允许回退代码默认值的 key 类）
```

### 数据模型（sa_ 前缀，spring.sql.init 幂等建表）

```
sa_prompt                 id, prompt_key(unique), current_version_id, description
sa_prompt_version         id, prompt_id, version_no, content, change_note, created_at
                          unique(prompt_id, version_no)；不可变只增
sa_prompt_bundle          id, name(unique), description, agent_type(null=通用包)
sa_prompt_bundle_release  id, bundle_id, release_no,
                          items(jsonb: {prompt_key: version_id}), change_note, created_at
                          unique(bundle_id, release_no)；发布即不可变快照
sa_prompt_binding         id, agent_type(unique), base_bundle_id, overlay_bundle_id(null=无特化)
```

### 两层组合（不做继承链，固定两层）

```
绑定(agentType) = ( 基座包名 , 特化包名 )
每次装配：snapshot = merge(基座包当前release.items, 特化包当前release.items)   // 同 key 特化胜出
```

- 基座包（全平台一个）：只装公共区 key；基座发新 release → 所有骨架下次装配自动受益
- 特化包（按骨架/域）：只装骨架特有 key + 需偏离基座的覆盖 key
- 装配校验：`definition.requiredKeys ⊆ merged.keys`，缺哪个报哪个，绝不静默 fallback
  （工具描述除外：缺 key 回退代码 `AgentTool.description()`——代码默认即其基线版本）

### 活包名 / 死指纹（可复现与自动生效的两全）

| 对象 | 存什么 | 语义 |
|---|---|---|
| sa_prompt_binding | 包名对 | **活**——每次装配解析两包各自当前 release 合并 |
| PromptSnapshot 身份 | `基座@rN + 特化@rM` + 内容 hash | **死**——装配那一刻固化 |
| eval run / sa_agent_session | snapshot 指纹（或内容 hash） | 粘住当时的组合，可复现 |
| 答案/意图/语义缓存 key | 内容 hash | 任一层改动 → 缓存自动失效 |

### PromptSnapshot 装配机制（组合的芯子）

```
请求 → 路由确定 agentType/链路
     → 绑定解析（分层链：请求级参数[预留不开放] > 会话粘住的 release > agent 全局绑定 > 基线包）
     → merge 构造 PromptSnapshot（不可变 Map<key,content> + identity + contentHash）
     → requiredKeys 校验
     → snapshot 进 AgentContext（prompt(key) 访问器，缺 key 抛错带 key 名）
     → agent.run() 全程消费快照
身份进三处：trace 首 step（prompt-bundle）、缓存 key、日志
```

两类消费方都吃快照：
- **agent 内**（workflow 型）：ToolLoopEngine / SlotExtractor / ReplanChecker 改 `ctx.prompt(key)`
- **链路级**（pipeline 直用）：意图分类、query 改写、RAG 回答人格、闲聊——bundle 可整链换人格

### 决策记录（不再重议）

- 固定两层 merge，**不做任意深度继承/组合链**（透明性 > 灵活性）
- 工具描述外**不允许 fallback**；全量快照语义
- classpath `resources/prompts/` 降级为**首次种子**：启动缺 key 建 v1；已有 key DB 为准，
  代码与线上内容不一致时 console 标"差异"+ 一键"以代码内容发新版本"（防开发改码被静默压制）
- 请求级选包参数**留位不开放**（前端将来加"包选择器"时启用）
- 不做：审批流、环境 label、按比例灰度、A/B 框架（eval 双轴已覆盖）、新模板引擎
  （PromptTemplateRenderer 够用）
- 模块归属：PromptSnapshot 数据类 + `prompt(key)` 契约随 rag-agent-framework（阶段 4）；
  绑定/版本/bundle 管理与持久化全在业务侧——框架零持久化依赖

---

## P1 资产化（无前置，已完成 2026-09-12）

### #P1.1 表与持久层
- [x] 5 张表 DDL（sql/init.sql 追加，幂等；bundle/release/binding 为 P2 预建）
- [x] entity + mapper（`chat/prompt/` 独立域：entity/mapper/service/controller 对齐现有模式）
- [x] 基线导入（PromptAssetService @PostConstruct）：缺 key 建档 v1；已有 key DB 为准，
      classpath 不一致记差异（warn + `/prompt/code-drift` 接口），不自动建版本
- [x] **PromptStore 顺手升级**：KNOWN_KEYS 硬编码清单 → 目录扫描（PathMatchingResourcePatternResolver），
      新增 prompt 文件自动预加载 + `allKeys()` 暴露——"忘登记"维护债消除，消费方 raw() 行为零变化

### #P1.2 管理 API + console 面板
- [x] `PromptController` 7 端点：keys（含漂移/未建档标记）/ code-drift / versions / version /
      diff（双版本预取）/ 发新版本 / rollback / sync-from-code——key 经 query param 传递
      （promptKey 含斜杠，@PathVariable 无法匹配且编码斜杠被容器默认拒绝）
- [x] 前端 `PromptManage.vue`（`#/admin/prompt` 菜单项"Prompt 资产"）：左 key 列表（搜索/漂移标记）+
      右时间线/内容查看/逐行 LCS diff（+N/-N 统计）/新版本编辑（textarea + change note）
- [x] 回滚 = 以旧版本内容发新版本（时间线一键；历史只增不改语义不变）

### #P1.3 验收
- [ ] 基线导入后 key 全集与 `resources/prompts/` 一致；改动代码 prompt 重启后差异可见 —— 待整体联调
- [ ] 版本链路：编辑→新版本→diff→回滚 全流程可用 —— 待整体联调
- [x] 现有 PromptStore 消费方行为零变化（raw() 签名与语义不变；全量测试绿佐证）
- [x] vue-tsc 通过 + 后端全量测试绿

## P2 组合与解析（前置：workflow 框架化批次 1-2 完成）

### 2026-09-13 增量（console 侧落地，E2E 已验）

- [x] **前端能力包与绑定 UI**（`PromptBundleDrawer.vue` + `api/bundle.ts`）：建包 / fork /
      发布 release（勾选 key 取当前版本快照）/ release 历史 / agent 骨架绑定（基座+特化，
      校验失败行内红字）——建包→发布 r1（28 keys）→绑定 react_loop→解绑 全链路冒烟通过
- [x] **运行链路接线**：`PromptStoreSnapshotSource` 经 `PromptBindingService.overridesFor`
      取绑定 merge 内容覆盖 classpath（无绑定空覆盖，行为零变化；发布新 release 下一请求生效，
      指纹 hash 随内容自动失效缓存）
- [x] **线上新建 prompt key**：`PromptAssetService.createVersion` 放开 classpath 校验
      （key 命名规范校验替代）；`listKeys` 对 DB-only key 安全（codeMissing 标记，不算漂移）；
      `syncFromCode` 对线上 key 报友好错
- 注：快照接线未做会话粘性（`resolveByReleases` 已备未接）；P2.3 缓存联动未做；
  删 bundle 接口不存在（如需 UI 删除再补）
- **冒烟遗留**：库里留有一个「冒烟测试包」（r1，无绑定）——测试数据，可忽略

### #P2.1 bundle 与绑定
- [x] bundle / release 的 service + console API：组包（挑各 key 版本）→ 发布 release → 复制包
- [x] `sa_prompt_binding` 管理：绑定切换（base+overlay），切换时即时校验
      requiredKeys ⊆ merged + 特化包 agent_type 匹配
- [ ] release 不可变：发布后 items 锁定，删 bundle 需解绑前置校验

### #P2.2 PromptSnapshot 与消费点改造
- [ ] `PromptSnapshot`（contents/identity/contentHash）+ `AgentContext.prompt(key)` 访问器
- [ ] `WorkflowDefinition.requiredPromptKeys()`（聚合 slotExtract/stages/replan 三类 key）
- [ ] `PromptBindingService`（业务侧）：分层绑定链解析 → merge → 校验 → 构造快照
- [ ] 消费点改造（动手前 grep `promptStore.raw` 全消费点建清单核对），已知集合：
      ToolLoopEngine / SlotExtractor / ReplanChecker（agent 内）；
      意图分类 / QueryRewriter / chitchat / RAG 回答（链路级——`ChatClientConfig.ragChatClient`
      的 defaultSystem 挂载方式改为运行时传入，注意 prompt cache 稳定性不回退）
- [ ] AgentTrace 首 step 记 `prompt-bundle: 基座@rN + 特化@rM`

### #P2.3 缓存与会话联动
- [ ] 答案缓存（exact）key 拼 contentHash；意图缓存 key 同理
- [ ] 语义缓存 sa_cache_answer 加 prompt_hash 列进命中 WHERE
- [ ] sa_agent_session 加 prompt 指纹字段：追问恢复粘住原组合；指纹对应的 release 已删 →
      回退当前绑定 + trace warn
- [ ] 切换语义：全局切换下一请求生效，进行中流式与既有会话不受影响

### #P2.4 验收
- [ ] 同一骨架绑不同包 → 行为差异可观测（trace 身份不同 + 回答风格实测变化）
- [ ] 基座发新 release → 所有绑定骨架自动生效；特化覆盖仅影响该骨架
- [ ] 改任一层 prompt → 答案/意图缓存自动失效（重放旧答案的隐患消除）
- [ ] 多轮追问会话内组合不漂移；缺 key 的绑定装配即时报错
- [ ] eval knowledge 基准回归无异常漂移

## P3 深化（前置 P2）

- [ ] eval 双轴：eval run 记录 snapshot 指纹，`paradigm × bundle` 对照（sa_eval_run 加列 +
      前端对照面板加轴）——消融实验："严格包比标准包好多少"可量化
- [ ] effective set diff：两个绑定（切换前后 / 两骨架间）的合并快照对比，逐 key 标注来源
      （基座/特化覆盖/回退）
- [ ] 工具描述 key 化：`tool/{name}/desc` 进 key 空间，ToolRegistry 装配回调从 snapshot 取，
      缺省回退代码 description()（同一工具不同包=不同"使用说明书"）
- [ ] 验收：eval 双轴跑批出数；工具描述换包实测模型行为变化

---

## 与 workflow 框架化计划的全局排序

```
workflow 批次 1（框架重写）─┬─→ workflow 批次 2（路由/双形态/legacy/合流）─→ workflow 批次 3（留缝）
prompt P1（资产化，并行）──┘              │
                                          └─→ prompt P2（组合与解析）─→ 阶段 4（rag-agent-framework 抽出）─→ prompt P3
```

阶段 4 前置包含 prompt P2 的原因：PromptSnapshot 契约决定 framework 模块接口形状，先定接口再拆模块。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 链路级消费点改造碰 ChatClient 装配（defaultSystem 运行时化），影响 prompt cache 稳定 | system/user 分离原则保持：资料进 user、规则进 system 的既有设计不破；改造后验证 Langfuse trace 中 system 段稳定 |
| 基线导入与 DB 演进分叉（开发改码 vs 线上改库） | P1 差异面板 + 一键同步；DB 为运行态真相写进 CLAUDE.md |
| 语义缓存加列迁移 | spring.sql.init 幂等 + 存量行 prompt_hash 允许 null（视为不匹配，自然重写） |
| bundle 发布后 key 需求变化（骨架新增 requiredKey） | 装配期校验兜底：缺 key 报错列出，console 绑定页显示覆盖度 |
| 两层 merge 的排障复杂度 | trace 身份 + effective diff（P3）+ 装配错误信息带 key 名与缺失来源 |
