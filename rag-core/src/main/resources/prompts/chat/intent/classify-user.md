<!-- 勿对此模板使用 .param()：其中的 JSON 花括号会被 Spring AI StringTemplate 解析报错。
     用户问题由调用方在 Java 侧拼接（见 DefaultIntentClassifier）。 -->

# 用户问题意图分类

## 功能
判断用户问题的意图类型，输出结构化 JSON，供下游决定是否检索知识库 / 是否联网 / 是否走日志诊断。

## 输入
用户问题（由调用方拼接在本提示词末尾）。

**输入示例**
```
pgvector 怎么配置 hnsw 索引？
```

## 输出
严格符合下述结构的 JSON，不附加任何其他文字。

**输出示例**
```json
{
  "intent": "knowledge_query",
  "confidence": 0.95,
  "needs_retrieval": true,
  "needs_web_search": false,
  "needs_diagnose": false,
  "reason": "询问知识库中的技术配置"
}
```

## 流程
1. 阅读用户问题，判断其真实意图。
2. 与五类意图逐一比对，选择最匹配的一类。
3. 评估置信度（把握越大越接近 1.0）。
4. 仅输出 JSON。

## 规则

**允许**（分类标准）
- `knowledge_query`：询问知识库/文档/业务知识，需检索（例：✓ "产品支持哪些向量库？"）
- `greeting`：问候语，无需检索（例：✓ "你好"、"早上好"）
- `chitchat`：闲聊，无需检索（例：✓ "今天星期几"、"你叫什么"）
- `web_search`：实时信息/新闻/天气/股价，需联网（例：✓ "今天北京天气如何？"）
- `diagnose`：排查报错/异常/日志/traceId，无需知识库检索（例：✓ "帮我看看刚才的报错"、"这个异常怎么回事"、"52462c684e1c47242ffea1bc96af94c3 是什么报错"）

**禁止**
- 输出 JSON 之外的任何字符（例：✗ "分类结果是：{...}"）
- 自创 intent 取值（例：✗ "intent": "search"）
- 置信度填 0~1 范围之外的值
