# Agent 核心机制面试 Q\&A

---

## **Q1: Agent 循环流程是怎样的？ReAct 介绍**

### **1\.1 本项目中的 ReAct 循环**

本项目基于 **Google ADK** 的内置 `PlanReActPlanner`，并实现了 `CustomPlanner` 进行定制。核心循环是 **Plan → Action → Reasoning → \.\.\. → Final Answer**：

```Plain Text
┌─────────────────────────────────────────────────────────────┐
│  ReAct 循环 (CustomPlanner extends PlanReActPlanner)        │
│                                                             │
│  用户问题: "分析最近一周策略A的转化率变化"                       │
│                                                             │
│  Round 1:                                                   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ /*PLANNING*/                                         │   │
│  │ 1. 先获取最近7天的数据                                 │   │
│  │ 2. 分析数据趋势                                       │   │
│  │ 3. 给出优化建议                                       │   │
│  │                                                      │   │
│  │ /*ACTION*/                                           │   │
│  │ function_call(data_pull_tool, {                       │   │
│  │   "time_range": "2024-07-13~2024-07-20"              │   │
│  │ })                                                   │   │
│  │                                                      │   │
│  │ /*REASONING*/                                        │   │
│  │ 已获取最近7天数据，共350条记录。接下来需要分析趋势。       │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  Round 2:                                                   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ /*ACTION*/                                           │   │
│  │ function_call(analysis_tool, {                        │   │
│  │   "data": [...],                                     │   │
│  │   "metric": "conversion_rate"                        │   │
│  │ })                                                   │   │
│  │                                                      │   │
│  │ /*REASONING*/                                        │   │
│  │ 分析结果表明转化率从12%下降至9%，主要原因是渠道A流量质量  │   │
│  │ 下降。                                                │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  Round 3:                                                   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ /*FINAL_ANSWER*/                                     │   │
│  │ 根据分析，策略A的转化率在最近一周从12%下降至9%。建议：     │   │
│  │ 1. 优化渠道A的流量来源                                  │   │
│  │ 2. 增加渠道B的投放比例                                  │   │
│  │ 3. A/B测试新的落地页                                    │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### **1\.2 CustomPlanner 的定制点**

```Python
# agent/auto_agent/framework_adapter/adk/custom_planner.py
class CustomPlanner(PlanReActPlanner):

    def _build_nl_planner_instruction(self) -> str:
        """自定义规划指令"""
        # 1. 要求先计划再执行
        # 2. 工具代码和推理交错进行
        # 3. 最终答案必须在 FINAL_ANSWER_TAG 下

    def _handle_non_function_call_parts(self, response_part, preserved_parts):
        """处理非 function_call 的响应部分"""
        # 只保留 PLANNING 部分作为思考过程展示
        # 去掉 ACTION 和 REASONING 标签

    def _only_keep_plan(self, text: str) -> str:
        """只保留 plan 部分，去掉其余部分"""
        if ACTION_TAG in text:
            text = text.split(ACTION_TAG, 1)[0]
        if REASONING_TAG in text:
            text = text.split(REASONING_TAG, 1)[0]
        if FINAL_ANSWER_TAG in text:
            text = text.split(FINAL_ANSWER_TAG, 1)[0]
        return text
```

**面试关键点**: CustomPlanner 将 LLM 思考过程做了**信息分级**——PLANNING 部分作为思考过程展示给用户，ACTION 和 REASONING 部分隐藏（避免暴露工具调用细节）。

### **1\.3 ReAct 与传统 Chain 的区别**

### **1\.4 ADK Runner 的循环控制**

```Python
# agent/apps/multi_agent/api.py
run_config = RunConfig(
    max_llm_calls=20,  # ← 核心：最多 20 轮 LLM 调用
    # streaming_mode=StreamingMode.SSE,
)

async for event in runner.run_async(
    user_id=user_id,
    session_id=session_id,
    new_message=new_message,
    run_config=run_config,
):
    # 每个 event 是一轮 PLAN/ACTION/REASONING/FINAL_ANSWER
    yield format_sse(event)
```

**面试关键点**: `max_llm_calls=20` 是整个循环的硬上限。即使 LLM 没有输出 FINAL\_ANSWER，到 20 轮也会自动终止。这是防止死循环的核心机制。

---

## **Q2: Agent 死循环问题**

### **2\.1 死循环的三种场景**

```Plain Text
场景1: 工具调用循环
  LLM → 调用工具A → 结果不够好 → 调用工具A → 还是不够好 → ...

场景2: 推理自循环
  LLM → PLANNING → ACTION → REASONING →
  "我需要更多信息" → PLANNING → ACTION → ...

场景3: Agent 嵌套循环
  AgentA → 调用 AgentB → AgentB 调用 AgentA → ...
```

### **2\.2 三层防御机制**

```Plain Text
┌─────────────────────────────────────────────────────────┐
│  第1层: 硬上限 (框架级)                                   │
│                                                         │
│  RunConfig.max_llm_calls = 20                           │
│  → 无论什么情况，最多 20 轮 LLM 调用后强制终止             │
│  → ADK Runner 内部计数，达到上限抛出异常                   │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  第2层: 循环检测 (应用级)                                  │
│                                                         │
│  AutoAgentServiceImpl.hasCycle()                        │
│  → 发布前 DFS 检测 Agent 嵌套环                           │
│                                                         │
│  def hasCycleHelper(visited, userInfo, agentDTO):        │
│      if agentDTO.agentId in visited:                     │
│          return True  # 发现环！阻止发布                    │
│      visited.add(agentId)                                │
│      for subAgent in agentDTO.tools.agentInfoList:       │
│          childAgent = findByAgentId(subAgent.id)         │
│          if hasCycleHelper(visited, userInfo, childAgent):│
│              return True                                 │
│      visited.remove(agentId)  # 回溯                     │
│      return False                                        │
│                                                         │
│  → 在 Agent 发布前就阻止循环引用                            │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  第3层: 超时保护 (节点级)                                  │
│                                                         │
│  ModuleService.executeWithTimeout()                       │
│  → 单节点执行超时 → Future.cancel()                       │
│  → 最多重试 3 次，每次重试间隔递增                          │
│                                                         │
│  ADKExecutor: 30s 无响应 → litellm.exceptions.Timeout    │
│  → yield {"error_message": "agent 处理超时"}             │
└─────────────────────────────────────────────────────────┘
```

### **2\.3 面试回答模板**

> 死循环防御分三层：
> 
> 1. **框架层硬上限**: `max_llm_calls=20`，ADK Runner 内置计数
> 
> 2. **应用层循环检测**: 发布前 DFS 检测 Agent 嵌套环，有环直接拒绝发布
> 
> 3. **节点级超时**: 单次工具调用 60\-180s 超时，LLM 调用 30s 超时
> 
> 

---

## **Q3: Agent 请求大模型的上下文怎么组装的？**

### **3\.1 上下文组装完整链路**

```Python
# agent/auto_agent/framework_adapter/adk/executor.py
# ADKExecutor.get_prompt()

def get_prompt(self, context=None) -> str:
    # Step 1: 基础 Prompt (Agent 管理模块配置)
    text = self._replace_variable(self.agent_meta.promptInfo)
    # 替换 {{variable}} 占位符为实际值
    
    # Step 2: 拼接临时知识库 (RAG召回结果)
    if self.rag_options and self.rag_options.quoteQA:
        rag = "\n".join([x.model_dump_json() for x in self.rag_options.quoteQA])
        text += f"\n\n【背景知识】\n如果背景知识中存在与问题直接相关的信息，请优先且仅使用这些信息进行总结或回答\n以下是背景知识\n{rag}"
    
    # Step 3: 拼接长期记忆 (Mem0)
    if self.agent_meta.longTermMemory:
        text += f"\n\n【长期记忆】以下是和问题相关的记忆\n{self.agent_meta.longTermMemory}"
    
    # Step 4: 拼接溯源提示词
    if self.agent_meta.knowledge and self.agent_meta.knowledge.showSource:
        text += "\n" + quote_prompt
    
    # Step 5: 多模态兜底
    text += """\n## 重要
当用户想要生成一个多模态内容，但你无法提供多模态文件时，直接仅回复下面文本内容：
很遗憾，当前我没有直接生成{图片/视频/音频/文件}的能力和对应的工具调用。"""
    
    return text
