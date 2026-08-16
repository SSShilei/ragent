# 多智能体架构面试 Q&A

> 结合 PowerAgent（Google ADK 主力 + LangGraph 降级）和 Claude Code swarm 的多 Agent 架构实践。
> 覆盖框架选择、上下文隔离、任务分配、结果合并、成本控制五大主题。
> 锚点格式 `<a id="qN">`，VS Code / IDEA：Ctrl+单击跳转。

---

<a id="q1"></a>
## 一、Agent 框架选择

### Q1: LangGraph 和 ADK 分别是什么？核心区别？

**LangGraph** = LangChain 的**图式 Agent 编排框架**，把 Agent 流程建模成"图"（节点是步骤、边是流转）：

```python
agent = create_react_agent(
    model=ChatOpenAI(...),
    tools=tools,
    prompt=prompt,
    checkpointer=InMemorySaver(),  # checkpoint
)
```

核心思想：**"Agent 流程 = 图"**，用 StateGraph 定义节点和边，灵活但要多 Agent 手动建子图。

**ADK** = Google 的**声明式 Agent 运行时**，Agent 是对象，框架负责执行：

```python
runner = Runner(
    app_name=...,
    agent=self.agent,              # CustomLlmAgent（含 sub_agents）
    session_service=session_service,
    memory_service=memory_service,
)
```

核心思想：**"Agent = 声明式对象"**，多 Agent 是 `sub_agents` 嵌套，不是画图。

| 维度 | LangGraph | ADK |
|---|---|---|
| 编程模型 | 图（StateGraph，节点+边） | 声明式 Agent（instruction+tools+sub_agents） |
| 多 Agent | 手动建子图 | 原生 `sub_agents` + `transfer_to_agent` |
| ReAct | 自己拼 create_react_agent | 内置 PlanReActPlanner |
| Session/Memory | 自己集成 | 内置抽象，换 Redis 只实现接口 |
| 事件/流式 | chunk 需自行转 SSE | Event 模型天然适配 SSE |
| 灵活度 | 高（任意图） | 中（框架约束结构） |

---

### Q2: 为什么选 ADK 而不是 LangGraph？

1. **层次化多 Agent 是刚需**：IntentAgent 下有 5 个子 Agent，StrategyAgent 内部还有子 Agent。ADK 的 `sub_agents` 声明式配置一行搞定，LangGraph 要手动建子图嵌套。

2. **PlanReAct 开箱即用**：ADK 内置 PlanReActPlanner，只需继承改中文指令；LangGraph 要自己拼 create_react_agent。

3. **Session/Memory 抽象清晰**：`InRedisSessionService` / `InRedisMemoryService` 只需实现接口，不用改 ADK 核心代码；LangGraph 的 checkpointer 要自己接 Redis。

4. **保留 LangGraph 作为降级方案**：通过 `framework` 参数切换，应对 ADK 不支持的场景。

---

### Q3: ADK 内部有什么好的实现？（6 个）

#### 实现 1：Runner 依赖注入式组装

```python
runner = Runner(
    agent=self.agent,                    # Agent 定义
    artifact_service=artifact_service,   # 产物服务
    session_service=session_service,     # 会话服务（可替换）
    memory_service=memory_service,       # 记忆服务（可替换）
)
```

每个服务都是接口，PowerAgent 把 Session/Memory 换成 Redis 实现，只实现接口没改 ADK 一行代码。依赖倒置的典范。

#### 实现 2：Session 可控恢复

```python
async def _init_session(self):
    session = await session_service.get_session(**kwargs)
    if not self.chat_history:        # 没传历史 → 用 Redis 里的
        return session or await session_service.create_session(**kwargs)
    if session:                       # 传了历史 → 删旧重建
        await session_service.delete_session(**kwargs)
    session = await session_service.create_session(**kwargs)
    for item in self.chat_history:    # 逐条 append 成 Event
        ...
```

策略"以客户端传入的 chat_history 为准"——前端传了历史就重建，没传就用 Redis 的，解决客户端/服务端状态不一致。

#### 实现 3：四个回调钩子（模板方法）

