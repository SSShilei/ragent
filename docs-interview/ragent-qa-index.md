# Ragent 面试问题全集索引（可点击跳转）

> 点击"定位"列的链接即可跳转到对应章节。需用 IDEA 打开本目录下的文件（内置 Markdown 预览支持跨文件锚点）。

---

## 1. Chunking / 文档分块（★ 核心亮点）

| 问题 | 定位 |
|:---|:---|
| Ragent 和 AgentFlow 的 Chunking 有什么区别？ | [一、对比](ragent-chunking-analysis.md#一、Ragent vs AgentFlow 的 Chunking 对比) |
| Parser 矩阵：6 个解析器×格式×路由 | [二、Parser 矩阵](ragent-chunking-analysis.md#二、Parser 矩阵：6 个解析器 × 支持的具体格式) |
| MinerU 六步异步解析流程 | [2.4](ragent-chunking-analysis.md#2.4 MinerU 的六步异步解析流程) |
| MinerU 不直接输出 Block——两层分工 | [2.5](ragent-chunking-analysis.md#2.5 MinerU 不是直接输出 Block——两层分工架构) |
| commonmark AST 解析器原理 | [2.6](ragent-chunking-analysis.md#2.6 commonmark-java AST 解析器) |
| 6 种 Block 详细结构 | [三、Block 模型](ragent-chunking-analysis.md#三、Block 模型详解) |
| TableChunker 双文本嵌入 | [4.4](ragent-chunking-analysis.md#4.4 TableChunker：双文本嵌入（Ragent 最大亮点）) |
| BlockAwareChunkerDispatcher 分发机制 | [4.2](ragent-chunking-analysis.md#4.2 BlockAwareChunkerDispatcher 分发机制) |
| HeadingHandler 不产 chunk 只累积路径 | [4.3](ragent-chunking-analysis.md#heading-handler) |
| CodeChunker/ListChunker/ImageChunker | [4.5-4.7](ragent-chunking-analysis.md#code-chunker) |
| ChunkPacker 贪心合并与块级重叠 | [4.8](ragent-chunking-analysis.md#chunk-packer) |
| STRUCTURE_AWARE vs Block-Aware | [5.2.1](ragent-chunking-analysis.md#structure-aware-vs-blockaware) |
| FIXED_SIZE 三层边界对齐 + normalizeText | [5.1](ragent-chunking-analysis.md#fixed-size) |
| VectorChunk 元数据字段（11 个） | [六、VectorChunk](ragent-chunking-analysis.md#六、VectorChunk 元数据字段) |
| 完整案例：解析到入库 4 阶段 | [七、完整案例](ragent-chunking-analysis.md#七、完整案例：一份 Markdown 文件从解析到入库) |
| Parent-Child 等价方案 | [八、Parent-Child](ragent-chunking-analysis.md#八、Parent-Child 等价方案（零额外成本）) |
| 六种业界分块策略评估 | [九、业界策略](ragent-chunking-analysis.md#九、业界六种分块策略评估) |
| Chunking 面试话术 | [十、面试话术](ragent-chunking-analysis.md#十、面试话术) |
| Metadata：博客 7 字段 × Ragent 对照 | [十一、Metadata](ragent-chunking-analysis.md#十一、元数据（Metadata）详解) |
| 每个 chunker 注入字段 + 合并规则 | [11.4](ragent-chunking-analysis.md#11.4 每个 chunker 注入的字段对照表) |
| Metadata 完整生命周期 | [11.5](ragent-chunking-analysis.md#11.5 元数据的完整生命周期) |
| embedding_model 字段缺失分析 | [11.3](ragent-chunking-analysis.md#11.3 逐字段分析) |

---

## 2. Query 重写与决策树

| 问题 | 定位 |
|:---|:---|
| 意图树和 Query 重写并行还是串行？ | [三、关系](ragent-intent-rewrite-config.md#三、意图树和 Query 重写是并行的吗？每次对话都重写吗？) |
| Query 重写完整链路 | [3.2](ragent-intent-rewrite-config.md#3.2 当前 Query 重写策略（Multi-Query 改造后）) |
| Multi-Query 改造前后对比 | [3.7](ragent-intent-rewrite-config.md#3.7 Multi-Query 改造前后对比) |
| Multi-Query 触发条件 | [3.5](ragent-intent-rewrite-config.md#3.5 Multi-Query 变体扩展的触发条件) |
| 精确实体短路正则设计 | [3.8](ragent-intent-rewrite-config.md#3.8 Query 决策树第一档：精确实体短路（exact-entities-bypass）) |
| 完整 Query 决策树 6 档 | [3.9](ragent-intent-rewrite-config.md#3.9 Ragent 当前 Query 决策树（完整版）) |
| 改写 query 用哪个模型？ | [五、改写模型](ragent-intent-rewrite-config.md#五、改写 query 用的哪个模型) |
| 多轮对话指代消解 | [5.5](ragent-intent-rewrite-config.md#5.5 多轮对话与指代消解——怎么判断用户 Query 和上文有关联) |
| temperature 参数调优 | [六、temperature](ragent-intent-rewrite-config.md#六、temperature 参数的作用) |

---

## 3. 意图识别

| 问题 | 定位 |
|:---|:---|
| 意图树有默认实现吗？怎么配置？ | [一、意图树配置](ragent-intent-rewrite-config.md#一、意图树 DefaultIntentClassifier 的实现) |
| KB 意图识别 classifyTargets 6 步 | [2.1](ragent-intent-rewrite-config.md#2.1 classifyTargets 流程) |
| 意图识别四个消费点 | [2.2](ragent-intent-rewrite-config.md#2.2 意图识别不只用在检索中——四个消费点) |
| promptSnippet vs promptTemplate | [1.3](ragent-intent-rewrite-config.md#1.3 t_intent_node 表关键字段) |
| 业内方案对比（5 方案） | [2.4](ragent-intent-rewrite-config.md#2.4 业内方案对比) |

---

## 4. Context 组装 / Prompt 工程

| 问题 | 定位 |
|:---|:---|
| ContextFormatter 三层分支 | [1.1](ragent-core-mechanisms-2.md#1.1 三层分支) |
| CONTEXT_FORMAT_PATH 模板结构 | [1.2](ragent-core-mechanisms-2.md#1.2 单意图（最常见情况）) |
| 等价父文档：docId 分组 + chunkIndex 排序 | [1.3](ragent-core-mechanisms-2.md#1.3 按文档分组 + 排序（"等价父文档"的实现）) |
| MetadataEnrichment 补什么？ | [1.3](ragent-core-mechanisms-2.md#1.3 按文档分组 + 排序（"等价父文档"的实现）) |
| 上下文压缩为什么不学 Claude Code？ | [六、生产工程](ragent-deep-dive-2.md#六、生产工程——真正拉开工程师差距的地方) |

---

## 5. 检索 / Rerank / RRF

| 问题 | 定位 |
|:---|:---|
| 检索召回率低怎么排查？ | [一、排查路径](ragent-deep-dive-1.md#一、检索召回率低的排查路径) |
| Rerank：cross vs bi-encoder | [二、Rerank](ragent-core-mechanisms-2.md#二、Rerank 详解——为什么需要两轮排序) |
| Rerank 模型怎么排序打分？ | [2.1](ragent-core-mechanisms-2.md#2.1 Bi-encoder vs Cross-encoder) |
| Rerank 归因日志解读 | [2.4](ragent-core-mechanisms-2.md#2.4 归因日志) |

---

## 6. 限流 / 并发控制

| 问题 | 定位 |
|:---|:---|
| ChatQueueLimiter SSE 入口限流 | [3.1](ragent-core-mechanisms-2.md#3.1 ChatQueueLimiter——SSE 入口限流) |
| FairDistributedRateLimiter 分布式公平队列 | [3.2](ragent-core-mechanisms-2.md#3.2 FairDistributedRateLimiter——Redis 分布式公平队列) |
| 公平队列时间线 + 分步详解 | [3.2](ragent-core-mechanisms-2.md#3.2 FairDistributedRateLimiter——Redis 分布式公平队列) |
| 5 个安全机制对照表 | [3.2](ragent-core-mechanisms-2.md#3.2 FairDistributedRateLimiter——Redis 分布式公平队列) |

---

## 7. SSE 流式 / 多模型 Fallback

| 问题 | 定位 |
|:---|:---|
| SSE 完整链路 | [4.1](ragent-core-mechanisms-2.md#4.1 整体链路) |
| 多模型 Fallback | [4.2](ragent-core-mechanisms-2.md#4.2 多模型 Fallback) |
| 前端取消反向释放 permit | [4.5](ragent-core-mechanisms-2.md#4.5 前端取消) |
| SSE 断线重连方案 | [6.1](ragent-deep-dive-2.md#6.1 SSE 断线重连与断点续传) |
| 用户停止后如何真正取消推理 | [6.2](ragent-deep-dive-2.md#6.2 用户停止生成后如何真正取消推理) |

---

## 8. 安全

| 问题 | 定位 |
|:---|:---|
| 间接 Prompt Injection——恶意简历案例 | [4.1](ragent-deep-dive-2.md#4.1 检索内容中的恶意指令——间接 Prompt Injection) |
| Text2SQL AST 校验/只读/脱敏 | [4.2](ragent-deep-dive-2.md#4.2 Text2SQL——AST 校验、只读限制、脱敏) |
| 敏感数据分层识别 | [4.3](ragent-deep-dive-2.md#4.3 敏感数据识别与脱敏) |
| MCP 安全风险矩阵 | [3.3](ragent-deep-dive-1.md#3.3 MCP 安全风险) |

---

## 9. 评测

| 问题 | 定位 |
|:---|:---|
| 9 维评测指标体系 | [5.1](ragent-deep-dive-2.md#5.1 评测指标体系) |
| EvalController 能自动算的指标 | [5.2](ragent-deep-dive-2.md#5.2 Ragent 能算哪些) |
| 成本分析模型 | [5.4](ragent-deep-dive-2.md#5.4 成本分析（最容易被忽略的差距点）) |

---

## 10. 生产工程

| 问题 | 定位 |
|:---|:---|
| SSE 断线重连与断点续传 | [6.1](ragent-deep-dive-2.md#6.1 SSE 断线重连与断点续传) |
| Prompt Cache / 语义缓存 / 错误命中 | [6.4](ragent-deep-dive-2.md#6.4 Prompt Cache、语义缓存与错误命中) |
| Token 预算与租户成本控制 | [6.5](ragent-deep-dive-2.md#6.5 Token 预算与租户成本控制) |

---

## 11. Agent 状态机 / MCP

| 问题 | 定位 |
|:---|:---|
| Agent 任务状态机 | [二、状态机](ragent-deep-dive-1.md#二、Agent 任务状态机设计) |
| 避免工具重复执行 | [2.3](ragent-deep-dive-1.md#2.3 避免工具重复执行) |
| MCP 完整通信模型 | [3.1](ragent-deep-dive-1.md#3.1 MCP 完整通信流程) |
| MCP vs Function Calling vs OpenAPI | [3.2](ragent-deep-dive-1.md#3.2 vs Function Calling vs OpenAPI) |

---

## 12. 全链路 / Trace / 调度

| 问题 | 定位 |
|:---|:---|
| @RagTraceNode 全链路 Trace | [core-mechanisms-3](ragent-core-mechanisms-3.md) |
| 文档自动同步三级变更检测 | [core-mechanisms-3](ragent-core-mechanisms-3.md) |
| Pipeline 数据通道 vs 直接分块 | [core-mechanisms-3](ragent-core-mechanisms-3.md) |
| 降级策略 | [core-mechanisms-3](ragent-core-mechanisms-3.md) |

---

## 13. 基础笔记

项目概览 / 7 步流水线 / 数据库 20 张表 / 长短期记忆 / 向量库 Milvus：
- [ragent-interview-notes.md](ragent-interview-notes.md)
- [ragent-interview-notes-2.md](ragent-interview-notes-2.md)

---

## 说明

- 链接格式 `[text](文件.md#标题锚点)`，锚点 = 目标文件的章节标题
- 在 IDEA 中打开本文件，按住 Ctrl 点击链接跳转（Markdown 预览里是普通点击）
- 若某条中文锚点跳转失败，是因为该标题含特殊字符（如 `—`、`×`），在源文件里用 Ctrl+F 搜标题即可定位
