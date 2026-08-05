# Agent Workflow 面试 Q\&A

<a id="q1"></a>
## **Q1: 这个项目的 Agent 整体架构是怎样的？**

### **双框架 \+ 多智能体编排**

```Plain Text
用户请求 → AutoAgentExecutor
              │
              ├── framework="adk" → ADKExecutor (Google ADK) —— 默认、主推框架
              └── framework="langgraph" → LangGraphExecutor (LangChain) —— 兼容/降级方案
```

项目同时支持两种 Agent 框架，通过 `framework` 字段切换，默认和主力是 Google ADK。

### **多智能体编排树**

```Plain Text
IntentAgent (根节点——意图路由)
                           │
        ┌──────┬──────┬────┼────┬────────┐
        ▼      ▼      ▼    ▼    ▼        ▼
   Meta    Strategy Strategy Direct  RunData
   Agent   View     Agent   Analysis Agent
           Agent            Agent
              │                │
       ┌──────┼──────┐    ┌───┴──────┐
       ▼      ▼      ▼    ▼          ▼
   Analysis Code  Decision ParamExtract Execute
   Agent    Agent Agent    Agent       Dataflow Agent
                      │
              ┌───────┴───────┐
              ▼               ▼
        StrategyCreate    DataPull
           Agent

共 7 个子 Agent，StrategyAgent 和 DirectAnalysisAgent 内部继续拆分
```

### **意图路由逻辑（IntentAgent）**

核心在 `agent/agents/intent_agent/prompt.py`——**用 Prompt 驱动意图路由**，而非代码硬编码：

- 社交互动 → 直接回复

- 元数据操作 → `metadata_agent`

- "查看/列出/展示策略" → `strategy_view_agent`

- "提取/分析/优化策略" → `strategy_agent`

- 有 chat\_id/customer\_id 的数据分析 → `direct_analysis_agent`

- 其他 → `strategy_agent`

关键设计：**严格区分动词**。"查看"类动词 → strategy\_view\_agent，"处理"类动词 → strategy\_agent。禁止 Agent 直接返回 JSON 或绕过 transfer\_to\_agent 生成业务回答。

---

<a id="q2"></a>
## **Q2: 一条请求的完整执行链路是怎样的？**

```Plain Text
1. POST /api/auto-agent/run → FastAPI (agent/app.py)
2. AutoAgentExecutor.execute()
3. 选择框架 → ADKExecutor (默认)
4. _init_session() → Redis 恢复/创建会话，加载 chat_history
5. Runner.run_async() → 启动 ADK 运行时
6. IntentAgent 接收消息
   ├── before_agent_callback: 检查前置分支 (策略发布检查、策略使用检查)
   ├── before_model_callback: 
   │   ├── 检查 label，特殊处理 RUN_START/RUN_CREATE
   │   └── 可选：工具调用判断意图 (INTENT_ANALYSIS_WITH_TOOL_FLAG)
   └── LLM 推理 → transfer_to_agent(目标子Agent)
7. 子Agent (如 StrategyAgent) 接收任务
   ├── CustomPlanner → Plan-ReAct 规划
   │   ├── /*PLANNING*/ → 制定分步计划
   │   ├── /*ACTION*/ → 调用工具
   │   ├── /*REASONING*/ → 基于结果推理
   │   └── /*FINAL_ANSWER*/ → 合成最终答案
   ├── Tool Call → HTTP POST agentflow-server/api/v1/tools/run
   │   ├── plugin 工具 → 执行插件
   │   ├── mcp 工具 → 调用 MCP Server
   │   ├── workflow 工具 → 触发工作流
   │   ├── autoAgent 工具 → 嵌套调用子 Agent
   │   └── dataset 工具 → 知识库检索 (ES/Milvus 混合召回 + Rerank)
   └── after_tool_callback → 工具结果后处理，记录知识库引用
8. ADKExecutor.fill_custom_metadata() → 标记事件类型、工具信息、思考过程
9. SSE Streaming: event: llm\ndata: {...}\n\n 逐个推送给前端
10. 每 10 秒心跳防止连接超时
11. after_model_callback → 处理引用溯源 (quoteMark 重新编号、quoteList 生成)
```

### **关键代码位置**

---

<a id="q3"></a>
## **Q3: 和 LangChain 的核心区别是什么？**

### **为什么选择 Google ADK 而不是 LangChain？**

1. **ADK 原生支持层次化多 Agent**：`sub_agents` \+ `transfer_to_agent` 是框架原生能力，不需要像 LangGraph 那样手动建图

2. **Plan\-ReAct 开箱即用**：ADK 内置 PlanReActPlanner，而 LangChain 需要自己拼装

3. **Session/Memory 内置**：ADK 自带 SessionService 和 MemoryService 抽象，替换为 Redis 实现只需实现接口

4. **事件模型更清晰**：ADK 的 Event 模型天然适合 SSE 流式输出，每个事件自带 author/content/custom\_metadata

5. **但保留 LangGraph 作为降级方案**：项目同时实现了 LangGraphExecutor，通过 `framework` 参数切换，应对 ADK 不支持的场景

### **两种框架执行方式对比**

```Python
# Google ADK 方式 (agent/auto_agent/framework_adapter/adk/executor.py)
runner = Runner(
    app_name=self.app_name,
    agent=self.agent,              # CustomLlmAgent (含 sub_agents)
    session_service=session_service,  # Redis 会话
    memory_service=memory_service,    # Redis 记忆
)
async for event in runner.run_async(
    user_id=user_id,
    session_id=chat_id,
    new_message=new_message,
):
    yield format_sse(event)  # 直接产出 SSE 事件

# LangGraph 方式 (agent/auto_agent/framework_adapter/langgraph_adapter/executor.py)
agent = create_react_agent(       # LangGraph 的 ReAct Agent
    model=ChatOpenAI(...),
    tools=tools,
    prompt=prompt,
    checkpointer=InMemorySaver(),  # 内存 checkpoint
)
async for chunk in agent.astream(
    input=message,
    config={"configurable": {"thread_id": chat_id}},
):
    yield chunk  # 产出 LangGraph chunk，需自行转换
```

---

<a id="q4"></a>
## **Q4: 工具系统如何设计？**

### **四种工具类型，统一调用网关**

```Plain Text
Agent (Python)                     AgentFlow Server (Java)
     │                                      │
     │  HTTP POST /api/v1/tools/run         │
     ├──────────────────────────────────────►
     │  {                                     │
     │    "id": "tool_001",                   │
     │    "type": "plugin|mcp|workflow|       │
     │            autoAgent|dataset",         │
     │    "params": {...}                     │
     │  }                                     │
     │                                      │
     │◄─────────────────────────────────────┤
     │  {"code": "10000", "data": {...}}     │
```

### **工具声明**

```Python
# CommonTool._get_declaration() → 生成 FunctionDeclaration
# autoAgent/workflow 类型自动追加 question 参数
FunctionDeclaration(
    name="策略分析工具",
    description="分析策略效果...\nArgs:\n  question(str): 用户输入参数"
)
```

### **知识库检索 \(KnowledgeTool\)**

- 支持三种检索模式：`embedding` / `fullTextRecall` / `mixedRecall`

- RRF 混合召回 \+ Rerank

- 检索为空时支持兜底策略 \(backup\_strategy\)：自定义回答

- 检索结果自动记录到 State 中用于最终引用溯源

---

<a id="q5"></a>
## **Q5: 会话和记忆如何管理？**

### **会话 \(Session\)**

- `InRedisSessionService`：基于 Redis 实现 ADK 的 SessionService 接口

- 每次请求根据 `chat_id` 创建或恢复会话

- 若前端传了 `chatHistory`，则删除旧会话重建，以传入的历史为准

- 会话中存储 Event 列表 \(对话历史\)

### **记忆 \(Memory\)**

- `InRedisMemoryService`：基于 Redis 实现 ADK 的 MemoryService 接口

- 支持长期记忆 \(`longTermMemory`\)，拼接到 Prompt 的 `【长期记忆】` 段

