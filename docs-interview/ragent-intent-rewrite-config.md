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
| `promptSnippet` | **短规则片段**：拼进检索结果上方 `<rules>` 块，告诉 LLM"这类问题怎么回答" | `回答要包含代码示例` |
| `promptTemplate` | **完整 Prompt 模板**：仅 SYSTEM 节点，替换默认 system prompt | 自定义角色设定 |
| `enabled` | 1 启用，0 禁用（实现里过滤掉了 0） | 1 |
| `deleted` | 0 正常 1 删（实现里过滤掉了 1） | 0 |

#### promptSnippet vs promptTemplate

两个字段都在意图节点配置中，但**作用不同、生效时机不同**：

| | promptSnippet | promptTemplate |
|:---|:---|:---|
| 适用节点 | KB + MCP 节点 | SYSTEM 节点（kind=2） |
| 用在哪个阶段 | ContextFormatter 组装检索上下文时 | handleSystemOnly 闲聊短路时 |
| 拼到 Prompt 的什么位置 | 检索结果上方的 `<rules>` 块 | 整个 System Prompt |
| 不填时 | 不拼接规则，纯粹检索 | 用默认 CHAT_SYSTEM_PROMPT |

**promptSnippet 源码**（`DefaultContextFormatter.formatSingleIntentContext` 行 68）：

```java
String snippet = nodeScore.getNode().getPromptSnippet();  // 取配置
return renderKbSection(renderSnippetRules(snippet), docBlocks);
// ↓ 渲染为 <rules>{snippet}</rules><content source="...">{chunks}</content>
```

**promptTemplate 源码**（`StreamChatPipeline.handleSystemOnly` 行 140）：

```java
String customPrompt = subIntents.stream()
    .map(ns -> ns.getNode().getPromptTemplate())  // 取配置
    .filter(StrUtil::isNotBlank)
    .findFirst()
    .orElse(null);
// 如果配了用它替代默认 prompt，没配用默认
String systemPrompt = StrUtil.isNotBlank(customPrompt)
    ? customPrompt : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);
```

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

### 2.2 意图识别不只用在检索中——四个消费点

意图识别的结果（`List<NodeScore>`）在流水线里被用了四处，检索只是其中之一：

```
resolveIntents() 产出 List<SubQuestionIntent>
   │
   ├─ ① 歧义引导（短路）
   │    handleGuidance()
   │    多意图低分 → 反问用户"你是想问 A 还是 B？" → 直接 return
   │    没有意图树 → 永远不会走这条短路
   │
   ├─ ② 闲聊短路
   │    handleSystemOnly()
   │    纯 SYSTEM 意图 → "这个问题不需要检索，直接 LLM 答"
   │    没有意图树 → 永远不会走这条短路
   │
   ├─ ③ 检索引擎分流
   │    NodeScoreFilters.kb(intent)   → KB 节点 → 定向或全局 retrieval
   │    NodeScoreFilters.mcp(intent)  → MCP 工具调用（不检索，调用外部工具）
   │    VectorSearchChannel 根据 KB intent 置信度选作用域：
   │      ≥0.6 → 意图定向（收窄命中库）
   │      <0.6 → 全局兜底（全库检索）
   │
   └─ ④ 答题 Prompt 拼装
        PromptContext.kbIntents → prompt builder 注入"用户在问哪类问题"
        PromptContext.mcpIntents → LLM 知道"我调了哪些工具 + 结果是什么"
        → 答题阶段 LLM 理解用户意图类别，答得更精准
```

源码证据（`StreamChatPipeline.java:81-90`）：

```java
resolveIntents(ctx);           // 产出 intentGroup

if (handleGuidance(ctx))       // ← ① 用 intentGroup 走歧义短路
    return;
if (handleSystemOnly(ctx))     // ← ② 用 intentGroup 走闲聊短路
    return;

RetrievalContext retrievalCtx = retrieve(ctx);  // ← ③ 用 intentGroup 分流 KB vs MCP
```

④ 在答题 Prompt 组装时（`StreamChatPipeline.java:211`）：

