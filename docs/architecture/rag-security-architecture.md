# RAG Pipeline 架构修复方案

## 1. 问题总览

基于对 `demo.java` 的"读取文档 → 拼上下文 → 喂 LLM"链路的分析，在现有 `StreamChatPipeline` 架构基础上，识别出以下两类问题：

### 1.1 安全类问题

| 编号 | 问题 | 严重级别 | 当前状态 |
|------|------|---------|---------|
| S1 | 无文档级权限控制 | 严重 | `SearchContext` 不携带用户身份，检索通道无 ACL 过滤 |
| S2 | Prompt Injection（用户输入） | 严重 | 用户问题经改写后直接拼入 `<question>` 标签，无清洗 |
| S3 | 间接 Prompt Injection（文档投毒） | 严重 | 检索结果直接拼入 `<content>`，无内容安全扫描 |
| S4 | 敏感信息泄露 | 高 | 文档内容不脱敏直接传给 LLM，PII/密钥可能外泄 |
| S5 | 无审计追踪 | 高 | 全链路无"谁查了什么"的记录 |
| S6 | 无防数据提取限流 | 中 | `FairDistributedRateLimiter` 仅做并发控制，不做用户级防滥用 |

### 1.2 RAG 链路功能缺失

| 编号 | 缺失环节 | 影响 | 当前状态 |
|------|---------|------|---------|
| F1 | 无上下文窗口预算管理 | 检索结果 + 历史消息可能超出 LLM token 上限 | `TokenCounterService` 已存在但 Pipeline 中未使用 |
| F2 | 无相关性阈值过滤 | 低相关 chunk 进入 Prompt，引入噪声降低回答质量 | Rerank 后仅按 topK 截断，不设最低分阈值 |
| F3 | 无检索质量分级兜底 | 只处理"完全空结果"，不处理"有结果但质量差" | `handleEmptyRetrieval` 仅判空 |
| F4 | 无可溯源引用机制 | LLM 回答无法追溯到具体文档，用户无法验证 | Prompt 模板明确禁止标注出处 |
| F5 | 无语义级去重 | 不同 chunk 内容高度相似时仍重复喂给 LLM | Dedup 仅按 ID/文本哈希去重，不处理近重复 |
| F6 | 无上下文压缩 | 长文档 chunk 直接拼接，占满 token 预算 | `DefaultContextFormatter` 仅做拼接，无摘要/压缩 |
| F7 | 无检索结果多样性保障 | Top-K 可能全部来自同一文档、同一角度 | Rerank 纯按相关性排序，无 MMR 等多样性策略 |
| F8 | 多轮对话无上下文裁剪 | 历史消息 + 检索上下文叠加可能超出窗口 | `loadMemory` 无 token 感知，不裁剪历史 |
| F9 | 无检索质量评估 | 无法感知本次检索效果好坏，缺乏反馈闭环 | 无 chunk 级相关性标注或评估 |
| F10 | 查询类型无差异化策略 | 事实查询/对比分析/流程问答用同一套检索参数 | 4 通道统一 topK，不分查询类型 |

## 2. 现有架构分析

### 2.1 当前链路数据流

```
用户问题
  │
  ▼
① loadMemory ─── 加载历史消息（无 token 预算）
  │
  ▼
② rewriteQuery ─── LLM 改写 + 多问句拆分
  │
  ▼
③ resolveIntents ─── 意图树打分（score >= 0.35 且 top 3）
  │
  ▼
⑥ retrieve ─── 4 通道并行检索 → Dedup → RRF → Rerank(topK) → MetadataEnrich
  │
  ▼
⑦ streamRagResponse ─── PromptContext 组装 → Prompt 模板渲染 → LLM 流式输出
```

### 2.2 关键间隙识别

以下是对照标准 RAG 管线的逐环节分析：

```
┌──────────────────────┐
│  Pre-Retrieval       │  ← ② rewriteQuery 已做（改写/拆分/Multi-Query）
│  (查询优化)          │
├──────────────────────┤
│  Retrieval           │  ← ⑥ 4 通道并行 ✓
│  (多路召回)          │
├──────────────────────┤
│  Post-Retrieval      │  ← Dedup ✓ / RRF ✓ / Rerank ✓ / MetadataEnrich ✓
│  (后处理)            │     ✗ 无语义去重(F5)
│                      │     ✗ 无相关性阈值过滤(F2)
│                      │     ✗ 无多样性保障(F7)
│                      │     ✗ 无上下文压缩(F6)
├──────────────────────┤
│  Context Assembly    │  ← ⑦ PromptContext + RAGPromptService
│  (上下文组装)        │     ✗ 无 token 预算管理(F1)
│                      │     ✗ 无引用标注机制(F4)
│                      │     ✗ 历史消息无裁剪(F8)
├──────────────────────┤
│  LLM Generation      │  ← ⑦ LLM 流式输出 ✓
│  (生成)              │
├──────────────────────┤
│  Post-Generation     │  ← ✗ 完全缺失
│  (后处理/评估)       │     ✗ 无检索质量评估(F9)
│                      │     ✗ 无引用解析
│                      │     ✗ 无反馈闭环
└──────────────────────┘
```