```

### **3\.2 上下文组装的可视化**

```Plain Text
┌───────────────────────────────────────────────────────────┐
│ 完整 LLM Request                                          │
│                                                           │
│ ┌─────────────────────────────────────────────────────┐   │
│ │ system:                                              │   │
│ │ ┌─────────────────────────────────────────────────┐ │   │
│ │ │ 1. Agent 角色定义 (promptInfo)                   │ │   │
│ │ │    "你是一个数据分析专家..."                       │ │   │
│ │ ├─────────────────────────────────────────────────┤ │   │
│ │ │ 2. 工具声明 (FunctionDeclaration)                │ │   │
│ │ │    - knowledge_search(question: str)             │ │   │
│ │ │    - data_analysis(data: str)                    │ │   │
│ │ │    - transfer_to_agent(...)                      │ │   │
│ │ ├─────────────────────────────────────────────────┤ │   │
│ │ │ 3. PlanReAct 指令 (CustomPlanner)                │ │   │
│ │ │    "先制定计划→执行工具→推理→最终答案"             │ │   │
│ │ ├─────────────────────────────────────────────────┤ │   │
│ │ │ 4. 长期记忆 (longTermMemory)                     │ │   │
│ │ │    "用户偏好电池续航>5000mAh..."                  │ │   │
│ │ ├─────────────────────────────────────────────────┤ │   │
│ │ │ 5. 背景知识 (ragOptions.quoteQA)                 │ │   │
│ │ │    "产品A: 电池5000mAh..."                        │ │   │
│ │ ├─────────────────────────────────────────────────┤ │   │
│ │ │ 6. 溯源指令 (quote_prompt)                       │ │   │
│ │ │    "引用时使用 quoteMark 格式..."                  │ │   │
│ │ ├─────────────────────────────────────────────────┤ │   │
│ │ │ 7. 多模态兜底                                     │ │   │
│ │ │    "无法生成时回复：很遗憾..."                      │ │   │
│ │ └─────────────────────────────────────────────────┘ │   │
│ ├─────────────────────────────────────────────────────┤   │
│ │ messages:                                            │   │
│ │ ┌─────────────────────────────────────────────────┐ │   │
│ │ │ user: "分析最近一周策略A的转化率"                   │ │   │
│ │ │ assistant: /*PLANNING*/ 1.获取数据... /*ACTION*/  │ │   │
│ │ │           function_call(data_pull_tool)           │ │   │
│ │ │ tool: {data_pull_result: [...]}                  │ │   │
│ │ │ assistant: /*REASONING*/ 数据已获取...             │ │   │
│ │ │           /*ACTION*/ function_call(analysis_tool) │ │   │
│ │ │ tool: {analysis_result: {...}}                   │ │   │
│ │ └─────────────────────────────────────────────────┘ │   │
│ └─────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────┘
```

### **3\.3 对话历史窗口管理**

```Python
# ADKExecutor.before_model_callback()
async def before_model_callback(self, callback_context, llm_request):
    """每次 LLM 调用前，裁剪对话历史"""
    
    # 找到所有的 user 消息索引
    user_indices = [
        i for i, item in enumerate(llm_request.contents)
        if item.role == "user" and item.parts and item.parts[0].text
    ]
    
    # 保留最近 N 轮 (historyRound，默认3)
    keep_turns = min((agent_meta.modelInfo.historyRound or 3) + 1, len(user_indices))
    start_user_idx = user_indices[-keep_turns]
    
    # 截断旧历史
    llm_request.contents = llm_request.contents[start_user_idx:]
```

---

## **Q4: 上下文结构是什么？**

### **4\.1 结构定义**

```Python
# ADK 的上下文使用 google.genai.types.Content 和 Part
# 一条完整的上下文包含:

LlmRequest {
    model: "gpt-4",
    contents: [
        # System 部分 (工具声明 + 指令)
        Content(role="user", parts=[
            Part(text="你是一个数据分析专家..."),     # Agent Prompt
            Part(text="可用工具:\n1. knowledge_search\n2. data_analysis\n..."),
            Part(text="/*PLANNING*/ ... /*ACTION*/ ... /*REASONING*/ ... /*FINAL_ANSWER*/ ..."),
        ]),
        
        # 对话历史
        Content(role="user", parts=[
            Part(text="分析最近一周策略A的转化率")
        ]),
        Content(role="model", parts=[
            Part(function_call=FunctionCall(name="data_pull_tool", args={...}))
        ]),
        Content(role="user", parts=[
            Part(function_response=FunctionResponse(name="data_pull_tool", response={...}))
        ]),
        Content(role="model", parts=[
            Part(text="/*REASONING*/ 数据已获取，转化率下降3%..."),
            Part(function_call=FunctionCall(name="analysis_tool", args={...}))
        ]),
    ],
    tools: [
        FunctionDeclaration(name="knowledge_search", description="...", parameters={...}),
        FunctionDeclaration(name="data_analysis", description="...", parameters={...}),
        FunctionDeclaration(name="transfer_to_agent", description="...", parameters={...}),
    ]
}
```

### **4\.2 Content 类型**

**面试关键点**: 工具返回值不是 model role，而是 user role。这是因为 LLM 的训练数据格式就是 `user→assistant→user→assistant`，function\_response 作为 user 消息传给 LLM，让 LLM 在下一轮继续生成。

### **4\.3 与 OpenAI Chat API 的映射**

```Python
# 本项目用 LiteLlm 统一调用多种模型，会自动转换:

ADK Content/Part              →    OpenAI Chat Completion API
─────────────────────────────────────────────────────────
Content(role="user",          →    {"role": "user",
  Part(text="..."))                     "content": "..."}
                                        
Content(role="model",         →    {"role": "assistant",
  Part(function_call=...))             "tool_calls": [...]}
                                        
Content(role="user",          →    {"role": "tool",
  Part(function_response=...))         "tool_call_id": "...",
                                        "content": "..."}
```

---

## **Q5: 上下文超长了怎么办？**

### **5\.1 三级处理策略**

```Plain Text
┌─────────────────────────────────────────────────────────┐
│  第1级: 对话历史裁剪 (before_model_callback)              │
│                                                         │
│  每次 LLM 调用前，只保留最近 N 轮 user 消息               │
│  keep_turns = min(historyRound + 1, total_user_count)    │
│  → historyRound 默认 3，即可保留最近 3+1=4 轮             │
│                                                         │
│  裁剪是"从旧到新"保留 —— 丢弃最早的消息，保留最近的        │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  第2级: Token 精确截断 (ChatContextFilter)                │
│                                                         │
│  Java 端 WorkFlow 中:                                    │
│  1. JTokkit 精确计算每条消息的 token 数                    │
│  2. 从后往前累加 token 数                                 │
│  3. 超过 maxTokens → 截断 (未开启摘要)                     │
│  4. 超过 maxTokens → 触发摘要 (开启摘要)                   │
│                                                         │
│  截断规则:                                                │
│  - 如果截断的是 HUMAN 消息 → 同时移除配对的 AI 回复         │
│  - 如果截断后 chats 为空 → 抛异常 CHAT_OVER_TOKEN_LIMIT   │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  第3级: LLM 摘要压缩 (AISummaryService)                   │
│                                                         │
│  当开启摘要 且 Token 超限时:                               │
│  1. 拼接历史: "Human:xxx\nAI:xxx\nHuman:xxx..."         │
│  2. 调用摘要 LLM 生成语义压缩:                             │
│     prompt = "请对以下对话历史进行摘要，保留关键事实和决策"  │
│  3. 用摘要替换原始历史 → 大幅减少 token 消耗                │
│  4. System Prompt 变为:                                  │
│     systemPrompt + "\n[history]\n" + summaryPrompt       │
│                                                         │
│  安全检查:                                                │
│  - 摘要后仍然超长 → 抛异常                                 │
│    CHAT_OVER_TOKEN_LIMIT_WITH_SUMMARY                    │
└─────────────────────────────────────────────────────────┘
```

### **5\.2 Python 端裁剪 vs Java 端截断**

### **5\.3 快速路径优化**

```Java
// ChatContextFilter.filterMessages()
// 优化: 总字符数 < maxTokens * 0.5 时直接跳过计算
if (rawTextLen < maxTokens * 0.5) {
    return messages;  // 无需计算，直接返回
}
```

---

## **Q6: 压缩过程中丢失了工具调用历史导致重复调用怎么办？**

### **6\.1 问题场景**

```Plain Text
原始对话:
  用户: "查一下产品A的库存"
  LLM: function_call(check_inventory, {product: "A"})
  Tool: {"库存": 150}
  LLM: "产品A库存150件"
  用户: "那产品B呢"
  LLM: function_call(check_inventory, {product: "B"})  ← 正常

如果摘要/裁剪不当:
  summary = "用户在询问产品库存"  ← 丢失了 tool call 细节
  
  用户: "那产品B呢"
  LLM: 不知道之前查了A，重新查 → function_call(check_inventory, {product: "A"})  ← 重复!
```

### **6\.2 本项目的保护措施**

```Plain Text
┌─────────────────────────────────────────────────────────┐
│  保护1: Python 端按轮次裁剪 (保留 function_call 完整轮次)   │
│                                                         │
│  before_model_callback 只裁剪 user 消息之前的轮次           │
│  不会截断"一轮完整对话"的中间部分                            │
│                                                         │
│  user 消息 = 完整的对话轮次分界线                           │
│  每一轮 = user → (model+function_call → tool → model)* → │
│           next user                                      │
│                                                         │
│  裁剪策略: 丢弃"整轮"的旧对话，不拆散单轮                    │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  保护2: Java 端截断保留配对关系                            │
│                                                         │
│  ChatContextFilter:                                      │
│  if (截断的是 HUMAN 消息) {                                │
│      chats.remove(0);  // 移除 HUMAN                    │
│      chats.remove(0);  // 同时移除配对的 AI 回复            │
│  }                                                      │
│  保证 Human+AI 成对移除                                    │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  保护3: 摘要的语义保留                                    │
│                                                         │
│  AISummaryService 摘要指令:                               │
│  "保留关键事实、工具调用结果和最终回答，不要丢失数据"         │
│                                                         │
│  摘要示例输出:                                            │
│  {                                                      │
│    "summary": "用户查询了产品A库存(结果:150件)，            │
│                接着询问产品B库存，                        │
│                尚未获得结果"                              │
│  }                                                      │
│                                                         │
│  → 工具调用的"结果"保留在摘要中，                          │
│    LLM 不需要重复调用就能知道之前的结果                      │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  保护4: 摘要粒度控制                                      │
│                                                         │
│  ChatItem 中存储 moduleSummaryData:                      │
│  [                                                      │
│    {moduleType: "chatNode", moduleId: "xxx",             │
│     summary: "用户查询产品A库存(150件)...",                │
│     lastSummary: "之前讨论过产品库存管理..." }             │
│  ]                                                      │
│                                                         │
│  每个对话节点可以独立维护自己的摘要                          │
│  → 不同时间窗口的摘要互不覆盖                               │
└─────────────────────────────────────────────────────────┘
```

### **6\.3 面试回答模板**

> 四层保护：
> 
> 1. **Python 端按"完整轮次"裁剪**：以 user 消息为分界线，不拆散 tool call \+ tool response \+ model 的原子组
> 
> 2. **Java 端配对截断**：截断 Human 消息时自动移除配对的 AI 回复
> 
> 3. **摘要语义保留**：摘要指令要求"保留工具调用结果和关键数据"
> 
> 4. **分模块摘要**：每个 chatNode 独立维护摘要，互不覆盖，避免信息丢失
> 
> 

---

## **Q7: 多 Agent 上下文如何管理？**

### **7\.1 本项目中的多 Agent 模式**

```Plain Text
┌─────────────────────────────────────────────────────────┐
│  IntentAgent (根 Agent)                                  │
│                                                         │
│  sub_agents: [                                          │
│    MetadataAgent, StrategyAgent, StrategyViewAgent,      │
│    DirectAnalysisAgent, RunDataAgent                    │
│  ]                                                      │
│                                                         │
│  每个 sub_agent 有自己独立的:                              │
│  ├── name (用于 transfer_to_agent)                       │
│  ├── instruction (系统 Prompt)                            │
│  ├── tools (自己的工具集)                                  │
│  └── sub_agents (自己的子 Agent)                          │
└─────────────────────────────────────────────────────────┘
```

### **7\.2 上下文隔离模式 \(ADK 的设计\)**

```Python
# Google ADK 的子 Agent 上下文管理：

