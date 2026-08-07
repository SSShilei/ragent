# Ragent 上下文压缩、摘要与短期记忆深度解析

> 覆盖 "压缩如何触发？"、"摘要写入哪？"、"和短期记忆什么关系？"、"异步还是同步？" 等面试常见追问。
> 所有代码定位均基于 Ragent 源码，精确到行号。

---

## 1. 先厘清三个概念的物理边界

| | 短期记忆 | 上下文压缩 | 摘要 |
|:---|:---|:---|:---|
| 是什么 | 原材料——对话的所有原始消息 | 加工过程——触发 LLM 把旧消息榨成摘要 | 产物——LLM 生成的一行文本 |
| 存在哪 | `t_message`（MySQL） | 代码逻辑 | `t_conversation_summary`（MySQL） |
| 谁写 | `JdbcConversationMemoryStore.append()` | `JdbcConversationMemorySummaryService.doCompressIfNeeded()` | `createSummary()` |
| 谁读 | `loadHistory()` → `LIMIT historyKeepTurns*2` | — | `loadLatestSummary()` → `LIMIT 1` |
| 注入位置 | messages[1..N] | — | messages[0]，在原文前面 |

**一句话关系**：短期记忆是 DB 里的原材料全集，上下文压缩从其中挑出"窗口外"的增量消息用 LLM 加工成摘要，摘要写入独立表，下一轮加载时排在原文前面注入 LLM 上下文。

---

## 2. 一条消息的完整生命周期

以第 N 轮对话为例，从用户请求到 assistant 回复完成，穿越全部相关代码。

### 阶段一：记忆加载 + 用户消息落库（同步，阻塞主流程）

```
StreamChatPipeline.execute()                                                          [L78]
  └── loadMemory(ctx)                                                                 [L100]
        └── memoryService.loadAndAppend(convId, userId, ChatMessage.user(question))   [L101]
              │
              ├── ① load(convId, userId)  ← 先加载历史
              │     └── DefaultConversationMemoryService.load()                        [L49]
              │           │
              │           ├── CompletableFuture.supplyAsync(                           [L58]
              │           │     () -> loadSummaryWithFallback(...),  memoryLoadExecutor)
              │           │         └── summaryService.loadLatestSummary(...)         [L86]
              │           │               └── SELECT * FROM t_conversation_summary
              │           │                   WHERE conversation_id=? AND user_id=?
              │           │                   AND deleted=0 ORDER BY id DESC LIMIT 1
              │           │                   返回最近一条摘要(可为 null)
              │           │
              │           └── CompletableFuture.supplyAsync(                           [L61]
              │                 () -> loadHistoryWithFallback(...),  memoryLoadExecutor)
              │                   └── memoryStore.loadHistory(...)
              │                         └── JdbcConversationMemoryStore.loadHistory()  [L54]
              │                               │
              │                               ├── resolveMaxHistoryMessages()
              │                               │     = historyKeepTurns * 2 = 16 条消息
              │                               │
              │                               ├── SELECT * FROM t_message
              │                               │     WHERE conversation_id=? AND user_id=?
              │                               │     ORDER BY create_time DESC LIMIT 16
              │                               │
              │                               └── normalizeHistory()  ← 去掉头部孤立的 assistant 消息
              │                                     [assistant, user, assistant, ...]
              │                                     → [user, assistant, assistant, ...]
              │                                     (丢弃开头角色的 assistant，直到遇到第一个 user)
              │
              ├── ② allOf(summaryFuture, historyFuture).join()  ← 阻塞等待两个并行任务完成
              │     └── attachSummary(summary, history)
              │           如果 summary != null:
              │             result = [decorateIfNeeded(summary), ...history]
              │             = [ChatMessage.system("<conversation-summary>..."), user, assistant, ...]
              │           如果 summary == null:
              │             result = history
              │           ctx.setHistory(result)  ← 这个列表成为后续 pipeline 的上下文基础
              │
              └── ③ append(convId, userId, userMessage)  ← 后写入(先读后写)
                    └── JdbcConversationMemoryStore.append()                             [L75]
                          │
                          ├── INSERT INTO t_message (role='user', content=问题文本)
                          │     → 返回 messageId
                          │
                          ├── 如果是 USER 角色:
                          │     UPDATE/INSERT t_conversation (更新 lastTime、question)
                          │
                          └── summaryService.compressIfNeeded(convId, userId, userMessage)
                                └── role != ASSISTANT → 直接 return ← 不触发！
```

**关键点 1**：`loadAndAppend` 的语义是 "先加载历史，再写入当前用户消息"。返回的 history **不含**当前消息，所以 pipeline 后续步骤拿到的是 "这条消息之前的上下文"。

**关键点 2**：`normalizeHistory` 处理的是从 DB 倒序查询、只取 LIMIT 条带来的边界情况——如果首条是孤立的 assistant 消息（对应的 user 消息在 LIMIT 之外），会被丢弃。

### 阶段二：消息列表最终传给 LLM

流水线经过 "改写 → 意图识别 → 检索 → Prompt 组装" 后，`streamLLMResponse` 组装最终请求：

```java
// StreamChatPipeline L220-225
List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
    promptContext,     // 检索结果、MCP 数据、知识库上下文
    history,           // ← loadMemory 阶段产出的 [summary + 最近8轮原文]
    rewrittenQuestion, // 改写后的 query
    subQuestions       // 多问题拆分结果
);
```

最终 messages 数组结构：
```
messages = [
  system(System Prompt + 知识库检索结果 + 对话规则),   ← promptBuilder 拼的
  ...history,                                          ← summary + 原文（如果有 summary 就在最前面）
  user(rewrittenQuestion)                              ← 当前问题放最后
]
```

注意：当前问题出现在 messages 最后，而不是 history 里。因为 history 是 `load` 阶段产出的（不包含当前消息），当前消息是 `append` 写入的——写入在 load 之后，所以当前消息不污染上下文。

### 阶段三：assistant 回复落库 + 触发压缩（异步，不阻塞 SSE 返回）

```
StreamChatEventHandler.onComplete()           [L157]
  └── memoryService.append(convId, userId, ChatMessage.assistant(answer, thinkingContent, duration))  [L165]
        └── JdbcConversationMemoryStore.append()
              ├── INSERT INTO t_message (role='assistant', content=回答文本)
              │     → 返回 messageId
              │
              └── summaryService.compressIfNeeded(convId, userId, assistantMessage)
                    └── role == ASSISTANT ✓
                    └── summaryEnabled == true?（默认 false！）
                    └── CompletableFuture.runAsync(this::doCompressIfNeeded, memorySummaryExecutor)
                          └── 立即返回，不阻塞 SSE 完成事件！
```

**这就是 "异步" 的来源**：`compressIfNeeded` 通过 `CompletableFuture.runAsync` 丢给 `memorySummaryExecutor` 线程池（核心 1 线程、最大 CPU/2 线程、LinkedBlockingQueue 200），主线程立刻继续 SSE complete → 用户收到回复。压缩在后台悄悄跑。

---

## 3. 压缩引擎内部：四条命门

`doCompressIfNeeded()` 的完整逻辑（`JdbcConversationMemorySummaryService.java:99-175`）：

### 命门 ①：轮数不够，不压

```java
long total = conversationGroupService.countUserMessages(conversationId, userId);
// SELECT COUNT(*) FROM t_message
// WHERE conversation_id=? AND user_id=? AND role='user' AND deleted=0
if (total < triggerTurns) {    // 默认 < 9
    return;
}
```

### 命门 ②：分布式锁没拿到，不压（防并发）

```java
String lockKey = "ragent:memory:summary:lock:" + userId + ":" + conversationId;
RLock lock = redissonClient.getLock(lockKey);
if (!lock.tryLock()) {
    return;  // 已经有另一个线程在压缩这个会话了
}
```

### 命门 ③：上次摘要没滑出窗口，不压（半窗重叠保护）

```java
// 取最近 W=8 轮的 user 消息（倒序）
List<ConversationMessageDO> latestUserTurns = listLatestUserOnlyMessages(convId, userId, maxTurns);
// SELECT * FROM t_message WHERE ... AND role='user' ... ORDER BY create_time DESC LIMIT 8
// 结果（倒序）: [m8, m7, m6, m5, m4, m3, m2, m1]

// 窗口最旧消息 id
String historyStartId = latestUserTurns.get(7).getId();  // m1.id

// 上次摘要锚点
ConversationSummaryDO latestSummary = findLatestSummary(convId, userId);
String afterId = latestSummary == null ? null : resolveSummaryStartId(latestSummary);

// 条件：afterId < historyStartId 时才继续（上次摘要覆盖的消息已在窗口外）
if (afterId != null && Long.parseLong(afterId) >= Long.parseLong(historyStartId)) {
    return;  // 摘要覆盖范围还包含在保留窗口内 → 没必要再摘要
}
```

这是最精妙的设计。为什么要这么做？

假设没有这个判断，每次 assistant 回复都触发 LLM，9 → 10 → 11 轮每次都生成摘要，每轮一次额外 LLM 调用。有了半窗重叠保护：

```
第 9 轮: total=9 ≥ 9 ✓, afterId=null → 触发，生成 S1(lastMessageId=m4)
第 10 轮: total=10 ≥ 9 ✓, afterId=m4.id, historyStartId=m2.id
         → afterId < historyStartId? 取决于 m4 是否在窗口内
         W=8 窗口: [m10,m9,m8,...,m3]  → historyStartId=m3
         afterId=m4.id < m3.id? → m4 在窗口外 ✓ → 触发，生成 S2
         W=8 窗口: [m10,m9,m8,...,m3]  → historyStartId=m3
         afterId=m4.id < m3.id? → id 是自增的 → m4.id < m3.id? 
         
这里需要注意：id 自增、消息按时间升序。m1 最早，m10 最新。
latestUserTurns 倒序后是 [m10, m9, m8, m7, m6, m5, m4, m3]
historyStartId = m3.id (最后一个 = 最旧)

第 9 轮首次压缩后 S1.lastMessageId = cutoffId = latestUserTurns[(8-1)/2].id = latestUserTurns[3].id = m7.id

第 10 轮: latestUserTurns = [m11,m10,m9,m8,m7,m6,m5,m4]
  historyStartId = m4.id
  afterId = m7.id (S1.lastMessageId)
  m7.id < m4.id → ✓ m7 在窗口外 → 触发！
  
第 11 轮: latestUserTurns = [m12,m11,m10,m9,m8,m7,m6,m5]
  historyStartId = m5.id
  afterId = m9.id (S2.lastMessageId)
  m9.id < m5.id → ✓ → 触发！

第 12 轮: latestUserTurns = [m13,m12,m11,m10,m9,m8,m7,m6]
  afterId = S3.lastMessageId = m11.id (取 cutoffId = turns[3])
  historyStartId = m6.id
  m11.id < m6.id → ✓ → 触发
```

