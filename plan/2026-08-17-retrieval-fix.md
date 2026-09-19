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
