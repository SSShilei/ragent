# Agent 开发面试源码 Q\&A

> 聚焦于"如果面试官问你怎么实现的，你能直接说到源码行号和文件名"
> 
> 



---

## **Q1: ReAct 循环具体怎么实现的？源码在哪？**

### **回答**

**入口**: `af-rag-server/agent/auto_agent/framework_adapter/adk/executor.py` 第 128 行

```Python
# ADKExecutor.execute() —— 核心执行循环
async for event in runner.run_async(
    user_id=self.user.userid,
    session_id=self.chat_id,
    new_message=new_message,
    state_delta={...},
):
    # ★ 每个 event 是 ReAct 循环的一步 ★
    event = self.fill_custom_metadata(event, idx)
    # event.parts = [Part(text="/*PLANNING*/ 1.获取数据...")]
    # event.parts = [Part(function_call=FunctionCall(...))]
    # event.parts = [Part(text="/*REASONING*/ 数据已获取...")]
    # event.parts = [Part(text="/*FINAL_ANSWER*/ 答案是...")]
    yield f"event: {event.custom_metadata.get('type')}\ndata: {event.model_dump_json()}\n\n"
```

**循环控制**: `agent/apps/multi_agent/api.py` 第 126 行

```Python
run_config = RunConfig(
    max_llm_calls=20,  # ★ 循环上限: 最多 20 轮 LLM 调用
)
```

**Planner 实现**: `agent/auto_agent/framework_adapter/adk/custom_planner.py` 第 12 行

```Python
class CustomPlanner(PlanReActPlanner):
    def _build_nl_planner_instruction(self) -> str:
        # 返回给 LLM 的指令: 必须按 PLANNING → ACTION → REASONING → FINAL_ANSWER 输出
        # 4 个 TAG: PLANNING_TAG, ACTION_TAG, REASONING_TAG, FINAL_ANSWER_TAG

    def _handle_non_function_call_parts(self, response_part, preserved_parts):
        # ★ 关键: 只保留 PLANNING 部分展示给用户，隐藏 ACTION 和 REASONING ★
        if response_part.text and FINAL_ANSWER_TAG in response_part.text:
            reasoning_text, final_answer_text = self._split_by_last_pattern(...)
            if reasoning_text:
                reasoning_text = self._only_keep_plan(reasoning_text)  # 只保留规划

    def _only_keep_plan(self, text):
        # 去掉 ACTION 和 REASONING 标签
        if ACTION_TAG in text:     text = text.split(ACTION_TAG, 1)[0]
        if REASONING_TAG in text:  text = text.split(REASONING_TAG, 1)[0]
        if FINAL_ANSWER_TAG in text: text = text.split(FINAL_ANSWER_TAG, 1)[0]
        return text
```

**面试追问**: CustomPlanner 为什么要只保留 PLANNING？

> 前端展示给用户的"思考过程"只用看计划，ACTION（工具调用参数）和 REASONING（中间推理）可能包含敏感数据或冗余信息，隐藏它们让 UI 更干净。
> 
> 

---

## **Q2: 多 Agent 的路由是怎么做的？**

### **回答**

**路由定义**: `agent/agents/intent_agent/agent.py` 第 79 行

```Python
root_agent = CustomLlmAgent(
    name=AGENT_INTENT_AGENT,
    model=ModelFactory.create_model(use_custom_llm_model=True),
    description="意图判定智能体",
    sub_agents=[                           # ★ 5 个子 Agent
        metadata_agent,
        strategy_agent,
        strategy_view_agent,
        direct_analysis_agent,
        run_data_agent,
    ],
    instruction=get_instruction(),         # ★ Prompt 驱动路由
    before_agent_callback=before_agent_callback,
    before_model_callback=before_model_callback,
)

# ★ 核心: Prompt 定义路由规则
# agent/agents/intent_agent/prompt.py 第 4-86 行
```