### **Prompt 拼接逻辑**

```Python
def get_prompt(context):
    text = agent_meta.promptInfo        # 用户配置的 Prompt
    # 替换全局变量占位符 {{var}}
    text = replace_variable(text, variables)
    # 拼接 RAG 临时知识库
    if rag_options.quoteQA:
        text += "\n【背景知识】\n" + rag_results
    # 拼接长期记忆
    if agent_meta.longTermMemory:
        text += "\n【长期记忆】\n" + memory
    # 拼接溯源提示词
    if knowledge.showSource:
        text += quote_prompt
    # 多模态兜底文案
    text += "当用户想要生成多模态内容但无法提供时，回复：很遗憾..."
    return text
```

---

<a id="q6"></a>
## **Q6: 可观测性如何实现？**

### **三层监控**

```Plain Text
Layer 1: Langfuse —— LLM 调用追踪
  ├── Trace: requestId
  ├── Span: agentMeta.name
  └── Generation: 每次 LLM 调用

Layer 2: Pinpoint —— APM 全链路追踪
  ├── Python: pinpointPy (Flask + FastAPI 中间件)
  ├── Java: pinpoint agent
  └── TraceId 透传: HTTP Header pinpoint-traceid / pinpoint-spanid

Layer 3: Prometheus —— 指标监控
  └── Flask: /metrics 端点 (prometheus_flask_exporter)
  └── Java: /actuator/prometheus (micrometer)
```

---

<a id="q7"></a>
## **Q7: Plan\-ReAct 规划器的核心逻辑？**

`CustomPlanner` 继承自 ADK 的 `PlanReActPlanner`，定义了 LLM 输出格式：

```Plain Text
/*PLANNING*/          —— 制定计划
1. 首先获取最近数据
2. 然后分析数据趋势
3. 最终生成策略

/*ACTION*/            —— 执行工具
function_call(data_pull, ...)

/*REASONING*/         —— 推理
已获取近7天数据，发现转化率下降3%

/*ACTION*/            —— 继续执行
function_call(analysis, ...)

/*REASONING*/
数据表明问题在渠道A...

/*FINAL_ANSWER*/      —— 最终答案
根据分析，建议...
```

关键定制：`_only_keep_plan()` 方法在 LLM 返回中只保留 PLANNING 部分作为思考过程展示，去掉 ACTION 和 REASONING 标签，让前端界面更清晰。

---

<a id="q8"></a>
## **Q8: 请求模型 \(AutoAgentRequest\) 的关键字段**

```Python
class AutoAgentRequest:
    requestId: UUID          # 请求唯一ID，用于 Langfuse trace
    chatId: str              # 会话ID
    question: str            # 用户问题
    agentMeta: AgentMeta     # Agent 元数据 (核心)
    framework: str = "adk"   # 运行框架: "adk" | "langgraph"
    draftMode: bool = False  # 调试/对话模式
    variables: dict          # 全局变量 {{var}}
    chatHistory: list        # 对话历史
    ragOptions: RagOptions   # 临时知识库召回

class AgentMeta:
    agentId: str             # Agent ID
    name: str                # Agent 名称
    intro: str               # Agent 描述
    type: str                # Agent 类型
    modelInfo: ModelInfo     # 模型配置 (modelId, url, temperature, maxTokens...)
    promptInfo: str          # Prompt 模板
    tools: Tools             # 工具列表 (plugin/mcp/workflow/autoAgent)
    knowledge: KnowledgeList # 知识库配置
    longTermMemory: dict     # 长期记忆
```

---

<a id="q9"></a>
## **Q9: agentflow\-server 是 workflow 工作流编排吗？和 LangChain 的区别？**

### **是的，agentflow\-server 是一个可视化的 DAG 工作流编排引擎**

AgentFlow 的核心是一个**低代码可视化工作流编排平台**，用户可以通过拖拽节点构建 DAG（有向无环图），WorkFlowEngine 负责按拓扑顺序递归执行。

### **WorkFlowEngine 引擎架构**

```Plain Text
dispatchModules(ChatDispatchParam)
    │
    ├── 1. loadModules()        —— 加载所有节点 → 过滤/转换 → RunningModuleItemType
    ├── 2. initRunningModuleType() —— 找到入口节点 (questionInput/historyNode/pluginInput/loopStart)
    ├── 3. moduleInput()        —— 为入口节点注入初始参数 (startParams)
    │
    └── 4. 执行模式分支
         ├── 同步模式: checkModulesCanRunSync()
         │       └── 递归链式执行: 当前节点完成 → 找下游节点 → 递归
         └── 并发模式: checkModulesCanRun()
                 └── 线程池 + CountDownLatch 并行执行同级节点
```

### **单节点执行流程 \(moduleRun\)**

```Plain Text
RunningModuleItemType
    │
    ├── ModuleFactory.getService(nodeType)  → 获取对应的 ModuleService 实现
    │       (共 50+ 种节点类型，每种有独立的 ServiceImpl)
    │
    ├── transParam(inputs)  → 组装输入参数 Map
    │
    ├── moduleService.executeWithTimeout(dispatchData)  → 带超时的节点执行
    │
    └── 返回 Map<String, Object>
```

### **节点完成后 → 数据流转 \(moduleOutput\)**

```Plain Text
节点执行结果 Map
    │
    ├── pushStore()  → 持久化 answerText / chatResponse / structOutput
    ├── 全局变量传递: output.globalKey → flowContext.globalVariables
    ├── 边连接 (Edges): outputItem.edges → 找到下游节点 → moduleInputGlobal
    └── 目标连接 (Targets): outputItem.targets → 按 target.key 赋值下游 input
```

### **全部节点类型 \(50\+\)**

### **agentflow\-server vs LangChain/LangGraph 的核心区别**

### **一句话区分**

> **agentflow\-server 是"可拖拽的 DAG 工作流引擎"**——面向业务人员，50\+ 预置节点，可视化编排，适合快速搭建 AI 应用流水线。**LangChain 是"代码级的 LLM 框架"**——面向开发者，提供 Chain/Graph/Tool 等编程抽象，灵活但需要写代码。
> 
> 

### **两者在项目中的关系**

```Plain Text
┌─────────────────────────────────────────────────┐
│  前端画布 (拖拽节点、连线)                          │
└─────────────────────┬───────────────────────────┘
                      │ 保存工作流 JSON
                      ▼
┌─────────────────────────────────────────────────┐
│  agentflow-server (Java)                        │
│  ├── WorkFlowEngine: 解析 JSON → DAG 执行        │
│  ├── 50+ ModuleServiceImpl: 每种节点的执行逻辑     │
│  └── /api/v1/tools/run: 工具调用统一网关          │
└─────────────────────┬───────────────────────────┘
                      │ HTTP (tool_type="workflow"/"autoAgent")
                      ▼
┌─────────────────────────────────────────────────┐
│  af-rag-server (Python)                         │
│  ├── Google ADK Agent: 意图路由 + Plan-ReAct     │
│  ├── Tool 调用时代理到 agentflow-server            │
│  └── 兼容 LangGraph: framework="langgraph"       │
└─────────────────────────────────────────────────┘

三层关系：
  agentflow-server = 工作流编排平台 + 工具执行网关
  af-rag-server     = 智能体推理引擎 (Google ADK 主力, LangGraph 备用)
  dataflow-server   = 数据 ETL 流水线 (知识入库处理)
```

所以项目中 **agentflow 和 LangChain 不是一个层面的东西**：

- **agentflow\-server** 对标的是 **Dify/Coze** 这类可视化工作流平台

- **LangChain/LangGraph** 对标的是 af\-rag\-server 中 **Google ADK** 的角色（Agent 推理框架）

- 项目保留 LangGraph 作为 Agent 推理的备用框架 \(`framework="langgraph"`\)，但主力是 Google ADK

---

<a id="q10"></a>
## **Q10: AgentFlow 分为哪些功能模块？每个模块的面试技术要点？**

### **模块全景图**

