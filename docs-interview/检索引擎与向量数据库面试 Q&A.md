# 检索引擎与向量数据库面试 Q&A（ES + Milvus + pgvector）

> 结合 PowerAgent + ragent 两个项目的实战。上篇 Elasticsearch（全文检索 + ES knn 向量），下篇 Milvus 向量数据库（含三方案对比）。
> 锚点格式 `<a id="qN">`，VS Code / IDEA：Ctrl+单击跳转。

---

# 上篇：Elasticsearch

<a id="q1"></a>
## 一、基础概念类

### Q1: ES 的倒排索引是什么？为什么快？

倒排索引是"**词 → 文档列表**"的映射，和正排索引（文档 → 词）相反。

```
正排索引（文档 → 词）:
  文档1: "张三负责OA审批"
  文档2: "李四负责报销审批"

倒排索引（词 → 文档）:
  "张三" → [文档1]
  "李四" → [文档2]
  "负责" → [文档1, 文档2]
  "审批" → [文档1, 文档2]
  "OA"   → [文档1]
```

**为什么快**：查询"审批"时，直接从倒排索引里查出对应的文档列表 `[文档1, 文档2]`，O(1) 查表，不用遍历所有文档。这是 ES 全文检索的基础。

### Q2: BM25 打分是什么？比 TF-IDF 好在哪？

BM25 是 ES 默认的相关性打分算法：

```
score(D, Q) = Σ IDF(qi) × [ f(qi,D) × (k1+1) ] / [ f(qi,D) + k1 × (1 - b + b × |D|/avgdl) ]

三个关键参数:
  IDF(qi)   : 词频逆文档频率——越稀有的词越重要（"OA"比"审批"更有区分度）
  f(qi,D)   : 词在文档中出现次数（词频）
  |D|/avgdl : 文档长度归一化——长文档不会因为词多就占优
  k1, b     : 调节参数（默认 k1=1.2, b=0.75）
```

**比 TF-IDF 好在哪**：
- TF-IDF 词频是线性无界的（词出现 10 次就是 5 次的 2 倍分），BM25 用饱和函数（词频到一定程度就封顶）
- BM25 有**文档长度归一化**——短文档匹配一个词，比长文档匹配一个词更"相关"

---

<a id="q2"></a>
## 二、检索原理类

### Q3: ES 的 knn 向量检索是什么？numCandidates 和 k 的区别？

ES 8.x 引入原生 knn（K-Nearest Neighbor），底层用 HNSW 图结构做近似最近邻检索。

```java
KnnQuery knnQuery = KnnQuery.of(m -> m
    .field("vector_768")
    .queryVector(vector)
    .numCandidates(100)   // 粗排候选数
    .k(10)                // 最终返回数
);
```

**numCandidates vs k**：

| 参数 | 含义 | 类比 |
|---|---|---|
| numCandidates | HNSW 检索时考察的候选节点数 | 初筛 100 个"可能相关"的 |
| k | 最终返回的最近邻数 | 精挑 10 个最相关的 |

**为什么分两步**：HNSW 是近似检索，numCandidates 越大越接近精确检索（越慢），k 是最终要的个数。numCandidates=100、k=10 意味着"从 100 个候选里精确算距离，返回最近的 10 个"。

**项目里 PowerAgent 用 numCandidates=200、k=150**——为召回足够多候选给后续 RRF + Rerank 精排。

### Q4: HNSW 索引是什么？为什么比暴力扫描快？

HNSW（Hierarchical Navigable Small World）是**多层图**索引结构：

```
Layer 2（顶层，节点少，边跨度大）:  ○ ← 入口，粗定位
                                      │
Layer 1（中层）:                   ○──○──○ ← 中距离跳转
                                    / \
Layer 0（底层，所有节点，密集边）:  ○─○─○─○─○─○ ← 精确查找
```

**查询流程**：从顶层入口 → 逐层下钻 → 底层精查。复杂度 O(log N)，暴力扫描是 O(N)。

**三个核心参数**：

