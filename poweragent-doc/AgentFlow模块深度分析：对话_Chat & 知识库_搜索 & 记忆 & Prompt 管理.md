# AgentFlow模块深度分析：对话/Chat \& 知识库/搜索 \& 记忆 \& Prompt 管理

# **模块深度分析：对话/Chat \& 知识库/搜索 \& 记忆 \& Prompt 管理**

---

## **一、对话/Chat 模块**

### **1\.1 核心类结构**

```Plain Text
ChatCompletionServiceImpl (@NodeType CHAT_NODE)
    ├── AISummaryService          —— 摘要生成 + 多轮上下文管理
    ├── ChatContextFilter         —— Token 截断 + 上下文过滤
    ├── AiCompletionCommonService —— LLM 调用公共逻辑
    └── AIApiService              —— 底层 HTTP 模型调用
```

### **1\.2 执行流程**

```Plain Text
用户问题 → chatNode.execute(dispatchData)
    │
    ├── 1. 获取对话历史 (ChatItemService.getChatItems)
    │       └── 从 ChatItem 表加载该 chatId 的历史消息
    │
    ├── 2. 获取摘要 (AISummaryService.getLastSummary)
    │       ├── 从 ChatItem.moduleSummaryData 中查找上次摘要
    │       ├── 裁切摘要节点之前的历史 (subList)
    │       └── 返回 {lastSummary, truncatedHistories}
    │
    ├── 3. Token 超长处理 (ChatContextFilter)
    │       ├── 快速判断: rawTextLen < maxTokens * 0.5 → 跳过
    │       ├── 未开摘要: 从后往前截断 (保留最近的对话)
    │       └── 开启摘要: 不截断，触发 LLM 摘要生成
    │
    ├── 4. 拼接 Prompt
    │       ├── System: systemPrompt + "\n[history]\n" + (lastSummary 或 摘要结果)
    │       ├── Messages: chatHistories (最近的 N 轮)
    │       └── User: question + quoteQA (知识库检索结果)
    │
    ├── 5. 引用过滤 (filterQuote)
    │       ├── JTokkit 精确计算 token 数
    │       ├── token 加起来超过 maxToken 则截断
    │       └── quoteTemplate 模板填充: {index}. {q} [{a}]
    │
    └── 6. 调用 LLM (aiCompletionCommonService.aiChat)
            ├── POST {modelUrl}/chat/completions
            ├── Stream: SSE 实时推送 token
            └── 返回: answerText, chatResponse, structOutput
```

### **1\.3 技术难点**

#### **难点一：长对话 Token 超限处理**

```Java
// ChatContextFilter.filterMessages()
// 核心策略: 从后往前裁剪，保证最近对话优先

// 快速路径: 文本长度小于 maxToken * 0.5，跳过计算
if (rawTextLen < maxTokens * 0.5) return;

// 开启摘要: 保留所有 messages，触发 LLM 摘要
// 未开启: 从后往前截断
for (int i = chatPrompts.size() - 1; i >= 0; i--) {
    chats.add(0, item);
    maxTokens -= countPromptTokens(item.value, item.role);
    if (maxTokens <= 0) {
        if (!summary) {
            chats.remove(0);  // 截断
            if (isHuman) chats.remove(0);  // 同时移除配对 AI 回复
        } else {
            aiSummary = true;  // 标识需要摘要
        }
        break;
    }
}
```

**Token 计数**: 使用 JTokkit \(Tiktoken 平替\) 精确计算，而非字符数估算。

#### **难点二：智能摘要生成**

```Java
// AISummaryService.getAISummary()
// 当对话过长且开启摘要开关时:

1. 拼接历史: "Human:xxx\nAI:xxx\nHuman:xxx..."
2. 调用摘要 LLM: POST {modelUrl} 
   使用 AI_SUMMARY_PROMPT 模板: "请对以下对话历史进行摘要..."
3. 解析返回: 提取 JSON 中的摘要内容
4. 替换 systemPrompt: systemPrompt + "\n[history]\n" + summaryPrompt
5. 再次校验长度: 摘要后仍然超长 → 抛出 CHAT_OVER_TOKEN_LIMIT_WITH_SUMMARY
```

