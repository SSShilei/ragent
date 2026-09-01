# Ragent 项目面试 Q&A（实战排查与架构深度）

> 本文档基于 ragent 项目实际运行中的问题排查、架构讨论与优化实战整理。
> 锚点格式 `<a id="qN">`，VS Code / IDEA：Ctrl+单击跳转。

---

<a id="q1"></a>
## Q1: SSE 为什么用 SseEmitter 而不是 Flux\<ServerSentEvent\>？

项目是 **Servlet（Tomcat）技术栈**（`spring-boot-starter-web`），不是 Reactive（`spring-boot-starter-webflux`）。`SseEmitter` 底层基于 Servlet 3.1 `AsyncContext` 异步写出，不阻塞 Tomcat 工作线程。换 WebFlux 意味着 Tomcat→Netty、Spring Security→Reactive Security、MyBatis→R2DBC 等全栈替换，改动量是推倒重来的级别。LLM 流式场景下两者效果无实质差别。

见源码：`RAGChatController.java` 返回 `SseEmitter`，`SseEmitterSender.java` 用 `AtomicBoolean` CAS 保证只关一次。

---

<a id="q2"></a>
## Q2: 项目中用到 Function Calling 了吗？

**没有**。`ChatRequest.enableTools` 是预留字段，全项目无任何地方读写它。`infra-ai` 层所有 LLM 客户端不传 `tools` 参数，`OpenAIStyleSseParser` 不处理 `tool_calls` delta，`StreamChatPipeline` 无 while 多轮循环。

当前"工具调用"走的是一条**非 Function Calling 路径**：

```
意图树叶子节点 kind=MCP + mcpToolId
  → LLMMcpParameterExtractor 用 LLM 提取参数
  → McpToolRegistry.getExecutor(toolId) 执行
  → 结果拼入 Prompt 做单次 LLM 生成
```

这与 Function Calling 的核心区别：**工具选择是意图树配置写死的，不是 LLM 自主决定的**。

---

<a id="q3"></a>
## Q3: MCP 和 Function Calling 有什么区别和联系？

| 维度 | MCP | Function Calling |
|---|---|---|
| 解决什么 | 客户端**发现和调用**远端工具（怎么调） | LLM **决定**调哪个工具、传什么参数（该不该调） |
| 协议层 | 标准协议（`tools/list`、`tools/call`），与模型无关 | LLM API 协议的一部分（OpenAI/Anthropic 等各自定义） |
| 核心产物 | 工具定义列表（`Tool` schema）+ 执行结果 | 模型输出的 `tool_calls` JSON（工具名 + 参数） |

**两者是上下层关系**：MCP 在下（执行层），Function Calling 在上（决策层）。串联方式：

```
LLM 输出 tool_calls → 把 tool name 映射到 MCP tools/call → 结果回填给 LLM → 继续推理
```

---

<a id="q4"></a>
## Q4: Agent 稳定性有哪些解决方案？

### 限流（3 层）

| 层 | 实现 | 配置 |
|---|---|---|
| 全局聊天 | `FairDistributedRateLimiter`（Redis ZSet 公平队列 + RPermitExpirableSemaphore + Lua 原子 claim + RTopic 通知） | `max-concurrent=10` |
| 文件上传 | `UploadRateLimitFilter`（Servlet Filter + RPermitExpirableSemaphore） | `max-concurrent=10` |
| 线程池 | 8+ 业务隔离线程池，`AbortPolicy`/`CallerRunsPolicy` | |

### 重试（6 类）

| 机制 | 实现 |
|---|---|
| OkHttp 连接重试 | `retryOnConnectionFailure(true)` |
| 模型故障转移 | `ModelRoutingExecutor` + `ModelHealthStore`断路器（CLOSED/OPEN/HALF_OPEN，`failureThreshold=2`，`openDurationMs=30s`） |
| MinerU 轮询 | `ScheduledExecutorService` 定时轮询，瞬时网络异常不终止，deadline 兜底 |
| MQ 消费重试 | RocketMQ 消息未 ACK 自动重投 |
| 存储 SDK | AWS SDK / OSS SDK 内置自动重试 |
| 前端 SSE | `retryDelayMs * 2^attempt` 指数退避，最多 2 次 |

### 幂等（3 套）

- **@IdempotentSubmit**：Redisson `RLock.tryLock()`，防并发重复提交（`chat`、`stop` 接口）
- **@IdempotentConsume**：Redis Lua `SET NX GET PX` + CONSUMING/CONSUMED 状态机（定义存在，当前未实际使用）
- **存储幂等**：`createBucket` 幂等创建

### 超时/取消矩阵

| 层 | 超时值 |
|---|---|
| SSE 全局 | `SseEmitter(sseTimeoutMs)` 5 分钟 |
| LLM 首包 | 60s → 切换 Provider |
| OkHttp 流式 | connect 30s / write 60s / read 0 |
| OkHttp 同步 | connect 10s / read 30s / write 30s / call 45s |
| MinerU | 300s + 30s 调度缓冲 |
| 流式取消 | `StreamTaskManager` Redis 标记 + RTopic 跨节点广播 |

### 缺失

| 缺口 | 说明 |
|---|---|
| Pipeline 无 checkpoint | 实例挂 → 任务全丢，需重问 |
| `@IdempotentConsume` 未启用 | 切面已存在只是没标在消费者上 |
| S3 `streamPut` 无重试 | 注释写明"需业务层自行重试" |
| 无模型断路器在 Function Calling 场景 | 预留字段存在但链路未通 |

---

<a id="q5"></a>
## Q5: 一条请求的完整执行链路是怎样的？

```
GET /rag/v3/chat?question=xxx
  │
  ├─ @IdempotentSubmit（Redisson 锁防重复）
  ├─ SseEmitter(5min) → 立即返回
  ├─ ChatQueueLimiter.enqueue（全局限流排队）
  │    超时 → SSE REJECT + "系统繁忙"
  │
  ├─ StreamChatPipeline.execute()
  │    ① loadMemory       并行加载摘要+历史
  │    ② rewriteQuery     LLM 改写 + 多问句拆分 + Multi-Query 变体
  │    ③ resolveIntents   并行对每个子问题做 LLM 意图打分
  │    ④ handleGuidance   歧义时反问澄清 → return (短路)
  │    ⑤ handleSystemOnly 纯闲聊直接回答 → return (短路)
  │    ⑥ retrieve         KB 多通道检索 + MCP 工具执行
  │        ├─ VectorSearch(向量) → KeywordSearch(ES BM25) → GraphSearch / WebSearch
  │        ├─ Dedup → RRF 融合(k=60) → Rerank(cross-encoder) → MetadataEnrichment
  │        └─ MCP 工具并行执行（参数 LLM 提取）
  │    ⑦ streamRagResponse Prompt 组装 → LLM 流式输出
  │
  └─ SSE push: META → MESSAGE(think/response) → FINISH → DONE
```