| 参数 | 含义 | 影响 |
|---|---|---|
| m | 每节点最大邻居数 | ↑ 精度 ↑ / 体积 ↑ |
| ef_construction | 建索引考察候选数 | ↑ 索引时间 ↑ / 精度 ↑ |
| ef_search | 查询考察候选数 | ↑ 精度 ↑ / 速度 ↓ |

ragent 用 pgvector 的 HNSW，查询时 `SET hnsw.ef_search = 200` 用精度换一点性能。

---

<a id="q3"></a>
## 三、项目实战类（结合 PowerAgent/ragent）

### Q5: 你们项目里 ES 承担了什么角色？

**PowerAgent 里 ES 干了两类事**：

1. **知识库检索**：
   - ES 8.x 原生 knn 向量检索（`vector_768` 字段 + `numCandidates` + filter）
   - ES bool query BM25 全文检索（`term`/`terms`/`match`）
   - 双索引分离（vector 索引 + data 索引）
   - 多租户隔离（bool filter：tenantId/teamId/knowledgeId/datasetId）

2. **记忆向量化**（mem0）：
   - script_score 混合检索（`cosineSimilarity` + `match` 加权）
   - 记忆带 weight + status（艾宾浩斯遗忘曲线）

**ragent 里 ES 只做关键词检索通道**（BM25 + ik 中文分词），向量检索走 PG vector（pgvector HNSW）。

### Q6: 向量检索里怎么做多租户隔离？（knn 嵌套 filter）

关键设计是**把过滤条件放进 knn 查询里**，而不是召回后再过滤：

```java
Query filter = Query.of(q -> q.bool(b -> b
    .must(n -> n.terms(t -> t.field("datasetId")...))
    .mustNot(n -> n.term(t -> t.field("isEnabled").value(1)))));

KnnQuery knn = KnnQuery.of(m -> m
    .field("vector_768")
    .queryVector(vector)
    .numCandidates(200)
    .k(150)
    .filter(filter));   // ★ 过滤放进 knn
```

**为什么不能召回后再过滤**：

```
错误: 向量召回 150 个 → Java 过滤掉 120 个无权限 → 只剩 30 个（topK 浪费）
正确: 先 filter 缩小到"有权限文档集" → 在这个子集里 knn 召回
      → 召回的 150 个都是有权限的
```

本质是"**先过滤后召回**"，保证召回结果的 topK 不被无权限数据稀释。

### Q7: 为什么分两个索引（vector 索引 + data 索引）？

```
vector 索引: 只存向量字段（vector_768），knn 查询专用
data 索引:   只存全文字段，BM25 bool 查询专用
```

**原因**：
1. 向量字段（768 维 float）体积大，需要 HNSW 图索引结构；全文字段需要倒排索引 + 分词器
2. 混在一个索引：索引体积膨胀、mapping 复杂、knn 和 bool 查询互相影响性能
3. 分开后各用各的最优索引结构

**代价**：双写（一个文档写两个索引），但检索性能收益大于双写成本。

---

<a id="q4"></a>
## 四、混合检索类（重点）

### Q8: 为什么向量检索 + 全文检索要混合？怎么混合？

**为什么混合**：两种检索各有所长，互补。

| | 向量检索 | 全文检索 |
|---|---|---|
| 擅长 | 语义理解（"怎么退钱" 匹配 "退款流程"） | 精确匹配（订单号、错误码、专有名词） |
| 短板 | 精确关键词匹配差 | 语义变体匹配差 |

**怎么混合**——两种思路：

**思路 1：RRF（ragent 和 PowerAgent 都用了）**

```
rrf = Σ weight / (k + rank)

关键: 只信名次，不信分数
因为 BM25 分和余弦相似度分数量纲完全不同，不能直接加权
RRF 用名次（rank）替代分数，天然跨模态可比
```

**思路 2：script_score 归一化加权（mem0 用）**

```painless
double sim = cosineSimilarity(params.query_vector, 'vector');
sim = (sim + 1.0) / 2.0;                               // 余弦 [-1,1] → [0,1]
double keywordScore = _score / params.max_keyword_score; // BM25 归一化
return sim * 0.2 + keywordScore * 0.8;                  // 加权
```