```Plain Text
agentflow-server
│
├── 1. 工作流编排引擎 ──── 核心中的核心
├── 2. 对话/Chat 模块 ──── AI 对话与多轮上下文
├── 3. 知识库/搜索模块 ──── 混合召回 + Rerank
├── 4. Agent 管理模块 ──── AutoAgent 全生命周期
├── 5. 插件/工具模块 ──── 可扩展工具生态
├── 6. Prompt 管理模块 ──── 版本化 Prompt 模板
├── 7. 模型管理模块 ──── 多模型接入与路由
├── 8. 权限/共享模块 ──── 多租户资源管控
├── 9. 审批模块 ──── 发布审批流程
├── 10. 评估模块 ──── Agent 质量评估
├── 11. 记忆模块 ──── 长期记忆与会话变量
├── 12. MCP 集成模块 ──── MCP 协议支持
├── 13. 多模态模块 ──── 图像/音频/视频
└── 14. 安全检测模块 ──── 敏感词/合规/规范
```

---

### **模块 1: 工作流编排引擎**

**核心类**: `WorkFlowEngine` → `ModuleFactory` → `ModuleService` \(50\+ 实现\)

```Plain Text
WorkFlowEngine
├── loadModules()           —— JSON → DAG 节点树
├── initRunningModuleType() —— 找入口节点 (questionInput/historyNode/loopStart)
├── checkModulesCanRunSync()—— 同步递归模式
├── checkModulesCanRun()    —— 线程池并发模式 (CountDownLatch)
├── moduleRun()             —— 单节点执行: ModuleFactory.getService(nodeType).execute()
└── moduleOutput()          —— 结果传递: edges / targets / globalKey → 下游节点
```

**面试技术要点:**

---

### **模块 2: 对话/Chat 模块**

**核心类**: `ChatCompletionServiceImpl`, `AISummaryService`, `ChatContextFilter`

**面试技术要点:**

---

### **模块 3: 知识库/搜索模块**

**核心类**: `DatasetSearchServiceImpl`, `KnowledgeSearchService`, `DenseVectorService`

**面试技术要点:**

---

### **模块 4: Agent 管理模块**

**核心类**: `AutoAgentServiceImpl`, `AgentService`, `AgentSnapshotService`, `FlowSnapshotService`

**面试技术要点:**

---

### **模块 5: 插件/工具模块**

**核心类**: `PluginController`, `PluginStoreController`, `PluginShareController`

工具类型: `plugin` / `mcp` / `workflow` / `autoAgent`

**面试技术要点:**

---

### **模块 6: Prompt 管理模块**

**核心类**: `PromptController`, `PromptDetailVersionController`

**面试技术要点:**

---

### **模块 7: 模型管理模块**

**核心类**: `ModelController`, `ModelQueryService`

**面试技术要点:**

---

### **模块 8: 权限/共享模块**

**核心类**: `ShareCenterController`, `PermissionController`, `AgentShareController`

**面试技术要点:**

---

### **模块 9: 审批模块**

**核心类**: `ApproveCenterController`, `ApproveConfigController`, `ApproveApiController`

**面试技术要点:**

---

### **模块 10: 评估模块**

**核心类**: `EvalDatasetController`, `AgentEvaluationController`, `MultiRoundEvalDatasetController`

**面试技术要点:**

---

### **模块 11: 记忆模块**

**核心类**: `AgentMemoryFieldRelationServiceImpl`, `TeamMemoryFieldConfigServiceImpl`

Python 端: `mem0ai` 集成, `/vector/memory/*` API

**面试技术要点:**

---

### **模块 12: MCP 集成模块**

**核心类**: `McpServerController`

**面试技术要点:**

---

### **模块 13: 多模态模块**

节点: `imgCompletion`, `audioCompletion`, `videoCompletion`, `imageGenerate`, `videoGenerate`, `voiceGenerate`, `videoKeyframesExtract`

**面试技术要点:**

---

### **模块 14: 安全检测模块**

节点: `sensitivityDetection`, `standardCheck`, `complianceCheck`

**面试技术要点:**

---

### **面试高频追问 \& 回答思路**

**Q: 工作流引擎怎么实现并发安全？**

> `FlowContext` 使用 `ConcurrentHashMap` 存储 `globalVariables`，`CountDownLatch` 协调并发节点完成，`ExecutorCompletionService` 按完成顺序收集结果。
> 
> 

**Q: 怎么保证节点执行的幂等性？**

> `moduleCanRun()` 检查所有 input 的 value 是否为 null——如果上游已经给过值，`switchMap` 移除该 key 防止重复执行。
> 
> 

**Q: 知识库搜索为什么用混合召回而不是纯向量？**

> 纯向量对精确关键词匹配效果差，`mixedRecall` 通过 RRF 融合向量相似度和 BM25 全文得分，同时保留语义理解和关键词匹配能力。
> 
> 

**Q: Agent 调用 Agent 怎么防止死循环？**

> ADK Runner 的 `RunConfig.max_llm_calls=20` 限制最大 LLM 调用次数，超限自动终止。
> 
> 

**Q: 插件系统和 MCP 是什么关系？**

> 插件是平台原生的工具扩展机制（HTTP/Python/NLP 插件），MCP 是外部标准协议接入。两者在工作流中都是节点类型，统一通过 `/api/v1/tools/run` 执行。
> 
> 

**Q: 为什么 DAG 不是真正的图而是树？**

> 实际上通过 `edges` 和 `targets` 可以实现多入多出的 DAG 结构。`flowContext.moduleItemTypeMap` 是 Map 而非树，一个节点可以有多个下游消费其输出。
> 
> 

---

<a id="q11"></a>
## **Q11: DataFlow 与 AgentFlow 如何联动？节点状态流转、调度与容错设计？**

### **DataFlow 的定位**

DataFlow 是**知识数据 ETL 流水线引擎**，负责将原始文档经过一系列处理节点，最终产出可检索的结构化知识数据。

```Plain Text
┌──────────────────────────────────────────────────────────────┐
│  agentflow-server                                            │
│  ├── Knowledge 管理 (创建知识库、上传文件、配置 Flow)            │
│  ├── 触发 dataflow 任务 (创建 KnowledgeFlowContext)           │
│  └── 消费 dataflow 产出 (ES/Milvus 中的向量化数据)             │
└──────────────────────┬───────────────────────────────────────┘
                       │ 共享数据库: knowledge_flow_context 表
                       ▼
┌──────────────────────────────────────────────────────────────┐
│  dataflow-server                                             │
│  ├── DataFlowContextJob: 定时 30s 轮询待处理 Context           │
│  ├── DataFlowContextTask: Argo 编排模式执行器                  │
│  ├── DataflowExecutorService: 40+ 节点执行器抽象基类           │
│  └── DataFlowModuleContext: 状态流转控制器                     │
└──────────────────────────────────────────────────────────────┘
```

### **核心数据模型**

```Plain Text
Knowledge (知识库)
    └── KnowledgeFlow (编排定义，DAG 节点拓扑)
            │
            ▼
KnowledgeDataset (数据集，一次导入/任务)
    └── KnowledgeFlowContext (执行上下文，一个 context = 一个 flow 的一次执行)
            │
            ▼
KnowledgeFlowContextNode (上下文的节点实例，每个 flow node 对应一个)
```

**面试要点**: agentflow 和 dataflow **不是通过 HTTP/RPC 通信**，而是通过**共享 MySQL 数据库表**进行协作：

- agentflow 创建 KnowledgeFlowContext 记录 \(status=INIT\) 和 KnowledgeFlowContextNode 记录

- dataflow 定时轮询这些记录，取到后执行，执行完更新状态

- 这是一种**数据库驱动的异步任务调度模式**

### **两层状态机**

#### **Context 层状态 \(KnowledgeFlowContextStatusEnum\)**

