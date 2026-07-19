# Ragent AI — 面试学习笔记

> 基于源码级分析的企业级 Agentic RAG 平台。
> 以下每个结论都有对应代码文件与行号支撑。

---

## 一、项目概览

**一句话定位**：后端程序员转型 AI 工程师的实战项目 — 不是 Demo，是能在面试中聊出深度的企业级 RAG 系统。

| 维度 | 数据 |
|:---|:---|
| 后端 | Java (Spring Boot 3)，约 40000 行 / 400+ 源文件 |
| 前端 | React 18 + TypeScript，约 18000 行 |
| 数据库 | PostgreSQL 20 张业务表 + Redis + Milvus/PGVector |
| 部署 | Docker Compose，非 K8s 原生（无 HPA 代码） |
| 定位 | 企业内部部署型 RAG，非 SaaS 多租户 |

### 模块分层 — 为什么分四层？

```
bootstrap/     ← 业务层：RAG 全链路（会话/检索/意图/知识库/入库流水线）
infra-ai/      ← AI 基础设施层：屏蔽供应商差异，模型路由/熔断/降级
framework/     ← 通用基础设施：异常体系、幂等、雪花 ID、上下文透传、SSE 封装
mcp-server/    ← MCP 协议服务端：独立部署，零内部依赖
```

**面试话术**：分层不是炫技。换模型供应商不改业务代码（改 infra-ai），换业务逻辑不动基础设施（framework 不变）。framework 层的 23 个类覆盖 10 个横切关注点，业务模块只需引入依赖 + 加注解，零样板代码。

### 面试必问：你跟 Spring AI / LangChain4j 比有什么不同？

项目 README 专门讲了技术选型思考。核心原因：LangChain 抽象层版本迭代极快，低版本功能缺、高版本升级约等于重写；Spring AI 功能覆盖不够。自研的 infra-ai 层做了多供应商路由 + 熔断 + 首包探测，这些 Spring AI 没有开箱即用的方案。

---

## 二、一次用户问答的完整管线（7 步详解）

```
用户提问："我在北京，年假怎么算？报销流程是什么？"
  │
  ▼
① loadMemory() — 并行加载记忆
  │
  ▼
② rewriteQuery() — 术语归一化 + LLM 改写拆分
  │
  ▼
③ resolveIntents() — 并行意图分类
  │
  ▼
④ handleGuidance() — 歧义引导（短路点1）
  │
  ▼
⑤ handleSystemOnly() — 闲聊短路（短路点2）
  │
  ▼
⑥ retrieve() — 多通道检索 + 后处理器链
  │
  ▼
⑦ streamRagResponse() — Prompt 组装 + SSE 流式输出
```

### 第①步：loadMemory() — 加载记忆

**为什么要并行？** 摘要查 `t_conversation_summary`，历史查 `t_message`，两张表互不依赖。用 `CompletableFuture.allOf()` 并行加载，加载时间 = max(摘要查询, 历史查询)，不是两者的和。

```java
// DefaultConversationMemoryService.load() → 并行加载
CompletableFuture<ChatMessage> summaryFuture = CompletableFuture.supplyAsync(
    () -> loadSummaryWithFallback(conversationId, userId), memoryLoadExecutor);
CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(
    () -> loadHistoryWithFallback(conversationId, userId), memoryLoadExecutor);
// allOf 等待两者，然后合并：摘要在前，历史在后
```

**合并策略**：摘要包装成 `<conversation-summary>` 标签，放在历史消息前面。LLM 先读摘要建立上下文，再读最近几轮原文做精确理解。

### 第②步：rewriteQuery() — 改写 + 拆分

**为什么不用规则而要调 LLM？** 规则搞不定"报销咋整"→"报销流程"这种口语化改写，更搞不定"它的数据库用什么"这种指代消解。

**两级处理**：
1. **术语归一化**（规则级，不调 LLM）：`QueryTermMappingService` 从 DB 加载映射表（Redis 缓存），把"平安保司"→"平安保险公司"
2. **LLM 改写拆分**（调 LLM）：`MultiQuestionRewriteService` 用小模型做改写

**提示词设计精要** — `user-question-rewrite.st`：

```
## 保留内容
- 专有名词（系统名、产品名）：原样保留，不得修改
- 关键限制：时间范围、环境、终端类型、角色身份

## 删除内容
- 礼貌用语："请帮我"、"麻烦"、"谢谢"
- 回答指令："详细说明"、"分点回答"

## 拆分规则
- 多个问号 → 拆："系统A怎么用？系统B呢？"
- 抽象对比 → 不拆："X和Y有什么区别？"（拆了丢对比信息）

## 禁止行为
- 不得添加原文没有的条件 → 防止 LLM "脑补"
```

**为什么抽象对比不拆？** 案例：用户问"微服务和单体架构的区别"，如果拆成"微服务架构""单体架构"两个独立查询，各自检索出两套文档，LLM 无法做对比——因为两份文档没有交叉信息。不拆分时，检索出的 chunk 自然覆盖两边，LLM 能看到关联。

**容错设计**：LLM 调用失败 → 回退到规则拆分（按 `[？?。；;\n]` 分隔符切 + 术语归一化）

**配置开关**：`rag.query-rewrite.enabled: true`，关闭后只做规则归一化不调 LLM

### 第③步：resolveIntents() — 意图分类

**每个子问题独立并行分类**，使用 `intentClassifyExecutor` 线程池：

```java
// IntentResolver.resolve() — 每个子问题并行调 LLM
List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream()
    .map(q -> CompletableFuture.supplyAsync(
        () -> new SubQuestionIntent(q, classifyIntents(q)), intentClassifyExecutor))
    .toList();
```

**意图树的三种节点**：
- `KB`（知识库）：走检索，配置了 collection_name 路由到特定知识库
- `MCP`（工具调用）：携带 mcpToolId，走工具执行
- `SYSTEM`（闲聊）："你好""你是谁" → 直接 LLM 回复，不走检索

**过滤与限制**：score < 0.35 的意图丢弃，每个子问题最多保留 3 个意图，总意图数也有上限。

### 第④⑤步：短路机制

**歧义引导**（④）：当 LLM 判断置信度不足时（如只说了"数据安全"但 OA 系统和保险系统都有这个分类），推选项让用户选：

```json
[{"id": "biz-oa-security", "score": 0.62}, {"id": "biz-ins-security", "score": 0.60}]
```

**系统短路**（⑤）：全是 SYSTEM 类型 → 不走检索，直接调 LLM 回复（temperature=0.7，闲聊场景需要一定创造性）

### 第⑥步：retrieve() — 检索引擎

详见第三章。

### 第⑦步：streamRagResponse() — 流式输出