**两种思路对比**：

| | RRF | script_score |
|---|---|---|
| 核心 | 只信名次 | 先归一化分数再加权 |
| 分数量纲 | 完全忽略 | 归一化到同量纲 |
| 灵活性 | 权重只有 k 一个旋钮 | 权重可精确控制（0.2/0.8） |
| 实现 | Java 侧（简单） | ES painless 脚本（复杂） |

### Q9: 为什么 RRF 的 k 值要调？k=60 合理吗？

RRF 公式 `1/(k+rank)`，k 是平滑常数，控制"名次差异被抹平的程度"。

```
k 越小: 名次差异被放大，头部优势明显（rank1 vs rank2 分数差大）
k 越大: 名次差异被抹平，头部尾部分数接近（rank1 vs rank2 分数差小）
```

**k 的选择取决于候选池大小**：

```
经典取 60: 面向"上千候选"的检索场景（如网页搜索）
本链路候选 20-40 条: k=60 会把名次差异过度抹平
  → ragent 的注释里明确写了建议调低到 20
```

**ragent 源码注释原文**：

> k=60 会把名次差异过度抹平（头部与尾部分数几乎拉不开），建议按候选池量级调低（如 20）让头部更有区分度。

---

<a id="q5"></a>
## 五、性能优化类

### Q10: ES 查询有哪些性能优化手段？

1. **`_source` 过滤**（两个项目都用了）：
```java
.source(s -> s.filter(e -> e.excludes(List.of("vector_768"))))
// 不返回向量字段，省 768×4 字节/文档的传输
```

2. **filter 上下文**（不参与打分，可缓存）：
```
bool.filter 里的条件不计算相关性分数，ES 会缓存结果
bool.must 里的条件参与打分，不能缓存
所以: 精确匹配（租户、状态）用 filter，全文匹配用 must
```

3. **numCandidates 调优**：numCandidates 越小越快但精度越低，是精度/性能的旋钮

4. **合理分片数**：分片过多增加协调开销，过少并发不足

5. **倒排索引按需精简**：`index_options`、`norms` 等按需关闭

### Q11: ES 的 bool query 里 must 和 filter 的区别？

| | must | filter |
|---|---|---|
| 是否打分 | ✅ 参与相关性评分 | ❌ 不评分（只判断匹配） |
| 是否缓存 | ❌ 不缓存 | ✅ 结果可缓存 |
| 用途 | "文档越匹配越高分" | "必须满足这个条件" |

**实践**：精确过滤（租户、状态、类型）用 filter，全文匹配用 must。这样 filter 结果能缓存，且不影响相关性排序。

---

<a id="q6"></a>
## 六、集群架构类

### Q12: ES 的集群架构？分片和副本是什么？

```
ES 集群
 └─ 节点（Node）: 一个 ES 实例
     └─ 分片（Shard）: 索引的数据分块
         ├─ 主分片（Primary）: 数据写入入口
         └─ 副本分片（Replica）: 主分片的备份，容灾 + 读负载均衡
```

**分片**：一个索引的数据切分成多份，分散到不同节点，实现水平扩展。

**副本**：主分片的拷贝，提供高可用（主挂了副本顶上）和读性能提升（副本也能响应查询）。

**为什么重要**：ragent/PowerAgent 的开发环境是单节点（`discovery.type=single-node`），生产要配多节点 + 副本保证可用性。

---

# 下篇：Milvus 向量数据库

<a id="q7"></a>
## 七、Milvus 基础

### Q13: Milvus 是什么？和普通数据库（MySQL/ES）有什么区别？

Milvus 是**专门为向量设计的分布式数据库**（Zilliz 开源），定位是"向量的 MySQL"。

```
普通数据库: 存标量数据（数字/字符串），按字段精确查询或全文检索
Milvus:     存高维向量（768/1536 维 float 数组），按"相似度"检索
```

**核心区别**：普通数据库的查询是"精确匹配"（`WHERE id = 1`），Milvus 的查询是"近似最近邻"（"找出和这个向量最相似的 10 个"）。