```python
agent = CustomLlmAgent(
    before_agent_callback=...,   # Agent 推理前（显式分支检查）
    before_model_callback=...,   # 每次 LLM 调用前（裁剪历史）
    after_tool_callback=...,     # 工具调用后（记录引用）
    after_model_callback=...,    # LLM 回答后（fix_quote）
)
```

框架定义流程，业务通过钩子注入，不侵入核心代码。

#### 实现 4：PlanReActPlanner 结构/指令分离 + REPLANNING

```python
class CustomPlanner(PlanReActPlanner):
    def _build_nl_planner_instruction(self) -> str:
        # 五个 tag：PLANNING / ACTION / REASONING / REPLANNING / FINAL_ANSWER
        # 继承只改指令文本，循环逻辑复用 ADK 的
```

关键是 **REPLANNING_TAG**——支持"计划失败后重新规划"，比普通 ReAct 高级。

#### 实现 5：sub_agents + transfer_to_agent（多 Agent 原生语义）

```python
IntentAgent(sub_agents=[metadata_agent, strategy_agent, ...])
```

LLM 输出 `transfer_to_agent("strategy_agent")`，ADK 自动暂停父 Agent、创建子 Agent 独立上下文、完成后 function_response 回传。

#### 实现 6：Event 模型（SSE 友好）

```python
event = Event(author="strategy_agent", content=..., custom_metadata={...})
yield format_sse(event)  # → "event: llm\ndata: {...}\n\n"
```

Event 自带 author（哪个 Agent 说的）、content（token 流）、custom_metadata（思考/工具信息），天然适配 SSE。

---

<a id="q2"></a>
## 二、框架使用（源码对比）

### Q4: 通过源码看两个框架怎么用？

**LangGraph 使用**（`langgraph_adapter/executor.py`）：

```python
# 拼装 ReAct Agent
agent = create_react_agent(
    model=ChatOpenAI(...),
    tools=tools,
    prompt=prompt,
    checkpointer=InMemorySaver(),  # 内存 checkpoint（生产要换）
)
# 流式执行，thread_id 区分会话
async for chunk in agent.astream(
    input=message,
    config={"configurable": {"thread_id": chat_id}},
):
    yield chunk  # 裸 chunk，需自行转 SSE
```

**ADK 使用**（`adk/executor.py`）分 5 步：

```python
# ① 初始化 Session（可控恢复）
await self._init_session()

# ② 构建 Runner（依赖注入）
runner = Runner(app_name=..., agent=self.agent,
                artifact_service=..., session_service=..., memory_service=...)

# ③ run_async 循环 + state_delta 透传
async for event in runner.run_async(
    user_id=..., session_id=self.chat_id,
    new_message=new_message,
    state_delta={HEADER_NAME_ORG_CODE_OUT: ..., DATASET_INFO: []},
):
    event = self.fill_custom_metadata(event, idx)  # 填充元数据
    yield f"event: {event.custom_metadata.get('type')}\ndata: {event.model_dump_json()}\n\n"

# ④ Agent 声明式定义
@property
def agent(self):
    return CustomLlmAgent(
        model=self.llm, name=..., instruction=self.get_prompt,
        tools=self.tools,
        after_tool_callback=..., after_model_callback=..., before_model_callback=...,
    )

# ⑤ 模型用 LiteLlm 多模型代理
@property
def llm(self):
    return CustomLiteLlm(model=..., base_url=..., api_key=..., custom_llm_provider="openai", ...)
```

**源码对比**：

| 维度 | LangGraph | ADK |
|---|---|---|
| Agent 创建 | `create_react_agent()` 拼装 | `CustomLlmAgent()` 声明对象 |
| 会话 | `InMemorySaver()`（内存） | `InRedisSessionService()`（Redis） |
| 多 Agent | 手动建子图 | `sub_agents=[...]` 嵌套 |
| 流式 | chunk 裸流 | event 带 author/type |
| 数据传递 | 手动 | state_delta 透传 |
| 模型 | ChatOpenAI 单一 | LiteLlm 多模型代理 |

---

<a id="q3"></a>
## 三、上下文隔离