```java
PromptContext.builder()
    .mcpIntents(intentGroup.mcpIntents())   // ← MCP 意图传给 LLM
    .kbIntents(intentGroup.kbIntents())     // ← KB 意图传给 LLM
```

#### 对照：意图树为空时失去的能力

| 位置 | 用意图识别做什么 | 意图树为空时 |
|:---|:---|:---|
| 歧义引导 ① | 多意图低分 → 反客用户选择 | 永远不会走这条短路，无歧义处理 |
| 闲聊短路 ② | 纯 SYSTEM 意图 → 直接 LLM 答 | 永远不会走这条短路，每次都走检索 |
| 检索分流 ③ | KB 节点定向 collection / MCP 工具路由 | 未命中 KB → 全局兜底；未命中 MCP → 无工具调用 |
| 答题增强 ④ | LLM 看到"用户在 KB/工具 类别下问该问题" | LLM 缺失用户意图信息，答题质量下降 |

**结论**：意图树为空不是"只缺定向检索"——四条能力全丢。歧义引导、闲聊短路、MCP 路由、答题上下文这四个功能全部依赖于意图识别，彼此互相卡。

### 2.3 意图识别的四个消费点——汇总

```
resolveIntents() 产出 List<SubQuestionIntent>
   │
   ├─ ① 歧义引导（短路）
   │    handleGuidance()
   │    多意图低分 → 反问用户"你是想问 A 还是 B？" → 直接 return
   │
   ├─ ② 闲聊短路
   │    handleSystemOnly()
   │    纯 SYSTEM 意图 → "这个问题不需要检索，直接 LLM 答"
   │
   ├─ ③ 检索引擎分流
   │    NodeScoreFilters.kb(intent)   → KB 节点 → 定向或全局 retrieval
   │    NodeScoreFilters.mcp(intent)  → MCP 工具调用（不检索，调用外部工具）
   │    SYSTEM 节点 → 不检索也不调工具
   │    VectorSearchChannel 根据 KB intent 置信度选作用域：
   │      ≥0.6 → 意图定向（收窄命中库）
   │      <0.6 → 全局兜底（全库检索）
   │
   └─ ④ 答题 Prompt 增强
        PromptContext.kbIntents  → "用户在问知识库类问题"
        PromptContext.mcpIntents → "我调了这些工具 + 结果是什么"
```

### 2.4 业内方案对比

#### 方案 1：无意图识别 — 直接检索（基线）

```
Query → Embedding → 全局向量检索 → Rerank → LLM
```

**代表**：早期的朴素 RAG、大多数 RAG 教程 Demo。

| 优点 | 缺点 |
|:---|:---|
| 零配置 | 全库检索噪声大 |
| 零额外 LLM 调用 | 无法区分 KB vs MCP vs 闲聊 |
| 不会分错 | 不会短路、不会引导 |

#### 方案 2：规则路由 — 关键词/正则匹配

```
Query → 关键词匹配"天气" → 调天气 API
      → 关键词匹配"文档" → KB 检索
      → 无匹配 → 全局兜底
```

**代表**：Dify 的早期版本、LangChain 的 `LLMRouterChain`

| 优点 | 缺点 |
|:---|:---|
| 快（不需要 LLM） | 口语化/同义词完全失效 |
| 确定性 100% | 维护成本随规则数量指数增长 |
| 适合固定场景 | 指代消解不了（"它怎么用？"）|

#### 方案 3：语义路由 — Embedding 相似度

```
Query → Embedding → 与每个意图的描述文本算 cosine 距离
      → 最近的那个 → 路由到对应 KB/工具
```

**代表**：Semantic Router（开源库）、LlamaIndex 的 `RouterQueryEngine`

| 优点 | 缺点 |
|:---|:---|
| 不需要 LLM（快、便宜） | 边界模糊的 query 容易被"吸"到语义相近但错误的意图 |
| 描述文本就是配置 | "ADK 和 React 的区别"分数接近 → 无法判定该合并还是分流 |
| 可扩展 | 无法处理组合意图 |