**面试关键点**: 摘要不是简单的文本压缩，而是通过 LLM 对历史对话进行语义压缩，保留关键信息的同时大幅减少 token 消耗。

#### **难点三：引用溯源 \(Quote\)**

```Java
// filterQuote() → 将知识库检索结果格式化为引用标记
quoteText = MessageFormatUtils.getValue(item, index, quoteTemplate);
// 模板: "[{index}] {q}" 或 "[{index}] {q} [{a}]"

// 最终拼入 user message:
// "用户问题: 电池参数是什么?\n\n参考资料:\n[1] 电池容量为5000mAh\n[2] 充电参数为5V/2A"
```

### **1\.4 面试重点**

### **1\.5 业界对比**

---

## **二、知识库/搜索模块**

### **2\.1 检索架构**

```Plain Text
DatasetSearchServiceImpl (@NodeType DATASET_SEARCH_NODE)
    │
    ├── getKnowledgeIds()         —— 知识库 ID 解析 (支持动态/固定)
    ├── buildKnowledgeRagDTO()    —— 构建检索参数
    │
    ▼
KnowledgeSearchServiceImpl.knowledgeSearch()
    │
    ├── 权限过滤: filterPermissionKnowledge()
    ├── 数据集启禁用: filterDatasetIdsByEnabled()
    │
    ├── switch(searchMode)
    │   ├── EMBEDDING   → vectorSearch()  → DenseVectorService.directVectorSearch()  → Milvus
    │   ├── FULLTEXT    → fullTextSearch()→ DenseVectorService.directFullTextSearch()→ ES
    │   └── MIXED_RECALL→ mixSearch()
    │
    ├── mixSearch()
    │   ├── 并行: vectorSearch() + fullTextSearch()
    │   ├── 融合: RRF (rrfSwitch=true) 或 Merge (去重合并)
    │   └── 重排序: reRanker (reRankerSwitch=true)
    │
    └── filterTokens() → 按 token 上限截断
```

### **2\.2 混合召回核心算法**

```Java
// mixSearch() → 三种检索模式 + RRF 融合 + Rerank

// 模式 1: EMBEDDING (纯向量)
DenseVectorService.directVectorSearch({
    query: "电池参数",
    modelName: "text-embedding-3-large",
    similarity: 0.7,
    recallLimit: 150
}) → Milvus ANN 检索 → Top-K 向量结果

// 模式 2: FULLTEXT_RECALL (纯全文)
DenseVectorService.directFullTextSearch({
    query: "电池参数",
    recallLimit: 150,
    teamId: "xxx"
}) → ES BM25 全文检索 → Top-K 全文结果

// 模式 3: MIXED_RECALL (混合)
1. 并行执行向量检索 + 全文检索
2. RRF 融合:
   vectorSearchRatio * 10 = 6, fullTextSearchRatio * 10 = 4
   RrfRankerUtil.rankFusion([vectorResult, fullTextResult], k=60)
   → docId → RRF score (map)
3. 合并两个结果列表的 score
4. ReRank (可选):
   PEG Ranker 模型二次排序
   → 过滤 similarity 以下的结果
   → 按 ranker score 降序
```

### **2\.3 RRF 融合算法**

```Java
// RrfRankerUtil.rankFusion()
// Reciprocal Rank Fusion
// score(d) = Σ 1 / (k + rank_i(d))
// k=60, 向量权重=6, 全文权重=4

// 向量检索排名: docA=1, docB=2, docC=3
// 全文检索排名: docB=1, docD=2, docA=3

// RRF 计算:
// docA: 6/(60+1) + 4/(60+3) = 0.0984 + 0.0635 = 0.1619
// docB: 6/(60+2) + 4/(60+1) = 0.0968 + 0.0656 = 0.1624  ← 排名最高
```

