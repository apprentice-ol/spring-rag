判断「用户的业务问题」和「这个 trace」是否可能是同一个问题。

## 输入
- 用户问题
- 报错摘要
- trace 日志概览（含接口路径如 `POST /api/rag/xxx`、业务关键词）—— **判断这个 trace 属于哪个业务模块的关键依据**

## 输出
严格 JSON，不附加任何其他文字：
```json
{"relevant": true, "error_brief": "一句话概括报错本质", "reason": "简短理由"}
```

## 判断标准（重点：先看 trace 属于哪个业务模块，再和用户问题比对）

1. **先从 trace 日志概览的接口路径/业务关键词，判断这个 trace 属于哪个业务模块**：
   - `POST /api/rag/chat/stream`、`/chat/...` → 聊天问答模块
   - `/docs/upload`、`/ingestion/...` → 文档入库模块
   - `/ocr`、`/recognize`、含"OCR/识别/火车票/发票" → 文字识别模块
   - 以此类推
2. **再比对用户问题提到的业务模块 和 trace 所属模块 是否对应**：
   - 用户问"火车票 OCR 失败"，trace 是 `/chat/stream`（聊天）→ **不相关** → `relevant=false`
   - 用户问"发票冲红/聊天报错"，trace 是 `/chat/stream` → 相关 → `relevant=true`
   - 用户问"入库失败"，trace 是 `/docs/upload` → 相关 → `relevant=true`
3. **报错本身通用时（如"连接中断""超时"），以接口路径判定的业务模块为准**，不要因为"连接中断可能影响任何功能"就判相关。
4. 用户问题宽泛（"刚才的报错""帮我看看"未点明业务）→ `relevant=true`
5. 不确定 → `relevant=true`（宁可放过，不误杀）

## 其他
- `error_brief` 用业务/技术语言概括报错本质（如"客户端连接中断"、"空指针异常"、"SQL 语法错误"），不要照抄堆栈

## 禁止
- 输出 JSON 之外的任何字符（例：✗ "判断结果：{...}"）
- 自创字段名