### Q5: 上下文隔离怎么实现？

靠 ADK 的 `transfer_to_agent` 机制，三段流转：

**阶段 1：父 Agent 发起 transfer**

```
根 Agent 上下文:
Content[] = [
    user: "查看当前有哪些策略"
    model: function_call(transfer_to_agent, {agent: "strategy_view_agent"})
]
// 父 Agent 只记录"转交给了谁"，没有子 Agent 的任何推理
```

**阶段 2：ADK 创建子 Agent 全新上下文**

```
子 Agent 上下文（全新 Content[]）:
Content[] = [
    user: "查看当前有哪些策略"          ← 原始 user 消息透传
    # 子 Agent 自己的推理 + 工具调用（父 Agent 看不见）
    model: "当前有3个策略: A、B、C"
]
```

**阶段 3：最终答案回传**

```
父 Agent 上下文追加:
Content[] = [
    ...
    model: function_call(transfer_to_agent, strategy_view_agent)
    user: function_response(result: "当前有3个策略: A、B、C")  // 只有最终答案
]
```

### Q6: 隔离了什么，共享了什么？

```
隔离（子 Agent 独有）:
  ├─ 中间推理过程（PLANNING/REASONING）
  ├─ 工具调用细节（参数、结果）
  └─ 内部多轮循环（5 轮 ReAct 只产出一个答案）

共享（父 Agent 可读）:
  ├─ State 字典（request_params、metadata，state_delta 透传）
  ├─ 最终结果（function_response 回传）
  └─ 长期记忆（Memory 全局）
```

**三个独立通道**：

```
通道 1: Content[]（对话上下文）→ 隔离，子 Agent 独立
通道 2: State 字典（关键数据）→ 共享，callback_context.state 读
通道 3: Memory（长期记忆）→ 全局共享
```

**为什么混合**：全隔离无法协作（子 Agent 不知道上下文），全共享 token 爆炸 + 信息污染（5 个子 Agent 的 ReAct 全塞进父 Agent）。混合 = 隔离推理过程 + 共享关键数据。

**对比 swarm**：ADK 隔离"推理上下文"（冲突代价是 token/污染），swarm 隔离"文件系统"（git worktree，冲突代价是代码覆盖）。隔离级别和冲突代价匹配。

---

<a id="q4"></a>
## 四、任务分配

### Q7: IntentAgent 怎么分发任务？（两层路由）

```
IntentAgent 接收用户消息
    │
    ├─ 第①层：显式分支（代码判断，不调 LLM）
    │     check_strategy_publish() → 命中 → 直接返回
    │     check_strategy_use()     → 命中 → 直接返回
    │     └─ 100% 确定场景，省 LLM + 零幻觉
    │
    └─ 第②层：LLM 路由（Prompt 驱动，模糊场景）
          System Prompt 写死分发规则：
          "社交互动 → 直接回复
           元数据 → metadata_agent
           策略查看 → strategy_view_agent（匹配词：查看/显示/列出）
           策略处理 → strategy_agent（匹配词：提取/分析/优化）
           数据分析 → direct_analysis_agent（条件：有 chat_id）
           优先级：社交 > 元数据 > 查看 > 分析 > 通用"
          → LLM 输出 transfer_to_agent("strategy_view_agent")
```

### Q8: 企业级任务分配五种方案

| 方案 | 原理 | 成本 | 准确率 | 适用 |
|---|---|---|---|---|
| 规则路由 | 关键词/正则匹配 | 零 | 中 | 明确场景 |
| 语义路由 | embedding 相似度 | 低 | 中高 | 意图库固定 |
| 意图树打分 | LLM 对所有叶子打分 | 中 | 高 | 细粒度路由 |
| 分类器 | 训练专用模型 | 中（训练） | 高 | 大量固定意图 |
| LLM 自由路由 | LLM 看工具列表自主决定 | 高 | 最高 | 开放场景 |

**核心原则：确定性优先，LLM 兜底**——能代码判断的不走 LLM，能规则匹配的不打分，只有真正模糊的才交给 LLM。

---

<a id="q5"></a>
## 五、结果合并与冲突