### 2.3 可复用的基础设施

```
TokenCounterService                → Token 估算服务已存在（HeuristicTokenCounterService）
  └── countTokens(String) → Integer

MultiChannelRetrievalEngine        → 后置处理器链模式（策略模式）
  └── SearchResultPostProcessor 链 → Dedup → RRF → Rerank → MetadataEnrichment

UserContext (ThreadLocal)          → 用户身份已在线程上下文中
  ├── LoginUser.userId
  ├── LoginUser.role
  └── LoginUser.username

FairDistributedRateLimiter         → 分布式限流框架（可按 userId 实例化）
StreamChatContext.userId           → Pipeline 已携带 userId
```

---

## 3. RAG 链路功能修复（F1-F10）

### 3.1 整体架构图（加入功能修复层）

```
┌──────────────────────────────────────────────────────────────────────┐
│                       StreamChatPipeline                              │
│                                                                      │
│  ① loadMemory   ② rewriteQuery  ③ resolveIntents                    │
│  ④ handleGuidance  ⑤ handleSystemOnly                               │
│  ⑥ retrieve ─────────────────────────────────────────────┐           │
│  ⑦ streamRagResponse ─── Prompt 组装 → LLM 输出          │           │
│                                                           │           │
│  ┌───────────────────────────────────────────────────────┤           │
│  │              功能修复层（新增/增强）                    │           │
│  │                                                       │           │
│  │  🔵 Pre-Retrieval（已存在 ✓）                         │           │
│  │                                                       │           │
│  │  🔵 Retrieval ───────────────────────────────────────┐│           │
│  │  │  Vector │ Keyword │ Graph │ WebSearch              ││           │
│  │  └───────────────────────────────────────────────────┘│           │
│  │                                                       │           │
│  │  🟢 Post-Retrieval（新增 F2/F5/F6/F7）                │           │
│  │  ┌────────────┐ ┌──────────────┐ ┌─────────────────┐ │           │
│  │  │SemanticDedup│ │RelevanceFilter│ │DiversityRanker │ │           │
│  │  │ (F5)        │ │ (F2)          │ │ (F7/MMR)       │ │           │
│  │  └────────────┘ └──────────────┘ └─────────────────┘ │           │
│  │  ┌──────────────────┐                                │           │
│  │  │ContextCompressor  │                                │           │
│  │  │ (F6)              │                                │           │
│  │  └──────────────────┘                                │           │
│  │                                                       │           │
│  │  🟢 Context Assembly（新增 F1/F4/F8）                 │           │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ │           │
│  │  │TokenBudget    │ │CitationMarker│ │HistoryTrimmer│ │           │
│  │  │ (F1)          │ │ (F4)         │ │ (F8)         │ │           │
│  │  └──────────────┘ └──────────────┘ └──────────────┘ │           │
│  │                                                       │           │
│  │  🟢 Post-Generation（新增 F3/F9）                     │           │
│  │  ┌──────────────┐ ┌──────────────┐                   │           │
│  │  │QualityGrader  │ │RetrievalEval │                   │           │
│  │  │ (F3)          │ │ (F9)         │                   │           │
│  │  └──────────────┘ └──────────────┘                   │           │
│  └───────────────────────────────────────────────────────┤           │
└──────────────────────────────────────────────────────────┘           │
```

### 3.2 F1 — 上下文窗口预算管理

**问题**：`TokenCounterService` 已存在，但 Pipeline 中从未调用。检索结果 + 历史消息 + 系统 Prompt 可能超出 LLM 上下文窗口（如 128K），超出后要么请求被拒，要么 LLM 端静默截断丢失关键信息。

**位置**：`rag.core.prompt.TokenBudgetManager`

**接入点**：`StreamChatPipeline.streamRagResponse()` 中，Prompt 组装之前

**设计**：