# 根 Agent (IntentAgent) 的上下文:
Content[] = [
    user: "查看当前有哪些策略"
    model: function_call(transfer_to_agent, {agent: "strategy_view_agent"})
]

# 当 transfer_to_agent 触发后:
# ADK Runner 创建子 Agent 的"新上下文":
Content[] = [
    user: "查看当前有哪些策略"    ← 原始 user 消息透传
    # ...子Agent自己的推理和工具调用...
    model: "当前有3个策略: A、B、C"  ← 子Agent独立生成
]

# transfer 完成后:
# 子Agent的最终答案返回给父Agent
# 父Agent的上下文追加:
Content[] = [
    ...
    model: function_call(transfer_to_agent, strategy_view_agent)
    user: function_response(result: "当前有3个策略: A、B、C")
]
```

### **7\.3 三种上下文模式的对比**

### **7\.4 本项目的具体实现**

```Python
# 1. State 共享 (跨 Agent 数据传递)
# multi_agent/api.py:
async for event in intent_runner.run_async(
    user_id=req.user_id,
    session_id=session_id,
    new_message=req.new_message,
    state_delta={
        "request_params": request_params,  # → 所有 Agent 可读取
        "metadata": req.metadata,          # → 子 Agent 按需使用
        HEADER_NAME_ORG_CODE_OUT: org_code
    },
):

# 2. 子 Agent 通过 CallbackContext 访问共享 State
# intent_agent/agent.py:
async def before_agent_callback(callback_context: CallbackContext):
    request_params = callback_context.state.get("request_params", {})
    label = request_params.get("metadata", {}).get("label", "")
    # 根据 label 决定是否跳过推理，直接走显式分支

# 3. Session 级别的记忆 (跨对话但不是跨Agent)
session_service = InRedisSessionService()  # Redis 存储
# 同一个 chatId 的对话记录在同一个 session 中
# 不同 Agent 的推理过程不共享 session

# 4. Memory 级别的全局记忆
memory_service = InRedisMemoryService()  # Redis 存储
# 长期记忆可以被所有 Agent 读取
```

### **7\.5 State 传递链路**

```Plain Text
用户请求
    │
    ▼
state_delta = {
    "request_params": {...},    ──→ IntentAgent.before_agent_callback 读取
    "metadata": {...},          ──→ IntentAgent.before_model_callback 读取
    HEADER_NAME_ORG_CODE_OUT    ──→ 所有 LLM 调用的 HTTP Header
}
    │
    ▼
IntentAgent 处理 → transfer_to_agent(strategy_agent)
    │
    ▼ (State 自动透传)
StrategyAgent 处理
    ├── callback_context.state["request_params"] 可读
    ├── callback_context.state["metadata"] 可读
    └── 自己的 tool call / reasoning 独立
    │
    ▼
StrategyAgent 完成 → 结果返回给 IntentAgent
    │
    ▼
IntentAgent 生成最终回答
```

### **7\.6 面试回答模板**

> 多 Agent 上下文采用**独立上下文 \+ State 共享**的混合模式：
> 
> 1. **对话历史隔离**: 每个子 Agent 的 `transfer_to_agent` 创建独立的推理上下文，工具调用和中间推理不污染父 Agent 的对话历史
> 
> 2. **State 字典共享**: 通过 ADK 的 `state_delta` 透传 `request_params`、`metadata` 等关键信息，所有 Agent 可通过 `callback_context.state` 读取
> 
> 3. **Session 统一**: 同一个 `chatId` 在 Redis 中共享 Session，不同 Agent 的最终回复统一存储
> 
> 4. **Memory 全局**: `longTermMemory` 通过 `InRedisMemoryService` 全局共享
> 
> 这样设计的优势：子 Agent 可以独立推理不受父 Agent 上下文长度限制，同时通过 State 和 Memory 共享必要的上下文信息。
> 
> 

---

# **RAG 专题面试 Q\&A**

---

## **Q20: RAG 项目的整体架构是什么？完整构建流程是什么？**

### **架构三层**

```Plain Text
┌─────────────────────────────────────────────────────────────────┐
│  agentflow-server (Java)   —— 业务编排层                         │
│  ├── 知识库 CRUD (KnowledgeController)                          │
│  ├── 数据集管理 (DatasetController)                               │
│  ├── 检索节点 (DatasetSearchNode) ★混合召回入口★                  │
│  └── Agent 工具调用网关 (/api/v1/tools/run)                      │
└──────────────────────────┬──────────────────────────────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
┌─────────────────┐ ┌───────────┐ ┌──────────────┐
│ af-rag-server   │ │ dataflow  │ │ ES + Milvus  │
│ (Python)         │ │ (Java)    │ │              │
│ ★文档解析        │ │ ★ETL流水线│ │ ★存储+检索   │
│ ★文本分块        │ │ ★调度执行 │ │              │
│ ★LLM摘要/关键词  │ │ ★重试容错 │ │              │
│ ★Agent推理       │ │           │ │              │
└─────────────────┘ └───────────┘ └──────────────┘
```

### **完整构建流程（12 步）**

```Plain Text
Step 1-2: 用户创建知识库 + 上传文件/URL
  → agentflow 写入 knowledge 表 + 文件上传 S3

Step 3: 创建 Context + Node 实例
  → agentflow 根据 KnowledgeFlow DAG 生成执行计划
  → knowledge_flow_context (status=INIT)
  → knowledge_flow_context_node × 6

Step 4-6: dataflow Job 轮询 → startHandle
  → @Scheduled(fixedDelay=30000) 每 30 秒轮询
  → startHandle: 验证文件存在性，设置 resultPath

Step 7: docParse 文档解析 ★af-rag-server 参与★
  → POST /rag_algorithm/parser
  → PyMuPDF 解析 PDF → 提取文本/表格/图片
  → 结果写入 S3 → 回调 dataflow

Step 8: textChunk 文本分块 ★af-rag-server 参与★
  → POST /rag_algorithm/splitter
  → chunk_size=512, chunk_overlap=50
  → 返回 [{chunkId, chunkText, chunkInfo}]

Step 9: summary + keyword ★af-rag-server 参与★
  → POST /rag_algorithm/summary → LLM 生成摘要
  → POST /rag_algorithm/keywords → jieba + LLM 提取关键词

Step 10: docStorage 向量化 + 入库
  → Embedding API: POST {modelUrl}/embeddings → 768 维向量
  → 双写: Milvus (向量索引) + ES (全文索引)
  → isLastNode=true → context(SUCCESS)

Step 11: 用户通过 Agent 检索
  → chatNode → datasetSearchNode → KnowledgeSearchService
  → ES BM25 + Milvus ANN → RRF 融合 → Rerank → 返回 Top K

Step 12: 异常处理
  → 节点失败 → 指数退避重试 (最多 3 次)
  → 全部失败 → 飞书通知用户
```

---

## **Q21: 有没有了解过 Agentic RAG 或主动式 RAG？**

### **本项目就是 Agentic RAG 的实践**

本项目通过 **Google ADK 多 Agent 系统** 实现了 Agentic RAG：

```Plain Text
传统 RAG: 检索 → 拼入 Prompt → LLM 一次回答
            (被动, 一条直线)

Agentic RAG: Agent 自主决策检索策略
            (主动, 多轮循环)
```

**本项目的 Agentic RAG 体现** \(`agent/agents/intent_agent/agent.py`\):

```Python
# ★ Agent 可以自主决定:
# 1. 什么时候检索 (PlanReAct 中的 ACTION 阶段)
# 2. 检索什么 (function_call 中的参数)
# 3. 检索结果不够怎么办 (REASONING 中决定是否再次检索)
# 4. 是否需要多知识库联合检索 (构建 knowledgeIds 列表)
```

**核心实现位置**:

**Re\-planning 指令** \(`custom_planner.py`\):

```Python
# 从 CustomPlanner._build_nl_planner_instruction() 生成的指令:
"""
如果最初的计划不能成功执行，你应该从之前的执行结果中学习并修改你的计划。
修订后的计划应在 /*REPLANNING*/ 下。然后使用工具来遵循新计划。
"""
```

**面试话术**:

> 我们的 Agentic RAG 不是"检索一次就回答"，而是让 Agent 在 PlanReAct 循环中自主决定：要不要检索、检索什么、检索结果不够时要不要换策略重试。同时通过动态知识库 ID（`variables["knowledgeIds"]`）让 Agent 根据上下文自动选择检索哪些知识库。如果计划失败，ADK 的 Replanning 机制支持 Agent 从错误中学习并重新规划。
> 
> 

---

## **Q22: 意图识别和 Rewrite 机制怎么实现的？**

### **意图识别——两层机制**

**第一层: 显式分支（代码逻辑）** \(`intent_agent/explicit_branch.py` 第 24\-41 行\):

```Python
async def before_agent_callback(callback_context):
    label = request_params.get("metadata", {}).get("label", "")

    # ★ 不需要 LLM 判断的明确场景 ★
    content = await check_strategy_publish(callback_context)   # 检查策略是否已发布
    if content: return content

    content = await check_strategy_use(callback_context)       # 检查策略使用情况
    if content: return content

    return None  # 非明确分支 → 交给 LLM