#### 方案 4：LLM 分类 — Ragent 方案

```
Query → LLM 一次性评所有叶子节点 → 返回 [{id, score}, ...]
      → KB 节点 → 定向检索
      → MCP 节点 → 工具调用
      → SYSTEM 节点 → 闲聊
```

| 优点 | 缺点 |
|:---|:---|
| 口语化、指代消解全覆盖 | 每次多一次 LLM 调用（~2K tokens） |
| 能处理复合意图 | 叶子超过 30 个 LLM 容易串分 |
| 分数可调 threshold | 依赖 description 和 examples 质量 |
| 三类路由统一框架 | 树结构需要人为设计 |

#### 方案 5：多 Agent 委托

```
Root Agent → 判断领域
  ├→ 财务 Agent（独立 KB + 工具）
  ├→ 人事 Agent（独立 KB + 工具）
  └→ 通用 Agent
```

**代表**：LangGraph 的 `StateGraph` + sub-agent、AutoGen 的 `GroupChat`

| 优点 | 缺点 |
|:---|:---|
| 每个 Agent 独立迭代、可独立上线 | 调度开销大 |
| 适合企业多部门场景 | Agent 之间传递信息困难 |
| 扩展性最好 | 死循环风险高于分类方案 |

#### 选方案决策框架

```
单一知识库、无工具           → 方案 1（无意图识别），够用不要过度设计
多个 KB、有工具、领域固定     → 方案 2/3（规则/语义路由），成本低
多个 KB、有工具、query 多样   → 方案 4（LLM 分类，Ragent 选型）
多部门、多独立系统、需独立迭代 → 方案 5（多 Agent 委托）
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

### 3.9 Ragent 当前 Query 决策树（完整版）

基于实际代码的完整 Query 处理决策树：

```
用户 Query （StreamChatPipeline.execute → rewriteQuery）
   │
   ▼
① loadMemory()  # 记忆并行加载，不影响 query 决策
   │
   ▼
② MultiQuestionRewriteService.rewriteWithSplit(query, history)
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ 第一档（3.8 实现的）：精确实体短路                       │
│ ExactEntityDetector.hasExactEntity(query)               │
│ 检测：长数字≥4 / 型号 / 日期 / 金额                       │
│                                                          │
│ exact-entities-bypass=true（默认开）                      │
│   ├─ 命中 → 术语归一化 + 规则拆分                          │
│   │         跳过 LLM 改写 + 跳过变体扩展                    │
│   │         RewriteResult{normalized, subs, variants=[]} │
│   │         返回 ↑                                       │
│   └─ 未命中 ↓                                            │
└─────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ 第二档：query-rewrite.enabled 总开关                     │
│                                                          │
│   ├─ false → 术语归一化 + 规则拆分                        │
│   │         RewriteResult base{normalized, subs}          │
│   │         → maybeExpandVariants ↓                      │
│   │                                                      │
│   └─ true  → 术语归一化                                  │
│              callLLMRewriteAndSplit (T=0.1)              │
│              RewriteResult{rewrite, subs}                │
│              → maybeExpandVariants ↓                     │
└─────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ 第三档：Multi-Query 变体扩展（3.7 实现的）                │
│ maybeExpandVariants → shouldExpand：                    │
│                                                          │
│   ① multi-query.enabled=false → 直接返回 base            │
│   ② query 长度 ≤ min-query-chars(10) → 触发              │
│   ③ 已多个子问题 → 不扩展（拆分已覆盖多角度）            │
│   ④ 改写后 ≤ 20 字符（模糊） → 触发                       │
│                                                          │
│   触发 → generateVariants (T=0.7) 生成 3 个变体          │
│           variants = [原始rewrite, 变体1, 变体2, ...]    │
└─────────────────────────────────────────────────────────┘
   │
   ▼
最终输出 RewriteResult{rewrittenQuestion, subQuestions, variants}
   │
   ▼