两个短路点（歧义引导/纯闲聊）命中即跳过检索。所有阶段已加 `Stage[x/7]` 耗时日志，排查慢链路时`elapsed` 直接可见。

---

<a id="q6"></a>
## Q6: 意图树只匹配知识库吗？

**不止**，意图结果在 Pipeline 中有 5 个消费点：

| 消费点 | 作用 | 意图为空时的退化 |
|---|---|---|
| `handleSystemOnly()` | `kind=SYSTEM` 且唯一意图 → 跳过检索直接回答 | 闲聊短路不触发 → 多走一轮检索 |
| `handleGuidance()` | 多 KB 意图得分接近 → 反问用户澄清 | 无歧义引导 |
| `retrieve()` | KB 意图 → `collection_name` 定向检索；MCP 意图 → `mcpToolId` 执行工具 | **退化为全局全库扫描** |
| `RAGPromptService.plan()` | 按场景选 MCP_ONLY / KB_ONLY / MIXED 模板 | 走默认 KB 模板 |
| 意图节点 `promptSnippet` / `promptTemplate` | 注入领域知识提示词 | 无领域知识注入 |
| 温度控制 | MCP 场景 0.3 / KB 场景 0 | 走默认 0 |

---

<a id="q7"></a>
## Q7: "DataFlow 引擎"慢链路排查及修复

### 现象

对话 `DataFlow 引擎实现了什么` 总耗时 ~13s，`intent_classify` 返回 `[]`（空），退化为全局检索兜底。

### 根因

`t_intent_node` 表只有 6 个节点（全是 Ragent 内部知识），LLM 对"DataFlow 引擎"打分全部 < 0.35，返回空数组。

### 修复

1. **加兜底意图节点**（改数据不改代码，投入产出比最高）：
   ```sql
   INSERT INTO t_intent_node (id, name, level, kind, collection_name, top_k, ...)
   VALUES (雪花ID, '通用知识', 0, 0, '1', 5, ...);
   ```
   效果：19 chunks(全局) → 5 chunks(定向)，Prompt 缩小，LLM 生成从 9s → 3-4s

2. **PG 向量检索加 `minSimilarity=0.3` 阈值**（`SearchChannelProperties.Global.minSimilarity`）：
   低分 chunk 在 SQL 层直接丢弃，不进入 Rerank，减少交叉编码成本
   ```sql
   WHERE collection_name IN (...) AND 1 - (embedding <=> ?::vector) >= 0.3
   ```

3. **闲聊节点 kind 修复**：`kind=2(MCP)` → `kind=1(SYSTEM)`，清 Redis `ragent:intent:tree` 缓存

---

<a id="q8"></a>
## Q8: RRF 分数在 Rerank 链路中的作用

**RRF 分数是"粗排入场券"**——不是精排依据。

```
Dedup 后 chunks (score=cosine/BM25)
    ↓
FusionPostProcessor: RRF = Σ weight/(k+rank+1) → chunk.setScore(RRF分) → 按 RRF 分排序
    → 截断前 rerankCandidateLimit=50 个送入 Rerank（粗排）
    ↓
RerankPostProcessor: 只取 chunk.getText()，不读 RRF 分 → POST cross-encoder API
    → API 返回自己的 relevance_score → chunk.score = relevance_score（RRF 分被覆盖）
    ↓
最终 chunks (score=cross-encoder 精排分)
```

RRF 分的价值在"选择谁进 Rerank"，不在"Rerank 怎么打分"。

---

<a id="q9"></a>
## Q9: 评测链路怎么做的？

### 设计

**纯检索评测**（不调 LLM），消除生成随机性对评测的干扰。

```
GET /rag/eval?question=xxx
  → QueryRewrite → IntentResolver → RetrievalEngine（多通道 + Dedup + RRF + Rerank）
  → ✂️ 不调 LLM 生成答案
  → 返回 EvalResponse（纯检索证据）
```

### 指标

| 指标 | 比对方式 |
|---|---|
| doc 级召回率 | `retrievedDocIds` vs 评测集 `reference_doc_ids` |
| chunk 级 precision/recall | `retrievedContextDocIds` vs reference（按 index 一一对应） |
| 意图 Top-1 准确率 | `intentLeafIds` vs `intent_l2` |
| 耗时 | `latencyMs` |

### 关键设计

- docId 链：`chunkId → t_knowledge_chunk.docId(雪花) → t_knowledge_document.doc_name → 剥后缀 → 业务码`
- `@ConditionalOnProperty(app.eval.enabled=true)` 生产默认关
- Eval 结果**不影响在线链路**（离线评测，手动调参）

### 评测闭环

| 层 | 说明 | 建议 |
|---|---|---|
| 0（离线归因） | 利用现有 chunk 级归因日志定位问题通道/参数 | 立刻做 |
| 1（参数搜索） | Eval 接口加参数覆盖，离线跑参数组合网格，找最优配置 | 下一步 |
| 2（在线反馈） | 生产检索指标 Prometheus 暴露，自动感知质量波动 | 有价值 |
| 3（自动决策） | 指标驱动自动调参 + 灰度验证 + 热更新 | 长期目标 |

---

<a id="q10"></a>
## Q10: MinerU 在项目中怎么用的？

MinerU 是外部 PDF/Word/PPT 解析 SaaS 服务（API: `https://mineru.net/api/v4`）。项目中定位为"高质量 Markdown 供应商"——MinerU 返回 zip（含 markdown + 图片），Ragent 自己用 commonmark-java 解析 AST → 强类型 Block。

### 调用链路（异步轮询）