实际上每 2 轮左右触发一次（因为 cutoffId 取窗口中间），比每轮触发省掉约 50% 的 LLM 调用。

### 命门 ④：没有新消息可摘要，不压

```java
String summaryCutoffId = resolveSummaryCutoffId(latestUserTurns);
// cutoffId = latestUserTurns[(W-1)/2]  ← 整数除法，取窗口中间位置

// 增量段 = (afterId, cutoffId] 之间的 user+assistant 消息
List<ConversationMessageDO> toSummarize = listMessagesBetweenIds(convId, userId, afterId, cutoffId);
// SELECT * FROM t_message WHERE id > afterId AND id < cutoffId
// AND role IN ('user','assistant') ORDER BY id ASC

if (CollUtil.isEmpty(toSummarize)) {
    return;  // 无增量，跳过
}
```

### 四条命门全部通过后：真正调 LLM

```java
String existingSummary = latestSummary == null ? "" : latestSummary.getContent();
String summary = summarizeMessages(toSummarize, existingSummary);
createSummary(conversationId, userId, summary, lastMessageId);
```

---

## 4. LLM 摘要调用的精确参数

```java
// summarizeMessages() L177-216
List<ChatMessage> summaryMessages = new ArrayList<>();

// ① System Prompt: 从 conversation-summary.st 加载，注入 summary_max_chars
String summaryPrompt = promptTemplateLoader.render(
    "prompt/conversation-summary.st",
    Map.of("summary_max_chars", String.valueOf(200))
);
summaryMessages.add(ChatMessage.system(summaryPrompt));

// ② 如果有旧摘要：作为 assistant 消息注入（带防污染指令）
if (StrUtil.isNotBlank(existingSummary)) {
    summaryMessages.add(ChatMessage.assistant(
        "历史摘要（仅用于合并去重，不得作为事实新增来源；若与本轮对话冲突，以本轮对话为准）：\n"
        + existingSummary.trim()
    ));
}

// ③ 增量消息：全部 user+assistant 对话原文
summaryMessages.addAll(histories);

// ④ 收尾指令
summaryMessages.add(ChatMessage.user(
    "合并以上对话与历史摘要，去重后输出更新摘要。要求：严格≤" + 200 + "字符；仅一行。"
));

// ⑤ LLM 调用
ChatRequest request = ChatRequest.builder()
    .messages(summaryMessages)
    .temperature(0.3D)   // 低温度，保证摘要稳定可复现
    .topP(0.9D)
    .thinking(false)     // 不需要深度推理
    .build();
String result = llmService.chat(request);  // ← 同步调用（非流式）
```

摘要 Prompt 的核心指令（`conversation-summary.st`）：
- **长度限制**：严格 ≤ 200 字符，单行输出
- **禁止记答案**：只记 "话题 + 状态"，不记具体数据/规则/结论
- **合并去重**：与旧摘要去重
- **状态标注**：已解答/当时无记录/部分解答/待确认
- **约束保留**：时间范围、地点、预算等用户明确约束

---

## 5. 摘要的写入存储

```java
// createSummary() L291-301
ConversationSummaryBO record = ConversationSummaryBO.builder()
    .conversationId(conversationId)
    .userId(userId)
    .content(content)           // "用户咨询了年假计算规则（已解答）、病假政策（当时无记录）..."
    .lastMessageId(lastMessageId)  // 摘要截止点的消息 ID（锚点）
    .build();
conversationMessageService.addMessageSummary(record);
// → INSERT INTO t_conversation_summary
```

### 两张表的物理关系

```
t_message                              t_conversation_summary
──────────                             ──────────────────────
id (PK)         VARCHAR(20)            id (PK)         VARCHAR(20)
conversation_id VARCHAR(20)            conversation_id VARCHAR(20)
user_id         VARCHAR(20)            user_id         VARCHAR(20)
role            VARCHAR(16)            last_message_id VARCHAR(20)  ← 锚点
content         TEXT                   content         TEXT          ← 摘要文本
thinking_content TEXT                  create_time     TIMESTAMP
thinking_duration INTEGER              update_time     TIMESTAMP
create_time     TIMESTAMP              deleted         SMALLINT
update_time     TIMESTAMP
deleted         SMALLINT

索引: idx_conversation_user_time       索引: idx_conv_user
     (conversation_id, user_id,             (conversation_id, user_id)
      create_time)
```

**为什么分两张表？**
- 角色不同：消息是 user/assistant 对话流转记录，摘要是 system 级别的元信息
- 生命周期不同：消息是 append-only、按时间查询、支持翻页；摘要是 upsert 语义（同会话只有一条有效记录，新摘要替代旧摘要）
- 注入方式不同：消息直接作为 messages[i] 参与对话，摘要需要 `decorateIfNeeded` 包成 `<conversation-summary>` 标签后再注入

---

## 6. 摘要注入上下文：读链路详解

下一轮对话启动时，`load()` 方法并行加载摘要和历史：

```
load(convId, userId)
  ├── loadSummaryWithFallback()
  │     → SELECT * FROM t_conversation_summary
  │       WHERE conversation_id=? AND user_id=? AND deleted=0
  │       ORDER BY id DESC LIMIT 1
  │     返回最近一条摘要
  │
  └── loadHistoryWithFallback()
        → SELECT * FROM t_message
          WHERE conversation_id=? AND user_id=?
          ORDER BY create_time DESC LIMIT historyKeepTurns*2 (=16条)
        → normalizeHistory() 去掉孤立的 assistant
        → 返回 List<ChatMessage>
```

然后 `attachSummary` 组装：

```java
private List<ChatMessage> attachSummary(ChatMessage summary, List<ChatMessage> messages) {
    if (summary == null) return messages;           // 没有摘要 → 纯原文
    List<ChatMessage> result = new ArrayList<>();
    result.add(summaryService.decorateIfNeeded(summary));  // 摘要放第一位
    result.addAll(messages);                                 // 原文跟在后面
    return result;
}
```

`decorateIfNeeded` 套用 `summary-wrapper` 模板：
```xml
<!-- context-format.st L64-67 -->
<conversation-summary>
{content}
</conversation-summary>
```

最终传给 LLM 的消息序列：
```
[0] system "<conversation-summary>\n用户咨询了年假计算规则（已解答）、病假政策（当时无记录）。关键词：人事政策, 假期\n</conversation-summary>"
[1] user    "请问年假怎么算？"          ← loadHistory 取到的第 1 轮原文
[2] assistant "根据公司规定..."         ← loadHistory 取到的第 1 轮原文
[3] user    "那病假呢？"               ← 第 2 轮
[4] assistant "抱歉，病假相关规定暂未收录..." ← 第 2 轮
...（至多 8 轮原文）
[N] user    "那我今年的年假还剩几天？"  ← 当前轮（loadAndAppend 时先 load 再 append，当前轮在 load 之后）
```

**摘要放在消息列表第 0 位**——这是设计选择：让 LLM 先看到 "之前谈了什么、现在什么状态"，再看具体对话原文。

---

## 7. 异步模型详解：什么时候用户能 "看到" 摘要效果？

### 时序图

```
时间轴 ──────────────────────────────────────────────────────→

第 9 轮:
  user msg → load(无摘要) → append(user) → ... → LLM reply
                                                     ↓
                                                  append(assistant)
                                                     ↓
                                            compressIfNeeded 触发
                                                     ↓
                                        CompletableFuture.runAsync
                                                     ↓          \
                                        主线程: onComplete → SSE DONE → 用户看到回复
                                                     ↓          \
                                        后台线程: 获取锁 → SQL → LLM 摘要 → INSERT summary
                                                     ↓
                                                  摘要写入 DB (但第 9 轮已经返回了)

第 10 轮:
  user msg → load() ─┬─ loadSummary: 查到摘要！(如果后台线程已完成)
                     │   如果后台线程还没完成 → 查不到 → 无摘要
                     └─ loadHistory: 最近 8 轮原文
           → append(user) → ... → LLM reply（带或不带摘要取决于后台线程速度）
```

**存在一拍的滞后**：第 9 轮回复完成后触发压缩，但压缩在后台异步执行。第 10 轮能否加载到摘要，取决于后台线程的速度。

正常情况（LLM 摘要 < 1 秒，两次对话间隔 > 1 秒）：
- 第 10 轮能加载到第 9 轮生成的摘要 → **滞后一拍**

极端情况（用户秒回、摘要 LLM 超时）：
- 第 10 轮加载不到摘要 → 滞后两拍
- 但 `memorySummaryExecutor` 队列容量 200 + CallerRunsPolicy，不会丢任务

### 线程池配置

```java
// ThreadPoolExecutorConfig.java L141-155
@Bean
public Executor memorySummaryExecutor() {
    new ThreadPoolExecutor(
        1,                              // corePoolSize: 1，保证摘要串行（避免同一会话的并发摘要）
        Math.max(2, CPU_COUNT >> 1),   // maxPoolSize: CPU/2（最少2）
        60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(200),  // 队列 200，满了走 CallerRunsPolicy
        new ThreadPoolExecutor.CallerRunsPolicy()  // 拒绝策略：调用者线程执行
    );
}
```

CallerRunsPolicy 的含义：队列满 200 时，第 201 个压缩任务由调用线程（model_stream_executor 的线程）直接执行 → 虽然会短暂阻塞 SSE 返回，但确保不丢任务。

---

## 8. 配置项全景

