# Ragent 意图树 & Query 重写 配置问答

> 基于源码分析。覆盖意图树配置、Query 重写机制、对话入口、改写模型选型、temperature 调参。

---

## 一、意图树 DefaultIntentClassifier 的实现

### 1.1 默认实现 = 没有，但 IO 层已就绪

`DefaultIntentClassifier` 是默认实现，但它做的事情只是"把 DB 里的叶子节点喂给 LLM 打分"。**它本身不内置任何意图节点**，完全靠 `t_intent_node` 表的数据。表为空 → 叶子列表为空 → LLM 没东西可打分 → 返回 `[]` → 向量检索走全局兜底。项目没有在 `init_data_pg.sql` 里给默认种子数据，所以新部署的环境**意图树是空的，需要自己配**。

### 1.2 加载链路

`DefaultIntentClassifier.loadIntentTreeData()`（`DefaultIntentClassifier.java:69`）三级加载：

```
① Redis 缓存 (IntentTreeCacheManager)   key = ragent:intent:tree，7 天过期
   ↓ 空
② 数据库 t_intent_node  (loadIntentTreeFromDB)
   只查 enabled=1 AND deleted=0
   ↓ 空
③ 返回空 IntentTreeData → classifyTargets 拿到空叶子节点 → 返回 []
```

缓存加载是惰性的，第一次请求从 DB 加载并写 Redis。意图节点增删改时通过 `clearIntentTreeCache()` 清缓存强制重读。

### 1.3 t_intent_node 表关键字段

| 列 | 作用 | 取值示例 |
|:---|:---|:---|
| `intent_code` | 节点业务唯一 ID（实现里 `node.setId()`） | `ragent-chunk` |
| `parent_code` | 父节点 code（根节点为 NULL） | 组成树形结构 |
| `name` | 展示名（拼 fullPath 用） | `Chunk 切分` |
| `level` | 0=DOMAIN 1=CATEGORY 2=TOPIC | 按层级填 |
| `kind` | 0=KB(走检索)、1=MCP(走工具)、2=SYSTEM(闲聊短路) | **关键分流** |
| `kb_id` | 知识库 ID（KB 节点绑定到具体库，定向检索） | 你的 KB id |
| `description` | 给 LLM 看的描述 | `Ragent 文档分块相关问题` |
| `examples` | 给 LLM 的示例问题（提高识别率） | `分块/chunk/切分策略` |
| `collection_name` | KB 类型时定向检索的 collection | 你的 collection 名 |
| `top_k` | 该节点检索 top-k（覆盖默认值，可空） | 10 |
| `enabled` | 1 启用，0 禁用（实现里过滤掉了 0） | 1 |
| `deleted` | 0 正常 1 删（实现里过滤掉了 1） | 0 |

### 1.4 配置 SQL 示例

```sql
-- 先查你的真实 kb_id 和 collection_name
SELECT id, kb_name, collection_name FROM t_knowledge_base WHERE deleted = 0;

-- 根（DOMAIN）：Ragent 知识库
INSERT INTO t_intent_node (id, intent_code, parent_code, name, level, kind, kb_id,
                            description, examples, collection_name, enabled, deleted)
VALUES ('1887000000000000001', 'ragent', NULL, 'Ragent 知识库', 0, 0,
        '<你的kb_id>',
        'Ragent 项目相关问题',
        'Ragent 是什么 / 项目介绍',
        '<你的collection_name>', 1, 0);

-- 子1（CATEGORY）：RAG 流水线
INSERT INTO t_intent_node (id, intent_code, parent_code, name, level, kind,
                            description, examples, collection_name, enabled, deleted)
VALUES ('1887000000000000100', 'ragent-rag', 'ragent', 'RAG 流水线', 1, 0,
        '检索增强生成、流水线、向量召回、RRF、Rerank 相关',
        '检索怎么做的 / 多路召回 / RRF 融合',
        '<你的collection_name>', 1, 0);

-- 叶子（TOPIC）：Chunk 切分
INSERT INTO t_intent_node (id, intent_code, parent_code, name, level, kind,
                            description, examples, collection_name, top_k, enabled, deleted)
VALUES ('1887000000000000101', 'ragent-chunk', 'ragent-rag', 'Chunk 切分', 2, 0,
        '文档分块策略、Parser、Block 模型、双文本嵌入',
        '分块策略 / chunk 怎么切 / Block 体系是什么',
        '<你的collection_name>', 10, 1, 0);

-- 叶子（TOPIC）：意图识别
INSERT INTO t_intent_node (id, intent_code, parent_code, name, level, kind,
                            description, examples, collection_name, enabled, deleted)
VALUES ('1887000000000000102', 'ragent-intent', 'ragent-rag', '意图识别', 2, 0,
        '意图树、意图分类、KB 识别',
        '意图识别怎么做的 / KB 意图',
        '<你的collection_name>', 1, 0);
```