```

**第二层: Prompt 驱动路由** \(`intent_agent/prompt.py`\):

```Python
"""
## 分发规则

### 社交互动 → 直接回复
### 元数据操作 → `metadata_agent`
### 策略查看 → `strategy_view_agent`
精确匹配词：`查看`、`显示`、`列出`、`展示`、`浏览`
### 策略处理 → `strategy_agent`
精确匹配词：`提取`、`分析`、`优化`、`制定`、`执行`
### 用户数据分析 → `direct_analysis_agent`
条件：能提取到 chat_id 或 customer_id

## 决策优先级
社交互动 > 元数据框架 > 策略查看 > 用户分析 > 通用策略

## 严禁行为
- 禁止返回JSON格式数据
- 禁止基于上下文直接生成业务回答
- 所有业务问题必须通过 transfer_to_agent 分发
"""
```

**可选: 工具判断意图** \(`intent_agent/utils.py`\):

```Python
# ★ 配置开关: INTENT_ANALYSIS_WITH_TOOL_FLAG ★
if INTENT_ANALYSIS_WITH_TOOL_FLAG:
    ret = await judge_intent_by_tool(callback_context)
    # 调用一个专门的 intent 判断工具（如一个小模型），
    # 返回确定的子 Agent 名称
```

### **Rewrite 机制**

本项目的 Rewrite 不是单独模块，而是**嵌入在 Prompt 模板中**：

```Python
# executor.py get_prompt() — 拼接时自动注入上下文:

# 1. 长期记忆注入 → 帮 LLM 理解用户历史偏好
text += f"\n【长期记忆】{longTermMemory}"

# 2. 背景知识注入 → 帮 LLM 理解领域术语
text += f"\n【背景知识】{rag_results}"

# 3. PlanReAct 指令 → 帮 LLM 理解任务分解
# "制定计划→执行工具→推理→最终答案"

# ★ Rewrite 是隐式的: LLM 在 REASONING 阶段自动做指代消解和问题改写 ★
# 例如:
# 用户: "那个电池参数再查一下"  ← 指代不明确
# LLM REASONING: "用户之前查询了产品A，'那个'指产品A，需要用产品A的电池参数重新检索"
```

**面试话术**:

> 我们有两层意图识别：第一层是显式分支（代码判断明确业务场景，如检查策略发布状态），第二层是 Prompt 驱动的 LLM 路由（通过 `transfer_to_agent` 分发给 5 个子 Agent）。Rewrite 是隐式的——通过长期记忆和背景知识注入到 Prompt，让 LLM 在 ReAct 的 REASONING 阶段自动完成指代消解和问题改写。
> 
> 

---

## **Q23: 知识库的召回策略是什么？检索方式是稠密、稀疏还是混合？**

### **三种检索模式**

**源码**: `KnowledgeSearchServiceImpl.java` 第 220\-232 行

```Java
switch (KnowledgeSearchModeEnum.getEnum(dto.getSearchMode())) {
    case EMBEDDING:
        recallResultDTOList = vectorSearch(dto);      // 稠密检索
        break;
    case FULLTEXT_RECALL:
        recallResultDTOList = fullTextSearch(dto);    // 稀疏检索
        break;
    case MIXED_RECALL:
        recallResultDTOList = mixSearch(dto);         // ★ 混合检索 (默认) ★
        break;
}
```

### **稠密检索 \(EMBEDDING\)**

**实现**: `DenseVectorServiceImpl.java` 第 354 行

```Java
// ES knn 向量检索
String vectorFieldName = "vector_" + dto.getVectors().size();  // vector_768
KnnQuery knnQuery = KnnQuery.of(m -> m
    .field(vectorFieldName)
    .queryVector(dto.getVectors())        // query 的 768 维 Embedding
    .numCandidates(200)                   // 粗排候选数
    .k(150)                               // 最终返回数
    .filter(query));                      // 过滤条件
// → Cosine 相似度排序
```

### **稀疏检索 \(FULLTEXT\_RECALL\)**

**实现**: `DenseVectorServiceImpl.java` 第 409 行

```Java
// ES Bool Query → BM25 打分
SearchResponse<JSONObject> searchResponse = elasticsearchClient.search(s -> s
    .index(indexName)
    .query(b -> b.bool(boolBuilder.build()))  // term/terms/match 条件
    .size(limit), JSONObject.class);
// → BM25 排序
```

### **混合检索 \(MIXED\_RECALL\) —— 默认模式**

**实现**: `KnowledgeSearchServiceImpl.java` 第 427\-452 行

```Java
private List<RecallResultDTO> mixSearch(KnowledgeRagDTO dto) {
    // 1) 并行执行两种检索
    List<RecallResultDTO> vectorSearchResult = vectorSearch(dto);      // ES knn
    List<RecallResultDTO> fullTextSearchResult = fullTextSearch(dto);  // ES BM25

    // 2) RRF 融合
    if (rrfSwitch) {
        result = rrfRank(mixSearchDTO, vectorSearchResult, fullTextSearchResult);
        // vectorSearchRatio * 10 = 6 (权重  60%)
        // fullTextSearchRatio * 10 = 4 (权重 40%)
    } else {
        result = merge(vectorSearchResult, fullTextSearchResult);  // 简单去重合并
    }

    // 3) ★ Rerank 重排序 ★
    if (reRankerSwitch) {
        result = reRanker(query, recallTypes, reRankerServiceUniCode, similarity, result);
        // PEG Cross-Encoder 交叉编码 → 更精准的排序
        // 过滤 similarity < 0.7 的低分结果
    }

    return result;
}
```

### **分词引擎**

- **英文/通用**: ES 内置分词器 \(standard\)

- **中文**: jieba 分词 \(`jieba-analysis` 依赖 \+ af\-rag\-server Python jieba\)

- **全文检索模式**: match/multi\_match 查询，ES 自动分词

---

## **Q24: 为什么要单独做 Rerank？用了什么向量模型？**

### **为什么需要 Rerank？**

```Plain Text
Embedding 模型 (双塔)         Rerank 模型 (交叉编码)
     │                              │
query → [Encoder] → vec_q     query + doc → [Encoder] → score
doc   → [Encoder] → vec_d     同时输入 query 和 doc
     │                              │
cosine(vec_q, vec_d)           直接输出相关性分数
     │                              │
速度快、可预计算               精度高、但需实时计算
适合海量召回                   适合 Top-K 精排
```

**面试话术**: Embedding 是双塔架构，向量可预计算存 Milvus，适合从海量文档中快速召回；但向量丢掉了 query\-doc 之间的细粒度交互。Rerank（PEG Ranker）是交叉编码，query 和 doc 一起送入模型，能捕捉精准的语义匹配关系。所以先 Embedding 从百万级召回 TopK，再用 Rerank 精排排序。

### **向量模型**

项目通过 `ModelService.queryModelList()` 动态获取可用模型，支持配置切换：

```Java
// DenseVectorServiceImpl.toVectorSearchParam() — 动态选择向量字段
String fieldName = DocVectorFieldEnum.VECTOR_PREFIX.getFieldName() + vectorSize;
// vector_768 (text-embedding-3-large)
// vector_1536 (text-embedding-ada-002)
// vector_1024 (GTE-large)
```

**调研过的模型矩阵**:

**Rerank 模型**: PEG Ranker \(通过 `modelService.pegRanker()` 调用\)

```Java
// KnowledgeSearchServiceImpl.java 第 455-494 行
List<PegRankerPrefabricationOutPutItemVO> ranker = modelService.pegRanker(modelParamDTO);
// input: [chunk1, chunk2, chunk3, ...]  ← 待排序文本
// content: "电池参数是多少"              ← 用户查询
// → [{index: 0, ranker: 0.92}, {index: 1, ranker: 0.87}, ...]
```

---

## **Q25: 文档切片策略是什么？召回不到数据怎么处理？**

### **切片策略**

**源码**: `af-rag-server/src/component/chunk_split/`

项目实现了多种切片器，可通过参数选择：

```Python
# 1. GeneralSplitter (默认)
# general_chunker.py 第 11 行
class GeneralSplitter:
    def __init__(self, chunk_size=1024, chunk_overlap=200, separator=None):
        # ★ 默认: chunk_size=1024, chunk_overlap=200 ★
        # OCR 文件按页切片 (保留页码元数据)
        # 普通文本按 chunk_size 切分 + overlap 维持语义连贯

    def split(self, chunk_nodes):
        for node in chunk_nodes:
            if is_ocr_result:
                # OCR: 按页切分 → TokenTextSplitter 再切
                for page_num, page in enumerate(res_json):
                    text_splitter = TokenTextSplitter(
                        chunk_size=self.chunk_size,
                        chunk_overlap=self.chunk_overlap
                    )
                    splits = text_splitter.split_text(page_text)
                    new_nodes.extend(splits)
            else:
                # 普通文本: TokenTextSplitter 直接切
                text_splitter = TokenTextSplitter(...)
                splits = text_splitter.split_text(text)

# 2. SeparatorSplitter (分隔符切片)
# separator_chunker.py 第 21 行
class SeparatorSplitter:
    # 按指定分隔符切分 (如 "\n\n", "。", " ")

# 3. SeparatorRecursiveSplitter (递归分隔符切片)
# separator_recursive_chunker.py 第 12 行
class SeparatorRecursiveSplitter:
    # 按优先级递归尝试分隔符: "\n\n" → "\n" → "。" → " "
    # chunk_size=1024, chunk_overlap=200

# 4. SpearatorTokenTextSplitter (Token 级别)
# spearator_token_text_splitter.py
    # 精确按 token 数切分，避免字节/字符偏差
```

**面试关键参数**:

### **召回不到数据的处理 — 三级兜底**

**第一级: 自定义回答兜底** \(`tool.py` 第 244\-245 行\):

```Python
# KnowledgeTool.run_async()
if len(knowledge_data) == 0 and self.backup_strategy and self.backup_strategy.get("backupMode") == 2:
    return success(self.backup_strategy.get("customAnswer"))
    # ★ 知识库无结果 → 返回用户预设的兜底回答 ★