**分场景选用不同 Prompt 模板**：
- 纯 KB 场景 → `answer-chat-kb.st`（严格事实性约束）
- 纯 MCP 场景 → `answer-chat-mcp.st`（动态数据片段）
- KB + MCP 混合 → `answer-chat-mcp-kb-mixed.st`（兼顾知识库与动态数据）

**多子问题时**，上下文按子问题分组带标签：
```xml
<document index="1"><question>年假怎么算？</question>
<content source="员工手册">...</content></document>
<document index="2"><question>报销流程是什么？</question>
<content source="财务制度">...</content></document>
```

LLM 能区分哪个 chunk 对应哪个问题。

---

## 三、多路检索 + RRF 融合 + Rerank — 深度解析

### 3.1 为什么需要多路？

**单路向量的致命缺陷**：向量检索（余弦相似度）对精确匹配极弱。用户问"订单号 ORD-2024-001"，向量检索可能返回语义相关但 ID 不匹配的结果。关键词检索（BM25）能精确匹配订单号，但理解不了"打印机墨盒怎么换"和"墨盒更换步骤"是同一回事。

**多路互补**：
- 意图定向：精确路由到目标知识库，召回精准
- 关键词 BM25：精确匹配（订单号、错误码、人名）
- 向量全局：语义理解（同义词、改写表述）

### 3.2 并行执行机制

```java
// MultiChannelRetrievalEngine.executeSearchChannels()
// 过滤启用的通道 → 按 priority 排序 → 并行执行
List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
    .map(channel -> CompletableFuture.supplyAsync(
        () -> {
            try { return channel.search(context); }
            catch (Exception e) { return emptyResult(channel); }  // 异常不传染
        }, ragRetrievalExecutor))
    .toList();
```

每个通道在自己的线程上执行，**某个通道挂了不影响其他通道**——返回空结果而不是抛异常。

### 3.3 全局检索安全网（安全网机制）

```java
// VectorGlobal 配置
confidenceThreshold: 0.6              // 最高意图分 < 0.6 → 触发全局检索
singleIntentSupplementThreshold: 0.8  // 只有一个意图且分 < 0.8 → 补充全局检索
```

**案例**：用户问"最近有什么新政策？"意图分类可能没有明确匹配（分数全部 < 0.6），此时全局检索作为安全网兜底，全库检索，保证不返回"未找到"。

### 3.4 四个后置处理器的完整案例

假设用户问"OA 系统的审批流程"，3 个通道返回以下结果：

```
意图定向通道: chunk_A(score=0.82, rank=0), chunk_B(score=0.75, rank=1), chunk_C(score=0.68, rank=2)
关键词通道:   chunk_D(BM25=15.3, rank=0), chunk_A(BM25=12.1, rank=1), chunk_E(BM25=9.8, rank=2)
全局向量通道: chunk_A(COSINE=0.79, rank=0), chunk_F(COSINE=0.72, rank=1)
```

**① Dedup（去重合并）**：

chunk_A 在三个通道都出现了。去重逻辑：按通道优先级排序（意图定向 > 关键词 > 全局向量），保留意图定向通道的 chunk_A（score=0.82）。如果意图定向没命中但关键词和向量都命中了 chunk_A，保留分数高的那个。

结果：chunk_A(0.82), chunk_B(0.75), chunk_C(0.68), chunk_D(15.3 → 取 BM25), chunk_E(9.8), chunk_F(0.72)

**② RRF 融合**：

```
chunk_A: 1/(60+0+1) + 1/(60+1+1) + 1/(60+0+1) = 1/61 + 1/62 + 1/61 = 0.0164 + 0.0161 + 0.0164 = 0.0489
chunk_D: 1/(60+0+1) = 1/61 = 0.0164  （只在关键词通道出现，且 rank=0）
chunk_B: 1/(60+1+1) = 1/62 = 0.0161  （只在意图通道出现，rank=1）
```

**chunk_A 的 RRF 分几乎是 chunk_D 的三倍**，因为它在三个通道都排在前列。这就是 RRF 的核心价值：多路命中天然提权。

**③ Rerank 精排 + 截断**：

RRF 排序后，`rerankCandidateLimit=50` 截断候选池。然后调百炼 qwen3-rerank cross-encoder，把每个候选的文本和 query 一起喂给模型：

```json
POST /api/v1/services/rerank/text-rerank/text-rerank
{
  "model": "qwen3-rerank",
  "input": {
    "query": "OA系统的审批流程",
    "documents": [
      "OA审批流程包括提交申请、部门审批、人事审批三个步骤...",  // chunk_A
      "员工入职流程包括...",                                    // chunk_B
      ...
    ]
  },
  "parameters": { "top_n": 10, "return_documents": true }
}
```

返回 `relevance_score`：chunk_A 0.91, chunk_D 0.88, chunk_C 0.85 ...

**为什么 Rerank 放 RRF 之后？** RRF 把 100+ 候选粗筛到 50 个，Rerank 只对 50 个做深度语义匹配。如果反过来，100+ 个候选的交叉编码成本和延迟都不可接受（cross-encoder 比 bi-encoder 慢 10-50 倍）。

**④ MetadataEnrichment 富化**：

精排后的 chunk 只有 id 和 text，没有文档归属。富化处理器回表补齐：
- docId → 文档归属
- chunkIndex → 组内排序
- docName → 来源标注

这些信息在第⑥步上下文组装时使用。

### 3.5 RRF 参数调优指南

```yaml
rag.search.fusion:
  rrf-k: 60              # 默认 60
  rerank-candidate-limit: 50
```

**k 参数含义**：RRF 公式 `1/(k + rank)` 中，k 越大，rank 差异越不敏感。

- **k=60（默认）**：rank 0 (1/61=0.0164) vs rank 9 (1/70=0.0143)，差异 13%
- **k=20**：rank 0 (1/21=0.0476) vs rank 9 (1/30=0.0333)，差异 30%。名次优势更突出
- **k=100**：rank 0 (1/101=0.0099) vs rank 9 (1/110=0.0091)，差异 8%。几乎平等看待

**什么时候调什么方向？**
- 某通道（如向量）一直压过其他通道 → 增大 k，让其他通道也有机会
- 结果太分散、噪音多 → 减小 k，更相信高名次

**rerankCandidateLimit**：
- 太小（20）：多路命中但 RRF 排名靠后的好 chunk 被截掉
- 太大（200）：Rerank 延迟飙升
- 经验值 40-100，默认 50

---

## 四、文档切分策略 — 深度案例

### 4.1 两种文本策略的对比案例

**输入文档片段**（纯文本，无解析器产出 Block）：

```
# 第三章 接口规范

## 3.1 认证接口

POST /api/auth/login
Header: Content-Type: application/json
Body: {"username": "admin", "password": "***"}
返回: {"token": "eyJ...", "expires": 3600}

调用示例：
curl -X POST https://api.example.com/auth/login -d '{"username":"admin","password":"***"}'

## 3.2 查询接口

查询接口支持分页和条件筛选。使用GET方法，参数通过URL传递。
```