### 1.5 配完后会发生什么

启用的节点会先去 Redis 缓存落地（`ragent:intent:tree`），下次请求时：

```
有意图节点 → classifyTargets 拿到叶子 → 拼 Prompt 发 LLM → 拿到 score
→ NodeScoreFilters.kb() 过滤出 KB 节点 → 向量通道收窄到 collection_name 检索
→ KB 意图高分 → VectorSearchChannel 走"意图定向"，不再走全局
```

日志会从 `意图识别树如下所示：[]` 变成出现节点 + LLM 打分结果，向量通道日志从 `未识别出 KB 意图，走全局作用域` 变成 `意图定向`。

验证插入成功：

```sql
SELECT intent_code, parent_code, name, level, kind, enabled
FROM t_intent_node
WHERE enabled = 1 AND deleted = 0
ORDER BY intent_code;

-- 顺手清缓存强制重读 DB（redis-cli 或调控制接口）
-- redis-cli DEL ragent:intent:tree
```

---

## 二、意图识别（KB 意图怎么识别）

### 2.1 classifyTargets 流程

`DefaultIntentClassifier.classifyTargets()`（`DefaultIntentClassifier.java:136`）：

```
Step 1 — 收集所有叶子节点（从意图树提取 isLeaf() == true）
Step 2 — 构造 Prompt 发给 LLM：把所有叶子的 id/path/description/type/examples 拼成 prompt
Step 3 — LLM 一次性对每个叶子节点打分，返回 JSON:
         [{"id":"ragent-chunk","score":0.85,"reason":"用户问的是分块相关"}]
Step 4 — 按 score 降序排序，返回 List<NodeScore>
Step 5 — 三种意图类型分别处理（NodeScoreFilters）：
         NodeScoreFilters.kb(intent.nodeScores())   → 去 KB 检索
         NodeScoreFilters.mcp(intent.nodeScores())  → 去 MCP 工具调用
         IntentGroup.systemIntents()                → 走闲聊短路
Step 6 — 向量通道根据意图置信度选作用域（VectorSearchChannel）：
         KB 意图最高分 >= confidence-threshold(0.6) → 意图定向（收窄到命中库检索）
         KB 意图最高分  < confidence-threshold(0.6) → 全局作用域（全库检索兜底）
```

构建 Prompt 的关键代码（`DefaultIntentClassifier.java:229`）：

```java
private String buildPrompt(List<IntentNode> leafNodes) {
    for (IntentNode node : leafNodes) {
        sb.append("- id=").append(node.getId()).append("\n");
        sb.append("  path=").append(node.getFullPath()).append("\n");
        sb.append("  description=").append(node.getDescription()).append("\n");
        if (node.isMCP())        sb.append("  type=MCP\n");
        else if (node.isSystem()) sb.append("  type=SYSTEM\n");
        else                     sb.append("  type=KB\n");
        if (node.getExamples() != null && !node.getExamples().isEmpty()) {
            sb.append("  examples=").append(String.join(" / ", node.getExamples())).append("\n");
        }
    }
    return promptTemplateLoader.render(INTENT_CLASSIFIER_PROMPT_PATH,
                                       Map.of("intent_list", sb.toString()));
}
```

意图识别的 ChatRequest 参数：

