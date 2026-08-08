# Ragent 核心机制深度解析（第二篇）

> 接第一篇（ragent-chunking-analysis.md、ragent-intent-rewrite-config.md），覆盖 Context 组装、限流并发、SSE 流式、Rerank 模型。

---

## 一、Context 组装——检索回来的 chunk 怎么变成 LLM 的 Prompt

`DefaultContextFormatter`（`DefaultContextFormatter.java:42`）是整个 RAG 管线的"最后一公里"——把检索结果格式化为 LLM 能理解的 Prompt 文本。

<a id="context-three-branches"></a>
### 1.1 三层分支

```java
public String formatKbContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> chunks, int topK) {
    if (kbIntents == null || kbIntents.isEmpty())    return formatChunksWithoutIntent(...);    // 无意图
    if (kbIntents.size() > 1)                        return formatMultiIntentContext(...);       // 多意图
    return formatSingleIntentContext(...);                                                       // 单意图
}
```

<a id="context-single-intent"></a>
### 1.2 单意图（最常见情况）

```
formatSingleIntentContext(ragent-retrieval, chunks):
  → renderSnippetRules   — 意图节点配置的 PromptSnippet 拼进开场规则
  → renderChunksGroupedByDoc — 按 docId 分组，组内按 chunkIndex 排序
  → renderKbSection       — 用 CONTEXT_FORMAT_PATH 模板包裹整个 KB 上下文
```

最终拼出的 Prompt 结构：

```xml
<rules>                                   ← 意图节点的 PromptSnippet 配置
回答要简洁准确
</rules>

<content source="简历亮点提炼（合并版）">   ← docName 剥后缀后作为 source 属性
  文档分块策略是...                        ← 同文档的 chunk 按 chunkIndex 排序后拼接
  Block 模型包括...
</content>
```

**背后的模板**（`prompt/context-format.st`）：

```
kb-section          → {snippet_section}{doc_blocks}      ← 包裹整体 KB 上下文
  ├─ snippet-rules  → <rules>{rules}</rules>              ← 意图节点配置的 PromptSnippet
  ├─ kb-doc-block   → <content source="{source}">        ← 有文档标题的 chunk 组
  │                    {chunks}</content>
  └─ kb-doc-block-untitled → <content>{chunks}</content> ← 无标题的 chunk 组（兜底）
```

`DefaultContextFormatter` 的方法与 section 的对应：

| Java 方法 | 渲染 section | 产出 |
|:---|:---|:---|
| `renderKbSection` | `kb-section` | 整体容器 |
| `renderSnippetRules` | `snippet-rules` | 意图规则 block |
| `renderDocBlock` | `kb-doc-block` 或 `kb-doc-block-untitled` | 每个文档的 chunk 组 |
| `renderChunksGroupedByDoc` | (调用 renderDocBlock × N) | 按 docId 分组后的所有文档块 |

<a id="context-doc-group"></a>
### 1.3 按文档分组 + 排序（"等价父文档"的实现）

`renderChunksGroupedByDoc`（行 201）是父文档等价方案的核心：

```java
// 1. 按 docId 分组（LinkedHashMap 保持首次出现顺序 = 文档间相关性排序）
LinkedHashMap<String, List<RetrievedChunk>> groups = new LinkedHashMap<>();
for (RetrievedChunk chunk : limited) {
    String key = chunk.getDocId() != null ? chunk.getDocId() : "__nodoc__";
    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
}

// 2. 组内按 chunkIndex 升序 → 还原原文顺序
List<RetrievedChunk> ordered = group.stream()
    .sorted(Comparator.comparing(RetrievedChunk::getChunkIndex, Comparator.nullsLast(...)))
    .toList();

// 3. 组内文本用换行顺次拼接（＝还原为"父文档"的连续片段）
return ordered.stream().map(RetrievedChunk::getText).collect(Collectors.joining("\n"));
```

**效果**：检索命中 chunk_3 和 chunk_7 → MetadataEnrichment 回表拿到同文档的 docId → 分组后按 chunkIndex 排序 → 还原原文顺序 → LLM 看到文档连续片段。

#### MetadataEnrichment 补了什么——检索结果 vs 富化后

检索阶段（PG/ES 查询后）直出的 `RetrievedChunk` 只有 3 个字段：

```java
// PgVectorRetrieverService: SELECT id, content, score
RetrievedChunk { id, text, score, docId=null, chunkIndex=null, docName=null }
```