```Plain Text
┌──── 用户触发 ────┐
                    ▼                  ▼
      ┌───────┐  ┌──────┐        ┌─────────┐
      │ QUEUING│  │ INIT │        │  PAUSE  │
      │  (-1)  │  │ (0)  │        │   (7)   │
      └───┬───┘  └──┬───┘        └─────────┘
          │         │                  ▲
          │    Job 轮询取到              │ 暂停操作
          │         │                  │
          │         ▼                  │
          │    ┌────────┐              │
          └───►│ TRANS  │◄─────────────┘
               │  (5)   │
               └───┬────┘
                   │ 节点开始执行
                   ▼
              ┌─────────┐
              │ PROCESS │  ←── 节点在跑
              │   (4)   │
              └────┬────┘
                   │
          ┌────────┼────────┬──────────────┐
          ▼        ▼        ▼              ▼
     ┌────────┐ ┌──────┐ ┌──────────┐ ┌──────────┐
     │ SUCCESS│ │TRANS │ │ FAILURE  │ │TERMINATE │
     │  (1)   │ │ (5)  │ │   (3)    │ │   (6)    │
     └────────┘ └──────┘ └──────────┘ └──────────┘
                    │         │              │
                    │    重试次数耗尽          │ 手动终止
                    │    可重试 → TRANS       │
                    └───────┘                │
                                             │
              ┌──────────────────────────────┘
              │ 分段调度模式:
              ▼
         ┌─────────┐     ┌─────────┐
         │ STAGED  │ ──► │ PROCESS │ ──► ...
         │   (8)   │     │   (4)   │
         └─────────┘     └─────────┘
```

**关键状态说明:**

#### **Node 层状态 \(KnowledgeflowContextNodeStatusEnum\)**

```Plain Text
┌──────────┐
                    │   INIT   │ (0)  初始化
                    └────┬─────┘
                         │ Job 取到
                    ┌────┴─────┐
              同步组件│          │异步组件
                    ▼          ▼
              ┌─────────┐  ┌──────────┐
              │ PROCESS │  │   SENT   │ (1)  已提交异步任务
              │   (4)   │  └────┬─────┘
              └────┬────┘       │ 回调
                   │            ▼
                   │     ┌──────────────┐
                   │     │WAITING_RESULT│ (5)  等待异步结果
                   │     └──────┬───────┘
                   │            │
              ┌────┴────────────┴────┬──────────┐
              ▼                      ▼          ▼
        ┌──────────┐          ┌──────────┐ ┌──────────┐
        │ COMPLETE │          │ FAILURE  │ │TERMINATE │
        │   (2)    │          │   (3)    │ │   (6)    │
        └──────────┘          └────┬─────┘ └──────────┘
                                  │
                            retryNum < 3
                                  │
                                  ▼
                            ┌──────────┐
                            │   INIT   │  重置为初始化，等待下一轮 Job
                            └──────────┘
```

**面试要点**: 状态机有两个维度——Context 是宏观任务状态，Node 是微观节点状态。Context 的 TRANS/PROCESS 切换实现了**分批次调度**：每次 Job 只取一批节点执行，执行完这批节点后 Context 回到 TRANS，等下一轮 Job 再取新一批。

### **调度设计**

#### **4\.1 定时调度 \(DataFlowContextJob\)**

```Plain Text
@Scheduled(fixedDelay = 30000)  每 30 秒执行一次
    │
    ├── Redis 分布式锁 (50s 超时)  —— 防止多实例重复执行
    │
    ├── 查询 INIT(0)/TRANS(5) 状态的 Context
    │
    ├── 文件大小分流:
    │   ├── 小文件 (< argoFileMax MB) 或 stageScheduling=true → 内存直接执行
    │   └── 大文件 → 交给 Argo 编排引擎 (k8s Pod 级别)
    │
    ├── 查询每个 Context 的最后一批待执行节点 (findLastProcessNodesByContextIds)
    │       └── 过滤掉 SEND(1)/PROCESS(4) 的节点 → 只处理 INIT/FAILURE 节点
    │
    └── 逐个执行: DataflowComponentFactory.getService(nodeType).execute(moduleContext)
```

#### **4\.2 分段调度 \(stageSchedulingExecute\)**

```Plain Text
正常模式: 一轮取所有 INIT 节点 → 一口气跑到最后
分段模式: 
  ┌───────────────────────────────────────────────┐
  │ 第1轮: Job 取 INIT 节点 → execute()           │
  │   → 组件执行 generateComponent() 创建 Argo 任务 │
  │   → handleStageSuccess(REQUEST_PROCESSING)    │
  │   → Context 状态改为 STAGED(8)                │
  │                                               │
  │ Argo Pod 执行完成 → 回调 Dataflow             │
  │   → Context 状态改为 PROCESS(4) + 版本号 +1    │
  │   → 触发下一阶段: executeStage(RESULT_PROCESSING) │
  │   → 组件执行 completeStage() 处理 Argo 结果    │
  │   → handleSuccess() → Node COMPLETE           │
  └───────────────────────────────────────────────┘
```

分段调度的核心目的是将**大计算任务交给 Argo \(K8s\) 执行**，DataFlow 自身只负责编排和状态管理。

### **失败处理与重试机制**

#### **5\.1 重试判断 \(noRetry\)**

```Java
// DataflowExecutorService.noRetry()
boolean canRetry = status == FAILURE && retryNum < maxRetry(默认3);
boolean noRetry = status != INIT && !canRetry;
// 只有 INIT 状态 或 FAILURE 且未满 3 次 才执行
```

#### **5\.2 指数退避重试**

```Java
// DataFlowModuleContext.moduleContextScheduleChangeStatus()
// 节点失败时:
int pow = (int) Math.pow(2, retryNum);           // 2^0=1, 2^1=2, 2^2=4 (分钟)
long retryTimestamp = pow * 60 * 1000 + now;      // 第1次: 1分钟后, 第2次: 2分钟后, 第3次: 4分钟后
context.setRetryTimestamp(retryTimestamp);
context.setStatus(TRANS);  // 放回 TRANS 等待 Job 下次取到
```

#### **5\.3 超时检测**

```Java
// DataflowExecutorService.preCheckHandle()
boolean overtime = knowledgeFlowContextService.isOvertime(contextId, contextNodeId);
if (overtime) return false;  // 超时直接跳过
```

#### **5\.4 Argo 集群健康检查**

```Java
// DataFlowModuleContext.batchProcess()
// 定时检测 Argo 任务实例状态:
if (argoInstance.phase == ERROR/FAILED && messageType == RUNTIME_EXCEPTION) {
    if (retryNum < 3) {
        node.retryNum++ → node.status = FAILURE
        context.status = STAGED → 重新触发 Argo
    } else {
        context.status = FAILURE  // 超过3次直接失败
    }
}
```

#### **5\.5 终止/暂停保护**

```Plain Text
执行前后双重检查:
  execute() 开头: context.status == TERMINATE/PAUSE → return
  execute() 异常: node.status == TERMINATE → 不修改状态
  
  moduleContextScheduleChangeStatus():
    context.status == TERMINATE/PAUSE → return (不推进状态)
    dataset.inited == PAUSING → context.status = PAUSE (转暂停)
```

### **节点间数据流转**

```Plain Text
┌──────────────────────────────────────────────────────────────┐
│  文件上传 → docParse → textChunk → summary → keyword         │
│                │                     │         │              │
│                │ S3 resultPath       │         │              │
│                ▼                     ▼         ▼              │
│           ┌─────────────────────────────────────────┐        │
│           │         docStorage / faqStorage          │        │
│           │   (读取前置节点 resultPath → Embedding   │        │
│           │    → 写入 ES + Milvus)                   │        │
│           └─────────────────────────────────────────┘        │
└──────────────────────────────────────────────────────────────┘

数据传递方式:
  1. resultPath: S3 文件路径，上游节点产出，下游节点读取
     contextNode.resultPath = "s3://bucket/dataflow/{contextId}/{contextNodeId}/output.json"
  
  2. globalKey: 全局变量引用，格式 "{moduleId}-{key}"
     例: "docParse-outputPath" → 引用 docParse 节点的 outputPath
  
  3. value 中的变量: "select {{customCodeHandle-O07NDL}},{{global-aa}}"
     Job 执行前通过正则提取 {{...}} 并替换为实际值
```