```

**第二级: 空结果标记** \(`DatasetSearchServiceImpl.java` 第 106\-107 行\):

```Java
map.put("isEmpty", searchRes.size() == 0 ? true : null);   // ★ 供下游 tfSwitch 判断 ★
map.put("unEmpty", searchRes.size() > 0 ? true : null);
// → 下游节点可以:
//   tfSwitch: isEmpty=true → 走"无结果"分支 (直接回答/人工转接/默认回复)
//   tfSwitch: unEmpty=true → 走"有结果"分支 (chatNode + quoteQA)
```

**第三级: LLM 自主调整** \(ReAct 循环\):

```Plain Text
LLM 看到 KnowledgeTool 返回空列表
  → REASONING: "检索未找到结果，换用不同关键词重试"
  → ACTION: function_call(knowledge_search, {question: "产品A 规格参数"})
  → 或 → REASONING: "两次检索都无结果，告知用户未找到"
  → FINAL_ANSWER: "抱歉，未找到关于电池参数的信息，请提供更具体的产品型号"
```

**DataFlow 级别兜底** \(节点执行失败\):

```Java
// ModuleService.executeWithTimeout() — 重试耗尽后返回默认值
if (!status && param.get("moduleDefaultOutput") != null) {
    result.putAll((Map<String, Object>) param.get("moduleDefaultOutput"));
}
```

---

## **Q26: RAG 文档隔离怎么做？关联文档和术语文档怎么处理？**

### **文档隔离——三层权限**

**第一层: 知识库级权限** \(`KnowledgeSearchServiceImpl.java` 第 820 行\):

```Java
// filterPermissionKnowledge()
// 检查用户对知识库的访问权限
// 无权限的知识库 → 从 knowledgeIds 中移除
```

**第二层: 数据集启禁用** \(`KnowledgeSearchServiceImpl.java` 第 216 行\):

```Java
// filterDatasetIdsByEnabled()
// 禁用的数据集 → 不参与检索
```

**第三层: ES 查询级别隔离** \(`DenseVectorServiceImpl.java` 第 186\-191 行\):

```Java
// 多租户隔离: tenantId + teamId 双重过滤
param.setFilterItems(List.of(
    new SearchItem("tenantId", EQ, dto.getTenantId()),        // 租户隔离
    new SearchItem("teamId", EQ, dto.getTeamId()),             // 团队隔离
    new SearchItem("knowledgeId", IN, dto.getKnowledgeIds()),  // 知识库隔离
    new SearchItem("datasetId", IN, dto.getDatasetIds()),      // 数据集隔离
    new SearchItem("type", IN, dto.getTypes()),                // 类型隔离
    new SearchItem("isEnabled", NEQ, 1)                        // 排除禁用
));
```

### **关联关系文档处理**

**知识库级别关联**:

```Java
// DatasetSearchServiceImpl.filterKnowledgeIds() 第 207-251 行
// ★ 多知识库检索一致性校验 ★

// 1) 只允许 3 种可检索类型
List<Knowledge> validKnowledge = knowledgeList.stream()
    .filter(k -> ALLOW_TYPES.contains(k.getType()))
    // ALLOW_TYPES = {DATESET, FAQ, GRAPH}

// 2) ★ 类型一致性: 必须同类型 ★
// 文档知识库不能和 FAQ 知识库混合检索
Knowledge first = validKnowledge.stream().findFirst();
resultIds = validKnowledge.stream()
    .filter(k -> sameType(first, k))

// 3) ★ 向量模型一致性: 必须同模型 ★
    .filter(k -> sameVectorModel(first, k))
    .collect(toList());
```

**关联文档的 chunk 级别**:

```Python
# general_chunker.py — association_info 参数
class GeneralSplitter:
    def __init__(self, chunk_size, chunk_overlap, association_info=None):
        self.association_info = association_info or []
        # ★ 切片时可以是注入文档关联关系 ★
        # 例如: "本文档关联《产品安全规范.pdf》和《行业标准术语表.doc》"
```

### **术语文档处理**

项目通过 **术语库 \(Term Entry\)** 机制处理：

```Java
// DenseVectorService.java 第 326-348 行
// 术语库独立索引
Boolean createTermIndex();

// ★ 查询术语并替换 ★
String queryTermAndReplace(String input, List<String> tokens, List<String> termIds, TermTypeEnum type);
// 用户输入: "查一下动力电池参数"
// 术语库匹配: "动力电池" → "锂离子动力电池组"
// 替换后检索: "查一下锂离子动力电池组参数"

// 批量插入术语
void batchInsertOrUpdateTermEntryData(List<TermEntrySaveDTO> dataDTOList, Boolean isCreate);
```

**面试话术**: 文档隔离通过三层权限（知识库→数据集→ES字段）\+ 多租户过滤实现。术语处理有独立的 Term Index，检索前先做术语匹配替换，将行业黑话转为标准化词汇。关联文档通过 chunk 的 `association_info` 元数据和知识库类型一致性校验来管理。

---

## **Q27: Code\-RAG 怎么设计？**

### **本项目中的 Code\-RAG：Python 代码执行**

本项目的 Code\-RAG 实现为**代码执行沙箱** \(`af-rag-server/src/rag_algorithm/handlers.py` — CodeRunner\):

```Plain Text
POST /rag_algorithm/code_runner
{
  "code": "import pandas as pd\ndf = pd.read_csv('data.csv')\nprint(df.head())",
  "parameters": {"data_path": "s3://bucket/data.csv"},
  "timeout": 60
}
→ Python 沙箱执行 → 返回 stdout + 文件输出
```

**在 Agent 中的使用** \(`agent/auto_agent/framework_adapter/adk/custom_planner.py`\):

```Python
# PlanReAct 中的代码工具调用:
"""
/*ACTION*/
function_call(run_python_code, {
    "code": "import jieba\nwords = jieba.cut(text)\nprint(' '.join(words))"
})

/*REASONING*/
代码执行完成，jieba 分词结果为 [...]
"""
```

### **Code\-RAG vs Code\-Generation 的区别**

### **如果要扩展 Code\-RAG**

```Plain Text
1. 代码索引
   ES 索引: code_snippets (code, language, function_name, docstring)
   Embedding: code-bert / unixcoder 生成代码向量

2. 检索策略
   - 自然语言 → 代码: "如何连接数据库" → db_connect.py
   - 代码 → 代码: func_call_pattern() → 相似的 API 调用
   - 注释/Docstring 检索: docstring 也索引

3. 执行与反馈
   - 检索到代码 → 沙箱执行 → 验证输出
   - 如果执行失败 → Agent 重新检索或修改代码
   - 成功执行的结果作为工具返回值注入 LLM
```

**面试话术**: 我们当前的 Python 代码执行插件（PluginPythonServiceImpl → /rag\_algorithm/code\_runner）已经实现了代码沙箱执行，Agent 可以通过 PlanReAct 自主决定何时调用代码工具。如果需要对已有代码库做检索（如企业的 SQL 模板库、Python 工具函数库），可以基于现有的 ES\+Milvus 双引擎，用 CodeBERT 做代码向量化，索引代码片段到独立知识库。

---

# **Ragent vs AgentFlow 对比分析**

## **一、总体定位差异**

**面试话术**:

> Ragent 是"RAG 专家"——专注于检索增强生成的每一个环节做到极致。AgentFlow 是"Agent 平台"——RAG 只是其中一个节点类型，真正的核心是多 Agent 编排和工作流引擎。
> 
> 

---

## **二、RAG 管线对比（核心差异）**

### **2\.1 完整管线步骤对比**

```Plain Text
Ragent 管线 (7 步):                        AgentFlow 管线:
                                           
① loadMemory()        并行加载记忆          chatNode 加载 ChatItem 历史
② rewriteQuery()      ★术语归一化+改写拆分   IntentAgent ★意图路由(Prompt驱动)
③ resolveIntents()    ★LLM意图分类(树)       before_agent_callback ★显式分支
④ handleGuidance()    歧义引导(短路点1)       ─── 没有独立的引导步骤
⑤ handleSystemOnly()  闲聊短路(短路点2)       ─── Prompt 指令中直接回复
⑥ retrieve()          多通道检索+后处理链      datasetSearchNode ★3种检索模式
⑦ streamRagResponse() Prompt组装+SSE流式      chatNode ★LLM调用+SSE推送
```

### **2\.2 关键差异深度分析**

#### **差异一: 意图识别的位置和方式**

**Ragent**: 意图识别是 RAG 管线的独立步骤（第③步），用 **LLM 一次性对所有叶子节点打分**：

```Plain Text
// DefaultIntentClassifier — 每个子问题独立并行调 LLM
List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream()
    .map(q -> CompletableFuture.supplyAsync(
        () -> new SubQuestionIntent(q, classifyIntents(q)), executor))
    .toList();

意图树节点:
  biz-oa-intro (KB, collection_name="oa_kb", TopK=5, promptSnippet="...")
  biz-oa-flow (KB, collection_name="oa_kb", TopK=10)
  mcp-weather (MCP, mcpToolId="weather_query")
  sys-chat    (SYSTEM, 直接闲聊)
