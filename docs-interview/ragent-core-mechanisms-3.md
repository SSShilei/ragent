# Ragent 核心机制深度解析（第三篇）

> 覆盖第二梯队（Trace/调度/MCP/评测）和第三梯队（降级/Pipeline/飞书/权限）。

---

<a id="rag-trace"></a>
## 一、全链路追踪（RagTrace）

### 1.1 设计：TTL 透传 + AOP 无侵入

`RagTraceContext`（`RagTraceContext.java:29`）用 `TransmittableThreadLocal` 保证异步线程池中 context 透传：

```java
private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>() {
    @Override
    public Deque<String> copy(Deque<String> parentValue) {
        return parentValue == null ? null : new ArrayDeque<>(parentValue); // 深拷贝
    }
};
```

**深拷贝是必要的**：父线程的 NODE_STACK 传给子线程后如果共享同一个 Deque，并发子任务互相 push/pop 会导致父节点 ID 串挂，trace 树层级紊乱。

### 1.2 AOP 采集

`RagTraceAspect`（`RagTraceAspect.java:57`）：`@Around("@annotation(traceNode)")`拦截所有标注 `@RagTraceNode` 的方法。

```java
// 注解示例
@RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
public RewriteResult rewriteWithSplit(...) { ... }

@RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
public RetrievalContext retrieve(...) { ... }

@RagTraceNode(name = "llm-first-packet", type = "LLM_TTFT")
public ProbeResult awaitFirstPacket(...) { ... }
```

AOP 写入逻辑：

```java
// 1. 起一 RUNNING node，记录 traceId / parentNodeId / depth / className / methodName
traceRecordService.startNode(...);
RagTraceContext.pushNode(nodeId);

// 2. 执行业务方法
Object result = joinPoint.proceed();

// 3. 完成 SUCCESS node，记录耗时 + endTime
traceRecordService.finishNode(traceId, nodeId, STATUS_SUCCESS, null, endTime, latencyMs);

// 4. 异常时记录 ERROR + 截断错误信息（maxErrorLength=1000）
catch (Throwable ex) {
    traceRecordService.finishNode(traceId, nodeId, STATUS_ERROR, truncateError(ex), ...);
    throw ex;
} finally {
    RagTraceContext.popNode();
}
```

### 1.3 数据库表

`t_rag_trace_run`：一次对话一次 trace，记录 trace_id、task_id、用户问题。
`t_rag_trace_node`：trace 下的每个步骤节点，记录 node_type（REWRITE/RETRIEVE/LLM_ROUTING/LLM_TTFT）、duration_ms、status、parent_node_id。

通过 parent_node_id + depth 还原调用树：

```
trace
  ├─ query-rewrite-and-split (REWRITE, 200ms)
  ├─ intent-classify (CLASSIFY, 3s)
  ├─ retrieval-engine (RETRIEVE, 800ms)
  │   ├─ VectorSearch (500ms)
  │   └─ KeywordSearch (300ms)
  ├─ llm-stream-routing (LLM_ROUTING, 5s)
  │   └─ llm-first-packet (LLM_TTFT, 800ms)
  ...
```

### 1.4 追踪范围

所有 Pipeline 关键方法都有 `@RagTraceNode`，包括：
- `rewriteQuery` / `resolveIntents` / `retrieve` / `streamLLMResponse`
- `llm-chat-routing` / `llm-stream-routing` / `llm-first-packet`
- `embedding` / `rerank` 调用

开关在 `rag.trace.enabled: true`。

### 1.5 面试话术

> 全链路追踪基于 AOP + TransmittableThreadLocal 实现。用 `@RagTraceNode` 注解标记流水线每个关键步骤，AOP 切面在方法前后记录 startNode/finishNode，通过 TTL 的 node stack 在异步线程间透传 traceId 和父子节点关系。深拷贝 node stack 是因为线程池复用场景下共享引用会导致并发 push/pop 串挂。数据落两张表——t_rag_trace_run 记录每次对话、t_rag_trace_node 记录每个步骤的耗时和状态。排查慢请求时按 traceId JOIN 两表还原调用树，能精确到每个步骤的毫秒耗时。

---

<a id="distributed-schedule"></a>
## 二、分布式调度（文档自动同步）

### 2.1 整体流程