```
TokenBudgetManager:
  - modelContextLimit: 128000 (模型上下文窗口，可配置)
  - systemPromptTokens: 系统 Prompt 固定消耗
  - historyTokens: 历史消息消耗
  - generationReserve: 4096 (为 LLM 回答预留的 token)
  - availableForContext = modelContextLimit - systemPromptTokens - historyTokens - generationReserve

ContextBudget contextBudget = budgetManager.calculate(ctx);
// 按 availableForContext 截断检索上下文
// 截断策略：按 Rerank 分数从高到低逐 chunk 累加，不超过预算
List<RetrievedChunk> budgeted = budgetManager.fitToBudget(chunks, contextBudget);
```

**关键点**：
- 复用 `HeuristicTokenCounterService` 估算 token 数
- 截断优先保留高分 chunk，而非简单截头/截尾
- 截断后日志记录实际截断量，用于监控调优
- 配置项：`rag.context.max-tokens: 90000`

### 3.3 F2 — 相关性阈值过滤

**问题**：Rerank 后仅按 topK 截断，无最低分阈值。Rerank 分数 0.1 的 chunk 和 0.9 的 chunk 一样进入 Prompt，低相关 chunk 引入噪声。

**位置**：`rag.core.retrieval.postprocessor.RelevanceFilter`

**接入点**：`MultiChannelRetrievalEngine.postProcessors` 链中，Rerank 之后、MetadataEnrichment 之前（order=15）

**设计**：

```
RelevanceFilter implements SearchResultPostProcessor:
  order: 15  // Rerank(10) 之后，MetadataEnrichment(20) 之前

  process(chunks, results, context):
    float threshold = properties.getRelevanceThreshold()  // 默认 0.3
    List<RetrievedChunk> filtered = chunks.stream()
        .filter(c -> c.getScore() == null || c.getScore() >= threshold)
        .toList()
    // 过滤后如果为空，不阻拦——下游 handleEmptyRetrieval 兜底
    return filtered

配置：
  rag.retrieval.relevance-threshold: 0.3
```

**关键点**：
- 阈值可配置，不同意图节点可覆盖（`IntentNode.minRelevanceScore`）
- 过滤后为空不报错，走现有 `handleEmptyRetrieval` 兜底
- 记录过滤掉的 chunk 数量，用于归因分析

### 3.4 F3 — 检索质量分级兜底

**问题**：`handleEmptyRetrieval` 只处理"完全空结果"，不处理"有结果但全是低质量"的情况。

**位置**：`StreamChatPipeline.handleEmptyRetrieval()` 增强

**设计**：

```
// 将 handleEmptyRetrieval 升级为 handlePoorRetrieval
// 三级判断：

① ctx.isEmpty() → 无结果
  → "未检索到与问题相关的文档内容。"

② ctx.avgScore() < 0.3 → 低质量结果
  → "当前检索到部分相关内容，但不确定是否能准确回答您的问题。以下是根据现有信息能确认的部分："
  → 仍然走 RAG 流程，但调整 temperature 和 system prompt 中的确定性表述

③ ctx.avgScore() >= 0.3 → 正常质量
  → 正常 RAG 流程

RetrievalContext 扩展：
  + avgScore: Float          // 检索结果平均相关性分
  + minScore: Float          // 最低分
  + qualityLevel: Quality    // HIGH / MEDIUM / LOW / EMPTY
```

### 3.5 F4 — 可溯源引用机制

**问题**：Prompt 模板要求"不要在正文里主动标注信息的出处"，用户无法验证回答来源。而且 demo 中 `Document::getContent` 丢弃了文档元数据，完全无法溯源。

**位置**：`rag.core.prompt.CitationMarker` + Prompt 模板修改

**设计**：

```
// 1. 上下文格式化时注入引用 ID
// 当前 context-format.st：
<content source="{source}">{chunks}</content>
// 修改为：
<content source="{source}" citation-id="[1]">{chunks}</content>

// 2. System Prompt 中增加引用指令
// 在 answer-chat-kb.st 中新增：
引用规范：
- 回答中引用资料事实时，在句末标注引用编号，如「……项目采用微服务架构[1][3]」
- 同一事实引用多个来源时，多个编号并列
- 引用标记只放在事实陈述后，不放标题后

// 3. 生成后引用解析
CitationResolver:
  - 从 LLM 回答中提取 [1][3] 等引用标记
  - 映射到 RetrievedChunk（docName, chunkId）
  - 在 SSE 流的最后追加引用列表：
    {
      "citations": [
        {"id": 1, "docName": "技术架构文档.pdf", "chunkId": "chunk_001"},
        {"id": 3, "docName": "部署手册.pdf", "chunkId": "chunk_042"}
      ]
    }
```