### **DataFlow 节点类型 \(40\+\)**

### **agentflow 与 dataflow 的联动总结**

```Plain Text
agentflow-server                          dataflow-server
      │                                        │
      │ 1. 用户创建知识库 + 上传文件              │
      │ 2. 配置 KnowledgeFlow (DAG)             │
      │ 3. 创建 KnowledgeDataset                │
      │ 4. 创建 KnowledgeFlowContext (INIT)      │
      │    + KnowledgeFlowContextNode (INIT)     │
      │                                        │
      │                                        │ 5. Job 30s 定时轮询
      │                                        │ 6. 取到 INIT/TRANS 的 Context
      │                                        │ 7. 加载节点 → 工厂获取执行器
      │                                        │ 8. executeComponent()
      │                                        │    ├── docParse → S3
      │                                        │    ├── textChunk → S3
      │                                        │    ├── summary → S3
      │                                        │    └── docStorage → ES/Milvus
      │                                        │
      │ 9. 知识库数据就绪                         │
      │ 10. Agent 通过 datasetSearchNode 检索     │
      │     → KnowledgeTool → af-rag-server       │
      └────────────── 完成 ──────────────────────┘
```

**面试一句总结**:

> DataFlow 和 AgentFlow 通过数据库驱动异步协作——AgentFlow 创建 Context/Node 记录，DataFlow 的 Job 每 30 秒轮询执行，两层状态机 \(Context\+Node\) 管理任务生命周期，失败指数退避重试最多 3 次，大文件交给 Argo\(K8s\) 编排，结果通过 S3 resultPath 在节点间流转。
> 
> 

---

<a id="q12"></a>
## **Q12: 完整案例——从上传文档到知识库可检索的全链路**

### **场景**

用户上传了以下内容到一个名为"产品手册"的知识库：

- 1 个 PDF 文档 \(`产品规格书.pdf`, 15MB\)

- 1 个 Word 文档 \(`操作指南.docx`, 3MB\)

- 2 个网页 URL \(`https://docs.example.com/api` 和 `https://docs.example.com/faq`\)

- KnowledgeFlow 编排: `docParse → textChunk → summary → keyword → docStorage`

### **全链路 12 步**