**它解决的问题**：embedding 向量有几十万到几百万条，暴力扫描太慢，Milvus 用索引（HNSW/IVF）做近似检索，把 O(N) 降到 O(log N)。

### Q14: Milvus 支持哪些索引类型？怎么选？

| 索引 | 原理 | 精度 | 速度 | 内存 | 适用 |
|---|---|---|---|---|---|
| **FLAT** | 暴力扫描 | 100%（精确） | 最慢 | 最低 | 数据量 < 1万，要求精确 |
| **IVF_FLAT** | K-Means 聚类，只查最近几个聚类 | 高 | 快 | 低 | 中等数据量（百万级） |
| **IVF_SQ8** | IVF + 8bit 量化 | 中（有损） | 更快 | 更低 | 内存敏感 |
| **HNSW** | 多层图导航 | 高（可调） | 极快 | 高（图结构） | 高并发查询，推荐默认 |
| **DISKANN** | 磁盘索引 | 高 | 快 | 最低 | 十亿级，内存放不下 |

**项目里的选择**：ragent 的 `MilvusVectorStoreAdmin` 用 **HNSW + COSINE**：

```java
.indexType(IndexParam.IndexType.HNSW)
.metricType(IndexParam.MetricType.COSINE)
```

HNSW 是当前最主流的默认选择——查询快、精度可调（ef 参数），代价是内存占用高（图结构）。

### Q15: 相似度度量 metric_type 有几种？怎么选？

| 度量 | 公式 | 适用 |
|---|---|---|
| **COSINE** | 余弦相似度 | 文本 embedding（最常用） |
| **L2** | 欧氏距离 | 图像特征、数值向量 |
| **IP** | 内积 | 归一化后的向量（等价余弦） |

**关键**：度量类型要和 embedding 模型的训练方式匹配。文本 embedding（OpenAI/BGE/GTE）都是余弦相似度语义，所以用 COSINE。选错度量类型检索结果会完全不对。

ragent 用 `MetricType.COSINE`，查询时 `1 - (embedding <=> ?)` 算的就是余弦相似度（pgvector 侧）。

---

<a id="q8"></a>
## 八、Milvus 架构

### Q16: Milvus 的存储计算分离架构？

Milvus 2.x 是**存储计算分离**的分布式架构：

```
┌─ 访问层（Proxy）─────────────┐
│  接收客户端请求，负载均衡       │
└──────────┬──────────────────┘
           │
┌─ 协调层（Coordinator）────────┐
│  QueryNode 管理查询 / DataNode 管理写入 / IndexNode 管理索引 │
└──────────┬──────────────────┘
           │
┌─ 执行层（Worker Node）────────┐
│  QueryNode: 执行向量检索       │
│  DataNode:  处理写入           │
│  IndexNode: 构建索引           │
└──────────┬──────────────────┘
           │
┌─ 存储层（Object Storage）─────┐
│  MinIO / S3：存原始向量和索引文件（持久化） │
└──────────────────────────────┘
```

**亮点**：存储用对象存储（MinIO/S3），计算用无状态 Worker，可以独立扩缩容。数据持久化在 S3，Worker 挂了重启从 S3 加载，不影响数据。

### Q17: Collection / Partition / Segment 是什么？

```
Collection（集合） = 表
  ├─ Partition（分区） = 逻辑分区，加速过滤
  │     └─ Segment（段） = 物理存储单元，数据写入/索引的基本单位
```

- **Collection**：向量 + 标量字段的集合，类比 MySQL 表
- **Partition**：Collection 内的逻辑分区，可按业务维度分（如按时间），查询时只扫相关分区
- **Segment**：物理存储单元，数据写入先落 Segment，达到阈值后合并（类似 LSM Tree 的 compaction）

---

<a id="q9"></a>
## 九、项目实战（ragent / PowerAgent）

### Q18: 共享 Collection 还是每个知识库一个 Collection？

**ragent 用共享 Collection**（这是重要设计决策）：

```
方案 A（每库一 Collection）: 30 个知识库 → 30 个 Collection → 30 个索引
  问题: 30 组索引参数要管理、migrate 要操作 30 个 Collection

方案 B（共享 Collection）: 30 个知识库 → 1 个 Collection + collection_name 标量字段
  优势: 单索引管理、迁移简单
```