**关键点**：
- 引用 ID 在 `DefaultContextFormatter` 渲染时按文档顺序自动编号
- 不在 Prompt 模板中暴露 chunk 内部 ID，只用 `[1][2]` 序号
- 回答完成后通过 SSE `event: citations` 下发引用映射
- 前端可据此渲染"来源"卡片

### 3.6 F5 — 语义级去重

**问题**：`DeduplicationPostProcessor` 仅按 ID/文本哈希去重。两个 chunk 来自不同文档但内容 90% 相似时，都会进 Prompt。

**位置**：`rag.core.retrieval.postprocessor.SemanticDedupPostProcessor`

**接入点**：`MultiChannelRetrievalEngine.postProcessors` 链中，Dedup(1) 之后、Fusion(5) 之前（order=3）

**设计**：

```
SemanticDedupPostProcessor implements SearchResultPostProcessor:
  order: 3   // Dedup(1) 之后，Fusion(5) 之前

  process(chunks, results, context):
    // 策略：基于文本的 Jaccard 相似度，阈值 0.85
    // 对 Rerank 前的候选池做语义去重，减少送入 Rerank 的冗余
    List<RetrievedChunk> deduped = new ArrayList<>()
    for chunk in chunks:
      boolean isDuplicate = deduped.stream()
          .anyMatch(existing -> jaccardSimilarity(chunk.text, existing.text) > 0.85)
      if (!isDuplicate) deduped.add(chunk)
    return deduped

  jaccardSimilarity(a, b):
    // 基于字符 n-gram (n=3) 的 Jaccard 系数
    Set<String> setA = ngrams(a, 3)
    Set<String> setB = ngrams(b, 3)
    intersection = setA ∩ setB
    union = setA ∪ setB
    return intersection.size() / union.size()
```

**关键点**：
- 不做精确匹配，用 n-gram Jaccard 容忍微小差异（如标点、空格）
- 阈值 0.85 可配置
- 保留先出现的 chunk（相关性排序靠前的）

### 3.7 F6 — 上下文压缩

**问题**：`DefaultContextFormatter` 仅做拼接，单个 chunk 可能 2000+ token，5 个 chunk 轻松过万。无压缩策略。

**位置**：`rag.core.retrieval.postprocessor.ContextCompressor`

**接入点**：`MultiChannelRetrievalEngine.postProcessors` 链中，MetadataEnrichment(20) 之后（order=25），或在 `DefaultContextFormatter` 渲染前

**设计**：

```
ContextCompressor implements SearchResultPostProcessor:
  order: 25

  process(chunks, results, context):
    // 对超出长度阈值的单个 chunk 做 LLM 摘要压缩
    int maxChunkTokens = properties.getMaxChunkTokens()  // 默认 500
    for chunk in chunks:
      if tokenCounter.countTokens(chunk.text) > maxChunkTokens:
        chunk.text = summarizeChunk(chunk.text, maxChunkTokens)
    return chunks

  summarizeChunk(text, maxTokens):
    // 调用轻量 LLM 做 extractive summary
    // 保留关键实体、数字、日期、流程步骤
    // 去掉冗余修饰、重复段落
    return llmService.compact(text, maxTokens)
```

**关键点**：
- 只压缩超长 chunk，短 chunk 保持原样
- 摘要保留关键信息（实体、数字、日期），丢弃修饰语
- 开关控制：`rag.context.compression.enabled: true`
- 压缩策略可选：extractive（低成本）vs. abstractive（高质量）

### 3.8 F7 — 检索结果多样性保障（MMR）

**问题**：Rerank 纯按相关性排序，top-5 可能全部来自同一篇文档的同一段落。LLM 看到的视角单一。

**位置**：`rag.core.retrieval.postprocessor.DiversityRanker`

**接入点**：`MultiChannelRetrievalEngine.postProcessors` 链中，Rerank(10) 之后、MetadataEnrichment(20) 之前（order=17）

**设计**：