```yaml
# application.yaml 或系统设置页面配置
rag:
  memory:
    history-keep-turns: 8        # 保留原文的最近轮数 W（1-100）
    summary-enabled: false       # 是否开启摘要压缩（默认关！）
    summary-start-turns: 9       # 开始摘要的轮数阈值 T（必须 > W）
    summary-max-chars: 200       # 摘要最大字符数（200-1000）
    title-max-length: 30         # 会话标题最大长度
```

校验规则（`MemoryConfigValidator.java`）：
```
若 summaryEnabled == true:
  summaryStartTurns > historyKeepTurns  ← 强制约束
  否则抛出: "summaryStartTurns (N) 必须大于 historyKeepTurns (M)，否则永远不会触发摘要"
```

**为什么默认关闭？** 因为摘要需要额外的 LLM 调用，增加延迟和成本。Ragent 的设计哲学是 "默认用轮次截断兜底，需要长对话记忆的用户手动开启摘要"。

---

## 9. loadHistory 的截断：当压缩关闭时的降级方案

就算 `summaryEnabled = false`，上下文仍然有长度控制——通过 `loadHistory` 的限制实现：

```java
// JdbcConversationMemoryStore.java L55
int maxMessages = resolveMaxHistoryMessages();  // = historyKeepTurns * 2 = 16 条
List<ConversationMessageVO> dbMessages = conversationMessageService.listMessages(
    conversationId, userId, maxMessages, ConversationMessageOrder.DESC
);
// SQL: SELECT * FROM t_message WHERE ... ORDER BY create_time DESC LIMIT 16
```

这就是 **纯截断模式**：不生成摘要，每轮只加载最近 16 条消息（8 轮 user+assistant）。超出 LIMIT 的更早消息根本不会出现在 SQL 结果中——数据库层面就直接丢弃了。

与 PA 的区别：PA 的截断在内存中做（每次 LLM 请求前 slice contents 数组），Ragent 的截断在 DB 查询中做（LIMIT 子句）。本质一样，但 Ragent 的方式更早截断（不加载到内存），更省资源。

---

## 10. Query 改写阶段的独立窗口（更激进）

除了记忆系统的 8 轮窗口，Query 改写阶段还有一个**更小的独立窗口**：

```java
// MultiQuestionRewriteService.java L174-180
// 只保留最近 1-2 轮的 User 和 Assistant 消息（最多 4 条）
List<ChatMessage> recentHistory = history.stream()
    .filter(msg -> msg.getRole() == USER || msg.getRole() == ASSISTANT)
    .skip(Math.max(0, history.size() - 4))  // 最多保留最近 4 条消息（2 轮对话）
    .toList();
messages.addAll(recentHistory);    // 历史拼在 system prompt 之后
messages.add(ChatMessage.user(question)); // 当前 query 放最后
```

原因：Query 改写只需要做指代消解（"它怎么用？" → "Ragent 检索通道怎么用？"），1-2 轮历史就够了。不需要带 8 轮完整历史发一次 LLM 调用，省 token。

这两个窗口是**嵌套**的：

```
loadHistory 的结果（8 轮原文 + 摘要）
        │
        ▼
   StreamChatPipeline.execute()
        │
        ├── rewriteQuery  ← 只从 8 轮里再裁 2 轮供改写 LLM 用
        ├── resolveIntents
        ├── retrieve
        └── streamLLMResponse  ← 8 轮原文全部传给最终 LLM（含检索结果）
```

---

## 11. 潜在问题与面试回答要点

### 问题 1：压缩丢失关键信息怎么办？

回答要点：
- Prompt 设计里 "约束条件优先保留" → 预算、时间范围、型号等不会丢
- 明确禁止记答案 → 避免摘要与最新检索结果冲突
- 摘要后还有 8 轮原文 → 最近的细节在原文里、老的关键约束在摘要里

### 问题 2：异步压缩导致 "摘要还没生成下一轮就来了"？

回答要点：
- 确实有一拍滞后（正常 1 秒内完成）
- 这不是 bug，是 tradeoff：优先保证用户响应速度
- 即使滞后一拍，第 10 轮也有 8 轮原文兜底，不会丢失上下文
- 极端场景可改为同步（去掉 `runAsync`），但没必要

### 问题 3：为什么不一直用摘要？

回答要点：
- 摘要丢失对话细节（具体数据、精确步骤）——这些对当前回答可能至关重要
- 最近 8 轮原文保证 LLM 看到完整的最近对话，不做语义折损
- 摘要只覆盖 8 轮窗口之前的旧对话——那些已经不需要精确原文了

### 问题 4：摘要和短期记忆到底是什么关系？

回答要点：
- **不是包含关系，是派生关系**
- 短期记忆 = `t_message` 里的原始消息（永远不删，只是 limit 查不到更早的）
- 摘要 = 从短期记忆中"压榨"出来的浓缩产物，写入独立表 `t_conversation_summary`
- 摘要**替代**（不是补充）超出窗口的原文——LLM 看到的是 "摘要 + 最近 8 轮原文"，看不到 8 轮之前的原文
- 可以理解为两套存储系统：一套存原料、一套存提炼物，查询时合并

---

## 12. Ragent vs AgentFlow(PA) 最终对比

| 维度 | Ragent | AgentFlow (Python ADK) |
|:---|:---|:---|
| 默认行为 | 纯截断（LIMIT 16 条），不生成摘要 | 纯截断（每次 LLM 调用前 slice），不生成摘要 |
| 可开启功能 | LLM 增量摘要（summaryEnabled=true） | 无（文档明确说"没有实现真正的上下文压缩"） |
| 截断层 | `loadHistory` DB LIMIT 16 | `before_model_callback` 内存 slice |
| 截断粒度 | 消息条数（轮数 × 2） | 轮数（historyRound + 1） |
| 摘要方式 | 增量合并（旧摘要 + 增量 → 新摘要） | — |
| 摘要窗口 | 半窗重叠（每次覆盖窗口前一半） | — |
| 摘要存储 | MySQL `t_conversation_summary` 独立表 | — |
| 摘要触发 | 异步（CompletableFuture.runAsync） | — |
| 注入位置 | `<conversation-summary>` 标签，放在消息列表首位 | — |
| token 硬上限 | 无（依赖轮数 × 消息长度预估） | 30K token 整轮丢弃 |
| 压缩 LLM 参数 | temperature=0.3, topP=0.9, thinking=false | — |

---

## 13. 多轮对话中的噪声去除：不相关的问题怎么办？

> 面试问题：**多轮对话中，如果有一些问题不相关，怎么去除噪声？**

### 13.1 问题本质

多轮历史中混入了与当前 query 无关的轮次——话题切换、闲聊、上一个任务的残留。如果全部拼进上下文：

- 稀释当前意图：意图识别/改写被无关信息干扰，把当前 query 引到错误方向
- 浪费 token：无关轮次占用上下文窗口，挤掉真正相关的检索结果
- 误导检索：改写阶段若参考了无关轮次，可能把不相关的实体写进改写后的 query

### 13.2 Ragent 现状：三道防线（全部有代码定位）

#### 防线 ①：改写窗口截断——噪声挡在改写阶段之外

`MultiQuestionRewriteService.buildRewriteRequest()` L174-180：

```java
// 只保留最近 1-2 轮的 User 和 Assistant 消息（最多 4 条）
// 显式过滤掉 System 摘要，避免 Token 浪费
List<ChatMessage> recentHistory = history.stream()
    .filter(msg -> msg.getRole() == ChatMessage.Role.USER
            || msg.getRole() == ChatMessage.Role.ASSISTANT)   // ← 摘要被滤掉
    .skip(Math.max(0, history.size() - 4))                     // ← 只留最近 4 条
    .toList();
messages.addAll(recentHistory);
messages.add(ChatMessage.user(question));
```

效果：改写 LLM 永远只看到最近 2 轮，2 轮之前的噪声（包括摘要）不参与指代消解。**这是 Ragent 最主要、成本最低的降噪手段。**

#### 防线 ②：意图树不带历史——话题切换天然免疫

`IntentResolver` 不接收历史，意图识别只基于改写后的 query 做分类。

效果：用户从"年假政策"切到"报销流程"时，当前 query 直接命中新的意图叶子，上一话题的意图节点不会干扰本次检索通道选择。

#### 防线 ③：上下文窗口截断——远旧噪声进不了上下文

`JdbcConversationMemoryStore.loadHistory()` 用 `LIMIT historyKeepTurns*2`（=16 条消息 / 8 轮），更早的轮次在 DB 查询层直接不加载。

### 13.3 现状的局限：窗口是"最近的"，不是"最相关的"

三道防线本质都是 **时间窗口**，不是 **语义过滤**。存在的问题：

1. **最近 ≠ 相关**：用户可能刚闲聊两句（"今天天气不错"），这 2 轮会被带进改写；而真正相关的话题可能在 5 轮前
2. **话题切换是隐式的**：靠意图树落到新叶子实现，没有显式"当前 query 与历史无关 → 直接清空历史"的逻辑
3. **不做相关度打分**：没有对历史轮次逐条计算与当前 query 的相似度

**Ragent 为什么这么做**：
- 成本敏感：每轮做相关度计算（embedding 或 LLM）增加延迟和调用
- 场景匹配：KB 问答是"一问一答"模式，跨轮依赖弱，指代消解用 2 轮窗口基本够
- 摘要本身就是降噪：摘要只记"话题+状态+约束"，明确禁止记答案，天然过滤掉对话过程噪声

### 13.4 进阶方案：如果要支持长对话 / 深度分析

按成本从低到高：

| 方案 | 做法 | 特点 |
|:---|:---|:---|
| ① 自包含判定 | 先判当前 query 是否自包含（无指代/无省略/新意图）→ 是则直接不带历史 | 最便宜，从根上消除噪声 |
| ② 向量相关度过滤 | 以当前 query 为 query，对每条历史轮次做 embedding 余弦打分，保留超阈值轮次 | 可复用已有混合检索 + rerank 设施，便宜 |
| ③ BM25 过滤 | 强实体型 query 用关键词命中过滤历史 | 最快，适合查代码/查表 |
| ④ LLM 判断 | 让 LLM 从历史里挑相关轮次 / 抽相关事实 | 最准但贵，适合少量关键历史 |
| ⑤ 话题感知 | 每轮打话题标签，检测话题切换 → 历史整体不参与 | 语义级去噪 |
| ⑥ 结构化记忆 | 每轮只提取"结论/关键信息"存记忆，原文不参与上下文 | 噪声根本不进上下文 |