**路由 Prompt 核心片段** \(`intent_agent/prompt.py`\):

```Python
"""
## 分发规则

### 生成数据过滤条件 → `run_data_agent`
触发条件（需同时满足）：
1. 检查【基础数据】中的`label`值，必须完全等于 "RUN_CREATE"
2. 用户的输入意图需要数据进行分析类

### 策略查看 → `strategy_view_agent`
精确匹配词：`查看`、`显示`、`列出`、`展示`、`浏览`

### 通用策略处理 → `strategy_agent`
触发条件：所有其他业务相关任务

### 决策优先级
社交互动 > 元数据框架 > 策略查看 > 用户分析 > 通用策略

严禁行为：
- 禁止返回JSON格式数据
- 禁止基于上下文直接生成业务回答
- 所有业务问题必须通过 `transfer_to_agent` 分发
"""
```

**显式分支（不走 LLM）** \(`intent_agent/explicit_branch.py`\):

```Python
async def before_agent_callback(callback_context):
    request_params = callback_context.state.get("request_params", {})
    label = request_params.get("metadata", {}).get("label", "")

    # ★ 策略发布检查: 代码逻辑判断，不走 LLM ★
    content = await check_strategy_publish(callback_context)
    if content:
        return content  # 直接返回结果，跳过 LLM 调用

    # ★ 策略使用检查 ★
    content = await check_strategy_use(callback_context)
    if content:
        return content

    return None  # None 表示继续走 LLM 路由
```

**面试追问**: 为什么不全部用 LLM 路由？

> 明确业务分支（如检查策略是否已发布）用 LLM 是不可靠的（幻觉风险）。`before_agent_callback` 在 LLM 调用**之前**执行，确保明确逻辑直接命中，节省一次 LLM 调用。
> 
> 

---

## **Q3: Agent 的工具怎么注册？LLM 怎么知道有哪些工具？**

### **回答**

**工具定义存储**: `agentflow-server/.../AutoAgent.java` — `tools` 字段 \(MySQL JSON 列\)

```SQL
-- AutoAgent 表 tools 字段 (JSON):
{
    "pluginInfoList": [
        {"id":"plugin_001","name":"数据查询","description":"...","parameters":{...}}
    ],
    "mcpInfoList": [
        {"id":"mcp_abc__sql_query","name":"SQL查询","description":"..."}
    ],
    "workflowInfoList": [...],
    "agentInfoList": [...]
}
```

**Python 端注册**: `af-rag-server/agent/auto_agent/framework_adapter/adk/executor.py` 第 270 行

```Python
@property
def tools(self) -> list[BaseTool]:
    """生成工具列表 —— 为 4 种类型分别创建 CommonTool"""
    tools = []

    # 知识库工具 (KnowledgeTool): 独立类型
    if knowledge := self.agent_meta.knowledge:
        for item in knowledge.knowledgeInfoList or []:
            tools.append(KnowledgeTool(data=item, ...))

    # 其余 4 种工具类型 (CommonTool)
    if _tools := self.agent_meta.tools:
        if _tools.pluginInfoList:
            for plugin in _tools.pluginInfoList:
                tools.append(CommonTool(plugin, tool_type="plugin", ...))
        if _tools.mcpInfoList:
            for _mcp in _tools.mcpInfoList:
                tools.append(CommonTool(_mcp, tool_type="mcp", ...))
        if _tools.workflowInfoList:
            for workflow in _tools.workflowInfoList:
                tools.append(CommonTool(workflow, tool_type="workflow", ...))
        if _tools.agentInfoList:
            for _agent in _tools.agentInfoList:
                tools.append(CommonTool(_agent, tool_type="autoAgent", ...))
    return tools
```

**工具声明生成**: `agent/auto_agent/framework_adapter/adk/tool.py` 第 113\-139 行