**FIXED_SIZE 切分（chunkSize=512, overlap=128）**：

```
chunk_0: "# 第三章 接口规范\n\n## 3.1 认证接口\n\nPOST /api/auth/login\n..."  
         （512 字符，在换行符后结束）
chunk_1: "...调用示例：\ncurl -X POST https://api.exa"  
         （512 字符，但 URL 被切断了——注意 FixedSizeTextChunker 的 normalize 会修复 URL 换行）
         （128 字符 overlap = chunk_0 的尾部）
```

FixedSizeTextChunker 的边界对齐优先找换行符，但如果 URL 在 512 字符边界处，它的 `normalizeText()` 会修复 `https://api.\nexample.com` → `https://api.example.com`。

**STRUCTURE_AWARE 切分（target=1400, max=1800, min=600）**：

```
扫描成块:
  Block(kind=HEADING, text="# 第三章 接口规范")
  Block(kind=HEADING, text="## 3.1 认证接口")
  Block(kind=ATOMIC, text="POST /api/auth/login\nHeader: ...")  ← ![]() 或 ``` 等
  Block(kind=PARA, text="调用示例：\ncurl -X POST...")
  Block(kind=HEADING, text="## 3.2 查询接口")
  Block(kind=PARA, text="查询接口支持分页...")

打包:
  chunk_0: HEADING3 + HEADING3.1 + ATOMIC + PARA + HEADING3.2
           = 从"第三章"到"3.2"，在块边界处累积到 target=1400 左右
  chunk_1: PARA + ...
```

**对比结论**：
- FIXED_SIZE：简单、快速，适合纯文本。但可能切在尴尬位置（段落中间、代码块内部）
- STRUCTURE_AWARE：保持 Markdown 结构完整性，标题/代码/图片不会从中间切开。但计算成本高

### 4.2 block-aware 切分 — 以表格为例

**输入**：一个 200 行的 Excel 表格，columns: [部门, 预算金额, 年度, 负责人, 审批状态]

解析器产出 `TableBlock(headers=["部门","预算金额","年度","负责人","审批状态"], rows=200行)`

**TableChunker 的处理流程**：

```java
// 每行渲染成 key-value 做体量度量: "部门: 研发部; 预算金额: 500万; 年度: 2024; 负责人: 张三; 审批状态: 已通过"
int rowCost = renderKeyValueRow(headers, row).length();

// 贪心累加: 50 行 * ~80 字符 ≈ 4000 字符，超过 maxChars=1800
// → 在第 ~22 行时触发切块
```

**切块结果**：

```
chunk_0 (行 1-22):
  content (markdown 表格):
    | 部门 | 预算金额 | 年度 | 负责人 | 审批状态 |
    |---|---|---|---|---|
    | 研发部 | 500万 | 2024 | 张三 | 已通过 |
    ...(21 行数据)...
  
  embeddingText (key-value):
    sheet=2024预算表; headers=部门, 预算金额, 年度, 负责人, 审批状态
    部门: 研发部; 预算金额: 500万; 年度: 2024; 负责人: 张三; 审批状态: 已通过
    部门: 市场部; 预算金额: 300万; 年度: 2024; 负责人: 李四; 审批状态: 待审批
    ...

  sectionContext: "sheet=2024预算表; headers=部门, 预算金额, 年度, 负责人, 审批状态"
```

**为什么 embeddingText 要用 key-value 而非 markdown？**

这是个好问题。Embedding 模型（如 text-embedding-3-large）按字面语义做向量化。它看到一个 markdown 表格：

```
| 研发部 | 500万 | 2024 | 张三 | 已通过 |
```

它的理解是："这是一行文本，有些词用竖线分隔"。列名（部门、预算金额）和值（研发部、500万）的对应关系靠**位置**推断，但 embedding 模型的 attention 机制对整个序列做加权平均，位置信号在 1536 维向量中被稀释了。

改成 key-value 格式：

```
部门: 研发部; 预算金额: 500万; 年度: 2024; 负责人: 张三; 审批状态: 已通过
```

"部门" 和 "研发部" 之间是 `:` 连接，"预算金额" 和 "500万" 之间也是 `:` 连接。Embedding 模型能把 `部门: 研发部` 作为一个语义单元编码，"谁是什么部门" 的语义关系写进了字面文本中。

**效果对比**（参考 RAGFlow、STC 的做法）：

| 查询 | markdown 表格检索 | key-value 检索 |
|:---|:---|:---|
| "研发部的预算" | 中等，需依赖上下文 | 高，`部门: 研发部; 预算金额:` 直接命中 |
| "谁负责审批" | 低，需要理解列顺序 | 中，`审批状态:` 关键词可匹配 |
| "2024 年度预算超过 500 万" | 低 | 中，`年度: 2024; 预算金额:` 两字段命中 |

### 4.3 ChunkPacker — 为什么需要打包？

各 chunker 只负责"单 Block 内切分"，天然只拆不并。后果示例：

```
文档内容:
  "Ragent 是一个企业级 RAG 平台。" (ParagraphBlock, 20 字符)
  "- 多路检索" (ListBlock, 5 字符)
  "- 意图识别" (ListBlock, 5 字符)
  "- MCP 集成" (ListBlock, 5 字符)

如果不打包 → 4 个独立 chunk，每个 5-20 字符，太碎了
ChunkPacker 贪心合并:
  chunk_0: "Ragent 是一个企业级 RAG 平台。\n\n- 多路检索\n- 意图识别\n- MCP 集成"  (~60 字符)
  → 正好接近 maxChars 预算
```

**表格/代码不合并**：ChunkPacker 的 `isMergeable()` 判断——TABLE 和 CODE 类型的 chunk 不可合并。保证了"表格完整结构不被 ChunkPacker 打散到不同 chunk 里"。

**块级重叠**：`overlapTail()` 从缓冲区尾部取完整可合并块作为下一块的起点——既复现跨块上下文、又不切碎段落/列表项（不像 FIXED_SIZE 那样在字符边界硬切）。

---

## 五、大表切分 — 完整案例

**场景**：财务预算表，500 行 × 8 列，每行 key-value 渲染后的长度约 120 字符。

```
配置: maxChars=1800(体量预算), rowsPerChunk=50(行数硬上限)

执行过程:
  第1行 cost=120, groupCost=120, group=[1]
  第2行 cost=118, groupCost=238, group=[1,2]
  ...
  第15行 cost=125, groupCost=1805, 1805+125=1930 > 1800
    → 触发切块! chunk_0 包含行 1-14，重新起组
  ...
  第50行: group.size()=35, 体量未超但行数==50（rowsPerChunk 硬上限）
    → 触发切块! chunk_1 包含行 15-49