```
① 扫描阶段
   ScheduleRefreshProcessor 扫描 schedule 表
   取 enabled=1 且到达执行时间的 URL 类型文档
   DB 乐观锁竞争：UPDATE ... SET lock_owner=X, lock_until=now+900s WHERE lock_owner IS NULL

② 变更检测（三级）
   RemoteFileFetcher.fetchIfChanged():
     Level 1: ETag 匹配  → HTTP HEAD 取 ETag，与上次记录对比 → 相同则跳过
     Level 2: Last-Modified 匹配 → ETag 不可用时降级为时间戳对比
     Level 3: SHA-256 匹配   → 上面前两级都不可靠时下载文件算 SHA-256

③ 原子切换
   变更确认后：
   下载新文件 → 上传到 S3/OSS → 更新 document 的 fileUrl
   → 触发重新分块（走 RocketMQ 异步）
   → 更新 schedule 的 etag/lastModified/contentHash 快照

④ 锁续期
   执行期间每 30s 调一次心跳续期（lock_until 延长）
   任务结束显式释放锁
   锁丢失时标记文档 FAILED、停止执行（防双写）
```

### 2.2 三级变更检测

`RemoteFileFetcher.fetchIfChanged()`（`RemoteFileFetcher.java:79`）：

```java
// Level 1: ETag（最快，一次 HEAD 请求）
String etag = trimOrNull(headResponse.etag());
boolean etagMatch = hasText(etag) && etag.equals(lastEtag);
if (etagMatch) return RemoteFetchResult.skipped("远程文件未变化", ...);

// 下载文件时同步算 SHA-256

// Level 2: Last-Modified（HEAD 也有，但可靠性低于 ETag）
boolean modifiedMatch = hasText(headLastModified) && headLastModified.equals(lastModified);

// Level 3: SHA-256（最可靠，但需要下载整个文件）
if (hasText(hash) && hash.equals(lastContentHash))
    return RemoteFetchResult.skipped("内容哈希未变化", ...);
```

**为什么需要三级**：ETag 最快但部分 CDN 不返回；Last-Modified 次快但部分服务器返回格式不标准；SHA-256 最可靠但需下载全文件——三级递进，只在低级不可靠时才升温。

### 2.3 DB 乐观锁防竞争

多实例并发扫描到同一个 schedule 时，用 `UPDATE ... WHERE lock_owner IS NULL` 做 CAS，只让一个实例抢到锁。

### 2.4 安全防护

```yaml
knowledge:
  schedule:
    batch-size: 20           # 每批最多处理数
    min-interval-seconds: 60 # 最小间隔
    lock-seconds: 900        # 锁超时
```

- 心跳续期防长任务锁过期
- 锁丢失自动停止执行
- 旧文件幂等清理（用完后删临时文件）

### 2.5 面试话术

> 我们为 URL 类型的知识源做了自动同步——用 DB 乐观锁做多实例竞争，一次只让一个实例拿到执行权。变更检测有三层：ETag → Last-Modified → SHA-256。这么做是因为 ETag 最快但不是所有 CDN 都返回，Last-Modified 次之但格式差异大，SHA-256 最可靠但要下载全文件——三级递进，只在低级不可靠时才升温。执行期间有心跳续期防长任务锁过期，锁丢失自动停止执行避免双写冲突。切换到新文件后通过 RocketMQ 异步触发重新分块入库。

---

## 三、MCP 工具调用

### 3.1 架构

```
意图识别 → kind=MCP 的叶子节点 → RetrievalEngine
    ├─ McpToolRegistry.getExecutor(toolId)     ← 拿工具定义
    ├─ McpParameterExtractor.extractParameters  ← LLM 从 query 提取参数
    └─ McpToolExecutor.execute(params)          ← 调 MCP Server
```

### 3.2 Tool 自动发现

`McpClientAutoConfiguration` 通过 MCP SDK 连接 `http://localhost:9099` 的 MCP Server，Ragent 把返回的 tool list 注册到 `McpToolRegistry`。

```yaml
mcp:
  servers:
    - name: default
      url: http://localhost:9099
```

意图树里的 MCP 节点通过 `mcpToolId` 关联到具体 tool。

### 3.3 参数提取——LLM 驱动的动态提取

`McpParameterExtractor.extractParameters(userQuestion, tool, customPromptTemplate)`：

```java
// 用 LLM 从用户自然语言中提取出 tool 需要的参数
// 输入："北京今天天气怎么样"
// tool 定义：{name:"weather", params:[{name:"city", type:"string"}]}
// 输出：{"city": "北京"}
```