```java
// DefaultIntentClassifier.java:147
.temperature(0.1D).topP(0.3D)    // 极度严格，保证可复现
.thinking(false)
```

---

## 三、意图树和 Query 重写是并行的吗？每次对话都重写吗？

### 3.1 不并行，串行

`StreamChatPipeline.execute()`（`StreamChatPipeline.java:78`）写死的顺序：

```java
public void execute(StreamChatContext ctx) {
    loadMemory(ctx);       // ① 记忆加载（内部并行）
    rewriteQuery(ctx);     // ② Query 重写 + 拆分
    resolveIntents(ctx);   // ③ 意图识别（基于 ② 的输出）
    ...
}
```

```
② rewriteQuery ──┐
                 │ 输出 RewriteResult{rewrittenQuestion, subQuestions, variants}
                 ▼
③ resolveIntents ── 对每个 subQuestion 调 IntentClassifier.classifyTargets()
```

**为什么必须串行**：意图识别的输入是 Query 重写的输出。改写把"它怎么用？"消解成"OA 怎么用？RBAC 怎么设置？"两个子问句后，意图识别才能分两次对每个子问句做意图命中。如果不重写直接做意图识别，口语化/指代会让命中率掉一大截。

### 3.2 当前 Query 重写策略（Multi-Query 改造后）

`MultiQuestionRewriteService.rewriteWithSplit()` 实际链路：

```
原始 query
   ↓
① queryTermMappingService.normalize()    ← 规则级术语归一化（DB/Redis 查表，不调 LLM）
   ↓
② 判断 query-rewrite.enabled:
   ├─ false → ruleBasedSplit()      ← 规则拆分（多问号拆分），不调 LLM
   └─ true  → callLLMRewriteAndSplit()  ← LLM 改写+拆分，temperature=0.1 极度严格
   产出 RewriteResult{rewrittenQuestion, subQuestions}
   ↓
③ maybeExpandVariants()  ← Multi-Query 变体扩展（你改造的部分）
   判断 shouldExpand():
     - multiQueryEnabled=true（默认 false）
     - query 长度 ≤ minQueryChars(10) 才触发
   触发 → generateVariants() 调 LLM（temperature=0.7 提高多样性）生成 3-5 个语义变体
   把 variants 拼回 RewriteResult{variants}
   ↓
输出 RewriteResult{rewrittenQuestion, subQuestions, variants}
```

### 3.3 两个开关

```yaml
rag:
  query-rewrite:
    enabled: true                     # LLM 改写+拆分开关
  multi-query:
    enabled: false                    # Multi-Query 变体扩展开关（你改造加的）
    max-variants: 3                   # 最大变体数（不含原始 rewrite）
    min-query-chars: 10               # query 字符数低于此值才触发扩展
```

### 3.4 每轮 LLM 调用次数（按开关组合）

| query-rewrite | multi-query | LLM 调用 |
|:---|:---|:---|
| 关 | 关 | 1 次（答题） |
| 开 | 关 | 1 次（改写, T=0.1）+ 1 次（意图识别, T=0.1）+ 1 次（答题, T=0）= 3 次 |
| 开 | 触发 | 1 次（改写, T=0.1）+ 1 次（变体生成, T=0.7）+ 1 次（意图识别）+ 1 次（答题）= 4 次 |

### 3.5 Multi-Query 变体扩展的触发条件

`MultiQuestionRewriteService.shouldExpand()`（行 252）：

```java
if (!multiQueryEnabled)       return false;
if (blank rawQuery/rewrite)   return false;
// 短 query（≤ 10 字符）→ 必触发
if (rewrite.length() ≤ minQueryChars) return true;
// 已多个子问题 → 不扩展（拆分已覆盖多角度）
if (base.hasSubQuestions())   return false;
// 改写仍较短或模糊（≤ 20 字符）→ 扩展
return rewrite.length() ≤ minQueryChars * 2;
```

**设计意图**：短/模糊 query 召回会漏，变体扩展能补到更多语义角度；长 query 或已拆分的子问题不需要扩展（再扩展会发散到不相关领域）。