③ IntentResolver.resolve() → 每个 subQuestion 生成 SubQuestionIntent{variantQueries}
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ 第四档：意图分流（NodeScoreFilters）                    │
│ DefaultIntentClassifier.classifyTargets (T=0.1)         │
│ → LLM 给叶子节点打分                                     │
│                                                          │
│   ├─ KB 节点高分 → RetrievalEngine 检索                  │
│   ├─ MCP 节点高分 → McpToolExecutor 工具调用              │
│   └─ SYSTEM 节点  → 闲聊短路                              │
└─────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ 第五档：歧义 / 闲聊短路（在意图识别之后）                │
│                                                          │
│   ├─ handleGuidance()：歧义引导（多意图低分）→ 短路      │
│   └─ handleSystemOnly()：纯 SYSTEM 意图 → 闲聊短路      │
└─────────────────────────────────────────────────────────┘
   │
   ▼
⑥ RetrievalEngine.retrieve()  # 检索引擎
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ 第六档：变体触发分支                                      │
│ variantQueries.size() > 1？                              │
│   ├─ 是 → retrieveWithVariants()：每个变体并行多通道检索  │
│   │        LinkedHashMap 按 chunkId 去重合并            │
│   │        变体 0=原始 rewrite 优先级最高                │
│   └─ 否 → 单 query 多通道检索                            │
└─────────────────────────────────────────────────────────┘
   │
   ▼
⑦ 后处理器链：Dedup → RRF Fusion → Rerank → MetadataEnrich
   │
   ▼