工程上建议 **② + ⑤ 组合**：用话题标签快速判断是否切换（便宜），切换则清空历史；未切换再对窗口内轮次做向量相关度打分（精确过滤）。粒度可细到"句"——某轮只有一句相关，就只保留那一句。

### 13.5 关键联动注意点

1. **顺序问题（最重要）**：过滤历史必须在改写消解 **之前或联动**。如果先把某轮历史删了，下一轮"它怎么用？"的指代就断了。正确姿势：先粗过滤（话题判断）→ 改写时把被忽略的轮次明确告知 LLM，或改写只基于过滤后的历史
2. **阈值调优**：相关度阈值太高 → 丢上下文（召回率崩），太低 → 去不掉噪声。要靠 Trace 的归因日志观测被过滤的轮次，迭代调参
3. **成本权衡**：相关度过滤每轮多一次检索/调用。Ragent 当初选"改写合并消解"（把消解和改写合并成一次 LLM 调用）就是为了不增加独立 LLM 调用——做增强时也要保持这个原则

### 13.6 面试话术

> "Ragent 目前用三层窗口控制多轮噪声：改写只带最近 2 轮（并显式滤掉摘要）、意图识别不拼历史、上下文只加载最近 8 轮。这是成本最低的方案，适合一问一答的 KB 问答。它的局限是没有按语义相关度过滤——窗口是'最近的'不是'最相关的'，最近 2 轮可能恰恰是闲聊噪声。如果要支持长对话/深度分析，我会加一层以当前 query 为基准的历史相关度打分（复用已有的混合检索 + rerank 设施，不新增加成本），并且在改写消解之前做粗过滤，避免破坏'它怎么用'这类指代。"

---

## 14. AgentFlow 短期记忆摘要与上下文压缩的关系

> 面试问题：**AgentFlow 的短期记忆摘要和上下文压缩有关吗？**

### 14.1 PA 记忆体系三层架构

PA 文档 `美团 Agent  Q&A.md` L3188-3222 明确分为三层：

| 层 | 存储 | 内容 | 控制手段 |
|:---|:---|:---|:---|
| ① 短期记忆 | ADK Session Event（Redis） | 当前会话的完整对话轮次 | `historyRound`（默认 3 轮）+ 30K token 硬上限 |
| ② 长期记忆 | Milvus（Mem0 SDK） | 跨会话关键信息（用户偏好、业务上下文） | 向量检索 + similarity 阈值 |
| ③ 对话摘要 | `ChatItem.moduleSummaryData`（MySQL） | 长对话的 LLM 语义摘要 | token 超限时 `ChatContextFilter` 触发 `AISummaryService` |

### 14.2 关键澄清：短期记忆本身没有摘要

"短期记忆摘要"这个说法容易混淆。需要拆开看：

- **短期记忆**（第 ① 层）是 Redis 里的 ADK Session Event，存的是完整对话轮次的**原文**。不做摘要、不做压缩。
- **对话摘要**（第 ③ 层）是 Java agentflow-server 侧的 `AISummaryService`，它**从短期记忆的对话历史中提炼 LLM 摘要**，写入 MySQL 的 `ChatItem.moduleSummaryData` 字段。

两者是**独立的两层**：短期记忆存原料，对话摘要存提炼物。

### 14.3 对话摘要就是上下文压缩的实现

文档 L4292-4300 原文：

> **手段 5：摘要压缩减少上下文丢失**
> 当对话历史过长时，如果不做摘要直接截断，会丢失关键上下文，导致 LLM 用自身知识补充。摘要是**语义压缩而非截断**，保留关键信息。

触发链路（L3236-3239）：

```
ChatContextFilter 检测 token 超限
  → AISummaryService.getAISummary() 调用 LLM 生成摘要
  → systemPrompt + "\n[history]\n" + summaryPrompt
  → 摘要替换 system prompt 中的历史部分
```

这就是 **上下文压缩**——对话摘要就是压缩的实现。注入方式是用摘要替换原文，而非在原文前面追加。

### 14.4 必须辩证看：PA 到底有没有压缩？

PA 文档**多次强调"PA 没有实现真正的上下文压缩"**（L1813 / 1857 / 1863 / 2025）：

> PA 的上下文管理用的是"截断"而不是"压缩"策略。两层截断：轮次级别在每次 LLM 调用前保留 historyRound + 1 轮对话，会话级别在总 token 超过 30K 时丢弃整轮 invocation。

这是指 **Python ADK 主链路**——`before_model_callback` 轮次截断 + 30K token 整轮丢弃。用截断不用压缩。

但 **Java agentflow-server 侧**有一个独立的 `AISummaryService`，在 Java 的 `ChatContextFilter` 层工作——它做的就是真正的 LLM 语义摘要。

### 14.4.1 重点澄清：PA 的两个独立机制，不要搞混

PA 有**两个完全不同、各管各的机制**，它们运行在**不同的执行路径**里，不会在同一个请求中串行叠加：

| | 机制一：轮次截断 | 机制二：Token 管理 |
|:---|:---|:---|
| 组件 | `before_model_callback`（Python ADK） | `ChatContextFilter`（Java agentflow-server） |
| 运行路径 | **Agent 模式**（ADK 执行器循环） | **ChatNode 模式**（Java 编排流水线） |
| 比较对象 | **轮数** vs `historyRound + 1` | **token 量** vs `maxTokens` |
| 触发条件 | 每次 LLM 调用前，无条件 | `getChatMessages()` 组装消息时触发 |
| 作用 | 保证 LLM 只看到最近 4 轮 | 保证总 token 不超模型上限 |

**二者不是串行的，而是两条独立的执行路径**：

```
                    用户请求
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
    ChatNode 模式               Agent 模式
    (KB 问答)                   (带工具调用)
          │                         │
    Java agentflow-server       Python ADK executor
          │                         │
    ChatCompletionServiceImpl    ADK 执行器循环
      .execute()                    │
          │                    before_model_callback ← 只走这个
    Step 5: getChatMessages()     │
      → ChatContextFilter         LLM 调用
        (快速路径 + JTokkit)       │
          │                    工具调用 / 下一个循环
    Step 6: aiChat() → LLM
```

**关于 `ChatContextFilter` 的快速路径**——它只在 ChatNode 模式下生效：

```java
// ChatContextFilter.filterMessages() — Java agentflow-server
// maxTokens = 模型上下文窗口（如 GPT-4 的 128K）
// 比较对象 = 整个 prompt 的估算长度
//           = systemPrompt + chatHistories + lastSummary + question + quoteText
if (rawTextLen < maxTokens * 0.5) return;   // 粗筛通过，跳过 JTokkit 精确计数
```

这里 `maxTokens` 是**模型的上下文窗口上限**（动态传入，取决于使用的模型）。`× 0.5` 的安全余量确保最坏情况下（1 字符 ≈ 1 token），仍有 50% 窗口余量给 system prompt + 工具声明 + 回答。

**跟"保留 4 轮"完全无关**——快速路径比较的是整个 prompt 的估算长度，不是在检查"4 轮是否太长"。

**准确结论**：

| 侧 | 组件 | 策略 | 是压缩吗？ |
|:---|:---|:---|:---|
| Python ADK 主链路 | `before_model_callback` + `check_session_token_count` | 轮次截断 + 整轮丢弃 | 不是，是截断 |
| Java agentflow-server | `AISummaryService` → `ChatItem.moduleSummaryData` | token 超限 → LLM 摘要 → 替换原文 | 是，语义压缩 |

### 14.5 与 Ragent 的同构对比

```plaintext
Ragent                              PA (Java 侧)
──────                              ────────────
短期记忆: t_message (MySQL)        短期记忆: ADK Session Event (Redis)
摘要产物: t_conversation_summary   摘要产物: ChatItem.moduleSummaryData (MySQL)
触发条件: 轮数 ≥ 9 + 半窗重叠      触发条件: token 超限 (ChatContextFilter)
摘要模式: 增量合并(旧摘要+增量)     摘要模式: 一次性 LLM 摘要
执行方式: 异步 (CompletableFuture)  执行方式: 同步 (ChatContextFilter 流程内)
注入方式: system 消息前置           注入方式: 替换 system prompt 中的历史部分
```

**两个系统在回答同一个问题**：上下文膨胀时，怎么用摘要替代原文。Ragent 做增量（每次只摘要窗口外的增量段），PA 做全量（token 超限时一次性摘要）。

---

## 15. 两种方案场景对比

### 15.1 核心差异归纳

| 维度 | Ragent | AgentFlow (Python) | AgentFlow (Java 侧) |
|:---|:---|:---|:---|
| 策略本质 | 轮次截断(默认) + 增量摘要(可选) | 轮次截断 + 会话级整轮丢弃 | token 超限 → 一次性 LLM 摘要 |
| 触发时机 | 每轮 assistant 回复后（异步） | 每次 LLM 调用前 + Session token 检查 | token 超限时（同步） |
| 压缩粒度 | 增量：只摘要窗口外的消息段 | 无压缩 | 全量：整个历史一起摘要 |
| 压缩后的原文 | 保留最近 8 轮原文 | 保留最近 4 轮原文 | 摘要替换全部历史原文 |
| 旧摘要参与 | 参与合并（去重增量更新） | — | —（一次性，无增量） |
| 默认是否开启 | 否（summaryEnabled=false） | 截断永远生效 | 随 ChatContextFilter 开启 |
| 额外 LLM 调用 | 每 2 轮左右 1 次 | 0 | 每次触发 1 次 |
| 存储 | MySQL 独立表 | —（无摘要产物） | MySQL ChatItem 字段 |
| 架构复杂度 | 中等（增量窗口 + 分布式锁 + 线程池） | 低（内存 slice + Redis token 计数） | 中等（依赖 Java 侧的 ChatContextFilter） |