```

**边缘情况：单行超预算**。假设某行的 key-value 渲染长度是 2500 字符（某个单元格有大段描述）：

```java
if (!group.isEmpty() && groupCost + rowCost > budget) {
    // 切块... group 非空说明前面已有累积行
}
if (group.isEmpty() && rowCost > budget) {
    // 单行自成一块，不拆散
    result.add(buildChunk(headers, List.of(row), ...));
}
```

**面试追问：你是按 token 数切还是按字符数切？**

按字符数（`renderKeyValueRow().length()`），不是按 token 数。原因：
1. token 数是模型相关的，没法在入库阶段确定（入库时不知道问答用哪个 LLM）
2. 字符数在 Java 中计算成本为零（String.length()），不用调 tokenizer
3. 字符数 ≈ α × token 数（中文 α≈1.5，英文 α≈4），做近似够用了

---

## 六、父子文档的等价实现 — 场景拆解

**先讲传统父文档模式**：

```
入库时: 原始文档 → 切大块(父, 例如 2000 token) + 切小块(子, 例如 500 token)
        父子都嵌入, 都存在向量库
检索时: query → 检索子 chunk(精细) → 找到父 chunk id → 取父 chunk 全文 → 喂 LLM
```

问题：两套 chunk 存储，双倍嵌入成本，父子关系的维护复杂度。

**Ragent 的做法：一套 chunk + 三套元数据**

### 案例：员工手册文档

假设「员工手册.pdf」被 MinerU 解析为 Block 体系后切分成 15 个 chunk：

```
chunk_0: "第三章 考勤制度" (outlinePath=["第三章", "3.1 考勤制度"])
chunk_1: "年假计算规则：入职满1年5天，满3年10天。北京地区额外增加2天。"
chunk_2: "病假需提供医院证明，3天以内不扣工资。"
chunk_3: "第四章 报销制度" (outlinePath=["第四章", "4.1 报销制度"])
...
```

用户问"北京地区的年假政策"：

1. 检索命中 chunk_1（按"年假 北京 政策" 语义匹配）
2. MetadataEnrichment 回表补齐：docId=员工手册, chunkIndex=1, docName=员工手册
3. 上下文组装 — `DefaultContextFormatter.renderChunksGroupedByDoc()`：
   - 按 docId 分组 → chunk_0, chunk_1, chunk_2 都属于"员工手册"
   - 组内按 chunkIndex 排序 → chunk_0(0) → chunk_1(1) → chunk_2(2)，还原原文顺序
   - 渲染：

```xml
<content source="员工手册">
第三章 考勤制度

年假计算规则：入职满1年5天，满3年10天。北京地区额外增加2天。

病假需提供医院证明，3天以内不扣工资。
</content>
```

LLM 不仅能回答"5天+2天北京"，还能引用上下文"病假规则"做补充。**效果上完全等于取了父 chunk 全文**，但不需要维护两套 chunk。

### sectionContext 的表格案例

财务预算表被切成 10 块，每块的 `sectionContext = "sheet=2024预算表; headers=部门, 预算金额, 年度"`。

当 LLM 的上下文中出现：

```xml
<content source="2024预算表.xlsx">
部门: 研发部; 预算金额: 500万; ...
context: sheet=2024预算表; headers=部门, 预算金额, 年度, 负责人, 审批状态
</content>
```

即使只有 3 行数据，LLM 也知道「这是一个 2024 预算表的片段，其他列还包括负责人和审批状态」。不会出现"列1=500"这种无头数据的尴尬。

---

## 七、长短期记忆 — 完整存储与使用链路

### 7.1 存储：为什么不用 JSON 而用关系表？

| 考量 | 关系表 | JSON 文档 |
|:---|:---|:---|
| 按会话+用户查询 | 索引 `idx_conversation_user_time`，O(log n) | 需要解析整个 JSON |
| 消息排序 | `ORDER BY create_time` 天然支持 | 需要应用层排序 |
| 部分更新 | UPDATE 单行 | 需要重写整个 JSON |
| 数据一致性 | ACID 事务 | 应用层做 |

结论：消息数据是典型的 OLTP 场景，关系表是正确选择。JSON 更适合配置类数据（如意图树节点配置）。

### 7.2 短期记忆：滑动窗口详解

```
historyKeepTurns = 8 → 每次加载最近 16 条消息 (8 轮 user+assistant)

查询: SELECT * FROM t_message WHERE conversation_id=? AND user_id=? AND deleted=0
       ORDER BY create_time DESC LIMIT 16

结果(倒序取回, 代码中 reverse 成正序):
  msg_50(assistant) msg_49(user) msg_48(assistant) msg_47(user) ... msg_35(user)

normalizeHistory() 处理: 如果开头是 assistant 消息, 去掉 (不能从半轮开始)
  → 最终: [msg_35(user), msg_36(assistant), ..., msg_50(assistant)]
```

**为什么不按 token 数做窗口？** 常见的做法是限制总 token 数（如 4000 tokens）。这里的实现按消息条数，更简单直接。如果 assistant 的长回复超过 LLM 上下文窗口，会在组装 Prompt 时自然截断。

### 7.3 长期记忆：渐进式摘要详解

**触发链路**：

```java
// 每次 assistant 回复后
DefaultConversationMemoryService.append() 
  → summaryService.compressIfNeeded(conversationId, userId, message)
    // 条件检查: summaryEnabled=true + 角色=ASSISTANT + 用户消息数>=summaryStartTurns
    → 异步执行 doCompressIfNeeded()
```

**窗口计算**（`doCompressIfNeeded` 的核心逻辑）：

```
假设 keepTurns=8, summaryStartTurns=9

消息时间线:
  msg_1  msg_2  ...  msg_20  msg_21  ...  msg_30  msg_31  msg_32
  |<-------- 保留原文(8轮=16条) ------>|<-- 可摘要的旧消息 -->|

摘要窗口:
  取出最近 keepTurns=8 条 user 消息 (latestUserOnlyMessages)
    → historyStartId = 最旧那条 (msg_21 的 id)
  
  取上次摘要的 last_message_id (afterId)
    → 如果 afterId >= historyStartId → 摘要足够新，跳过
    → 如果 afterId < historyStartId → 需要生成新摘要

  摘要覆盖: afterId 到 summaryCutoffId (historyStartId 和末尾的中间位置)
    → 每次摘要覆盖窗口的一半，渐进式推进
```

**摘要提示词设计精要**：

```
# 核心约束
1. 不超过 {summary_max_chars} 字符，单行输出
2. 绝对禁止记录具体答案——只记话题+状态+约束
   - 原因: RAG 实时检索最新文档，摘要里的旧答案会和最新文档冲突
3. 话题颗粒度要具体: ❌ 咨询了人事制度 ✅ 咨询了年假计算规则