**为什么共享可行**：Milvus 2.x 单 Collection 支持百万级向量 + 标量过滤，性能足够。

**隔离方式**：每个 chunk 写入带 `collection_name` 标量字段，检索时 filter：

```java
// 单库检索
String filter = "collection_name == \"hr_kb\"";

// 全局检索（跨库召回）
String filter = "collection_name in [hr_kb, tech_kb, product_kb]";

// 删除文档
filter = "collection_name == \"hr_kb\" && doc_id == \"doc123\""
```

**类比**：这和 ragent 的 ES 关键词索引（`rag_keyword_store` 单索引按 `collection_name` 区分）、PG 向量（单表按 `collection_name` 列过滤）是**同一个共享模型**——三套存储都用"单表/单集合 + collection_name 字段隔离"，架构一致。

### Q19: Milvus 不支持事务，怎么保证一致性？

**这是关键的最终一致性问题**：

```java
// persistChunksAndVectorsAtomically
transactionOperations.executeWithoutResult(status -> {
    knowledgeChunkService.deleteByDocId(docId);          // ① MySQL 删 chunk
    knowledgeChunkService.batchCreate(docId, chunks);    // ② MySQL 写 chunk
    vectorStoreService.deleteDocumentVectors(...);       // ③ Milvus 删向量 ← 不支持事务
    vectorStoreService.indexDocumentChunks(...);         // ④ Milvus 写向量 ← 不支持事务
});
```

**问题**：MySQL 的 ①②在 Spring 事务里，但 Milvus 的 ③④是独立调用，不参与 MySQL 事务。如果 ④ 失败：
- MySQL 的 ①②回滚（chunk 没变）
- 但 ③ 的 Milvus 删除已经执行了（旧向量没了）

**怎么处理**：**最终一致性**——失败后文档状态标记为 FAILED，用户手动重试。重试时 ③ 的 filter 条件（`collection_name + doc_id`）仍然匹配，会重新删（幂等），再写新向量。最终达到一致。

**面试话术**：MySQL 事务 + Milvus 无事务是跨存储的经典问题，我们不用分布式事务（太重），而是靠"失败标记 + 幂等重试"达到最终一致性。删除操作的 filter 是幂等的（删不存在的东西不报错），重试安全。

### Q20: ragent 的双实现切换怎么做的？

```yaml
rag.vector.type: pg  # 可选 milvus / pg
```

两套实现都实现 `VectorStoreService` 接口，通过 `@ConditionalOnProperty` 激活：

```
VectorStoreService 接口
  ├─ PgVectorStoreService       （pgvector，@ConditionalOnProperty("rag.vector.type"="pg")）
  └─ MilvusVectorStoreService   （Milvus，@ConditionalOnProperty("rag.vector.type"="milvus")）
```

**为什么做双实现**：
- pgvector：部署简单（PG 已存在），小数据量够用，事务一致性好
- Milvus：大数据量（百万级+）性能更优，但多一个中间件

**ragent 当前用 pg**——因为数据量小（15 万 chunk），pgvector HNSW 够用，省一个 Milvus 中间件。

---

<a id="q10"></a>
## 十、三方案对比（核心）

### Q21: Milvus vs pgvector vs ES knn，怎么选？

三个方案都能做向量检索，核心差异：

| 维度 | Milvus | pgvector | ES knn |
|---|---|---|---|
| 定位 | 专业向量数据库 | PG 的向量扩展插件 | 搜索引擎的向量能力 |
| 向量检索性能 | ★★★★★ 最强 | ★★★ 中等 | ★★★★ 较强 |
| 标量过滤 | ✅ 支持（不如 ES） | ✅ SQL WHERE | ✅ 最强（bool query） |
| 全文检索 | ❌ 无 | ❌ 弱（PG 全文检索） | ✅ 最强（BM25） |
| 事务 | ❌ 无 | ✅ 有（随 PG 事务） | ❌ 无 |
| 部署成本 | 高（独立集群 + S3） | 低（PG 已存在） | 中（ES 已存在） |
| 数据量级 | 十亿级 | 百万级以内 | 亿级 |
| 一致性 | 最终一致 | 强一致（事务） | 最终一致 |