```
① MinerUClient.requestUpload()   申请上传链接（batchId + 预签名 URL）
② MinerUClient.uploadFile()      PUT 源文件字节到 MinerU OSS
③ MinerUPollingExecutor.submitAndAwait()  4 线程共享调度池，定时轮询（5s 间隔）
④ MinerUClient.downloadZip()    下载结果 zip
⑤ MinerUResultUnpacker.unpack()  解包为 Block 列表（图片自动上传 RustFS）
⑥ 注入 batchId + zipUrl 到 metadata（用于排障 + 缓存 key）
```

### 配置

| 参数 | 默认值 | 说明 |
|---|---|---|
| `api-url` | `https://mineru.net/api/v4` | |
| `api-key` | `${MINERU_API_KEY}` | 环境变量注入 |
| `timeout-seconds` | 300 | 单任务超时 |
| `poll-interval-seconds` | 5 | 轮询间隔 |
| `concurrency-limit` | 5 | 全局 outstanding 任务上限（分布式信号量 `rag:mineru:parse`） |
| `cache-enabled` | true | RustFS 缓存 zip，同文件重分块跳过 API |

### Parser 路由

`MinerUDocumentParser` 标注 `@Order(HIGHEST_PRECEDENCE)`，`supports(mimeType)` 匹配 pdf/word/ppt（Excel 默认走 POI，用户可在 chunkConfig 中显式选 mineru）。

---

<a id="q11"></a>
## Q11: Workflow 的四问

### 1) 原子性如何体现？

入库持久化的 4 个写操作包进一个事务（`TransactionTemplate.executeWithoutResult`）：

```java
deleteByDocId(docId) → batchCreate(docId, chunks) → deleteDocumentVectors(...) → indexDocumentChunks(...)
```

任一失败全部回滚，保证 chunk + 向量 + 关键词索引三份数据一致。

对话入口的原子性：`@IdempotentSubmit`（Redisson 锁）+ RocketMQ 事务消息。

### 2) 如何搭建？开源还是自研？

**完全自研**（未使用 Camunda/Flowable/Zeebe/LangChain4j）。两套：

| | 对话 Pipeline | 入库 Pipeline |
|---|---|---|
| 类 | `StreamChatPipeline` | `IngestionEngine` |
| 方式 | 代码硬编码 7 步 | 配置驱动（`PipelineDefinition` + `NodeConfig`） |
| 节点 | 私有方法 | `IngestionNode` 接口实现类 |

### 3) 每个节点如何控制？

`IngestionEngine` 5 个控制点：`validatePipeline`（环检测）→ `findStartNode`（找入口）→ `executeChain`（while 循环链式执行，执行数 > 节点数抛死循环）→ `executeNode`（条件检查 + 执行 + 日志 + 错误短路）。

### 4) Eval 评判标准及对链路的影响？

见 Q9。Eval 不影响在线链路（离线评测接口，手动调参），缺少自动闭环。

---

<a id="q12"></a>
## Q12: 多 Agent 框架选择

### 四条路径

| 方案 | 优点 | 缺点 |
|---|---|---|
| **A: LangChain4j** | Java-native，零跨语言开销；Spring Boot 原生；可直接复用 infra-ai | 多 Agent 编排不如 ADK 成熟 |
| **B: Spring AI** | Spring 官方，长期战略对齐 | Agent 能力最弱 |
| **C: Google ADK（跨语言）** | 多 Agent 编排最成熟（transfer_to_agent + PlanReActPlanner） | 跨语言 HTTP 开销；infra-ai 基础设施用不上 |
| **D: 基于 infra-ai 自研** | 完全可控；与现有 Pipeline/断路器/限流无缝衔接 | 开发工作量大 |

**建议**：A + D 混合 —— LangChain4j `@Tool` 快速验证可行性；不满足时基于 infra-ai 自研 ReAct 循环（底座已就绪）。

---

<a id="q13"></a>
## Q13: 实例挂掉后 Workflow 任务如何恢复？

### 当前状态

- **对话 Pipeline**：全在内存，无 checkpoint，实例挂 = 任务全丢
- **入库 Pipeline**：MQ at-least-once 保证重投，但从零重跑，无断点续跑
- **入库事务**：`persistChunksAndVectorsAtomically` 保证了全有或全无（半途挂事务回滚）

### 改造方案

| 层 | 方案 | 成本 |
|---|---|---|
| **对话 Pipeline checkpoint** | `StreamChatContext` 中间态存 Redis（taskId key，5min TTL），实例 B 读 checkpoint 从断点继续 | 低（~100 行） |
| **入库 Pipeline 断点续跑** | `IngestionContext.setLastCompletedNodeId()`，重跑时 skip 已完成节点 | 中 |
| **SSE 跨实例迁移** | 前端重连 + resume-from-checkpoint 或 Redis Pub/Sub 桥接 | 高 |

---

<a id="q14"></a>
## Q14: MCP + Function Calling 结合使用难点与方案

### 8 个难点

| # | 难点 | 业内方案 |
|---|---|---|
| 1 | **Schema 转换**（MCP JSON Schema → OpenAI tools 参数） | 95% 直接透传，薄适配器处理 `$ref` 等边缘字段 |
| 2 | **流式 tool_calls 解析**（跨 chunk 拼装 JSON） | 全缓冲拼装（LagngChain4j）或流式 JSON 解析（Vercel AI SDK） |
| 3 | **ReAct 循环控制**（防死循环 + Token 预算） | `max_llm_calls` 硬上限 + 重复调用检测 + token 累计限制 |
| 4 | **工具执行超时与隔离** | `Future.get(timeout)` + 独立线程池 + 异步回调（长任务） |
| 5 | **工具结果过长 → 上下文爆炸** | 截断 + LLM 摘要 + 分页 |
| 6 | **多工具并行 vs 串行** | OpenAI `parallel_tool_calls` 默认并行 |
| 7 | **多 Provider 格式差异** | LiteLLM/LangChain4j ChatLanguageModel 统一抽象 |
| 8 | **Function Calling 质量验证** | 评测集标注期望 tool_call，离线批量跑对比准确率 |

### 实现优先级

```
① Schema 转换 → ② SSE tool_calls → ③ 循环控制 → ④ 超时隔离 → ⑤ 结果截断 → ⑥ 并行 → ⑦ 多Provider → ⑧ 质量验证
```

**核心难点不在 MCP（执行层已成熟），而在 Function Calling（决策层）的循环控制和流式解析**。

---

<a id="q15"></a>
## Q15: ES IK 分词器安装排查