格式: 用户咨询了【话题1】（状态）、【话题2】（状态）。约束：约束1。关键词：词1
```

**案例**：一段 20 轮的对话，话题涉及年假、病假、报销：

```
摘要1 (第9轮触发，覆盖 msg_1 到 msg_10 的中间部分):
  用户咨询了年假计算规则（已解答）、病假政策（当时无记录）。关键词：人事政策

摘要2 (第15轮触发，合并旧摘要+新增消息 msg_11 到 msg_18):
  用户咨询了年假计算规则（已解答）、病假政策（当时无记录）、报销单据规范（已解答）。
  约束：北京地区。关键词：人事政策, 财务制度
```

### 7.4 怎么确认本轮用了长记忆？

加载代码无条件执行：`loadLatestSummary()` → 有摘要就拼进去，没有就跳过。判断标准不在代码逻辑里，而在数据是否有：
- `t_conversation_summary` 表有该会话的记录 = 用了
- 发给 LLM 的消息列表第一条是 `<conversation-summary>` 标签 = 用了
- 表里没有 = 还没触发摘要（轮数不够或未开启）

### 7.5 记忆检索的优化空间

当前是 JDBC 直读，每次查询 2-3 条 SQL。优化方向：

1. **Redis 缓存短记忆**：写入时 LPUSH + LTRIM 保持 N 条，读取时 LRANGE，miss 回 DB
2. **Redis 缓存长记忆**：摘要更新频率很低，缓存命中率极高
3. **去掉冗余的 conversation 存在性检查**：`listMessages()` 里先查 `t_conversation` 再查 `t_message`，可合并
4. **长消息截断**：历史窗口里的 assistant 超长回复截断到 500 字符，减少网络传输

---

## 八、意图识别的多重价值 — 不只是路由

### 8.1 意图树的管理界面

意图树在管理后台可视化编辑。节点结构（`t_intent_node` 表）：

```sql
intent_code     parent_code     name          kind    prompt_snippet       top_k  mcp_tool_id
biz-oa          NULL            业务系统>OA    KB      NULL                 NULL   NULL
biz-oa-intro    biz-oa          OA系统介绍     KB      "请使用OA术语作答"    5      NULL
biz-oa-flow     biz-oa          OA审批流程     KB      NULL                 10     NULL
biz-ins         NULL            保险系统       KB      "使用保险行业术语"    NULL   NULL
mcp-weather     NULL            天气查询       MCP     NULL                 NULL   weather_query
sys-chat        NULL            日常闲聊       SYSTEM  NULL                 NULL   NULL
```

### 8.2 每个价值的落地案例

**① 知识库路由**：意图 `biz-oa-intro` 配置了 `collection_name = "oa_knowledge_base"`。检索时只在这个 collection 内搜，不会查到保险系统的内容。**如果没有意图路由**，用户问"审批流程"时全局检索可能返回保险理赔审批+OA 审批混在一起，噪音极大。

**② KB/MCP/SYSTEM 三路分发**：

```java
// NodeScoreFilters.kb() — 过滤 kind=KB 的意图
// NodeScoreFilters.mcp() — 过滤 kind=MCP 的意图
// 如果所有意图都是 SYSTEM → handleSystemOnly() 短路，不走检索
```

案例：用户问"你好，今天心情不错" → 意图分类 score 最高的可能是 SYSTEM 节点 → 直接 LLM 闲聊回复，不走检索，省一次向量检索+Milvus 查询。

**③ Prompt 定制注入**：

意图节点 `biz-ins` 的 `promptSnippet = "涉及金额必须注明币种（人民币），涉及时间必须注明日期格式（yyyy年MM月dd日）"`。

在 `DefaultContextFormatter.formatKbContext()` 中被渲染进最终 Prompt：

```xml
<rules>
1. 涉及金额必须注明币种（人民币），涉及时间必须注明日期格式（yyyy年MM月dd日）
</rules>
<content source="保险条款">...</content>
```

同一个 LLM 回答保险相关问题时自动切换回答规则。**面试话术**：这是"软路由"——不是切换模型，而是切换 Prompt 约束，零延迟零成本。

**④ TopK 精确控制**：

FAQ 类意图（标准问答）TopK=3（精准就够了）。政策文档类意图（长文档）TopK=10（需要更多上下文）。代码实现：

```java
// RetrievalEngine.resolveSubQuestionTopK()
intent.nodeScores().stream()
    .map(NodeScore::getNode).map(IntentNode::getTopK).filter(topK -> topK > 0)
    .max(Integer::compareTo).orElse(fallbackTopK);
```

取所有命中意图节点的 TopK 最大值。如果有三个意图节点分别配置 TopK=3/5/10，取 10。

**⑤ 歧义引导 — 完整交互案例**：

```
用户: 数据安全有什么规定？

意图分类结果:
  biz-oa-security: 0.62 (OA系统的数据安全)
  biz-ins-security: 0.60 (保险系统的数据安全)
  两个分差 < 0.05，LLM 判定为歧义 → handleGuidance() 触发

返回给用户:
  "您想了解的是哪方面的数据安全规定？
   1. OA系统的数据安全
   2. 保险系统的数据安全
   请选择或输入更具体的问题。"