### 15.2 场景适用性

| 场景 | 适合哪个方案 | 原因 |
|:---|:---|:---|
| 一问一答 KB 问答 | Ragent 默认(截断) / PA 默认(截断) | 跨轮依赖弱，截断就够了，零额外成本 |
| 长会话深度分析（10+ 轮） | Ragent 开摘要 | 增量摘要保留关键约束，不丢早期信息 |
| Agent 多步推理（工具调用密集） | PA Python 主链路 | 需要保留 `function_call/function_response` 配对，截断比摘要更安全 |
| 超长上下文（50+ 轮） | Ragent 开摘要 + 调小 historyKeepTurns | 增量模式累积不爆，原文窗口可收窄 |
| 严格成本控制 | PA 截断 / Ragent 截断 | 零额外 LLM 调用 |
| 微服务多系统协调 | PA Java 侧 AISummaryService | Java agentflow-server 已有完整链路，复用而不是重建 |

### 15.3 为什么 Ragent 选了增量、PA 选了全量？

**Ragent 的场景**：
- KB 问答为主，对话轮数可能非常多（用户不断追问不同知识点）
- 不能等 token 超限才压——摘要响应不及时，用户体验差
- 增量压更平滑，每次压一小段，摘要成本摊薄到每 2 轮

**PA 的场景**（Python ADK 侧）：
- Agent 多步推理，每轮工具调用重、token 消耗快
- 截断更安全——不破坏 `function_call/function_response` 配对
- 大多场景轮数短，截断够用

**PA 的场景**（Java 侧）：
- agentflow-server 管的是企业编排层（Spring Boot + MyBatis），对话持久化在 MySQL
- token 超限是"兜底"事件，不是常态
- 一次性摘要实现简单，不需要维护增量窗口状态

---

## 16. 上下文压缩的痛点分析

### 痛点 1：信息丢失不可逆——截断丢工具调用配对

**问题**（PA Q15 文档 L1872-1890）：

截断用 user 消息做分割点，但工具调用（`function_call` + `function_response`）可能跨越截断边界：

```
截断前:  [user, assistant, user, assistant+tool_call, tool_result, ...user]
                                                       ↑ 截断边界
截断后:  [tool_result, user, ...]
          ↑ 孤立的 tool_result，对应的 tool_call 在边界外
```

LLM 看到孤立的 `tool_result` 和 `function_response` 但不清楚这是哪个工具的、什么参数触发的结果，导致**重复调用工具**或**错误解读数据**。

**Ragent 没有这个问题**——因为 Ragent 是纯检索 Agent，不涉及 function_call。但如果将来做工具调用型 Agent，要从"按 user 消息切割"改为"按 invocation 切割"。

### 痛点 2：摘要的"去信息"是双刃剑

摘要 Prompt 明确要求 "绝对禁止记录具体答案"——这是为了避免摘要与最新检索结果冲突。但副作用是：

- 用户明确说过的约束条件（"只看 7 月的数据"）可能被摘要丢失 → 下一轮 LLM 不知道这个约束
- 摘要长度严格限 200 字符，多话题对话可能被截断成 "用户咨询了【话题1】（已解答）、【话题2】（已解答）、【话题3】..."
  最后一两个话题被截掉

**缓解**：Ragent 的 8 轮保留窗口兜底——最近的约束在原文里能找到。但 8 轮之前的约束就只能依赖摘要的保真度。

### 痛点 3：时序一致性——异步压缩的一拍滞后

Ragent 摘要在后台异步跑，本轮压缩、下轮才能用。如果用户秒回或 LLM 超时，可能存在"第 10 轮还看不到第 9 轮的摘要"。

**缓解**：8 轮原文兜底，滞后一拍不影响回答质量。但如果 `summaryEnabled=true` 且 `historyKeepTurns` 设得特别小（比如 2 轮），滞后一拍可能导致关键老信息完全丢失。

### 痛点 4：多轮话题切换时摘要污染

```
第 1-5 轮: 聊年假政策（摘要: "咨询了年假计算规则"）
第 6-8 轮: 聊报销流程（摘要: "咨询了年假计算规则（已解答）、报销流程（已解答）"）
第 9 轮: 回到年假 → 改写阶段从摘要看到"年假（已解答）"，可能不再检索
```

摘要的状态标注（"已解答"）在第 9 轮不一定还成立——知识库可能已更新、模型可能有更优解。摘要的"已解答"标记可能误导 LLM 不去重新查。

**缓解**：摘要注入时带 `<conversation-summary>` 标签 + 改写只带 2 轮原文（不带摘要）。Ragent 改写阶段 `filter(msg.role == USER || msg.role == ASSISTANT)` 显式滤掉了摘要。

### 痛点 5：成本 vs 收益 的不可见性

摘要成本包括：
- 额外 LLM 调用（每 2 轮 1 次，每次约 2K input + 200 output tokens）
- 分布式锁开销（Redisson）
- 线程池资源占用（memorySummaryExecutor）

但收益（"避免上下文丢失导致重复回答"）很难量化——没有指标告诉你是"摘要有用"还是"关了摘要也一样"。

**实际取舍**：
- Ragent 默认 `summaryEnabled=false`，把问题交给窗口截断
- 只有明确需要长对话记忆的用户才手动开启

### 痛点 6：增加系统可观测性盲区

摘要生成是异步的、后台的，失败后静默降级（`exceptionally(ex -> return null)`）。如果摘要 LLM 持续失败，系统不会有告警——只是长对话体验逐渐变差，排查链路漫长。

**需要补的**：摘要失败率指标 + 摘要内容长度告警（太短说明 LLM 产生错误输出）。

### 痛点 7：安全边界——摘要被注入恶意内容

摘要 Prompt 里的防污染指令（L192-195）：
```
"历史摘要（仅用于合并去重，不得作为事实新增来源；若与本轮对话冲突，以本轮对话为准）"
```

这只是在 Prompt 层面约束——如果恶意用户在多轮对话中精心构造内容、让摘要提取出误导性关键信息，下一轮可能被注入上下文。

**Ragent 的防御**：改写阶段显式过滤掉摘要（不参与指代消解），意图识别不带历史，降低被污染摘要影响检索通道的风险。

### 痛点 8：分布式锁的竞争与阻塞

Ragent 用 Redisson `tryLock()`（非阻塞），拿不到锁就跳过——这在并发场景下可能导致"应该压缩但没压缩"。

如果同一会话的两个请求几乎同时到达：
- 请求 A 拿到锁，开始压缩
- 请求 B 拿不到锁，直接返回 → 本次不压缩
- 请求 B 下次才可能触发 → 最多多等一轮，不致命

但如果 `historyKeepTurns` 很小（比如 2 轮）、用户回复很快——可能连续 3-4 轮都因为锁冲突跳过压缩。锁冲突累积下，窗口外消息被永久丢弃而无摘要。

---

## 17. 痛点总结与解决思路一览

| 痛点 | 严重程度 | 当前缓解手段 | 改进方向 |
|:---|:---|:---|:---|
| ① 截断丢工具调用配对 | 中（仅 PA 有） | — | 按 invocation 切割而非 user 消息 |
| ② 摘要去信息双刃剑 | 高 | 8 轮原文兜底 | 约束条件提取独立于话题摘要 |
| ③ 异步滞后 | 低 | 8 轮原文兜底 | 可选同步模式（flag 控制） |
| ④ 话题切换摘要污染 | 中 | 改写显式滤掉摘要 | 话题标签 + 话题切换时重置摘要 |
| ⑤ 成本收益不可见 | 中 | 默认关闭 | 加摘要命中率/有效性指标 |
| ⑥ 可观测性盲区 | 中 | — | 失败率告警 + 摘要长度监控 |
| ⑦ 安全——摘要注入 | 低 | 改写滤摘要 + 防污染 Prompt | 摘要内容校验/Sanitize |
| ⑧ 分布式锁冲突 | 低 | tryLock + 最多多等一轮 | 解锁后补偿检查（如需要） |

---

## 18. Claude Code 的上下文压缩与记忆体系（业界前沿参考）

> 研究 Claude Code 如何解决同样的问题，提取可借鉴的设计思想。

### 18.1 五层压缩防线（逐层递进，前四层零 API 调用）

**这是 Claude Code 设计的精髓：前四层压缩不调用任何 LLM，只有最后一层 auto-compact 才触发 LLM 摘要。**

| 层 | 实现 | API 调用？ | 触发 | 作用 |
|:---|:---|:---|:---|:---|
| ① Budget Reduction | token 预算管理 | **零** | 每轮 | 提前丢弃可重取内容（如旧的工具调用结果），保持 token 在预算线以下 |
| ② Snip-compact | history snipping | **零** | 中等阈值 | 裁剪/移除旧上下文段，不需要完整重摘要——纯文本操作，延迟为零 |
| ③ Micro-compact | `microCompact.ts` | **零** | 每次迭代 | 清除旧的工具调用结果（文件读取/shell 输出/grep/web 搜索），替换为 `[Old tool result content cleared]`，去除 ANSI 码、截断冗长输出。**这些内容可随时重取，丢弃不丢信息。** |
| ④ Context Collapse | `contextCollapse/` | **零** | 长期运行 | 渐进式地把旧消息转化为已提交摘要或折叠状态结构——结构性压缩，非被动触发 |
| ⑤ Auto-compact | `autoCompact.ts` | **一次 LLM** | token 达到有效窗口 − 13K 缓冲区时（约 93% 利用率） | 唯一触发 LLM 的层——生成**结构化九段摘要**，用摘要替换全部历史 |

此外还有一个 **reactive compact**（`reactiveCompact.ts`）作为安全网：只有 API 返回 `413 prompt_too_long` 时才触发，仅重试一次。

**设计哲学**：能用规则解决的不用 LLM。前四层在"每条消息"粒度上自动做预算裁剪和内容清除，把触发 LLM 压缩的时机尽量往后推。这是 Ragent/PA 最应该吸收的核心思想——**不是"摘要替代截断"，而是"先尽可能零成本去噪，最后才调 LLM"**。

### 18.2 Auto-compact：结构化九段摘要，不是自由文本

