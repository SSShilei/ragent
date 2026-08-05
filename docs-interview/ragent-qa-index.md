# Ragent 面试问题全集索引

> 整合 `docs-interview/` 下 Ragent 相关 md 的全部问题，按模块分类。
> 每行标注来源文件定位（章节号或行号）。

---

## 1. Chunking / 文档分块（★ 核心亮点）

| 问题 | 定位 |
|:---|:---|
| Ragent 和 AgentFlow 的 Chunking 有什么区别？ | chunking-analysis 一 |
| Parser 矩阵：6 个解析器×支持格式×路由优先级 | chunking-analysis 二 |
| MinerU 六步异步解析流程 | chunking-analysis 二.4 |
| MinerU 不直接输出 Block——两层分工架构 | chunking-analysis 二.5 |
| commonmark AST 解析器原理 | chunking-analysis 二.6 |
| 6 种 Block 详细结构（sealed interface + JSON 示例） | chunking-analysis 三 |
| TableChunker 双文本嵌入（content vs embeddingText） | chunking-analysis 四.4 |
| BlockAwareChunkerDispatcher 分发机制 | chunking-analysis 四.2 |
| HeadingHandler 不产 chunk 只累积 outlinePath | chunking-analysis 四.3 |
| CodeChunker/ListChunker/ImageChunker 策略差异 | chunking-analysis 四.5-7 |
| ChunkPacker 贪心合并与块级重叠 | chunking-analysis 四.8 |
| STRUCTURE_AWARE vs Block-Aware 对比（6 个覆盖不到的能力） | chunking-analysis 五.2.1 |
| FIXED_SIZE 三层边界对齐 + normalizeText | chunking-analysis 五.1 |
| VectorChunk 元数据字段（11 个） | chunking-analysis 六 |
| 完整案例：Markdown 文件从解析到入库 4 阶段 | chunking-analysis 七 |
| Parent-Child 等价方案（零额外成本） | chunking-analysis 八 |
| 六种业界分块策略评估 | chunking-analysis 九 |
| Chunking 面试话术（4 个模板） | chunking-analysis 十 |
| Metadata 设计：博客 7 字段 × Ragent 对照 | chunking-analysis 十一 |
| 每个 chunker 注入的字段对照表 + ChunkPacker 合并规则 | chunking-analysis 十一.4 |
| Metadata 完整生命周期（Parser→Chunker→Packer→Embedding→向量库→MetadataEnrichment） | chunking-analysis 十一.5 |
| embedding_model 字段缺失分析 | chunking-analysis 十一.3 |
| STRUCTURE_AWARE 可以看成手动的对 markdown 分成 block 吗？ | chunking-analysis 五.2.1 |

---

## 2. Query 重写与决策树

| 问题 | 定位 |
|:---|:---|
| 意图树和 Query 重写是并行还是串行？每次都会重写吗？ | intent-rewrite-config 三 |
| Query 重写完整链路（术语归一化→改写拆分→Multi-Query变体→精确实体短路） | intent-rewrite-config 三.2 |
| Multi-Query 变体扩展改造前后对比（6 维度表+面试话术） | intent-rewrite-config 三.7 |
| Multi-Query 触发条件（短query≤10字符）+ shouldExpand 逻辑 | intent-rewrite-config 三.5 |
| 精确实体短路 ExactEntityDetector 正则设计（4 类+6 设计决策） | intent-rewrite-config 三.8 |
| 完整 Query 决策树（6 档流程图+与业界对照矩阵） | intent-rewrite-config 三.9 |
| 改写 query 用哪个模型？（默认 chat 模型，走 deepseek-v4-flash） | intent-rewrite-config 五 |
| 多轮对话指代消解——怎么判断 Query 和上文关联 | intent-rewrite-config 五.5 |
| temperature 参数：数学原理、Ragent 5 个调用点调优表 | intent-rewrite-config 六 |

---

## 3. 意图识别

| 问题 | 定位 |
|:---|:---|
| 意图树有默认实现吗？怎么配置？（t_intent_node 表 + INSERT SQL） | intent-rewrite-config 一 |
| KB 意图识别 classifyTargets 6 步流程 | intent-rewrite-config 二.1 |
| 意图识别不只用在检索——四个消费点（歧义/闲聊/检索/Prompt增强） | intent-rewrite-config 二.2 |
| promptSnippet vs promptTemplate 区别 | intent-rewrite-config 一.3 |
| 业内方案对比：5 方案（无意图/规则路由/语义路由/LLM分类/多Agent） | intent-rewrite-config 二.4 |
| 意图树为空四种能力全丢对照表 | intent-rewrite-config 二.2 |

---

## 4. Context 组装 / Prompt 工程

| 问题 | 定位 |
|:---|:---|
| ContextFormatter 三层分支（无意图/单意图/多意图） | core-mechanisms-2 一.1 |
| CONTEXT_FORMAT_PATH 模板结构（7 个 section） | core-mechanisms-2 一.2 |
| 按 docId 分组 + chunkIndex 排序——等价父文档核心实现 | core-mechanisms-2 一.3 |
| MetadataEnrichment 补什么：检索结果只有 id/text/score，回表补 docId/chunkIndex/docName | core-mechanisms-2 一.3 |
| 多意图合并去重（chunkId 去重 + LinkedHashMap） | core-mechanisms-2 一.4 |
| 上下文压缩怎么做？为什么不考虑 Claude Code 方式？ | deep-dive-2 六 |

---

## 5. 检索 / Rerank / RRF