```Python
class CommonTool(BaseTool):
    def _get_declaration(self) -> Optional[FunctionDeclaration]:
        desc = self.description

        # ★ autoAgent/workflow 类型自动追加 question 入参 ★
        if self.tool_type in ["autoAgent", "workflow"]:
            self.data.parameters.properties["question"] = {
                "description": "用户输入参数",
                "type": "STRING"
            }
            self.data.parameters.required.append("question")

        if self.data.parameters:
            desc += f"\nArgs:\n{self.data.parameters.model_dump_json()}"

        # ★ 这就是 LLM 看到的 FunctionDeclaration ★
        return FunctionDeclaration(name=self.name, description=desc)

class KnowledgeTool(BaseTool):
    def _get_declaration(self):
        # ★ 知识库工具有固定入参 question ★
        return FunctionDeclaration(
            name=self.name,
            description="知识库工具：" + self.description
                + "\nArgs:\n    question(str): 拿去知识库检索的问题"
        )
```

**工具 Schema 转换 \(Java 端\)**: `agentflow-server/.../AutoAgentToolContext.java` 第 29\-40 行

```Java
// ★ 将前端定义的 ToolOriginalDTO 转换为 LLM 可理解的 FunctionDeclaration ★
public JsonSerializable convert(ToolOriginalDTO toolOriginalDTO) {
    AutoAgentToolBaseTool converter = findConverter(toolOriginalDTO.getType());
    converter.validate(toolOriginalDTO);
    return converter.tryInto(toolOriginalDTO);  // ★ 各工具自己实现 Schema 映射
}
```

---

## **Q4: 工具调用到底怎么执行的？全链路从 LLM 决定调用到返回结果**

### **回答**

```Plain Text
LLM 输出 function_call
    │
    ▼
Python: CommonTool.run_async()                          [tool.py:63]
    │
    │ POST /api/v1/tools/run                            [tool.py:83]
    │ {id, name, type, params, draftMode, agentId, chatId}
    ▼
Java: AutoAgentToolController.run()                     [AutoAgentToolController.java]
    │
    ▼
Java: AutoAgentToolContext.toolRun()                    [AutoAgentToolContext.java:48]
    │
    │ findConverter(type)                               [AutoAgentToolContext.java:60]
    │   └── toolSchemaList.stream().filter(supports(type)).findFirst()
    ▼
各工具实现类.callTool(toolCallDTO)
    ├── type="plugin"  → PluginTool.callTool()
    ├── type="mcp"     → McpTool.callTool()
    ├── type="workflow"→ WorkflowTool.callTool()
    ├── type="autoAgent"→ AutoAgentCallTool.callTool()
    └── type="dataset" → DatasetTool.callTool()
    │
    ▼
返回 JSON string                                    [AutoAgentToolContext.java:52]
    │
    ▼
Python: CommonTool.run_async() 返回                  [tool.py:96-111]
    │
    │ res["data"] → 处理 fileInfos                    [tool.py:102-108]
    │ tool_context.actions.skip_summarization = ...   [tool.py:98]
    ▼
ADK Runner 将 tool response 作为新的 user Content     [ADK 框架内部]
    │
    ▼
LLM 看到 tool response → 下一轮思考/生成最终回答
```

**Python 端关键代码** \(`tool.py` 第 63\-111 行\):

```Python
async def run_async(self, *, args, tool_context):
    url = f"{AGENT_FLOW_SERVER_URL}/api/v1/tools/run"
    data = {
        "id": self.data.id,
        "name": self.data.name,
        "type": self.tool_type,      # ★ 类型标识
        "params": args,               # ★ LLM 传入的参数
        "draftMode": self.draft_mode,
        "agentId": self.agent_id,
        "requestId": self.request_id,
        "chatId": self.chat_id,
    }
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(url, json=data, headers=self.headers)
        response.raise_for_status()

    res = response.json()
    if res["code"] != "10000":
        return fail(f"tool execute fail: {res['message']}")

    # ★ 跳过 LLM 总结 (如工具已经返回完整答案) ★
    tool_context.actions.skip_summarization = self.data.skipSummarization

    # ★ 处理文件生成 (多模态) ★
    if isinstance(data, dict) and data.get("fileInfos") and not data.get("answer"):
        names = "、".join(x.get("fileName") for x in data.get("fileInfos"))
        data["answer"] = f"已为您生成以下文件：{names}"

    return success(data)
```