`MetadataEnrichmentPostProcessor`（`MetadataEnrichmentPostProcessor.java:42`）两跳 SQL 补齐 3 个字段：

```java
// ① t_knowledge_chunk → doc_id, chunk_index
// ② t_knowledge_document → doc_name
chunk.setDocId(meta.docId());
chunk.setChunkIndex(meta.chunkIndex());
chunk.setDocName(meta.docName());
```

| 字段 | 检索阶段 | MetadataEnrichment 后 | 用途 |
|:---|:---|:---|:---|
| `id` | ✅ 向量库主键 | 同 | chunk 去重 key |
| `text` | ✅ chunk 内容 | 同 | **直接拼接，不需再取** |
| `score` | ✅ 相关度 | 同 | RRF 融合 + Rerank 排分 |
| `docId` | ❌ | ✅ 回表补齐 | **按文档分组** — 等价父文档的核心 ① |
| `chunkIndex` | ❌ | ✅ 回表补齐 | **组内排序** — 等价父文档的核心 ② |
| `docName` | ❌ | ✅ 回表补齐 | LLM 引用标注（`source="..."`）|

**回表取的是元数据，不是内容**：传统 Parent-Child 需要多取父 chunk 的 `text`（内容），一次 I/O。Ragent 的相邻 chunk 的 `text` 本就在检索结果里，只差 `docId`/`chunkIndex`/`docName` 三个标签来分组排序——查的是元数据，成本低一个量级。

### 1.4 多意图——合并去重

多个 KB 意图命中时（如"检索" + "Chunk 切分"），去重后仍有重叠的 chunk 按 chunkId 去重：

```java
// 合并所有意图的文档片段（按 chunk id 去重，保持相关性顺序）
Map<String, RetrievedChunk> dedupById = new LinkedHashMap<>();
rerankedByIntent.values().stream()
    .flatMap(List::stream)
    .forEach(chunk -> dedupById.putIfAbsent(chunk.getId(), chunk));
```

### 1.5 MCP 上下文——独立通道

`formatMcpContext` 把工具调用结果格式化，按 toolId 分组、成功/错误分区、可以带对应的 PromptSnippet。

### 1.6 面试话术

> 检索回来的 chunk 不是直接堆进 Prompt。我们有 `ContextFormatter` 做三层格式化：第一层按 docId 分组（同文档的 chunk 聚在一起），第二层组内按 chunkIndex 排序还原原文顺序（这是"等价父文档"方案的核心——不存两套 chunk，靠排序还原上下文），第三层用 StringTemplate 模板包裹带文档标题和意图规则。关键设计是 MetadataEnrichment——回表只补齐 docId/chunkIndex/docName 三个元数据标签，不取任何内容。chunk 的 text 在检索阶段已经有了，相邻 chunk 拼起来就是原文。传统 Parent-Child 需要多查一份父 chunk 的内容，我们多查的只是几个元数据字段，成本差了一个数量级。

---

<a id="rerank-details"></a>
## 二、Rerank 详解——为什么需要两轮排序

<a id="bi-cross-encoder"></a>
### 2.1 Bi-encoder vs Cross-encoder

| | 向量检索 (bi-encoder) | Rerank (cross-encoder) |
|:---|:---|:---|
| 原理 | query 和 doc 独立编码，再算余弦相似度 | query 和 doc 拼接后一起过模型 |
| query-doc 交互 | **无**（各自编码后不交流） | **有**（attention 跨越 query 和 doc） |
| 速度 | 快（一次编码+ANN 索引） | 慢（每对 query-doc 跑一次 Transformer） |
| 精度 | 低（丢失词级交互） | **高**（能判定"这个词对 query 重要"） |
| 阶段 | 召回（从海量候选中粗筛） | 精排（从少量候选中精选） |

**第一阶段**：向量检索用 bi-encoder 从 17 个 chunk 中粗筛出来 + BM25 从 14 个中粗筛。
**第二阶段**：Rerank 用 cross-encoder 把这 17 个候选 pair-by-pair 跟 query 过一遍，重新打分、取 top 10。

### 2.2 为什么 Rerank 放 RRF 之后

RRF 把 17+14=31 个候选去重融合成 17 个，**先减少候选量**，再 Rerank 精排。如果 Rerank 接手 31 个候选，跨编码成本是 31×17 对，放 17 个候选只跑 17 对——省近一半成本。

### 2.3 模型路由