支持**意图节点级别的自定义 Prompt**（`paramPromptTemplate`），不同 MCP 意图可以有不同的参数提取策略。

### 3.4 并行执行

`RetrievalEngine.executeMcpTools()`：多个 MCP 工具并行调用（`mcpBatchExecutor` 线程池），结果按 toolId 分组后格式化进 LLM Prompt。

### 3.5 面试话术

> MCP（Model Context Protocol）是一个标准化的工具调用协议。Ragent 通过 MCP SDK 连接 MCP Server 实现 tool 自动发现——Server 启动后推送自己的 tool 列表，Ragent 注册到 registry。意图树里配好 MCP 节点指定 toolId，query 进来后意图识别命中 MCP 节点，LLM 从自然语言中提取参数后调 MCP 工具。多个 MCP 工具并行执行，结果格式化后拼进 LLM Prompt。这套机制让意图树从"纯检索路由"升级为"检索 + 工具调用"的双路由。

---

## 四、评测体系

### 4.1 EvalController（纯检索评测）

`EvalController`（`EvalController.java:55`）：只评测检索质量，不调 LLM 答题。

```
GET /rag/eval?question=Block 体系是什么

返回 EvalResponse:
  retrievedDocIds       → 命中的文档 ID 列表
  retrievedContexts     → 命中的 chunk 文本列表
  retrievedContextDocIds→ chunk 维度的 docId（评测脚本按 index 计算 precision/recall）
  subIntents            → 改写拆分后的子问题
  intentLeafIds         → top-1 意图叶子节点（用于计算 Top-1 准确率）
  latencyMs             → 总耗时
```

评测脚本据此与标注集比对：`reference_doc_ids` vs `retrievedDocIds` 算 doc 级召回率。

### 4.2 意图准确率评测

`intentLeafIds` 字段直接对齐评估集的 `intent_l2`——每次请求知道 LLM 把问题分到了哪个意图叶子节点，批量跑后用 Top-1 准确率评估意图识别质量。

### 4.3 设计决策

- 不调 LLM 答题（省成本、消除 LLM 随机性对评测的干扰）
- 通过 `docName` → 剥后缀 → 业务码 对齐评测集的 reference_doc_ids
- chunk 级别和 doc 级别双重评测粒度

### 4.4 面试话术

> 我们的评测接口 `/rag/eval` 只跑检索管线——改写 → 意图识别 → 多通道检索 → RRF → Rerank，但不调 LLM 答题。这样消除了 LLM 随机性对评测的干扰，纯粹评估检索和意图识别的质量。返回结果包括 chunk 级别的 retrievedContextDocIds（评测脚本算 context_precision/context_recall）和 intentLeafIds（Top-1 准确率）。评测集可以完全离线批量跑，不用人工标注每轮答案。

---

<a id="degradation-strategy"></a>
## 五、降级策略（降级矩阵）

Ragent 在多处有降级保护：

### 5.1 LLM 调用降级（模型级别）

```java
// RoutingLLMService.streamChat()
for (ModelTarget target : targets) {
    if (!healthStore.allowCall(target.id())) continue;  // 跳过不健康模型
    try {
        handle = client.streamChat(request, bridge, target);
    } catch (Exception e) {
        healthStore.markFailure(target.id());
        continue;  // 失败 → 切下一个
    }
    ProbeResult result = awaitFirstPacket(bridge, handle, callback);  // 60s 首包超时
    if (result.isSuccess()) {
        healthStore.markSuccess(target.id());
        return handle;  // 成功 → 不再试
    }
    handle.cancel();  // 首包超时 → 切下一个
}
callback.onError(new RemoteException("大模型调用失败，请稍后再试..."));  // 全部失败
```

### 5.2 Query 重写降级

```java
// MultiQuestionRewriteService
RewriteResult fromLLM;
try {
    fromLLM = callLLMRewriteAndSplit(...);
} catch (Exception e) {
    // LLM 调用失败 → 仅术语归一化 + 规则拆分 兜底
    return new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
}
if (parsed == null) {
    // LLM 返回非 JSON → 同上兜底
    return new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
}
```

### 5.3 Embedding 降级

embedding 模型选型中有 3 个 candidate，按 priority 排队——默认 `qwen-emb-4b(gitee)` → fallback `qwen-emb-local(ollama)`。