**错误**：`analyzer [ik_smart] has not been configured in mappings`

**根因**：官方 ES 8.11 镜像不含 IK 中文分词插件，但项目 `KeywordProperties` 默认 `analyzer=ik_max_word`。

**解决**：
1. 下载 IK 插件 zip（`release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.11.0.zip`）
2. `docker cp` 进容器 → `elasticsearch-plugin install --batch file:///tmp/ik.zip`
3. `docker restart ragent-es`

**`startup.sh` 已加 ES 自动检查启动**（与 Redis/PG/RocketMQ/RustFS 同等待遇）。

---

<a id="q16"></a>
## Q16: pgvector 0.6.0 `hnsw.iterative_scan` ERROR 误报

**现象**：日志出现 `ERROR: invalid configuration parameter name "hnsw.iterative_scan"`

**根因**：`PgVectorRetrieverService.applyPgVectorHints()` 尝试设 `SET hnsw.iterative_scan = relaxed_order`（需 pgvector >= 0.8），本地 pgvector 0.6.0 不支持。代码有降级逻辑（`log.debug` 静默跳过），但 PG 抛的异常文本里含 `ERROR:` 字样被 `grep ERROR` 匹配到。

**结论**：误报，不影响检索。升级 pgvector 到 0.8+ 可彻底消除。

---

<a id="q17"></a>
## Q17: 主链路日志改造

在 `StreamChatPipeline`、`RetrievalEngine`、`MultiChannelRetrievalEngine`、`DefaultContextFormatter`、`JdbcConversationMemorySummaryService`、`DefaultConversationMemoryService` 共 6 个文件加了编排视角耗时日志。格式：

```
Stage[1/7] loadMemory finished, historySize=3, elapsed=42ms
Stage[2/7] rewriteQuery finished, rewritten=..., subQuestions=1, hasVariants=false, elapsed=2300ms
Stage[3/7] resolveIntents finished, subIntents=1, intentHits=0, elapsed=2100ms
Stage[6/7] retrieve finished, hasKb=true, hasMcp=false, elapsed=3600ms
Chat pipeline end, scene=rag, elapsed=9187ms
Memory loaded, conversationId=..., hasSummary=true, historySize=8, elapsed=35ms
Summary compress succeeded, summarizedTurns=5, elapsed=410ms
Multi-channel retrieve start, subIntents=1, topK=10, question=...
KB retrieveAndRerank finished, chunks=10, contextChars=2847, elapsed=3400ms
KB context formatted, intentCount=1, chunkCount=10, outputChars=2847, elapsed=2ms
```

每条请求从头到尾的每阶段耗时直接可读，无需日志聚合。

---

<a id="q18"></a>
## Q18: 项目中的重试与限流机制全景

见 Q4 详细展开。核心：**限流以 Redis 分布式实现为主**（自研 ZSet 公平队列 + Lua 原子 claim）、**重试三层**（框架层 OkHttp+SDK / 应用层模型 failover+MinerU 轮询+MQ 重投 / 前端指数退避）、**幂等两套**（@IdempotentSubmit Redisson 锁 + @IdempotentConsume Redis Lua）、**取消/超时体系**（SSE 跨实例取消用 Redis 标记 + RTopic 广播）。

---

<a id="q19"></a>
## Q19: 信号量汇总

| 信号量 Key | 用途 | 上限 |
|---|---|---|
| `rag:global:chat:semaphore` | 全局聊天并发 | 10 |
| `rag:global:chat:queue` | 聊天公平排队 ZSET | 无上限（TTL 防僵尸） |
| `rag:document:upload` | 文件上传 | 10 |
| `rag:mineru:parse` | MinerU 解析 | 5 |

全部使用 `RPermitExpirableSemaphore`（带 lease 自动过期），防持有者崩溃后 permit 永久泄漏。

---

<a id="q20"></a>
## Q20: MQ 在哪里用到？Broker 和半消息是什么？

### MQ 的唯一核心用途：把耗时的切 chunk 操作异步化

```
用户上传文档 → upload() → 文件存 S3 + 写 DB（status=INIT）→ HTTP 立即返回
用户点"开始分块" → startChunk(docId) → 发 MQ 消息 → HTTP 返回
消费者（异步）→ executeChunk → 解析(MinerU 可能 5min) + 分块 + embedding + 入库
```

**为什么用 MQ**：

| 原因 | 说明 |
|---|---|
| 异步化 | PDF 解析（MinerU 5 分钟）+ 分块 + embedding 不能在 HTTP 线程里等 |
| 事务消息 | `sendInTransaction` 保证"status=RUNNING 写成功"和"消息发送成功"原子 |
| 削峰 | chunk 消费串行，不会同时处理太多文件撑爆 MinerU（MinerU 自己有 5 槽限制） |
| 重试 | 消费抛异常 → RocketMQ 重投（当前 `runChunkTask` 吞异常不重抛，靠调度兜底） |

### 完整链路

```
startChunk(docId)  ← 事务消息发送端
    │
    ├─ ① 本地事务: UPDATE document SET status=RUNNING WHERE status!=RUNNING
    ├─ ② 发送半消息（Broker 暂存，不投递）
    └─ ③ Broker 回查 KnowledgeDocumentChunkTransactionChecker.check()
         → SELECT status WHERE id=docId → status==RUNNING ? commit : rollback
    │
    ▼ RocketMQ 投递
KnowledgeDocumentChunkConsumer.onMessage()
    │
    ▼
documentService.executeChunk(docId) → runChunkTask
    │
    ├─ ProcessMode 分叉:
    │   PIPELINE → runPipelineProcess（IngestionEngine DAG 链式执行）
    │   DIRECT（默认）→ runChunkProcess（读文件→MIME→parse→chunk→embed）
    └─ persistChunksAndVectorsAtomically（事务原子写 4 步）
```

### Broker 是什么

**Broker = RocketMQ 的消息存储/转发节点**——所有消息真正落地的节点。docker 里的 `rmqbroker` 容器。

```
生产者 → Broker（存消息、按 topic 落盘、投递给消费者、记录消费进度 offset）→ 消费者
```

职责：接收生产者消息 → 按 topic 存磁盘 → 按消费组推给消费者 → 记录消费进度。消息进了 Broker，生产者和消费者就解耦了。