```yaml
rerank:
  enabled: true
  default-model: qwen3-rerank
  candidates:
    - id: qwen3-rerank
      provider: gitee
      model: Qwen3-Rerank         # gitee 有硬编码的 API Key，实际可用
```

`RoutingRerankService` 跟 chat 一样的 fallback 机制：首选 default-model，失败切备选。

<a id="rerank-attribution"></a>
### 2.4 归因日志

`RerankPostProcessor.logAttribution` 在 rerank 前后对比各通道存活数量，日志示例：

```
检索归因 - Rerank 输入按通道: 向量=17 关键词=14
检索归因 - Rerank 输出 top10 按通道: 向量=10 关键词=8
```

interp：10+8-10=8 个 chunk 在两个通道都有，Rerank 偏语义所以向量通道占优。

### 2.5 面试话术

> 向量检索和 BM25 是 bi-encoder，query 和 doc 独立编码——没有交互，精度有限。Rerank 是 cross-encoder，把 query 和每个候选拼接后过 Transformer，attention 能捕捉提问词和文档词的匹配关系，精度远高于向量检索。但 cross-encoder 每对 query-doc 要跑一次模型，全量候选跑不了。我们把 Rerank 放 RRF 融合之后——RRF 把两路 31 个候选去重融合成 17 个，再让 Rerank 精排到 10 个——先减量再精排，成本和精度都控制住了。

---

## 三、限流 & 并发控制

Ragent 有两层限流：**SSE 全局并发限流**和**MinerU 解析限流**。

<a id="chat-queue-limiter"></a>
### 3.1 ChatQueueLimiter——SSE 入口限流

```yaml
rate-limit:
  global:
    enabled: true
    max-concurrent: 10      # 同时对话上限
    max-wait-seconds: 15    # 排队超时
    lease-seconds: 30       # permit 租约（防僵尸）
    poll-interval-ms: 200   # 排队时轮询间隔
```

**流程**：

```
RAGChatServiceImpl.streamChat()
  → ChatQueueLimiter.enqueue(question, conversationId, emitter, onAcquire)

enqueue 内部:
  开关关 → 直通（chatEntryExecutor 直接执行）
  开关开 → FairDistributedRateLimiter.acquire(...)
           ├ onAcquired   → chatEntryExecutor 执行业务
           ├ onTimeout    → handleReject (记录 conversation + 发 SSE REJECT 事件)
           └ cancelBinder → emitter onCompletion/onTimeout/onError 触发取消排队
```

超限时前端收到 `REJECT` SSE 事件后显示"系统繁忙，请稍后再试"，且**这条对话仍被记录到会话历史**。

<a id="fair-rate-limiter"></a>
### 3.2 FairDistributedRateLimiter——Redis 分布式公平队列

#### 解决什么问题

多用户同时提问，LLM 槽位有限（`max-concurrent=10`）。没有排队机制时第 11 个请求直接被拒——不公平，先提问的人应该先拿槽位。更糟的是**多实例部署**：两台 Ragent 各占 5 个槽位，但 10 个请求全落在实例 A，实例 B 闲着。简单本地计数解决不了。

#### 为什么不能只用 Redisson 的 tryAcquire

`RPermitExpirableSemaphore.tryAcquire()` 能拿 permit，但它是**非公平的**——多个人同时抢，谁抢到算谁的，不保证先来的先拿。刚来的请求可能先抢到，先排队的人反而一直等待。

#### 核心设计："谁先排队，谁先拿到 permit"（非抢占式、FIFO）

```
Redisson RPermitExpirableSemaphore (permit 池, max=10)
Redis ScoredSortedSet (排队队列, score=序列号 → FIFO)
Redis Lua 脚本 (原子 claim: ZREM + 检查 entry 存活标记)
Redis RTopic (跨实例通知: permit 释放时唤醒等待者)
```

#### 完整时间线示例