```Plain Text
┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 1: 用户在 agentflow 前端创建知识库                                        │
├────────────────────────────────────────────────────────────────────────────┤
│ POST /api/knowledge/create                                                 │
│ {                                                                          │
│   "name": "产品手册",                                                        │
│   "vectorModel": "text-embedding-3-large",                                 │
│   "flowId": "flow_abc123"    ← 绑定一个预配置的 KnowledgeFlow                 │
│ }                                                                          │
│                                                                            │
│ → KnowledgeController.create()                                             │
│ → KnowledgesServiceImpl → 写入 knowledge 表                                 │
│   knowledgeId = "know_001", typing = "dataflow"                            │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 2: 用户上传文件 + 添加 URL                                               │
├────────────────────────────────────────────────────────────────────────────┤
│ POST /api/knowledge/dataset/create                                         │
│ {                                                                          │
│   "knowledgeId": "know_001",                                               │
│   "files": [                                                               │
│     {"name":"产品规格书.pdf", "size":15728640},                              │
│     {"name":"操作指南.docx", "size":3145728}                                 │
│   ],                                                                       │
│   "urls": [                                                                │
│     "https://docs.example.com/api",                                        │
│     "https://docs.example.com/faq"                                         │
│   ]                                                                        │
│ }                                                                          │
│                                                                            │
│ → KnowledgeDatasetController.create()                                      │
│ → KnowledgeDatasetServiceImpl.create()                                     │
│                                                                            │
│ 做了什么:                                                                    │
│   a) 文件上传到 S3:                                                          │
│      s3://bucket/files/{tenantId}/know_001/产品规格书.pdf                    │
│      s3://bucket/files/{tenantId}/know_001/操作指南.docx                     │
│   b) URL 写入 dataset 的 url 字段                                            │
│   c) 创建 KnowledgeDataset 记录:                                            │
│      datasetId = "ds_20240720_001"                                         │
│      inited = 0 (未初始化)                                                   │
│   d) 加载 KnowledgeFlow 编排定义 → 生成 KnowledgeFlowContext + 节点实例         │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 3: agentflow 创建 Context 和节点实例 (写入数据库)                           │
├────────────────────────────────────────────────────────────────────────────┤
│ KnowledgeFlowServiceImpl.createContext(datasetId, flowId)                   │
│                                                                            │
│   a) 创建 KnowledgeFlowContext:                                             │
│      contextId = "ctx_001"                                                 │
│      datasetId = "ds_20240720_001"                                         │
│      knowledgeId = "know_001"                                              │
│      status = 0 (INIT)                                                     │
│      globalVariables = [                                                     │
│        {"key":"filePath", "value":"s3://bucket/files/..."},                │
│        {"key":"urls", "value":"https://docs.example.com/api,..."}           │
│      ]                                                                     │
│                                                                            │
│   b) 加载 Flow 的 DAG 定义 → 为每个节点创建 KnowledgeFlowContextNode:          │
│                                                                            │
│      ┌──────────────────────────────────────────────────────┐             │
│      │ Flow 定义 (KnowledgeFlowNode 表)                       │             │
│      │                                                      │             │
│      │  startHandle ──→ docParse ──→ textChunk              │             │
│      │                                      │                │             │
│      │                                      ├──→ summary     │             │
│      │                                      ├──→ keyword     │             │
│      │                                      └──→ docStorage  │             │
│      └──────────────────────────────────────┴──────────────┘             │
│                                                                            │
│      生成 KnowledgeFlowContextNode (按拓扑顺序):                              │
│                                                                            │
│      contextNodeId  nodeType      status  inputPath         retryNum       │
│      ─────────────  ────────      ──────  ─────────         ────────       │
│      cnode_01       startHandle   0(INIT) s3://.../产品规格书.pdf  0         │
│      cnode_02       docParse      0(INIT) null              0              │
│      cnode_03       textChunk     0(INIT) null              0              │
│      cnode_04       summary       0(INIT) null              0              │
│      cnode_05       keyword       0(INIT) null              0              │
│      cnode_06       docStorage    0(INIT) null              0              │
│                                                                            │
│   注意: 对于 URL，agentflow 内部会调用 af-rag-server 的 webParser    │
│   先将网页内容下载为文本文件，再作为普通文件进入 dataflow 流程                     │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 4: dataflow Job 定时轮询                                                │
├────────────────────────────────────────────────────────────────────────────┤
│ DataFlowContextJob.process()  ← @Scheduled(fixedDelay=30000)               │
│                                                                            │
│ RedisLock("DataFlowContextJob") → tryLock(50s)                             │
│                                                                            │
│ SELECT * FROM knowledge_flow_context                                       │
│ WHERE status IN (0, 5)     ← INIT(0) 或 TRANS(5)                           │
│   AND dataset_id = 'ds_20240720_001'                                       │
│                                                                            │
│ → 找到 ctx_001 (status=0)                                                   │
│                                                                            │
│ 文件大小检查:                                                                 │
│   产品规格书.pdf 15MB < argoFileMax(200MB) → 内存直接执行                       │
│   操作指南.docx 3MB < argoFileMax(200MB) → 内存直接执行                         │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 5: 获取待执行节点                                                        │
├────────────────────────────────────────────────────────────────────────────┤
│ findLastProcessNodesByContextIds(["ctx_001"])                              │
│                                                                            │
│   SELECT * FROM knowledge_flow_context_node                                │
│   WHERE context_id = 'ctx_001'                                             │
│     AND status NOT IN (1, 4)  ← 排除 SENT 和 PROCESS 的                    │
│   ORDER BY create_time                                                     │
│                                                                            │
│ → 返回 [cnode_01 (startHandle, INIT)]  ← 只有第1个节点                      │
│   (后续节点 inputPath 为空，moduleCanRun() 会返回 false)                     │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 6: 执行 startHandle 节点                                               │
├────────────────────────────────────────────────────────────────────────────┤
│ DataflowComponentFactory.getService(START_HANDLE)                          │
│   → StartHandleService.execute(moduleContext)                              │
│                                                                            │
│ startHandle 做什么:                                                         │
│   1. 从 globalVariables 读取 filePath/urls                                 │
│   2. 验证文件存在性 (S3 HEAD request)                                        │
│   3. 设置 contextNode.resultPath = "s3://bucket/files/.../产品规格书.pdf"    │
│   4. 状态更新:                                                               │
│      - cnode_01.status = COMPLETE(2)                                       │
│      - ctx_001.status = TRANS(5)        ← 回到 TRANS，等 job 下一轮          │
│                                                                            │
│   moduleContextScheduleChangeStatus:                                        │
│     isLastNode? NO → context.status = TRANS (不是最后节点)                    │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 7: 下一轮 Job → 执行 docParse 节点                                       │
├────────────────────────────────────────────────────────────────────────────┤
│ Job 再次轮询 ctx_001 (status=TRANS)                                         │
│ findLastProcessNodesByContextIds:                                           │
│   → 返回 [cnode_02 (docParse, INIT)]                                       │
│   (cnode_01 COMPLETE 了，被过滤)                                            │
│                                                                            │
│ handleInputPath():                                                          │
│   previousNode = cnode_01 (startHandle)                                    │
│   inputPath = cnode_01.resultPath = "s3://bucket/files/.../产品规格书.pdf"   │
│   cnode_02.inputPath = inputPath                                           │
│                                                                            │
│ DataflowComponentFactory.getService(DOC_PARSE)                              │
│   → DocParseService.execute(moduleContext)                                  │
│                                                                            │
│ DocParseService (异步组件):                                                    │
│   1. 读取 inputPath (S3 上的 PDF)                                 │
│   2. 调用 af-rag-server: POST /rag_algorithm/parser                        │
│      {                                                                     │
│        "file_download_url": "s3://.../产品规格书.pdf",                       │
│        "callback_url": "https://dataflow/callback/docparse",               │
│        "method": "general"                                                 │
│      }                                                                     │
│   3. af-rag-server (Flask):                                                 │
│      - PyMuPDF 解析 PDF → 提取文本/表格/图片                                  │
│      - 结果写入 S3: s3://bucket/dataflow/ctx_001/cnode_02/output.json       │
│      - 回调 dataflow: POST /callback/docparse                               │
│   4. handlePreStatus(): node.status = SENT(1)                               │
│      context.status = PROCESS(4)                                            │
│                                                                            │
│   异步回调回来后:                                                              │
│   5. handleSuccess(): node.status = COMPLETE(2)                             │
│      node.resultPath = "s3://.../cnode_02/output.json"                     │
│      context.status = TRANS(5)                                              │
│                                                                            │
│   (同理处理 操作指南.docx → 另一个 dataset + context)                          │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 8: textChunk 节点 (文本分块)                                             │
├────────────────────────────────────────────────────────────────────────────┤
│ Job 下一轮 → 取到 cnode_03 (textChunk, INIT)                                 │
│ handleInputPath:                                                            │
│   previousNode = cnode_02 (docParse)                                       │
│   inputPath = cnode_02.resultPath                                          │
│                                                                            │
│ TextChunkService.execute():                                                 │
│   1. 读取 docParse 的输出 JSON (解析后的文本内容)                               │
│   2. 调用 af-rag-server: POST /rag_algorithm/splitter                       │
│      {                                                                     │
│        "text": "...",                                                      │
│        "chunk_size": 512,                                                  │
│        "chunk_overlap": 50                                                 │
│      }                                                                     │
│   3. af-rag-server 分块 → 返回 chunk 列表                                    │
│   4. 结果写入 S3: s3://.../cnode_03/output.json                             │
│      [                                                                     │
│        {"chunkId":"chunk_001","chunkText":"...","chunkInfo":"..."},        │
│        {"chunkId":"chunk_002","chunkText":"...","chunkInfo":"..."}         │
│      ]                                                                     │
│   5. node.status = COMPLETE(2), context.status = TRANS(5)                  │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 9: summary + keyword 节点 (并行?不，这里顺序执行)                          │
├────────────────────────────────────────────────────────────────────────────┤
│ 注意: 虽然 summary 和 keyword 在 DAG 中都是从 textChunk 出来的分支，           │
│ 但 dataflow 的实际执行是按拓扑顺序的，Job 一次只取一批节点。                      │
│ 如果两个节点的 input 都满足 → 同一批中并发执行 (CompletionService)              │
│                                                                            │
│ SummaryExtractService:                                                       │
│   1. 读取 textChunk 的所有 chunk                                            │
│   2. 调用 LLM 对每个 chunk 生成摘要                                           │
│   3. 输出 → S3                                                              │
│                                                                            │
│ KeywordExtractService:                                                       │
│   1. 读取 textChunk 的所有 chunk                                            │
│   2. 调用 jieba 分词 + LLM 关键词提取                                        │
│   3. 输出 → S3                                                              │
│                                                                            │
│ 完成后: node.status = COMPLETE, context.status = TRANS                      │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 10: docStorage 节点 (向量化 + 入库)  ← 最后一步                           │
├────────────────────────────────────────────────────────────────────────────┤
│ Job 下一轮 → 取到 cnode_06 (docStorage, INIT)                                │
│                                                                            │
│ DocStorageService.execute():                                                 │
│   → 调用父类 executeStore(moduleContext)                                    │
│                                                                            │
│ executeStore 流程:                                                           │
│   1. 收集前置节点 (textChunk/summary/keyword) 的 resultPath                  │
│      preModuleIdToResultPathMap:                                           │
│        "textChunk" → "s3://.../cnode_03/output.json"                       │
│        "summary"   → "s3://.../cnode_04/output.json"                       │
│        "keyword"   → "s3://.../cnode_05/output.json"                       │
│                                                                            │
│   2. 遍历每个前置节点的输出 → 调用对应 Storage 服务:                             │
│      │                                                                     │
│      ├── textChunk 输出 → DocStorageService.store()                         │
│      │   a) 读取 chunk JSON                                                │
│      │   b) 对每个 chunk.text 调用 Embedding 模型                            │
│      │      POST {modelUrl}/embeddings                                     │
│      │      → 768 维向量                                                    │
│      │   c) 写入 Milvus (向量索引):                                          │
│      │      collection: "know_001"                                         │
│      │      [id, vector(768), chunkText, chunkInfo, ...]                   │
│      │   d) 写入 ES (全文索引):                                              │
│      │      index: "know_001"                                              │
│      │      {chunkId, chunkText, summary, keywords, ...}                   │
│      │                                                                     │
│      ├── summary 输出 → 更新 ES 对应 chunk 的 summary 字段                    │
│      └── keyword 输出 → 更新 ES 对应 chunk 的 keywords 字段                   │
│                                                                            │
│   3. 全部存储完成后:                                                          │
│      dataset.inited = COMPLETE(1)                                           │
│      knowledgeMetricService.recordDatasetMetric(...)  ← 记录指标             │
│      node.status = COMPLETE(2)                                              │
│                                                                            │
│   moduleContextScheduleChangeStatus:                                        │
│     isLastNode? YES → context.status = SUCCESS(1)                           │
│                                                                            │
│   ★ 知识库数据处理完毕！                                                       │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 11: 用户通过 Agent 检索知识库                                            │
├────────────────────────────────────────────────────────────────────────────┤
│ 用户: "产品规格书中关于电池参数是什么？"                                        │
│                                                                            │
│ Agent 工作流执行:                                                             │
│   chatNode → 识别到需要检索知识库                                              │
│   → datasetSearchNode.execute()                                            │
│      {                                                                     │
│        "searchMode": "mixedRecall",    ← 混合召回                            │
│        "vectorSearchRatio": 0.6,       ← 向量权重 60%                        │
│        "fullTextSearchRatio": 0.4,     ← 全文权重 40%                        │
│        "datasets": [{"datasetId":"know_001"}],                              │
│        "userChatInput": "电池参数",                                          │
│        "reRankerSwitch": true,         ← 开启 Rerank                        │
│        "similarity": 0.7               ← 相似度阈值                          │
│      }                                                                     │
│                                                                            │
│   DatasetSearchServiceImpl.execute():                                      │
│     a) ES 全文检索 "电池参数" → BM25 打分                                     │
│     b) Milvus 向量检索 "电池参数" → Cosine 相似度                              │
│     c) RRF 融合两个排序结果                                                   │
│     d) Rerank 模型二次排序                                                   │
│     e) 过滤 similarity < 0.7 的结果                                         │
│     f) 返回 Top K chunks:                                                   │
│        [                                                                   │
│          {"chunkText":"电池容量: 5000mAh...", "score":0.92},                │
│          {"chunkText":"充电参数: 5V/2A...", "score":0.87}                   │
│        ]                                                                   │
│                                                                            │
│   → chatNode 将检索结果拼入 Prompt                                            │
│   → LLM 回答: "根据产品规格书，电池容量为 5000mAh，充电参数为 5V/2A..."          │
│   → 引用溯源: `quoteMark {"id":"chunk_001","quoteId":1,...}`                 │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ STEP 12: 异常情况处理                                                        │
├────────────────────────────────────────────────────────────────────────────┤
│ 如果 docParse 失败:                                                          │
│   node.status = FAILURE(3), retryNum = 0                                    │
│   context.retryTimestamp = now + 2^0 * 60s = now + 1min                     │
│   context.status = TRANS(5)                                                 │
│   → 1 分钟后 Job 再次取到 → retryNum=0 < 3 → 重试                             │
│                                                                            │
│ 如果 docParse 连续失败 3 次:                                                   │
│   node.status = FAILURE(3), retryNum = 3                                    │
│   retryNum >= 3 → 不可重试                                                   │
│   context.status = FAILURE(3)                                               │
│   dataset.inited = FAILURE                                                  │
│   → 发送飞书通知给用户                                                         │
│                                                                            │
│ 如果用户中途点击"暂停":                                                        │
│   context.status = PAUSING(9)                                               │
│   当前正在执行的节点完成后:                                                     │
│   context.status = PAUSE(7)                                                 │
│   Job 下次检查到 PAUSE → 跳过                                                 │
│                                                                            │
│ 如果用户点击"终止":                                                            │
│   context.status = TERMINATE(6)                                             │
│   所有正在执行的节点: 检查到 TERMINATE → 不修改状态，直接 return                   │
│   未开始的节点: node.status 改为 TERMINATE(6)                                 │
└────────────────────────────────────────────────────────────────────────────┘
```