```

**AgentFlow**: 意图识别是 **Agent 层**的行为，分两层：

1. `before_agent_callback` 代码判断明确分支（策略发布检查等）

2. IntentAgent 的 Prompt 驱动路由到 5 个子 Agent

```Python
# IntentAgent 的 Prompt 路由:
"""
### 策略查看 → `strategy_view_agent`
精确匹配词：`查看`、`显示`、`列出`

### 通用策略处理 → `strategy_agent`
触发条件：所有其他业务相关任务

决策优先级：社交互动 > 元数据框架 > 策略查看 > 用户分析 > 通用策略
"""
```

**区别**: Ragent 的意图分类是**细粒度知识库路由**（"查OA的审批流程" vs "查保险的审批流程"），AgentFlow 的意图路由是**粗粒度业务场景路由**（"查看策略" vs "分析策略" vs "跑数据"）。原因是 Ragent 专做 FAQ 知识问答，AgentFlow 做的是多业务场景的 Agent 平台。

#### **差异二: Query Rewrite — 有 vs 隐式**

**Ragent**: 有独立的 `rewriteQuery()` 步骤，两级处理：

1. **规则级术语归一化**: `QueryTermMappingService` — DB 映射表 \+ Redis 缓存，"平安保司"→"平安保险公司"

2. **LLM 改写拆分**: `MultiQuestionRewriteService` — 提示词严格控制"保留专有名词、删除礼貌用语、多问号拆分、抽象对比不拆"

**AgentFlow**: 没有独立的 Rewrite 步骤。Rewrite 是**隐式的**：

- Prompt 中注入长期记忆和背景知识 → LLM 在 REASONING 阶段自动做指代消解

- 多问题拆分依赖 PlanReAct 中的 REASONING 步骤："用户问了两个问题，分别处理"

**面试话术**:

> Ragent 的 Query Rewrite 独立成步是因为它专做 FAQ 场景——口语化问题（"报销咋整"）和术语缩写（"平安保司"）必须标准化后才能检索。AgentFlow 不需要独立 Rewrite 是因为它的 Agent 有 PlanReAct 循环——LLM 可以在 REASONING 阶段隐式完成改写，而且多 Agent 路由（IntentAgent）已经做了问题场景的粗粒度分类。
> 
> 

#### **差异三: Agent loop vs 无 Agent loop — 最大差异**

**Ragent 为什么不搞 Agent loop？**（ragent\.md 第 494 行）:

> 1. 企业场景的工具调用相对简单：查天气、查订单、查库存 → 一次调用足够
> 
> 2. 避免幻觉循环：LLM 在 loop 中可能反复调用工具、不断调整参数，消耗大量 token
> 
> 3. 延迟可控：用户等工具返回 \+ LLM 回复的总延迟 = 工具延迟 \+ LLM 延迟，没有 loop 的叠加
> 
> 

**AgentFlow 为什么需要 Agent loop？**:

> AgentFlow 的核心场景是"策略分析"——需要拉数据→分析→生成策略→验证→修正，这是天然的多步推理场景，一次工具调用不可能完成。
> 
> 

#### **差异四: 检索通道数量**

**Ragent**: 4 通道并行

```Plain Text
① 意图定向通道 (IntentDirected)     → 精确路由到目标知识库
② 关键词通道 (Keyword BM25)         → 精确匹配订单号/错误码
③ 向量全局通道 (Vector Global)      → 语义理解
④ 联网搜索通道 (YouCom Web Search)  → 公网信息
```

**AgentFlow**: 2 引擎 \+ 3 模式

```Plain Text
EMBEDDING 模式:    ES knn 向量检索
FULLTEXT 模式:     ES BM25 全文检索
MIXED_RECALL 模式: 上述两者并行 → RRF 融合 → Rerank
```

**区别**: Ragent 多了"意图定向通道"和"联网搜索通道"。意图定向是 Ragent 独有的（因为它的意图树直接映射到 collection\_name），AgentFlow 没有这层是因为知识库路由在 WorkFlow 节点配置时已经确定了。

#### **差异五: 后处理器链**

**Ragent**: 4 个后处理器（责任链模式）

```Plain Text
Dedup(去重合并) → RRF融合 → Rerank精排(含截断) → MetadataEnrichment(富化)
```

**AgentFlow**: 等价逻辑在 `KnowledgeSearchServiceImpl.mixSearch()` 中：

```Java
// 第 427-452 行
merge/rrfRank → reRanker → filterTokens → filterDisEnabledDatasets
// MetadataEnrichment 在 DatasetSearchServiceImpl 中
// → buildKnowledgeRagDTO 已经带了 knowledgeId/datasetId
```

**关键区别**: Ragent 的 `MetadataEnrichment` 是一个独立的后处理器——回表补齐 docId/chunkIndex/docName。AgentFlow 没有这个独立步骤，因为这些元数据在 ES 索引写入时已经作为字段存储了，检索时直接返回。

---

## **三、文档处理对比**

### **3\.1 切分策略**

**面试话术**: Ragent 的切分是"结构感知型"——先解析 Markdown/PDF 为强类型 Block，再按 Block 边界切分，表格用 key\-value 格式保留列名\-值的关系。AgentFlow 的切分是"参数可配置型"——通过 LlamaIndex 的 TokenTextSplitter，chunk\_size/chunk\_overlap/separator 都可调，更灵活但不如 Ragent 的表格 key\-value 方案精准。

### **3\.2 父子文档**

**Ragent**: 一套 chunk \+ 三套元数据，实现"等价父文档"效果：

```Plain Text
不存两套 chunk（父+子），只存一套 chunk + 元数据
检索命中 chunk_1 → MetadataEnrichment 回表 → 按 docId 分组 → 按 chunkIndex 排序
→ 还原原文顺序 → 效果等同于取父 chunk 全文
```

**AgentFlow**: 没有显式的父子文档机制。但 `ChatContextFilter` 的上下文截断和 `quoteQA` 的组合有类似效果——检索到的 chunk 拼入 Prompt 时带着 sourceText（含上下文）。

---

## **四、记忆机制对比**

**关键差异**: Ragent 的长期记忆是"结构化摘要"（只记话题不记答案），AgentFlow 的长期记忆是"向量化自由文本"。前者更适合 FAQ 场景（避免旧答案冲突），后者更适合个性化场景（记住用户偏好）。

---

## **五、工程能力对比**

---

## **六、各自优势总结**

### **AgentFlow 比 Ragent 强的地方**

### **Ragent 比 AgentFlow 强的地方**

---

## **七、面试话术模板**

**"你做的 RAG 和开源的 Ragent 有什么异同？"**

> 两个项目代表了 RAG 的两种实现思路。Ragent 是"RAG 专家"，把检索增强生成的每个环节——Query Rewrite、意图分类、多路检索、后处理链——做到极致。切分是结构感知的 Block 体系，表格用 key\-value 渲染，碎片有 ChunkPacker 合并。
> 
> 我们的 AgentFlow 是"Agent 平台"，RAG 只是其中一个节点。核心差异在 Agent 机制：Ragent 没有 Agent loop，工具调用是同步一步式，适合企业 FAQ 场景；AgentFlow 有完整的 PlanReAct 循环，LLM 自主决定"检索→分析→不满意→换策略重试"，适合复杂多步推理场景。
> 
> 另外架构上 Ragent 是 Java 单体四层，AgentFlow 是 Java\+Python 三服务协作。前者统一技术栈，后者各取所长——Java 做高并发编排，Python 做 AI 能力。Ragent 的可观测性不如我们（我们有 Pinpoint\+Langfuse 全链路追踪），但它的 Query Rewrite 和意图树机制比我们更精细。
> 
> 

---

# **意图识别专题**

---

## **Q28: 意图识别到底是什么？**

### **一句话**

> 用户发来一句话 → 系统判断"该让哪个子 Agent 来处理" → 把任务分派过去。中间的判断逻辑，就是意图识别。
> 
> 

### **AgentFlow 的两层意图识别**

用户消息到达后，IntentAgent 是第一道门。它不回答问题，只做一件事——**转发给正确的子 Agent**。

```Plain Text
用户: "查看当前有哪些策略"
           │
           ▼
    ┌─────────────────────────────────┐
    │  IntentAgent (意图判定智能体)     │
    │                                 │
    │  先走第①层: 显式分支 (代码逻辑)    │
    │  → 不匹配                       │
    │                                 │
    │  再走第②层: LLM 路由             │
    │  → "查看" 关键词命中              │
    │  → transfer_to_agent(strategy_view_agent)
    └─────────────┬───────────────────┘
                  │
                  ▼
         StrategyViewAgent
         "当前有3个策略: A、B、C"
```

### **第①层：显式分支——代码逻辑判断（不调 LLM）**

**文件**: `agent/agents/intent_agent/explicit_branch.py` 第 24\-41 行

```Python
async def before_agent_callback(callback_context):
    """★ 在 LLM 调用之前执行 ★"""
    request_params = callback_context.state.get("request_params", {})
    label = request_params.get("metadata", {}).get("label", "")

    # 检查: 用户是不是在看策略是否发布？
    content = await check_strategy_publish(callback_context)
    if content:
        return content  # ★ 直接返回结果，跳过 LLM ★

    # 检查: 用户是不是在使用策略？
    content = await check_strategy_use(callback_context)
    if content:
        return content  # ★ 直接返回结果，跳过 LLM ★

    return None  # → 都不是，让第②层 LLM 判断
```

**这是在做什么？** 有些意图是**100% 确定的**，不需要 LLM 来判断。比如"检查策略是否已发布"是一个明确的业务操作，代码直接判断即可。省一次 LLM 调用，也不会有幻觉风险。

### **第②层：LLM 路由——Prompt 驱动（调 LLM 判断）**

**文件**: `agent/agents/intent_agent/prompt.py` 第 4\-86 行

如果显式分支没有命中，LLM 会收到这样一段 **System Prompt**：

```Python
"""
# 数据洞察系统任务分发器

你是任务分发器，负责识别用户意图并将任务分派给对应的专业代理。

**重要：你只有两种行为模式**
1. 对于社交互动（问候、感谢等）：直接简洁回复
2. 对于业务问题：必须使用 transfer_to_agent 调用子代理

**严禁行为：**
- 禁止返回JSON格式数据
- 禁止基于上下文直接生成业务回答     ← ★ 关键：不允许LLM自己回答 ★

## 分发规则:

### 策略查看 → `strategy_view_agent`
精确匹配词：`查看`、`显示`、`列出`、`展示`、`浏览`
示例："查看当前有哪些策略"、"显示所有策略"

### 通用策略处理 → `strategy_agent`
触发条件：所有其他业务相关任务
匹配词：`提取`、`分析`、`优化`、`制定`、`执行`

### 元数据框架处理 → `metadata_agent`
关键词：`元数据`、`字段`、`框架`、`schema`

### 用户数据分析 → `direct_analysis_agent`
条件：能提取到 chat_id 或 customer_id

## 决策优先级:
社交互动 > 元数据框架 > 策略查看 > 用户分析 > 通用策略
"""
```

**LLM 收到用户问题 \+ 这段 Prompt 后，输出只有一个动作**：

```Plain Text
transfer_to_agent("strategy_view_agent")
```

LLM **不能**自己回答业务问题。"所有业务问题必须通过 transfer\_to\_agent 分发，无例外"——这条规则写死在 Prompt 里。

### **第③层（可选）：工具判断意图**

```Python
# intent_agent/agent.py 第 72-76 行
if INTENT_ANALYSIS_WITH_TOOL_FLAG:
    ret = await judge_intent_by_tool(callback_context=callback_context)
    return ret