用户点击选项1 → 下一轮意图直接命中 biz-oa-security
```

**⑥ 全局检索安全网**：意图置信度 < 0.6 时触发。防止"问得很模糊但觉得肯定有相关内容"的场景。

**⑦ MCP 参数提取模板**：每个 MCP 节点可以自定义参数提取提示词。如天气查询的提示词把"今天热不热"→`{"city":"北京","queryType":"current"}`：

```java
// McpParameterExtractor 使用 intentNode.getParamPromptTemplate() 做定制化提取
```

### 8.3 意图识别与 TinyBERT 的讨论

**面试中可能的追问**："你说意图分类用的是 LLM 而不是 TinyBERT？"

回答：这个项目用 LLM 做意图分类（`DefaultIntentClassifier` 一次性把所有叶子节点发 LLM 打分）。TinyBERT 适合部署成本极低、分类数固定的场景（如"客服/销售/技术支持"3 分类），但它的语义理解能力做不到对 20+ 个叶子节点做细粒度分类。LLM 能理解"用户说'这个月的那个事'→ 结合上下文应该是财务报销流程的月度结算"，这是 TinyBERT 做不到的。

**但 LLM 意图分类有代价**：每次调用多几十 ms 延迟 + 几十个 token 成本。如果你的意图树只有 5 个分类、用户问题都很直接，TinyBERT 就够用了。

---

## 九、向量库与 Milvus — 架构选择

### 9.1 双实现切换

```yaml
rag.vector.type: milvus  # 默认，也可选 pg (PGVector)
```

两套实现都实现 `VectorStoreService` 接口，通过 `@ConditionalOnProperty` 激活。

### 9.2 共享 Collection 的隔离策略

**为什么不每个知识库建一个 Collection？**

假设企业有 30 个知识库（人事/财务/技术/产品/...）。每个建 Collection 就意味着 30 个索引、30 组参数要管理、migrate 时要操作 30 个 Collection。Milvus 2.x 单 Collection 百万级向量 + 标量过滤的性能足够。

隔离方式：每个 chunk 写入时带 `collection_name` 标量字段。检索时加 filter：

```python
filter = 'collection_name == "hr_kb" && metadata["doc_id"] in ["doc1", "doc2"]'
results = milvus_client.search(filter=filter, ...)
```

删除文档时：`collection_name == "hr_kb" && metadata["doc_id"] == "doc123"` → 精确删除该文档所有向量。

### 9.3 向量量级计算

```
15 万 chunk × 1536 维 × 4 字节 (float32) ≈ 920 MB (纯向量数据)
+ Milvus 索引开销 (HNSW 约 1.5-2x) ≈ 1.4-1.8 GB
+ 元数据 (content + metadata) ≈ 200 MB
≈ 总内存需求：2-3 GB
```

Milvus 单 Collection 支持百万级，15 万是轻松量级。

---

## 十、Agent/MCP 工具调用 — 实践细节

### 10.1 触发不是独立 Agent 循环

**关键区分**：Ragent 的 "Agent" 不是 LangChain 那种 "思考→行动→观察→思考" loop。它是**嵌入在意图识别→检索→工具调用流水线中的一步**。

```
用户问"今天北京天气怎么样？"
  → 意图分类命中 mcp-weather (MCP 节点)
  → McpParameterExtractor 调 LLM: "北京 + current" → {"city":"北京","queryType":"current"}
  → DefaultMcpToolRegistry.getExecutor("weather_query")
  → McpClientToolExecutor.execute({"city":"北京","queryType":"current"})
  → mcpClient.callTool(new CallToolRequest("weather_query", args))
  → HTTP SSE → mcp-server:9099 → WeatherMcpExecutor.handleCall()
  → 返回: "【北京 今日天气】\n日期: 2024年...\n天气: 晴\n温度: 18°C ~ 28°C..."
  → ContextFormatter.formatMcpContext() 把结果包装进 <data> 标签
  → 拼入 LLM Prompt → LLM 用天气数据生成自然语言回答
```

### 10.2 为什么不搞 Agent loop？

`McpToolExecutor.execute()` 是同步阻塞的，一次调用返回一次结果。没有循环是因为：

1. **企业场景的工具调用相对简单**：查天气、查订单、查库存 → 一次调用足够
2. **避免幻觉循环**：LLM 在 loop 中可能反复调用工具、不断调整参数，消耗大量 token
3. **延迟可控**：工具调用是问答管线的一步，不是对话的全部。用户等工具返回 + LLM 回复的总延迟 = 工具延迟 + LLM 延迟，没有 loop 的叠加

如果确实需要多步推理（"查用户订单→根据订单金额查优惠券→计算优惠后价格"），可以在意图树中组合多个 MCP 节点，或者让 LLM 在 Prompt 中做链式调用。

### 10.3 工具超时与线程池隔离

当前是 `CompletableFuture.supplyAsync()` + `mcpBatchExecutor` 线程池并行。问题是工具没有超时——如果 `mcpClient.callTool()` 阻塞 60 秒，一个线程就浪费了。

**改进方向**：

```java
CompletableFuture<CallToolResult> future = CompletableFuture.supplyAsync(
    () -> mcpClient.callTool(request), mcpBatchExecutor);
CallToolResult result = future.get(10, TimeUnit.SECONDS);  // 10 秒超时
```

超时后返回标准化错误结果 `{"error": "工具调用超时"}`，不影响其他工具。

### 10.4 业界方案对比

| 方案 | 代表 | 适用场景 |
|:---|:---|:---|
| 同步阻塞 + 线程池隔离 | Ragent, LangChain | 工具调用数量少(1-5个)，延迟低的场景 |
| 异步回调 + SSE 推送 | OpenAI Assistants | 工具可能需要 30s+，用户需要看进度 |
| 流式回调 (streaming tool use) | Claude, Dify | 工具执行中推送中间结果 |
| Agent loop (ReAct) | LangChain Agent | 多步推理场景 |

Ragent 的选择适合企业场景：工具少、延迟可预测、不搞复杂的链式推理。

---

## 十一、文档自动同步 — 分布式调度引擎

### 11.1 完整时序案例

**场景**：一个技术 Wiki 页面 `https://wiki.company.com/hr-policy`，配置每天 9:00 自动同步。

```
T+0: 用户在管理后台上传该 URL，配置 cron: "0 9 * * *"，schedule_enabled=1
  → KnowledgeDocumentServiceImpl.upload() → scheduleService.upsertSchedule()
  → INSERT INTO knowledge_document_schedule:
      doc_id=doc123, kb_id=kb1, cron_expr="0 9 * * *", enabled=1,
      next_run_time=2024-01-02 09:00:00

T+1天 09:00:00: KnowledgeDocumentScheduleJob.scan() 每 10s 扫描
  → SELECT * FROM knowledge_document_schedule
    WHERE enabled=1 AND next_run_time <= now() AND (lock_until IS NULL OR lock_until < now())

T+1天 09:00:05: ScheduleLockManager.tryAcquire("schedule_id")
  → UPDATE ... SET lock_owner='kb-schedule-host1-uuid', lock_until=now()+900s
    WHERE id=? AND (lock_until IS NULL OR lock_until < now())
  → 抢锁成功！返回 ScheduleLockLease

T+1天 09:00:05: knowledgeChunkExecutor.submit(() -> refreshProcessor.process(lease))

  阶段1 — 变更检测:
    fetchIfChanged(url, lastEtag="abc123", lastModified="2024-01-01", lastContentHash="sha256:def")
    
    1. HTTP HEAD https://wiki.company.com/hr-policy
       → 200 OK, ETag="abc123", Last-Modified="2024-01-01"
       → ETag 没变 → fetchResult.changed() == false
    
    → markSkippedIfOwned() — 本次跳过，更新 next_run_time=2024-01-03 09:00:00

T+3天 09:00:00: 再次触发，但这次 Wiki 页面已更新

  阶段1 — 变更检测:
    1. HTTP HEAD → ETag="xyz789" (变了！)
       → 继续下载
    
    2. GET → 临时文件 /tmp/knowledge-schedule-xxx.tmp
       → SHA-256 计算: "sha256:newhash" ≠ "sha256:def"
       → fetchResult.changed() == true
    
  阶段2 — 文档运行权抢占:
    tryMarkRunning("doc123")
    → UPDATE t_knowledge_document SET status='RUNNING'
      WHERE id=? AND status != 'RUNNING' AND enabled=1
    
  阶段3 — 文件上传:
    临时文件 → fileStorageService.upload() → S3/MinIO
    → stored.getUrl() = "s3://ragent-sources/hr_kb/new_file.pdf"
    
  阶段4 — 重新分块:
    chunkDocument(documentDO)
    → 解析（MinerU）→ 切分 → 嵌入 → 原子写入
    
  阶段5 — 清理:
    oldFileUrl != newFileUrl → deleteOldFileQuietly(oldFileUrl)
    release(lease) → UPDATE ... SET lock_owner=NULL, lock_until=NULL
```