⑧ streamLLMResponse()  # 答题（T=0 或 MCP 时 0.3）
```

#### 与业界决策树的对照矩阵

| 业界决策树分支 | Ragent 实现 | 状态 |
|:---|:---|:---|
| 含精确数字/人名/型号 → 直接检索 | ExactEntityDetector 短路 | ✅ 3.8 实现 |
| 质量差 → Query 改写 | query-rewrite.enabled + LLM 改写 | ✅ 已实现 |
| 缺背景 → Step-back | 无 | ❌ 未实现 |
| 短 query/零样本 → HyDE | 用 Multi-Query 变体扩展替代覆盖 | ✅ 有替代方案 |
| 多义/多跳 → Multi-Query | maybeExpandVariants（短 query 触发） | ✅ 3.7 实现 |
| 直接检索（不需要改写） | 短路返回 RewriteResult{原值} | ✅ 已实现 |

#### Ragent 这套相比业界版的特点

**1. 把"判断"放在 query 入口最前，避免无效 LLM 调用**

业界决策树是"层层判断每个分支是否触发"，Ragent 的实现是短路串行——先判断精确实体（最便宜，纯正则），再判断总开关，再判断变体触发。每个判断从前到后越来越贵，能短路就短路。

**2. HyDE 没做，用 Multi-Query 顶替**

HyDE 和 Multi-Query 在短 query 场景功能重叠 70%——都是产生多个"延伸查询"提升召回。Ragent 用 Multi-Query 变体扩展覆盖了这个能力，省一次 LLM 调用（HyDE 要先"生成假文档"再 embed，比变体生成贵）。

**3. Step-back 没做**

Step-back 是"对太具体的 query 让 LLM 提炼出更抽象的问题，双路检索"——比如 `"P6 的薪资"` → `"各职级薪资结构"` + 原始 query。决策树里没有这一层，目前也没有触发判断——少这档是合理的取舍。

**4. 决策树隐藏在意图识别分流里**

业界决策树没画意图识别这一格，但 Ragent 把意图识别当成"分流回到哪条检索路径"——KB 节点回 retrieve、MCP 节点回工具、SYSTEM 短路闲聊。这等同于把决策树的某些分支提前到了意图识别阶段。

**5. query-rewrite.enabled=false 时短路仍在第一位生效**

源码细节（`MultiQuestionRewriteService.java:71-89`）：精确实体短路在 `query-rewrite.enabled` 总开关判断**之前**。所以即使总开关关了，遇到精确实体仍然会短路——其实让它走 LLM 改写也走不到（因为总开关关了），这条短路在总关时是冗余的优化，但不会出错，且让"含实体的 query 不浪费 LLM"这个语义在两个开关的任意组合下都成立。

#### Q&A：业界没覆盖的，你的也没净做到

**Q: 为什么没有 Step-back？**
> Step-back 对"问得窄但需要背景知识"的 query 才有用（如"P6 薪资"问各职级薪资结构背景），这种场景在知识库问答中占比不高。增加 Step-back 需要：① 一次额外 LLM 调用生成更抽象的问题；② 多跑一路检索；③ 融合两路结果。成本翻倍，收益集中在特定问类型。先放着，等评测显示具体类型问题检索质量差再补。

**Q: 为什么没用 HyDE？**
> HyDE 用 LLM 生成"假文档"再 embed，本质上和 Multi-Query 一样是"扩展语义角度"，但贵一次 LLM 调用。我做的 Multi-Query 变体扩展在短 query 场景已经覆盖了 HyDE 想解决的问题——多个变体并行检索后去重合并，召回率提升且成本更低。重叠达 70%，没必要都做。

**Q: 短路和 query-rewrite 总开关的优先级是不是不合理？**
> 设计上是有意的。精确实体短路永远在最前面，无论总开关开不开——逻辑上"含 P6 15000 这种具体值的 query 没必要改写"应该比"是否启 LLM 改写"更优先判定。如果总开关关了，命中实体也是走归一化+规则拆分，等价结果但走两次短路有点浪费——这是冗余（不是 bug），简化逻辑顺序的代价。可以接受。

### 3.8 Query 决策树第一档：精确实体短路（exact-entities-bypass）

#### 改造动机

业界推荐的 Query 处理决策树第一档：**含精确数字 / 人名 / 型号 / 日期的 query，直接检索或走 metadata filter，跳过 LLM 改写**。

**痛点**：Ragent 原 `query-rewrite.enabled=true` 时对每个 query 都调 LLM 改写。对 `"Bob 在 2024 年的薪资"`这种含精确实体的 query：
- LLM 可能把 `"P6 基本工资 15000"` 改成 `"职级 6 工资 15000"` —— 泛化掉具体型号，向量召回跑偏
- 改写本身 1 次 LLM 调用 + 可能再触发变体扩展 1 次 LLM 调用 — 对自包含 query 是冗余成本

**目标**：纯规则、零成本、不引入新模块，在 query 入口最前短路掉改写。

#### 实现：ExactEntityDetector + maybeBypassForExactEntity

```java
// ExactEntityDetector：4 类正则的精确实体检测器
LONG_NUMBER       = \d{4,}                                            // 4 位以上纯数字（年份/工号/订单号/大额值）
                                                                       // 例：2024、2083847186960904192、15000

ALPHANUMERIC_CODE = (?iu)(?=.*\d)[A-Z0-9]+(?:[-_/][A-Z0-9]+)+
                       |(?=[A-Z]*\d)[A-Z]+\d+[A-Z0-9]*
                       |\d+[A-Z]+[A-Z0-9]*                            // 字母数字型号（≥2 段或字母数字紧贴）
                                                                       // 例：P6、A8-C3x、RAG-001、iPhone15

ISO_DATE          = \d{4}[-/.年]\d{1,2}([月/-/.]\d{1,2}日?)?            // 日期
                                                                       // 例：2024-06-30、2024年6月15日、2024.07

AMOUNT            = (?i)([$￥]\s?\d|\d+\s?(?:万元|k|m|%)|\d+\s?(?:元|块))   // 金额/百分比
                                                                       // 例：$1500、5万元、20%
```

**短路插入点**：`MultiQuestionRewriteService.rewriteWithSplit()` 入口最前端，两个重载（带 history 和不带 history）都加：

```java
public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
    // 精确实体短路：含数字/型号/日期等强实体的 query 不走 LLM 改写，避免泛化跑偏
    RewriteResult bypassed = maybeBypassForExactEntity(userQuestion);
    if (bypassed != null) return bypassed;

    if (!ragConfigProperties.getQueryRewriteEnabled()) { ... }      // 原 LLM 改写流程
    String normalizedQuestion = queryTermMappingService.normalize(userQuestion);
    RewriteResult fromLLM = callLLMRewriteAndSplit(...);            // ← 命中实体时跳过此调用
    return maybeExpandVariants(userQuestion, fromLLM);               // ← 命中实体时也跳过此调用
}