### 5.4 Rerank 开关

```yaml
rerank:
  enabled: true    # false 时 RerankPostProcessor 直接跳过，不影响检索结果
```

### 5.5 关键词同步降级

```java
// KeywordSyncingVectorStoreService.syncKeyword()
try { action.run(); }
catch (Exception e) { log.warn("关键词索引同步失败，已跳过", e); }
// 关键词写入是 best-effort，绝不阻塞向量主链路
```

### 5.6 面试话术

> 我们在四个关键点有降级：① LLM 答题多模型 fallback（首包 60s 超时 + 健康标记）；② Query 重写 LLM 调用失败自动降级到术语归一化 + 规则拆分；③ Embedding 模型 priority 链 fallback；④ 关键词索引同步是 best-effort，失败只打日志不阻塞向量主链路。整体设计原则是"每一层的失败都不会导致整个对话失败"。

---

<a id="pipeline-vs-chunk"></a>
## 六、Pipeline 数据通道 vs 直接分块

之前问过这两个处理模式的区别，这里展开：

### 6.1 两套流程

| | 直接分块 (CHUNK) | 数据通道 (PIPELINE) |
|:---|:---|:---|
| 链路 | Parser → Chunk → Embed 三步固定 | IngestionEngine DAG 编排 |
| 可配置度 | 切分策略 + 参数 | 自定义节点链 |
| 复杂度 | 低 | 高（需建 Pipeline 定义） |
| 代码入口 | `runChunkProcess()` | `runPipelineProcess()` |

### 6.2 Pipeline 引擎

`IngestionEngine` 根据 `PipelineDefinition` 执行 DAG 节点链。内置节点类型：
- `ParserNode`：解析（按 MIME 路由）
- `ChunkerNode`：分块
- `IndexerNode`：向量化 + 写入
- 可通过 SPI 扩展自定义节点

### 6.3 面试话术

> 日常上传用直接分块——三步固定链路、零配置开箱即用。Pipeline 是企业级复杂流程——需要先在管理后台画 DAG 节点拖拽编排，定义好之后文档按 DAG 顺序执行各节点，可以插入自定义的清洗、格式转换等节点。面试时问到"处理复杂文档流程"的场景可以讲这个双模式设计。

---

## 七、飞书文档接入

### 7.1 FeishuFetcher

`FeishuFetcher`（`FeishuFetcher.java:47`）实现 `DocumentFetcher`，支持 `SourceType.FEISHU`：

```java
public SourceType supportedType() { return SourceType.FEISHU; }
```

### 7.2 两种模式

**Token 认证**：
```java
// 1. 直接用 accessToken
String token = credentials.get("tenantAccessToken");

// 2. 或用 app_id + app_secret 自动换 token
POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal/
```

**Docx 类型（在线文档）**：调 `/open-apis/docx/v1/documents/{docToken}/raw_content` 拿纯文本

**二进制文件（附件）**：直接 GET 文件 URL，MIME 探测

### 7.3 面试话术

> 支持飞书文档是通过 FeishuFetcher 实现的——用户粘贴飞书链接后，输入凭据里的 tenantAccessToken，系统调飞书 Open API 拿到文档原始内容，再像本地文件一样走 Parser → Chunk → Embed 流程。

---

## 八、权限 & 多租户

### 8.1 Sa-token 配置

```yaml
sa-token:
  token-name: Authorization        # 前端传 Authorization header
  timeout: 2592000                 # 30 天
  is-concurrent: true              # 同账号允许多端登录
  token-style: simple-uuid         # UUID token
```

### 8.2 多租户隔离

Ragent **不是 SaaS 多租户**——每个部署实例独立部署，知识库按 `kb_id` 隔离：

```
t_user               → 用户表
t_knowledge_base     → kb_id 隔离知识库（每库独立 collection）
t_knowledge_document → 归属 kb
t_knowledge_vector   → 按 collection_name 字段逻辑隔离（PG 同表，Milvus 共享 collection）
```

表里没有 `tenant_id` 字段——隔离靠 kb_id + user_id。

### 8.3 面试话术

> Ragent 是内部部署型系统，不是 SaaS。权限靠 Sa-token 实现登录态管理，多租户隔离靠 kb_id 维度——每个知识库独立 collection，用户只能访问有权限的知识库。向量库用 PG 的 schema 隔离（每库一个 collection_name），不需要真正多租户级别的 tenant 列。