```
用户 A 提问（第 11 个，槽位已满，需要排队）
用户 B 提问（第 12 个）

T0: A 到达 → tryAcquireIfReady → availablePermits=0
    → setEntryMarker(A, ttl=20s)   ← Redis 写存活标记
    → queue.add(seq=100, "A")      ← 入队
    → scheduleQueuePoll(A)         ← 启动 200ms 定时轮询

T1: B 到达 → tryAcquireIfReady → availablePermits=0
    → setEntryMarker(B, ttl=20s)
    → queue.add(seq=101, "B")
    → scheduleQueuePoll(B)

T2: 某个执行中的任务完成 → releasePermit
    → 1 个 permit 回到池
    → publishQueueNotify()  ← Redis 广播 "permit_changed"

T3: PollNotifier 收到广播 → 批量唤醒所有等待的 poller
    A 的 poller: claimIfReady("A", availablePermits=1)
      → Lua 原子执行: "A" 是队头 ✅ → ZREM "A" → claim 成功
      → tryAcquirePermit → permitId = "xxx"
      → A.state: PENDING → GRANTED  ✅ A 拿到，开始执行业务

    B 的 poller: claimIfReady("B", availablePermits=1)
      → Lua 原子执行: availablePermits=0 (已被 A 消耗)
      → claim 失败，B 继续等待
```

#### 分步详解

**① 入队**（`acquire`，行 138）：

```java
// 写入 entry 存活标记（TTL = maxWaitMillis + 5s 缓冲）
setEntryMarker(ticket.requestId, req.maxWaitMillis());

// 加入 Redis SortedSet（score = 递增序列号，保证 FIFO）
RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(queueKey);
queue.add(nextQueueSeq(), ticket.requestId);

// 尝试立即获取
if (tryAcquireIfReady(ticket)) return;

// 没拿到 → 启动定时轮询
scheduleQueuePoll(ticket);
```

**② 轮询等待**（`scheduleQueuePoll`，行 338）：200ms 定时轮询 + PollNotifier 即时通知：

```
定时轮询 (200ms):     确保至少每 200ms 检查一次 → 最坏 200ms 延迟
PollNotifier 通知:    收到 Redis 广播立即批量唤醒 → ~5ms 延迟
```

**③ 原子抢占**（Lua 脚本 `lua/queue_claim_atomic.lua`）：

```lua
-- ① 查队头（ZSet 中 score 最小 = 最早入队）
local queueHead = redis.call('ZRANGE', queueKey, 0, 0)

-- ② 检查队头是否有活着的 entry 标记 → 无标记 = JVM 崩溃 → ZREM 清理
while queueHead and not redis.call('EXISTS', entryPrefix .. queueHead) do
    redis.call('ZREM', queueKey, queueHead)
    queueHead = redis.call('ZRANGE', queueKey, 0, 0)
end

-- ③ 只有队头 + permit 可用 → 原子 ZREM（抢占成功）
if queueHead == requestId and availablePermits > 0 then
    redis.call('ZREM', queueKey, requestId)
    return {1, score}  -- 抢占成功！
end
return {0}  -- 失败（不在队头或没 permit）
```

三个保障：只允许队头抢占 → **FIFO 公平** / Lua 原子执行 → **多实例不冲突** / 清理僵尸队头 → **JVM 崩溃不阻塞**

**④ 取消链路**：用户点"停止生成" → emitter.close → cancelBinder → Ticket.cancel：

```
state.compareAndSet(PENDING, CANCELLED)  ← CAS，只执行一次
cleanup():
  ZREM from queue            ← 从等待队列删除
  deleteEntryMarker          ← 清理 entry 标记
  releaseHeldPermit          ← 如果在 GRANTED 态，释放 permit 给下一个人
  unregisterFromNotifier     ← 注销 poller
  cancelFuture               ← 停止定时轮询
  publishQueueNotify         ← 通知其他等待者"permit 变化了"
```

**⑤ 状态机**：三个终态互斥，回调最多触发一次：

```java
cancel():  state.compareAndSet(PENDING, CANCELLED)
timeout(): state.compareAndSet(PENDING, TIMED_OUT)
grant():   state.compareAndSet(PENDING, GRANTED)
// 只有第一个成功的 CAS 进入终态，其他 CAS 直接失败返回
```

#### 关键安全机制

| 机制 | 解决什么问题 | 如果不用会怎样 |
|:---|:---|:---|
| **entry 存活标记** (TTL) | JVM 崩溃后 entry 自然过期 | 已死请求永久占据队头，阻塞所有人 |
| **Lua 原子 claim** | 多实例同时抢占队头 | 两个实例同时 ZREM 同一个 requestId，都 claim 成功但只有一个 permit → 并发槽位错乱 |
| **permit lease 30s** | 业务线程卡死 | permit 永久泄漏，槽位数慢慢降到 0 |
| **RTopic 跨实例通知** | 一台机器释放 permit，其他机器不知道 | 其他机器要等 200ms 轮询才能感知 → 平均 100ms 额外延迟 |
| **状态机 CAS** | cancel/timeout/grant 三个路径并发 | permmit 被释放两次，导致两个请求同时拿到同一个槽位 |