### 11.2 分布式锁的安全设计

```java
// 抢锁: UPDATE 带 WHERE lock_until IS NULL OR lock_until < now()
// 别人持有的锁（lock_until 未到期）抢不到

// 续约: UPDATE ... SET lock_until=now()+900s WHERE id=? AND lock_owner=?
// 只续自己的锁

// 释放: UPDATE ... SET lock_owner=NULL, lock_until=NULL WHERE id=? AND lock_owner=?
// 只释放自己的锁

// 心跳: ScheduledExecutorService 每 300s (lock_seconds/3) 续约一次
// 心跳丢失 → ScheduleLockHeartbeat.isLost() == true
// 在每个阶段前 shouldAbortForLeaseLoss() 检查
```

**为什么不用 Redis 锁？** DB 乐观锁的优势：锁信息（lock_owner、lock_until）和调度记录在同一行，不需要额外的 Redis 依赖。且事务性的状态更新（调度状态 + 锁释放）可以在同一个 DB 事务中完成。缺点：DB 的压力比 Redis 大，需要心跳续约。

### 11.3 三级变更检测的工程考量

为什么要三级？因为不同源站的 HEAD 响应可靠性不同：

- **第一级（ETag/Last-Modified）**：大部分 CDN 和 Web 服务器正确返回。一个 HEAD 请求几 KB，延迟 < 100ms
- **第二级（SHA-256）**：对于 HEAD 不可靠的源站（如某些自建文件服务器），下载完整文件后算哈希。这是兜底但最可靠的方式

第一级命中（ETag 未变）→ 直接跳过，零下载流量。这在绝大多数周期中成立（文档不是每天改）。

### 11.4 事务原子写入

```java
transactionOperations.executeWithoutResult(status -> {
    knowledgeChunkService.deleteByDocId(docId);           // 1. 删旧 chunks (MySQL)
    knowledgeChunkService.batchCreate(docId, chunks);     // 2. 写新 chunks (MySQL)
    vectorStoreService.deleteDocumentVectors(col, docId); // 3. 删旧向量 (Milvus)
    vectorStoreService.indexDocumentChunks(col, docId, chunks); // 4. 写新向量 (Milvus)
    documentMapper.updateById(updateDO);                  // 5. 更新文档状态
});
```

Spring 事务保证 MySQL 操作原子。但 Milvus 不支持事务——如果步骤 4 失败，步骤 1-3 会回滚（MySQL），但步骤 3 的 Milvus 删除已执行。这是一个 **最终一致性场景**：失败后文档状态变为 FAILED，用户可以手动重试。下次重试时步骤 3 的 filter 条件仍然匹配，不会残留旧向量。

---

## 十二、Rerank 模型详解

### 12.1 为什么 cross-encoder 比 bi-encoder 准？

**Bi-encoder（Embedding 模型）**：query 和 document 独立编码成向量，相似度 = cos(q_vec, d_vec)。速度快（可以预计算文档向量），但 query 和 document 之间没有 token 级别的交叉注意力。

**Cross-encoder（Rerank 模型）**：query 和 document 拼接后一起喂给模型。模型能看到 `[CLS] query [SEP] document [SEP]` → 每个 token 能关注到另一侧的 token。代价是每对 (query, document) 都要跑一次前向传播，O(n) 复杂度。

```
Embedding (bi-encoder):     Query ──→ vec_q
                            Doc_1 ──→ vec_1  → cos(vec_q, vec_1) = 0.82
                            Doc_2 ──→ vec_2  → cos(vec_q, vec_2) = 0.78

Rerank (cross-encoder):    "OA审批流程 [SEP] OA审批包括提交..." → score 0.91
                           "OA审批流程 [SEP] 员工入职流程..." → score 0.32
```

Cross-encoder 能捕捉到 "审批" 和 "入职" 的不匹配 —— bi-encoder 的向量近似可能被 "OA系统" 这个共现词误导。

### 12.2 模型路由与降级

```yaml
ai.rerank.candidates:
  - id: qwen3-rerank, provider: bailian, priority: 1    # 主模型
  - id: rerank-noop, provider: noop, priority: 100       # 兜底：直接截断前 topN
```

`ModelRoutingExecutor.executeWithFallback()` 的降级机制：

```java
for (ModelTarget target : targets) {
    if (!healthStore.allowCall(target.id())) continue;  // 熔断中，跳过
    try {
        T response = caller.call(client, target);
        healthStore.markSuccess(target.id());  // 成功 → 健康
        return response;
    } catch (Exception e) {
        healthStore.markFailure(target.id()); // 失败 → 累加计数
        // failureCount >= failureThreshold(2) → OPEN → 30s 冷却 → HALF_OPEN
    }
}
throw new RemoteException("All rerank candidates failed");
```

三态熔断器：CLOSED（正常）→ OPEN（熔断，拒绝所有请求 30s）→ HALF_OPEN（放一个探测请求）→ 成功回 CLOSED / 失败回 OPEN。

**noop 兜底**：`NoopRerankClient.rerank()` 只做截断——取前 topN 个原样返回。保证 Rerank 故障不影响主流程。

### 12.3 DPO 的展望

项目代码中没有 DPO 实现（搜索 DPO/dpo/preference/reward 全为零命中）。但 `t_message_feedback` 表（用户点赞/点踩数据）为偏好数据采集提供了基础。

如果要做 DPO：
1. 从 `t_message_feedback` 提取 chosen（点赞）和 rejected（点踩或低分）的回答对
2. 构造 DPO 数据集：`{"prompt": "用户问题+检索上下文", "chosen": "好回答", "rejected": "差回答"}`
3. 训练：`L_DPO = -log σ(β × (log π_θ(chosen)/π_ref(chosen) - log π_θ(rejected)/π_ref(rejected)))`
4. 上线新模型

---

## 十三、设计模式实战 — 每个模式的落地

### 策略模式 — SearchChannel

```java
public interface SearchChannel {
    String getName();              // 通道名
    int getPriority();             // 优先级
    SearchChannelType getType();   // 通道类型
    boolean isEnabled(SearchContext context);
    SearchChannelResult search(SearchContext context);
}

// 四个实现: IntentDirectedSearchChannel, KeywordSearchChannel, VectorGlobalSearchChannel, YouComWebSearchChannel
// 新增通道: 实现接口 → 注册为 Spring Bean → 自动加入并行检索
```