### Q22: 两个项目分别怎么选？

**ragent**：用 pgvector（当前）或 Milvus，都是"专业向量存储"路线，因为：
- 向量检索是核心需求，要专门的向量索引
- 全文检索（ES）和向量检索（pgvector/Milvus）分离，各用最优方案

**PowerAgent**：ES 主力 + Milvus 辅助，因为：
- ES 8.x 原生 knn 后，一个引擎同时支持向量 + 全文，不用额外部署
- Milvus 通过 VectorSpi 抽象层保留，用于纯向量高吞吐场景（旧 Batch 流程）

### Q23: 什么时候选哪个？决策框架

```
① 数据量 < 100万 且 已用 PostgreSQL
   → pgvector（省一个中间件，事务一致）

② 数据量 > 100万 且 向量检索是核心
   → Milvus（专业向量数据库，性能最强）

③ 需要向量 + 全文混合检索 且 已用 ES
   → ES 8.x knn（一个引擎搞定两种检索）

④ 需要强事务一致（向量和业务数据必须原子）
   → pgvector（唯一支持事务的）
```

### Q24: 向量检索为什么是"近似"的？ANN vs 暴力扫描

**暴力扫描（FLAT）**：每条向量都和 query 算距离，返回最近的 K 个。**精确**但 O(N)。

**近似最近邻（ANN）**：用索引结构（HNSW 图/IVF 聚类）快速定位到"大概最近"的区域，只在这个区域里精确算。**可能漏掉个别真正最近的**，但速度快几个数量级。

```
N=100万条向量:
  暴力扫描: 100万次距离计算，约 1 秒
  HNSW(ef=128): 只访问 ~几千个节点，约 1 毫秒

代价: HNSW 可能漏掉 1% 的最优结果（召回率 99%）
```

**为什么 RAG 场景能接受近似**：RAG 后续还有 Rerank 精排兜底，向量检索召回"差不多相关"的候选就够了，Rerank 会再精挑。这就是"粗排 + 精排"两阶段设计。

### Q25: ef 参数是什么？怎么调？

ef（ef_search）是 HNSW 查询时"考察候选数"，是精度/性能的核心旋钮：

```java
// ragent MilvusVectorRetrieverService
Map<String, Object> params = new HashMap<>();
params.put("metric_type", "COSINE");
params.put("ef", 128);   // 查询时考察 128 个候选
```

```
ef 越小: 查询越快，召回率越低
ef 越大: 查询越慢，召回率越高（接近暴力扫描）

默认 128，精度要求高调到 256-512，性能敏感调到 64-128
```

ragent 的 pgvector 侧对应参数是 `SET hnsw.ef_search = 200`。

---

<a id="q11"></a>
## 十一、面试策略

### 高频追问及应对

| 追问 | 回答要点 |
|---|---|
| "为什么不用 ES 做向量检索？" | ragent 用 pgvector 是为了向量和业务数据同库事务一致，省一个中间件 |
| "RRF 和 script_score 选哪个？" | 候选池小用 RRF（简单），需要精确控制权重用 script_score |
| "为什么 ragent 用 pgvector 不用 Milvus？" | 数据量 15 万 chunk，pgvector HNSW 够用；PG 已存在省中间件；事务一致性好 |
| "Milvus 不支持事务怎么办？" | 最终一致性 + 失败标记 + 幂等重试（filter 删除幂等） |
| "共享 Collection 会不会互相干扰？" | collection_name 标量字段隔离，检索/删除都带 filter，单 Collection 百万级够用 |
| "向量检索是精确的吗？" | 不是，ANN 近似检索，召回率 99%，靠 Rerank 精排兜底 |
| "ES 都能 knn 了还要 Milvus 吗？" | 数据量大/纯向量高吞吐场景 Milvus 更强；ES 优势是一个引擎搞定向量+全文 |

### 一句话总结