### 3.6 变体生成细节

`generateVariants()`（行 277）：

```java
ChatRequest.builder()
    .temperature(0.7D)    // 提高多样性（vs 改写的 0.1）
    .topP(0.5D)
    .build();

// 输出格式：每行一个问题，无编号无解释
// 解析后把原始 rewrite 放第一位（保证始终参与检索），变体去重后追加
// RewriteResult.variants = [原始rewrite, 变体1, 变体2, ...]
```

**注意**：变体扩展只对 **subQuestions.size() == 1** 的单子问题生效。`IntentResolver.resolve()` 在分发的 `SubQuestionIntent` 时才把 variants 传到检索层。检索层 `RetrievalEngine.retrieveWithVariants()` 让每个变体并行跑多通道检索，结果按 chunkId 去重合并（变体 0=原始 query 的优先级最高）。

### 3.7 Multi-Query 改造前后对比

#### 数据模型层

```java
// 改造前 — RewriteResult 俩字段
public record RewriteResult(String rewrittenQuestion, List<String> subQuestions)

// 改造后 — 加了 variants
public record RewriteResult(String rewrittenQuestion, List<String> subQuestions, List<String> variants) {
    public RewriteResult(String rewrittenQuestion, List<String> subQuestions) {
        this(rewrittenQuestion, subQuestions, List.of());  // 老调用点兼容，variants 为空
    }
    public boolean hasVariants() { return variants != null && !variants.isEmpty(); }
    public boolean hasSubQuestions() { return subQuestions != null && subQuestions.size() > 1; }
}
```

#### 重写阶段流程

```
改造前：
  normalize → LLM 改写+拆分 → 输出 RewriteResult(rewrite, subQuestions)
                                              ↑ 只有 1 个角度

改造后：
  normalize → LLM 改写+拆分 → maybeExpandVariants
                              ↓
              shouldExpand?
                ├ 否 → 原样返回（行为同改造前）
                └ 是 → generateVariants(T=0.7)
                       → variants = [原始rewrite, 变体1, 变体2, ...]
                       → 输出 RewriteResult(rewrite, subQuestions, variants)
                                              ↑ 多角度语义
```

#### 检索阶段流程

```
改造前：
  subQuestions → 每个 subQuestion 跑一次多通道检索 → 合并
  （单子问题时只跑一次，召回率受限于单一表达）

改造后 — RetrievalEngine.retrieveWithVariants()：
  subQuestions (单子问题时)
    ↓ IntentResolver 把 variants 传进 SubQuestionIntent.variantQueries
    ↓ RetrievalEngine 检测 variantQueries.size() > 1
    ↓ retrieveWithVariants 路径
       variants[0..N] 每个 variant 并行跑多通道检索（用 ragContextExecutor）
       ↓
       所有变体的 chunks 用 LinkedHashMap 按 chunkId 去重合并
       （首次出现优先 = 变体 0=原始 rewrite 的优先级最高）
       ↓
       buildIntentChunks + formatKbContext
```

#### 核心差异总结

| 维度 | 改造前 | 改造后 |
|:---|:---|:---|
| 单 query 表达 | 只有 1 个改写 query | 1 个原始 + N 个变体（多角度） |
| LLM 调用 | 1 次（改写） | 1 次（改写）+ 可能 1 次（变体生成） |
| 检索并行度 | subQuestions 并行 | subQuestions × variants 双层并行 |
| 召回策略 | 改写 query 命中什么算什么 | 多个变体并集去重 → 召回率↑ |
| 触发条件 | 始终单 query | 短 query 或模糊 query 才触发（避免长 query 发散） |
| 变体温度 | — | 0.7（专门拉高买多样性，跟改写的 0.1 形成对比） |

#### 解决什么问题

**改造前的痛点**：用户问"Block 体系是什么"这种短 query，向量检索靠单一 embedding 召回，可能命中不到——因为文档里同样的概念可能写成"Block 模型"、"Block 结构"、"Block IR"。