（`rmqnamesrv` 是注册中心/路由表，`dashboard` 是管理界面，三个容器组成 RocketMQ 集群。真正"存消息"的是 Broker。）

### 半消息（Half Message）是什么

**半消息 = 事务消息的"预备状态"**。消息已发到 Broker，但本地事务未确认，暂不投递给消费者。

```
① 生产者发半消息 → Broker 存起来但不投递
② 生产者执行本地事务（UPDATE status=RUNNING）
③ 成功 → commit（Broker 投递）／ 失败 → rollback（Broker 丢弃）
④ 生产者 ①② 之间挂了 → Broker 回查 DB 判断 commit/rollback
```

**为什么需要半消息**：因为"发消息"和"执行本地事务"不是原子的。半消息让 Broker 先扣住消息，等本地事务结果出来再决定投递还是丢弃。

### MQ 消息丢失怎么办（三个环节）

| 环节 | 丢失风险 | 防护 |
|---|---|---|
| 生产者 → Broker | ✅ 低 | 事务消息 + Broker 回查（`KnowledgeDocumentChunkTransactionChecker`） |
| Broker 存储 | ⚠️ 中 | 生产需配同步刷盘 + 主从同步（本地单节点无） |
| 消费者处理 | ⚠️ **缺口** | `runChunkTask` 吞异常不重抛 → 不触发 RocketMQ 重投，靠 `KnowledgeDocumentScheduleJob` 定时扫描 FAILED 文档重新入队 |

**消费者环节的缺口**：`KnowledgeDocumentChunkConsumer.onMessage()` 调 `executeChunk`，内部 `runChunkTask` 用 try-catch 吞掉异常只标记 FAILED，不重新抛异常。结果 RocketMQ 认为消费成功 ACK 丢弃消息，用户看到"分块失败"但不会自动重试，只能手动重试或等调度兜底。对比 `KnowledgeBaseCleanupConsumer` 是失败就 `throw ServiceException` 触发重投。

### MinerU 5 槽并发限制

`rag:mineru:parse` 分布式信号量，`mineru.concurrency-limit: 5`。

```
KnowledgeDocumentChunkConsumer（MQ，可多实例多线程并发消费）
    ↓ 每个任务都调 MinerU
MinerUDocumentParser.parseStructured()
    ↓
RPermitExpirableSemaphore("rag:mineru:parse").setPermits(5)
    ├─ tryAcquire(30s, 900s) 成功 → 调 MinerU API → finally 释放
    └─ 超时 → 抛 "MinerU 解析任务过多" → MQ 消费失败 → 重投
```

**为什么是 5**：MinerU 是外部 SaaS，并发太多会限流/降级/收费翻倍。用 Redis 信号量把跨实例 + 跨线程的 outstanding 解析任务数钉死在 5。**跨实例的**（Redis 全局），不是单 JVM。lease=900s 防持有者崩溃后 permit 永久占用。

---

<a id="q21"></a>
## Q21: Token 成本怎么算？成本归因怎么做？

### Token 成本 = 计数 + 换算两步

**第一步：计数——从 LLM API 的 usage 字段拿**（唯一准确来源）：

```json
// OpenAI 兼容格式（DeepSeek/百炼/Qwen 都一样）
{
  "usage": {
    "prompt_tokens": 3500,      // 输入 token（system + history + 检索上下文）
    "completion_tokens": 500,   // 输出 token（生成内容）
    "total_tokens": 4000
  }
}
```

**输入输出要分开算**——单价不同（输出通常比输入贵 3-10 倍）：

| 模型 | 输入单价 | 输出单价 |
|---|---|---|
| DeepSeek-V4 | ~$0.14/百万 token | ~$0.28/百万 token |
| GPT-4o | $2.5/百万 | $10/百万 |

**第二步：换算**

```
成本 = prompt_tokens × 输入单价 + completion_tokens × 输出单价

例: 一次 RAG 请求
  prompt_tokens = 3500（system 500 + history 2000 + 上下文 1000）
  completion_tokens = 500
  DeepSeek-V4: 3500/1M×$0.14 + 500/1M×$0.28 ≈ $0.00063 ≈ 0.004 元
```

### ragent 现状：没做 token 计费

代码搜 `usage`/`prompt_tokens`/`total_tokens` 零命中。Trace 表结构印证：

```sql
CREATE TABLE t_rag_trace_node (
    duration_ms    BIGINT,   -- ✅ 有耗时
    extra_data     TEXT,     -- 有扩展字段，但没存 token
    -- ❌ 没有 tokens_in / tokens_out / model 字段
);
```

**文档明确结论**：ragent 有 Trace 但无 token 计费追踪、无预算控制、无租户级别隔离。

### PowerAgent 现状：Langfuse 自动做

Langfuse 的 Generation 自动从 LLM 响应提取 usage：

```
Langfuse 记录每个 Generation:
  ├─ prompt_tokens / completion_tokens / total_tokens
  ├─ cost（自动按模型单价算）
  └─ latency

Dashboard 按 agent/用户/会话/模型维度聚合成本
```

**区别**：Langfuse 开箱即用（集成 SDK 自动抓 usage + 算成本 + UI 看）；ragent 自研 Trace 只记耗时状态，漏了 token 维度。

### 成本归因：一次请求有多个 LLM 调用点

```
一次 RAG 请求的 token 消耗（4 个调用点）:

① rewriteQuery     LLM 改写      prompt 300 + output 50   = 350
② resolveIntents   LLM 意图打分  prompt 800 + output 30   = 830
③ MCP 参数提取      LLM 提取参数  prompt 200 + output 40   = 240
④ streamRagResponse LLM 最终回答  prompt 3500 + output 500 = 4000
─────────────────────────────────────────────────────────────
总计: 5420 tokens
```

**成本归因的意义**：如果意图分类(②)占 830 tokens 但经常打空（返回[]），就是纯浪费 → 优化方向：意图树精简 or 用轻量模型做意图分类。

### ragent 要补的设计（TokenBudget 模型）

```
① Token 计数: 从 LLM 响应 usage 提取
   → 存 t_rag_trace_node.extra_data（JSON 序列化）
   → {"tokens_in": 3500, "tokens_out": 500, "model": "deepseek-v4"}

② Token 预算:
   TokenBudget = SystemPromptTokens + HistoryTokens + ContextTokens + OutputTokens
   每个用户/租户独立预算
   remaining = budget - token_counter
   remaining < 0 → 降级(减 context/裁 history) 或拒绝

③ 成本归因:
   per query 记录每个 LLM 调用的 token_usage
   → 每个调用点(改写/意图/检索/回答)精确追踪
   → 才能定位"哪个环节最烧钱"
```