三个方案都是"向量索引 + 近似检索"，差异在定位——**Milvus** 是专业向量库（性能最强、无事务）、**pgvector** 是 PG 扩展（部署简单、有事务）、**ES knn** 是搜索引擎附带能力（向量+全文一体）。选型看数据量、事务需求、是否已用 ES/PG。

### 项目讲法建议

**讲 PowerAgent**：优先讲 ES 用法（功能更丰富），重点突出 knn 嵌套 filter、script_score 混合检索、双索引分离。

**讲 ragent**：重点讲 ik 中文分词、BM25 + pgvector 的"ES 只做关键词、向量走 PG"分工、minSimilarity 阈值、共享 collection 隔离策略。

---

<a id="q12"></a>
## 十二、查询语句速查（实战）

### 1. match 查询系列（全文匹配，会分词）

#### match（分词后 OR 匹配）

```json
// 查询 "北京天气" → 分词 [北京, 天气] → OR 匹配（命中任一词即可）
{
  "query": {
    "match": { "content": "北京天气" }
  }
}
```

**特点**：会分词、OR 语义、按 BM25 打分。是全文检索最基础的查询。

#### match_phrase（短语匹配，词序一致）

```json
// 必须连续匹配 "北京" 紧接着 "天气"
{
  "query": {
    "match_phrase": { "content": "北京天气" }
  }
}
```

**特点**：分词后要求词按原顺序相邻出现，精确度比 match 高。适合"专有名词"精确匹配。

#### multi_match（多字段匹配，ragent 实际用的）

```json
// ragent 的关键词检索：在 content + outline 两个字段搜
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "DataFlow引擎",
            "fields": ["content", "outline"]   // ★ 多字段
          }
        }
      ],
      "filter": [
        { "terms": { "collection_name": ["1"] } }  // 知识库过滤
      ]
    }
  }
}
```

对应 ragent 源码 `EsKeywordRetrieverService`：

```java
b.must(m -> m.multiMatch(mm -> mm
        .query(query)
        .fields("content", "outline")));   // content 正文 + outline 章节路径
```

**为什么用 multi_match 而不是 match**：ragent 的 chunk 有 content（正文）和 outline（章节路径）两个字段，用户可能问正文内容，也可能问"第几章讲了什么"，需要同时搜两个字段。

### 2. term 查询系列（精确匹配，不分词）

#### term（单值精确）

```json
// 精确匹配 collection_name == "1"（不分词）
{ "query": { "term": { "collection_name": "1" } } }
```

#### terms（多值 IN）

```json
// collection_name in ["1", "2"]
{ "query": { "terms": { "collection_name": ["1", "2"] } } }
```

**term vs match 的核心区别**：

| | match | term |
|---|---|---|
| 是否分词 | ✅ 查询词也分词 | ❌ 不分词，整值匹配 |
| 字段类型 | text（分词字段） | keyword（精确字段） |
| 场景 | 全文搜索 | ID、状态、枚举等精确值 |

**经典错误**：对 text 字段用 term 查询会查不到（因为 text 字段存的是分词结果，term 拿完整词去匹配分词后的 token 匹配不上）。精确匹配必须用 keyword 字段。

### 3. bool 查询（组合，ragent 和 PowerAgent 都重度使用）

```json
{
  "query": {
    "bool": {
      "must":     [ ... ],   // 必须匹配，参与打分
      "should":   [ ... ],   // 应该匹配，加分（OR）
      "filter":   [ ... ],   // 必须匹配，不打分，可缓存
      "must_not": [ ... ]    // 必须不匹配
    }
  }
}
```

四个子句的语义：

| 子句 | 语义 | 打分 | 缓存 |
|---|---|---|---|
| must | AND（必须满足） | ✅ | ❌ |
| should | OR（满足加分，最少匹配数可配） | ✅ | ❌ |
| filter | AND（必须满足） | ❌ | ✅ |
| must_not | NOT（必须不满足） | ❌ | ✅ |

**实战规则**：精确过滤（租户、状态、类型）用 filter，全文匹配用 must。

### 4. knn 查询（向量检索，ES 8.x 原生）

