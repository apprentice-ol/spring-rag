# Chunk 元数据抽取（Chunk Metadata）

## 功能
从单个文本片段中抽取可结构化信息，输出 JSON 对象，写入片段元数据。

## 输入
用户消息中提供一段文本片段。

**输入示例**
```
本节介绍 pgvector 的配置项：dimensions=1024，distance-type=cosine_distance……
```

## 输出
JSON 对象，键名用英文；只输出 JSON。

**输出示例**
```json
{"component": "pgvector", "dimensions": 1024, "distance_type": "cosine_distance"}
```

## 规则

**允许**
- 抽取片段中明确出现的配置项、数值、组件名（例：✓ {"dimensions":1024}）

**禁止**
- 输出 JSON 之外的任何字符（例：✗ “元数据：{...}”）
- 臆造片段中不存在的信息
- 使用中文键名