### Q9: 子 Agent 结果冲突怎么办？

**场景**：3 个子 Agent 并行分析，结论冲突，信谁？

**方案 1：Coordinator 综合（Claude Code swarm）**

```
Research（调研）→ Synthesis（综合）→ Implementation → Verification
                    ↑ 关键：把分散信息综合成统一结论
```

Coordinator 用一个 LLM 调用，把所有子 Agent 结果汇总、去重、消解冲突，产出权威结论。不是"拼一起"，是"综合成统一结论"。

**方案 2：投票/加权**

```
多 Agent 各给答案 → 投票
  Agent A: 结论 X（置信度 0.9）
  Agent B: 结论 X（置信度 0.7）
  Agent C: 结论 Y（置信度 0.5）
  → X 得票多 + 置信度高 → 采纳 X
```

**方案 3：LLM 仲裁（最通用）**

```
把冲突结果交给仲裁 LLM：
  "以下是 3 个子 Agent 的结论，请判断哪个最可靠，或综合它们"
输入: 各 Agent 结论 + 依据 + 置信度
输出: 最终结论 + 理由
```

**方案 4：顺序依赖（无冲突，串行）**

```
A 拉数据 → B 分析 → C 生成策略（流水线，单向传递，无冲突）
```

### Q10: 部分失败怎么办？

```
3 个子 Agent，2 成功 1 失败:

策略 1: 全有或全无（严格）→ 一个失败全失败
策略 2: 降级继续（宽松）→ 失败标记无结果，用成功的继续 ★
策略 3: 失败重试（幂等）→ 重试 N 次，还失败才降级 ★
策略 4: 兜底回答 → 失败用预设兜底

PowerAgent 选择: 策略 2 + 3
  工具失败返回 isError=true，拼进上下文让 LLM 感知
  LLM 自己决定"换策略重试"还是"告知用户无法处理"
```

---

<a id="q6"></a>
## 六、成本控制

### Q11: 多 Agent 成本怎么不失控？

**成本放大本质**：单 Agent = 1 次 LLM × N 轮；多 Agent = M 个 Agent × N 轮 = M×N tokens。

**六招**：

#### 招 1：分级路由（能不并行就不并行）

```
简单问题 → 单 Agent 直答（零多 Agent 开销）
复杂问题 → 才启动多 Agent
```

IntentAgent 先判断：社交互动直接回复（不 dispatch），业务问题才 transfer。

#### 招 2：轻量模型分工

```
IntentAgent（路由）→ 小模型（便宜 100 倍）
子 Agent（推理）→ 主力模型
Coordinator（综合）→ 主力模型
```

路由和综合是"判断型"任务，轻量模型够用；推理是"生成型"才要主力模型。

#### 招 3：结果缓存 + 复用

```
同一 query 的检索结果 → 缓存
同一工具调用的结果 → 缓存（幂等工具）
注意: RAG 场景不能语义缓存（知识库更新答案会变）
```

#### 招 4：token 预算 + 硬上限

```
max_llm_calls=20（ADK 硬上限）
全局/单 Agent token 预算
超预算 → 降级（裁 context/减子 Agent）或终止
```

#### 招 5：并行 vs 串行的权衡

```
并行（无依赖）: 延迟 = 最慢 Agent（不是 3 倍），token = 3 倍（不能省）
串行（有依赖）: 延迟 = A+B+C（3 倍），token = 3 倍

关键: 无依赖必须并行（省延迟），有依赖只能串行
延迟和 token 是两回事——并行省延迟不省 token
```

#### 招 6：上下文隔离 + 结果截断

```
子 Agent 中间推理 → 不回传（上下文隔离）
子 Agent 最终答案 → 截断（max_tool_output_length）
```

ADK 的 transfer 只回传最终答案，不回传中间推理——父 Agent 上下文不被 5 个子 Agent 的 ReAct 撑爆，省掉"中间推理"这一大块 token。

**完整框架**：

```
┌─ 能不跑就不跑（分级路由、意图短路）
├─ 能用便宜的就不用贵的（轻量模型分工）
├─ 能复用的不重算（缓存）
├─ 能省的就省（上下文隔离、结果截断）
└─ 兜底（token 预算、max_llm_calls、超时）
```