### **2\.4 知识库过滤**

```Java
// 知识库类型一致性过滤 (filterKnowledgeIds)
// 多个知识库必须同类型 + 同向量模型
Knowledge first = knowledgeList.stream().findFirst();
resultIds = knowledgeList.stream()
    .filter(k → sameType(first, k) && sameVectorModel(first, k))
    .collect(Collectors.toList());

// 数量限制
knowledgeLimit = 50;  // 最多 50 个知识库
datasetLimit = 10000;  // 最多 10000 个数据集
```

### **2\.5 技术难点**

#### **难点一：为什么不用纯向量？**

> 向量检索擅长语义相似度，但对精确关键词匹配（如型号、代码、ID）效果差。混合召回通过 RRF 融合向量和 BM25，兼顾语义理解和精确匹配。
> 
> 

#### **难点二：Rerank 如何保证精度？**

```Java
// PEG Ranker 交叉编码器 (Cross-Encoder)
// query 和每个 chunk 拼接后一起送入 Ranker 模型
// 得到的分数比双塔 Embedding 更精准
modelService.pegRanker({
    input: [chunk1, chunk2, ...],  // 待排序文本列表
    content: "电池参数"              // 查询
});
// → [{index: 0, ranker: 0.92}, {index: 1, ranker: 0.87}]
```

#### **难点三：动态知识库 ID**

```Java
// 支持运行时从全局变量中获取 knowledgeIds
if (variables.containsKey("knowledgeIds")) {
    // 动态模式: 从变量中读取
    knowledgeIds = JSON.parseArray(variables.get("knowledgeIds"));
} else {
    // 静态模式: 从节点配置中读取
    knowledgeIds = params.getDatasets();
}
```

### **2\.6 面试重点**

### **2\.7 业界对比**

---

## **三、记忆模块**

### **3\.1 总体架构**

```Plain Text
记忆模块 = Java 端 (字段配置) + Python 端 (向量化 + 存储)

┌─────────────────────────────────────────────┐
│  Java 端 (agentflow-server)                  │
│                                              │
│  TeamMemoryFieldConfig                        │
│    ├── 记忆字段 CRUD (每个 team 可自定义)       │
│    └── /api/v1/memory/field-config/*          │
│                                              │
│  AgentMemoryFieldRelation                     │
│    ├── Agent 开启了哪些记忆字段                 │
│    └── 关联 Agent ↔ 记忆字段配置               │
│                                              │
│  AutoAgent.longTermMemory                     │
│    └── JSON 配置: {"enabled": true, ...}      │
└─────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│  Python 端 (af-rag-server)                    │
│                                              │
│  /vector/memory/*  (Flask)                    │
│    ├── /insert  ← mem0.add()                 │
│    ├── /query   ← mem0.search()              │
│    ├── /update  ← mem0.update()              │
│    └── /delete  ← mem0.delete()              │
│                                              │
│  memory_scheduler (APScheduler)               │
│    └── 定时任务: 向量化 + 清理                   │
│                                              │
│  ADKExecutor.getPrompt()                      │
│    └── 拼接长期记忆:                            │
│        text += "\n【长期记忆】" + memory       │
└─────────────────────────────────────────────┘
```

### **3\.2 记忆字段配置**

```Java
// TeamMemoryFieldConfig: 每个 team 的自定义记忆字段
{
  "id": "mem_001",
  "teamId": "12345",
  "fieldName": "user_name",       // 字段名
  "fieldType": "string",          // string/number/date
  "status": 1,                    // 启用/禁用
  "description": "用户姓名"
}

// Agent 开启哪些记忆字段
AgentMemoryFieldRelation: {
  agentId → [mem_001_id, mem_002_id, ...]
}
```

### **3\.3 Prompt 拼接**