**改造后**：maybeExpandVariants 把"Block 体系是什么"扩展成 3 个不同表达的变体（如"Block 结构包含哪些类型"、"Block 中间表示是什么"），每个变体各自跑检索，去重合并。任一变体命中的 chunk 都进结果池——召回率提升，且通过 LinkedHashMap 首次出现优先保证原始 query 的命中分不被变体稀释。

#### 不破坏原行为的设计

- `multi-query.enabled=false`（默认）→ shouldExpand 直接 false，maybeExpandVariants 透传原 RewriteResult，行为完全等同改造前
- 老 RewriteResult 两参构造器自动填 `variants=List.of()`，所有未改调用点 0 兼容
- 只对单子问题生效（多子问题已经天然多角度，不需要再扩展）

#### 面试话术

> Ragent 原来的 Query 重写只产出一个改写 query，单子问题时召回率受限于单一表达。我做了一个 Multi-Query 变体扩展——在改写完成后加 maybeExpandVariants 步骤，对短 query 或模糊 query 调用一次 LLM（temperature 拉高到 0.7）生成 3 个不同角度的语义变体。检索阶段每个变体并行多通道检索再按 chunkId 去重合并，原始 rewrite 永远排第一保证优先级。开关默认关，不影响存量行为；触发条件用 query 长度阈值控制，避免长 query 发散到不相关领域。改造后召回率提升明显，特别在概念有多重表达的领域知识库场景。

---

## 四、RAGChatServiceImpl 实现的是哪个对话

### 4.1 是用户问答对话

`RAGChatServiceImpl` 实现 `RAGChatService` 接口，对外就是**前端主界面的"用户提问 → RAG 检索 → LLM 流式回答"那次对话**。

### 4.2 它实际做的事（很薄的一层）

`RAGChatServiceImpl.streamChat()` 自己几乎不做业务，是编排皮：

```java
public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
    // 1. 会话 ID 兜底（空就生成新雪花 ID）
    String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;

    // 2. 任务 ID（用于前端"停止"操作）
    String taskId = IdUtil.getSnowflakeNextIdStr();

    // 3. 构造 SSE 回调（定义如何把流推给前端）
    StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

    // 4. 限流排队 + 链路追踪 + 实际执行
    chatQueueLimiter.enqueue(question, actualConversationId, emitter,
        () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
            StreamChatContext ctx = StreamChatContext.builder()...build();
            chatPipeline.execute(ctx);   // ← 真正的 7 步流水线
        }));
}
```

### 4.3 调用链全景

```
前端发 POST /rag/chat
       ↓
RAGChatController.streamChat(question, conversationId, deepThinking)
       ↓
RAGChatService.streamChat(...)
       ↓
RAGChatServiceImpl              ← 限流/追踪/SSE装配
       ↓
ChatQueueLimiter.enqueue       ← 限流排队（max-concurrent: 10 在这里生效）
       ↓
StreamChatTraceRunner.run      ← 全链路 Trace 包裹
       ↓
StreamChatPipeline.execute     ← 真正的 7 步流水线
   ① loadMemory     ② rewriteQuery  ③ resolveIntents
   ④ handleGuidance  ⑤ handleSystemOnly
   ⑥ retrieve       ⑦ streamLLMResponse
       ↓
SseEmitter 流式推给前端
```

### 4.4 RAGChatServiceImpl 的职责定位

它**不包含流水线逻辑**，只解决三件事：

| 职责 | 实现 | 作用 |
|:---|:---|:---|
| 限流 | `ChatQueueLimiter` | yaml 里配的 `max-concurrent: 10` 在这里生效，挡住第 11 个并发请求 |
| Trace | `StreamChatTraceRunner` | 包住整链路，落 `t_rag_trace_run` + `t_rag_trace_node` |
| 中断 | `taskManager` | 前端"停止生成"按钮调的就是 `stopTask(taskId)` |

逻辑流水线本身拆出去了——`StreamChatPipeline` 专注"记忆→重写→意图→检索→答题"，`RAGChatServiceImpl` 专注"线程/限流/Trace/SSE"。

### 4.5 几个易混 Service 对比