```

这是一个配置开关。开启后，不调 LLM 做意图判断，而是调一个专门的轻量工具（比如一个小模型），返回确定的子 Agent 名称。更快更便宜，但灵活度不如 LLM。

### **对比 Ragent 的意图识别**

### **面试怎么讲**

> **一句话**: 意图识别就是"用户说了什么 → 该谁来处理"。我们分两层：第一层是代码逻辑（明确的业务场景直接命中，不走 LLM），第二层是 Prompt 驱动的 LLM 路由（模糊场景让 LLM 按 Prompt 指令分发到 5 个子 Agent）。LLM 被严格限制——它只能做分发决策，不能自己回答业务问题。
> 
> **和业界对比**: Ragent 的意图识别是细粒度的"知识库路由"（查 OA 的审批流程 vs 查保险的审批流程），我们的意图识别是粗粒度的"场景路由"（查看策略 vs 分析策略 vs 跑数据）。粒度不同是因为场景不同——他们是 FAQ 知识问答，我们是多业务 Agent 平台。
> 
> 

---

## **Q29: 意图识别和 Rewrite 的关系**

### **一句话**

> **Rewrite 在前，意图识别在后。Rewrite 把用户的话"翻译清楚"，意图识别才能在清楚的话上做准确判断。**
> 
> 

```Plain Text
用户原始输入: "那个批一下"
        │
        ▼
   ┌──────────────┐
   │  Rewrite      │  ← "那个" → "订单 ORD-2024-001",
   │  指代消解      │     "批一下" → "审批"
   │  术语归一化    │
   │  多问题拆分    │
   └──────┬───────┘
          │
          ▼  "审批订单 ORD-2024-001"
   ┌──────────────┐
   │  意图识别      │  ← 在清晰的话上判断：
   │              │     "审批" → 业务操作 → 不是闲聊
   │              │     → 分发给 workflow_agent
   └──────────────┘
```

### **为什么顺序不能反过来？**

如果先做意图识别，再做 Rewrite：

```Plain Text
用户: "那个批一下"
        │
        ▼
   意图识别 → "那个"、"批一下" → 无法匹配任何规则 → 分错/漏掉
        │
        ▼
   Rewrite → 已经晚了，意图已经判错了
```

**Rewrite 是"让用户想说的东西变得清晰"，意图识别是"在清晰的东西上做决策"。先清再判，这个顺序不能反。**

### **Ragent 中有，AgentFlow 中没有**

Ragent 的管线明确分了 7 步：

```Plain Text
① loadMemory → ② rewriteQuery → ③ resolveIntents → ④⑤ 短路 → ⑥ retrieve → ⑦ 回答
```

AgentFlow 的管线上没有独立的 Rewrite 步骤，但不是在别的地方做了：

AgentFlow 的做法：

```Plain Text
用户: "那个电池参数再查一下"
    ↓
IntentAgent: "那个"是业务问题 → 分给 strategy_agent
    ↓
strategy_agent PlanReAct:
  /*REASONING*/
  "用户之前讨论的是产品A，"那个"指产品A，"电池参数"是检索关键词。
   先查知识库获取产品A的电池参数。"
  /*ACTION*/
  function_call(knowledge_search, {question: "产品A 电池参数 容量 电压"})
```

### **两种方式的优劣**

### **面试怎么讲**

> **Rewrite 是意图识别的前置步骤**——先把用户口语化、带指代、模糊的问题"翻译"清楚，意图识别才能在清楚的话上做出准确判断。如果用户在意图树里有 20\+ 个意图节点（像 Ragent），不先 Rewrite 直接分类几乎不可能准确。
> 
> AgentFlow 没有独立 Rewrite 步骤，因为它的意图识别是粗粒度的（5 个子 Agent），不需要 Rewrite 也能分对。细粒度的改写（指代消解、关键词提取）嵌入在了 PlanReAct 的 REASONING 阶段，LLM 在推理时自动完成。
> 
> **如果你做的是一个 FAQ 密集场景的系统，独立 Rewrite 是必选项。如果你做的是 Agent 平台，Rewrite 可以嵌入到思考链中，省一步调用。**
> 
> 

---

# **DAG 引擎专题**

---

## **Q30: DAG 引擎详细介绍**

### **一句话**

> 用户在前端画布上拖了 10 个节点、连了线，点"运行"——DAG 引擎负责按拓扑顺序执行这些节点，把数据在节点间正确传递，最终输出结果。
> 
> 

### **它解决什么问题**

```Plain Text
┌───────────┐
                    │ questionInput │   ← 用户问题从这里进入
                    └─────┬─────┘
                          │
                    ┌─────▼─────┐
                    │ datasetSearch │ ← 查知识库
                    └─────┬─────┘
                          │
              ┌───────────┼───────────┐
              │           │           │
        ┌─────▼─────┐ ┌──▼───┐ ┌─────▼─────┐
        │ textEditor │ │ tfSwitch│ │ httpRequest│  ← 可能并行
        └─────┬─────┘ └──┬───┘ └─────┬─────┘
              │           │           │
              └───────────┼───────────┘
                          │
                    ┌─────▼─────┐
                    │  chatNode  │   ← LLM 回答
                    └───────────┘
```

如果每个节点都是硬编码的 if\-else，加一个节点就要改代码。DAG 引擎让这变成**配置驱动**——前端 JSON → 引擎自动解析 → 拓扑排序 → 逐节点执行。

### **三个核心组件**

```Plain Text
┌──────────────────────────────────────────────────────────┐
│  WorkFlowEngine.java (685 行)                             │
│  职责: DAG 拓扑遍历 + 执行控制                             │
│                                                          │
│  dispatchModules()                                        │
│    ├── loadModules()          → JSON → RunningModuleItem  │
│    ├── initRunningModuleType()→ 自动找到入口节点            │
│    ├── checkModulesCanRunSync()→ 同步递归执行              │
│    │     或                                                │
│    └── checkModulesCanRun()   → 线程池并发执行             │
└──────────────────────────────────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
┌──────────────────┐ ┌──────────────┐ ┌──────────────────┐
│ ModuleFactory     │ │ FlowContext  │ │ ModuleService    │
│ (35 行)           │ │ (139 行)     │ │ (接口, 76 行)    │
│                  │ │              │ │                  │
│ 启动时扫描所有     │ │ 运行时上下文  │ │ 每个节点实现      │
│ @NodeType 注解    │ │ 全局变量池    │ │ execute() 方法   │
│ 自动注册到 Map    │ │ 线程安全      │ │ executeWithTimeout│
│                  │ │              │ │ (超时+重试模板)   │
└──────────────────┘ └──────────────┘ └──────────────────┘
```

### **节点工厂（面试最爱问）**

```Java
// ModuleFactory.java —— 启动时自动注册
@Component
public class ModuleFactory implements ApplicationListener<ApplicationReadyEvent> {

    private Map<FlowNodeTypeEnum, ModuleService> typeToModuleServiceMap = new HashMap<>();

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // ★ Spring 启动完成后，扫描所有 ModuleService Bean ★
        Map<String, ModuleService> beans = context.getBeansOfType(ModuleService.class);
        for (ModuleService bean : beans.values()) {
            NodeType annotation = bean.getClass().getAnnotation(NodeType.class);
            if (annotation != null) {
                typeToModuleServiceMap.put(annotation.value(), bean);
            }
        }
    }

    // ★ 运行时 O(1) 查表获取 ★
    public ModuleService getService(FlowNodeTypeEnum type) {
        return typeToModuleServiceMap.get(type);
    }
}
```

### **同步递归模式（默认）**

```Java
// WorkFlowEngine.java 第 141 行
private void checkModulesCanRunSync(
    List<RunningModuleItemType> nodes,
    FlowContext ctx,
    ChatDispatchParam param
) {
    for (RunningModuleItemType node : nodes) {
        // 1) ★ 检查前置依赖: 所有 input 是否都有值 ★
        if (!moduleCanRun(node)) continue;

        // 2) ★ 执行单节点 ★
        Map<String, Object> result = moduleRun(node, param, ctx);

        // 3) ★ 找下游节点 ★
        List<RunningModuleItemType> downstream = moduleOutput(node, result, ctx);

        // 4) ★ 递归: 对下游节点再做相同的事 ★
        if (downstream != null && !downstream.isEmpty()) {
            checkModulesCanRunSync(downstream, ctx, param);
        }
    }
}
```

### **并发模式（CountDownLatch）**

```Java
// WorkFlowEngine.java 第 196 行
private void checkModulesCanRun(List<RunningModuleItemType> nodes, ...) {
    CountDownLatch latch = new CountDownLatch(nodes.size());

    for (node : nodes) {
        ctx.getCompletionService().submit(() -> {
            try { return moduleRun(node); }
            finally { latch.countDown(); }  // ★ 异常也计数 ★
        });
    }

    latch.await();  // ★ 等待同级所有节点完成 ★

    // ★ 按完成顺序取结果 → 找下游 → 递归 ★
    for (int i = 0; i < completedCount; i++) {
        Map<String, Object> map = ctx.getCompletionService().take().get();
        List<RunningModuleItemType> downstream = moduleOutput(node, result, ctx);
        checkModulesCanRun(downstream, ...);  // 递归下一级
    }
}
```

### **单节点超时 \+ 重试**

```Java
// ModuleService.java 第 25 行 —— 接口 default 方法
default Map<String, Object> executeWithTimeout(DispatchData data) {
    int maxRuntime = (int) param.get("maxRuntime");
    int retryTime  = Math.min((int) param.get("retryTime"), 3);

    for (int i = 0; i < retryTime; i++) {
        Future<Map<String, Object>> future = executor.submit(() -> execute(data));
        try {
            result = future.get(maxRuntime, TimeUnit.SECONDS);
            break;
        } catch (TimeoutException e) {
            future.cancel(false);  // 超时取消，重试
        }
    }
    // ★ 全部失败 → 兜底默认值，不中断整体流程 ★
    if (!success && param.get("moduleDefaultOutput") != null) {
        result.putAll(param.get("moduleDefaultOutput"));
    }
    return result;
}
```

### **节点间数据流转**

```Plain Text
节点A 执行完 → moduleOutput()
                    │
                    ├── edges:   "下游是节点B和C"  → 把B、C加入下一批执行
                    ├── targets: "把 a_result 赋值给 B.input.userChatInput"
                    └── globalKey:"把 a_result 存入全局变量池"
                                       │
                                       ▼
                          后续任意节点可通过 globalKey 读取