```
DiversityRanker implements SearchResultPostProcessor:
  order: 17  // Rerank(10) 之后，RelevanceFilter(15) 之后

  process(chunks, results, context):
    // MMR (Maximal Marginal Relevance):
    // 迭代选择：每次选一个 chunk，既要相关（Rerank 分高），
    // 又要与已选 chunk 不相似（基于文本向量余弦距离）
    float lambda = 0.7  // 相关性 vs 多样性权重，0.7 偏相关
    List<RetrievedChunk> selected = mmrSelect(chunks, context.getTopK(), lambda)
    return selected

  mmrSelect(candidates, k, lambda):
    selected = []
    remaining = new ArrayList<>(candidates)
    // 第一个选 Rerank 分最高的
    selected.add(remaining.remove(0))
    while selected.size() < k && !remaining.isEmpty():
      for each candidate in remaining:
        relevance = candidate.score
        maxSimilarity = max(cosineSimilarity(candidate, s) for s in selected)
        mmr = lambda * relevance - (1 - lambda) * maxSimilarity
      best = argmax(mmr)
      selected.add(remaining.remove(best))
    return selected
```

**关键点**：
- MMR 参数 λ=0.7 可配置
- 优先保证来自不同文档、不同段落的 chunk 入选
- 与 RelevanceFilter 配合：先过滤低分，再 MMR 多样化

### 3.9 F8 — 多轮对话历史裁剪

**问题**：`loadMemory` 加载历史消息时不考虑 token 预算。历史消息 + 检索上下文叠加可能超出 LLM 窗口。

**位置**：`ConversationMemoryService` 增强 + `StreamChatPipeline.loadMemory()` 增强

**设计**：

```
// 在 loadMemory 阶段增加 token 感知裁剪
// 修改 StreamChatPipeline.loadMemory():

private void loadMemory(StreamChatContext ctx) {
    List<ChatMessage> history = memoryService.loadAndAppend(
            ctx.getConversationId(), ctx.getUserId(), ChatMessage.user(ctx.getQuestion())
    );
    // 新增：按 token 预算裁剪历史
    int maxHistoryTokens = ragConfigProperties.getMaxHistoryTokens();  // 默认 8000
    List<ChatMessage> trimmed = trimHistoryToBudget(history, maxHistoryTokens);
    ctx.setHistory(trimmed);
}

trimHistoryToBudget(history, maxTokens):
    // 从最近的消息开始保留，超出预算的旧消息丢弃
    // 但保留摘要消息（第一条 system summary）不丢弃
    int used = 0
    List<ChatMessage> kept = new ArrayList<>()
    for msg in reverse(history):
        int msgTokens = tokenCounter.countTokens(msg.content)
        if used + msgTokens <= maxTokens || msg.role == "system" && msg.isSummary:
            kept.add(0, msg)
            used += msgTokens
    return kept
```

### 3.10 F9 — 检索质量评估

**问题**：无反馈闭环，无法感知本次检索效果好坏。

**位置**：`rag.core.eval.RetrievalQualityEvaluator`

**接入点**：`StreamChatPipeline.execute()` 末尾，LLM 回答完成后异步执行

**设计**：

```
RetrievalQualityEvaluator:
  // 评估维度：让 LLM 判断每个 chunk 是否与回答内容相关
  // 计算：命中率 = 被回答引用的 chunk 数 / 总 chunk 数

  evaluate(question, answer, chunks):
    for each chunk:
      // 异步调小模型判断：这个 chunk 的内容是否出现在回答中？
      boolean cited = llmService.judgeRelevance(chunk.text, answer)
    float hitRate = citedCount / totalChunks
    // 写入 ES 索引 rag_retrieval_quality
    // 用于后续分析：哪些意图的检索质量差？哪些通道贡献低？
    return EvaluationResult { hitRate, channelBreakdown, intentBreakdown }
```

**关键点**：
- 异步执行，不阻塞用户获取回答
- 用低成本小模型做判断（如 embedding 相似度），不调大模型
- 聚合数据用于离线优化：调整 topK、Rerank 阈值、通道权重

### 3.11 F10 — 查询类型差异化策略（远期规划）

**问题**：事实查询、对比分析、流程问答用同一套检索参数，不做区分。

**设计思路**（远期，不在本次实施范围）：

```
// 在 rewriteQuery 阶段增加查询类型分类
QueryType classifyQuery(question):
  - FACTUAL: "什么是XX" → 高精度，topK=3，threshold=0.5
  - COMPARATIVE: "A和B区别" → 多样性优先，MMR λ=0.5，topK=6
  - PROCEDURAL: "怎么做XX" → 长文本，maxChunkTokens=800，topK=5
  - EXPLORATORY: "有哪些XX" → 高召回，topK=10，threshold=0.2

// 根据查询类型动态调整检索参数
```

---

## 4. 安全修复（S1-S6）

### 4.1 整体架构图