```Python
# ADKExecutor.get_prompt()
def get_prompt(self, context):
    text = self._replace_variable(agent_meta.promptInfo)
    
    # 1. 拼接临时知识库 (ragOptions)
    if self.rag_options and self.rag_options.quoteQA:
        rag = "\n".join([x.model_dump_json() for x in self.rag_options.quoteQA])
        text += f"\n\n【背景知识】\n{rag}"
    
    # 2. 拼接长期记忆 (Mem0)
    if self.agent_meta.longTermMemory:
        text += f"\n\n【长期记忆】以下是和问题相关的记忆\n{self.agent_meta.longTermMemory}"
    
    # 3. 拼接溯源提示
    if self.agent_meta.knowledge and self.agent_meta.knowledge.showSource:
        text += "\n" + quote_prompt
    
    return text
```

### **3\.4 面试重点**

### **3\.5 业界对比**

---

## **四、Prompt 管理模块**

### **4\.1 核心数据结构**

```Plain Text
Prompt 表 (模板主表)
  promptId, name, content (模板内容), type, tags
  userId, teamId, tenantId

PromptDetailVersion 表 (版本表)
  promptId, version, content (版本快照)
  createTime, creator
```

### **4\.2 Prompt 模板语法**

```Plain Text
你是{{role}}，你的任务是帮助用户{{task}}。

请遵循以下规则:
{{rules}}

用户输入: {{userChatInput}}

参考资料:
{{quoteQA}}
```

**变量占位符**: `{{variableName}}`，执行时通过 `MessageFormatUtils.replaceVariable()` 替换为实际值。

### **4\.3 版本管理**

```Plain Text
Prompt 版本管理 = 类似 Agent Snapshot

创建 → 编辑 → 发布(生成新版本号)
  │              │
  │              └── PromptDetailVersion 记录快照
  └── Prompt.content 保持最新

特点:
  - 每次发布 content 快照到 version 表
  - 支持版本回溯
  - Agent 可绑定特定版本的 Prompt
```

### **4\.4 Prompt 与 Agent 的关联**

```Plain Text
Agent → AutoAgent.promptInfo = 完整 Prompt 文本
  或
Agent → 引用 Prompt 模板 ID → 加载 Prompt.content → 替换变量

两种方式:
  1. 直接编辑: Agent 内嵌 Prompt，不关联模板库
  2. 引用模板: Agent 关联 Prompt 模板，支持中心化管理
```

### **4\.5 面试重点**

### **4\.6 业界对比**

---

## **五、四模块总览与联动**

```Plain Text
┌─────────────────────────────────────────────────────────────┐
│  用户问题                                                      │
│     │                                                        │
│     ▼                                                        │
│  ┌──────────────────┐                                        │
│  │ Prompt 管理       │ 提供 systemPrompt + 变量模板               │
│  │ (模板替换+版本)    │                                        │
│  └────────┬─────────┘                                        │
│           │                                                  │
│           ▼                                                  │
│  ┌──────────────────┐                                        │
│  │ 记忆模块          │ 注入长期记忆 + 对话历史摘要                  │
│  │ (Mem0+Session)    │ → Prompt 的 [history] 段               │
│  └────────┬─────────┘                                        │
│           │                                                  │
│           ▼                                                  │
│  ┌──────────────────┐                                        │
│  │ 知识库/搜索        │ ES+Milvus 混合召回 → quoteQA               │
│  │ (RRF+Rerank)      │ → Prompt 的 [背景知识] 段              │
│  └────────┬─────────┘                                        │
│           │                                                  │
│           ▼                                                  │
│  ┌──────────────────┐                                        │
│  │ 对话/Chat          │ 拼装完整 Prompt → 调用 LLM              │
│  │ (token 截断+摘要)   │ → SSE 流式输出                         │
│  └──────────────────┘                                        │
└─────────────────────────────────────────────────────────────┘
```

**每个模块在 Prompt 中的位置**:

```Plain Text
[Memory: 长期记忆] → [Prompt: System角色定义] → [Chat: 对话历史摘要]
→ [Knowledge: 搜索到的背景知识] → [Chat: 当前用户问题] → LLM 生成回复
```