Claude Code 的 `/compact` 不是自由文本摘要，而是**按固定结构输出**：

| 段 | 名称 | 内容 |
|:---|:---|:---|
| 1 | Task & Intent | 当前正在做什么、目标是什么 |
| 2 | Current State | 进行到哪一步了、哪些文件被修改了 |
| 3 | Key Decisions | 做了哪些关键决策及原因 |
| 4 | Constraints & Requirements | 必须遵守的约束条件 |
| 5 | Files Modified/Created | 操作过的文件清单 |
| 6 | Recent User Messages | 最近的用户消息原文保留（意图可追溯） |
| 7 | Errors & Blockers | 遇到了什么问题、怎么解决的 |
| 8 | Next Steps | 接下来要做什么 |
| 9 | Open Questions | 尚未解决的问题 |

**为什么结构化很重要**：

1. **可解析**：每段独立，后续上下文组装时可以按需取用（比如只有 "Constraints" 段需要注入，其他段可降级）
2. **一致性**：不管谁写的摘要，结构相同，LLM 在下一轮能快速定位关键信息
3. **防止遗漏**：每段是一个明确的 check 项，摘要 LLM 不太可能漏掉整段
4. **可操控**：用户可以用 `/compact focus on X` 来定向增强某段的详细程度

对比 Ragent 的摘要（`conversation-summary.st`）：`"用户咨询了【话题1】（状态）、【话题2】（状态）。约束：xxx。关键词：xxx"`——本质也是结构化，但只有 3 个维度（话题/状态/约束），远不如 Claude Code 的九段丰富。

### 18.3 记忆系统：四级文件层级 + T0-T3 社区方案

#### 内置四级层级

| 级 | 路径 | 作用域 | 共享？ |
|:---|:---|:---|:---|
| Managed（最低） | `/etc/claude-code/CLAUDE.md` | 组织全员 | 管理员强制 |
| User | `~/.claude/CLAUDE.md` + `~/.claude/rules/*.md` | 所有项目、私有 | 否 |
| Project | `CLAUDE.md`、`.claude/CLAUDE.md`、`.claude/rules/*.md` | 团队共享、入 git | 是 |
| Local（最高） | `CLAUDE.local.md` | 个人项目覆盖 | 否（gitignored） |

**加载顺序 = 优先级**：从文件系统根目录往工作目录走，后加载 → 位置靠后 → 优先级更高。

#### 社区 6-Tier（memory-hygiene）

| 阶 | 文件 | token 预算 | 何时加载 | 内容 |
|:---|:---|:---|:---|:---|
| T0 | `axioms.md` | ≤12 条 | 每次对话 | 通用 + 角色行为约束 |
| T0 | `CLAUDE.md` | ~70 行 | 每次对话 | 工作流规则、检索策略 |
| T1 | `MEMORY.md` | ~40-80 行（硬上限 200 行/25KB） | 每次对话 | 话题文件索引 |
| T1.5 | `.claude/rules/phase-*.md` | ~5 条规则 | 文件匹配时 | `paths:` glob 门控规则 |
| T2 | 话题文件 | ~50 行 | 按需 | 按话题上下文 |
| T3 | 归档（`lessons.md`） | 不限制 | **grep only** | 完整历史 |

结果：从 9500 token 降至 1760 token——**81% 节省**。

### 18.4 Subagents：上下文隔离的架构级解法

```
Parent Agent                          Subagent
───────────                          ────────
messages=[...完整历史...]             messages=[]  ← 全新空的！
                                    │
tool: Task(prompt="审查 PR #42")     │   while tool_use:
  ─────────────────────────────────→│     read_file(), grep(), diff()
                                    │     上下文爆炸式增长
                                    │
  result = "发现 3 个问题: ..."  ←──│   return 最后一条 text（仅摘要！）
```

每个子 Agent 获得全新 `messages=[]`，内部所有文件读取、shell 输出、栈跟踪在完成时**全部丢弃**。父进程只接收最后一条文本消息。

### 18.5 Ragent/PA 真正可借鉴的三点

#### 借鉴 ①：Micro-compact 思路——清除可重取的检索结果

Claude Code 的核心洞察：**工具调用结果可随时重取，丢弃不丢信息。**

对应到 Ragent：多轮对话中，第 3 轮的检索结果在第 10 轮大概率已无用——这些检索结果占用的 token 完全可以清除。因为 Ragent 每次对话都会重新检索，旧的检索结果本来就是"可随时重取"的内容。

**方案**：在每轮结束时，自动清除之前注入上下文的知识库检索结果/MCP 数据块——就像 Claude Code 清除 `[Old tool result content cleared]`。下一轮会重新检索，不依赖旧结果。

#### 借鉴 ②：结构化摘要——九段式替代自由文本

Ragent 当前摘要只有 3 个维度（话题/状态/约束），可以扩展为：

| Ragent 可加的结构段 | 内容 |
|:---|:---|
| 对话目标 | 用户最终想解决什么问题 |
| 约束条件 | 时间范围、部门、预算等（已有） |
| 关键决策点 | 用户在对话中做的选择 |
| 未解决问题 | 哪些问题还没答案 |
| 话题清单 | 已咨询的知识点（已有） |

**不是摘要内容更多**，而是**用固定字段让摘要 LLM 不会漏掉关键信息**。

#### 借鉴 ③：缓存安全前缀——压缩后维护 Prompt Cache 连续性

Claude Code 在压缩后把 CLAUDE.md/system prompt/auto memory 等**稳定前缀**重新注入到消息列表首位——保持 prompt cache 前缀不变，压缩只影响可变部分。

对应到 Ragent：如果摘要的 `summary-wrapper` 标签格式固定，作为 system prompt 的稳定前缀注入，压缩前后 `system prompt 前缀` 不变 → prompt cache 命中率不受压缩影响。

这是 Ragent 目前可以低成本做到的点——因为 `decorateIfNeeded` 输出固定格式的 `<conversation-summary>`，本身就是 cache-safe 的。

### 18.6 关键澄清：关于"3 次失败熔断"

社区有观察到 `autoCompact.ts` 中对连续 auto-compact 失败次数的统计与限制行为，有分析文章据此归纳出"连续 3 次失败熔断"的工程实践模式。这**不是 Anthropic 官方公布的结论**，但作为工程实践的防御性设计思路值得参考——其核心思想是：压缩本身也有成本，如果连续失败说明压缩策略可能不适合当前场景，不如停掉以止损。

---

## 19. 三种方案的终局对比

| 维度 | Ragent | AgentFlow (PA) | Claude Code |
|:---|:---|:---|:---|
| 压缩层数 | 2 层（截断 + 增量摘要） | 2+1 层（截断×2 + Java 侧摘要） | 5 层（Budget→Snip→Micro→Collapse→Auto） |
| 零 API 调用层 | 1 层（截断） | 2 层（截断×2） | **4 层（前四层零 LLM）** ← 设计精髓 |
| 压缩触发 | 轮数 ≥ 9 且半窗滑出（异步） | token 超限 / 30K token | token ~93% 利用率 + `/compact` 手动 |
| 摘要模式 | 增量合并（旧摘要 + 增量 → 新摘要） | Java 侧一次性全量 | **结构化九段替换**全部历史 |
| 压缩后原文保留 | 最近 8 轮 | 最近 4 轮 | 保留最近用户消息原文 |
| 记忆分级 | 3 层隐式 | 3 层显式（短期/长期/摘要） | 4 级文件 + T0-T3 |
| 上下文隔离 | 无 | sub-agent transfer | subagent spawn（空上下文） |
| 缓存感知 | 无 | 无 | 压缩后重新注入稳定前缀，保持 cache 连续性 |
| 核心设计哲学 | 成本优先、窗口兜底 | 简单优先、截断为主 | **零 API 调用优先**，LLM 压缩是最后手段 |

---

## 20. 面试加分回答模板

如果被问到 "你们项目的上下文压缩相比 Claude Code 怎么样？"：

> "Claude Code 的压缩策略最值得学的不是它的 LLM 摘要，而是它的前四层。Budget Reduction → Snip → Micro-compact → Context Collapse，这四层**全都不调用 LLM**，纯工程操作就把 token 控制住了。它的核心洞察是：工具调用结果是可随时重取的，丢弃不丢信息。对应到 Ragent，我们的检索结果也是可随时重取的——每轮都重新检索，旧的检索结果不需要留在上下文中。
>
> 我们目前借鉴了这个思路，规划在每轮结束时自动清除上轮的检索结果块，这对应 Claude Code 的 micro-compact 层。另外它的结构化九段摘要也是我们想学的——不是让 LLM 自由发挥写摘要，而是固定九个字段让 LLM 填空，这样不会漏维度、后续也更容易按需取用各段。
>
> 还有一个容易忽略的点是缓存安全前缀——压缩后要重新注入稳定的 system prompt 前缀，保证 prompt cache 不因压缩而失效。这对调用成本敏感的场景非常重要。"

---

## 21. 四种方案的横向对比：各自解决什么、带来什么、互相学什么

### 21.1 各自方案一览

| | ChatNode 模式 | Agent 模式 | Ragent | Claude Code |
|:---|:---|:---|:---|:---|
| 运行环境 | Java agentflow-server | Python ADK executor | Java Spring Boot | CLI / Node.js |
| 压缩策略 | 快速路径 → 截断 → 摘要（三级） | 轮次截断 + 30K 整轮丢弃 | 轮次截断(默认) + 增量摘要(可选) | Budget→Snip→Micro→Collapse→Auto（五层） |
| 触发方式 | 消息组装时同步触发 | 每次 LLM 调用前无条件截断 + Session 级兜底 | 每轮 assistant 回复后异步触发摘要 | 每次迭代零 API 清除 + token 93% 自动摘要 |
| 压缩粒度 | 整个 prompt 的 token 量 | 轮次 + invocation 单位 | 轮数窗口 + 消息 ID 区间 | 逐条消息清除 + 全文替换 |

### 21.2 各解决了什么问题

#### ChatNode 模式（Java）

**解决的核心问题**：KB 问答场景下，长对话 + 大量检索引用导致 prompt 超过模型上下文窗口。