### 3.3 MinerU 解析限流

```yaml
mineru:
  semaphore-name: rag:mineru:parse
  concurrency-limit: 5
```

跟聊天限流同一套 `RPermitExpirableSemaphore`，独立 5 个槽位，限制 MinerU API 并发数。

### 3.4 面试话术

> 限流是 RAG 系统的第一道防线。我们基于 Redisson 的 `RPermitExpirableSemaphore` 做了一个分布式公平队列——permit 池上限 10 个并发对话，超出的排队等待 15 秒。队列按序列号 FIFO 保证公平性，permit lease 30 秒防僵尸。关键是在超限场景下不是简单返回 429——对话仍然被记录到会话历史，前端收到 REJECT 事件显示友好的"系统繁忙"提示。MinerU 解析还有独立 5 槽的限流，避免多个文档同时解析打爆 MinerU API 额度。

---

## 四、SSE 流式输出——从模型到前端

<a id="sse-overall"></a>
### 4.1 整体链路

```
StreamChatPipeline.streamLLMResponse()
  → RoutingLLMService.streamChat(request, callback)
    ├ 选候选模型列表（deepThinking=true 时优先 deep-thinking-model）
    ├ 逐个尝试:
    │   for (ModelTarget target : targets) {
    │       client.streamChat(request, probeBridge, target)
    │       → 首包超时探测（60s）
    │       → 成功 → 返回 StreamCancellationHandle
    │       → 失败 → healthStore.markFailure + 切下一个模型
    │   }
    │   → 所有模型失败 → callback.onError("大模型调用失败")
    ↓
  SseEmitter 流式推给前端
```

<a id="multi-model-fallback"></a>
### 4.2 多模型 Fallback

`RoutingLLMService.streamChat`（`RoutingLLMService.java:102`）：

```java
for (ModelTarget target : targets) {
    // 健康检查：优先跳过已被标记不健康的模型
    if (!healthStore.allowCall(target.id())) continue;

    StreamCancellationHandle handle;
    try {
        handle = client.streamChat(request, probeBridge, target);
    } catch (Exception e) {
        healthStore.markFailure(target.id());     // 标记失败
        continue;                                   // 切下一个
    }

    ProbeResult result = awaitFirstPacket(bridge, handle, callback);  // 60s 超时
    if (result.isSuccess()) {
        healthStore.markSuccess(target.id());
        return handle;                             // 成功，不再试后备
    }
    handle.cancel();
    healthStore.markFailure(target.id());
}

// 所有模型都失败 → 通知前端
callback.onError(new RemoteException("大模型调用失败，请稍后再试..."));
```

### 4.3 健康状态存储

```yaml
ai:
  selection:
    failure-threshold: 2    # 连续失败 2 次标记不健康
    open-duration-ms: 30000 # 30 秒后半开重试
```

`ModelHealthStore` 记录模型健康状态，连续 2 次失败后标记 unhealthy，30 秒后自动半开。

### 4.4 首包超时探测（TTFT 追踪）

`LlmFirstPacketProbe.awaitFirstPacket`（`LlmFirstPacketProbe.java:35`）：单独拆成 bean 是为了让 Spring AOP 能拦截做全链路 Trace 的 TTFT（Time to First Token）探针。

首包超过 60s → 标记失败 + 切下一个模型。

<a id="frontend-cancel"></a>
### 4.5 前端取消

```java
// RAGChatServiceImpl.stopTask(taskId)
taskManager.cancel(taskId);  → StreamCancellationHandle.cancel() → 关闭 HTTP 流
```

前端点"停止生成" → SSE emitter 关闭 → `cancelBinder` 触发 → `FairDistributedRateLimiter.Ticket.cancel()` → 队列清理 + 释放 permit。

### 4.6 面试话术

> 我们在 infra-ai 层封装了 RoutingLLMService 做模型多路 fallback。每次 LLM 请求并行加载所有候选模型，从默认模型开始逐个尝试——每个有 60 秒首包超时探测，失败自动切下一个。连续 2 次失败后模型被标记不健康 30 秒，期间不再尝试。所有模型都失败才向前端报错。这套机制在 AI API 不稳定的场景下大幅降低了对话失败率。前端点了"停止生成"也通过 cancelBinder 反向通知到排队系统释放 permit，避免槽位泄漏。