private RewriteResult maybeBypassForExactEntity(String userQuestion) {
    if (!Boolean.TRUE.equals(ragConfigProperties.getExactEntitiesBypass())) return null;
    if (!exactEntityDetector.hasExactEntity(userQuestion)) return null;
    String normalized = queryTermMappingService.normalize(userQuestion);
    List<String> subs = ruleBasedSplit(normalized);
    log.info("精确实体短路命中，跳过 LLM 改写与变体扩展 - 原始 query='{}' 归一化='{}'", ...);
    return new RewriteResult(normalized, subs);   // 仅做术语归一化 + 规则拆分
}
```

#### 关键设计决策

1. **短路跳过 LLM 改写 + 也跳过 maybeExpandVariants**：精确实体 query 语义自包含，生成变体反而稀释召回（变体覆盖其他角度相当于把"P6"漂移到其他职级）
2. **保留术语归一化**：`queryTermMappingService.normalize()` 仍执行——这是 DB/Redis 映射表查询，零成本，且让"平安保司"还能归一化成"平安保险公司"，不影响术语统一
3. **_SHORT 数字不误中**：长数字阈值 4 位才触发（`"3 个步骤"` 中的 3 不命中、`"5 行实现"` 中的 5 不命中）。FIVED-数字型号正则要求"至少一段含数字 + 字母数字混合"，避免"OA 系统"这种纯文本误中
4. **`(?iu)` Unicode + 不区分大小写**：兼容小写开头的型号（`iPhone15`、`SKU2024`）
5. **默认开启**：`rag.query-rewrite.exact-entities-bypass: true`。改动纯增量，不影响存量行为，老 query 走不到这条短路就是原路径
6. **设计正交**：与 `query-rewrite.enabled`、`multi-query.enabled` 完全独立——前者关闭时短路仍在第一个判定执行，二者关闭时短路也能命中

#### 测试覆盖

`ExactEntityDetectorTest` 7 个用例：

```
✅ 长数字：   "Bob 在 2024 年的薪资"、"订单号 2083847186960904192 状态"、"P6 基本工资 15000"
✅ 型号：     "iPhone15 的续航"、"查询 RAG-001 的状态"、"A8-C3x 配置说明"
✅ 日期：     "2024-06-30 上线的功能"、"2024年6月15日 出的 bug"、"2024/6/15 版本"
✅ 金额：     "满 $1500 免运费"、"月销 5万元 的门店"、"占比 20% 的部分"
❌ 纯文本：   "Block 体系是什么"、"RAG 流水线怎么做的"、"意图识别怎么实现的"（不命中）
❌ 短数字：   "3 个步骤"、"用 5 行实现"（不命中，避免误伤）
❌ 空输入：   null / "" / "   "（不命中）
```

跑测试：`./mvnw -pl bootstrap -Dtest=ExactEntityDetectorTest test`

#### 改动汇总

| 文件 | 改动 |
|:---|:---|
| `ExactEntityDetector.java`（新） | 4 类正则的精确实体检测器组件 |
| `RAGConfigProperties.java` | 新增 `exactEntitiesBypass` 字段（默认 true） |
| `MultiQuestionRewriteService.java` | 注入 `ExactEntityDetector`，两个入口都加 `maybeBypassForExactEntity` 短路 |
| `application.yaml` | `rag.query-rewrite.exact-entities-bypass: true` |
| `ExactEntityDetectorTest.java`（新） | 7 个用例覆盖 4 类命中 + 3 类不命中 |

#### 面试话术

> 业界推荐的 Query 处理决策树第一档，是"含精确数字/型号/日期的 query 直接跳过改写走原 query"。Ragent 原来对每个 query 无脑 LLM 改写，"P6 15000"可能被泛化成"职级 6 工资 15000"，向量召回跑偏。我加了 ExactEntityDetector 4 类正则（长数字/型号/日期/金额）在 query 入口最前短路——命中实体的话跳过 LLM 改写和变体扩展，只做术语归一化加规则拆分，省 1-2 次 LLM 调用且不跑偏。正则阈值刻意保守：长数字≥4 位才触发避免误中"3 个步骤"，型号正则要求字母数字混合避免误中"OA 系统"，加 (?iu) 兼容小写开头型号如 iPhone15。开关默认开启、7 个单元测试覆盖，与 query-rewrite.enabled / multi-query.enabled 完全正交，不影响存量行为。
>
> 当时也评估了决策树其他几档——Step-back、HyDE 没做：HyDE 跟我做的 Multi-Query 变体扩展在短 query 场景功能重叠 70%，再上重复造轮子；Step-back 对"问得窄但需要背景"的 query 才有用，单独评估再加。完整 6 层决策树不建议一次全上维护成本高于收益。这一档代价最低、收益最直接，先做。

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

### 5.5 多轮对话与指代消解——怎么判断用户 Query 和上文有关联

**不在意图识别里判断，在 Query 改写里处理**。两步串联：先改写消解指代，再意图识别分类。

```
用户第 1 轮: "Ragent 的检索通道有哪些"
  → 改写: "Ragent 的检索通道有哪些"（首轮，无历史）
  → 意图: ragent-retrieval (0.95)

