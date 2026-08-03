# Elasticsearch 操作笔记

> 索引: `rag_keyword_store` | 17 个文档 | 52KB | ES 8.11

## 索引 Mapping

```json
{
  "content":     { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
  "outline":     { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
  "block_type":  { "type": "keyword" },
  "chunk_index": { "type": "integer" },
  "collection_name": { "type": "keyword" },
  "doc_id":      { "type": "keyword" }
}
```

- 索引时用 `ik_max_word`（最大切分，召回优先）
- 搜索时用 `ik_smart`（粗粒度切分，精准优先）

## 常用命令速查

### 基础状态

```bash
# 查看所有索引
curl -s 'http://localhost:9200/_cat/indices?v'

# 查看索引文档数
curl -s 'http://localhost:9200/rag_keyword_store/_count'

# 查看 mapping
curl -s 'http://localhost:9200/rag_keyword_store/_mapping?pretty'
```

### 搜索

```bash
# match 查询（会经过 search_analyzer 分词）
curl -s -X POST "http://localhost:9200/rag_keyword_store/_search" \
  -H 'Content-Type: application/json' -d'
{
  "query": { "match": { "content": "ADK React 区别" } },
  "size": 3
}'
```

### 查看文档

```bash
# 获取单个文档完整内容
curl -s 'http://localhost:9200/rag_keyword_store/_doc/{doc_id}'
```

### 评分诊断

```bash
# 解释为什么某个文档被匹配及评分详情
curl -s -X GET "http://localhost:9200/rag_keyword_store/_explain/{doc_id}" \
  -H 'Content-Type: application/json' -d'
{
  "query": { "match": { "content": "ADK React 区别" } }
}'
```

### 分词分析

```bash
# 查看一段文本如何被分析器分词
curl -s -X POST "http://localhost:9200/rag_keyword_store/_analyze" \
  -H 'Content-Type: application/json' -d'
{
  "field": "content",
  "text": "ADK React 区别"
}'
```

## BM25 评分实战分析

用 `_explain` 诊断评分，BM25 公式核心三要素：

| 要素 | 说明 | 示例（"ADK"在文档 2084140740857888827） |
|------|------|----------------------------------------|
| **IDF** (逆文档频率) | 稀有词权重高 | 1.64（只在 3/17 文档中出现） |
| **TF** (词频) | 同文档出现次数 | freq=2 → tf=0.57 |
| **Field Norm** | 短文档有加分 | dl=344 vs avgdl=250，略高于平均，轻微减分 |

```
score = boost(2.2) × idf(1.64) × tf(0.57) = 2.04
```

### 实际案例：为什么 "ADK React 区别" 搜出异常处理文档排第一？

1. 查询分词为 `["adk", "react", "区别"]` 三个独立词项
2. 排名第一的文档只命中了 "adk"（2 次），"react"和"区别"都没命中
3. 但该文档 "adk" 出现 2 次，另一个也讲 ADK 的文档只出现 1 次，freq 差 1 就导致分数从 1.91 → 2.04
4. **这就是 BM25 的局限：匹配了词但不理解语义**。向量检索更适合这类语义查询

## 索引健康状态说明

| 状态 | 含义 |
|------|------|
| green | 所有分片正常分配 |
| yellow | 主分片正常，replica 未分配（单节点常见，不影响读写） |
| red | 主分片丢失，读写异常 |