---

## **Q5: Agent 的 session 和 state 怎么管理？**

### **回答**

**SessionService**: `af-rag-server/agent/services/google_adk/in_redis_session_service.py`

```Python
# ★ Redis 实现 ADK 的 SessionService 接口 ★
class InRedisSessionService:
    async def get_session(app_name, user_id, session_id): ...  # 读 Redis
    async def create_session(app_name, user_id, session_id): ...# 写 Redis
    async def append_event(session, event): ...                 # 追加 Event
    async def delete_session(app_name, user_id, session_id): ...# 删除 Session
```

**Session 初始化** \(`executor.py` 第 65\-96 行\):

```Python
async def _init_session(self):
    session = await session_service.get_session(...)

    # ★ 如果传了 chatHistory → 重建 Session ★
    if self.chat_history:
        if session:
            await session_service.delete_session(...)   # 删旧
        session = await session_service.create_session(...)  # 建新
        # ★ 将历史消息写入新的 Event ★
        for item in self.chat_history:
            event = Event(
                invocation_id=self.request_id,
                author=agent_name if item.obj == "AI" else "user",
                content=Content(parts=[Part.from_text(text=item.value)],
                                role="model" if item.obj == "AI" else "user"),
            )
            await session_service.append_event(session, event)

    return session
```

**State 字典跨 Agent 传递** \(`multi_agent/api.py` 第 139\-145 行\):

```Python
async for source_event in intent_runner.run_async(
    user_id=req.user_id,
    session_id=session_id,
    new_message=req.new_message,
    state_delta={
        "request_params": request_params,       # ★ 所有子 Agent 可读
        "metadata": req.metadata,               # ★ label 等元数据
        HEADER_NAME_ORG_CODE_OUT: org_code      # ★ 租户信息
    },
):
```

**State 在 IntentAgent 中读取** \(`intent_agent/agent.py` 第 24\-41 行\):

```Python
async def before_agent_callback(callback_context: CallbackContext):
    request_params = callback_context.state.get("request_params", {})
    label = request_params.get("metadata", {}).get("label", "")
    # ★ 根据 label 决定显式分支 ★
```

---

## **Q6: Agent 和 LLM 之间怎么通信的？上下文怎么传？**

### **回答**

**模型调用层**: `agent/services/google_adk/lite_llm.py` — CustomLiteLlm

```Python
# ★ 自定义 LiteLlm 封装，在 LLM 调用时注入租户 Header ★
class CustomLiteLlm(LiteLlm):
    # 每次 LLM API 调用时自动注入:
    #   header["orgCode"] = tenantId
```

**CustomLlmAgent 构建** \(`executor.py` 第 235\-250 行\):

```Python
@property
def agent(self) -> LlmAgent:
    agent = CustomLlmAgent(
        model=self.llm,            # CustomLiteLlm (LiteLLM → OpenAI API)
        name=self.agent_meta.name,
        description=self.agent_meta.intro,
        instruction=self.get_prompt,  # ★ Prompt 工厂函数 ★
        tools=self.tools,            # ★ 工具列表 ★
        after_tool_callback=self.after_tool_call,
        after_model_callback=self.after_model_callback,
        before_model_callback=self.before_model_callback,
    )
    return agent
```

**对话历史裁剪** \(`executor.py` 第 368\-388 行\):