```
┌──────────────────────────────────────────────────────────────────────┐
│                        RAGChatServiceImpl                             │
│  UserContext.getUserId() ─────────────────────────────────┐          │
└──────────────────────────┬─────────────────────────────────┤          │
                           ▼                                 │          │
┌──────────────────────────────────────────────────────────────┐       │
│                    StreamChatPipeline                         │       │
│  ① loadMemory   ② rewriteQuery  ③ resolveIntents             │       │
│  ④ handleGuidance  ⑤ handleSystemOnly                        │       │
│  ⑥ retrieve ──────────────────────────────────────────┐      │       │
│  ⑦ streamRagResponse                                   │      │       │
└──────────────────────────────────────────────────────────────┘       │
                           │                                 │          │
                           ▼                                 ▼          │
┌──────────────────────────────────────────────────────────────────┐   │
│                    安全加固层（新增）                              │   │
│                                                                   │   │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────────┐   │   │
│  │ PermissionFilter │  │ ContentSanitizer │  │PromptSanitizer │   │   │
│  │ (后置处理器链)   │  │ (后置处理器链)   │  │(Prompt 构建前) │   │   │
│  │                  │  │                  │  │                │   │   │
│  │ 按 userId+role   │  │ PII/密钥/手机号  │  │ 用户输入清洗   │   │   │
│  │ 过滤检索结果     │  │ 正则脱敏         │  │ 分隔符包裹     │   │   │
│  └─────────────────┘  └──────────────────┘  └────────────────┘   │   │
│                                                                   │   │
│  ┌─────────────────┐  ┌──────────────────┐                       │   │
│  │  AuditLogger    │  │ UserRateLimiter  │                       │   │
│  │  (AOP 切面)     │  │ (per-user 限流)  │                       │   │
│  │                  │  │                  │                       │   │
│  │ 检索/问答全链路 │  │ 防数据提取攻击   │                       │   │
│  │ 审计日志        │  │                  │                       │   │
│  └─────────────────┘  └──────────────────┘                       │   │
└──────────────────────────────────────────────────────────────────┘   │
```

### 4.2 组件详细设计

#### 4.2.1 PermissionFilter — 文档权限过滤器

**位置**：`rag.core.retrieval.postprocessor.PermissionFilter`

**接入点**：`MultiChannelRetrievalEngine.postProcessors` 链中（order=30，所有后置处理器的最后一步）

**设计**：

```
SearchContext 扩展：
  + userId: String        // 当前用户 ID
  + role: String          // 当前用户角色

RetrievedChunk 扩展：
  + securityLevel: Integer    // 安全等级（0=公开, 1=内部, 2=机密, 3=绝密）
  + allowedRoles: List<String> // 允许访问的角色列表
  + allowedUsers: List<String> // 允许访问的用户 ID 列表

PermissionFilter.process(chunks, context):
  for each chunk:
    if chunk.securityLevel == 0 → 保留
    if chunk.allowedUsers.contains(userId) → 保留
    if chunk.allowedRoles.contains(role) → 保留
    else → 过滤掉
```

**关键点**：
- 权限元数据在文档入库时由 `MetadataEnrichment` 从 ES/向量库的 `doc_acl` 字段补齐
- 过滤是静默的（不报错），被过滤的 chunk 对用户透明
- 过滤后如果所有 chunk 都被移除 → 走现有的 `handleEmptyRetrieval` 短路

#### 4.2.2 ContentSanitizer — 文档内容脱敏器

**位置**：`rag.core.retrieval.postprocessor.ContentSanitizer`

**接入点**：`MultiChannelRetrievalEngine.postProcessors` 链中（order=28，PermissionFilter 之前）

**设计**：

```
ContentSanitizer.process(chunks):
  for each chunk:
    chunk.text = applyRules(chunk.text)

脱敏规则（可配置）：
  手机号：  \d{11} → 138****1234
  身份证：  \d{17}[\dXx] → 320***********1234
  邮箱：    保留域名，脱敏用户名
  API Key： 匹配 sk-xxx / Bearer xxx 模式 → [REDACTED]
  内网 IP： 10.x.x.x / 192.168.x.x → [INTERNAL_IP]
```

**关键点**：
- 规则通过配置文件 `rag.security.sanitizer.*` 控制开关
- 脱敏后的 chunk 保留原始长度和结构，不影响 LLM 理解
- 不脱敏文档标题（docName），只脱敏正文

#### 4.2.3 PromptSanitizer — 用户输入清洗器

**位置**：`rag.core.prompt.PromptSanitizer`

**接入点**：`RAGPromptService.buildStructuredMessages()` 调用前

**设计**：