### 面试话术

> Token 成本 = 计数 + 换算两步。计数靠 LLM API 返回的 `usage` 字段（prompt_tokens 和 completion_tokens 分开，单价不同）；换算就是 token × 模型单价。
>
> 关键是成本归因——一次 Agent 请求有多个 LLM 调用点（改写、意图、参数提取、回答），必须把每个调用点的 token 精确记录到 Trace，才能定位哪个环节最烧钱。比如意图分类经常打空（返回空数组），830 个 token 白花，就换成轻量模型专门做意图分类。
>
> 我们项目里 PowerAgent 用 Langfuse 自动抓 usage 算成本，ragent 自研 Trace 只记了耗时没记 token，这是要补的能力。

### 相关延伸

- **Prompt Cache**：system prompt 不变部分用 KV cache 复用，省首 token 延迟和 token 计费。RAG 场景不能做语义缓存（知识库更新答案会变），只能缓存检索结果。
- **取消推理**：用户关浏览器 ≠ 省成本，AI provider 推理进程继续扣费。ragent 通过 `StreamCancellationHandle` 从 provider 端停掉推理。

---

<a id="q22"></a>
## Q22: Langfuse 是用来干什么的？PA 用 Langfuse 干了什么？

### Langfuse 是什么

**开源的 LLM 可观测性（Observability）平台**——专门追踪、监控、评估大模型应用的"黑盒"。

普通 APM（Pinpoint/SkyWalking）只能看到"哪个接口慢"，看不到"LLM 内部发生了什么"。Langfuse 补的就是这一层：

```
普通 APM:  POST /api/auto-agent/run 耗时 5.2s  ← 只知道慢，不知道为啥慢
Langfuse:  这个请求里
            ├─ 调了 3 次 LLM（意图分类 1 次 + 子 Agent 2 次）
            ├─ 每次的 prompt 是什么、返回什么
            ├─ 每次的 token 消耗、延迟、成本
            └─ 哪次调用最慢、哪次重试了
```

### 三层数据模型

```
Trace（一次请求）→ Span（一个处理阶段）→ Generation（一次 LLM 调用）
```

| 层级 | PA 里的映射 |
|---|---|
| Trace | 一次请求，trace_id = requestId.hex |
| Span | 处理阶段，span 名 = agentMeta.name |
| Generation | 每次 LLM 调用（意图分类、子 Agent 推理、工具调用） |

### PA 用 Langfuse 干了 4 件事（4 层埋点）

#### 第 1 层：中间件——开启最外层 Trace

`trace_middleware` 读请求的 reqId，开最外层 span：

```python
with langfuse.start_as_current_span(
    name="ai-insight-agent",            # ★ 最外层 span 名
    trace_context={"trace_id": trace_id} # trace_id 用 reqId
) as span:
    set_trace_id(trace_id=trace_id)
    session_id = data.get("session_id")
    if session_id:
        span.update_trace(session_id=session_id)  # 关联会话
    response = await call_next(request)
    response.headers["X-Trace-Id"] = trace_id     # 写响应头
```

**干什么**：reqId 作 trace_id（和业务日志/Pinpoint 对齐）、开最外层 span、关联 session_id、写 X-Trace-Id 响应头。

#### 第 2 层：执行器——嵌套 Agent span

`AutoAgentExecutor.execute()` 开内层 span，span 名 = agent 名：

```python
trace_context = TraceContext(trace_id=self.data.requestId.hex)
with langfuse_instance.start_as_current_span(
    name=self.data.agentMeta.name,    # ★ span 名 = agent 名
    trace_context=trace_context
):
    async for event in self._execute_impl():
        yield event
```

**干什么**：在 `ai-insight-agent` span 里嵌套 agent 名 span，形成嵌套链路：

```
Trace: requestId
 └─ Span "ai-insight-agent"      ← 中间件（整次请求）
     └─ Span "strategy_agent"    ← 执行器（agent 执行）
         ├─ Generation: 意图分类 LLM 调用
         ├─ Generation: PlanReAct 每轮 LLM 调用
         └─ Generation: 最终回答
```

#### 第 3 层：工具——@fill_langfuse 装饰器

`CommonTool.run_async()` 用 `@fill_langfuse` 追踪工具调用：

```python
@override
@fill_langfuse          # ★ 追踪工具调用
async def run_async(self, *, args, tool_context) -> Any:
    url = f"{AGENT_FLOW_SERVER_URL}/api/v1/tools/run"
```

**干什么**：追踪 Agent 里每个工具调用（KnowledgeTool 检索、MCP、plugin），记录到当前 span 下：

```
Span "strategy_agent"
 ├─ Generation: LLM 决定调用 data_pull
 ├─ Tool: data_pull 执行（耗时、输入输出）  ← fill_langfuse
 ├─ Generation: LLM 推理工具结果
 └─ Generation: 最终回答
```

#### 第 4 层：重排——LlamaIndex 事件上报

`RagBaseRerank` 通过 LlamaIndex callback_manager 上报 RERANKING 事件 → Langfuse 追踪。

### 三层监控分工

```
┌─ Langfuse ──────────────────────────────────┐
│ LLM 层可观测性                               │
│ 追踪 prompt/token/延迟/成本/输出质量          │
│ 回答 "这次 LLM 调用花了多少钱、说了什么"      │
└─────────────────────────────────────────────┘
┌─ Pinpoint ──────────────────────────────────┐
│ 应用层全链路 APM                             │
│ 追踪跨服务调用链/数据库/缓存/MQ               │
│ 回答 "请求经过哪些服务、卡在哪个环节"          │
└─────────────────────────────────────────────┘
┌─ Prometheus ────────────────────────────────┐
│ 指标监控                                     │
│ 追踪 QPS/错误率/CPU/内存/连接数               │
│ 回答 "系统整体健康吗、要不要扩容"              │
└─────────────────────────────────────────────┘
```

### 关键设计：trace_id = reqId

这让 Langfuse、业务日志、Pinpoint 三套系统用同一个 ID 串联，排查问题时能从一个 ID 在三套系统里互相跳转。