```Python
async def before_model_callback(self, callback_context, llm_request):
    """★ 每次 LLM 调用前，裁剪对话历史 ★"""
    # 找到所有 user 角色消息的索引
    user_indices = [
        i for i, item in enumerate(llm_request.contents)
        if item.role == "user" and item.parts and item.parts[0].text
    ]
    # ★ 只保留最近 historyRound 轮 ★
    keep_turns = min(
        (self.agent_meta.modelInfo.historyRound or 3) + 1,
        len(user_indices)
    )
    start_user_idx = user_indices[-keep_turns]
    llm_request.contents = llm_request.contents[start_user_idx:]
```

**工具调用后处理** \(`executor.py` 第 390\-403 行\):

```Python
async def after_model_callback(self, callback_context, llm_response):
    """★ LLM 返回后，处理引用溯源 ★"""
    for part in llm_response.content.parts:
        self.fix_quote(part, callback_context.state)
    # fix_quote: 重新编号 quoteMark，生成 quoteList，
    # 过滤非知识库引用的假 quoteMark
```

---

## **Q7: streaming 端到端怎么实现的？**

### **回答**

**Python 端 SSE 推送** \(`app.py` 第 79\-129 行\):

```Python
@router.post("/api/auto-agent/run")
async def auto_agent_run(request, req: dict):
    async def event_generator(_req: dict):
        q = asyncio.Queue()

        # ★ 心跳: 每 10 秒发送空数据防止超时 ★
        async def heartbeat(q):
            while True:
                await asyncio.sleep(10)
                await q.put("data: \n\n")

        # ★ Agent 执行: 推入 Queue ★
        async def execute_autoagent(_req, q):
            executor = AutoAgentExecutor(req, user)
            async for res in executor.execute():
                await q.put(res)
            await q.put(None)  # None = 结束信号

        heartbeat_task = asyncio.create_task(heartbeat(q))
        agent_task = asyncio.create_task(execute_autoagent(_req, q))

        while True:
            msg = await q.get()
            if msg is None: break
            yield msg

    return StreamingResponse(
        event_generator(req),
        media_type="text/event-stream",  # ★ 标准 SSE 格式 ★
    )
```

**ADK Event → SSE 格式化** \(`executor.py` 第 128\-156 行\):

```Python
async for event in runner.run_async(...):
    event = self.fill_custom_metadata(event, idx)

    # ★ 缓冲一个 Event: 为了标记工具类型/头像 ★
    event_buffer.append(event)
    if len(event_buffer) > 1:
        pre_event = event_buffer.pop(0)
        # 如果当前 event 是 function_response，
        # 把 tool_type 和 avatar 回填到上一个 function_call event
        if pre_event.get_function_calls() and event.get_function_responses():
            pre_event.custom_metadata["type"] = event.custom_metadata.get("type")
            pre_event.custom_metadata["avatar"] = event.custom_metadata.get("avatar")
        yield f"event: {pre_event.custom_metadata.get('type')}\ndata: {pre_event.model_dump_json()}\n\n"

# ★ 最后一个 Event 标记 is_runner_final ★
final_event.custom_metadata["is_runner_final"] = True
```

**Java 端 WorkFlow SSE** \(`SSEUtils.java` 第 24\-79 行\):

```Java
// ★ SseEmitter Map: reqId → 全局频道 ★
private static final Map<String, SseEmitter> subscribeMap = new ConcurrentHashMap<>();

public static SseEmitter addSub(String reqId) {
    SseEmitter emitter = new SseEmitterUTF8(10 * 60 * 1000L);  // 10 分钟超时
    emitter.onTimeout(() -> closeSub(reqId));
    emitter.onCompletion(() -> closeSub(reqId));
    emitter.onError(e -> closeSub(reqId));
    subscribeMap.put(reqId, emitter);
    return emitter;
}

// ★ 推送事件: 前端通过 GET /api/v1/chat/stream/{reqId} 订阅 ★
public static void pubMsg(String reqId, String event, String msg) {
    SseEmitter emitter = subscribeMap.get(reqId);
    if (emitter != null) {
        emitter.send(event().name(event).data(msg));
    }
}
```