```json
{
  "knn": {
    "field": "vector_768",       // 向量字段
    "query_vector": [...768维...],
    "num_candidates": 200,       // 粗排候选数（HNSW 考察数）
    "k": 10,                     // 返回数
    "filter": {                   // ★ 过滤条件（先过滤后召回）
      "bool": {
        "must": [
          { "terms": { "datasetId": ["know_001"] } },
          { "term": { "isEnabled": 0 } }
        ]
      }
    }
  }
}
```

对应 PowerAgent 源码 `directVectorSearch()`：`KnnQuery` 里嵌套 `filter`，做多租户隔离。

### 5. sparse_vector 查询（稀疏向量，ELSER/SPLADE）

ES 8.11+ 引入的**稀疏向量**，用于稀疏嵌入模型（ELSER、SPLADE）。和稠密向量（dense vector）的区别：

| | dense vector（knn） | sparse vector |
|---|---|---|
| 向量 | 768/1536 维全非零 | 大部分为零，只有命中的 token 有值 |
| 模型 | OpenAI/BGE/GTE | ELSER（ES 官方）、SPLADE |
| 语义 | 语义相似 | 关键词扩展（同义词/相关词） |
| 查询 | `knn` | `sparse_vector` |

```json
// sparse_vector 查询（用 ELSER 稀疏嵌入）
{
  "query": {
    "sparse_vector": {
      "field": "sparse_embedding",
      "inference_id": "my-elser-model",   // 推理模型
      "query": "什么是机器学习"             // 原始文本，ES 内部转稀疏向量
    }
  }
}
```

**为什么要有 sparse_vector**：dense 向量擅长语义但精确关键词匹配差，sparse 向量反过来——擅长词级别匹配（因为稀疏向量就是"词 → 权重"）。ES 8.11 之后可以用 ELSER 做"语义 + 关键词"一体的稀疏检索，比传统 BM25 多了同义词扩展能力。

**PowerAgent/ragent 现状**：两个项目都**没用到 sparse_vector**——用的还是传统"dense knn + BM25 bool"混合 + RRF 融合。sparse_vector 是 ES 8.11+ 的新能力，可以作为面试的"了解前沿"加分项。

### 6. script_score（自定义打分，mem0 用）

```json
{
  "query": {
    "script_score": {
      "query": { "match": { "metadata.data": "用户偏好" } },
      "script": {
        "source": """
          double sim = cosineSimilarity(params.query_vector, 'vector');
          sim = (sim + 1.0) / 2.0;
          double keywordScore = _score / params.max_keyword_score;
          return sim * 0.2 + keywordScore * 0.8;
        """
      }
    }
  }
}
```

对应 mem0 的混合检索——用 painless 脚本把向量相似度和 BM25 分归一化后加权。

### 项目实际用到的查询语句汇总

| 查询类型 | ragent | PowerAgent |
|---|---|---|
| multi_match | ✅ 关键词检索（content+outline） | ✅ 全文检索 |
| term/terms | ✅ collection_name/doc_id 过滤 | ✅ datasetId/knowledgeId 过滤 |
| bool | ✅ filter + must 组合 | ✅ must + filter + must_not |
| knn | ❌（向量走 pgvector） | ✅ ES 8.x 原生 knn |
| match | ✅（间接，multi_match 内） | ✅ |
| script_score | ❌ | ✅ mem0 记忆检索 |
| sparse_vector | ❌ | ❌（都是传统 BM25） |
| delete_by_query | ✅ 删文档/删库 | — |

### 面试话术

> 查询语句我用得最多的是 multi_match（content + outline 多字段全文匹配）、bool 的 must + filter 组合（must 全文打分、filter 精确过滤）、knn 向量检索（ES 8.x 原生，嵌套 filter 做多租户隔离）。mem0 记忆检索还用了 script_score 自定义打分，把余弦相似度和 BM25 分归一化后按 0.2/0.8 加权。
>
> sparse_vector 是 ES 8.11 的新能力，用 ELSER 稀疏嵌入做"语义 + 关键词"一体检索，我们目前还是传统"dense knn + BM25"混合，sparse_vector 是后续可以尝试的方向。