| 问题 | 定位 |
|:---|:---|
| 检索召回率低怎么排查？（6 层框架+诊断 SQL） | deep-dive-1 一 |
| Rerank：cross-encoder vs bi-encoder、为什么放 RRF 之后 | core-mechanisms-2 二 |
| Rerank 模型怎么排序打分？cross-encoder 原理 | core-mechanisms-2 二 |
| Rerank 归因日志解读（向量 vs 关键词 Rerank 前后存活率） | core-mechanisms-2 二.4 |
| 向量化维度：1536 维 + 维度一致性校验 | chunking-analysis / notes |
| PGVector HNSW 参数兼容修复 | 工程实践 |

---

## 6. 限流 / 并发控制

| 问题 | 定位 |
|:---|:---|
| ChatQueueLimiter SSE 入口限流 | core-mechanisms-2 三.1 |
| FairDistributedRateLimiter Redis 分布式公平队列详解 | core-mechanisms-2 三.2 |
| 公平队列完整时间线 + 分步详解（入队/轮询/Lua原子抢占/取消/状态机） | core-mechanisms-2 三.2 |
| 5 个安全机制对照表（entry标记/Lua/lease/RTopic/CAS） | core-mechanisms-2 三.2 |

---

## 7. SSE 流式 / 多模型 Fallback

| 问题 | 定位 |
|:---|:---|
| SSE 完整链路（pipeline→RoutingLLMService→SseEmitter） | core-mechanisms-2 四.1 |
| 多模型 Fallback（逐个尝试+健康标记+60s首包超时+30s半开） | core-mechanisms-2 四.2 |
| 前端取消反向释放 permit | core-mechanisms-2 四.5 |
| StreamTaskManager Redis 跨实例广播取消链路 | core-mechanisms-2 四.5 |
| SSE 断线重连方案（taskId+lastEventId 进度恢复） | deep-dive-2 六.1 |
| 用户停止后如何真正取消推理（5 步完整链路） | deep-dive-2 六.2 |

---

## 8. 安全

| 问题 | 定位 |
|:---|:---|
| 间接 Prompt Injection——恶意简历案例 + 四层防护 | deep-dive-2 四.1 |
| Text2SQL AST 校验/只读限制/成本控制/脱敏 | deep-dive-2 四.2 |
| 敏感数据分层识别（规则→NER→LLM 三层递进） | deep-dive-2 四.3 |
| 安全处置预案（审计/凭证撤销/泄露定位/隔离） | deep-dive-2 四.4 |
| MCP 安全风险矩阵（Server注入/数据外泄/冒充/权限过宽/DDOS） | deep-dive-1 三.3 |

---

## 9. 评测

| 问题 | 定位 |
|:---|:---|
| 9 维评测指标体系（成功率/工具准确率/不必要调用率/引用正确率/事实一致性/接管率/延迟/完成时间/成本） | deep-dive-2 五.1 |
| Ragent EvalController 能自动算的指标（召回率+意图准确率） | deep-dive-2 五.2 |
| 成本分析模型（改写+意图+答题+变体+Rerank 累计） | deep-dive-2 五.4 |

---

## 10. 生产工程

| 问题 | 定位 |
|:---|:---|
| SSE 断线重连与断点续传（高铁场景案例） | deep-dive-2 六.1 |
| Prompt Cache / 语义缓存 / 错误命中取舍 | deep-dive-2 六.4 |
| Token 预算与租户成本控制 | deep-dive-2 六.5 |

---

## 11. Agent 状态机 / MCP

| 问题 | 定位 |
|:---|:---|
| Agent 任务状态机（6 状态→暂停→恢复→死循环防御） | deep-dive-1 二 |
| 避免工具重复执行（幂等token+结果缓存+DB UNIQUE） | deep-dive-1 二.3 |
| MCP 完整通信模型（5 步） | deep-dive-1 三.1 |
| MCP vs Function Calling vs OpenAPI 对比 | deep-dive-1 三.2 |

---

## 12. 全链路 / Trace / 调度

| 问题 | 定位 |
|:---|:---|
| @RagTraceNode 注解式全链路 Trace（ThreadLocal+TTL+节点栈） | core-mechanisms-3 |
| 文档自动同步三级变更检测（ETag→Last-Modified→SHA-256） | core-mechanisms-3 |
| Pipeline 数据通道 vs 直接分块 | core-mechanisms-3 |
| 降级策略：LLM 失败→切备选→兜底消息 | core-mechanisms-3 |

---

## 13. Coremechanisms 第三篇（全链路 / MCP / 评测等）

从 `ragent-core-mechanisms-3.md` 提取

---

## 14. 基础笔记（notes / notes-2）

覆盖项目概览、7 步流水线、数据库 20 张表、长短期记忆、向量库 Milvus 等。
详见 `ragent-interview-notes.md` 和 `ragent-interview-notes-2.md`。

---

## 文件缩写

| 文件 | 行数 | 定位 |
|:---|:---|:---|
| chunking-analysis | 1108 | Chunking 专题（Parser/Block/分块/Metadata/业界策略） |
| intent-rewrite-config | 1059 | 意图树+Query重写+决策树+temperature |
| core-mechanisms-2 | 443 | Context组装/Rerank/限流并发/SSE流式 |
| core-mechanisms-3 | 407 | 全链路Trace/分布式调度/MCP/评测/降级/权限 |
| deep-dive-1 | 354 | 深水区上：召回排查/Agent状态机/MCP安全 |
| deep-dive-2 | 299 | 深水区下：RAG安全/评测/生产工程 |
| interview-notes | 1116 | 原版笔记：管线细节/切分案例/设计模式 |
| interview-notes-2 | 1381 | 扩展版：表结构/意图树/RRF归一化/Agentic RAG |
| resume-qa-0804 | 933 | PA简历QA主文档 |
| qa-index | 216 | PA问题分类索引 |