用户第 2 轮: "它的实现原理是什么"
  → 改写时把最近 2 轮对话历史一起发给 LLM
        历史消息:
          user: "Ragent 的检索通道有哪些"
          assistant: "Ragent 使用混合检索，包括语义向量检索和关键词稀疏检索..."
        当前 query: "它的实现原理是什么"
  → LLM 改写: "Ragent 检索通道的实现原理"  ← "它"被消解为"Ragent 检索通道"
  → 意图: ragent-retrieval（正确命中同一个叶子）
```

**关键代码**（`MultiQuestionRewriteService.buildRewriteRequest` 行 174）：

```java
// 只保留最近 1-2 轮的 User 和 Assistant 消息（最多 4 条）
List<ChatMessage> recentHistory = history.stream()
    .filter(msg -> msg.getRole() == USER || msg.getRole() == ASSISTANT)
    .skip(Math.max(0, history.size() - 4))  // 最多保留最近 4 条消息（2 轮对话）
    .toList();
messages.addAll(recentHistory);    // 历史拼在 system prompt 之后
messages.add(ChatMessage.user(question)); // 当前 query 放最后
```

**改写 Prompt 里的一句关键指令**（`user-question-rewrite.st` 行 36）：

```
## 特殊场景
- 指代词（"它"、"这个"）：结合历史消息还原具体实体
```

**示例**（`user-question-rewrite.st` 行 96）：

```
## 示例 5：指代消解（结合历史）
历史：用户问"12306系统的架构是什么"
输入：它的数据库用什么？
输出：{"rewrite": "12306系统的数据库", "should_split": false,
       "sub_questions": ["12306系统的数据库"]}
```

#### 业内其他做法

| 方案 | 做法 | 代表 |
|:---|:---|:---|
| **改写消解**（Ragent） | 改写阶段带历史，LLM 消解指代后意图识别 | Ragent、LangChain `CondenseQuestionChain` |
| **意图识别直接带历史** | 不先改写，意图识别的 Prompt 里同时带历史和当前 query | 部分 ReAct Agent |
| **窗口拼接** | 不做消解，直接把最近 N 轮 query 全拼成一条去检索 | 最简单但噪声最大 |
| **独立指代消解模型** | 用专门的 coreference resolution 模型先消解，再改 | 学术方案，生产少见 |

**Ragent 选改写消解的原因**：不增加独立 LLM 调用（消解和改写合并为一步），利用改写 Prompt 里的指代规则，成本和效果平衡。缺点是历史只有最近 2 轮（最多 4 条消息），更早的上下文无法消解。

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