为什么选这套方案：
- 检索引用（quoteText）是不可预测的变量——用户问的知识点不同，引用量差异巨大
- 必须 token 级别精确控制（JTokkit），不能用轮次估算
- 摘要的模式是"替换历史原文"，释放 token 给检索引用

#### Agent 模式（Python ADK）

**解决的核心问题**：Agent 多步推理中，`function_call/function_response` 配对不能被拆散。

为什么选这套方案：
- 工具调用的配对完整性比 token 精确控制更重要——丢一个 function_call 但不丢对应的 function_response，LLM 会混乱
- 按 user 消息切割是最简单的方式，但代价是可能切断工具调用对（已知问题 Q15）
- 30K token 整轮丢弃确保内存不炸

#### Ragent

**解决的核心问题**：KB 问答长会话中，早期关键约束（时间范围、预算等）不因截断丢失。

为什么选这套方案：
- 增量摘要只压"窗口外的增量段"，不重复摘要已有内容
- 半窗重叠避免每轮都调 LLM
- 默认关闭摘要，把成本控制交给用户选择

#### Claude Code

**解决的核心问题**：编程 Agent 的上下文膨胀速度极快（一次 grep 可能上数千行），且很多内容是可随时重取的。

为什么选这套方案：
- 核心洞察：**工具调用结果可随时重取，丢弃不丢信息**——前四层全围绕这一点，零 API 调用
- 子 Agent 上下文隔离从架构层面解决膨胀——脏活交给子进程，只回传摘要
- 1M token 窗口意味着"什么都不做"的成本很高——缓存命中后每轮多占用 token 都直接反映在账单上

### 21.3 各带来了什么问题

#### ChatNode 模式的问题

| 问题 | 原因 | 影响 |
|:---|:---|:---|
| 快速路径估算不准 | `rawTextLen < maxTokens × 0.5` 用字符数估算 token，中文场景（1 字 ≈ 2 token）可能误判 | 中文长文可能绕过快速路径但 token 实际已超 |
| 全量摘要成本高 | 每次触发都是完整历史一次性摘要 | token 超限频率越高，摘要 LLM 调用越频繁 |
| 和 Agent 模式割裂 | 两套机制独立维护，参数、策略不一致 | 你无法在 ChatNode 里用 before_model_callback 的轮次截断，反过来也不行 |
| 阻塞主流程 | 摘要 LLM 同步调用，用户等待 | 摘要超时直接影响回复延迟 |
| 摘要替换全量历史 | 没有增量机制，每次重新摘要全部内容 | 历史越长越浪费——99% 内容和上次一样也全量重新摘要 |

#### Agent 模式的问题

| 问题 | 原因 | 影响 |
|:---|:---|:---|
| 切断工具调用对 | 按 user 消息切割，function_call/function_response 跨边界 | LLM 看到孤立的 tool_result，可能重复调用工具或错误解读 |
| 轮次截断不关心 token | 4 轮短对话也可能超 token（单轮检索了大量数据） | 没有快速路径兜底，可能直接超限报错 |
| 无摘要能力 | Python ADK 侧不做摘要 | 早期关键决策信息在 4 轮后就永久丢失，只能靠长期记忆（Mem0）补 |
| 30K 整轮丢弃太粗暴 | 整轮 invocation 一起丢 | 如果某轮 invocation 耗时很长、结论很关键，被整体丢弃损失巨大 |

#### Ragent 的问题

| 问题 | 原因 | 影响 |
|:---|:---|:---|
| 摘要默认关闭 | `summaryEnabled=false` | 不了解这个配置的用户可能永远用不上摘要 |
| 异步滞后一拍 | `CompletableFuture.runAsync` | 用户秒回时本轮加载不到最新摘要 |
| 无 micro-compact | 旧检索结果一直占据上下文直到超出 8 轮窗口 | 第 3 轮的检索结果在第 10 轮仍占 token，且大概率无用 |
| 摘要只能增量、不能全量 | 依赖 afterId~cutoffId 窗口 | 如果摘要锚点错位（如消息被删除），增量链路断裂 |
| 无 token 级别控制 | 只管轮数，不管 token | 8 轮如果每轮都很长（大量检索结果），token 可能远超预期 |

#### Claude Code 的问题

| 问题 | 原因 | 影响 |
|:---|:---|:---|
| auto-compact 阻塞会话 | 只此一层需要 LLM 调用，但它是同步 block 的 | 大项目 compact 耗时 10-30 秒，用户干等 |
| 冷缓存子 Agent | 每个子 Agent 全新 `messages=[]`，prompt cache 从零开始 | trivial 任务 delegate 反而更贵 |
| 摘要丢硬约束 | 结构化九段仍然依赖 LLM 不遗漏 | 关键数值、硬约束仍可能被摘要丢失，社区方案（Mem0/DAG）在补这个 |
| 复杂度高 | 五层压缩 + 子 Agent + T0-T3 记忆 | 学习成本、调试成本远高于 Ragent/PA |

### 21.4 互相借鉴

#### ChatNode → 其他三者

| 借鉴点 | 谁需要 | 怎么做 |
|:---|:---|:---|
| JTokkit 精确 token 计数 + 快速路径 | Ragent、Agent 模式 | Ragent 可在 `loadHistory` 后加一层快速路径估算，避免 8 轮原文超 token；Agent 模式可在 `before_model_callback` 截断后做 token 估算兜底 |
| 摘要替换而非追加 | Ragent | Ragent 当前是摘要 + 原文（追加），可借鉴替换模式——摘要替换 8 轮之前的原文，省略窗口外原文 |
| 三级递进 | Ragent、Agent 模式 | 先快速估算（不调 LLM）→ 不行再截断 → 最后才摘要，Ragent 当前是截断后直接异步摘要，少了中间"精确截断"层 |

#### Agent 模式 → 其他三者

| 借鉴点 | 谁需要 | 怎么做 |
|:---|:---|:---|
| 整轮 invocation 丢弃（30K 兜底） | Ragent、ChatNode | Ragent 可加一个 "totalToken > 上限 → 整轮丢弃" 的硬兜底，不需要复杂逻辑 |
| Mem0 长期记忆补丢失 | ChatNode、Ragent | 非 Agent 模式也可用 Mem0 提取约束/偏好，弥补截断和摘要的信息丢失 |

#### Ragent → 其他三者

| 借鉴点 | 谁需要 | 怎么做 |
|:---|:---|:---|
| 增量摘要 + 半窗重叠 | ChatNode | ChatNode 当前是全量摘要，可改成增量：只摘要"上次摘要锚点之后"的新消息，大幅降低摘要 LLM 调用成本 |
| 异步压缩不阻塞 | ChatNode、Claude Code | ChatNode 摘要目前同步 block，可改成异步（摘要在后台跑，本次用截断兜底）；Claude Code 的 auto-compact 也是同步 block，如果有异步选项体验更好 |
| 改写阶段显式滤摘要 | Agent 模式、Claude Code | 摘要内容是"被压缩过的二手信息"，指代消解不应该基于它——Agent 模式的 `before_agent_callback` 和 Claude Code 的 compaction boundary 之后都可以显式跳过摘要块 |

#### Claude Code → 其他三者

| 借鉴点 | 谁需要 | 怎么做 |
|:---|:---|:---|
| Micro-compact（零 API 清除可重取内容） | **全部三者** | Ragent 每轮结束时清除旧检索结果块；Agent 模式清除旧的 tool_result（只在 function_call/function_response 生命周期内保留）；ChatNode 清除超过 N 轮的 quoteText |
| 结构化摘要（固定字段） | Ragent、ChatNode | 不用自由文本，改成固定字段模板（话题/状态/约束/未解决问题/下一步），不遗漏、可解析 |
| 缓存安全前缀 | Ragent、ChatNode | 压缩后保持 system prompt 前缀不变（如 `<conversation-summary>` 标签格式固定），prompt cache 不受压缩影响 |
| Sub-agent 上下文隔离 | Ragent | KB 问答通常不需要，但若扩展到工具调用型 Agent，把重 I/O 的检索任务 delegate 给子 Agent，只回传引用摘要 |
| 前四层零 API 调用 | **全部三者** | 核心思想：能用规则/简单的就不调 LLM。Ragent 可以加 micro-compact（删除旧检索结果），Agent 模式可以加 budget reduction（预丢弃工具结果），ChatNode 可以加 snip（裁剪非关键系统消息） |

### 21.5 终局思考：如果要设计一个"理想"的上下文压缩方案

从四种方案的得失中，可以归纳出一个理想方案应该有的层次：

```
                       零 API 调用层（便宜、随时做）
                       ─────────────────────────────
第 1 层  预算裁剪        清除可重取内容（旧检索结果、旧工具输出）
第 2 层  精确截断        轮次 + token 双维度控制，保留最近的 N 轮但总 token 不超 M
第 3 层  结构化增量摘要   只摘要窗口外的增量段，固定字段模板，异步执行

                       有 API 调用层（贵、按需触发）
                       ─────────────────────────────
第 4 层  全量摘要        当增量链路断裂时（锚点错位、消息被删），退化为一次性全量摘要
第 5 层  上下文隔离      重型任务 spawn 子进程/子 Agent，空上下文执行，只回传摘要

                       持久化层（跨会话）
                       ─────────────────────────────
第 6 层  分级记忆        短期记忆（原文轮次）→ 会话摘要 → 长期记忆（跨会话关键信息）→ 归档（grep only）
```

**关键原则**：
1. **能用规则的不调 LLM**——第 1、2 层应覆盖 80% 场景
2. **摘要是最后手段**——只压"窗口外"的增量，不全量重压
3. **缓存感知**——压缩前后稳定前缀不变，不破坏 prompt cache
4. **不丢不可重取的信息**——用户明确的约束条件在摘要中必须保留，工具调用结果可以丢因为随时重取
5. **两种模式不割裂**——ChatNode 和 Agent 模式应该共享同一套压缩基础设施，而不是各自维护

Sources:

---

## 22. Badcase 处理：上下文压缩的失败模式与应对

> 面试问题：**上下文压缩出了 badcase 怎么处理？**

### 22.1 Badcase 全景分类