### 注册表模式 — McpToolRegistry

```java
@PostConstruct
public void init() {
    for (McpToolExecutor executor : autoDiscoveredExecutors) {
        register(executor);  // 自动发现所有 Spring 容器中的 McpToolExecutor Bean
    }
}
// 新增工具: 实现 McpToolExecutor → @Component → 自动注册
// 不需要改配置文件，不需要改 Registry 代码
```

### 责任链模式 — 后处理器链

```java
// MultiChannelRetrievalEngine.executePostProcessors()
for (SearchResultPostProcessor processor : enabledProcessors) {
    try {
        chunks = processor.process(chunks, results, context);
    } catch (Exception e) {
        log.error("处理器 {} 失败，跳过", processor.getName(), e);
        // 继续下一个，不中断整条链
    }
}
// 每个处理器独立，增删处理器、调顺序只需改 getOrder()
```

### sealed interface — ChunkingOptions

```java
public sealed interface ChunkingOptions permits FixedSizeOptions, TextBoundaryOptions {
    Map<String, Integer> toConfigMap();
}
// 编译期保证: 所有 ChunkingOptions 实现只能是这两种
// match 的时候不用写 default 分支，IDE 能提示覆盖完整
```

---

## 十四、线程池架构

| 线程池 | Bean 名 | 队列类型 | 用途 |
|:---|:---|:---|:---|
| mcpBatchExecutor | mcpBatchExecutor | LinkedBlockingQueue | MCP 工具批量并行调用 |
| ragContextExecutor | ragContextExecutor | LinkedBlockingQueue | RAG 上下文组装 |
| ragRetrievalExecutor | ragRetrievalExecutor | LinkedBlockingQueue | 多路检索并行 |
| intentClassifyExecutor | intentClassifyExecutor | LinkedBlockingQueue | 意图分类（每个子问题并行） |
| memoryLoadExecutor | memoryLoadExecutor | LinkedBlockingQueue | 记忆并行加载 |
| memorySummaryExecutor | memorySummaryExecutor | LinkedBlockingQueue | 摘要异步生成 |
| knowledgeChunkExecutor | knowledgeChunkExecutor | LinkedBlockingQueue | 定时文档刷新 |
| defaultIntentClassifier | defaultIntentClassifier | LinkedBlockingQueue | 意图分类器专用 |

所有线程池用 `TtlExecutors` 包装，保证 `UserContext`（用户身份）和 Trace 信息（链路追踪 ID）在异步线程中不丢失。这是阿里巴巴 `transmittable-thread-local` 的典型用法。

---

## 十五、面试核心话术模板

### "介绍一下你做的 RAG 项目"

> Ragent 是一个企业级 RAG 平台，我负责了核心检索链路的开发。它覆盖了从文档入库到智能问答的完整链路。
>
> 文档入库方面，我实现了基于结构化 Block 的分块策略——PDF 通过 MinerU 解析产出标题、表格、代码等强类型 Block，然后按类型分发到专属切分器。表格用 key-value 格式嵌入替代 markdown 表格，检索准确率有明显提升。
>
> 检索方面，我做了四路并行检索（意图定向 + 关键词 BM25 + 向量 + 联网），通过 RRF 融合 + Rerank 精排的两阶段排序，解决了不同通道分数量纲不可比的问题。RRF 的 k 值和 Rerank 候选上限都是可配置的，可以根据业务场景调优。
>
> 记忆方面，我实现了滑动窗口（短期）+ 渐进式摘要（长期）的双层记忆。摘要是 LLM 驱动的，只记话题和约束不记答案，避免与实时检索的最新文档冲突。
>
> 工程方面，三态熔断器保护模型调用，分布式锁 + 心跳保证定时文档刷新在多实例下不冲突，所有线程池用 TTL 透传用户上下文。总共 8 个专用线程池按工作负载配置。

### "难点在哪里？"

> 最大的难点是表格的检索准确率。Embedding 模型按文字语义做匹配，markdown 表格的列对应关系靠位置对齐，向量化后位置信息被稀释了。我的方案是在嵌入阶段把表格渲染成 `列名: 值` 的 key-value 格式，把列名和值的语义关系写进字面文本。同时每个表格切片带 `sectionContext` 标明表身份，检索后回填。这个方案参考了 RAGFlow 和 STC 的做法。

### "有什么可以优化的？"

> 1. 工具调用可以加超时——当前是同步阻塞，极端情况下可能长时间占用线程
> 2. 记忆的读路径可以加 Redis 缓存——当前每次查 DB，高 QPS 下有优化空间
> 3. ChunkPacker 的合并策略可以更智能——当前是贪心按 maxChars 合并，可以考虑用语义边界做切分决策
> 4. RRF 的 k 值可以根据通道质量动态调整——当前是静态配置

---

## 附录：核心文件索引

| 功能 | 核心文件 |
|:---|:---|
| 问答管线 | `StreamChatPipeline.java` |
| 查询改写 | `MultiQuestionRewriteService.java`, `user-question-rewrite.st` |
| 意图分类 | `DefaultIntentClassifier.java`, `IntentResolver.java`, `intent-classifier.st` |
| 多通道检索 | `MultiChannelRetrievalEngine.java` |
| RRF 融合 | `FusionPostProcessor.java` |
| Rerank 精排 | `RerankPostProcessor.java`, `BaiLianRerankClient.java`, `RoutingRerankService.java` |
| 元数据富化 | `MetadataEnrichmentPostProcessor.java` |
| 上下文组装 | `DefaultContextFormatter.java`, `context-format.st` |
| 切分入口 | `StructuredChunkingService.java`, `ChunkerNode.java` |
| 文本策略 | `FixedSizeTextChunker.java`, `StructureAwareTextChunker.java` |
| 表格切分 | `TableChunker.java` |
| 打包器 | `ChunkPacker.java` |
| 记忆加载 | `DefaultConversationMemoryService.java` |
| 持久化 | `JdbcConversationMemoryStore.java`, `JdbcConversationMemorySummaryService.java` |
| 文档同步 | `KnowledgeDocumentScheduleJob.java`, `ScheduleRefreshProcessor.java` |
| 变更检测 | `RemoteFileFetcher.java` |
| 分布式锁 | `ScheduleLockManager.java` |
| 文档状态 | `DocumentStatusHelper.java` |
| 向量库 | `MilvusVectorStoreService.java` |
| MCP 工具 | `McpClientToolExecutor.java`, `McpToolRegistry.java` |
| MCP 服务 | `WeatherMcpExecutor.java`, `McpServerConfig.java` |
| 评测接口 | `EvalController.java` |
| 模型降级 | `ModelRoutingExecutor.java`, `ModelHealthStore` |
| 限流 | `FairDistributedRateLimiter.java` |
| 配置 | `application.yaml`, `SearchChannelProperties.java`, `MemoryProperties.java` |