### **时间线总览**

```Plain Text
T+0s    用户上传文件 + URL → agentflow 创建 Dataset/Context/Node 记录
T+0s    Context(INIT), Nodes(INIT) 写入 MySQL

T+0~30s 等待 Job 下一轮轮询

T+30s   Job 第1轮: startHandle → COMPLETE → context(TRANS)
T+60s   Job 第2轮: docParse → SENT(异步) → af-rag-server 解析 PDF
T+80s   docParse 回调 → COMPLETE → context(TRANS)
T+90s   Job 第3轮: textChunk → COMPLETE → context(TRANS)
T+120s  Job 第4轮: summary + keyword → COMPLETE → context(TRANS)
T+150s  Job 第5轮: docStorage → Embedding → ES/Milvus → COMPLETE
        → isLastNode=true → context(SUCCESS) → dataset(COMPLETE)

T+150s  知识库就绪，用户可检索
```

### **面试要点总结**

---

## **Q12 附录: af\-rag\-server 在全链路中的角色**

### **一句话定位**

> af\-rag\-server 是 **AI 能力提供者**。agentflow\-server 和 dataflow\-server 是"编排者"（决定什么节点、什么顺序），af\-rag\-server 是"执行者"（具体完成 AI 相关的解析、分块、摘要等任务）。三者通过 **HTTP 调用 \+ 回调** 协作。
> 
> 

### **各环节参与情况**