### 对应 ragent 的替代

| | Langfuse | ragent 自研 Trace |
|---|---|---|
| LLM 层追踪（prompt/token/成本） | ✅ 强 | ❌ 弱（只记耗时状态） |
| 应用层调用树 | ✅ 有 | ✅ 有（落库） |
| 可视化 UI | ✅ 开箱即用 | ❌ 靠查表 |
| 评估/标注 | ✅ 有 | ❌ 无 |

ragent 用 `@RagTraceNode` AOP + TTL 透传 + `t_rag_trace_run`/`t_rag_trace_node` 表落库调用树，记录 TTFT、各阶段耗时、LLM 路由，但缺 prompt/token/成本维度。

### 面试话术

> Langfuse 是 LLM 调用的 APM——专门追踪每次大模型调用的 prompt、token、延迟、成本、输出。PA 用它在 4 层埋点：HTTP 中间件开最外层 span（reqId 作 trace_id）、执行器开 agent 名 span、工具层用 @fill_langfuse 装饰器追踪工具调用、重排通过 LlamaIndex 事件上报。
>
> 核心价值是把一次 Agent 请求的完整内部过程可视化——从 HTTP 入口到 Agent 推理到每次 LLM 调用到每次工具执行。关键设计是 trace_id = reqId，让 Langfuse、业务日志、Pinpoint 三套系统统一 ID 串联。

---

<a id="q23"></a>
## Q23: Agent 框架用的什么？Skill 中间状态怎么存？

### Agent 框架：Google ADK（主力）+ LangGraph（降级）

PowerAgent 用**双框架**，通过 `framework` 字段切换：

```
AutoAgentExecutor
  ├── framework="adk"        → ADKExecutor（Google ADK）—— 默认、主推
  └── framework="langgraph"  → LangGraphExecutor（LangChain）—— 兼容/降级
```

**为什么 ADK 主力**：
1. 原生支持层次化多 Agent（`sub_agents` + `transfer_to_agent`）
2. PlanReAct 开箱即用（`PlanReActPlanner`）
3. Session/Memory 内置抽象（替换为 Redis 实现只需实现接口）
4. Event 模型天然适配 SSE 流式

**ragent 对比**：没有 Agent 框架，是确定性 Pipeline + 意图树路由，不走 LLM 自主循环。

### Skill 中间状态的四层存储

PowerAgent 的 skill = 子 Agent，中间状态分 4 层存储：

| 层 | 内容 | 存储介质 | 生命周期 | 是否有断点 |
|---|---|---|---|---|
| Session | 对话历史 | Redis（`InRedisSessionService`） | 会话级 | ❌ 无 checkpoint |
| State 字典 | 跨 Agent 关键数据 | 内存（`state_delta` 透传） | 请求级 | ❌ 进程挂了丢 |
| Memory | 长期记忆 | Redis + ES（mem0） | 跨会话 | ✅ 持久化 |
| FlowContext | Workflow 中间态 | JVM 内存 | 请求级 | ❌ **最大缺陷** |

#### 第 1 层：对话历史 → Session（Redis）

```python
session_service = InRedisSessionService()  # Redis 存储
```

每次请求根据 `chat_id` 恢复/创建会话，存 Event 列表（对话历史）。

#### 第 2 层：State 字典 → state_delta（内存透传）

```python
async for event in runner.run_async(
    state_delta={
        "request_params": {...},   # 请求参数
        "metadata": {...},         # 元数据
    }
)
# 子 Agent 通过 callback 读
request_params = callback_context.state.get("request_params", {})
```

**存哪里**：内存（ADK 的 State 是进程内字典，随 run_async 透传），不是 Redis 不是 DB。

#### 第 3 层：长期记忆 → Memory（Redis）+ mem0（ES 向量）

```python
memory_service = InRedisMemoryService()  # Redis 存储
```

`longTermMemory` 拼到 Prompt 的 `【长期记忆】` 段，深层用 mem0（ES 向量）做个性化记忆。

#### 第 4 层：Workflow 引擎中间态 → FlowContext（JVM 内存，⚠️ 无持久化）

AgentFlow（工作流编排引擎）和 Agent 推理层是两套东西：

```java
// WorkFlowEngine 的 FlowContext
// 整个 Workflow 在一个 JVM 进程跑完
// FlowContext 是内存对象，进程崩溃 → 中间状态全丢
```

文档明确写的缺陷（Q33 问题一）：

> 整个 Workflow 在一个 JVM 进程中跑，FlowContext 是内存对象。进程崩溃 → 所有中间状态丢失。现状：单节点有超时 + 重试 3 次，但 Workflow 级别没有 checkpoint。

**改进方向**：非实时场景加 checkpoint——每执行完一个节点把 `FlowContext` 序列化到 Redis。

### 核心结论

1. **Agent 推理层（ADK）的中间状态**：对话历史存 Redis（Session），但**没有 checkpoint**——Agent 循环到第 5 轮实例挂了，前面 4 轮没持久化，只能重来。

2. **Workflow 引擎层中间状态**：`FlowContext` 纯 JVM 内存，进程崩溃全丢，是文档承认的最大缺陷（对应"实例挂了 Workflow 任务如何恢复"，现状是"没有断点续跑"）。

3. **真正持久化的只有长期记忆**（Memory/mem0）——跨会话，必须落盘。

这呼应"断点重续"话题：PowerAgent 的 skill 中间状态大多不持久化，实例挂了 Agent 任务会丢。真正做断点续跑，需要给 Session 和 FlowContext 加 checkpoint。

---

<a id="q24"></a>
## Q24: 短期记忆（Redis Session）的数据结构、过期与续期

### 短期记忆 = ADK Session 的 Event 列表

PowerAgent 的短期记忆就是**对话历史**，存 ADK 的 `Session`，核心是一个 **Event 列表**：

```python
event = Event(
    invocation_id=self.request_id,    # 哪次请求产生的
    author="user" / "strategy_agent", # 谁说的
    id=uuid.uuid4().hex,             # 唯一 ID
    content=types.Content(
        parts=[types.Part.from_text(text=item.value)],
        role="user" / "model",
    ),
)
```

**Redis 存储**：