| 类 | 干啥 |
|:---|:---|
| **RAGChatServiceImpl** | 主对话（用户问 → RAG → LLM 答），有 SSE 流式 |
| StreamChatPipeline | 上面的实际流水线编排（7 步） |
| EvalController | 评测接口（`/rag/eval`），只做检索不做答题，纯调试用 |
| IntentResolver | 意图解析（被 StreamChatPipeline 调用，不是独立对外服务） |

---

## 五、改写 query 用的哪个模型

### 5.1 当前是默认 chat 模型

`MultiQuestionRewriteService.rewriteWithSplit()` 调用 `llmService.chat(request)` —— 走 RoutingLLMService.chat，按 `ai.chat.default-model` 路由。当前配置下是 `deepseek-v4-flash`（aihubmix）。

**关键点**：改写**没有指定专门的"小模型"**，它直接调默认 chat 模型。这意味着每次改写都走你那条主力 chat 链路（默认 deepseek-v4-flash，跟答题是同一个路由池）。

### 5.2 实际流量

```
MultiQuestionRewriteService.rewriteWithSplit()
   → llmService.chat(request)            ← 未指定 modelId，走默认
       → RoutingLLMService.chat(request)
           → request.getThinking() == false   ← 改写设了 thinking(false)
           → ModelSelector.selectChatCandidates(false)
               firstChoice = default-model = "deepseek-v4-flash"
           → 候选列表 = [deepseek-v4-flash(p1), qwen-plus(p2), qwen3-local(p3), qwen3-max(p4)]
           → 按 priority 逐个尝试，deepseek-v4-flash 可用就用它
```

改写 + 答题**用同一个模型** deepseek-v4-flash。区别只在 thinking / temperature / topP 等参数。

### 5.3 不同阶段 ChatRequest 参数对比

| 阶段 | temperature | topP | thinking | 为什么 |
|:---|:---|:---|:---|:---|
| **改写 + 拆分** | 0.1 | 0.3 | false | 极度严格，避免发散拆分（`buildRewriteRequest` 行 153） |
| **变体扩展**（Multi-Query） | 0.7 | 0.5 | false | 反过来要提高多样性（`generateVariants` 行 290） |
| **意图识别** | 0.1 | 0.3 | false | 几乎 greedy，保证可复现（`DefaultIntentClassifier.java:147`） |
| **RAG 答题（非 MCP）** | 0.0 | 1.0 | false | 完全贪婪，忠实知识库（`StreamChatPipeline.java:229`） |
| **RAG 答题（含 MCP）** | 0.3 | 0.8 | true/false | MCP 场景稍放宽随机性，避免工具参数都填成默认值 |

**注意**：改写用的 temperature 是 **0.1**（不是早期文档里写的 0.3，0.3 是含 MCP 答题的温度）。改写和意图识别共用低温度保证可复现，变体扩展专门拉高温度买到多样性。

### 5.4 优化点：可让改写走本地小模型

`MultiQuestionRewriteService.rewriteWithSplit()` 没有用 `llmService.chat(request, modelId)` 这个重载去指定便宜的小模型。改写不需要深度推理能力，用本地 Ollama 跑 `qwen3:8b-fp16` 完全够，节省主链路配额和成本。改造一行：调 `llmService.chat(request, "qwen3-local")` 让改写走本地 ollama。

---

## 六、temperature 参数的作用

### 6.1 通俗解释

`temperature` 控制模型生成下一个 token 时**有多大的发散性**，是硬度调节器——越低越确定，越高越随机。

### 6.2 数学原理

模型生成每个 token 时输出一个**概率分布**——所有候选词的概率。temperature 把 logits（原始分数）做缩放后再 softmax：

```
原始分数(logits)        temperature=1.0       temperature=0.3        temperature=0.0
Token A    5.0          P(A)=0.71              P(A)=0.99           P(A)=1.0 (硬选)
Token B    2.0          P(B)=0.04              P(B)≈0              P(B)=0
Token C    4.0          P(C)=0.21              P(C)=0.01           P(C)=0
Token D    0.5          P(D)=0.02              P(D)≈0              P(D)=0
```