**WorkFlowEngine 中推送** \(`WorkFlowEngine.java` 第 154 行\):

```Java
// ★ 节点状态通知 ★
SSEUtils.pubMsg(reqId, MODULESTATUS, {status:1});  // 运行中
SSEUtils.pubMsg(reqId, MODULESTATUS, {status:2});  // 完成
SSEUtils.pubMsg(reqId, MODULESTATUS, {status:3});  // 异常

// ★ LLM token 流式推送 ★
SSEUtils.pubMsg(reqId, ANSWER, {answer: "电池...", reasoningContent: "..."});
```

---

## **Q8: Agent 开发时怎么调试？草稿模式是什么？**

### **回答**

**草稿模式**: `AutoAgentRequest.draftMode = true`

```Python
# agent/auto_agent/model.py 第 108 行
class AutoAgentRequest(BaseModel):
    draftMode: bool = False  # true: 调试(false=对话)

# agent/auto_agent/framework_adapter/adk/tool.py
# ★ 工具调用时透传 draftMode ★
data = {..., "draftMode": self.draft_mode, ...}
# → Java 端根据 draftMode 执行不同的逻辑
# 例如: 草稿模式不走真实支付/不发送消息/不更新数据库
```

**Agent 发布的覆盖率检测** \(`AutoAgentServiceImpl.java`\):

```Java
// ★ 发布前循环检测: DFS 检查 Agent 嵌套环 ★
private boolean hasCycleHelper(Set<String> visited, ..., AgentDTO agentDTO) {
    if (agentDTO.id in visited) {
        return true;  // ★ 发现环！阻止发布 ★
    }
    visited.add(agentDTO.id);
    for (subAgent in agentDTO.tools.agentInfoList) {
        childAgent = findByAgentId(subAgent.id);
        if (hasCycleHelper(visited, ..., childAgent)) {
            return true;
        }
    }
    visited.remove(agentDTO.id);  // ★ 回溯 ★
    return false;
}
```

**Langfuse 追踪** \(`agent/auto_agent/executor.py` 第 18\-26 行\):

```Python
class AutoAgentExecutor:
    async def execute(self):
        if langfuse_instance:
            # ★ 以 requestId 作为 Trace ID ★
            trace_context = TraceContext(trace_id=self.data.requestId.hex)
            with langfuse_instance.start_as_current_span(
                name=self.data.agentMeta.name, trace_context=trace_context):
                async for event in self._execute_impl():
                    yield event

# agent/auto_agent/observability.py:
# ★ fill_langfuse 装饰器: 工具调用自动记录到 Langfuse ★
@fill_langfuse
async def run_async(self, *, args, tool_context):
    ...
```

---

## **Q9: Agent 的 Prompt 到底怎么拼接的？源码在哪？**

### **回答**

**Python 端 Prompt 工厂函数** \(`executor.py` 第 211\-232 行\):

```Python
def get_prompt(self, context=None) -> str:
    # ===== 1) 基础 Prompt + 替换变量 =====
    text = self._replace_variable(self.agent_meta.promptInfo)
    # {{variable}} → 实际值

    # ===== 2) 临时知识库 (RAG 召回结果) =====
    if self.rag_options and self.rag_options.quoteQA:
        rag = "\n".join([x.model_dump_json() for x in self.rag_options.quoteQA])
        text += f"""
【背景知识】
如果背景知识中存在与问题直接相关的信息，请优先且仅使用这些信息进行总结或回答
以下是背景知识
{rag}"""

    # ===== 3) 长期记忆 (Mem0) =====
    if self.agent_meta.longTermMemory:
        text += f"\n\n【长期记忆】以下是和问题相关的记忆\n{self.agent_meta.longTermMemory}"

    # ===== 4) 溯源提示词 =====
    if self.agent_meta.knowledge and self.agent_meta.knowledge.showSource:
        text += "\n" + quote_prompt  # 要求 LLM 输出 quoteMark 格式

    # ===== 5) 多模态兜底 =====
    text += """
## 重要
当用户想要生成一个多模态内容，但你无法提供多模态文件时，直接仅回复下面文本内容：
很遗憾，当前我没有直接生成{图片/视频/音频/文件}的能力和对应的工具调用。
不过你可以调用多模态模型和工具进行支持。"""

    return text
```