---

<a id="q7"></a>
## 七、Claude Code swarm 多 Agent 架构

> Claude Code 的 swarm 是另一个多 Agent 架构范本，和 ADK 形成鲜明对比：swarm 用"文件系统隔离 + 显式消息"，ADK 用"上下文隔离 + State 共享"。

### Q12: swarm 的组织结构？（Team/Leader/Teammate）

```
Team（一组 Agent 的容器，负责资源和目标管理）—— 最高
  └─ Leader（分配任务、审批权限、合并结果）—— 高
      └─ Teammate（执行具体任务、报告进度）—— 受限
```

**类比真实公司**：Leader = tech lead，Teammate = 开发者，Team = 项目组。权限分层清晰。

### Q13: 三种执行方式？（同进程 / tmux / iTerm2）

| 方式 | 并发模型 | 可视性 | 场景 |
|---|---|---|---|
| **同进程隔离** | Node.js 单线程 + `AsyncLocalStorage` 上下文隔离 | 无 | 简单并行搜索 |
| **tmux 窗口** | 独立 tmux 窗格（独立进程） | 实时输出 | 需监控进度 |
| **iTerm2 分割** | 独立窗格（仅 macOS） | 最直观 | 复杂多模块开发 |

**为什么三种**：不同场景对可视性需求不同——简单调研不用看每个 Agent 输出，复杂开发要同时看所有 Agent 进度。

**同进程隔离的关键**：`AsyncLocalStorage` 做上下文隔离 + 独立 `AbortController`（Teammate 不因 Leader 中断而中断）。Node.js 单线程事件循环，多 Agent 是 async 上下文关系，不是线程关系。

### Q14: Git Worktree 隔离（最关键设计）

**问题**：多个 Agent 同时改代码，最大风险是文件冲突——Agent A 改文件 X，Agent B 也改文件 X，互相覆盖。

**方案**：每个 Agent 在独立 git worktree 工作：

```
不用 worktree: Agent A 改 X，Agent B 改 X → 互相覆盖
用 worktree:   每个 Agent 独立工作树 → 完成后 git merge 合并
```

**关键特征**：
- worktree 不是完整 clone，共享同一个 `.git` 目录，只建新工作目录 + 分支
- 创建快、磁盘小、适合短生命周期 Agent 任务
- Agent 没改东西自动清理，改了才返回路径给调用者合并

**这是"隔离级别和冲突代价匹配"的典范**：改同一份代码冲突代价极高（覆盖文件），所以用文件系统级隔离（重隔离）。

### Q15: 邮箱通信（Mailbox）

**Agent 之间怎么通信**？不是 API、不是消息队列，是**邮箱文件**：

```
Agent A 想给 B 发消息 → 写 B 的邮箱文件
Agent B 定期检查邮箱 → 读新消息
```

**为什么用文件**：文件系统是最可靠的通信基础设施——不用起额外服务、没连接超时、重启后消息还在。代价是延迟高（轮询），但 Agent 级协作是分钟级任务，几秒延迟可接受。

**核心哲学**：通信选型匹配部署形态——本机单用户用文件，分布式多实例才用 Redis/MQ。

### Q16: 权限冒泡（Permission Bubbling）

```
Teammate 遇到需确认的操作（删除文件）
  → 不直接弹给用户
  → 冒泡给 Leader
  → Leader 批量审批
```

**为什么**：5 个 Agent 同时工作，每个都弹权限确认窗，用户会被淹没。Leader 作为中间层，根据任务上下文批量处理权限请求。

**类比**：开发者不找 CEO，找 tech lead 代为判断。

### Q17: Coordinator 四阶段

```
① Research（调研）: 多个 worker 并行调查代码库
② Synthesis（综合）: Coordinator 收集所有发现，生成统一规格 ★关键
③ Implementation: worker 按规格精准修改
④ Verification: worker 验证结果
```

**Synthesis 是关键**：把分散信息变成统一行动计划，后续实现按同一份 spec 来，避免各干各的。