```
PromptSanitizer.sanitize(userInput):
  1. 移除/转义用户输入中的 XML 标签（<question>, <documents>, <rules> 等）
  2. 移除"忽略以上指令"/"新系统指令"等注入模式
  3. 用明确分隔符包裹用户输入，与系统指令隔离

RAGPromptService.buildUserQuestion() 修改：
  当前：
    <question>{userQuestion}</question>
  修改为：
    <question><![CDATA[{sanitizedQuestion}]]></question>
```

**关键点**：
- 清洗在 rewrite 之后、Prompt 组装之前执行
- 不改变 rewrite 后的语义，只做安全过滤
- 基于规则匹配，不做 LLM 调用（避免引入额外延迟）
- 如果检测到注入攻击特征 → 记录告警日志 + 替换为占位符

#### 4.2.4 AuditLogger — 审计日志

**位置**：`rag.core.audit.RagAuditLogger`

**接入点**：AOP 切面，切 `StreamChatPipeline.execute()` 和 `RetrievalEngine.retrieve()`

**设计**：

```
审计记录结构：
  timestamp:      2026-09-02T10:30:00Z
  userId:         "u_12345"
  username:       "张三"
  role:           "employee"
  conversationId: "conv_xxx"
  taskId:         "task_xxx"
  question:       "原始问题"
  rewrittenQuestion: "改写后问题"
  intentHits:     [{nodeId, score}, ...]
  retrieval: {
    totalChunks:  15
    afterPermission: 12
    afterSanitize:  12
    filteredChunks:  [{chunkId, reason: "permission"}, ...]
  }
  llmTokens:      {prompt: 3200, completion: 500}
  latency:        2300ms
  hasSensitiveContent: false
```

**关键点**：
- 异步写入（`@Async`），不阻塞主链路
- 写入 ES（索引 `rag_audit_log`），便于后续检索分析
- 敏感内容标记（`hasSensitiveContent`）用于合规审计
- 保留原始 chunk ID 列表，便于事后追溯

#### 4.2.5 UserRateLimiter — 用户级防滥用限流

**位置**：`rag.service.ratelimit.UserRateLimiter`

**接入点**：在 `ChatQueueLimiter.enqueue()` 之前，作为前置检查

**设计**：

```
UserRateLimiter:
  - 每分钟每用户最多 N 次请求（可配置，默认 20）
  - 每小时每用户最多 M 次请求（可配置，默认 200）
  - 使用 Redis 滑动窗口实现
  - 超限后返回 HTTP 429 + 提示信息

配置：
  rag.security.rate-limit.per-minute: 20
  rag.security.rate-limit.per-hour: 200
  rag.security.rate-limit.admin-bypass: true
```

---

## 5. 数据模型变更

### 5.1 RetrievedChunk 扩展

```java
// 安全相关
private Integer securityLevel;        // 0=公开, 1=内部, 2=机密, 3=绝密
private List<String> allowedRoles;    // 允许访问的角色
private List<String> allowedUsers;    // 允许访问的用户 ID

// 功能相关
private String citationId;            // 引用编号（如 "[1]"），由 CitationMarker 注入
```

### 5.2 SearchContext 扩展

```java
// 安全相关
private String userId;    // 当前用户 ID
private String role;      // 当前用户角色

// 功能相关
private Integer maxContextTokens;  // 上下文 token 预算上限
```

### 5.3 RetrievalContext 扩展

```java
// 功能相关
private Float avgScore;           // 检索结果平均相关性分
private QualityLevel qualityLevel; // HIGH / MEDIUM / LOW / EMPTY
private List<CitationRef> citations; // 引用映射列表
```

### 5.4 新增配置项

```yaml
rag:
  # === 功能配置 ===
  context:
    max-tokens: 90000              # 上下文 token 预算上限
    compression:
      enabled: true                # 长 chunk 压缩开关
      max-chunk-tokens: 500        # 单 chunk 超过此值触发压缩
  retrieval:
    relevance-threshold: 0.3       # 相关性最低阈值
    diversity:
      enabled: true                # MMR 多样性开关
      lambda: 0.7                  # 相关性 vs 多样性权重
    semantic-dedup:
      enabled: true                # 语义去重开关
      threshold: 0.85              # Jaccard 相似度阈值
  memory:
    max-history-tokens: 8000       # 历史消息最大 token 数
  citation:
    enabled: true                  # 引用标注开关

  # === 安全配置 ===
  security:
    permission:
      enabled: true
    sanitizer:
      enabled: true
      pii-patterns:
        phone: true
        id-card: true
        email: true
        api-key: true
    prompt:
      sanitize-enabled: true
      detect-injection: true
    rate-limit:
      per-minute: 20
      per-hour: 200
      admin-bypass: true
    audit:
      enabled: true
      async: true
```