```

---

## **Q31: AgentFlow DAG 引擎 vs Dify**

### **核心对比**

### **关键差异**

#### **差异一：执行模型——内存 vs 分布式**

**AgentFlow**: 一个 Workflow 实例在一个 JVM 进程中跑完。数据通过 FlowContext 对象直接传递，零序列化开销，延迟极低。代价是单次 Workflow 消耗的内存受 JVM 堆限制。

**Dify**: 每个节点是独立的 Celery Task，通过 Redis 传递数据。无状态、可水平扩展，但每一步都要序列化/反序列化 \+ 网络开销。

#### **差异二：插件模型——子 Workflow vs Python 函数**

**AgentFlow**: "插件即子 Workflow"——`RunPluginServiceImpl` 加载插件内部的 DAG 节点列表，递归调用 `WorkFlowEngine.dispatchModules()`。插件可以有完整编排能力，甚至可以嵌套另一个插件。

**Dify**: 插件就是 Python Tool 函数——轻量、简单，适合单一职责的 API 调用。

#### **差异三：容错——内存 vs 持久化**

**AgentFlow**: 进程崩溃状态全丢，重试靠 `Future.cancel()` \+ 循环重试。

**Dify**: Celery Task 状态持久化到 Redis/DB，Worker 挂了其他 Worker 可以接手。

### **适合什么场景**

### **面试怎么讲**

> 两个引擎都是 DAG 编排，但实现哲学相反。AgentFlow 选择内存内执行——一个 Workflow 在一个 JVM 进程跑完，数据用 FlowContext 对象直接传递，零序列化延迟。Dify 选择分布式执行——每个节点是 Celery Task，通过 Redis 串联，可水平扩展。 AgentFlow 的核心差异化设计是"插件即子 Workflow"——插件不是简单函数调用，而是加载插件内部的 DAG 节点列表，递归调用 WorkFlowEngine。 简单说：Dify 更适合高并发、长运行时间的批量任务；AgentFlow 更适合低延迟、复杂编排的实时 AI 对话。
> 
> 

---

## **Q32: DataFlow 离线跑批引擎**

### **双引擎定位**

```Plain Text
┌─────────────────────────────┐
│      AgentFlow 平台           │
│                              │
│  WorkFlow 在线引擎            │
│  实时对话 (< 5s)              │
│  内存递归执行                 │
│                              │
│  DataFlow 离线引擎            │
│  ETL 批处理 (分钟级)          │
│  DB 轮询 + 分段调度            │
│  Argo K8s 大文件分流          │
└─────────────────────────────┘
```

### **DataFlow vs Dify Celery**

### **DataFlow 独特设计**

#### **数据库驱动调度（不是消息队列）**

```Java
@Scheduled(fixedDelay = 30000)  // 每 30 秒
public void process() {
    // ★ 从数据库轮询待处理的 Context ★
    List<KnowledgeFlowContext> contexts = contextService.list(
        Wrappers.lambdaQuery(KnowledgeFlowContext.class)
            .in(KnowledgeFlowContext::getStatus, List.of(0, 5))
    );
    // INIT(0) 或 TRANS(5)
}
```

pull 模式的好处：不需要额外的消息中间件，状态和任务在同一张表中天然一致。

#### **两层状态机——Context \+ Node**

```Plain Text
Context 状态:  INIT → TRANS → 执行中 → SUCCESS/FAILURE/PAUSE
                        │
                        ▼
Node 状态:    INIT → PROCESS → SUCCESS
                │        │
                └── FAILURE → 重试(指数退避) → SUCCESS/FAILURE

两层独立:
  - context.TRANS = "可以继续执行下一批节点"
  - node.FAILURE + retryNum < 3 = "继续重试这个节点"
  - node.FAILURE + retryNum >= 3 = "标记 context.FAILURE"
```

#### **文件大小分流**

```Java
if (fileSize < argoFileMax) {
    executor.execute(node);       // < 200MB → JVM 内存直接执行
} else {
    argoApiService.submitJob(node);// >= 200MB → Argo K8s Pod
}
```

### **三引擎总结**

---

# **AgentFlow 缺点与改进**

---

## **Q33: AgentFlow 的核心缺点与解决方案**

### **问题一：WorkFlow 引擎——内存执行，挂了全丢**

**问题**: 整个 Workflow 在一个 JVM 进程中跑，`FlowContext` 是内存对象。进程崩溃 → 所有中间状态丢失。

```Plain Text
用户对话 → dispatchModules() → 执行了 5 个节点 → JVM OOM
                                           ↓
                                    全部白跑，用户看到超时
```

**现状**: `ModuleService.executeWithTimeout()` 对单节点做了超时 \+ 重试 3 次，`moduleDefaultOutput` 兜底。但这些是单节点级别的，Workflow 级别没有 checkpoint。

**改进方向**: 对非实时场景增加 checkpoint——每执行完一个节点把 `FlowContext` 序列化到 Redis，进程恢复后从最近的 checkpoint 继续。代价是每次 checkpoint 有序列化开销，折中方案是对非对话场景（API 调用 Workflow）开启。

### **问题二：意图路由太粗——5 个子 Agent，无法做细粒度 FAQ**

**问题**: IntentAgent 只能路由到 5 个子 Agent 级别，无法区分"OA 审批"和"保险审批"。

```Plain Text
用户: "OA 审批流程是什么？"
  → IntentAgent: "业务问题" → strategy_agent
  → strategy_agent: 检索 "审批流程"
  → ES 返回: 保险审批流程 + OA 审批流程 ← 噪音混在一起
```

**原因**: AgentFlow 定位是多业务 Agent 平台，不是 FAQ 系统。知识库路由在 WorkFlow 节点配置时手动指定。

**改进方向**: 参考 Ragent 的意图树——datasetSearchNode 前增加意图分类，动态传入 `variables["knowledgeIds"]`。

### **问题三：DataFlow 30 秒轮询间隙**

**问题**: 节点 1 秒执行完，下一批节点要等最多 29 秒。

```Plain Text
cnode_02 执行完成 (1s)
  → context.status = TRANS
  → 等待 Job 下一轮轮询... ⏳ 29 秒
  → Job 取到 cnode_03 → 执行
```

**原因**: 数据库驱动调度的代价。好处是不需要额外消息队列，状态和任务在同一张表中一致。

**改进方向**: 已支持分阶段调度（`stageSchedulingExecute`），可改成事件驱动——节点完成时发 Kafka/Redis 事件，消费者立刻处理，轮询作为兜底。

### **问题四：Java ↔ Python 跨语言调用开销**

**问题**: 每次跨语言都是 HTTP 往返 \+ JSON 序列化。文档解析、分块、摘要这些高吞吐的 AI 操作都经过这个跳转。

**原因**: 历史原因——Java 做企业编排（Spring Boot \+ MyBatis \+ 公司平台包），Python 做 AI（PyMuPDF/LangChain/Google ADK），各取所长。

**改进方向**: 高频操作（Embedding）已直接从 Java 调用模型 API 不经过 Python。长期可迁移到 gRPC（少一次 JSON 序列化）或 Spring AI。

### **问题五：没有模型熔断降级**

**问题**: LLM 调用失败 → 重试 3 次 → 抛异常 → 前端看到"服务异常"。没有三态熔断器。

**改进方向**: 用 Sentinel 的 `@SentinelResource` 注解（项目已引入 Sentinel 依赖）或自研三态熔断。Open → 自动降级到备用模型 → 预设兜底回复。

### **问题六：工具调用是同步 HTTP 阻塞**

**问题**: Python Agent 的 `CommonTool.run_async()` 发 HTTP 到 Java，同步等待返回。工具慢 → LLM 干等。

**改进方向**: 长时间运行工具改成异步回调——立即返回 `{"status": "processing"}`，完成后回调 Agent。ADK 框架支持此模式但未启用。

### **问题七：RRF k 值固定**

**问题**: `RrfRankerUtil.rankFusion()` 中 k=60 写死，不可按场景调优。

**改进方向**: 把 k 值放到 `MixSearchDTO` 中（前端画布可配），或根据通道质量动态调整——向量通道好时减小 k，两通道接近时增大 k。

### **问题八：长期记忆和会话记忆是两套系统**

**问题**: 短期记忆在 Redis Session（Java），长期记忆在 Mem0 \+ Milvus（Python）。运维两套存储。

**改进方向**: 用 LLM 驱动的渐进式摘要（像 Ragent 那样）替代 Mem0 语义检索，摘要直接存 MySQL ChatItem 表，统一记忆存储。

### **总结：这是取舍，不是错误**