---

### Q18: swarm vs ADK 对比（共享/私有、隔离、并发）

#### 共享 vs 私有

| 维度 | Claude Code swarm | PowerAgent (ADK) |
|---|---|---|
| 默认模式 | **全私有**（白名单） | 分层共享（有默认共享） |
| 通信方式 | 邮箱文件显式消息 | State 字典 + transfer 回传 |
| 共享粒度 | 发送方决定 | 框架预设（State/Memory/Session） |
| 上下文隔离 | 文件系统级（worktree） | 推理上下文级 |
| 协作模式 | 对等（Peer-to-Peer） | 层级（父子 transfer） |

**Claude Code**：默认你什么都不知道，要共享就发邮件 → 隔离彻底，适合改代码防冲突。
**PowerAgent**：框架预设三层共享（State/Memory/Session），推理上下文隔离 → 适合分工回答。

#### 隔离级别对比

| | ADK | swarm |
|---|---|---|
| 隔离什么 | 推理上下文（Content[]） | 文件系统（git worktree） |
| 共享什么 | State + 最终结果 + Memory | 邮箱文件（显式消息） |
| 隔离原因 | 省 token + 不污染 | 防止改代码冲突 |
| 隔离级别 | 轻（上下文级） | 重（文件系统级） |

**核心差异**：ADK 隔离"思考过程"（冲突代价是 token/污染），swarm 隔离"文件"（冲突代价是代码覆盖）。隔离级别和冲突代价匹配。

#### 并发模型对比

| 系统 | 子 Agent 并发载体 |
|---|---|
| Claude Code 同进程 | async 上下文（单线程） |
| Claude Code tmux/iTerm | 独立进程（spawn） |
| PowerAgent ADK | asyncio 协程（单线程） |
| ragent（无子 Agent） | 线程池 CompletableFuture |

**关键概念 spawn**：创建新进程。隔离彻底度 `spawn(进程) > 线程 > async上下文/协程`，开销大小同样排序。Claude Code 提供三种方式就是"隔离级别和开销的权衡"。

---

<a id="q8"></a>
## 八、面试策略

### 高频追问及应对

| 追问 | 回答要点 |
|---|---|
| "为什么选 ADK 不选 LangGraph？" | 层次化多 Agent 刚需（sub_agents 一行 vs 手动建子图）+ ReAct 内置 + Session/Memory 可替换 |
| "上下文隔离怎么做的？" | transfer_to_agent 三段流转，隔离推理过程只回传最终答案，State 字典共享关键数据 |
| "任务分配怎么做？" | 两层路由：显式分支（代码判断，省 LLM 零幻觉）+ LLM 路由（Prompt 驱动） |
| "结果冲突怎么办？" | Coordinator 综合 + LLM 仲裁 + 投票，部分失败降级继续 + 幂等重试 |
| "多 Agent 成本怎么控制？" | 分级路由省 + 上下文隔离省 + 预算兜底，并行省延迟不省 token |
| "swarm 和 ADK 隔离的区别？" | swarm 隔离文件系统（git worktree，防代码冲突），ADK 隔离推理上下文（省 token 不污染） |
| "多 Agent 通信用什么？" | swarm 用邮箱文件（本机可靠），ADK 用 State 字典（框架预设），分布式才用 Redis/MQ |
| "子 Agent 是线程吗？" | 不一定——Node 用 async 上下文、Python 用协程、Java 用线程池，隔离载体不同 |

### 一句话总结

多智能体的三大工程难题——**任务分配**（分得准，两层路由确定性优先）、**结果合并**（合得拢，Coordinator 综合 + 部分失败降级）、**成本控制**（养得起，分级路由 + 上下文隔离 + 预算兜底）。三者环环相扣：分得准才能少跑 Agent（省成本），合得拢才敢并行（保质量），成本可控才能规模化（上生产）。

两大架构范本：**ADK**（上下文隔离 + State 共享，适合分工回答）和 **swarm**（文件系统隔离 + 邮箱通信，适合改代码防冲突）。核心原则是"隔离级别和冲突代价匹配"。