---

## 6. 实施步骤

| 阶段 | 步骤 | 内容 | 编号 | 优先级 |
|------|------|------|------|--------|
| 一 | 1 | `SearchContext`/`RetrievedChunk`/`RetrievalContext` 扩展 | S1/F1/F4 | P0 |
| 一 | 2 | `PermissionFilter` 实现 + 注册后置处理器链 | S1 | P0 |
| 一 | 3 | `PromptSanitizer` 实现 + 接入 `RAGPromptService` | S2/S3 | P0 |
| 一 | 4 | `TokenBudgetManager` 实现 + 接入 `streamRagResponse` | F1 | P0 |
| 一 | 5 | `RelevanceFilter` 实现 + 注册后置处理器链 | F2 | P0 |
| 二 | 6 | `ContentSanitizer` 实现 + 注册后置处理器链 | S4 | P1 |
| 二 | 7 | `UserRateLimiter` 实现 + 接入 `RAGChatServiceImpl` | S6 | P1 |
| 二 | 8 | `RagAuditLogger` AOP 切面 + ES 索引 | S5 | P1 |
| 二 | 9 | `SemanticDedupPostProcessor` 实现 | F5 | P1 |
| 二 | 10 | `DiversityRanker` (MMR) 实现 | F7 | P1 |
| 二 | 11 | 检索质量分级兜底（`handleEmptyRetrieval` 升级） | F3 | P1 |
| 三 | 12 | `CitationMarker` + `CitationResolver` 实现 | F4 | P2 |
| 三 | 13 | `ContextCompressor` 实现 | F6 | P2 |
| 三 | 14 | 历史消息 token 裁剪 | F8 | P2 |
| 三 | 15 | `RetrievalQualityEvaluator` 实现 | F9 | P2 |
| 远期 | 16 | 查询类型差异化策略 | F10 | P3 |

---

## 7. 不变更的部分

- `StreamChatPipeline` 7 步流程不变，功能组件作为后置处理器或横切关注点接入
- `RetrievalEngine` 核心检索逻辑不变，过滤/压缩/多样化在外部后置处理器完成
- `RAGPromptService` Prompt 模板保留，CitationMarker 在模板中增加引用指令
- `MultiChannelRetrievalEngine` 后置处理器链模式不变，新处理器按 order 插入
- `FairDistributedRateLimiter` 保留，`UserRateLimiter` 作为补充层
- 现有的 SaToken 认证 + `UserContextInterceptor` 不变

## 8. 后置处理器链最终顺序

```
Order  1: DeduplicationPostProcessor      (ID 去重)
Order  3: SemanticDedupPostProcessor      (语义去重, 新增 F5)
Order  5: FusionPostProcessor             (RRF 融合)
Order 10: RerankPostProcessor             (Cross-encoder 精排)
Order 15: RelevanceFilter                 (相关性阈值, 新增 F2)
Order 17: DiversityRanker                 (MMR 多样性, 新增 F7)
Order 20: MetadataEnrichmentPostProcessor (元数据富化)
Order 25: ContextCompressor               (长文本压缩, 新增 F6)
Order 28: ContentSanitizer                (PII 脱敏, 新增 S4)
Order 30: PermissionFilter                (权限过滤, 新增 S1)
```

## 9. 风险与边界

| 风险 | 缓解措施 |
|------|---------|
| 权限过滤导致检索结果为空 | 非阻塞报错，走现有 `handleEmptyRetrieval` 兜底 |
| 脱敏导致 LLM 理解偏差 | 脱敏保留语义结构，不做完全替换 |
| 注入检测误杀正常问题 | 仅告警日志 + 清洗，不阻断正常请求 |
| 审计日志 ES 写入失败 | 异步入库，失败丢弃不阻塞主链路 |
| 权限元数据在存量文档中缺失 | 默认 `securityLevel=0`（公开），保证兼容性 |
| Token 估算偏差导致实际超窗口 | 预留 10% 安全边际，`generationReserve` 不低于 4096 |
| MMR 多样性降低相关性 | λ=0.7 偏相关，可配置降为 0.5 增加多样性 |
| 语义去重 Jaccard 误判 | 阈值 0.85 保守，仅去重高度相似内容 |
| 上下文压缩丢失关键信息 | 仅压缩超长 chunk，短 chunk 保持原样；extractive 优先 |