公式：`softmax(logit / temperature)`

- temperature = 1：原汁原味的概率分布
- temperature → 0：高分项压倒性独占（趋向 greedy，永远选最高分那个）
- temperature → ∞：分布被压平，所有 token 概率几乎相等（纯随机）

### 6.3 温度档位经验值

```
0.0 ───────────── 0.3 ───────────── 0.7 ───────────── 1.0 ────── 2.0
 完全确定性         严格但留兜底      平衡（创作型对话） 正常发散     极度随机
 事实问答/代码       改写/抽取/分类    通用对话/客服     写诗/创意   头脑风暴
```

### 6.4 Ragent 多个调用点的温度调优

| 阶段 | temperature | topP | 为什么 |
|:---|:---|:---|:---|
| **Query 改写 + 拆分** | 0.1 | 0.3 | 改写要求"严格按指令生成"，不能脑补，极度严格避免发散拆分 |
| **Multi-Query 变体生成** | 0.7 | 0.5 | 反过来：要发散买多样性（生成多个不同角度的语义变体） |
| **意图识别** | 0.1 | 0.3 | 给每个叶子节点打分，要极度严格 → 几乎 greedy，保证每次问同一个问题打的分一致（可复现） |
| **RAG 答题（非 MCP）** | 0.0 | 1.0 | 直接基于检索证据答题，要忠实知识库、不脑补、不幻觉 → 完全贪婪，每次问同一个问题答案一致 |
| **RAG 答题（含 MCP）** | 0.3 | 0.8 | MCP 工具调用场景稍微放宽随机性，避免工具参数都填成最常见的默认值 |

源码证据：

```java
// MultiQuestionRewriteService.buildRewriteRequest (行 153) — 改写
.temperature(0.1D).topP(0.3D)

// MultiQuestionRewriteService.generateVariants (行 290) — 变体生成
.temperature(0.7D).topP(0.5D)   // 同一个类里两个完全相反的温度档

// DefaultIntentClassifier (行 147) — 意图识别
.temperature(0.1D).topP(0.3D)

// StreamChatPipeline (行 229) — 答题
.temperature(ctx.hasMcp() ? 0.3D : 0D)
.topP(ctx.hasMcp() ? 0.8D : 1D)
```

### 6.5 为什么 RAG 答题用 0.0

RAG 系统的核心是**忠实知识库**：
- temperature 高 → 模型脑补，编造知识库里没有的事实（幻觉）
- temperature 0 → 模型概率最高的 token 就是它对知识库内容最忠实的转述

实测：同一份检索证据 + 同一个问题，temperature=0.7 时它能"润色"答案，temperature=0 时它逐字转述证据。企业知识库场景默认就 0。

### 6.6 temperature=0 也不保证 100% 可复现

不完全对，需要配合：
1. 不同 batchsize 时浮点数求和顺序不同，logits 可能微差
2. 模型供应商实现细节（量化、KV cache 精度）可能引入小随机
3. top-k/top-p 没显式关时即便 temperature=0 也可能触发采样

真正要 100% 可复现：`temperature=0` + `top_p=1` + 关 top_k + 关 logits 截断。Ragent 答题的 `temperature(0D).topP(1D)` 就是这个意思——top_p=1 等于不截断，配合 temperature=0 就是尽量 greedy。

### 6.7 实战判断：什么任务用什么温度

| 任务类型 | 推荐 temperature | 理由 |
|:---|:---|:---|
| 事实问答 / 数学 / 代码生成 | 0.0 - 0.2 | 容错率低，发散=错 |
| 分类 / 抽取 / 改写 / 意图识别 | 0.1 - 0.3 | 输出结构固定，但需要一点"软"避免僵化 |
| 客服对话 / 通用问答 | 0.5 - 0.7 | 既要对又要自然 |
| 创意写作 / 故事 / 营销文案 | 0.7 - 1.0 | 要发散要"火花" |
| 头脑风暴 | 1.0 - 1.2 | 越发散越好 |