| 类别 | 典型 badcase | 影响 | 发生在哪个系统 |
|:---|:---|:---|:---|
| 摘要质量 | 摘要丢失关键约束 / 编造不存在的话题 / 状态标注错误 | 下一轮 LLM 基于错误上下文回答 | Ragent、ChatNode、Claude Code |
| 时序问题 | 异步滞后导致本轮无摘要 / 摘要超时阻塞用户 | 上下文不完整或响应变慢 | Ragent、ChatNode |
| 锁/并发 | 分布式锁冲突导致连续多轮跳过压缩 | 窗口外消息被永久丢弃 | Ragent |
| 锚点错位 | afterId/cutoffId 错位，增量段为空或重叠 | 不触发摘要或产生重复摘要 | Ragent |
| 摘要污染 | 错误轮次的摘要误导改写/检索 / 摘要注入恶意内容 | 意图识别、检索通道被误导 | Ragent、ChatNode |
| 估算误判 | 快速路径 `rawTextLen < maxTokens × 0.5` 中文误判 | token 实际超限但绕过检查 | ChatNode |
| 截断撕裂 | 工具调用对跨截断边界被拆散 | LLM 看到孤立的 tool_result | Agent 模式 |
| 整轮误丢 | 30K 触发时丢掉关键 invocation | 重要结论永久丢失 | Agent 模式 |

### 22.2 当前各系统的实际处理

#### Ragent：静默降级 + 旧摘要兜底

**摘要 LLM 调用失败**（`JdbcConversationMemorySummaryService.java` L213-215）：

```java
} catch (Exception e) {
    log.error("对话记忆摘要生成失败, conversationId相关消息数: {}", messages.size(), e);
    return existingSummary;  // ← 兜底：返回旧摘要，不丢
}
```

策略：失败 → 保留旧摘要不更新 → 下次继续尝试。不会因为一次 LLM 调用失败就把摘要清空。

**异步任务异常**（L74-78）：

```java
CompletableFuture.runAsync(...)
    .exceptionally(ex -> {
        log.error("对话记忆摘要异步任务失败", ex);
        return null;  // ← 静默吞掉，不影响主流程
    });
```

**摘要为空**（`decorateIfNeeded`）：

```java
if (summary == null || StrUtil.isBlank(summary.getContent())) {
    return summary;  // ← 不注入，返回 null，上下文退化为纯原文
}
```

**分布式锁拿不到**（`tryLock()`）：

```java
if (!lock.tryLock()) {
    return;  // ← 跳过，下次再试
}
```

策略：拿不到锁就放弃本次压缩，不阻塞、不报错。代价是可能连续多轮跳过。

**问题**：所有 badcase 都只有**日志**，没有指标、没有告警。摘要质量差（LLM 输出乱码/空内容/错误标注）和摘要失败（异常/锁冲突）在日志层面不可区分——开发者也看不到到底发生了多少次失败。

#### ChatNode 模式

**摘要在 `getChatMessages()` 中是同步的**——失败直接抛异常到上层，走 ChatNode 的全局异常处理器：

- 摘要 LLM 超时 → 用户看到错误提示
- 摘要后仍超长 → 抛明确异常（不静默）
- 没有"保留旧摘要"的兜底

**快速路径估算不准**：`rawTextLen < maxTokens × 0.5` 用字符数估算，中文场景（1 中文字 ≈ 2 tokens）可能导致估算偏低——字符数只有窗口 40%，但 token 已占 80%。此时快速路径放行，后续 LLM 调用可能报 token 超限。**当前没有对这一 badcase 的专门处理。**

#### Agent 模式

**截断撕裂工具调用对**（PA Q15）：`before_model_callback` 以 user 消息为基准切片，不检查 function_call/function_response 的配对完整性。当前的处理是**接受这个 badcase**——"Agent 通常是一问一答模式，截断够用"（文档原文）。

**30K 整轮丢弃**：`check_session_token_count` 在总 token 超过 30K 时以 entire invocation 为单位丢弃。**没有优先级机制**——不会区分"这个 invocation 是关键决策"还是"这个 invocation 是冗余重试"。

#### Claude Code

**auto-compact 熔断**：社区观察到连续 3 次 auto-compact 失败后停止重试——避免无限浪费 API 调用。

**reactive compact**：只有 API 返回 `413 prompt_too_long` 时才触发，作为最后的物理安全网。

**Micro-compact 无副作用**：清除的是可随时重取的工具结果，失败了也无所谓——下次需要时 LLM 重新调用工具即可。

### 22.3 Badcase 处理的分层策略

#### 第一层：预防（设计阶段消除 badcase）

| badcase | 预防手段 | 谁已做到 |
|:---|:---|:---|
| 摘要丢失约束 | 结构化摘要 + Prompt 强化"约束优先于话题" | Claude Code（九段）部分做到，Ragent 有约束字段 |
| 工具调用对撕裂 | 按 invocation 切割而非 user 消息 | Claude Code（micro-compact 以消息为单位，不是轮次） |
| 摘要污染 | 改写阶段显式滤掉摘要块 | Ragent ✓ |
| 估算误判 | 中文场景用 1 字 ≈ 1.5 token 做更保守的估算 | 都未做到，目前用 0.5 这个经验系数 |

#### 第二层：兜底（运行时 badcase 发生后的处理）

| badcase | 兜底手段 | Ragent 现状 | 改进方向 |
|:---|:---|:---|:---|
| 摘要 LLM 失败 | 保留旧摘要，不更新 | ✓ `return existingSummary` | 加上失败计数，连续失败 N 次后告警 |
| 摘要 LLM 返回空 | 不注入摘要，退化纯原文 | ✓ `isBlank → return null` | 加日志区分"无摘要"和"摘要失败" |
| 异步滞后 | 下一轮 8 轮原文兜底 | ✓ | 可选同步模式（flag 控制） |
| 锁冲突跳过 | 下次再试 | ✓ `tryLock → return` | 解锁后补偿检查：如果 unlock 后发现又需要压缩，立即再触发一次 |
| 锚点错位 | 下次触发时 afterId 自然推进 | 隐式自愈 | 加显式检查：afterId > cutoffId → 重置锚点为 null → 下次全量摘要 |
| 快速路径误判 | 后续 LLM 调用报 413 → reactive compact | 都没有 | ChatNode 在 LLM 返回 token 超限错误时补一次精确截断 |

#### 第三层：可观测（事后感知 badcase）

| 需要监控的指标 | 为什么 | Ragent 现状 |
|:---|:---|:---|
| 摘要成功/失败率 | 失败率高说明 LLM 或 Prompt 有问题 | ❌ 只有日志，无指标 |
| 摘要长度中位数 / P99 | 摘要持续偏短 → 可能 LLM 输出异常 | ❌ |
| 锁冲突次数 | 锁冲突频繁 → historyKeepTurns 设太小或用户回复太快 | ❌ |
| 摘要滞后轮数 | 用户看到摘要效果的延迟 | ❌ |
| 摘要触发频次 | 每 N 轮触发一次是正常的；每轮触发说明半窗重叠失效 | ❌ |

### 22.4 面试话术

> "上下文压缩的 badcase 分三类处理。第一类是预防——设计阶段就消除。比如 Claude Code 的前四层零 API 压缩，清除的是可随时重取的工具结果，失败了也无所谓。Ragent 改写阶段显式滤掉摘要，防止摘要污染指代消解。这些是从架构上避免 badcase。
>
> 第二类是兜底——运行时发生 badcase 后的处理。Ragent 有三处兜底：摘要 LLM 失败保留旧摘要不更新；返回空内容不注入退化纯原文；分布式锁冲突跳过等下次。ChatNode 是同步的，失败直接抛异常让用户感知，不会静默降级。
>
> 第三类是可观测——但目前是我们共同的短板。摘要的成功率、质量、滞后程度都没有指标化，出了 badcase 只能靠用户反馈或看日志。如果要增强，我会在三个方面补：摘要 LLM 失败告警、摘要内容长度异常告警、锁冲突频率监控。"

### 22.5 Badcase 处理对照表

| 场景 | Ragent | ChatNode | Agent 模式 | Claude Code |
|:---|:---|:---|:---|:---|
| 摘要 LLM 失败 | 保留旧摘要 + 异步静默 | 同步抛异常 | —（无摘要） | auto-compact 3 次熔断 |
| 摘要为空/异常短 | 不注入，退化纯原文 | 抛异常 | — | 退化原文 |
| 锁/并发冲突 | tryLock → 跳过 | —（同步，无冲突） | — | — |
| 快速路径估算不准 | —（无此层） | 无兜底，后续 LLM 可能报 413 | — | micro-compact + snip 在前四层过滤 |
| 截断撕裂工具调用 | —（无工具调用） | —（ChatNode 无工具调用） | **接受此 badcase** | micro-compact 以消息为单位，不按轮切 |
| 关键信息丢失 | 8 轮原文兜底 | 摘要替换全部历史，无原文兜底 | 长期记忆 Mem0 补 | 缓存安全前缀 + 子 Agent 隔离 |
| 可观测性 | 仅日志 | 异常可见（阻塞抛错） | 仅日志 | 部分指标（token before/after） |


- [Using Claude Code: session management and 1M context](https://claude.com/blog/using-claude-code-session-management-and-1m-context)
- [Claude Code Context Compaction DeepWiki](https://deepwiki.com/alesha-pro/claude-code/8.3-context-compaction)
- [Claude Code Multi-Strategy Compaction](https://github.com/0xtresser/Claude-Code-VS-OpenCode/blob/main/EN/Chapter_11_Claude_Code_Commercial/11.5_Multi_Strategy_Compaction.md)
- [Claude Code Context Management Architecture](https://github.com/6551Team/claude-code-design-guide/blob/main/architecture/07-%E4%B8%8A%E4%B8%8B%E6%96%87%E7%AE%A1%E7%90%86/context-management-en.md)
- [Claude Code Subagents Guide](https://code.claude.com/docs/en/sub-agents)
- [Memory Hygiene for Claude Code](https://github.com/wan-huiyan/memory-hygiene)
- [4-Layer Nested Memory for Claude Code](https://github.com/tak633b/nested-memory)