```Plain Text
┌──────────────────────────────────────────────────────────────┐
│ STEP 3 (URL 网页解析)                                         │
│                                                              │
│ agentflow 创建 Context 时，对 URL 类型的输入:                   │
│   调用 af-rag-server: POST /parse/parse_url                   │
│   或 POST /parse/crawl_fire (FireCrawl)                      │
│   或 POST /parse/crawl_jina_reader (Jina Reader)             │
│                                                              │
│ af-rag-server 做什么:                                         │
│   - 使用 requests/beautifulsoup4 爬取网页内容                  │
│   - 或调用 FireCrawl/Jina Reader API                         │
│   - 提取正文文本 → 写入 S3                                    │
│   - 返回文本内容给 agentflow                                   │
│                                                              │
│ 使用的 af-rag-server 组件:                                     │
│   src/web_parse/parse_manager.py                             │
│   src/web_parse/ → CrawlJinaReader, CrawlFire, ParseWeb      │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ STEP 7 (docParse — 文档解析)                                   │
│                                                              │
│ dataflow 的 DocParseService:                                  │
│   调用 af-rag-server: POST /rag_algorithm/parser              │
│   {                                                          │
│     "file_download_url": "s3://.../产品规格书.pdf",            │
│     "callback_url": "https://dataflow/callback/docparse",    │
│     "method": "general",                                     │
│     "parameters": {...}                                      │
│   }                                                          │
│                                                              │
│ af-rag-server 做什么:                                         │
│   1. 从 S3 下载文件                                            │
│   2. 根据文件类型选择解析器:                                    │
│      PDF  → PyMuPDF (fitz)                                   │
│      DOCX → python-docx                                      │
│      XLSX → openpyxl                                         │
│      TXT  → 直接读取                                          │
│   3. 提取文本/表格/图片                                         │
│   4. 结果写入 S3 → output.json                                │
│   5. HTTP POST 回调 dataflow: /callback/docparse              │
│                                                              │
│ 使用的 af-rag-server 组件:                                     │
│   src/rag_algorithm/handlers.py → Parser                     │
│   src/component/parser/ (解析器实现)                           │
│   PyMuPDF, python-docx, openpyxl, docx2txt                    │
│                                                              │
│ ★ 这是异步组件: 调用后立即返回，解析完成通过回调通知              │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ STEP 8 (textChunk — 文本分块)                                  │
│                                                              │
│ dataflow 的 TextChunkService:                                  │
│   调用 af-rag-server: POST /rag_algorithm/splitter            │
│   {                                                          │
│     "text": "...",                                           │
│     "chunk_size": 512,                                       │
│     "chunk_overlap": 50                                      │
│   }                                                          │
│                                                              │
│ af-rag-server 做什么:                                         │
│   1. 读取解析后的文本                                          │
│   2. 按 chunk_size 切分，重叠 chunk_overlap                    │
│   3. 返回 chunk 列表 [{chunkId, chunkText, chunkInfo}]        │
│                                                              │
│ 使用的 af-rag-server 组件:                                     │
│   src/rag_algorithm/handlers.py → Splitter                   │
│   src/component/chunk/ (分块策略)                              │
│   src/component/chunk_split/ (切分实现)                        │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ STEP 9 (summary + keyword — 摘要 + 关键词)                     │
│                                                              │
│ dataflow 的 SummaryExtractService:                             │
│   调用 af-rag-server: POST /rag_algorithm/summary             │
│   {                                                          │
│     "chunks": [...]                                          │
│   }                                                          │
│                                                              │
│ af-rag-server 做什么:                                         │
│   1. 对每个 chunk 调用 LLM 生成摘要                             │
│   2. 返回摘要列表                                              │
│                                                              │
│ dataflow 的 KeywordExtractService:                             │
│   调用 af-rag-server: POST /rag_algorithm/keywords            │
│                                                              │
│ af-rag-server 做什么:                                         │
│   1. jieba 分词提取中文关键词                                   │
│   2. 可选: LLM 辅助关键词提取                                   │
│   3. 返回关键词列表                                            │
│                                                              │
│ 使用的 af-rag-server 组件:                                     │
│   src/rag_algorithm/handlers.py → SummaryResource            │
│   src/rag_algorithm/handlers.py → KeywordResource            │
│   jieba, langchain-openai                                    │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ STEP 10 (docStorage — 向量化 + 入库)                           │
│                                                              │
│ ★ af-rag-server 不直接参与 Embedding。                         │
│ dataflow 的 DocStorageService 直接调用模型服务的 API:           │
│   POST {modelUrl}/embeddings                                 │
│   → 768 维向量                                                │
│   然后直接写入 Milvus (PyMilvus) 和 ES (elasticsearch-py)      │
│                                                              │
│ 为什么不由 af-rag-server 代理 Embedding?                       │
│   - Embedding 是高频、大批量的操作                              │
│   - 直接调用模型 API 减少中间跳转的延迟                          │
│   - dataflow 已经集成了模型服务的调用能力                        │
│                                                              │
│ af-rag-server 的向量能力在另一个场景:                            │
│   /vector/memory/*  → Mem0 的长期记忆向量化                    │
│   /rag_algorithm/ragas_evaluate → RAGAS 评估                  │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ STEP 11 (Agent 检索知识库)                                     │
│                                                              │
│ 检索在 Java 端 (agentflow) 完成:                               │
│   DatasetSearchServiceImpl → KnowledgeSearchService           │
│   → DenseVectorService → ES + Milvus                         │
│                                                              │
│ ★ af-rag-server 不直接参与检索。但参与 Agent 推理:              │
│   agentflow 的 AutoAgent → 调用 af-rag-server Agent:          │
│   POST /api/auto-agent/run (FastAPI, 端口 10001)             │
│   → AutoAgentExecutor → ADKExecutor                          │
│   → PlanReAct 循环 → 决定调用工具 (包括 KnowledgeTool)         │
│                                                              │
│ 当 Agent 调用 KnowledgeTool 时:                                │
│   → HTTP POST agentflow-server/api/v1/tools/run              │
│   → type="dataset" → Java 端执行 ES+Milvus 检索               │
│   → 返回结果给 Python Agent → 拼入 Prompt → 继续推理            │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ 其他场景中 af-rag-server 的参与                                 │
│                                                              │
│ Python 代码执行 (PluginPython):                                │
│   agentflow → POST af-rag-server/user/{userId}/package/      │
│               {projectId}/execution                          │
│   → Python 沙箱执行代码 → 返回结果                              │
│                                                              │
│ 视频解析:                                                      │
│   POST /rag_algorithm/video → 视频解析                        │
│   POST /rag_algorithm/video_callback → ASR 回调               │
│                                                              │
│ GraphRAG:                                                     │
│   POST /graphrag/extract → 知识图谱提取                        │
│   POST /graphrag/query/global/map → 图谱查询                  │
│                                                              │
│ RAGAS 评估:                                                    │
│   POST /rag_algorithm/ragas_evaluate → RAG 质量评估            │
│                                                              │
│ JSON 修复:                                                     │
│   POST /rag_algorithm/json_repair → 修复损坏的 JSON            │
│                                                              │
│ 飞书文档解析:                                                   │
│   POST /parse/feishu/token → 获取飞书 Token                   │
│   POST /parse/feishu/doc-info → 获取文档信息                   │
│   POST /parse/feishu/doc-detail → 获取文档内容                 │
│                                                              │
│ 记忆向量化:                                                     │
│   /vector/memory/insert → Mem0 add()                         │
│   /vector/memory/query  → Mem0 search()                      │
│   /vector/memory/delete → Mem0 delete()                       │
│   /vector/memory/update → Mem0 update()                       │
└──────────────────────────────────────────────────────────────┘
```

### **af\-rag\-server 参与环节汇总**

```Plain Text
STEP 1 ─── agentflow ─── 创建知识库                   ❌ 不参与
STEP 2 ─── agentflow ─── 上传文件                     ❌ 不参与
STEP 3 ─── agentflow ─── URL 网页解析                  ✅ 参与 (webParser)
STEP 4 ─── dataflow ─── Job 定时轮询                  ❌ 不参与
STEP 5 ─── dataflow ─── 获取待执行节点                 ❌ 不参与
STEP 6 ─── dataflow ─── startHandle 节点              ❌ 不参与
STEP 7 ─── dataflow ─── docParse (文档解析)            ✅ 核心 (PyMuPDF解析)
STEP 8 ─── dataflow ─── textChunk (文本分块)           ✅ 核心 (文本切分)
STEP 9 ─── dataflow ─── summary (LLM摘要)             ✅ 参与 (调用LLM)
STEP 9 ─── dataflow ─── keyword (关键词提取)           ✅ 参与 (jieba分词)
STEP 10─── dataflow ─── docStorage (入库)             ❌ 不参与 (dataflow直连ES/Milvus)
STEP 11─── agentflow── 检索知识库                      ⚡ Agent推理参与 (检索不参与)
STEP 12─── 异常处理                                   ❌ 不参与
```

### **三个项目的分工总结**

```Plain Text
┌──────────────────────────────────────────────────────────────┐
│  agentflow-server (Java)                                      │
│  角色: 业务编排者                                              │
│  做什么:                                                      │
│    - 知识库/数据集 CRUD                                        │
│    - 创建 Context 和 Node 记录 (触发 dataflow)                 │
│    - Agent 对话/检索的工作流编排                                │
│    - 工具调用统一网关 /api/v1/tools/run                        │
│                                                              │
│  与 af-rag-server 的交互:                                     │
│    - URL 解析: 上传 URL 时调用 webParser                        │
│    - Agent 推理: 调用 /api/auto-agent/run                     │
│    - 工具执行: Agent 内部通过 /api/v1/tools/run 再调到 agentflow │
└──────────────────────────────────────────────────────────────┘
                              │
                              │ HTTP (Python Agent 推理)
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  af-rag-server (Python)                                       │
│  角色: AI 能力提供者                                          │
│  做什么:                                                      │
│    - 文档解析 (PyMuPDF/python-docx/openpyxl)                  │
│    - 文本分块 (chunk/splitter)                                │
│    - LLM 摘要 (summary)                                       │
│    - 关键词提取 (jieba + LLM)                                  │
│    - 网页爬取 (beautifulsoup4/FireCrawl/Jina)                 │
│    - 视频解析 + ASR                                            │
│    - Python 代码沙箱执行                                       │
│    - 记忆向量化 (Mem0)                                         │
│    - Agent 推理 (Google ADK / LangGraph)                      │
│    - GraphRAG 知识图谱                                         │
│    - RAGAS 评估                                                │
└──────────────────────────────────────────────────────────────┘
                              │
                              │ HTTP (文档解析/分块/摘要/关键词)
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  dataflow-server (Java)                                       │
│  角色: 数据处理引擎                                            │
│  做什么:                                                      │
│    - Job 定时轮询 Context/Node                                 │
│    - 调用 af-rag-server 进行文档处理                            │
│    - 直接调用模型 API 进行 Embedding                            │
│    - 写入 ES + Milvus                                         │
│    - 重试/超时/状态管理                                        │
│    - Argo 大文件编排                                           │
│                                                              │
│  与 af-rag-server 的交互:                                     │
│    - docParse → POST /rag_algorithm/parser                   │
│    - textChunk → POST /rag_algorithm/splitter                │
│    - summary → POST /rag_algorithm/summary                   │
│    - keyword → POST /rag_algorithm/keywords                  │
│    - 视频解析 → POST /rag_algorithm/video                     │
│    - 代码执行 → POST /rag_algorithm/code_runner               │
└──────────────────────────────────────────────────────────────┘
```