**变量替换** \(`executor.py` 第 203\-208 行\):

```Python
def _replace_variable(self, s: str) -> str:
    if not self.variables:
        return s
    for k, v in self.variables.items():
        s = s.replace("{{" + str(k) + "}}", str(v))  # ★ 简单字符串替换 ★
    return s
```

**Java 端 Prompt 拼接** \(`AISummaryService.java` 第 125\-178 行\):

```Java
// chatNode 内 Prompt 组装顺序:
// [0] systemPrompt + "\n[history]\n" + lastSummary
//     ↑ 角色定义       ↑ 历史摘要
// [1..N] chatHistories  (从 ChatItem 表加载)
// [N+1] userChatInput + "\n参考资料:\n" + quoteQA
//       ↑ 当前问题       ↑ 知识库检索结果

// 超长检测:
//   1. rawTextLen < maxTokens * 0.5 → 跳过
//   2. ChatContextFilter.filterMessages() → 截断 或 触发摘要
//   3. 摘要 → 调用 LLM 生成摘要 → 替换原始 history
//   4. 摘要后仍超限 → 抛 CHAT_OVER_TOKEN_LIMIT_WITH_SUMMARY
```

---

## **Q10: Agent 的记忆功能怎么实现？长短期记忆有什么区别？**

### **回答**

**短期记忆 \(Session\)**: 同一个 `chatId` 的对话历史，存 Redis

```Python
# executor.py _init_session()
# Session = 当前 chatId 的所有 Event
# 每次请求从 Redis 恢复 → 交给 RunConfig 管理
```

**长期记忆 \(Mem0\)**: 跨对话的向量化记忆

```Python
# af-rag-server/src/memory/memory_vector.py
# POST /vector/memory/insert
# → mem0.add("用户偏好电池续航>5000mAh", user_id=..., agent_id=...)

# POST /vector/memory/query
# → mem0.search("电池偏好", user_id=..., agent_id=..., limit=5)

# ★ 注入 Prompt ★
# executor.py get_prompt():
# if agent_meta.longTermMemory:
#     text += f"\n【长期记忆】{longTermMemory}"
```

**记忆字段配置** \(Java 端\):

```Java
// TeamMemoryFieldConfig 表:
//   字段名: "user_name", 字段类型: "string", status: 1(启用)
//
// AgentMemoryFieldRelation 表:
//   agentId → [fieldConfigId1, fieldConfigId2, ...]
//   表示这个 Agent 开启了哪些记忆字段
```

---

## **Q11: 工具调用失败怎么办？死循环怎么防？**

### **回答**

**工具调用失败** \(`tool.py` 第 83\-96 行\):

```Python
async def run_async(self, *, args, tool_context):
    try:
        response = await client.post(url, json=data, headers=self.headers)
        response.raise_for_status()
    except Exception as e:
        # ★ 返回失败信息 → LLM 看到 fail() → 自己决定下一步 ★
        return fail(f"call tool-execute fail: {e}")

    res = response.json()
    if res["code"] != "10000":
        return fail(f"tool execute fail: {res['message']}")
    # fail() = {"result": "null", "error_message": "..."}
    # LLM 看到 error_message → 尝试其他方法 / 告知用户失败原因
```

**循环防御**:

```Python
# ★ 第 1 层: 硬上限 ★
# api.py 第 126 行
run_config = RunConfig(max_llm_calls=20)

# ★ 第 2 层: 循环检测 (发布前) ★
# AutoAgentServiceImpl.java hasCycleHelper()
# DFS 检测 Agent → Agent 嵌套环

# ★ 第 3 层: 超时保护 ★
# executor.py 第 146-151 行
except Exception as e:
    if isinstance(e, litellm.exceptions.Timeout):
        yield event: {"error_message": "agent 处理超时"}
```

**心跳防超时** \(`app.py` 第 107\-109 行\):

```Python
async def heartbeat(q):
    while True:
        await asyncio.sleep(10)          # ★ 每 10 秒 ★
        await q.put("data: \n\n")        # ★ 空数据行 ★
# 防止 Nginx/网关因长时间无数据而断开 SSE 连接
```

---

## **Q12: Agent 文件上传和支持多模态怎么实现？**

### **回答**

**Agent 级开关**: `AutoAgent.enableUploadFiles`

```Plain Text
AutoAgent.enableUploadFiles = true
    → 前端允许上传文件输入
    → Agent 可接收图片/音频/视频

AutoAgent.enableUploadFiles = false
    → 仅文本输入
```

**文件信息透传** \(`executor.py` 第 161\-191 行\):

```Python
def fill_custom_metadata(self, event, idx):
    if event.content and event.content.parts:
        for part in event.content.parts:
            if part.function_response:
                result = part.function_response.response.get("result")
                result = json.loads(result)
                if isinstance(result, dict):
                    # ★ 从工具返回中提取文件信息 ★
                    if file_infos := result.pop("fileInfos", None):
                        event.custom_metadata["fileInfos"] = file_infos
                # 去掉 result 中的 fileInfos (不重复序列化)
                part.function_response.response["result"] = json.dumps(result)
```

**多模态 Type 标记** \(`executor.py` 第 164\-178 行\):

```Python
# 根据 function_response 标记事件类型
if part.function_response:
    event.custom_metadata["type"] = part.function_response.response.get("tool_type")
    event.custom_metadata["avatar"] = part.function_response.response.get("avatar")
# tool_type: "plugin"/"mcp"/"workflow"/"autoAgent"/"dataset"
# avatar: 工具在 UI 上的图标 URL
```

---

## **Q13: 怎么给 Agent 加一个新工具？完整步骤**

### **回答**

**Step 1**: Java 端定义工具 Schema \(如果需要的工具后端不存在\)

```Java
// 实现 AutoAgentToolBaseTool 接口
@Component
public class MyNewTool implements AutoAgentToolBaseTool {
    @Override
    public boolean supports(String type) {
        return "myTool".equals(type);
    }

    @Override
    public JsonSerializable tryInto(ToolOriginalDTO dto) {
        // ★ 将前端配置转为 LLM FunctionDeclaration ★
        return new FunctionDeclarationVO(dto.getName(), dto.getDescription(), ...);
    }

    @Override
    public String callTool(ToolCallDTO dto) {
        // ★ 实际执行工具 ★
        // 调用真正的服务/API/数据库...
        return result;
    }
}
```

**Step 2**: 在 AutoAgent 中配置工具

```JSON
// AutoAgent 表 tools JSON 字段添加:
{
  "pluginInfoList": [
    ...
    {"id":"my_tool_001", "name":"我的新工具", "description":"执行XXX操作"}
  ]
}
```

**Step 3**: 发布 → Python Agent 自动注册

```Plain Text
发布 Agent
  → AutoAgent.tools 写入 MySQL
  → Agent 下次运行时:
      ADKExecutor.tools (property)
        → tools.pluginInfoList 遍历
        → CommonTool(tool, tool_type="plugin", ...) 创建
        → _get_declaration() 生成 FunctionDeclaration
        → ADK LlmAgent 注册工具
  → LLM 看到新工具 → 可在 PlanReAct 中调用
```

---

## **快速索引：关键源码位置速查**