```
Key:  app_name:user_id:session_id
  app_name   = "auto_agent_{tenantid}"   ← 按租户隔离
  user_id    = user.userid               ← 按用户隔离
  session_id = chat_id                   ← 按会话隔离

Value: 整个 Session 序列化成 JSON（含 events 列表 + token_count）
{
  "events": [
    {
      "invocationId": "req_20260817_001",
      "author": "user",
      "id": "e1a2b3c4",
      "content": {
        "parts": [{"text": "帮我分析这份销售数据"}],
        "role": "user"
      },
      "timestamp": 1723881600000
    }
  ],
  "token_count": 156,
  "version": 1,          // 乐观锁版本号（可选，用于极端并发校验）
  "last_active": 1723881600000  // 最后活跃时间（用于监控冷热）
}

```

### 应该用什么数据结构（选型分析）

**核心洞察**：短期记忆的操作粒度是"**整个会话**"（整段读、尾部追加、整体截断），不是单条 Event。

| 结构 | 追加 | 全量读 | 截断 | 并发安全 | 结论 |
|---|---|---|---|---|---|
| **String + JSON** | GET+SET（读改写） | GET 一次拿全 | 反序列化重写 | ❌ 有竞态 | ✅ 推荐 |
| List | LPUSH/RPUSH | LRANGE 0 -1 | LTRIM | ✅ | ⚠️ 语义不匹配 |
| Hash | HSET | HGETALL | HDEL | ✅ | ❌ 无序不适合有序事件 |
| Stream | XADD | XRANGE | XTRIM | ✅ | ❌ 消息流不是状态 |
| RedisJSON | JSON 路径 | JSON.GET | JSON 截断 | ✅ | 超大会话才考虑 |

**推荐 String + JSON**：
1. 操作粒度匹配（整段读 → GET 一次拿完）
2. 最接近 ADK 的内存 Session 语义（events 列表对象 → JSON 序列化）
3. 简单可靠，一个 key 一个值

### 过期时间设置

**没有统一答案，取决于会话类型**：

```
短期会话（问答型）: 30 分钟 ~ 2 小时
工作会话（数据分析）: 1 天 ~ 3 天
长期会话（个性化助手）: 7 天 ~ 30 天
```

Google ADK 默认 Session TTL 是 **7 天**，生产一般按场景覆盖。

```python
SESSION_TTL_BY_TYPE = {
    "chat": 2 * 3600,        # 普通问答 2 小时
    "analysis": 3 * 86400,   # 数据分析 3 天
    "assistant": 30 * 86400  # 长期助手 30 天
}
```

### 续期机制

**每次活跃请求刷新 TTL**（活跃就不丢，离开自动清）：

```python
async def _init_session(self):
    session = await session_service.get_session(**kwargs)
    if session:
        # ★ 续期：重置 TTL，会话活跃就永不超时
        await session_service.refresh_session_ttl(session, ttl=SESSION_TTL_SECONDS)
```

**为什么必须续期**：

```
不续期: 第 1 天聊 10 轮，TTL 7 天，第 8 天回来 → Session 过期失忆
续期后: 每次活跃刷新 TTL，只要持续活跃永不丢失，离开 7 天自动清理
```

**优化**：不是每次都续，快到一半才续（省 Redis 调用）：

```python
def maybe_refresh_ttl(session):
    remaining = redis.ttl(session_key)
    if remaining < SESSION_TTL_SECONDS // 2:
        redis.expire(session_key, SESSION_TTL_SECONDS)
```

### 上下文压缩后怎么更新缓存

**所有写回必须原子**，否则并发请求互相覆盖导致会话不一致。

**两种截断策略**：

```
策略 A: 临时截断（不更新 Redis）—— PA 的轮次级截断
  Session 在 Redis 里是完整的，每次 LLM 调用前内存截断
  → Redis 不改，天然一致 ✅

策略 B: 永久删除（更新 Redis）—— PA 的 30K 会话级截断
  reset_session_events 真正删除旧事件
  → 必须原子替换（Lua），否则并发读不一致
```

**策略 B 的 Lua 原子替换**：

```lua
-- reset_session_events 原子写回
local s = redis.call('GET', KEYS[1])
local obj = cjson.decode(s)
obj.events = ARGV[1]       -- 已过滤的事件
obj.token_count = ARGV[2]  -- 重算的 token
redis.call('SET', KEYS[1], cjson.encode(obj), 'EX', ARGV[3])
```

**如果是压缩（非截断）**，更新为"摘要 + 最近 N 轮原文"整体原子写回：

```python
def compress_session(session_id, keep_rounds, summary_max_chars):
    session = get_session(session_id)
    recent = session.events[-keep_rounds:]    # 保留最近 N 轮原文
    old = session.events[:-keep_rounds]        # 旧事件
    summary = llm_summarize(old)               # LLM 压缩

    new_events = [summary_event(summary)] + recent  # 摘要 + 最近原文
    session.events = new_events
    session.token_count = sum_token(new_events)
    lua_atomic_set(session_id, session)        # ★ 原子写回
```

### 工程 Checklist

| 项 | 建议 |
|---|---|
| 过期时间 | 会话型 7 天，按场景可调（问答 2h / 分析 3d / 助手 30d） |
| 续期机制 | 每次活跃请求刷新 TTL（或快到一半才续） |
| 截断同步 | 临时截断不写 Redis；永久截断用 Lua 原子替换 |
| token 统计 | Session 维护 token_count 字段，截断后重算 |
| 一致性 | 所有写回 Lua 原子化，防并发覆盖 |

### 面试话术

> 短期记忆存 Redis，本质是一个 Session 对象 = 一个 Event 列表，按 app_name + user_id + session_id 定位 key，整个 Session 序列化成 JSON。选 String + JSON 是因为操作粒度是"整段读、尾部追加、整体截断"，粒度匹配 GET/SET，最接近 ADK 的内存 Session 语义。
>
> 过期时间按场景设——问答 2 小时、数据分析 3 天、长期助手 30 天（ADK 默认 7 天）。续期是每次活跃请求刷新 TTL，活跃就不丢，离开自动清。
>
> 上下文压缩后关键是"写回必须原子"——临时截断（内存）不写 Redis 天然一致，永久截断（30K 级）用 Lua 原子替换防并发覆盖，如果是摘要压缩则"摘要 + 最近 N 轮原文"整体原子写回。核心原则是所有对 Redis 的写回都必须是原子的，否则并发请求会互相覆盖导致会话状态不一致。
