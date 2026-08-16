# 美团 Agent  Q\&A

---

<a id="q1"></a>
## **Q1: 做个自我介绍，重点介绍 Agent 相关项目经历。**

### **回答框架**

> 我参与的核心项目是 **PowerAgent（PA）**，一个面向企业级业务洞察的 multi\-agent 系统。它定位在业务人员和数据之间，让用户用自然语言提问，系统理解意图后自动进行数据拉取、分析、生成洞察报告。
> 
> 我主要负责 Agent 推理引擎和相关基础设施的设计与实现。
> 
> 

### **项目基本盘**

### **重点介绍的经历**

**经历一：从 LangGraph 迁移到 Google ADK（\~2025 年初）**

项目原本基于 LangGraph 的 `create_react_agent` 构建单 Agent 循环，但随着业务方提出"深度洞察"需求，单 Agent 难以处理"理解需求→拉取数据→分析→生成报告"的多步骤流程。我主导了向 Google ADK 的迁移，利用其原生的 `transfer_to_agent` 机制实现了层次化多 Agent 架构：

- IntentAgent 作为入口，负责意图识别和分发

- 5 个专用子 Agent（run\_data/metadata/strategy\_view/strategy\_create/analysis）各司其职

- 保留 LangGraph 作为备用框架（`framework="langgraph"`），通过 `framework_adapter` 模式切换

**经历二：Agent 稳定性体系搭建**

针对生产环境常见的幻觉、循环调用、异常崩溃问题，我设计了分层防御体系：

- 防幻觉三层：Prompt 硬约束 → `_post_process_text` 后处理拦截 → `check_final_event` 最终校验

- 防循环四种手段：`disallow_transfer_to_parent/peers`、`max_llm_calls=20`、`before_agent_callback` 显式调工具、历史截断

- 兜底三层：子 Agent try/except → Runner 级别 catch → Event packer 最后防线

其中一次关键修复是：2025 年 8 月模型升级后 LLM 不再自动调用工具，导致死循环。我通过 `_explicit_call_tool()` 在 `before_agent_callback` 中显式执行工具调用，绕过了模型行为变更带来的问题。

**经历三：Agent 与 WorkFlow 引擎的双向集成**

Agent 通过统一的 `/api/v1/tools/run` 网关调用 WorkFlow 引擎中的 50\+ 节点类型，同时 WorkFlow 也可通过 `autoAgent` 节点将控制权交还给 Agent 推理引擎，形成"工作流编排 Agent 执行，Agent 调用工作流工具"的双向关系。

---

<a id="q2"></a>
## **Q2: Agent 项目的核心架构是怎么样的？**

### **整体架构图**

```Plain Text
┌──────────────────────────────────────────────────────────────┐
│                       前端 (Web/SSE)                          │
└──────────────────────────┬───────────────────────────────────┘
                           │ HTTP / SSE
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  af-rag-server (Python, Agent 推理引擎)                       │
│                                                              │
│  ┌──────────────────────────────────────┐                    │
│  │  Runner (Google ADK)                 │                    │
│  │  max_llm_calls=20                    │                    │
│  │  ┌────────────────────────────────┐  │                    │
│  │  │  IntentAgent (主 Agent)        │  │                    │
│  │  │  └─ 意图识别 → 分发            │  │                    │
│  │  │     transfer_to_agent 路由     │  │                    │
│  │  └──────────┬─────────────────────┘  │                    │
│  │             │ 5 个子 Agent           │                    │
│  │  ┌──────────┼──────────┬──────────┐  │                    │
│  │  ▼          ▼          ▼          ▼  │                    │
│  │ run_data  metadata  strategy   ...   │                    │
│  │           strategy_view analysis     │                    │
│  │  ┌────────────────────────────────┐  │                    │
│  │  │  Event Packer 后处理管线       │  │                    │
│  │  │  _post_process_text → repack   │  │                    │
│  │  │  → check_final_event           │  │                    │
│  │  └────────────────────────────────┘  │                    │
│  └──────────────────────────────────────┘                    │
│                                                              │
│  ┌──────────────────────────────────────┐                    │
│  │  Chunking & RAG 管线                 │                    │
│  │  10+ 分块策略 → 混合检索 → Rerank    │                    │
│  └──────────────────────────────────────┘                    │
└──────────────────────────┬───────────────────────────────────┘
                           │ /api/v1/tools/run (tool_type)
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  agentflow-server (Java, 工作流编排引擎)                      │
│                                                              │
│  ┌──────────────────────────────────────┐                    │
│  │  WorkFlowEngine                      │                    │
│  │  └─ DAG 图解析 → 节点调度执行        │                    │
│  │     同步递归 / 线程池并发             │                    │
│  │     CountDownLatch 协调              │                    │
│  └──────────────────────────────────────┘                    │
│                                                              │
│  ┌──────────────────────────────────────┐                    │
│  │  50+ ModuleServiceImpl               │                    │
│  │  LLM/MCP/知识库/Agent/循环/条件...    │                    │
│  └──────────────────────────────────────┘                    │
│                                                              │
│  ┌──────────────────────────────────────┐                    │
│  │  统一工具网关                         │                    │
│  │  /api/v1/tools/run                   │                    │
│  │  5 种 tool_type 自动分发             │                    │
│  │  (plugin/mcp/workflow/autoAgent/     │                    │
│  │   dataset)                           │                    │
│  └──────────────────────────────────────┘                    │
└──────────────────────────────────────────────────────────────┘
```

### **核心设计要点**

#### **双引擎架构（ADK 主力 \+ LangGraph 备用）**

```Plain Text
af-rag-server/agent/
├── auto_agent/
│   ├── executor.py              # 统一入口，framework 参数选择引擎
│   └── framework_adapter/
│       ├── adk_adapter/         # Google ADK 实现（主力）
│       └── langgraph_adapter/   # LangGraph 实现（备用降级）
```

- **主力 Google ADK**：原生支持 `transfer_to_agent` 层次化多 Agent、内置 Plan\-ReAct Planner

- **备用 LangGraph**：通过 `framework="langgraph"` 切换，用于 ADK 不支持的场景

- 保留了 LangGraph `/docs/README.md` 中的说明，但已不在主流程中使用

#### **IntentAgent 分发路由**

主 Agent 的 Prompt 定义了 5 条分发规则：

#### **层次化多 Agent 信息流**

```Plain Text
IntentAgent (分发)
    │
    │ transfer_to_agent + 参数
    ▼
子 Agent (执行)
    │
    │ Event.content.parts[0].text (纯文本结果)
    ▼
IntentAgent (继续/结束)
```

**关键设计**：主 Agent 不感知子 Agent 的失败/重试/超时。子 Agent 内部异常统一返回"请再试一次"的兜底消息，主 Agent 的 Prompt 可以保持极简——它只需要知道"分发给谁"，不需要理解"出了什么问题"。

#### **分层稳定性体系**

#### **与 WorkFlow 引擎的双向关系**

```Plain Text
agentflow-server  = 工作流编排平台 + 工具执行网关 (类比 Dify/Coze)
af-rag-server     = 智能体推理引擎 (类比 LangGraph)
```

- Agent → WorkFlow：Agent 调用工具时，通过 `/api/v1/tools/run` 网关路由到 WorkFlow 引擎

- WorkFlow → Agent：WorkFlow 的 `autoAgent` 节点调用 Agent 推理引擎

- 工具统一网关：5 种 `tool_type`（plugin/mcp/workflow/autoAgent/dataset）自动分发

---

<a id="q3"></a>
## **Q3: 有没有了解过 harness，和你的项目有什么区别？**

### **Harness 是什么**

Harness 是 Claude Code / Google ADK 中的一种**安全沙箱机制**，它在 Agent 执行外围做四件事：

1. **限制权限**：文件系统（白名单路径）、网络（域名白名单）、进程（不允许 shell）、环境变量

2. **拦截许可**：敏感操作（git push、文件删除）走用户确认流程

3. **会话隔离**：Agent 运行在受限环境中，无法访问宿主机全部资源

4. **回滚能力**：Agent 的修改可以被 discard（如 Claude Code 的 `exit worktree --remove`）

### **与 PA 项目的区别**

### **关键差异点总结**

1. **PA 不关心安全问题**：PA 是后端服务，Agent 运行在受控的服务器环境中，不需要文件系统白名单或进程隔离。安全边界在统一的工具调用网关，而不是 Agent 运行时本身。

2. **Harness 是框架能力，PA 是业务能力**：Harness 解决的是"如何让 Agent 安全地执行代码"，PA 解决的是"如何让 Agent 正确地做业务分析"。两者可以组合——如果 PA 跑在 Claude Code 环境中，可以叠加 Harness 做安全沙箱。

3. **PA 的"兜底" ≠ Harness 的"拦截"**：PA 的兜底是业务层面的（分析失败→返回重试消息），Harness 的拦截是安全层面的（危险操作→询问用户）。一个是系统韧性，一个是系统安全。

### **行业对比**

---

<a id="q4"></a>
## **Q4: 为什么用 LangGraph？有什么缺点？是否过度设计？**

> **首先需要澄清**：项目目前的主力框架是 **Google ADK**，LangGraph 是备用/降级方案。但考虑到 LangGraph 是业界更通用的框架，这里从"为什么选型和切换"的角度回答。
> 
> 

### **最初为什么选 LangGraph**

### **LangGraph 的缺点（项目实际踩过的坑）**

#### **层次化多 Agent 支持薄弱**

- LangGraph 的 `create_react_agent` 是单 Agent 实现，多 Agent 需要手动建 `StateGraph`

- 子 Agent 之间状态隔离需要自己实现（用 `subgraph` 或手写 state schema）

- **PA 项目吃了这个亏**：最初 IntentAgent \+ 子 Agent 的交互靠手写 Graph 连接，代码复杂且脆弱

#### **高度抽象带来的调试困难**

- `StateGraph` 的 `add_node` / `add_edge` / `add_conditional_edges` 将控制流隐藏在抽象中

- 执行到哪个节点、为什么没调用工具、状态在哪里被修改——这些信息需要穿透多层抽象才能定位

- 对比 ADK 的 `transfer_to_agent` \+ Event 流，可观测性差距明显

#### **状态变更不兼容**

- LangChain 0\.2 → 0\.3 的 API 变更导致多处代码需要重写

- 作为一个还在快速迭代的框架，版本升级的向后兼容性不够好

#### **不适合"业务分析"场景的深度推理**

- LangGraph 的 ReAct 循环适合工具调用密集的场景（API 调用、代码执行、搜索）

- 但业务分析需要：长上下文理解 → 多步规划 → 结构化输出 → 可干预的人类反馈

- LangGraph 缺少内置的 Plan 机制，项目需要自己在 Prompt 层面实现 Plan\-ReAct

### **是否过度设计？**

**取决于用在什么场景**：

**PA 项目的实际情况**：最初用 LangGraph 时确实是过度设计——IntentAgent 只做了"分发"这一件事，但跑在完整的 `create_react_agent` 循环中，LLM 每次都被迫做"是否调用工具→是否回复"的决策。迁移到 ADK 后更简洁——IntentAgent 的 Prompt 只关注分发规则，没有多余的 Agent 循环负担。

### **迁移到 Google ADK 后的对比**

### **从 LangGraph 到 ADK 的迁移经验**

迁移过程中核心解决了三个问题：

1. **工具定义兼容**：LangGraph 的 `@tool` 装饰器 → ADK 的 `BaseTool` 子类。通过 `_get_declaration()` 动态生成 `FunctionDeclaration`，工具定义从代码硬编码变为从 DB 读取。

2. **多 Agent 状态隔离**：LangGraph 的 `subgraph` 方式状态容易泄露 → ADK 的 `transfer_to_agent` 天然隔离，子 Agent 的 Event 在主 Agent 看来只是一个文本片段。

3. **流式输出改造**：LangGraph 的 `astream_events` 需要解析不同 chunk 类型 → ADK 的 `StreamingMode.SSE` 原生支持流式 Event，前端可以直接消费。

---

<a id="q5"></a>
## **Q5: 为什么不直接用 Claude Code 或公司内部 Agent？**

### **为什么不直接用 Claude Code**

**根本原因**：**领域不同**。Claude Code 是"编程领域的通用 Agent"，PA 是"业务分析领域的专用 Agent"。Claude Code 不知道什么是"数据源"、"分析策略"、"业务指标"——这些是 PA 的核心领域知识。

**具体来说**：

1. **工具集不匹配**：Claude Code 的工具（Read/Edit/Bash/Glob）对业务分析毫无意义。PA 需要的工具是"查数据表"、"运行分析脚本"、"保存策略"——这些需要与后端系统深度集成。

2. **工作流不匹配**：业务分析是多步骤的（理解需求→拉数据→分析→格式化输出→人工确认），Claude Code 的"对话→执行"模式不适合这种有状态的流水线。

3. **不能替换，但可以组合**：Claude Code 可以作为一个 MCP 工具被 PA 调用——在需要编写分析代码时，PA 把任务交给 Claude Code，取回结果。

### **为什么不直接用公司内部 Agent**

> 这里的"公司内部 Agent"有两种理解：一种是 MIP（公司的统一 AI 平台），另一种是直接使用 OpenAI/Claude 的 API。
> 
> 

#### **对比 MIP（公司内部 AI 平台）**

**关系**：PA 是 MIP 的上层应用。PA 不直接调用 LLM，而是通过 MIP 的 `maip` 接口（公司内部 LLM 网关）获取模型能力。MIP 负责模型路由、鉴权、监控、限流，PA 负责 Agent 逻辑和业务编排。

所以"为什么不用 MIP"是一个伪问题——**PA 本来就在用 MIP**。MIP 是 LLM 调用层，PA 是 Agent 应用层，两者是上下游关系。

#### **对比直接用 OpenAI/Claude API**

**企业级 Agent 的四层需求**：

```Plain Text
直接调用 API 只能满足第 1 层
┌────────────────────────────────────────────┐
│ 4. 安全合规 ─── 数据不出域、操作可审计     │
│ 3. 多 Agent 编排 ─── 意图分发、任务分解     │
│ 2. 工具集成 ─── 内部系统、MCP、知识库       │
│ 1. LLM 调用 ─── 补全、流式、function call   │
└────────────────────────────────────────────┘
```

### **一句话总结**

> 不是"不用 Claude Code 或内部 Agent"，而是"Claude Code 是编程助手不适合业务分析，MIP 是 LLM 网关被 PA 作为底层依赖使用"。PA 在它们之上构建了业务洞察这个垂直领域的专用 Agent。
> 
> 

---

<a id="q6"></a>
## **Q6: Skill 机制具体是做什么的？**

### **两种语境下的 Skill**

"Skill" 这个概念在 PA 项目语境和 Claude Code 语境中有不同的含义，需要先区分清楚。

#### **Claude Code 的 Skill**

Claude Code 的 Skill 是一种**可复用的 slash command 能力单元**，本质是一组预定义的 Prompt \+ 工具集 \+ 执行逻辑的封装：

```Plain Text
Skill = 触发词（/skill-name） + System Prompt + 工具权限 + 回调逻辑
```

用户键入 `/skill-name` → Claude Code 加载对应的 System Prompt 和工具集 → 在特定上下文中执行。

**特点：**

- **按需加载**：不是 Agent 上下文的一部分，只有用户触发时才注入

- **封装完整**：一个 Skill 包含完整的 Prompt、工具、权限声明

- **可发现**：用户在 UI 中可以看到所有可用的

- **可组合**：多个 Skill 可以组合使用

**行业类比：**

#### **PA 项目的"Skill 等价物"——Plugin \+ Tool**

PA 项目中没有直接叫"Skill"的机制，但有两个等价概念：

1. **Plugin（插件系统）**：通过 `CustomPlugin(BasePlugin)` 实现的 ADK 生命周期钩子

2. **Tool（工具）**：通过统一网关 `/api/v1/tools/run` 调用的 5 种工具类型

**PA 的"Skill"更多体现为 Tool 的聚合**——一个业务能力单元 = 一个 Agent \+ 一组工具 \+ 一段 Prompt。

### **Skill 机制的核心价值**

无论是 Claude Code 的 Skill 还是 PA 的 Plugin，核心价值是三点：

1. **关注点分离**：每个 Skill 只关心自己领域的能力，不耦合其他逻辑

2. **可插拔**：新增一个 Skill 不需要修改核心框架代码

3. **权限隔离**：Skill 可以声明自己需要哪些权限，框架按需授权

#### **PA 的具体场景**

PA 没有直接实现 Skill 机制，但它的**子 Agent 体系**起到了类似的作用：

```Plain Text
IntentAgent（路由器）
    │
    ├── run_data_agent（Skill ≈ "数据查询"）
    │   ├── Tool: 查 MySQL
    │   ├── Tool: 查 ClickHouse
    │   └── Prompt: 理解表格查询意图
    │
    ├── metadata_agent（Skill ≈ "元数据查询"）
    │   ├── Tool: 查字段定义
    │   ├── Tool: 查枚举值
    │   └── Prompt: 理解元数据查询意图
    │
    ├── strategy_create_agent（Skill ≈ "策略生成"）
    │   ├── Tool: 代码执行
    │   ├── Tool: 数据拉取
    │   └── Prompt: 生成分析策略
    │
    └── analysis_agent（Skill ≈ "深度分析"）
        ├── Tool: 代码执行
        ├── Tool: MCP 工具
        └── Prompt: 执行分析逻辑
```

**区别在于**：Claude Code 的 Skill 由用户主动触发（键入命令），PA 的子 Agent 由 IntentAgent 自动路由（LLM 判断意图后分发）。

---

<a id="q7"></a>
## **Q7: 你在支持 Skill 机制这方面具体做了哪些开发工作？你的 Skill 里面会放代码吗？**

### **具体开发工作**

#### **ADK Plugin 生命周期实现**

开发了 `CustomPlugin(BasePlugin)`，实现了 10 个 ADK 生命周期回调：

**关键实现细节**（`after_model_callback`）：

```Python
async def after_model_callback(self, *, callback_context, llm_response):
    # 1. 记录事件日志到文件（用于问题排查）
    if LOG_ANALYSIS_FLAG:
        log_events = callback_context._invocation_context.session.events
        log_events.append(Event(...))
        await log(req_id, session_id, events)

    # 2. 去除 CustomLiteLlm 添加的 reasoning_content
    #    模型返回的思维链对前端无意义，且浪费带宽
    if isinstance(model, CustomLiteLlm) and llm_response?.content?.parts:
        llm_response.content.parts = [p for p in parts if not p.thought]

    # 3. 更新 session token 使用量（用于成本统计和限流）
    update_session_token_count(...)
```

#### **统一工具网关（Tool Gateway）**

开发了 `CommonTool` 框架，将 5 种工具类型通过统一的接口暴露给 Agent：

```Python
# 调用侧（Agent 侧）
tool = CommonTool(data, tool_type=tool_type, ...)
result = await tool.run_async(args=args, tool_context=context)

# 接收侧（agentflow-server 侧）
POST /api/v1/tools/run  →  AutoAgentToolController
    →  AutoAgentToolContext.toolRun()
    →  根据 type 字段分发到具体执行器
```

5 种 `tool_type`：

- **plugin**：内部插件（SQL 查询、API 调用等）

- **mcp**：MCP 外部服务

- **workflow**：子工作流

- **autoAgent**：嵌套子 Agent

- **dataset**：知识库检索

#### **工具定义的三次迭代**

当前 V3 的实现：

```Python
class CommonTool(BaseTool):
    def _get_declaration(self) -> FunctionDeclaration:
        desc = self.description
        # 自动补充 question 参数（autoAgent/workflow 需要）
        if self.tool_type in ["autoAgent", "workflow"]:
            self.data.parameters.properties["question"] = {
                "description": "用户输入参数",
                "type": "STRING"
            }
        return FunctionDeclaration(name=self.name, description=desc)
```

#### **MCP 工具的双层实现**

开发了两层 MCP 客户端：

- **MCPClientTools**（ADK 原生 `MCPToolset`）：用于长期连接的场景，带 10 分钟缓存

- **MCPClientToolsNew**（原生 `mcp` 库）：每次调用新建 SSE 连接，带重试逻辑

原因是 ADK 的 MCPToolset 在长连接场景下稳定性不够好（连接断开后恢复困难），所以增加了第二层"无状态每次重连"的实现作为备选。

### **Skill 里面会放代码吗？**

**会，但分情况**：

#### **情况 1：代码在 Tool 执行器中（推荐方式）**

- 代码不在 Skill 本身，在 Tool 的执行端（agentflow\-server 或 MCP 服务端）

- Agent 只负责"调用哪个工具"，不负责"工具怎么执行"

- **优点**：Agent 不需要关心实现细节，工具逻辑独立更新

#### **情况 2：代码在 Agent Prompt 中（少数情况）**

有些 Agent 的 Prompt 中会包含少量代码逻辑，例如 `analysis_agent` 会生成 Python 代码并在沙箱中执行：

```Plain Text
analysis_agent 的代码执行流程：
1. LLM 生成代码 → 2. 在沙箱中执行 → 3. 返回结果 → 4. 如果失败则重试
```

这部分代码是**动态生成的**，不是预置的。

#### **情况 3：Skill 作为配置（数据驱动）**

Skill 更多是**配置**而非代码：

```JSON
{
  "name": "data_query",
  "description": "数据查询工具",
  "type": "plugin",
  "parameters": {
    "type": "OBJECT",
    "properties": {
      "sql": { "type": "STRING" }
    }
  }
}
```

**总结**：

- **Tool 的定义是配置**（名称、描述、参数）

- **Tool 的执行是代码**（在后端执行）

- **Agent 本身不包含业务代码**，它只做意图识别和分发

- **唯一的例外是分析 Agent 的代码生成**——它让 LLM 动态生成 Python 代码执行数据分析

---

<a id="q8"></a>
## **Q8: MCP 和 Skill 的区别是什么？和 CLI 的区别呢？**

### **三者的定位差异**

### **详细区别**

#### **MCP vs Skill**

MCP 和 Skill **不是同一层的东西，可以组合使用**。

```Plain Text
Skill（应用层） → MCP 协议（传输层） → MCP Server（执行层）

           Skill 通过 MCP 调用外部工具
           Skill 也可以调用内部 Plugin（不走 MCP）
```

**在实际项目中**：

- MCP 是 PA 调用外部服务的一种方式（通过 `MCPClientTools`）

- Skill（子 Agent）是 PA 组织内部能力的方式

- **MCP 工具被注册为 Agent 的一个 Tool**，通过统一的 CommonTool 框架管理

#### **Skill vs CLI**

**一个具体的对比案例**：

```Plain Text
场景：在项目里找一个 bug

CLI 方式：
  grep -r "bug_pattern" src/
  git log --oneline | head -20
  vim src/file.py +42

Skill 方式：
  /code-review  # → 自动分析 diff，检查代码质量
  
区别：CLI 需要用户自己知道"找 bug 要 grep + git log + vim"，
Skill 把"代码审查"这个能力封装成一步到位。
```

### **MCP 在业界的定位**

MCP 是一个新兴的开放协议，可以类比为"LLM 应用的 USB\-C 接口"：

**PA 项目中的 MCP 接入情况**：目前有两个 MCP Server（洞察 MCP 和一个内部服务），通过 `MCPClientToolsNew` 的 SSE 方式连接。ADK 也内置了 `MCPToolset` 支持，但因为长连接稳定性问题，我们更多使用裸 `mcp` 库的"每次重连"模式。

### **三者的组合关系**

```Plain Text
┌──────────────────────────────────────────┐
│              Agent/Skill                   │
│  ┌────────────────────────────────────┐   │
│  │  /data-query ← Skill 触发         │   │
│  │   └─→ CommonTool（统一工具调用）   │   │
│  │        ├─→ MCP（协议层，调外部服务）│   │
│  │        ├─→ Plugin（内部 API）      │   │
│  │        └─→ CLI（进程级执行）       │   │
│  └────────────────────────────────────┘   │
└──────────────────────────────────────────┘
```

**MCP 是工具调用的标准协议**，**CLI 是系统命令的执行接口**，**Skill 是面向用户的能力封装**。三者不是替代关系，而是不同抽象层级——Skill 可以用 MCP 调外部服务，MCP Server 内部也可以调用 CLI。

---

<a id="q9"></a>
## **Q9: 工具链能否 Skill 化？**

### **什么是"工具链 Skill 化"**

将一系列工具调用序列封装成一个完整的 Skill，让用户通过一个简单的触发词完成复杂的多步骤操作。

```Plain Text
未 Skill 化（用户自己编排）：
  步骤 1：调用 A 工具 → 步骤 2：处理结果 → 步骤 3：调用 B 工具 → 步骤 4：生成报告

Skill 化后（封装成能力单元）：
  /generate-report → 自动完成步骤 1-4
```

### **能，但有限制条件**

#### **适合 Skill 化的工具链**

**典型案例——PA 的"生成策略"工具链**：

```Plain Text
用户触发 "生成分析策略"
    步骤 1: get_latest_date_by_mcp → 获取最新数据时间（确定）
    步骤 2: DataPuller.save_local_cache → 拉取数据（确定）
    步骤 3: LLM 生成分析代码（不确定——依赖数据内容）
    步骤 4: 执行代码（确定，但可能失败）
    步骤 5: 格式化输出（确定）

前两步确定，可 Skill 化；第 3 步需要 Agent 推理，需要 ReAct 循环
→ PA 的方案是把整体封装为子 Agent，而不是纯 Skill
```

#### **不适合 Skill 化的工具链**

### **PA 项目中的实践**

PA 实际上已经在做"工具链 Skill 化"，但不是通过 Skill 机制，而是通过**子 Agent** \+ **WorkFlow 引擎**：

#### **方式 1：子 Agent = 高级 Skill**

```Plain Text
strategy_create_agent
触发: IntentAgent 分发（"需要生成分析策略"）
工具链:
  1. get_latest_date_by_mcp → 获取最新数据时间
  2. DataPuller.save_local_cache → 拉取数据到本地
  3. 分析代码生成 → LLM 生成分析 Python 代码
  4. 执行代码 → 在沙箱中运行
  5. 格式化输出 → 前端可消费的结果
```

这本质上就是"工具链 Skill 化"——把"生成分析策略"这个多步骤流程封装成一个独立的 Agent 能力单元。

#### **方式 2：WorkFlow 节点 = 可视化 Skill**

agentflow\-server 的 50\+ 节点类型本质上也是 Skill 化：

每个节点都封装了一个独立的能力单元，通过可视化拖拽"连接"成完整流程。

#### **方式 3：Claude Code 的 Skill 机制直接支持**

Claude Code 的 Skill 本身就是这个思路的体现：

```Plain Text
/data-query       → 封装了"查询数据库"的工具链
/code-review      → 封装了"审查代码"的工具链
/debug            → 封装了"调试问题"的工具链
```

每个 Skill 都可以包含多个工具调用步骤，用户不需要关心内部实现。

### **局限性**

工具链 Skill 化有三个"难以逾越"的障碍：

1. **非确定性步骤**：如果工具链的下一步依赖于上一步的复杂结果（不是简单的是/否），AI 的推理能力就是瓶颈。比如"分析数据 → 根据分析结果决定下一步分析方向"——这本质上是 Agent 循环，不是 Skill 能封装的。

2. **状态共享**：多步骤之间需要共享状态，Skill 机制通常设计为无状态的（输入 → 处理 → 输出）。有状态的工具链需要更复杂的上下文管理。

3. **错误恢复**：工具链中某一步失败后的恢复策略很难通用化。PA 的做法是"子 Agent 内部 try/except 兜底"，但具体的重试逻辑还是需要针对每个工具链定制。

### **结论**

> 工具链可以 Skill 化，但不是万能的。**确定性高频的工具链最适合 Skill 化**；**需要复杂推理的多步骤流程更适合 Agent 循环**；PA 的做法是两者结合——简单工具链用 WorkFlow 节点封装，复杂分析流程用子 Agent \+ 代码生成实现。
> 
> 

---

<a id="q10"></a>
## **Q10: Agent 的循环流程是怎样的？也就是 ReAct 循环。**

### **ADK 的 Plan\-ReAct 循环**

PA 基于 Google ADK，ADK 的 Agent 循环是**Plan\-ReAct**，比标准的 ReAct 多了一个规划阶段。

```Plain Text
用户输入
    │
    ▼
┌──────────────────────────────────────┐
│  1. 规划阶段（Plan）                  │
│  LLM 输出：{PLANNING_TAG} 分解步骤... │
│  根据可用工具，制定执行计划           │
└──────────────────┬───────────────────┘
                   │
                   ▼
┌──────────────────────────────────────┐
│  2. 执行阶段（Action）                │
│  LLM 输出：{ACTION_TAG} 调工具       │
│  ↓                                   │
│  工具执行 → 返回结果                  │
│  ↓                                   │
│  LLM 输出：{REASONING_TAG} 推理当前   │
│  状态 + 判断是否继续调用工具          │
└──────────────────┬───────────────────┘
                   │
          ┌────────┴────────┐
          ▼                 ▼
      还有工具需要调用     已完成
          │                 │
          ▼                 ▼
    ┌─────────────┐  ┌──────────────────┐
    │ 重新规划     │  │ 3. 最终回答       │
    │ {REPLANNING} │  │ {FINAL_ANSWER}    │
    │ → 步骤 2     │  │ → 输出给用户     │
    └─────────────┘  └──────────────────┘
```

### **关键标签**

ADK Plan\-ReAct 定义了 5 个标签来控制循环：

### **实际执行流程（PA 的多 Agent 版本）**

PA 的 IntentAgent 运行在这个循环之上，但多了一层分发逻辑：

```Plain Text
用户输入 "2024 年 Q4 的销售额是多少？"
    │
    ▼
IntentAgent Plan-ReAct 循环
    │
    ├── PLANNING: "用户需要查询销售额数据，我需要调用 run_data_agent"
    │
    ├── ACTION: transfer_to_agent("run_data_agent", {query: "2024年Q4销售额"})
    │
    ├── [run_data_agent 子循环]
    │   ├── PLANNING: "先查表，再聚合"
    │   ├── ACTION: query_database("SELECT SUM(amount) FROM sales WHERE quarter='2024Q4'")
    │   ├── REASONING: "数据已返回，格式化为表格"
    │   └── FINAL_ANSWER: {rows: [{total: 1250000}], fields: [...]}
    │
    ├── REASONING: "run_data_agent 已返回结果，内容是表格数据，直接返回给用户"
    │
    └── FINAL_ANSWER: "2024年Q4的销售额为1,250,000元"
```

### **实际运行中的循环条件（代码确认）**

从 `executor.py` 中确认，**Plan\-ReAct 目前处于注释状态**：

```Python
# executor.py:247-249
# 有工具时才进行任务规划
# if agent.tools:
#     agent.planner = CustomPlanner()
```

这意味着 PA 的实际 Agent 循环是 **ADK 默认的 ReAct 循环**，没有显式的 Plan 阶段。LLM 直接在对话上下文中根据工具描述决定调用什么工具，不需要独立的规划阶段。

### **业界方案对比**

---

<a id="q11"></a>
## **Q11: Plan \& Execute 和 ReAct 两种范式有什么区别？分别适合什么场景？**

### **两种范式的定义**

**ReAct（Reasoning \+ Acting）**：

```Plain Text
循环：
  推理当前状态 → 决定行动（调用工具/回复） → 观察结果 → 再推理 → ...
```

特点：推理和行动**交织进行**，每一步的决定取决于上一步的观察。

**Plan \& Execute（先计划再执行）**：

```Plain Text
阶段 1：制定完整计划（一次性）
  分析目标 → 分解步骤 → 列出所有需要的工具调用
阶段 2：按计划执行
  执行步骤 1 → 执行步骤 2 → ... → 执行步骤 N
阶段 3（可选）：必要时重新规划
  如果某步失败 → 回到阶段 1 修正计划
```

特点：先规划再执行，**规划阶段不调用工具**，执行阶段不改变计划（除非失败）。

### **核心区别**

### **一个具体的对比案例**

**场景：用户问"2024 年 Q4 销售额同比下降了多少？"**

#### **ReAct 方式**

```Plain Text
Step 1: LLM 推理 → "需要查 2024Q4 和 2023Q4 的数据"
        行动 → 调工具 A：查 2024Q4 销售额 → 返回 1,250,000
Step 2: LLM 推理 → "还需要 2023Q4 的数据来做对比"
        行动 → 调工具 B：查 2023Q4 销售额 → 返回 1,180,000
Step 3: LLM 推理 → "计算同比：(1,250,000-1,180,000)/1,180,000 ≈ 5.93%"
        回复 → "2024Q4 同比上升 5.93%"
```

如果工具 A 返回时 LLM 发现还需要额外信息（比如要除以门店数），它会自然地在 Step 2 做调整。

#### **Plan \& Execute 方式**

```Plain Text
规划阶段：
  步骤 1：查 2024Q4 销售额
  步骤 2：查 2023Q4 销售额
  步骤 3：计算同比
执行阶段：
  步骤 1 → 返回 1,250,000
  步骤 2 → 返回 1,180,000
  步骤 3 → 计算 → 回复 "同比上升 5.93%"
```

如果步骤 1 的结果表明"销售额需要按门店分组"，计划中没有这个步骤，需要触发重新规划。

### **分别适合什么场景**

### **实际项目中的混合使用**

PA 实际上**两者都用**：

```Plain Text
IntentAgent 级别：ReAct（灵活分发）
    ↓
子 Agent 内部：Plan & Execute（确定流程）
    ↓
代码生成阶段：ReAct（探索性分析）
```

- IntentAgent 用 ReAct 是因为意图识别后分发给哪个子 Agent 是不确定的

- 子 Agent 内部用 Plan \& Execute 是因为"拉取数据 → 分析 → 格式化"流程固定

- 代码生成阶段又回到 ReAct 是因为分析代码写出来后结果不确定，需要根据结果调整

### **为什么 CustomPlanner 被注释了**

PA 虽然内置了 `CustomPlanner(PlanReActPlanner)` 支持 Plan \& Execute，但在实际使用中它被注释了：

```Python
# if agent.tools:
#     agent.planner = CustomPlanner()
```

原因是 PA 的场景更接近 ReAct——用户的问题不确定，预先规划反而低效。只有在流程确定的子 Agent 内部，才适合 Plan \& Execute。

### **面试话术**

> ReAct 和 Plan \& Execute 不是替代关系，而是互补的。**ReAct 适合探索性、不确定性高的任务**——每一步都根据上一步的结果实时决策。**Plan \& Execute 适合确定性、步骤固定的任务**——一次性规划好，按部就班执行。
> 
> 实际项目往往是混合的。PA 中 IntentAgent 用的是 ReAct（因为用户意图不确定），子 Agent 内部用 Plan \& Execute（因为分析流程固定）。Google ADK 的 Plan\-ReAct 是两者的结合——先规划、再执行、必要时重新规划。
> 
> 

---

<a id="q12"></a>
## **Q12: Agent 死循环问题怎么解决？**

### **死循环的几种类型**

### **PA 的解决方案（4 层防御）**

#### **第 1 层：静态阻断（配置层面）**

```Python
# 每个子 Agent 禁止转回父 Agent 和平级 Agent
llm_agent = LlmAgent(
    ...
    disallow_transfer_to_parent=True,   # 禁止转回父 Agent
    disallow_transfer_to_peers=True,    # 禁止转给同级 Agent
)
```

**原理**：直接从 ADK 框架层面禁止 A→B→A 的循环。这是最简单最有效的防护。

#### **第 2 层：硬性上限（运行配置）**

```Python
run_config = RunConfig(
    max_llm_calls=20,  # 最多调用 20 次 LLM
)
```

**原理**：不管什么原因，达到 20 次 LLM 调用后强制终止。这是最后一道防线。

**业界标准**：

#### **第 3 层：回调层干预（针对模型 Bug）**

这是 PA 遇到过的一个实际问题——2025 年 8 月模型升级后，LLM 不再自动调用工具，而是输出 `transfer_to_agent` 文本。这在 ADK 中是一个不完整的状态转换（不被当做工具调用），导致死循环。

**修复方案**：在 `before_agent_callback` 中显式调用工具

```Python
# agents/strategy_agent/analysis_agent/agent.py

async def _explicit_call_tool(callback_context: CallbackContext):
    """
    解决模型升级后 LLM 不调用 transfer_to_agent 工具的问题
    """
    last_content = callback_context.agent_output
    if not last_content or not last_content.parts:
        return
    
    text = last_content.parts[0].text
    if not text or "transfer_to_agent" not in text:
        return
    
    # 解析出目标 Agent 名称
    agent_name = _parse_transfer_target(text)
    if not agent_name:
        return
    
    # 在回调中显式调用 transfer 函数
    await callback_context.actions.transfer_to_agent(agent=agent_name)
```

**原理**：在模型的输出到达 Agent 引擎之前，先检查是否包含 `transfer_to_agent` 文本，如果是则主动执行转移，而不是等 LLM 以工具调用的方式执行。

#### **第 4 层：历史截断（上下文管理）**

```Python
# before_model_callback 中截断历史
async def before_model_callback(self, callback_context, llm_request):
    messages = llm_request.messages
    if len(messages) > MAX_HISTORY_ROUNDS * 2:
        # 保留系统消息 + 最近 N 轮对话
        llm_request.messages = truncate_messages(messages)
```

**原理**：对话历史越长，LLM 越容易在上下文中"迷失"，做出错误的循环决策。截断历史可以减少这种风险。

### **业界方案**

### **不同场景的推荐策略**

### **关键发现**

PA 项目中有一个值得注意的**保护缺口**——AutoAgent 的 ADK 执行器里 `max_llm_calls=20` 被注释掉了：

```Python
# auto_agent/framework_adapter/adk/executor.py
# run_config = RunConfig(max_llm_calls=20) ← 被注释
```

这意味着 AutoAgent（用户自定义 Agent）**没有硬性循环上限**，依赖于 LLM 自身的判断来结束循环。这是一个已知但未修复的问题。

---

## **面试话术模板**

### **"介绍一下你的 Agent 项目"**

> 我参与的项目叫 PowerAgent，是一个面向企业业务洞察的多 Agent 系统。核心架构分为两层：Agent 推理层（Python，Google ADK）和工作流编排层（Java，自研引擎）。
> 
> Agent 推理层通过 IntentAgent 分发到 5 个子 Agent，每个子 Agent 负责一个环节（数据拉取、元数据查询、策略匹配、深度分析）。工作流引擎提供 50\+ 预置节点类型，通过统一的工具网关与 Agent 层双向调用。
> 
> 项目最初基于 LangGraph，后来迁移到 Google ADK，原因是 ADK 原生支持层次化多 Agent 和 Plan\-ReAct 推理，减少了大量手写编排代码。
> 
> 

### **"LangGraph 有什么缺点？"**

> 我们的场景是"业务分析师的多步骤推理"，不是"API 调用密集的 ReAct 循环"。LangGraph 在工具调用编排上很强，但在层次化多 Agent 上支持薄弱——需要手写 StateGraph \+ subgraph，状态隔离和调试都很困难。此外 LangChain 大版本升级带来的 API 不兼容也是一个生产环境的隐患。
> 
> 最终我们迁移到了 Google ADK，它原生的 transfer\_to\_agent 和 Plan\-ReAct 更贴合我们的需求。
> 
> 

### **"为什么不用现成的产品？"**

> 不是因为现成的产品不好，而是领域不匹配。Claude Code 是编程助手，它的工具集和工作模式不适合业务分析。MIP 是 LLM 调用网关，我们本来就在用，但它只负责模型路由和鉴权，不负责 Agent 编排。PA 是在这些基础设施之上构建的垂直领域 Agent，解决的是"业务人员如何用自然语言做数据洞察"这个具体问题。
> 
> 

### **"ReAct 和 Plan \& Execute 怎么选？"**

> 两者互补。ReAct 适合探索性任务——每步根据上一步结果实时决策；Plan \& Execute 适合确定性任务——一次性规划按部就班执行。Google ADK 的 Plan\-ReAct 是两者的结合。PA 中 IntentAgent 用 ReAct（用户意图不确定），子 Agent 内部用 Plan \& Execute（分析流程固定）。
> 
> 

### **"Agent 死循环怎么防？"**

> 我们 4 层防护：配置层 `disallow_transfer_to_parent/peers` 从拓扑上阻止循环；配置上限 `max_llm_calls=20` 兜底；回调层 `_explicit_call_tool()` 解决模型升级后不调工具的 Bug（2025 年 8 月真实经历，24 小时修复）；历史截断防止 LLM 在长上下文中迷失。
> 
> 不过 AutoAgent 的 `max_llm_calls` 被注释了是一个遗留缺口。
> 
> 

### **"MCP 和 Skill 有什么区别？"**

> MCP 是协议层（类比 HTTP），Skill 是应用层（类比 Web 应用），CLI 是执行接口。三者不是替代关系而是不同抽象层级——Skill 可以通过 MCP 调用外部服务，MCP Server 内部可以调用 CLI。一个类比：MCP = 螺丝刀的接口标准，Skill = 一把组装好的电动螺丝刀，CLI = 手动拧螺丝的操作方式。
> 
> 



# **Skill、MCP 与 Agent 循环面试 Q\&A**

---

<a id="q1_13"></a>
## **Q1: Skill 机制具体是做什么的？**

### **两种语境下的 Skill**

"Skill" 这个概念在 PA 项目语境和 Claude Code 语境中有不同的含义，需要先区分清楚。

#### **Claude Code 的 Skill**

Claude Code 的 Skill 是一种**可复用的 slash command 能力单元**，本质是一组预定义的 Prompt \+ 工具集 \+ 执行逻辑的封装：

```Plain Text
Skill = 触发词（/skill-name） + System Prompt + 工具权限 + 回调逻辑
```

用户键入 `/skill-name` → Claude Code 加载对应的 System Prompt 和工具集 → 在特定上下文中执行。

**特点：**

- **按需加载**：不是 Agent 上下文的一部分，只有用户触发时才注入

- **封装完整**：一个 Skill 包含完整的 Prompt、工具、权限声明

- **可发现**：用户在 UI 中可以看到所有可用的

- **可组合**：多个 Skill 可以组合使用

行业类比： \| 平台 \| 类似机制 \| \|\-\-\-\-\-\-\|\-\-\-\-\-\-\-\-\-\| \| **Claude Code** \| Skill（/command 触发） \| \| **OpenAI GPTs** \| 自定义 GPT \+ Actions \| \| **LangChain** \| Agent Executor \+ Tool 组合 \| \| **Coze** \| Bot \+ Plugin 插件市场 \|

#### **PA 项目的"Skill 等价物"——Plugin \+ Tool**

PA 项目中没有直接叫"Skill"的机制，但有两个等价概念：

1. **Plugin（插件系统）**：通过 `CustomPlugin(BasePlugin)` 实现的 ADK 生命周期钩子

2. **Tool（工具）**：通过统一网关 `/api/v1/tools/run` 调用的 5 种工具类型

**PA 的"Skill"更多体现为 Tool 的聚合**——一个业务能力单元 = 一个 Agent \+ 一组工具 \+ 一段 Prompt。

### **Skill 机制的核心价值**

无论是 Claude Code 的 Skill 还是 PA 的 Plugin，核心价值是三点：

1. **关注点分离**：每个 Skill 只关心自己领域的能力，不耦合其他逻辑

2. **可插拔**：新增一个 Skill 不需要修改核心框架代码

3. **权限隔离**：Skill 可以声明自己需要哪些权限，框架按需授权

#### **PA 的具体场景**

PA 没有直接实现 Skill 机制，但它的**子 Agent 体系**起到了类似的作用：

```Plain Text
IntentAgent（路由器）
    │
    ├── run_data_agent（Skill ≈ "数据查询"）
    │   ├── Tool: 查 MySQL
    │   ├── Tool: 查 ClickHouse
    │   └── Prompt: 理解表格查询意图
    │
    ├── metadata_agent（Skill ≈ "元数据查询"）
    │   ├── Tool: 查字段定义
    │   ├── Tool: 查枚举值
    │   └── Prompt: 理解元数据查询意图
    │
    ├── strategy_create_agent（Skill ≈ "策略生成"）
    │   ├── Tool: 代码执行
    │   ├── Tool: 数据拉取
    │   └── Prompt: 生成分析策略
    │
    └── analysis_agent（Skill ≈ "深度分析"）
        ├── Tool: 代码执行
        ├── Tool: MCP 工具
        └── Prompt: 执行分析逻辑
```

**区别在于**：Claude Code 的 Skill 由用户主动触发（键入命令），PA 的子 Agent 由 IntentAgent 自动路由（LLM 判断意图后分发）。

---

<a id="q2_14"></a>
## **Q2: 你在支持 Skill 机制这方面具体做了哪些开发工作？你的 Skill 里面会放代码吗？**

### **具体开发工作**

#### **ADK Plugin 生命周期实现**

开发了 `CustomPlugin(BasePlugin)`，实现了 10 个 ADK 生命周期回调：

**关键实现细节**（`after_model_callback`）：

```Python
async def after_model_callback(self, *, callback_context, llm_response):
    # 1. 记录事件日志到文件（用于问题排查）
    if LOG_ANALYSIS_FLAG:
        log_events = callback_context._invocation_context.session.events
        log_events.append(Event(...))
        await log(req_id, session_id, events)

    # 2. 去除 CustomLiteLlm 添加的 reasoning_content
    #    模型返回的思维链对前端无意义，且浪费带宽
    if isinstance(model, CustomLiteLlm) and llm_response?.content?.parts:
        llm_response.content.parts = [p for p in parts if not p.thought]

    # 3. 更新 session token 使用量（用于成本统计和限流）
    update_session_token_count(...)
```

#### **统一工具网关（Tool Gateway）**

开发了 `CommonTool` 框架，将 5 种工具类型通过统一的接口暴露给 Agent：

```Python
# 调用侧（Agent 侧）
tool = CommonTool(data, tool_type=tool_type, ...)
result = await tool.run_async(args=args, tool_context=context)

# 接收侧（agentflow-server 侧）
POST /api/v1/tools/run  →  AutoAgentToolController
    →  AutoAgentToolContext.toolRun()
    →  根据 type 字段分发到具体执行器
```

5 种 `tool_type`：

- **plugin**：内部插件（SQL 查询、API 调用等）

- **mcp**：MCP 外部服务

- **workflow**：子工作流

- **autoAgent**：嵌套子 Agent

- **dataset**：知识库检索

#### **工具定义的三次迭代**

当前 V3 的实现：

```Python
class CommonTool(BaseTool):
    def _get_declaration(self) -> FunctionDeclaration:
        desc = self.description
        # 自动补充 question 参数（autoAgent/workflow 需要）
        if self.tool_type in ["autoAgent", "workflow"]:
            self.data.parameters.properties["question"] = {
                "description": "用户输入参数",
                "type": "STRING"
            }
        return FunctionDeclaration(name=self.name, description=desc)
```

#### **MCP 工具的双层实现**

开发了两层 MCP 客户端（见代码）：

- **MCPClientTools**（ADK 原生 `MCPToolset`）：用于长期连接的场景，带 10 分钟缓存

- **MCPClientToolsNew**（原生 `mcp` 库）：每次调用新建 SSE 连接，带重试逻辑

原因是 ADK 的 MCPToolset 在长连接场景下稳定性不够好（连接断开后恢复困难），所以增加了第二层"无状态每次重连"的实现作为备选。

### **Skill 里面会放代码吗？**

**会，但分情况**：

#### **情况 1：代码在 Tool 执行器中（推荐方式）**

```Python
# 代码逻辑封装在 tool.run_async() 中
# 调用后端服务执行，不在 Agent 进程中
```

- 代码不在 Skill 本身，在 Tool 的执行端（agentflow\-server 或 MCP 服务端）

- Agent 只负责"调用哪个工具"，不负责"工具怎么执行"

- **优点**：Agent 不需要关心实现细节，工具逻辑独立更新

#### **情况 2：代码在 Agent Prompt 中（少数情况）**

有些 Agent 的 Prompt 中会包含少量代码逻辑，例如 `analysis_agent` 会生成 Python 代码并在沙箱中执行：

```Python
# analysis_agent 的代码执行流程
1. LLM 生成代码 → 2. 在沙箱中执行 → 3. 返回结果 → 4. 如果失败则重试
```

这部分代码是**动态生成的**，不是预置的。

#### **情况 3：Skill 作为配置（数据驱动）**

Skill 更多是**配置**而非代码：

```JSON
{
  "name": "data_query",
  "description": "数据查询工具",
  "type": "plugin",
  "parameters": {
    "type": "OBJECT",
    "properties": {
      "sql": { "type": "STRING" }
    }
  }
}
```

**总结**：

- **Tool 的定义是配置**（名称、描述、参数）

- **Tool 的执行是代码**（在后端执行）

- **Agent 本身不包含业务代码**，它只做意图识别和分发

- **唯一的例外是分析 Agent 的代码生成**——它让 LLM 动态生成 Python 代码执行数据分析

---

<a id="q3_15"></a>
## **Q3: MCP 和 Skill 的区别是什么？和 CLI 的区别呢？**

### **三者的定位差异**

### **详细区别**

#### **MCP vs Skill**

MCP 和 Skill **不是同一层的东西，可以组合使用**。

```Plain Text
Skill（应用层） → MCP 协议（传输层） → MCP Server（执行层）

           Skill 通过 MCP 调用外部工具
           Skill 也可以调用内部 Plugin（不走 MCP）
```

**在实际项目中**：

- MCP 是 PA 调用外部服务的一种方式（通过 `MCPClientTools`）

- Skill（子 Agent）是 PA 组织内部能力的方式

- **MCP 工具被注册为 Agent 的一个 Tool**，通过统一的 CommonTool 框架管理

#### **Skill vs CLI**

**一个具体的对比案例**：

```Plain Text
场景：在项目里找一个 bug

CLI 方式：
  grep -r "bug_pattern" src/
  git log --oneline | head -20
  vim src/file.py +42

Skill 方式：
  /code-review  # → 自动分析 diff，检查代码质量
  
区别：CLI 需要用户自己知道"找 bug 要 grep + git log + vim"，
Skill 把"代码审查"这个能力封装成一步到位。
```

### **三者的组合关系**

```Plain Text
┌──────────────────────────────────────────┐
│              Agent/Skill                   │
│  ┌────────────────────────────────────┐   │
│  │  /data-query ← Skill 触发         │   │
│  │   └─→ CommonTool（统一工具调用）   │   │
│  │        ├─→ MCP（协议层，调外部服务）│   │
│  │        ├─→ Plugin（内部 API）      │   │
│  │        └─→ CLI（进程级执行）       │   │
│  └────────────────────────────────────┘   │
└──────────────────────────────────────────┘
```

**MCP 是工具调用的标准协议**，**CLI 是系统命令的执行接口**，**Skill 是面向用户的能力封装**。三者不是替代关系，而是不同抽象层级——Skill 可以用 MCP 调外部服务，MCP Server 内部也可以调用 CLI。

---

<a id="q4_16"></a>
## **Q4: 工具链能否 Skill 化？**

### **什么是"工具链 Skill 化"**

将一系列工具调用序列封装成一个完整的 Skill，让用户通过一个简单的触发词完成复杂的多步骤操作。

```Plain Text
未 Skill 化（用户自己编排）：
  步骤 1：调用 A 工具 → 步骤 2：处理结果 → 步骤 3：调用 B 工具 → 步骤 4：生成报告

Skill 化后（封装成能力单元）：
  /generate-report → 自动完成步骤 1-4
```

### **能，但有限制条件**

#### **适合 Skill 化的工具链**

#### **不适合 Skill 化的工具链**

### **PA 项目中的实践**

PA 实际上已经在做"工具链 Skill 化"，但不是通过 Skill 机制，而是通过**子 Agent**\+**WorkFlow 引擎**：

#### **方式 1：子 Agent = 高级 Skill**

```YAML
子 Agent: strategy_create_agent
触发: IntentAgent 分发（"需要生成分析策略"）
工具链:
  1. get_latest_date_by_mcp → 获取最新数据时间
  2. DataPuller.save_local_cache → 拉取数据到本地
  3. 分析代码生成 → LLM 生成分析 Python 代码
  4. 执行代码 → 在沙箱中运行
  5. 格式化输出 → 前端可消费的结果
```

这本质上就是"工具链 Skill 化"——把"生成分析策略"这个多步骤流程封装成一个独立的 Agent 能力单元。

#### **方式 2：WorkFlow 节点 = 可视化 Skill**

agentflow\-server 的 50\+ 节点类型本质上也是 Skill 化——将常见的 AI 能力封装成可拖拽的节点：

#### **方式 3：Claude Code 的 Skill 机制直接支持**

Claude Code 的 Skill 本身就是这个思路的体现：

```Plain Text
/data-query       → 封装了"查询数据库"的工具链
/code-review      → 封装了"审查代码"的工具链
/debug            → 封装了"调试问题"的工具链
```

每个 Skill 都可以包含多个工具调用步骤，用户不需要关心内部实现。

### **局限性**

工具链 Skill 化有三个"难以逾越"的障碍：

1. **非确定性步骤**：如果工具链的下一步依赖于上一步的复杂结果（不是简单的是/否），AI 的推理能力就是瓶颈。比如"分析数据 → 根据分析结果决定下一步分析方向"——这本质上是 Agent 循环，不是 Skill 能封装的。

2. **状态共享**：多步骤之间需要共享状态，Skill 机制通常设计为无状态的（输入 → 处理 → 输出）。有状态的工具链需要更复杂的上下文管理。

3. **错误恢复**：工具链中某一步失败后的恢复策略很难通用化。PA 的做法是"子 Agent 内部 try/except 兜底"，但具体的重试逻辑还是需要针对每个工具链定制。

### **结论**

> 工具链可以 Skill 化，但不是万能的。**确定性高频的工具链最适合 Skill 化**；**需要复杂推理的多步骤流程更适合 Agent 循环**；PA 的做法是两者结合——简单工具链用 WorkFlow 节点封装，复杂分析流程用子 Agent \+ 代码生成实现。
> 
> 

---

<a id="q5_17"></a>
## **Q5: Agent 的循环流程是怎样的？也就是 ReAct 循环。**

### **ADK 的 Plan\-ReAct 循环**

PA 基于 Google ADK，ADK 的 Agent 循环是**Plan\-ReAct**，比标准的 ReAct 多了一个规划阶段。

```Plain Text
用户输入
    │
    ▼
┌──────────────────────────────────────┐
│  1. 规划阶段（Plan）                  │
│  LLM 输出：{PLANNING_TAG} 分解步骤... │
│  根据可用工具，制定执行计划           │
└──────────────────┬───────────────────┘
                   │
                   ▼
┌──────────────────────────────────────┐
│  2. 执行阶段（Action）                │
│  LLM 输出：{ACTION_TAG} 调工具       │
│  ↓                                   │
│  工具执行 → 返回结果                  │
│  ↓                                   │
│  LLM 输出：{REASONING_TAG} 推理当前   │
│  状态 + 判断是否继续调用工具          │
└──────────────────┬───────────────────┘
                   │
          ┌────────┴────────┐
          ▼                 ▼
      还有工具需要调用     已完成
          │                 │
          ▼                 ▼
    ┌─────────────┐  ┌──────────────────┐
    │ 重新规划     │  │ 3. 最终回答       │
    │ {REPLANNING} │  │ {FINAL_ANSWER}    │
    │ → 步骤 2     │  │ → 输出给用户     │
    └─────────────┘  └──────────────────┘
```

### **关键标签**

ADK Plan\-ReAct 定义了 5 个标签来控制循环：

### **实际执行流程（PA 的多 Agent 版本）**

PA 的 IntentAgent 运行在这个循环之上，但多了一层分发逻辑：

```Plain Text
用户输入 "2024 年 Q4 的销售额是多少？"
    │
    ▼
IntentAgent Plan-ReAct 循环
    │
    ├── PLANNING: "用户需要查询销售额数据，我需要调用 run_data_agent"
    │
    ├── ACTION: transfer_to_agent("run_data_agent", {query: "2024年Q4销售额"})
    │
    ├── [run_data_agent 子循环]
    │   ├── PLANNING: "先查表，再聚合"
    │   ├── ACTION: query_database("SELECT SUM(amount) FROM sales WHERE quarter='2024Q4'")
    │   ├── REASONING: "数据已返回，格式化为表格"
    │   └── FINAL_ANSWER: {rows: [{total: 1250000}], fields: [...]}
    │
    ├── REASONING: "run_data_agent 已返回结果，内容是表格数据，直接返回给用户"
    │
    └── FINAL_ANSWER: "2024年Q4的销售额为1,250,000元"
```

### **实际运行中的循环条件（代码确认）**

从 `executor.py` 中确认，**Plan\-ReAct 目前处于注释状态**：

```Python
# executor.py:247-249
# 有工具时才进行任务规划
# if agent.tools:
#     agent.planner = CustomPlanner()
```

这意味着 PA 的实际 Agent 循环是 **ADK 默认的 ReAct 循环**，没有显式的 Plan 阶段。LLM 直接在对话上下文中根据工具描述决定调用什么工具，不需要独立的规划阶段。

### **业界方案对比**

---

<a id="q6_18"></a>
## **Q6: Plan \& Execute 和 ReAct 两种范式有什么区别？分别适合什么场景？**

### **两种范式的定义**

**ReAct（Reasoning \+ Acting）**：

```Plain Text
循环：
  推理当前状态 → 决定行动（调用工具/回复） → 观察结果 → 再推理 → ...
```

特点：推理和行动**交织进行**，每一步走的决定取决于上一步的观察。

**Plan \& Execute（先计划再执行）**：

```Plain Text
阶段 1：制定完整计划（一次性）
  分析目标 → 分解步骤 → 列出所有需要的工具调用
阶段 2：按计划执行
  执行步骤 1 → 执行步骤 2 → ... → 执行步骤 N
阶段 3（可选）：必要时重新规划
  如果某步失败 → 回到阶段 1 修正计划
```

特点：先规划再执行，**规划阶段不调用工具**，执行阶段不改变计划（除非失败）。

### **核心区别**

### **一个具体的对比案例**

**场景：用户问"2024 年 Q4 销售额同比下降了多少？"**

#### **ReAct 方式**

```Plain Text
Step 1: LLM 推理 → "需要查 2024Q4 和 2023Q4 的数据"
        行动 → 调工具 A：查 2024Q4 销售额 → 返回 1,250,000
Step 2: LLM 推理 → "还需要 2023Q4 的数据来做对比"
        行动 → 调工具 B：查 2023Q4 销售额 → 返回 1,180,000
Step 3: LLM 推理 → "计算同比：(1,250,000-1,180,000)/1,180,000 ≈ 5.93%"
        回复 → "2024Q4 同比上升 5.93%"
```

问题：如果工具 A 返回时 LLM 发现还需要额外信息（比如要除以门店数），它会自然地在 Step 2 做调整。

#### **Plan \& Execute 方式**

```Plain Text
规划阶段：
  步骤 1：查 2024Q4 销售额
  步骤 2：查 2023Q4 销售额
  步骤 3：计算同比
执行阶段：
  步骤 1 → 返回 1,250,000
  步骤 2 → 返回 1,180,000
  步骤 3 → 计算 → 回复 "同比上升 5.93%"
```

问题：如果步骤 1 的结果表明"销售额需要按门店分组"，计划中没有这个步骤，需要触发重新规划。

### **分别适合什么场景**

### **实际项目中的混合使用**

PA 实际上**两者都用**：

```Plain Text
IntentAgent 级别：ReAct（灵活分发）
    ↓
子 Agent 内部：Plan & Execute（确定流程）
    ↓
代码生成阶段：ReAct（探索性分析）
```

- InterestAgent 用 ReAct 是因为意图识别后分发给哪个子 Agent 是不确定的

- 子 Agent 内部用 Plan \& Execute 是因为"拉取数据 → 分析 → 格式化"流程固定

- 代码生成阶段又回到 ReAct 是因为分析代码写出来后结果不确定，需要根据结果调整

### **面试话术**

> ReAct 和 Plan \& Execute 不是替代关系，而是互补的。**ReAct 适合探索性、不确定性高的任务**——每一步都根据上一步的结果实时决策。**Plan \& Execute 适合确定性、步骤固定的任务**——一次性规划好，按部就班执行。
> 
> 实际项目往往是混合的。我们项目中 IntentAgent 用的是 ReAct（因为用户意图不确定），子 Agent 内部用 Plan \& Execute（因为分析流程固定）。
> 
> Google ADK 的 Plan\-ReAct 是两者的结合——先规划、再执行、必要时重新规划。
> 
> 

---

<a id="q7_19"></a>
## **Q7: Agent 死循环问题怎么解决？**

### **死循环的几种类型**

### **PA 的解决方案（4 层防御）**

#### **第 1 层：静态阻断（配置层面）**

```Python
# 每个子 Agent 禁止转回父 Agent 和平级 Agent
llm_agent = LlmAgent(
    ...
    disallow_transfer_to_parent=True,   # 禁止转回父 Agent
    disallow_transfer_to_peers=True,    # 禁止转给同级 Agent
)
```

**原理**：直接从 ADK 框架层面禁止 A→B→A 的循环。这是最简单最有效的防护。

#### **第 2 层：硬性上限（运行配置）**

```Python
run_config = RunConfig(
    max_llm_calls=20,  # 最多调用 20 次 LLM
)
```

**原理**：不管什么原因，达到 20 次 LLM 调用后强制终止。这是最后一道防线。

**业界标准**：

#### **第 3 层：回调层干预（针对模型 Bug）**

这是 PA 遇到过的一个实际问题——2025 年 8 月模型升级后，LLM 不再自动调用工具，而是输出 `transfer_to_agent` 文本。这在 ADK 中是一个不完整的状态转换（不被当做工具调用），导致死循环。

**修复方案**：在 `before_agent_callback` 中显式调用工具

```Python
# agents/strategy_agent/analysis_agent/agent.py

async def _explicit_call_tool(callback_context: CallbackContext):
    """
    解决模型升级后 LLM 不调用 transfer_to_agent 工具的问题
    """
    last_content = callback_context.agent_output
    if not last_content or not last_content.parts:
        return
    
    text = last_content.parts[0].text
    if not text or "transfer_to_agent" not in text:
        return
    
    # 解析出目标 Agent 名称
    agent_name = _parse_transfer_target(text)
    if not agent_name:
        return
    
    # 在回调中显式调用 transfer 函数
    await callback_context.actions.transfer_to_agent(agent=agent_name)
```

**原理**：在模型的输出到达 Agent 引擎之前，先检查是否包含 `transfer_to_agent` 文本，如果是则主动执行转移，而不是等 LLM 以工具调用的方式执行。

#### **第 4 层：历史截断（上下文管理）**

```Python
# before_model_callback 中截断历史
async def before_model_callback(self, callback_context, llm_request):
    # 如果历史消息超过阈值，截断最早的消息
    messages = llm_request.messages
    if len(messages) > MAX_HISTORY_ROUNDS * 2:
        # 保留系统消息 + 最近 N 轮对话
        llm_request.messages = truncate_messages(messages)
```

**原理**：对话历史越长，LLM 越容易在上下文中"迷失"，做出错误的循环决策。截断历史可以减少这种风险。

### **业界方案**

### **不同场景的推荐策略**

### **关键发现**

PA 项目中有一个值得注意的**保护缺口**——AutoAgent 的 ADK 执行器里 `max_llm_calls=20` 被注释掉了：

```Python
# auto_agent/framework_adapter/adk/executor.py (具体行数可能变化)
# run_config = RunConfig(max_llm_calls=20) ← 被注释
```

这意味着 AutoAgent（用户自定义 Agent）**没有硬性循环上限**，依赖于 LLM 自身的判断来结束循环。这是一个已知但未修复的问题。

### **面试话术**

> Agent 死循环是生产环境中最常见也最隐蔽的问题。我们的防护是分层的：**静态层面**用 `disallow_transfer_to_parent/peers` 从拓扑上阻止 A→B→A 的循环；**动态层面**用 `max_llm_calls=20` 硬性限制最大调用次数；**适配层面**通过 `before_agent_callback` 显式调工具来解决模型升级导致的不兼容问题。
> 
> 最有意思的是第三个——2025 年 8 月模型升级后 LLM 不再自动调用工具，而是输出文本。我们不是等框架修，而是在回调层做了"拦截 \+ 显式执行"的适配，24 小时内上线修复。
> 
> 不过也有遗留问题：AutoAgent 的 ADK 执行器里 `max_llm_calls` 被注释了，用户自定义 Agent 目前没有硬性上限。
> 
> 

---

<a id="q12_20"></a>
## **Q12: Agent 大模型请求的上下文具体是怎么分层组装的？**

### **三层组装架构**

PA 的 LLM 请求上下文分为三层，从内到外依次组装：

```Plain Text
第 1 层：基础 System Prompt（get_prompt）
  └── agent_meta.promptInfo 替换 {{variable}}
  └── 背景知识（rag_options.quoteQA）
  └── 长期记忆（agent_meta.longTermMemory）
  └── 引用规则（quote_prompt）
  └── 多模态兜底说明

第 2 层：会话历史（_init_session）
  └── 从 chatHistory 还原为 ADK Event
  └── 写入 ADK Session（Redis 持久化）
  └── before_model_callback 截断到 N 轮

第 3 层：动态注入（append_instructions）
  └── code_agent：注入数据文件信息、CSV 样例、字段名、当前日期
  └── run_data_agent：注入当前日期
  └── 策略上下文：TEMP_CURRENT_PYCODE + TEMP_CURRENT_PYCODE_EXCEPTION
```

### **第 1 层：System Prompt 组装**

在 `adk/executor.py:211-232`，`get_prompt()` 按顺序拼接：

```Python
# 1. 基础 Prompt（替换变量）
instruction = agent_meta.promptInfo
instruction = instruction.replace("{{variable}}", value)

# 2. 背景知识（临时知识库检索结果）
if rag_options.quoteQA:
    instruction += """
【背景知识】
如果背景知识中存在与问题直接相关的信息，请优先且仅使用这些信息进行总结或回答
以下是背景知识
{rag}
"""

# 3. 长期记忆
if agent_meta.longTermMemory:
    instruction += "【长期记忆】以下是和问题相关的记忆\n" + agent_meta.longTermMemory

# 4. 引用规则
if knowledge_base_enabled and showSource:
    instruction += quote_prompt  # 来自 prompt.py

# 5. 多模态兜底
instruction += "\n注意：如果生成内容包含图片，请用 markdown 格式输出"
```

### **第 2 层：会话历史写入 ADK Session**

在 `adk/executor.py:65-96`，`_init_session()` 将前端传来的 `chatHistory` 反写为 ADK Event：

```Python
for item in chat_history:
    if item.obj == "Human":
        event = Event(author="user", role="user",
                      content=types.Part.from_text(text=item.value))
    else:
        event = Event(author=agent_meta.name, role="model",
                      content=types.Part.from_text(text=item.value))
    session.append_event(event)
```

这些 Event 随后被 ADK 框架自动转换为 `llm_request.contents`，成为发送给模型的 messages 数组的一部分。

### **第 3 层：动态注入（每个请求可自定制）**

子 Agent 通过 `llm_request.append_instructions()` 在每次 LLM 调用前注入动态上下文：

- **code\_agent**（`code_agent/agent.py:27-57`）：注入数据文件信息、字段名列表、CSV 前几行样例、当前日期

- **run\_data\_agent**（`run_data_agent/agent.py:126-141`）：注入当前日期，同时检查 instruction 是否超过 40K token 限制

- **strategy\_create\_agent**（`strategy_create_agent/agent.py`）：重试时注入之前生成的错误代码和异常信息

### **最终发往模型的 messages 结构**

```Plain Text
messages = [
    {"role": "system",    "content": "<第 1 层组装好的 instruction>"},
    {"role": "user",      "content": "<第 2 层中的第 1 个用户问题>"},
    {"role": "assistant", "content": "<第 2 层中的 AI 回复>"},
    {"role": "user",      "content": "<第 2 层中的第 2 个用户问题>"},
    {"role": "assistant", "content": "<第 2 层中的 AI 回复 + 工具调用>"},
    {"role": "tool",      "content": "<工具执行结果>"},
    ...  （截断到 historyRound + 1 轮） ...
    {"role": "user",      "content": "<当前用户问题>"},
]
```

---

<a id="q13"></a>
## **Q13: 上下文结构是不是：System Prompt \+ Memory \+ Tools Result \+ Conversation？**

### **准确的结构是四层叠加**

是的，但需要准确说明每一层的**来源**和**组装时机**：

```Plain Text
LLM Request Context = System Prompt + Session History + Tool Results + Dynamic Injection
```

### **区别于一般认知的关键点**

1. **Memory 并不独立存在**——长期记忆被拍平到 System Prompt 中，不是单独的 messages role

2. **Tool Results 不由 PA 代码管理**——ADK 框架自动将工具结果转换为 `function_response` part 追加到 `contents`，PA 只通过 `skip_summarization` 控制模型是否总结

3. **Dynamic Injection 是 PA 的特色**——不是所有 Agent 框架都支持在每次请求前动态追加指令。PA 通过 `before_model_callback` 实现了这一点：

```Python
# code_agent/agent.py:before_model_callback
if data_info:
    llm_request.append_instructions(f"数据文件信息：{data_info}")
if data_headers:
    llm_request.append_instructions(f"数据字段：{data_headers}")
```

### **一个具体的 messages 示例**

```JSON
[
  {"role": "system", "content": "你是一个数据分析助手..."},
  {"role": "user", "content": "2024年销售额是多少？"},
  {"role": "assistant", "content": "", "tool_calls": [{"name": "query_data", "args": {...}}]},
  {"role": "tool", "content": "1250000", "tool_call_id": "call_1"},
  {"role": "assistant", "content": "2024年销售额为1,250,000元"},
  {"role": "user", "content": "同比增长了多少？"}  ← 当前问题
]
```

### **Memory 在 PA 中的实际角色**

PA 有两种"记忆"：

---

<a id="q14"></a>
## **Q14: 上下文超长了怎么办？有没有考虑过上下文压缩？**

### **PA 的两层截断策略（无压缩）**

PA **没有实现真正的上下文压缩**（如摘要压缩、向量化压缩），而是用了两层**截断**策略：

#### **第 1 层：每次 LLM 调用前的轮次截断（细粒度）**

在 `before_model_callback` 中，每次请求 LLM 前截断到最近 `historyRound + 1` 轮：

```Python
# adk/executor.py:368-388
async def before_model_callback(self, callback_context, llm_request):
    user_indices = [i for i, item in enumerate(llm_request.contents)
                    if item.role == "user" and item.parts and item.parts[0].text]

    keep_turns = min((self.agent_meta.modelInfo.historyRound or 3) + 1, len(user_indices))
    start_user_idx = user_indices[-keep_turns]

    llm_request.contents = llm_request.contents[start_user_idx:]
```

**参数来源**：`agent_meta.modelInfo.historyRound`，前端可配置，默认值为 3。 **效果**：保留最近 4 轮对话（3 轮历史 \+ 当前轮），丢弃更早的所有内容（包括工具调用记录）。

#### **第 2 层：会话级整轮丢弃（粗粒度）**

在 `in_redis_session_service.py:154-244`，当 Session 中累积的事件总 token 数超过 30K 时触发：

```Python
# 简化逻辑
limit = MEMORY_SESSION_TOKEN_COUNT_LIMIT - MEMORY_SESSION_TOKEN_COUNT_RESERVED_SPACE
# = 40K - 10K = 30K

if total_tokens > limit:
    # 保留当前 invocation + 尽可能多的历史 invocation
    # 以 entire invocation 为单位丢弃（不会切分单轮对话）
    reset_session_events(session, keep_invocations)
```

**配置参数**（`settings.py:84-86`）：

```Python
MEMORY_SESSION_TOKEN_COUNT_LIMIT = env.int("AGENT_MEMORY_SESSION_TOKEN_COUNT_LIMIT", 40 * 1024)
MEMORY_SESSION_TOKEN_COUNT_RESERVED_SPACE = env.int("AGENT_MEMORY_SESSION_TOKEN_COUNT_RESERVED_SPACE", 10 * 1024)
```

### **为什么不做真正"压缩"（Project\-level key note）**

PA 团队**没有采用**摘要压缩或软压缩方案，原因是：

### **与业界方案的对比**

### **面试话术**

> PA 的上下文管理用的是"截断"而不是"压缩"策略。两层截断：**轮次级别**在每次 LLM 调用前保留 `historyRound + 1` 轮对话，**会话级别**在总 token 超过 30K 时丢弃整轮 invocation。没有做摘要压缩或向量化压缩，主要考虑是避免额外的 LLM 开销和系统复杂度。
> 
> 这种方案的优点是实现简单、性能开销几乎为零，缺点是粗暴——如果关键信息在截断范围之外，模型就会"失忆"。在实际使用中，数据查询类的 Agent 对历史依赖不强，这个方案够用；但如果是长对话深度分析场景，上下文丢失会导致 Agent 需要重复查询。
> 
> 

---

<a id="q15"></a>
## **Q15: 压缩过程中会丢失工具调用历史，导致模型重复调用工具，怎么解决？**

### **问题根源**

轮次截断会**整体丢弃**超过保留轮数的 `contents`，其中包含工具调用的 `function_call` 和 `function_response` 对。截断后 LLM 看到的上下文里没有"已经查过数据"的记录，自然会重新调用工具。

```Plain Text
截断前（contents 数组）：
  [user, assistant, user, assistant+tool_call, tool_result, ...user]
                                                     ↑
                                             当前保留的起始位置

截断后：
  [tool_result, user, assistant+tool_call, tool_result, user]
  ↑
  工具调用记录和结果不再配对，造成混乱
```

轮次截断的 `before_model_callback` 以 **user message 为分割点**做切片，如果一个工具调用和它的结果跨越了截断边界，就会丢失配对关系。

### **PA 的实际做法**

#### **方案 1：靠 Sub\-agent 隔离来避免问题**

PA 的策略是将**可能重复调用的工具逻辑封装在独立的 Sub\-agent 中**，Sub\-agent 每次接收新的请求，内部自行拉取数据，不依赖历史上下文中的工具调用记录。

```Plain Text
错误示例：同一个 Agent 内
  User: "查Q4销售额"
  → LLM 调工具 → 得到结果
  → 上下文被截断
  User: "和Q3对比"  
  → LLM 没看到刚才的结果 → 重新调工具查Q4 → 浪费一次调用

PA 的做法：分 Agent
  run_data_agent: 查Q4销售额 → 返回数据 → 结束
  截断发生在 run_data_agent 内部，不影响父 Agent
  父 Agent 拿着结果去调 analysis_agent
```

**核心思想**：每个 Sub\-agent 只处理一次工具调用，做完就返回结果。工具调用的"状态"体现在 Sub\-agent 的输出中（结构化数据），而不是在对话历史里。

#### **方案 2：结果状态化——用 state 而不是历史来保存工具结果**

PA 不会依赖对话历史中的工具调用记录，而是将关键结果写入 `callback_context.state`：

```Python
# after_tool_callback 中
state[DATASET_INFO] = knowledge_base_results
state[TEMP_CURRENT_PYCODE] = generated_code
state[AGENT_OUTPUT_DECISION_RESULT] = decision_result
```

当下一次需要这些数据时，从 state 中读取，而不是指望对话历史中的 tool\_result 还在：

```Python
# before_model_callback 或其他回调中
previous_code = callback_context.state.get(TEMP_CURRENT_PYCODE)
if previous_code:
    llm_request.append_instructions(f"之前生成的代码：{previous_code}")
```

#### **方案 3：重试时通过 state 喂回错误信息**

code\_agent 的重试场景是一个典型例子——代码执行失败后，上下文被截断，但 LLM 需要知道刚才的代码和错误：

```Python
# strategy_create_agent/agent.py:run_code_agent
for _ in range(MULTI_AGENT_GEN_CODE_RETRY_COUNT):
    ctx.session.state[TEMP_CURRENT_PYCODE] = data["code"]
    ctx.session.state[TEMP_CURRENT_PYCODE_EXCEPTION] = tmp["msg"]
    # 在 code_agent.before_model_callback 中：
    # llm_request.append_instructions(f"上次代码：{state[TEMP_CURRENT_PYCODE]}，错误：{state[TEMP_CURRENT_PYCODE_EXCEPTION]}")
```

### **业界方案**

### **面试话术**

> 这确实是截断方案的一个固有问题。我们主要靠**状态化**来解决：关键的工具调用结果不依赖对话历史中的 tool\_result，而是写入 `callback_context.state`，在需要时通过 `append_instructions` 重新注入。
> 
> 另一个有效做法是**Agent 职责分离**——每个 Sub\-agent 只做一次工具调用，拿到结果就返回。这样截断只影响 Sub\-agent 内部的对话轮次，不会丢失跨 Agent 的关键数据。父 Agent 拿到的是 Sub\-agent 的结构化输出，而不是历史中的 tool\_result 记录。
> 
> 

---

<a id="q16"></a>
## **Q16: 长期运行导致上下文膨胀问题怎么处理？**

### **PA 面对的场景**

PA 的 Agent 是**一次请求一次调用**的模式（用户发一个问题 → IntentAgent 分发 → Sub\-agent 执行 → 返回结果），不是长对话场景。但有两种情况会导致上下文膨胀：

1. **多轮对话**：用户连续追问，`chatHistory` 不断累积

2. **复杂工具链**：Agent 内部多次调用工具，单轮对话中的 `contents` 膨胀

### **PA 的应对方案**

#### **方案 1：轮次截断（解决"多轮对话膨胀"）**

如前所述，`before_model_callback` 保留 `historyRound + 1` 轮。这是主要防御手段。

#### **方案 2：Session 级 Token 限制（解决"单轮工具调用过多"）**

`InRedisSessionService.check_session_token_count()` 在总 token 超过 30K 时，以 **entire invocation** 为单位丢弃历史：

```Python
# in_redis_session_service.py
for inv_id in reversed(invocation_ids):
    if current_invocation_id == inv_id:
        continue  # 不丢弃当前轮
    if accumulated + inv_tokens <= keep_token_limit:
        accumulated += inv_tokens
        keep_invocations.append(inv_id)
    else:
        break  # 超出限制，丢弃更早的
```

**关键是"以 invocation 为单位丢弃"**——不会切分单次请求内的工具调用链，保证了单次 Agent 执行的完整性。

#### **方案 3：Sub\-agent 隔离（解决"工具链膨胀"）**

SequentialAgent 的子 Agent 之间上下文不共享。`strategy_agent` 执行 `decision_agent → strategy_create_agent → analysis_agent` 时，每个子 Agent 看到的是自己的上下文，不是前面 Agent 的完整工具调用链。只有 state 中保存的关键数据传递到下一阶段。

```Plain Text
没有隔离时：
  strategy_agent 的 contents =
    [decision 的 system + user + tool_call + tool_result × N +
     strategy_create 的 system + user + tool_call + tool_result × M +
     analysis 的 system + user + tool_call + tool_result × K]

有隔离时（实际）：
  decision_agent 的 contents = [system, user, tool_call, tool_result]
  → 输出写到 state
  strategy_create_agent 的 contents = [system, user, tool_call, tool_result]
  → 输出写到 state
  analysis_agent 的 contents = [system, user, tool_call, tool_result]
```

### **长期运行场景的不足之处**

PA 主要面向**单次请求**场景，对于"持续运行数小时的长对话"场景，有两个未解决的问题：

### **业界方案对比**

### **面试话术**

> PA 不是长对话场景，上下文膨胀主要靠两层截断控制：**轮次截断**限制每次 LLM 看到的对话轮数，**Session 级 Token 限制**在总 token 超过 30K 时以整轮 invocation 为单位丢弃历史。
> 
> 此外，**Sub\-agent 隔离**从架构上限制了膨胀——SequentialAgent 的子 Agent 不共享上下文，每个子 Agent 只看到自己的对话历史，工具调用链再长也不会跨 Agent 累积。
> 
> 但坦白说，PA 没有真正的"上下文压缩"——如果需要长期运行的 Agent，摘要压缩或向量化记忆是必要的补充。我们的场景里 Agent 通常是"一问一答"模式，截断够用。
> 
> 

---

<a id="q17"></a>
## **Q17: Multi\-Agent 中各 Agent 怎么通信？上下文怎么管理？**

### **通信方式（3 种）**

PA 的 Multi\-Agent 系统有三种通信方式：

#### **方式 1：****`transfer_to_agent`****（LLM 驱动的路由）**

Intent Agent 通过 LLM 判断用户意图，调用 `transfer_to_agent` 将控制权转给子 Agent：

```Python
# intent_agent/prompt.py
"""你必须使用 transfer_to_agent 调用子代理：
- transfer_to_agent(metadata_agent): 元数据查询
- transfer_to_agent(strategy_agent): 策略分析
- transfer_to_agent(run_data_agent): 数据查询
- transfer_to_agent(direct_analysis_agent): 直接分析
"""
```

ADK 框架将 `transfer_to_agent` 实现为一种特殊的工具调用（function\_call），LLM 选择调用哪个子 Agent 就跟选择工具一样。

```Plain Text
IntentAgent LLM 输出：
  function_call: transfer_to_agent(args={agent_name: "strategy_agent"})
  ↓
ADK 框架拦截 → 挂起 IntentAgent → 启动 strategy_agent
  ↓
strategy_agent 执行完毕 → 返回结果给 IntentAgent
  ↓
IntentAgent 继续执行 → 总结结果 → 输出给用户
```

#### **方式 2：SequentialAgent 固定管道（无 LLM 路由）**

两个 SequentialAgent 让子 Agent 按固定顺序执行，不需要 LLM 决策：

```Python
# strategy_agent/agent.py
SequentialAgent(
    sub_agents=[decision_agent, strategy_create_agent, analysis_agent]
)
```

每个子 Agent 的**输出**通过 `callback_context.state` 传递，子 Agent 之间**不直接对话**。

#### **方式 3：手动调用子 Agent（程序化控制）**

`strategy_create_agent` 手动调用 `code_agent`，共享 InvocationContext：

```Python
# strategy_create_agent/agent.py:299-329
ctx = code_agent._create_invocation_context(callback_context._invocation_context)
ctx.session.state[TEMP_CURRENT_PYCODE] = ""  # 共享 state
async for event in code_agent._run_async_impl(ctx):
    ...  # 处理子 Agent 的流式事件
```

### **上下文管理（3 层隔离）**

### **关键设计：共享 State 的约定优于框架**

PA 没有使用 ADK 的高级通信原语，而是**通过共享 state \+ 约定式键名**来实现通信：

```Python
# common/state_key.py —— 所有通信键的中央注册表

# 决策阶段
AGENT_OUTPUT_DECISION_RESULT = "decision_result"        # decision_agent → strategy_create_agent
# 策略生成阶段
AGENT_OUTPUT_STRATEGY_CREATE_RESULT = "strategy_create_result"  # strategy_create_agent → analysis_agent
# 参数提取阶段
AGENT_OUTPUT_PARAM_AGENT_RESULT = "param_agent_result"  # param_extract_agent → execute_dataflow_agent
# 临时数据
TEMP_CURRENT_PYCODE = "temp:current_pycode"              # strategy_create_agent → code_agent
TEMP_CURRENT_PYCODE_EXCEPTION = "temp:current_pycode_exception"
```

**这样做的好处**：

- Agent 之间**松耦合**——不直接调用对方的方法

- **可追溯**——所有通信键在一个文件里集中管理

- **跨 Agent 类型通用**——无论 LLM Agent 还是 BaseAgent，都通过 state 通信

### **通信流程全景（以策略分析为例）**

```Plain Text
User → IntentAgent
  │  [transfer_to_agent(strategy_agent)]
  ▼
strategy_agent (SequentialAgent)
  │
  ├── decision_agent
  │   ├── 从 MCP 获取策略列表 → 写入 state[TEMP_DECISION_ALL_STRATEGIES]
  │   ├── LLM 选择策略
  │   └── 输出 → state[AGENT_OUTPUT_DECISION_RESULT]
  │
  ├── strategy_create_agent
  │   ├── 读取 state[AGENT_OUTPUT_DECISION_RESULT]
  │   ├── 如果选择了已有策略：从 MCP 加载
  │   ├── 否则：LLM 生成新策略
  │   ├── 手动调用 code_agent（共享 state 传递代码和错误信息）
  │   └── 输出 → state[AGENT_OUTPUT_STRATEGY_CREATE_RESULT]
  │
  └── analysis_agent
      ├── 读取 state[AGENT_OUTPUT_STRATEGY_CREATE_RESULT]
      ├── 执行策略代码过滤数据
      ├── LLM 分析
      └── 输出 → state[AGENT_OUTPUT_ANALYSIS_RESULT]
```

---

<a id="q18"></a>
## **Q18: 多智能体系统中如何解决"无限循环"或"通信冗余"问题？**

### **PA 的 4 层防御**

#### **第 1 层：拓扑限制——禁止向上/平级转移**

```Python
disallow_transfer_to_parent = True   # 不能转回父 Agent
disallow_transfer_to_peers = True    # 不能转给同级 Agent
```

**效果**：子 Agent 只能接受控制权并执行任务，无法发起新的转移。从拓扑上切断了 A→B→A 的循环路径。

所有 LLM 子 Agent 都设置了这两项（`analysis_agent` 例外——但它没有工具，也无从转移）。

#### **第 2 层：硬性上限——****`max_llm_calls=20`**

```Python
# multi_agent/api.py:87
run_config = RunConfig(max_llm_calls=20)
```

**效果**：无论是死循环还是合法调用，总共最多 20 次 LLM 调用后强制终止。这是最后一道防线。

#### **第 3 层：****`explicit_call_tool`** **回调修复（针对模型 Bug）**

2025 年 8 月模型升级后，LLM 不自动调工具，而是输出 `transfer_to_agent` 文本。这在 ADK 中不被识别为工具调用，导致死循环。

修复见 `analysis_agent/agent.py` 的 `_explicit_call_tool()`——在回调层拦截文本，显式执行转移。

#### **第 4 层：SequentialAgent 天然防循环**

`SequentialAgent` 按固定顺序执行子 Agent，不存在 LLM 决策路由的环节，从根本上消除了循环的可能。

```Plain Text
SequentialAgent 的行为：
  decision_agent → 执行完自动结束
  strategy_create_agent → 执行完自动结束
  analysis_agent → 执行完自动结束
  整个 strategy_agent 结束 → 返回父 Agent
```

### **通信冗余问题**

PA 的 Multi\-Agent 中"通信冗余"指：**同一个上下文在多 Agent 间重复传递，造成 token 浪费**。

#### **PA 中存在的冗余**

#### **PA 控制冗余的做法**

1. **Agent 输出精简**——子 Agent 的输出经过结构化（Pydantic OutputSchema），只包含关键数据，不是完整对话：

```Python
# decision_agent 的输出
class OutputSchema(BaseModel):
    select_strategy_id: str      # 只输出选择了哪个策略
    select_reason: str           # 选择的原因（给用户看的）
    param_value: Optional[str]   # 策略参数
```

1. **按需传递**——下游 Agent 只读取它需要的 state key，不是全量复制

2. **SequentialAgent 的天然隔离**——每个子 Agent 的完整调用链不会传递到下一个

### **与业界方案的对比**

### **面试话术**

> 无限循环主要靠三层：**拓扑限制**禁止子 Agent 转回父/同级；**`max_llm_calls=20`** 做硬性兜底；**SequentialAgent** 的固定管道从根本上消除了路由死循环的可能。
> 
> 通信冗余方面，PA 的做法是**结构化输出 \+ 按需传递**——子 Agent 输出经过 OutputSchema 精简，下游只读取需要的 state key。每个子 Agent 的完整调用链对其他 Agent 不可见，避免了"全套上下文传递"的浪费。
> 
> 

---

<a id="q19"></a>
## **Q19: Subagent 设计是怎样的？**

### **四种 Sub\-agent 模式**

PA 的 Sub\-agent 有四种设计模式，分别对应不同的场景：

```Plain Text
┌────────────────────────────────────────────┐
                    │              IntentAgent                    │
                    │  (LLM dispatcher: transfer_to_agent)       │
                    └──┬────┬────┬────┬────┬────┬────┬──────────┘
                       │    │    │    │    │    │    │
         ┌─────────────┘    │    │    │    │    │    └──────────────┐
         ▼                  │    │    │    ▼    ▼                   ▼
   ┌──────────┐             │    │    │  ┌──────────────┐    ┌──────────┐
   │metadata  │             │    │    │  │run_data      │    │strategy  │
   │_agent    │             │    │    │  │_agent        │    │_view     │
   │(Base)    │             │    │    │  │(LlmAgent)    │    │(Base)    │
   └──────────┘             │    │    │  └──────────────┘    └──────────┘
                            │    │    │
                   ┌────────┘    │    └────────┐
                   ▼             ▼             ▼
           ┌──────────────┐ ┌──────────────┐ ┌────────────────────┐
           │strategy      │ │direct        │ │(future agents)    │
           │_agent        │ │_analysis     │ │                   │
           │(Sequential)  │ │(Sequential)  │ │                   │
           └──────┬───────┘ └──────┬───────┘ └────────────────────┘
                  │                │
       ┌──────────┼──────────┐    └────────────┐
       ▼          ▼          ▼                 ▼
  ┌────────┐ ┌────────┐ ┌────────┐   ┌──────────────┐
  │decision│ │strategy│ │analysis│   │param_extract │
  │_agent  │ │create  │ │_agent  │   │_agent        │
  │(LLM)   │ │_agent  │ │(LLM)   │   │(LLM)         │
  └────────┘ │(LLM)   │ └────────┘   └──────────────┘
             └────────┘                     │
                 │(手动调用)           ┌──────────────┐
                 ▼                     │execute       │
           ┌──────────┐                │dataflow      │
           │code_agent│                │_agent        │
           │(LLM)     │                │(Base)        │
           └──────────┘                └──────────────┘
```

#### **Pattern A：LLM 驱动的子 Agent（****`CustomLlmAgent`****）**

**定位**：需要 LLM 理解用户意图、生成内容或决策的任务。

**关键组件**：

- `instruction`（System Prompt）——定义 Agent 角色和规则

- `sub_agents`——注册的子 Agent（若有下级分发）

- 回调 4 件套——`before_agent`、`before_model`、`after_model`、`after_agent`

- `output_schema`——结构化输出定义

- `disallow_transfer_to_parent/peers`——循环防护

**适用场景**：需要 LLM 推理能力的任务（策略选择、数据分析、代码生成）。

#### **Pattern B：Tool\-only 子 Agent（****`BaseAgent`** **直调）**

**定位**：不需要 LLM，直接调用 MCP 工具返回结果。

**实现方式**——绕过 LLM，在 `_run_async_impl` 中直接调 MCP 工具：

```Python
# metadata_agent/agent.py
class MetadataAgent(BaseAgent):
    async def _run_async_impl(self, ctx):
        result = await insightMCPTools.execute(
            tool_name=METADATA_TOOL_NAME,
            args={"question": ctx.session.state["request_params"]["question"]}
        )
        yield self._create_tool_response_event(result)
```

**适用场景**：元数据查询、策略视图获取、快速数据流执行。速度比 LLM Agent 快很多（无模型调用）。

**特点**：没有 `instruction`、没有 `model`、没有 `disallow_transfer`——因为没有 LLM，不需要任何大模型相关配置。

#### **Pattern C：SequentialAgent 容器**

**定位**：将多个子 Agent 编排为固定顺序的流水线。

```Python
# strategy_agent/agent.py
SequentialAgent(
    sub_agents=[decision_agent, strategy_create_agent, analysis_agent],
)
```

**特点**：

- 没有自己的 LLM 调用

- 所有子 Agent 按注册顺序依次执行

- 执行流：子 Agent 1 → 子 Agent 2 → \.\.\. → 子 Agent N

- 子 Agent 之间通过 `callback_context.state` 共享数据

- 天然防循环（无路由决策）

#### **Pattern D：手动调用子 Agent（程序化生命周期）**

**定位**：需要精细控制子 Agent 生命周期的场景。

```Python
# strategy_create_agent 手动调用 code_agent
ctx = code_agent._create_invocation_context(callback_context._invocation_context)
for _ in range(MULTI_AGENT_GEN_CODE_RETRY_COUNT):
    async for event in code_agent._run_async_impl(ctx):
        ...
    # 如果失败，更新 state 重试
    ctx.session.state[TEMP_CURRENT_PYCODE] = generated_code
```

**核心差异**：这个子 Agent 不是通过 ADK 的路由机制调用，而是由父 Agent 在回调中**主动创建 context \+ 调用 run\_async\_impl**。父 Agent 可以在循环中控制重试次数、检查结果、更新状态。

### **四种模式对比**

### **Sub\-agent 的创建和注册**

Sub\-agent 通过统一的工厂函数创建，每个 Agent 文件中的 `create_agent()` 是标准入口：

```Python
# agents/strategy_agent/decision_agent/agent.py
def create_agent() -> CustomLlmAgent:
    return CustomLlmAgent(
        name=AGENT_DECISION_AGENT,
        model=ModelFactory.create_model(max_completion_tokens=10240),
        instruction=get_instruction(),
        output_key=AGENT_OUTPUT_DECISION_RESULT,
        output_schema=OutputSchema,
        before_agent_callback=before_agent_callback,
        after_agent_callback=after_agent_callback,
        disallow_transfer_to_parent=True,
        disallow_transfer_to_peers=True,
    )
```

Intent Agent 通过 `sub_agents` 参数注册所有子 Agent：

```Python
# intent_agent/agent.py
root_agent = CustomLlmAgent(
    sub_agents=[metadata_agent, strategy_agent, strategy_view_agent,
                direct_analysis_agent, run_data_agent],
    ...
)
```

### **Sub\-agent 设计原则**

1. **单一职责**——每个 Sub\-agent 只做一件事（查元数据、选策略、生成代码\.\.\.）

2. **输出结构化**——通过 `output_schema` 定义明确的输出格式

3. **无状态通信**——通过 `callback_context.state` 共享数据，不直接调用其他 Agent

4. **拓扑安全**——LLM 子 Agent 全部设置 `disallow_transfer_to_parent/peers`

5. **可替换**——`create_agent()` 是标准工厂函数，可以独立测试每个 Agent

### **面试话术**

> Sub\-agent 有四种模式：**LLM 驱动**的子 Agent 用于需要推理的任务；**Tool\-only** 的子 Agent 绕过 LLM 直接调 MCP 工具，速度快零模型开销；**SequentialAgent** 作为容器编排固定流水线；**手动调用**用于需要精细控制生命周期的场景（比如代码生成\+重试）。
> 
> 设计原则是单一职责 \+ 结构化输出 \+ state 通信。所有 LLM 驱动的子 Agent 都设置 `disallow_transfer_to_parent/peers` 来防循环。最关键的经验是：**不是所有子 Agent 都需要 LLM**——对于元数据查询这类确定性操作，Tool\-only Agent 更快更可靠。
> 
> 

---

## **RAG 项目与检索链路**

<a id="q20"></a>
## **Q20: RAG 项目的整体架构是什么？完整构建流程是什么？**

### **整体架构（三层）**

PA 的 RAG 系统分三层，分别在 Java 和 Python 两个服务中实现：

```Plain Text
┌─────────────────────────────────────────────────────────────────┐
│  第 1 层：Orchestration（agentflow-server / Java）               │
│                                                                  │
│  DatasetSearchServiceImpl  (@NodeType DATASET_SEARCH_NODE)      │
│    ├── 知识库 ID 解析（静态配置 / 动态变量 / 运行时注入）          │
│    ├── 权限过滤（用户 → 知识库 → 数据集 → ES 查询四层）           │
│    ├── 检索参数构建（searchMode、recallLimit、相似度阈值）         │
│    └── 调用 KnowledgeSearchServiceImpl                          │
│                                                                  │
│  完整源码分析见：source-code-analysis-02-03-04-knowledge-        │
│  agent-chat.md 第 2 模块（知识库/搜索）                           │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  第 2 层：RAG 编排（af-rag-server / Python）                     │
│                                                                  │
│  KnowledgeSearchServiceImpl                                     │
│    ├── mixSearch() → 混合召回                                   │
│    │   ├── DenseVectorService.directVectorSearch() → ES KNN     │
│    │   ├── DenseVectorService.directFullTextSearch() → ES BM25  │
│    │   └── RRF 融合 + Rerank                                    │
│    ├── filterTokens() → token 上限截断                           │
│    └── 返回 quoteQA 给上游                                       │
│                                                                  │
│  详细代码见：agent-core-mechanism-qa.md Q20                      │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  第 3 层：存储引擎（ES + Milvus）                                │
│                                                                  │
│  Elasticsearch 8.x（主要）                                       │
│    ├── DenseVectorService.directVectorSearch()                  │
│    │   → ES knn 查询 (numCandidates=200, k=150)                 │
│    │   → 向量字段: "vector_" + dimensionSize                    │
│    ├── DenseVectorService.directFullTextSearch()                │
│    │   → ES Bool Query + BM25 评分                              │
│    └── filter: terms + mustNot (tenantId, teamId, knowledgeId)  │
│                                                                  │
│  Milvus（辅助，旧批次流）                                        │
│    └── VectorSpi/FullTextSpi → 异步批次                          │
└─────────────────────────────────────────────────────────────────┘
```

### **完整构建流程（12 步）**

```Plain Text
阶段 1：知识库创建
  步骤 1：用户在界面创建知识库 → 填写名称、类型（DATESET/FAQ/GRAPH...）
  步骤 2：上传文档（PDF/DOCX/XLSX/图片/网页...）

阶段 2：文档处理流水线
  步骤 3：创建 Context + NodeInstances（WorkFlow 节点实例化）
  步骤 4：DataFlow 轮询（定时检查是否有新文件待处理）
  步骤 5：docParse（文档解析）
          ├── 文本型 PDF → PyMuPDF 提取结构化内容
          ├── 扫描型 PDF → OCR 服务识别
          ├── Office 文档 → python-docx / openpyxl
          └── 网页 → FireCrawl / Jina / beautifulsoup4
  步骤 6：textChunk（文档切片）
          ├── 自动路由（按文件扩展名选择 Chunker）
          ├── 普通文本 → GeneralSplitter / SeparatorRecursiveSplitter
          ├── 表格 → TabularRowSplitter
          └── 扫描件 → OcrChunker（按 OCR Block 边界分割）
  步骤 7：summary + keyword（可选：生成每段摘要 + 提取关键词）
  步骤 8：docStorage（向量化 + 双写）
          ├── 调 embedding 模型 → 生成向量
          ├── 写入 ES（实时查询路径）
          └── 写入 Milvus（旧批次流路径）

阶段 3：在线检索
  步骤 9：用户提问 → 意图路由 + 知识库选择
  步骤 10：queryRewrite（可选重写）
  步骤 11：混合检索（向量 KNN + BM25 并行 → RRF 融合 → Rerank）
  步骤 12：结果组装 quoteQA → 拼入 LLM Prompt

阶段 4：异常处理
          ├── 召回为空 → backupMode 兜底回答 / LLM 自行调整
          ├── 解析失败 → 错误日志 + 重试
          └── 向量模型不一致 → filterKnowledgeIds 消除
```

### **关键设计决策**

### **面试话术**

> RAG 整体是三层架构：**Java 端**做检索编排和权限控制，**Python 端**做文档解析和切片，**ES \+ Milvus** 做存储和检索。构建流程分四个阶段 12 步，从知识库创建到文档解析切片、向量化入库、再到在线检索。
> 
> 一个关键设计是 ES 作为主存储引擎——8\.x 的原生 KNN 插件让我们能在一个集群里同时做向量检索和全文检索，不需要维护两套基础设施。Milvus 只作为旧批次流的兼容路径保留。
> 
> 

---

<a id="q21"></a>
## **Q21: 有没有了解过 Agentic RAG 或主动式 RAG 这类方案？**

### **什么是 Agentic RAG**

传统 RAG 是"检索 → 生成"的线性流程：

```Plain Text
用户问题 → 向量检索 → Top-K 结果 → LLM 生成回答
```

Agentic RAG 将**检索决策权交给 Agent**，Agent 自主决定：

- 什么时候需要检索（还是直接回答）

- 检索什么（查询词是什么、搜哪个知识库）

- 检索几次（结果不满意就重试）

- 怎么用检索结果（直接引用、总结、还是忽略）

### **PA 中的 Agentic RAG 实现**

PA 通过 Google ADK 的 Multi\-Agent 系统实现了 Agentic RAG，具体体现在三个层面：

#### **层面 1：ReAct 循环中的自主检索决策**

ADK 的 Plan\-ReAct 循环让 Agent 自主决定是否调用知识库搜索：

```Plain Text
用户问题："A 股市场最近有什么热点？"

Agent 的 Plan 阶段：
  /*PLANNING*/
  1. 搜索知识库获取近期市场信息
  2. 分析搜索结果
  3. 生成回答

Agent 的 Action 阶段：
  function_call(datasetSearchTool, {question: "A股市场近期热点"})
  ↓
  工具返回 Top-K 结果
  ↓
Agent 的 Reasoning 阶段：
  /*REASONING*/
  搜索结果提到了新能源和人工智能两个方向，
  但新能源信息不够完整，需要再搜一次
  ↓
Agent 的第二次 Action：
  function_call(datasetSearchTool, {question: "A股新能源板块近期表现"})
```

Agent 可以自主决定：搜一次不够就搜第二次，搜错了方向就换关键词。

#### **层面 2：动态知识库选择**

运行时从全局变量中动态指定知识库 ID，不是硬编码：

```Java
// DatasetSearchServiceImpl.getKnowledgeIds()
if (variables.containsKey("knowledgeIds")) {
    // 运行时动态模式：从 WorkFlow 全局变量读取
    knowledgeIds = JSON.parseArray(variables.get("knowledgeIds"));
} else {
    // 静态模式：从节点配置读取
    knowledgeIds = params.getDatasets();
}
```

**两种动态格式**：

```JSON
// 格式 1：字段名匹配
{"knowledgeField": "knowledgeIds"}

// 格式 2：直接指定 ID 列表
{"knowledgeIds": ["kb_001", "kb_002"]}
```

#### **层面 3：多层兜底**

当 Agentic RAG 检索结果不满意时，有三层降级：

### **业界方案对比**

### **与 Ragent 的对比**

详见 `agent-core-mechanism-qa.md` 的 Ragent vs AgentFlow 对比表：

### **面试话术**

> Agentic RAG 的核心思想是**把检索决策权从固定流程交给 Agent**。PA 的实现体现在三个层面：ReAct 循环让 Agent 自主决定搜什么、搜几次；动态知识库 ID 让运行时可以切换检索目标；多层兜底保证检索失败时有降级路径。
> 
> 跟 Self\-RAG、Corrective RAG 这些学术方案比，PA 的实现更"工程化"——没有复杂的反射机制，而是通过 Multi\-Agent 的路由能力和 ReAct 的天然循环来实现自主检索。代价是 Agent 的检索行为有一定不可预测性，不像固定流程那样每次行为一致。
> 
> 

---

<a id="q22"></a>
## **Q22: 意图识别和 Rewrite 机制具体是怎么实现的？平时提示词怎么写的？**

### **意图识别（两层 \+ 可选第三层）**

PA 的意图识别分两层实现：

#### **第 1 层：显式分支（Explicit Branch）**

某些场景不走 LLM 判断，直接通过代码逻辑确定意图：

```Python
# intent_agent/explicit_branch.py
async def explicit_branch(callback_context) -> Optional[LlmResponse]:
    label = callback_context.state.get(TEMP_METADATA_LABEL)
    
    if label == LABEL_PUBLISH_STRATEGY:
        # 固定流程：发布策略 → 不需要 LLM 判断
        return await handle_publish_strategy(callback_context)
    
    if label == LABEL_USE_STRATEGY:
        # 固定流程：使用策略 → 直接跳转到特定 Agent
        return await transfer_to_strategy_agent(callback_context)
    
    return None  # 无匹配 → 走第 2 层
```

**适合场景**：前端按钮触发的固定操作（"发布策略"、"使用策略"），这类操作意图100%确定，不需要 LLM 参与。

#### **第 2 层：Prompt 驱动的 LLM 路由**

通过 System Prompt 让 LLM 自主判断：

```Python
# intent_agent/prompt.py
system_prompt = """
## 角色
你是意图判定智能体，负责将用户问题分发给对应的子 Agent。

## 可用子 Agent
- metadata_agent：元数据查询（表结构、字段定义、枚举值）
- strategy_agent：策略分析和数据洞察
- run_data_agent：数据查询和表格展示
- direct_analysis_agent：直接数据分析
- strategy_view_agent：策略视图查看
- 其他社交互动：直接回复

## 路由规则
1. 对于社交互动（问候、感谢等）：直接简洁回复
2. 对于业务问题：必须使用 transfer_to_agent 调用子代理
3. 如果用户问题涉及多个领域：选择最主要的那个
4. 不确定时：分配 strategy_agent 兜底
"""
```

#### **第 3 层（可选）：Tool\-based 意图判断**

当需要降低 LLM 调用的成本时，可以用轻量模型通过 MCP 工具判断意图：

```Python
# intent_agent/utils.py:judge_intent_by_tool
# 通过 MCP 工具调用轻量模型做意图分类
result = await insightMCPTools.execute(
    tool_name=INTENT_JUDGE_TOOL,
    args={"question": question, "intents": valid_intents}
)
# 返回意图名称或 "other"
```

### **Rewrite 机制**

PA **没有独立的 Rewrite 步骤**，而是隐式嵌入在 Prompt 中：

#### **PA 的方式：REASONING 阶段隐式重写**

```Plain Text
用户问："最近怎么样了？"（指代模糊）

Agent 的 REASONING：
  "用户指的是最近一周的策略表现，需要查 run_data_agent 获取数据"
  → 实际调用时的查询词已由 LLM 隐式重写为"最近一周策略表现数据"
```

重写不是独立步骤，而是 LLM 在推理过程中自然完成的：

```Python
# 在 run_data_agent 的工具调用中，LLM 自动补全了模糊指代
tool_call = {
    "name": "query_data",
    "args": {"table": "strategy_performance", "time_range": "最近一周"}
}
```

#### **对比 Ragent 的独立 Rewrite**

详见 `agent-core-mechanism-qa.md` Q29：

### **平时提示词怎么写**

#### **原则**

1. **先定边界，再定规则**——第一段告诉 LLM"你是谁、能做什么、不能做什么"

2. **否定式约束 \> 肯定式引导**——直接告诉 LLM 不能做什么比要求它做什么更有效

3. **分段的 Prompt \> 长段落**——每个段落只讲一件事

4. **用案例 \> 用抽象描述**——给例子的效果比描述规则好得多

#### **模板结构**

```Plain Text
## 角色与目标
你是一个{角色}，你的目标是{一句话目标}。

## 核心约束（禁止行为）
1. 绝对不要{行为 A}
2. 绝对不要{行为 B}

## 可用工具
{tools}

## 行为规则
### 规则 1：{规则名}
{具体描述，必要时附案例}

### 规则 2：{规则名}
{具体描述}

## 输入输出格式
{格式要求}
```

#### **实际案例**

```Python
# run_data_agent 的部分 Prompt
def get_instruction() -> str:
    return """
你是一个数据查询智能体，负责将用户的自然语言问题转化为结构化的数据查询。

## 可用工具
{tools}

## 行为规则
1. 字段过滤规则：
   如果用户明确指定了需要查询的字段，不允许额外增加字段（如用户要A、B字段，不能加C）
   允许减少字段（如用户要A、B、C字段，但B是冗余的，可以只返回A、C）

2. 数据限制：
   如果未指定日期范围，默认查询最近 30 天
   单次查询最多返回 1000 条记录

3. 结果处理：
   数值保留两位小数
   金额字段以"万元"为单位

## 常见错误避免
- 不要将"查询"误认为"分析"——用户说"查一下数据"是查询，不是分析
- 不要对数据做用户未要求的计算——用户要"销售额"，不要返回"增长率"
"""
```

#### **写 Prompt 的迭代方法**

```Plain Text
V1：用自然语言描述完整需求
  → 测试发现：LLM 在某些边界情况表现不稳定
V2：拆分为"角色 + 规则 + 案例"三段式
  → 测试发现：规则仍不够具体，LLM 会钻空子
V3：加上"常见错误避免"段（基于 V2 的实际错误案例）
  → 测试发现：准确率明显提升
V4：对核心规则增加"为什么"说明
  → LLM 理解规则背后的意图后，泛化能力更强
```

### **面试话术**

> 意图识别分两层：**显式分支**处理前端按钮触发的确定性操作（发布策略等），**LLM 路由**处理用户自然语言。第三层是可选的轻量模型 MCP 调用，用于成本敏感场景。
> 
> Rewrite 方面，我们**没有独立的 Rewrite 步骤**，而是让 LLM 在 REASONING 阶段隐式完成查询词重写。好处是零额外 token 消耗，缺点是重写过程不可观测。对比 Ragent 的独立 Rewrite \+ 独立 LLM 调用，各有优劣。
> 
> Prompt 工程方面，我的经验是：**定边界比定规则重要，负面约束比正面引导有效，案例比抽象描述更有力。** 核心模板是"角色 \+ 核心约束 \+ 行为规则 \+ 常见错误"四段式。
> 
> 

---

<a id="q23"></a>
## **Q23: 知识库的召回策略是什么？检索方式是稠密检索、稀疏检索，还是混合检索？**

### **三种检索模式**

PA 支持三种检索模式，通过 `searchMode` 参数切换：

### **默认策略：混合检索**

`MIXED_RECALL` 是默认模式，执行流程如下：

```Plain Text
用户查询 → "2024年A公司电池参数"
     │
     ├── 并行检索 ──────────────────────────────┐
     │                                           │
     ▼                                           ▼
ES KNN 向量检索                              ES BM25 全文检索
  query: "2024年A公司电池参数"                   query: "2024年A公司电池参数"
  field: vector_1536                            field: content
  numCandidates: 200, k: 150                    BM25 评分
  filter: teamId + knowledgeId                  filter: teamId + knowledgeId
     │                                           │
     ▼                                           ▼
  Top-150 向量结果                            Top-150 全文结果
     │                                           │
     └───────────────┬───────────────────────────┘
                     ▼
              RRF 融合（k=60）
              score(d) = Σ weight / (k + rank_i(d))
              向量权重 0.6，全文权重 0.4
                     │
                     ▼
              ReRank（可选）
              PEG Ranker 交叉编码器
              过滤 similarity 阈值以下结果
                     │
                     ▼
              最终 Top-K 结果 → quoteQA
```

### **RRF 融合详解**

RRF（Reciprocal Rank Fusion）是对**排名**的融合，不是对**分数**的融合：

```Plain Text
向量检索结果排名：docA=1, docB=2, docC=3
全文检索结果排名：docB=1, docD=2, docA=3

RRF 计算（k=60，向量权重 6，全文权重 4）：
docA: 6/(60+1) + 4/(60+3) = 0.0984 + 0.0635 = 0.1619
docB: 6/(60+2) + 4/(60+1) = 0.0968 + 0.0656 = 0.1624  ← 最高
docC: 6/(60+3) + 0          = 0.0952
docD: 0          + 4/(60+2) = 0.0645
```

**为什么用 RRF 而不是分数加权平均？**

- 向量检索的 score（余弦相似度 0\.0\~1\.0）和 BM25 的 score（0\~N）分布不兼容

- 直接加权平均会让 BM25 分数主导结果

- RRF 只关心排名，不受分数分布影响

详见 `source-code-analysis-02-03-04-knowledge-agent-chat.md` 第 5\.3 节和 `agent-core-mechanism-qa.md` Q23。

### **ES 检索细节**

### **Tokenizer**

### **面试话术**

> 默认使用混合检索：向量 KNN \+ BM25 并行检索后通过 RRF 融合排名。RRF 的核心优势是对排名融合而不是分数融合，避免了向量和全文分数分布不兼容的问题。之后可选的 PEG Ranker 交叉编码器对结果做二次排序。
> 
> 三者的关系可以理解为：**向量检索找"意思相近的"**（语义），**全文检索找"字面匹配的"**（关键词），**Rerank 在候选集里做"精排"**。前两步是召回阶段，目标是高召回率；最后一步是排序阶段，目标是高精度。
> 
> 

---

<a id="q24"></a>
## **Q24: 为什么要单独做 Rerank？RAG 知识库选择了什么向量模型？调研过哪些模型？**

### **为什么需要单独做 Rerank**

#### **Embedding（双塔） vs Rerank（交叉编码器）**

```Plain Text
Embedding 模型（双塔）：
  query → encoder → query_vector
  doc1  → encoder → doc1_vector
  doc2  → encoder → doc2_vector
  ...
  计算 query_vector 与每个 doc_vector 的余弦相似度

Rerank 模型（交叉编码器）：
  (query, doc1) → encoder → score1
  (query, doc2) → encoder → score2
  ...
  query 和 doc 拼接后一起送入模型
```

#### **PA 中的 Rerank 定位**

```Plain Text
召回阶段（高召回）→ 排序阶段（高精度）
  向量检索 + BM25       PEG Ranker 交叉编码器
  200 个候选             150 个候选 → 过滤 + 重排
  耗时：~50ms            耗时：~200ms
```

**不在召回阶段直接使用交叉编码器的原因**：如果有 100 万篇文档，交叉编码器需要对 100 万对 \(query, doc\) 做推理，不可行。所以先用双塔 Embedding 快速筛出候选集（200 个），再用交叉编码器精排。

### **向量模型选型**

#### **PA 使用的模型**

#### **Rerank 模型**

### **调研过的模型**

### **面试话术**

> Rerank 的存在是因为 Embedding 和 Rerank 的"分工"不同：**Embedding 是双塔模型，query 和 doc 独立编码，适合快速筛候选集；Rerank 是交叉编码器，query 和 doc 拼接后一起过 Transformer，能捕捉更细粒度的匹配关系，适合精排**。
> 
> 可以理解为：Embedding 是海选阶段的"简历筛选"，Rerank 是终面阶段的"专家评审"。两者配合才能兼顾效率和精度。
> 
> 模型选型方面，选择了 text\-embedding\-3\-large 为主力（综合能力强、维度可控），PEG Ranker 为 Rerank 模型。调研过 GTE、E5、BGE 等开源模型，最终基于"成本 \+ 精度 \+ 运维复杂度"的综合考量选择了闭源 API 方案。
> 
> 

---

<a id="q25"></a>
## **Q25: 文档切片采用的是什么策略？召回不到数据怎么处理？**

### **文档切片策略**

PA 有多种 Chunker，根据文件类型自动路由：

```Python
# rag_flow.py:chunk_splitting()
# 按文件扩展名自动选择 Chunker
if ext in [".pdf", ".docx", ".txt"]:
    chunker = GeneralSplitter(chunk_size=1024, overlap=200)
elif ext in [".xlsx", ".xls"]:
    chunker = TabularRowSplitter()  # 按行切分，保留表头
elif ext == ".png" or ".jpg":
    chunker = OcrChunker(block_level="paragraph")  # 按 OCR Block 边界
elif ext == ".mp4":
    chunker = VideoASRSplitter()
...
```

#### **10 种分片策略**

详见 `rag-doc-processing-ocr-qa_0728.md` Q7 的完整表格，核心策略包括：

**父子文档切片的 PA 实现**（详细见 `agent-core-mechanism-qa.md:1410-1476`）：

PA **没有**传统的父子文档双存储方案（父 chunk 存语义，子 chunk 存细节），而是用"一套 chunk \+ 元数据补齐"的等价方案：

```Plain Text
Ragent 方式：两套 chunk
  父 chunk：完整段落（用于检索）
  子 chunk：细粒度片段（用于生成）
  存储两份，冗余大

PA 方式：一套 chunk + 元数据补齐
  chunk 本身是细粒度片段
  元数据中保存"所属段落ID"（para_id）
  检索到子 chunk → 通过 para_id 从数据库回表补齐上下文
  存储一份，查询时补齐
```

### **召回不到数据的处理（三级兜底）**

#### **详细流程**

```Plain Text
用户问题 → 检索知识库
    │
    ├── 有结果（≥1条）→ 正常返回 quoteQA
    │
    └── 无结果
         │
         ├── backupMode == 2？
         │    ├── 是 → 返回 customAnswer（预置兜底回答）
         │    └── 否 → 返回 isEmpty=true
         │
         └── Agent 模式？
              ├── 是 → Agent 自行改写问题重新检索
              │         （ReAct 循环中的 REASONING 阶段）
              └── 否 → 返回空结果给用户
```

#### **补充措施**

### **面试话术**

> 切片策略按文件类型自动路由——普通文本用 GeneralSplitter（1024/200），表格用 TabularRowSplitter，扫描件用 OcrChunker。父子文档方面没有做传统的双存储方案，而是用"一套 chunk \+ 元数据补齐"的方式，减少存储冗余。
> 
> 召回不到数据有三层兜底：最下层是预置的 customAnswer，中间层是 isEmpty 标记让下游做分支，最上层是 Agent 的 ReAct 循环自主重试。实践中，**调 similarity 阈值和 chunk\_size 是最有效的优化手段**——很多"召回不到"的问题其实是阈值太严或 chunk 太短。
> 
> 

---

<a id="q26"></a>
## **Q26: RAG 文档隔离怎么做？关联关系文档和行业术语文档 RAG 怎么处理？**

### **文档隔离（四层权限）**

```Plain Text
第 1 层：知识库级别
  └── 用户只能看到自己有权限的知识库
  └── 通过用户角色 + 知识库成员列表控制

第 2 层：数据集启禁用
  └── 知识库内的数据集可以单独启用/禁用
  └── filterDatasetIdsByEnabled() 过滤

第 3 层：ES 查询级过滤
  └── 所有检索请求携带 tenantId + teamId + knowledgeId
  └── ES 查询的 filter 条件:
      {
        "bool": {
          "filter": [
            {"term": {"tenantId": "xxx"}},
            {"term": {"teamId": "yyy"}},
            {"terms": {"knowledgeId": ["kb1", "kb2"]}}
          ]
        }
      }

第 4 层：多知识库一致性校验
  └── 一次查询多个知识库时，必须满足：
      1. 类型相同（都是 DATESET / 都是 FAQ）
      2. 向量模型相同（否则向量维度不一致）
  └── filterKnowledgeIds() 负责校验
```

### **跨知识库检索的类型一致性**

```Java
// DatasetSearchServiceImpl.filterKnowledgeIds()
// 多个知识库同时检索的限制条件
Knowledge first = knowledgeList.stream().findFirst();
List<String> filteredIds = knowledgeList.stream()
    .filter(k -> sameType(first, k) && sameVectorModel(first, k))
    .map(Knowledge::getId)
    .collect(Collectors.toList());

// 如果不一致，直接从检索列表中移除
// 不会报错，只是"能检几个检几个"
if (filteredIds.size() != knowledgeList.size()) {
    log.warn("部分知识库因类型或模型不一致被过滤");
}
```

### **关联关系文档处理**

关联文档（如"合同 A"引用"附件 B"）的处理策略：

### **行业术语文档处理**

行业术语有**专门的术语库**，处理流程：

```Plain Text
文档入库前：
  原始文本 → queryTermAndReplace → 替换后的文本 → 切片 → 向量化
                                                    ↑
  术语库：{"电芯": "电池电芯", "SOC": "State of Charge", ...}

在线检索时：
  用户问题 → queryTermAndReplace → 替换后的问题 → 检索
```

```Java
// Java 侧的 queryTermAndReplace 服务
// /api/v1/term/replace
// 将文本中的术语缩写替换为全称，提升检索命中率
input: "SOC 低于 20% 时电芯会损坏"
output: "State of Charge 低于 20% 时电池电芯会损坏"
```

#### **术语库的维护**

- **系统预置**：行业通用术语（电池、金融、医疗等）

- **用户自定义**：每个租户可以维护自己的术语词典

- **自动扩展**：通过同义词挖掘算法发现新术语

### **面试话术**

> 文档隔离走四层：知识库权限、数据集启禁用、ES 查询 filter、多库一致性校验。最关键是 ES 查询层——所有检索都带 tenantId \+ teamId \+ knowledgeId 作为 filter 条件，从存储层保证数据不会跨租户泄露。
> 
> 关联文档用元数据 \+ 同库聚合的方式处理。行业术语有独立的术语库，在入库前和检索前分别做 `queryTermAndReplace` 替换，将缩写/简称转为全称后再做向量化/检索，显著提升命中率。
> 
> 

---

<a id="q27"></a>
## **Q27: Code\-RAG 怎么设计？**

### **Code\-RAG 的定位**

PA 的 Code\-RAG 目前以**代码执行沙箱**为主，代码检索为辅：

```Plain Text
当前状态：
  代码执行沙箱（核心） > 代码检索（辅助）
  
未来目标：
  代码执行 + 代码索引 + NL-to-Code（渐进增强）
```

### **当前实现：代码执行沙箱**

```Python
# POST /rag_algorithm/code_runner
# 接收用户生成的 Python 代码，在沙箱中执行
{
    "code": "import pandas as pd\ndf = pd.read_csv('data.csv')\ndf.describe()",
    "data_files": ["data.csv"],
    "timeout": 30
}
→ {
    "result": "count  100\nmean   0.5\n...",
    "success": true
}
```

**使用场景**：Agent 在 ReAct 循环中生成 Python 代码，通过 `function_call(run_python_code, ...)` 在沙箱中执行，结果返回给 LLM 继续分析。

### **代码生成的"自愈"循环**

```Python
# strategy_create_agent 中 code_agent 的调用流程
for _ in range(MULTI_AGENT_GEN_CODE_RETRY_COUNT):  # 默认 2 次
    # 1. LLM 生成代码
    async for event in code_agent._run_async_impl(ctx):
        ...
    
    # 2. 在沙箱中执行代码（用测试数据 DataFrame）
    result, error = exec_code(code, test_data)
    
    # 3. 如果成功 → 返回
    if success:
        return result
    
    # 4. 如果失败 → 错误信息注入 state，下一轮重试
    ctx.session.state[TEMP_CURRENT_PYCODE] = code
    ctx.session.state[TEMP_CURRENT_PYCODE_EXCEPTION] = error_message
    # 下一轮 LLM 会看到：
    # "上次生成的代码：{code}，错误信息：{error}"
```

### **Code\-RAG 与 Code\-Generation 的对比**

### **Code\-RAG 的扩展方向**

PA 规划中但尚未完全实现的 Code\-RAG 能力：

```Plain Text
阶段 1（已完成）：代码执行沙箱
  └── Agent 生成 Python 代码 → 沙箱中执行 → 结果返回

阶段 2（进行中）：代码索引
  └── 对存量代码库切片 → 向量化 → 存入 ES
  └── 检索时：NL question → 向量检索 → Top-K 代码片段 → 上下文

阶段 3（规划中）：NL-to-Code 闭环
  └── 用户描述需求 → 检索相关代码 → LLM 理解 → 生成新代码 → 执行验证
  └── 如果执行失败 → 自动修正 → 重新执行
```

### **面试话术**

> Code\-RAG 目前以代码执行沙箱为核心——Agent 生成 Python 代码后在沙箱中执行，失败则自愈重试。代码检索部分还比较薄弱，规划中的方向是对存量代码库切片索引，让 Agent 在生成代码前先检索相似的已有代码作为参考。
> 
> 关键经验是**代码生成的重试循环**——不是简单重试，而是把上一次的代码和错误信息注入到下一轮的上下文中，让 LLM 理解"哪里错了"再修正。这种模式比"重新生成一遍"的成功率高很多。
> 
> 

---

## **Memory 记忆系统**

<a id="q28"></a>
## **Q28: Memory 怎么做的？怎么插入上下文？怎么考虑上下文长度问题？**

### **Memory 体系总览**

PA 的记忆系统分三层：

```Plain Text
┌────────────────────────────────────────────────────────────┐
│  第 1 层：短期记忆（Session 级别）                           │
│                                                            │
│  存储：ADK Session Event（Redis）                           │
│  内容：当前会话的完整对话轮次                                 │
│  控制：historyRound（默认 3 轮）+ 30K token 硬上限           │
│                                                            │
│  详细机制见：agent-skill-mcp-react-qa.md Q14-16             │
└────────────────────────────────────────────────────────────┘
                            │
┌────────────────────────────────────────────────────────────┐
│  第 2 层：长期记忆（Mem0 向量记忆）                          │
│                                                            │
│  存储：Milvus 向量库（通过 Mem0 SDK 管理）                   │
│  内容：跨会话的关键信息（用户偏好、业务上下文、关键结论）       │
│  控制：向量检索 + similarity 阈值                            │
│                                                            │
│  架构图见：module-deep-dive-02-chat-knowledge-              │
│  memory-prompt.md 第 3 节                                   │
└────────────────────────────────────────────────────────────┘
                            │
┌────────────────────────────────────────────────────────────┐
│  第 3 层：对话摘要（Java AISummaryService）                  │
│                                                            │
│  存储：ChatItem.moduleSummaryData（MySQL）                  │
│  内容：长对话的 LLM 语义摘要                                  │
│  控制：token 超限时触发 LLM 摘要生成                          │
│                                                            │
│  详细代码见：source-code-analysis-02-03-04-                 │
│  knowledge-agent-chat.md 第 4 模块                          │
└────────────────────────────────────────────────────────────┘
```

### **记忆注入上下文的方式**

三层的注入时机和方式不同：

```Plain Text
System Prompt 组装时注入（长期记忆 + 背景知识）：
  instruction = promptInfo + 【背景知识】 + 【长期记忆】 + 引用规则

Session 初始化时注入（短期记忆）：
  chatHistory → ADK Event → session.append_event()
  → ADK 框架自动包含在 llm_request.contents 中

Token 超限时触发注入（对话摘要）：
  ChatContextFilter 检测 token 超限
  → AISummaryService 调用 LLM 生成摘要
  → 摘要替换 system prompt 中的历史部分
```

#### **长期记忆的注入代码**

```Python
# ADKExecutor.get_prompt()
def get_prompt(self):
    text = self.agent_meta.promptInfo
    
    # 1. 替换变量
    text = self._replace_variable(text)
    
    # 2. 知识库检索结果
    if self.rag_options.quoteQA:
        text += f"\n\n【背景知识】\n{rag}"
    
    # 3. 长期记忆（Mem0 检索结果）
    if self.agent_meta.longTermMemory:
        text += f"\n\n【长期记忆】以下是和问题相关的记忆\n{self.agent_meta.longTermMemory}"
    
    return text
```

### **上下文长度控制（三层防御）**

### **面试话术**

> Memory 体系分三层：**短期记忆**是 Session 内的对话轮次（ADK Event \+ Redis），**长期记忆**是 Mem0 管理的向量化记忆（Milvus），**对话摘要**是超长对话时 LLM 生成的语义压缩。
> 
> 注入方式也不同：长期记忆拍平到 System Prompt 的 `【长期记忆】` 段，短期记忆通过 ADK Session Event 自动成为 messages 的一部分，摘要在 token 超限时触发并替换历史。
> 
> 长度控制有三层防御：轮次截断（最常用）、Token 硬上限（兜底）、LLM 摘要（最优雅但成本最高）。实践中 80% 的场景轮次截断就够了。
> 
> 

---

<a id="q29"></a>
## **Q29: Mem0 记忆系统的实现细节是什么？**

### **Mem0 的集成架构**

```Plain Text
┌────────────────────────────────────────────┐
│  Java 端（配置层）                          │
│                                            │
│  TeamMemoryFieldConfig                     │
│    → 按团队自定义记忆字段                    │
│                                            │
│  AgentMemoryFieldRelation                  │
│    → Agent 关联哪些记忆字段                  │
│                                            │
│  AutoAgent.longTermMemory                  │
│    → JSON 配置: {enabled, fieldIds, ...}   │
└────────────────────┬───────────────────────┘
                     │  HTTP
                     ▼
┌────────────────────────────────────────────┐
│  Python 端（af-rag-server）                  │
│                                            │
│  Flask API: /vector/memory/*               │
│    ├── /insert → mem0.add()                │
│    ├── /query  → mem0.search()             │
│    ├── /update → mem0.update()             │
│    └── /delete → mem0.delete()             │
│                                            │
│  memory_scheduler (APScheduler)             │
│    └── 定时清理 + 向量化                    │
└────────────────────┬───────────────────────┘
                     │  Mem0 SDK
                     ▼
┌────────────────────────────────────────────┐
│  存储层                                     │
│                                            │
│  Milvus（向量存储）                          │
│  Redis（缓存）                              │
│  SQLite（Mem0 元数据）                      │
└────────────────────────────────────────────┘
```

### **Mem0 的核心能力**

### **记忆的写入流程**

```Plain Text
用户完成一次对话 → 触发记忆写入
    │
    ├── 1. Java 端调用 /vector/memory/insert
    │     ├── messages: [user_msg, assistant_msg]
    │     ├── user_id: user_123
    │     └── agent_id: agent_456
    │
    ├── 2. Python Flask 接收 → mem0.add()
    │     ├── 提取 messages 中的关键信息
    │     ├── 选择相关记忆字段（来自 AgentMemoryFieldRelation）
    │     └── 向量化 → 存入 Milvus
    │
    └── 3. Milvus 存储
          ├── id: mem_001
          ├── text: "用户偏好使用柱状图展示数据"
          ├── vector: [0.123, 0.456, ...] (768维)
          ├── metadata: {user_id, agent_id, timestamp, field_name}
          └── score: 0.0 (占位，检索时计算)
```

### **记忆的读取流程**

```Plain Text
用户提问 → Agent 开始处理
    │
    ├── 1. Java 端调用 /vector/memory/query
    │     ├── query: "用户对数据展示有什么偏好"
    │     ├── user_id: user_123
    │     └── agent_id: agent_456
    │
    ├── 2. Python Flask → mem0.search()
    │     ├── 向量化 query
    │     ├── Milvus ANN 检索 Top-K
    │     └── 返回匹配的记忆列表
    │
    ├── 3. Java 端接收 longTermMemory
    │     └── 暂存到 AutoAgent.longTermMemory
    │
    └── 4. ADKExecutor.getPrompt() 注入
          └── instruction += "【长期记忆】... 检索到的记忆..."
```

### **Mem0 与 PA 集成的关键点**

```Python
# Python Flask API（简化）
@app.route("/vector/memory/insert", methods=["POST"])
def insert_memory():
    data = request.json
    # mem0 会自己决定"记住什么"
    result = mem0.add(
        messages=data["messages"],      # 对话内容
        user_id=data["user_id"],         # 用户 ID
        agent_id=data["agent_id"],       # Agent ID
        metadata={"field": data["field"]}  # 记忆字段信息
    )
    return {"success": True, "memory_id": result["memory_id"]}


@app.route("/vector/memory/query", methods=["POST"])
def query_memory():
    data = request.json
    # 语义检索相关记忆
    memories = mem0.search(
        query=data["query"],
        user_id=data["user_id"],
        agent_id=data["agent_id"],
        limit=5  # 返回 Top-5 记忆
    )
    return {"memories": memories}
```

### **记忆字段可配置**

Java 端允许按团队自定义记忆字段，实现"只记住需要的信息"：

```JSON
// TeamMemoryFieldConfig 示例
{
  "teamId": "team_001",
  "fields": [
    {"fieldName": "data_preference", "fieldType": "string",
     "description": "用户对数据展示方式的偏好"},
    {"fieldName": "focus_metrics", "fieldType": "string[]",
     "description": "用户关注的指标列表"},
    {"fieldName": "last_analysis_date", "fieldType": "date",
     "description": "上次分析的数据日期"}
  ]
}
```

Agent 通过代理关系选择需要使用的字段。

### **面试话术**

> Mem0 的集成是 Java \+ Python 两端的配合。Java 端负责记忆字段的配置（按团队自定义），Python 端通过 Mem0 SDK 管理 Milvus 中的向量记忆。写入和读取都通过 Flask API 中转。
> 
> 关键设计是**记忆字段可配置**——不是所有信息都记住，而是按团队和 Agent 的需求选择性地记忆。这样既控制了向量存储的增长，也提高了检索精度。Mem0 SDK 本身负责"提取什么信息"和"怎么向量化"，PA 负责"什么时候提取"和"怎么注入上下文"。
> 
> 

---

<a id="q30"></a>
## **Q30: 长期记忆和短期记忆怎么区分与保存？结构化信息和文本信息之间如何区分？会不会重复？**

### **长期记忆 vs 短期记忆**

#### **一句话区分**

> 短期记忆是"刚才说了什么"，长期记忆是"这个用户通常怎么做事"。
> 
> 

### **结构化信息 vs 文本信息**

PA 中两者通过不同的机制处理：

#### **结构化信息**

#### **文本信息**

#### **两者的关系**

```Plain Text
结构化信息 = 记忆的"骨架"（字段定义、配置参数、元数据标签）
文本信息   = 记忆的"血肉"（具体内容、对话记录、用户表述）

PA 的做法：结构化字段定义 + 文本内容向量化
  1. 在 Java 端定义"需要记住什么字段"（结构化）
  2. 对话完成后，提取这些字段对应的文本内容（非结构化）
  3. 将文本内容向量化存入 Milvus（向量化）
  4. 检索时，向量检索找到相关记忆，再映射回结构化字段
```

### **重复问题的处理**

记忆重复主要有三种场景：

#### **场景 1：相同内容多次写入**

```Plain Text
Round 1: "用户说我更喜欢柱状图" → 写入记忆: "偏好柱状图"
Round 2: "用户又说了一次柱状图" → 可能再次写入: "偏好柱状图"
```

**PA 的处理**：Mem0 本身有去重机制（基于内容 hash），但不太可靠。PA 没有做额外的去重，原因是——如果同一条记忆被检索到多次，在 System Prompt 中重复出现，LLM 通常会忽略重复内容，影响不大。

#### **场景 2：相似但不同的记忆**

```Plain Text
记忆 A: "用户偏好柱状图展示数据"
记忆 B: "用户偏好折线图展示趋势数据"
```

**处理**：不合并。两条都保留，LLM 根据上下文选择使用哪条。

#### **场景 3：记忆冲突**

```Plain Text
记忆 A: "用户是市场部"
记忆 B: "用户是技术部"
```

详见 Q31 的专门讨论。

### **面试话术**

> 短期记忆和长期记忆本质上是\*\*"记住了"vs"记住了且觉得重要"\*\*的区别。短期记忆是 Session 中的所有对话轮次，按轮次截断；长期记忆是经过向量化、可跨会话检索的关键信息。
> 
> 结构化信息（字段配置、Agent 参数）存在 MySQL 中，文本信息（对话内容、用户表述）向量化后存 Milvus。PA 的做法是"结构化字段定义 \+ 文本内容向量化"——先在 Java 端定义好要记住什么，再把实际内容向量化检索。
> 
> 重复问题没有做专门的去重，主要靠 Mem0 自带的去重和 LLM 对重复内容的天然容忍度。如果真的有必要，可以在写入前做一次语义相似度检查，如果找到相似度 \> 0\.95 的已有记忆则跳过。
> 
> 

---

<a id="q31"></a>
## **Q31: 记忆冲突怎么解决？**

### **什么是记忆冲突**

记忆冲突指同一用户的同一条记忆在不同时间被写入相互矛盾的内容：

```Plain Text
时间线：
  t1: "用户是市场部"        → 写入记忆 A
  t2: "用户是技术部"        → 写入记忆 B
  t3: 检索 "用户是哪个部门"  → 同时返回 A 和 B，LLM 困惑
```

### **PA 中记忆冲突的四种情况**

### **PA 的解决方案**

#### **方案 1：写时覆盖（简单但有效）**

在同一 field 下，新的记忆覆盖旧的：

```Python
# 写入记忆时检查是否已有同类记忆
existing = mem0.search(
    query=query,
    user_id=user_id,
    agent_id=agent_id,
    metadata={"field": field_name}  # 按字段筛选
)

if existing and similarity(existing, new_memory) > 0.9:
    # 高度相似 → 更新已有记忆（cover）
    mem0.update(existing[0]["id"], new_memory)
else:
    # 不相似 → 新增记忆（保留两条）
    mem0.add(messages, user_id, agent_id)
```

**问题**：覆盖的判断标准难定——多高的相似度才算"同一件事"？

#### **方案 2：时间戳 \+ LLM 综合判断**

检索时带时间戳，让 LLM 自行判断：

```Python
# System Prompt 中的长期记忆带上时间
【长期记忆】
- 2024-01: 用户是市场部（来源：对话 "我在市场部工作"）
- 2024-06: 用户是技术部（来源：对话 "我刚转到技术部"）
```

LLM 看到时间线后会理解"用户转部门了"，而不是"记忆冲突"。

#### **方案 3：多个候选 \+ LLM 裁决**

检索到多个可能冲突的记忆时，全部提供给 LLM，让 LLM 根据当前上下文裁决：

```Python
# 检索到 3 条关于"用户部门"的记忆
memory_candidates = [
    "用户是市场部（置信度: 0.92）",
    "用户是技术部（置信度: 0.85）",
    "用户曾在市场部工作（置信度: 0.70）",
]

# 全部注入到 System Prompt
instruction += "【长期记忆】\n" + "\n".join(memory_candidates)
# LLM 自行判断用哪条
```

#### **PA 实际使用的方式**

PA 目前**没有专门的冲突解决机制**，主要靠：

1. **写时覆盖**——同 field 同内容高度相似的，覆盖更新

2. **LLM 自主裁决**——冲突的记忆都喂给 LLM，让 LLM 看上下文决定

3. **人工修正**——用户可以在前端编辑/删除记忆

### **业界方案对比**

### **面试话术**

> 记忆冲突目前没有完美的自动化方案。PA 的做法是"防大于治"——通过写时覆盖减少冲突，通过 LLM 的上下文理解能力裁决冲突。对于关键信息的变更，带时间戳的记忆让 LLM 能理解"过去是这样，现在变成了那样"，而不是"两条矛盾的信息"。
> 
> 坦白说，这是一个开放性问题。工业界也没有完美的方案——Mem0 的置信度排序、知识图谱推理、人工审核，各有局限。我的看法是：**对于 LLM 应用，与其花大力气消除冲突，不如让冲突的信息都呈现给 LLM，让它根据当前上下文做判断**——这正是 LLM 擅长的事。
> 
> 

---

<a id="q32"></a>
## **Q32: 用 RAG 检索 Memory？为什么用向量方式？Memory 的检索是分层次的吗？具体怎么设计？**

### **为什么用向量方式检索 Memory**

**选择向量的核心原因**：长期记忆的价值在于"跨会话的语义关联"。用户不会在每个会话中都精确复述"我上次说了偏好柱状图"，而是说"跟上次一样"。向量检索能理解"数据展示方式"和"柱状图"之间的语义关系。

### **Memory 检索的分层设计**

PA 的记忆检索是**两层结构**，不是全量向量检索：

```Plain Text
用户提问 → "A 股最近走势怎么样"
    │
    ├── 第 1 层：身份过滤（粗筛）
    │   ├── user_id = user_123
    │   ├── agent_id = agent_456
    │   └── field = data_preference  （如果有指定）
    │
    ├── 第 2 层：向量语义检索（精查）
    │   ├── query = "A 股走势"
    │   ├── embedding 模型 → query_vector
    │   ├── Milvus ANN 检索 → Top-5
    │   └── 过滤：similarity > 0.7
    │
    └── 结果：匹配的记忆列表
        ├── "用户上次分析的股票池：A 股消费板块"
        └── "用户偏好用 K 线图查看走势"
```

#### **为什么不直接全量向量检索**

1. **权限隔离**——必须先按 user\_id \+ agent\_id 过滤，不能搜到其他用户的记忆

2. **字段筛选**——按 Agent 配置的记忆字段过滤，只检索"这个 Agent 需要记住的"信息

3. **性能优化**——先过滤子集再做 ANN，比在全集上做 ANN 更快更准

### **记忆检索的完整数据流**

```Plain Text
用户提问 → IntentAgent 分发 → 子 Agent 开始处理
    │
    │  Step 1: Java 端准备检索参数
    │    └── user_id, agent_id, field_ids (来自 AgentMemoryFieldRelation)
    │
    │  Step 2: 调用 /vector/memory/query（Python Flask API）
    │    └── mem0.search(query=question, user_id=..., agent_id=...)
    │
    │  Step 3: Milvus 检索
    │    ├── Filter: user_id == "u123" AND agent_id == "a456"
    │    ├── ANN: query_vector, top_k=5, metric_type=COSINE
    │    └── Post-filter: similarity > 0.7
    │
    │  Step 4: 结果返回 Java 端
    │    └── longTermMemory = {memories: [...]}
    │
    │  Step 5: 注入 System Prompt
    │    └── instruction += "【长期记忆】\n{formatted_memories}"
    │
    ▼
Agent 带着长期记忆调用 LLM
```

### **记忆检索 vs 知识库检索**

### **关于"层次化"检索**

PA 的 Memory 检索**没有多级层级结构**（不像知识库那样有多级分类树）。记忆是按"flat 向量库 \+ metadata 过滤"的方式组织的：

```Plain Text
层级化的例子（PA 没有）：
  第 1 层：按主题分类（数据偏好、业务偏好、个人偏好）
  第 2 层：按时间分段（2023、2024、2025）
  第 3 层：向量检索

PA 的实际方式：
  所有记忆在同一个向量空间中
  通过 metadata 过滤（user_id, agent_id, field_name）缩小范围
  一次向量检索搞定
```

**不采用层次化的原因**：

1. 记忆量不大——单个用户的长期记忆通常几十条，不需要多级索引

2. 维度少——过滤条件（用户、Agent、字段）固定，不需要动态分类

3. 维护成本——层次化结构需要维护分类树，而用户的记忆类别是动态变化的

### **面试话术**

> 用向量检索做 Memory 是因为长期记忆的价值在于跨会话的语义关联——用户不会每次都精确复述，向量检索能理解"数据展示方式"和"柱状图"之间的语义关系。不用关键词或时间顺序是因为长期记忆中"相关的"不等于"刚刚的"或"字面匹配的"。
> 
> 检索分两层：先按 user\_id \+ agent\_id 做身份过滤（权限隔离），再做向量 ANN 检索。检索配置（哪些 field）来自 Java 端的 AgentMemoryFieldRelation 配置。
> 
> 和知识库检索的区别在于：知识库是"查文档"，目标是高召回（Top\-150 \+ Rerank）；记忆是"查个人上下文"，目标是精准（Top\-5 直接给 LLM）。注入位置也不同——知识库在 quoteQA，记忆在 System Prompt。
> 
> 关于层次化：PA 的记忆是 flat 的，不做多级分类。因为单用户的记忆量不大，metadata 过滤 \+ 一次向量检索就够了。这不是技术做不到，而是复杂度收益不匹配。
> 
> 

---

<a id="q33"></a>
## **Q33: 怎样的结构才能更好地追踪整个 Trace？日志怎么结构化？为什么要这么结构化？**

### **Trace 追踪的整体架构**

PA 的 Trace 体系覆盖**请求入口 → 内部调用 → 外部存储 → 前端展示**的全链路：

```Plain Text
用户请求 → Controller (生成 requestId + chatId)
    │
    ├── MDC 注入: PtxId (traceId) + PspanId (spanId)
    │
    ├── SSE 连接建立 → AutoAgentChatSSEListener
    │       ├── 每个 event 记录时间间隔
    │       ├── ragMapLists (reqId → List<AutoAgentRagVO>)
    │       └── totalTime 累加
    │
    ├── OkHttp 调用 Python RAG 服务
    │       └── SentinelOkHttpInterceptor 注入 X-SENTINEL header
    │
    ├── DB 持久化
    │       ├── AutoAgentChatItem.traceId        ← 整个对话的追踪 ID
    │       ├── AutoAgentChatItemDetail.traceId   ← 每个步骤的追踪 ID
    │       ├── AutoAgentChatItem.runningTime     ← 总耗时
    │       └── AutoAgentChatItemDetail.executeTime ← 单步耗时
    │
    └── 前端 SSE 事件流
            ├── type=content/function_call/function_response/error
            ├── 每个 event 携带 timestamp
            └── 前端按 requestId 聚合展示
```

#### **traceId 的生成与传递**

```Java
// AutoAgentChatController.java — 请求入口生成
dto.setRequestId(UuidUtil.getUUID());   // → traceId
dto.setChatId(IdUtil.fastUUID());       // → chatId

// AutoAgentChatSSEListener.java — 持久化到 DB
String traceId = dto.getRequestId();
AutoAgentChatItem itemUser = AutoAgentChatItem.builder()
    .chatId(chatId)
    .traceId(traceId)                    // ← 写入 trace_id 字段
    .runningTime(totalTime)             // ← 写入总耗时
    .build();

// AutoAgentChatItemDetail — 每个 detail 也带 traceId
AutoAgentChatItemDetail detail = new AutoAgentChatItemDetail();
detail.setItemId(chatItemId);
detail.setTraceId(traceId);             // ← 每个步骤关联同一 traceId
detail.setExecuteTime(ragVO.get(i).getTime());  // ← 单步耗时
```

#### **为什么要用 requestId 作为 traceId？**

PA 的架构中，**一次对话请求 = 一个 requestId**，原因如下：

1. **端到端关联**——从 Controller 入口到 SSE 回调到 DB 存储，都用同一个 requestId 串起来

2. **异步追踪**——`asyncAutoAgentChat()` 是异步执行的，不能依赖线程局部变量传递，requestId 作为 DTO 字段显式传递

3. **多步骤聚合**——一次 Agent 调用可能包含多次 function\_call/function\_response，通过 `ragMapLists.put(reqId, ragVOLists)` 聚合

4. **前端配对**——SSE 的 `pubMsg(reqId, type, data)` 和 `complete(reqId)` 是成对调用的，前端通过 reqId 识别对话流

### **日志结构化**

#### **Logback 配置中的结构化模式**

```XML
<!-- logback-spring.xml — pattern 定义 -->
<property name="pattern"
    value="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %-40.40logger{39}
    -[TxId : %X{PtxId} , SpanId : %X{PspanId}]- %msg%n"/>
```

日志输出格式：

```Plain Text
2025-06-15 10:30:45.123 INFO [http-nio-8080-exec-3] c.m.p.a.w.a.l.AutoAgentChatSSEListener
-[TxId : a1b2c3d4-e5f6-..., SpanId : span_001]- connect to rag success:...
```

结构化的几个维度：

#### **日志文件拆分策略**

```Plain Text
${appName}-info-30dt.log      ← 全量日志 (INFO 及以上)
${appName}-error-30dt.log     ← 仅 ERROR (ThresholdFilter)
${appName}-perf-30dt.log      ← 性能日志 (独立 Logger: LOGGER_PERFORMANCE)

滚动策略:
  ├── TimeBasedRollingPolicy: 按天滚动
  ├── maxFileSize: 1024MB (单文件上限)
  └── 30 天保留
```

#### **异步日志架构**

```XML
<appender name="${appName}-info-ASYNC" class="ch.qos.logback.classic.AsyncAppender">
    <discardingThreshold>0</discardingThreshold>   <!-- 不丢弃日志 -->
    <queueSize>100000</queueSize>                   <!-- 10 万队列深度 -->
    <appender-ref ref="${appName}-info"/>
</appender>
```

**AsyncAppender 的设计考量**：

- `discardingThreshold=0`：队列满时不丢弃日志（防止关键时刻日志丢失）

- `queueSize=100000`：10 万队列深度，应对高并发写入

- 性能收益：业务线程只做入队操作，IO 写盘由 Appender 后台线程处理

### **为什么要这样结构化**

1. **故障排查效率**——TxId 串联跨服务调用，不用 grep 关键词，直接按 traceId 查全链路

2. **性能分析**——`executeTime` \+ `runningTime` 字段配合 perf 日志，精确定位慢调用

3. **容量规划**——30 天滚动 \+ 1024MB 分片，既保留足够的历史窗口，又控制单文件大小

4. **异步不丢日志**——生产环境 IO 压力大，异步写盘 \+ discardingThreshold=0 确保不丢日志

5. **前端可观测**——SSE 每个 event 带 time 间隔，前端能展示"思考中\.\.\."的实时状态

### **面试话术**

> Trace 体系以 requestId 为核心，从 Controller 入口生成后通过 DTO 显式传递到 SSE Listener 和 DB 层，不走线程上下文（因为异步调用）。日志采用 Logback AsyncAppender（discardingThreshold=0、队列 10 万），按 info/error/perf 三类文件拆分，MDC 注入 PtxId 和 PspanId 实现全链路关联。
> 
> 这样做的核心原因：Agent 场景下一次对话就是一个事务，涉及多次 LLM 调用和工具执行，没有 traceId 就无法关联散落在不同文件、不同线程中的日志。
> 
> 

---

<a id="q34"></a>
## **Q34: 跨 Session 是否需要记录日志系统？**

### **跨 Session 的日志需求**

在 PA 的场景中，"Session" 指的是一次对话窗口（一个 chatId）。跨 Session 指的是**多轮对话之间的关联**，例如：

```Plain Text
Session 1 (chatId=chat_001): 用户问 "A 股最近走势"
Session 2 (chatId=chat_002): 用户问 "帮我分析一下消费板块"
Session 3 (chatId=chat_003): 用户问 "跟上次说的比怎么样"
```

### **跨 Session 追踪的对象**

PA 的做法是**分两个层面处理跨 Session 日志**：

#### **日志系统（Logback）层面 — 不需要跨 Session**

日志系统关注的是**单次请求的完整生命周期**，每个 Session 的请求都有自己的 traceId：

```Plain Text
Session 1 的日志: TxId=trace_001 → 存入 chat_001 对应的日志文件
Session 2 的日志: TxId=trace_002 → 存入 chat_002 对应的日志文件
```

日志系统不需要跨 Session 的原因是：

- **日志的主要用途是排障**——排查问题时，你关心的是"这次请求哪里出了问题"，而不是"用户上周说了什么"

- **每次请求独立**——每次对话请求是一个独立的 HTTP 请求，有独立的 requestId 和线程

- **日志集中管理**——所有 Session 的日志都写入同一个文件（按时间排序），需要时通过 traceId 或时间范围过滤

#### **业务数据层面 — 需要跨 Session**

跨 Session 的信息不是走日志系统，而是通过**业务表**记录的：

```Plain Text
AutoAgentChat 表（对话表）
    chatId / userId / agentId / chatTitle / createTime

AutoAgentChatItem 表（对话条目表）
    chatId / chatItemId / chatObj (HUMAN/AI) / chatValue
    traceId / runningTime / user_goodfeedback / user_badfeedback

查询方式:
    SELECT * FROM auto_agent_chat WHERE userId = ? ORDER BY createTime DESC
    → 列出用户的所有 Session
    → 点击某个 Session 查看完整对话
```

#### **长期记忆层面 — 需要跨 Session**

跨 Session 的业务信息通过 **Memory 模块**传递，而非日志：

```Python
# ADKExecutor.get_prompt()
if self.agent_meta.longTermMemory:
    text += f"\n\n【长期记忆】以下是和问题相关的记忆\n{self.agent_meta.longTermMemory}"
```

长期记忆存储在 Milvus 向量库中，通过 Mem0 管理，跨 Session 检索。

### **是否需要专门的"跨 Session 日志系统"？**

**不需要**。原因：

### **如果有跨 Session 分析需求怎么做**

```Plain Text
场景：需要分析"某个用户的所有 Session 中，哪些对话质量较低"

方案：
  1. 查 AutoAgentChat 表 → 找到该用户的所有 chatId
  2. 查 AutoAgentChatItem 表 → 找到这些 chatId 的对话内容 + feedback
  3. 如果开启了 ES 同步 → 在 ChatItemES 中做全文检索
  4. 如果需要 LLM 评分 → 通过 ProductionDataEvaluationService 做批量评测
```

### **面试话术**

> 跨 Session 不需要专门的日志系统。日志系统只关注单次请求的端到端追踪，用 traceId 关联即可。跨 Session 的业务信息已经由业务表（AutoAgentChat \+ AutoAgentChatItem）和 Memory 模块（Mem0 \+ Milvus）覆盖了。如果要做跨 Session 分析，直接查 DB 或 ES 即可，不需要引入额外的日志系统。
> 
> 

---

<a id="q35"></a>
## **Q35: 项目的评测方法是什么？数据集自建、沙箱、监控怎么做的？准确率优化方案是什么？**

### **评测体系总览**

PA 的评测体系分为**三大模式**，覆盖不同场景：

```Plain Text
┌─────────────────────────────────────────────────────────────┐
│                   PA 评测体系                                  │
├─────────────────┬───────────────────┬───────────────────────┤
│   RAGAS 评测     │   召回评测          │   生产数据评测           │
│   (RagasEval)    │   (RecallEval)     │   (ProdDataEval)      │
├─────────────────┼───────────────────┼───────────────────────┤
│ 评测对象: Agent   │ 评测对象: 检索质量   │ 评测对象: 真实用户数据   │
│ 调用方式: RAGAS   │ 调用方式: LLM 打分   │ 调用方式: LLM 打分      │
│ 指标: 7 个        │ 指标: relevance     │ 指标: 自定义 Prompt    │
│ 场景: 离线数据集   │ 场景: 检索链路诊断   │ 场景: 线上监控          │
└─────────────────┴───────────────────┴───────────────────────┘
```

### **一、RAGAS 评测**

```Java
// RagasEvalService.java — 核心流程
public void executeRagasEval(AgentEvaluationData data, AgentSnapshot agent,
                             ChatOutputVO chatOutput, ...) {
    // 1. 提取 context (知识库检索结果)
    List<String> contexts = getContexts(data, chatOutput, modelListVO.getChannelName());

    // 2. 提取 answer (LLM 生成结果)
    String answer = chatOutput.getChoices().get(0).getMessage().getContent();

    // 3. 调用 Python RAG Server 执行 RAGAS 评估
    RefereeCallResponseDTO resp = getRefereeCallResponseDTO(data, agent, ...);

    // 4. 保存评测结果
    AgentEvaluationProcessRagas processRagas = new AgentEvaluationProcessRagas();
    processRagas.setJudgmentResult(cleanResult);
    ragasService.save(processRagas);
}
```

**支持的 7 个指标**（Python 端 `ragas_eval.py`）：

```Python
all_metrics = [
    "answer_correctness",    # 答案正确性——与 ground truth 对比
    "answer_relevancy",      # 答案相关性——是否回答了问题
    "answer_similarity",     # 答案语义相似度——embedding 相似度
    "context_precision",     # 上下文精确率——检索结果有多少是相关的
    "context_recall",        # 上下文召回率——相关文档有多少被检索到
    "context_relevancy",     # 上下文相关性——检索结果与问题的匹配度
    "faithfulness",          # 忠实度——答案是否基于检索结果，有无幻觉
]

# 默认 4 个指标（平衡评估粒度与成本）
default_metrics = ["answer_correctness", "answer_similarity", "context_recall", "faithfulness"]

# Java 端实际使用 2 个（性能考虑）
private static final List<String> metrics = Arrays.asList("context_recall", "answer_correctness");
```

**调用链路**：

```Plain Text
Java RagasEvalService
  → POST /rag_algorithm/ragas_evaluate (Python RAG Server)
    → Python ragas_eval.py
      → 调用 LLM (如 GPT-4) 打分
      → 返回 RefereeCallJudgmentResultDTO
  → 保存到 AgentEvaluationProcessRagas 表
```

### **二、召回评测 \(RecallEvalService\)**

```Java
// RecallEvalService.java — 核心逻辑
public void executeRecallEval(...) {
    // 1. 获取召回数据
    List<AgentEvaluationProcessRecall> recallDataList = processRecallService.findRecallData(...);

    // 2. 按 recallModelId 分组
    Map<String, List<AgentEvaluationProcessRecall>> evalRecallDataMap = recallDataList.stream()
        .collect(Collectors.groupingBy(AgentEvaluationProcessRecall::getRecallModelId));

    for (entry : evalRecallDataMap) {
        // 3. 按 similarity 排序，取 Top N
        List<AgentEvaluationProcessRecall> topN = entry.getValue().stream()
            .sorted(Comparator.comparing(AgentEvaluationProcessRecall::getRecallSimilarity).reversed())
            .limit(agentEvalProperties.getRecallDataEvalLimit())
            .collect(Collectors.toList());

        // 4. LLM-as-Judge 打分
        for (recallData : topN) {
            executeModelChatEvalRecall(userInfo, recallData, evalModelVO, prompt,
                data.getQuestion(), recallData.getSegmentContent());
        }

        // 5. 未进入 Top N 的标记为忽略
        ignoreModelChatEvalRecall(recallData);
    }
}
```

**LLM\-as\-Judge 的 Prompt 模板**：

```Plain Text
System: 你是一个评估助手，请判断检索到的文档片段与用户问题的相关程度。
User: query: {用户问题}, context: {检索到的片段}
→ 返回 JSON: {"relevance_score": 0.85}
```

### **三、生产数据评测 \(ProductionDataEvaluationService\)**

```Java
// ProductionDataEvaluationService.java — 核心流程
public void executeEval(AgentEvaluation agentEvaluation, SessionUserInfo userInfo) {
    // 1. 从 ES (ChatItemES) 读取真实的用户对话数据
    List<ChatItem> chatItemAIDataList = findChatItemAIData(agentParam, agentSnapshot);
    List<ChatItem> chatItemHumanDataList = findChatItemHumanData(agentParam, agentSnapshot, dataIds);

    // 2. 组装评测数据集
    Map<AgentEvaluationData, AgentEvaluationDataResult> dataToResultMap = ...;

    // 3. 线程池并发执行评测
    ThreadPoolExecutor evalExecutor = createEvalExecutor(poolSize);
    for (dataEntry : dataToResultMap) {
        evalExecutor.submit(() -> {
            executeCustomMetricEval(userInfo, parameterDTO, evalModelVO, dataEntry.getKey(), dataEntry.getValue());
        });
    }

    // 4. 聚合结果
    aggregateResultService.aggregateResultV2V3(agentEvaluation, agentSnapshot);
}
```

**生产数据评测的特点**：

### **数据集自建**

PA 的评测数据集通过 **AgentEvaluationData** 表管理，支持两种构建方式：

```Plain Text
方式 1: 手动构建（在线数据集）
  AgentEvaluationController
    POST /api/v1/agenteval/create   ← 创建评测任务
    POST /api/v1/agenteval/export   ← 导出评测数据
    POST /api/v1/agenteval/downloadTemplate  ← 下载导入模板

  数据字段:
    question:    用户问题
    answer:      Ground Truth（标准答案）
    contexts:    上下文/参考文档
    globalVars:  全局变量

方式 2: 从生产数据构建
  ChatItemES  →  ProductionDataEvaluationService
    ├── 按 agentId + version 过滤
    ├── 按时间范围过滤 (dataStartTime ~ dataEndTime)
    ├── 按数量限制 (dataAmount)
    └── 自动配对 HUMAN/AI 对话对
```

**多轮对话评测数据集**：

```Java
// MultiRoundEvalDatasetController.java
// 支持多轮对话的评测数据集管理
// 每一轮包含: question → AI answer → 下一轮 question → ...
```

### **沙箱（评测执行环境）**

评测的"沙箱"实际上是**隔离的评测执行环境**：

```Plain Text
执行沙箱:
  ├── 独立的线程池 (evalExecutor)
  │     ├── corePoolSize = prodPoolSize (可配置)
  │     └── maxPoolSize = prodPoolSize
  │
  ├── 独立的评测模型 (evalModelVO)
  │     ├── 与 Agent 生产模型不同
  │     ├── 通常使用更强模型 (如 GPT-4) 做 Judge
  │     └── 通过 qaEvalAgentChatService.queryEvalModel() 获取
  │
  ├── 错误隔离
  │     ├── 单条数据评测失败不影响整体
  │     └── evalFailedWhenError = false (默认)
  │
  └── 取消机制
        ├── 每次迭代检查 CANCELED 状态
        └── 取消后正在执行的任务继续执行但不再提交新任务
```

### **监控**

PA 的线上监控主要通过**用户反馈 \+ 埋点**实现：

```Plain Text
反馈采集:
  POST /api/v1/autoAgent/chat/updateUserFeedBack
    ├── user_goodfeedback (👍 点赞)
    └── user_badfeedback  (👎 点踩)

埋点统计:
  statisticAutoAgentChatUsages()
    ├── agentId + version
    ├── chatId + 对话轮次
    ├── 模型消耗 token 数 (customMetadata.getUsages())
    └──── totalTokens = Σ usage.totalToken
```

反馈数据存储在 `AutoAgentChatItem` 表中：

```Java
@TableField(value = "user_goodfeedback", updateStrategy = FieldStrategy.IGNORED)
private String userGoodFeedBack;

@TableField(value = "user_badfeedback", updateStrategy = FieldStrategy.IGNORED)
private String userBadFeedBack;
```

**生产评测也可以作为监控手段**——定期用 ProductionDataEvaluationService 评测最近 N 天的对话数据，生成质量报告。

### **准确率优化方案**

PA 的准确率优化不是单一手段，而是一个**持续迭代的闭环**：

```Plain Text
评测发现低分 → 分析根因 → 针对性优化 → 重新评测验证
```

**常见的优化手段**：

**具体的 RAGAS 指标优化闭环**：

```Plain Text
context_recall 低
    → 检索引擎（增加 recallLimit、调整 embedding 模型）
    → 召回评测验证（RecallEvalService）
    → 重新 RAGAS 评测

answer_correctness 低
    → 检查 ground truth 是否合理
    → 优化 Prompt（增加输出格式约束）
    → 人工校验 + 重新评测

faithfulness 低（幻觉）
    → 加强 Prompt: "请严格基于检索结果回答"
    → 增加 Post-Processing 校验
    → 对话摘要优化
```

### **面试话术**

> PA 的评测体系分三层：RAGAS 评测（自动化指标）、召回评测（LLM\-as\-Judge 打分检索质量）、生产数据评测（用真实用户数据做批量评估）。数据集支持手动构建和从 ES 生产数据自动生成。监控通过用户点赞/点踩反馈 \+ 使用量埋点实现。准确率优化是持续迭代闭环：评测发现问题 → 分析根因（Prompt/检索/模型） → 针对性优化 → 重新验证。
> 
> 

---

<a id="q36"></a>
## **Q36: 模型的幻觉产生原因是什么？怎么减少？**

### **幻觉的定义**

在 RAG 架构中，幻觉指的是 **LLM 生成的回答中包含不在检索结果中的信息**，或**与事实不符的内容**。

### **幻觉的产生原因**

#### **原因 1：检索不完整（上下文缺失）**

```Plain Text
用户问题 → 向量检索 → 未检索到关键文档
         → LLM 基于不完整上下文回答
         → LLM 用自身知识"脑补"缺失信息 → 幻觉
```

**表现**：RAGAS 的 `context_recall` 指标低，即相关文档未被召回。

#### **原因 2：Prompt 约束不足**

```Plain Text
System: "你是一个智能助手..."
（缺少 "请严格基于以下参考资料回答" 的约束）
→ LLM 倾向于自由发挥 → 幻觉
```

#### **原因 3：模型自身倾向**

- LLM 的预训练目标使其倾向于**生成流畅连贯的文本**，而不是严格的事实性

- RLHF 训练让模型更"乐于助人"，有时会编造答案而不是承认不知道

#### **原因 4：知识库质量**

- 知识库中的文档本身过期或错误

- 文档切片策略不合理（关键信息被切碎）

- embedding 模型对专业术语理解不足

#### **原因 5：多轮对话中的上下文污染**

```Plain Text
第一轮: 用户问 "A 股走势如何" → AI 回答 "上证指数 3200 点"
第二轮: 用户问 "那港股呢" → AI 可能混淆上下文，用 A 股信息回答港股
```

### **减少幻觉的手段**

PA 在实践中使用以下手段：

#### **手段 1：RAGAS Faithfulness 指标监控**

```Python
# ragas_eval.py — faithfulness 指标
# 定义: answer 中的每个 claim 是否能被 context 支持
# 检测方式: LLM 逐句判断 answer 中的陈述是否在 context 中有依据
```

将 faithfulness 纳入评测指标，每次迭代跟踪幻觉率。

#### **手段 2：Prompt 层面约束**

```Plain Text
在 System Prompt 中增加:
1. "请严格基于以下【背景知识】回答"
2. "如果背景知识不足以回答问题，请说'根据现有信息无法回答'"
3. "不要编造或推测不存在的信息"
```

#### **手段 3：引用溯源（Quote）**

```Java
// filterQuote() → 将知识库检索结果标记来源
// 模板: "[{index}] {q} [{a}]"
// 效果: 用户可以看到每个结论的知识来源
// LLM 在引用机制下更倾向于忠实于原文
```

引用溯源有两个作用：

1. **对 LLM**——在 Prompt 中加入引用格式，引导 LLM 指向具体来源

2. **对用户**——用户可以点击引用链接验证答案真实性

#### **手段 4：混合召回提高检索质量**

```Plain Text
纯向量检索的幻觉风险:
  query: "iPhone 15 充电参数"
  → 向量检索到"iPhone 15 评测"（语义相关但缺少参数）

混合召回:
  向量检索: "iPhone 15 评测"  (语义匹配)
  BM25 全文: "iPhone 15 充电参数 5V/2A"  (关键词匹配)
  RRF 融合 + Rerank → 精确结果排到前面

效果: context_recall 提高 → 幻觉减少
```

#### **手段 5：摘要压缩减少上下文丢失**

```Java
// AISummaryService.getAISummary()
// 长对话 → LLM 摘要 → 替换原始历史
// 减少 token 超限导致的"信息遗忘"
```

当对话历史过长时，如果不做摘要直接截断，会丢失关键上下文，导致 LLM 用自身知识补充。摘要是**语义压缩而非截断**，保留关键信息。

#### **手段 6：生产环境反馈闭环**

```Plain Text
用户反馈 (badfeedback) → 标记低质量回答
  → 人工审核 → 分析原因
    → Prompt 问题 → 修改 Prompt
    → 检索问题 → 优化知识库
    → 模型问题 → 更换模型/调整参数
```

### **面试话术**

> 幻觉的本质是 LLM 回答了不在检索结果中的内容。PA 从"检索引擎 \+ Prompt 约束 \+ 监控反馈"三个层面减少幻觉：检索引擎通过混合召回 \+ RRF \+ Rerank 提高 context\_recall；Prompt 层面增加"严格基于背景知识回答"的约束并启用引用溯源机制；监控层面通过 RAGAS faithfulness 指标 \+ 用户反馈形成闭环。最有效的手段是让 LLM 能明确说出"不知道"——Prompt 中明确要求无法回答时主动声明。
> 
> 

---

<a id="q37"></a>
## **Q37: Agent 部署时遇到哪些问题？性能优化、并发控制怎么做？**

> 本题结合两个项目回答：PowerAgent（Java agentflow + Python af-rag-server，Agent 平台）和 ragent（Java 单体，RAG 系统）。ragent 在并发控制和稳定性上做得更深，面试时优先讲 ragent 的分布式限流和断路器。

---

### 一、部署中遇到的问题（4 类）

#### 问题 1：异构架构依赖冲突（PowerAgent）

```Plain Text
场景: Java Server (agentflow-server) 依赖 Python RAG Server (af-rag-server)

痛点:
  - Python 端需要 GPU（向量化 + Rerank）
  - Java 端 CPU 密集型
  - 混合部署资源争抢、依赖节奏不同步

解决方案:
  分离部署（K8s 三集群）:
    Java Server       → CPU 集群（Deployment）
    Python RAG Server → GPU 集群（独立 Deployment）
    Milvus/ES/Redis   → 独立中间件集群
  之间通过 OkHttp / HTTP 通信
```

**ragent 的对比**：ragent 是 Java 单体，没有跨语言依赖问题，但 AI 能力（文档解析、向量化）要靠外部服务（MinerU、模型 API）。

#### 问题 2：模型服务不稳定（LLM/embedding/rerank 超时）

```Plain Text
表现:
  - LLM 服务超时（70B+ 大模型）
  - embedding 服务间歇性不可用
  - Rerank 服务响应慢

PowerAgent 方案:
  - Sentinel 熔断 + X-SENTINEL header 传递降级状态
  - 超时控制: 评测场景 6 分钟连接超时 + 10 分钟读超时

ragent 方案（更完善）:
  - ModelHealthStore 三态断路器（CLOSED/OPEN/HALF_OPEN）
    · 连续失败阈值 2 → 熔断打开，openDurationMs=30s 后半开探测
  - ModelRoutingExecutor 多候选模型故障转移
  - ProbeStreamBridge 首包超时 60s → 切换候选模型
  - LlmFirstPacketProbe 记录 TTFT（首包延迟）
```

#### 问题 3：SSE 长连接管理

```Plain Text
问题:
  - SSE 是长连接，高并发时连接数暴增
  - 客户端断开后服务端还在推送（Broken pipe）

PowerAgent 方案:
  - CountDownLatch 控制生命周期
  - scheduler.shutdown() 清理
  - CompletableFuture.runAsync() 异步保存

ragent 方案:
  - SseEmitterSender 用 AtomicBoolean CAS 保证只关一次
  - StreamTaskManager 跨节点取消（Redis 标记 + RTopic 广播）
  - 取消时保存部分回答（不丢已生成内容）
  - ClientAbortException 静默处理（客户端断开是预期，不算错误）
```

#### 问题 4：数据库写入瓶颈

```Plain Text
PowerAgent 方案:
  - 异步写入: CompletableFuture.runAsync() + 独立线程池
  - 批量 insert: saveBatch(items)
  - 异步统计

ragent 方案:
  - 8 个业务隔离线程池（chat/检索/MCP/记忆/分块各一个）
```

**ragent 的两个"原子性"设计（面试重点，易混淆）**：

##### 原子性 1：事务原子写入（库内，单事务多步写）

`persistChunksAndVectorsAtomically` —— 一个文档重新分块后，4 份数据要同时更新，用 `TransactionTemplate.executeWithoutResult` 包成一个事务：

```Java
transactionOperations.executeWithoutResult(status -> {
    knowledgeChunkService.deleteByDocId(docId);          // ① 删旧 chunk
    knowledgeChunkService.batchCreate(docId, chunks);    // ② 写新 chunk
    vectorStoreService.deleteDocumentVectors(...);       // ③ 删旧向量
    vectorStoreService.indexDocumentChunks(...);         // ④ 写新向量 + 关键词索引
    // ⑤ 更新 document.status = SUCCESS
});
```

**解决什么**：chunk 表和向量表的一致性。不包事务的后果——①② 成功、③ 失败，会出现"新 chunk 已写入但旧向量还在"，检索时新旧数据混杂。

**机制**：这是数据库事务 ACID 的 A（原子性），任一步抛异常，前面已执行的都回滚。

##### 原子性 2：RocketMQ 事务消息（跨系统，DB + MQ）

`startChunk` 要同时做"改 DB 状态"和"发 MQ 消息"，但 PG 和 RocketMQ 是两个系统，无法用普通事务包住。用事务消息解决：

```
① 先发"半消息"（Broker 暂存，不投递给消费者）
② 执行本地事务: UPDATE document.status = RUNNING
③ 事务成功 → commit（Broker 投递消息）
   事务失败 → rollback（Broker 丢弃消息）
④ 生产者在 ②③ 之间挂了 → Broker 回查
   → KnowledgeDocumentChunkTransactionChecker.check()
   → SELECT status FROM document WHERE id=docId
   → status==RUNNING ? commit : rollback
```

**解决什么**：防止"status 改了但消息没发"（任务丢失）或"消息发了但 status 没改"（状态错乱）。

**机制**：分布式事务，靠"半消息 + 本地事务 + Broker 回查 DB"三段式兜底。

**两个原子性的区别**：

| | 事务原子写入 | RocketMQ 事务消息 |
|---|---|---|
| 解决什么 | 一次分块 4 个写操作一致性 | DB 更新 + 消息发送一致性 |
| 范围 | 单库内（PG） | 跨系统（PG + RocketMQ） |
| 机制 | 数据库事务（TransactionTemplate） | 半消息 + 本地事务 + 回查 |
| 失败后果 | chunk 和向量不一致 | 任务丢失 / 重复分块 |

完整链路：`startChunk`（事务消息保证 status+消息原子）→ 消费者 `runChunkTask`（事务写入保证 chunk+向量原子）→ 结果要么完整更新，要么完全没变（FAILED 可重试）。

---

### 二、性能优化（5 个方向）

#### 优化 1：检索侧优化（ragent，最有效）

```Plain Text
问题: 意图为空 → 全局检索 → 全库 19 chunks → 大 Prompt → LLM 生成慢

优化:
  ① 意图树兜底节点（数据层）: 加"通用知识"节点，定向到具体 collection
     19 chunks（全局）→ 5 chunks（定向），Prompt 缩小 40%
  ② minSimilarity=0.3（SQL 层）: 低分 chunk 在 PG 层直接丢弃
     减少 Rerank 交叉编码成本 + Prompt 噪声
  ③ 候选预算 candidateBudget: 控制送入 Rerank 的候选规模
```

#### 优化 2：缓存（ragent）

```Plain Text
① MinerU RustFS 缓存:
   同一文件重分块，命中 SHA-256 缓存跳过 MinerU API
   首次 7s → 缓存命中 <1s

② 意图树 Redis 缓存:
   t_intent_node 加载后存 Redis（key=ragent:intent:tree，7天 TTL）
   每次意图分类直接从 Redis 读，不打 DB
```

#### 优化 3：Token 计算快速路径（PowerAgent）

```Java
// ChatContextFilter.filterMessages()
// 文本长度 < maxTokens * 0.5 时跳过 JTokkit 精确计算
if (rawTextLen < maxTokens * 0.5) return;
```

#### 优化 4：异步化（两项目通用）

```Plain Text
① 异步 SSE: Controller 立即返回 SseEmitter，业务异步执行
② 异步落库: CompletableFuture.runAsync + 独立线程池
③ 异步日志: AsyncAppender（queueSize=100000，discardingThreshold=0）
④ 异步缓存写入: MinerU 解析结果异步写 RustFS，不阻塞主流程
```

#### 优化 5：并行执行

```Plain Text
① 混合检索并行: 向量检索 + 全文检索 CompletableFuture 并行，再 RRF 融合
② 多通道并行: ragRetrievalExecutor 线程池并行跑 4 个检索通道
③ 多 MCP 工具并行: mcpBatchExecutor 并行执行多个工具
④ 多子问题意图并行: intentClassifyExecutor 并行打分
⑤ 记忆加载并行: 摘要 + 历史 CompletableFuture 并行加载
```

---

### 三、并发控制（重点，ragent 最硬核）

#### 控制 1：分布式公平限流（ragent 核心亮点）

`FairDistributedRateLimiter` —— 不是简单信号量，是"可过期信号量 + ZSet 公平队列 + Lua 原子 claim + RTopic 通知"组合：

```
组件:
  RPermitExpirableSemaphore  → 全局并发数（max-concurrent=10）
  RScoredSortedSet (ZSet)    → 公平队列（雪花 score 排序）
  Lua 脚本                    → 原子 claim + 清理僵尸条目
  RTopic                     → 跨实例唤醒 poller
  Ticket 状态机              → PENDING → GRANTED / TIMED_OUT / CANCELLED

流程:
  请求 → enqueue 入队 → 轮询(200ms) → Lua 原子抢占 permit
    → 超时(15s) → 拒绝 → SSE REJECT + "系统繁忙"
    → lease(30s) 自动过期 → 防持有者崩溃后 permit 泄漏
```

**为什么是"公平"**：简单信号量是"谁先抢到谁先跑"，可能饿死；ZSet 按入队序号排序，保证先来先服务。

#### 控制 2：信号量池（ragent）

| 信号量 Key | 用途 | 上限 | lease |
|---|---|---|---|
| `rag:global:chat:semaphore` | 全局聊天并发 | 10 | 30s |
| `rag:document:upload` | 文件上传 | 10 | 300s |
| `rag:mineru:parse` | MinerU 解析（限外部 SaaS） | 5 | 900s |

全部 `RPermitExpirableSemaphore`，lease 自动过期防死锁。

#### 控制 3：模型断路器（ragent）

```Java
ModelHealthStore 三态:
  CLOSED → 连续失败 >= 2 次 → OPEN（熔断，拒绝调用）
  OPEN   → openDurationMs=30s 后 → HALF_OPEN（放一个探测请求）
  HALF_OPEN → 成功 → CLOSED；失败 → 重新 OPEN

配合 ModelRoutingExecutor:
  遍历候选模型 → allowCall() 跳过熔断中的 → 失败 markFailure 切下一个
```

#### 控制 4：线程池隔离（两项目通用）

```Plain Text
PowerAgent:
  - 评测线程池与业务线程池分离（prod-eval- 前缀）
  - 避免评测任务耗尽系统资源

ragent（8 个隔离线程池）:
  - chatEntryExecutor     对话入口（SynchronousQueue + AbortPolicy）
  - ragRetrievalExecutor  多通道检索
  - mcpBatchExecutor      MCP 工具批量执行
  - ragContextExecutor    子问题上下文构建
  - intentClassifyExecutor 意图分类
  - memoryLoadExecutor    记忆加载
  - modelStreamExecutor   流式输出
  - memorySummaryExecutor 摘要压缩

拒绝策略: AbortPolicy(快速失败) / CallerRunsPolicy(背压)
StreamAsyncExecutor 捕获 RejectedExecutionException → 取消 OkHttp call + 回调 onError
```

#### 控制 5：熔断降级（PowerAgent Sentinel）

```Java
// SentinelOkHttpInterceptor 传递降级状态
X-FALLBACK header: "Y"(有降级) / "N"(无降级)
// 下游根据 header 决定是否降级，防级联故障
```

#### 控制 6：幂等（ragent）

```Java
① @IdempotentSubmit: Redisson RLock.tryLock()
   防并发重复提交（chat/stop 接口）

② @IdempotentConsume: Redis Lua SET NX GET PX
   防 MQ 重复消费（CONSUMING/CONSUMED 状态机）

③ RocketMQ 事务消息:
   status 更新与消息发送原子，Broker 回查 DB 判断是否投递
```

#### 控制 7：数据库并发（PowerAgent）

```Java
// 取消操作的并发安全：检查 CANCELED 状态避免已取消任务继续执行
if (AgentEvaluationStatusEnum.CANCELED.equals(status)) {
    return;  // 已取消，直接返回
}
```

---

### 四、可观测性（面试加分项）

```Plain Text
三层监控（PowerAgent）:
  Langfuse  → LLM 调用追踪（Trace/Span/Generation）
  Pinpoint  → 全链路 APM（Java + Python，traceId 透传）
  Prometheus → 指标监控（/metrics 端点）

ragent 的 Trace 体系:
  @RagTraceNode AOP + TTL 透传（TransmittableThreadLocal + 深拷贝）
  t_rag_trace_run + t_rag_trace_node 表落库调用树
  记录 TTFT（首包延迟）、各阶段耗时、LLM 路由
  排查慢链路: 一条请求的每阶段耗时直接可读
```

---

### 五、面试话术（模板）

> 部署主要有两类挑战：一是异构架构的依赖管理（Java CPU 集群 + Python GPU 集群分离部署），二是模型服务的不稳定性。
>
> 性能优化核心是"异步化 + 并行化 + 缓存 + 检索侧收敛"：异步 SSE、异步落库、异步日志；混合检索多通道并行；MinerU 结果缓存跳过重复解析；意图定向 + 相似度阈值让检索候选从 19 收敛到 5。
>
> 并发控制我重点做的是分布式公平限流——不是简单信号量，而是 Redis ZSet 公平队列 + Lua 原子 claim + 可过期许可的组合，解决"高并发下先来先服务 + permit 不泄漏"两个问题。配合模型三态断路器（连续失败 2 次熔断、30s 半开探测）、线程池隔离（8 个业务池）、幂等双切面（提交幂等 + 消费幂等）。
>
> 一句话总结：部署靠分层（异构分离 + 中间件独立），性能靠异步并行（SSE/落库/检索），并发靠公平限流 + 断路器 + 隔离 + 幂等四件套。

### 六、两项目对比速记

| 维度 | PowerAgent | ragent |
|---|---|---|
| 架构 | Java + Python 分离 | Java 单体 |
| 限流 | Sentinel 熔断 | FairDistributedRateLimiter 公平队列 |
| 断路器 | Sentinel（已引入未深度用） | ModelHealthStore 自研三态 |
| 模型降级 | LiteLLM 代理 | ModelRoutingExecutor 多候选 fallback + 首包探测 |
| 幂等 | 无声明式 | @IdempotentSubmit + @IdempotentConsume |
| 流式取消 | CountDownLatch | StreamTaskManager 跨节点 Redis 广播 |

**面试策略**：如果只讲一个项目，ragent 的并发控制（公平限流 + 断路器 + 幂等）是更好的素材——比 PowerAgent 的 Sentinel 更深入、更能体现工程能力。


---

<a id="q38"></a>
## **Q38: 你在项目中遇到了什么困难？怎么解决的？**

### **困难 1：Agent 执行链路的不透明性**

**问题描述**：

Agent 一次对话涉及多个步骤：意图识别 → 工具调用 → 知识库检索 → LLM 推理 → 回复生成。在项目初期，整个执行链路是黑盒——问题出在哪个环节完全无法定位。

```Plain Text
用户反馈"回答不对"
  → 是意图识别错了？工具调用失败了？知识库没检索到？还是 LLM 生成有问题？
  → 没有追踪手段，全靠猜测
```

**解决方案**：

构建**全链路追踪体系**：

```Plain Text
1. traceId 贯穿整个请求
   Controller 生成 requestId → SSE Listener 使用 → DB 持久化

2. 每一步都有记录 (AutoAgentChatItemDetail)
   每个 function_call / function_response / tool_execution 都记录
   每个 Detail 带 executeTime 字段，精确到毫秒

3. SSE 事件流实时推送
   每个事件类型（content/function_call/function_response/error）
   前端实时展示 Agent 的"思考过程"

4. 错误码体系
   ATA_20000: 正常
   ATA_60000: 系统错误
   ATA_70000: 调用错误
```

**效果**：将黑盒链路变成了白盒——每次对话都能完整复现 Agent 的执行过程。

### **困难 2：长对话的 Token 超限**

**问题描述**：

Agent 对话天然是多轮的。当对话进行到第 20 轮、第 30 轮时，历史消息积累到几万甚至十几万 token，远超 LLM 的上下文窗口。

```Plain Text
原始方案: 简单截断
  → 丢失了早期的关键信息
  → 用户问"跟之前说的比怎么样"时，Agent 已经不记得"之前说的"是什么
```

**解决方案**：

实施**三级 Token 管理策略**：

```Plain Text
第 1 级: 快速路径
  如果 rawTextLen < maxToken * 0.5 → 跳过所有处理
  适用: 对话初期（大部分场景）

第 2 级: 截断（未开摘要）
  从后往前保留最近对话
  保证最近的 N 轮对话完整
  旧消息被丢弃

第 3 级: LLM 摘要（开启摘要）
  不截断原始消息
  调用 LLM 对历史生成摘要
  systemPrompt + "\n[history]\n" + summaryPrompt
  摘要后仍然超长 → 抛出明确异常
```

**关键决策**：**摘要比截断好**——截断丢失信息，摘要是语义压缩。但摘要也有成本（额外 LLM 调用 \+ 延迟）。

### **困难 3：混合检索的效果调优**

**问题描述**：

向量检索对"iPhone 15 充电参数"这种混合语义查不准——语义相似但文档中可能不包含精确参数。纯 BM25 又无法理解同义词（如"充电参数"和"电源规格"）。

**解决方案**：

实施**混合召回 \+ RRF 融合 \+ Rerank 重排序**：

```Plain Text
Step 1: 并行检索
  向量检索（语义） + BM25 全文（关键词）

Step 2: RRF 融合
  score = Σ 权重 / (k + rank)
  向量权重=6, 全文权重=4, k=60
  解决不同检索器分数分布不一致的问题

Step 3: Rerank（可选）
  Cross-Encoder 交叉编码器
  query + chunk 拼接后打分，比双塔向量更精准
```

**效果**：混合召回比单路向量检索的 context\_recall 提升了 15\-20%（通过 RecallEvalService 评测验证）。

### **困难 4：评测体系从 0 到 1 的建设**

**问题描述**：

项目初期没有任何评测手段。Agent 效果好不好全靠人工体验，无法量化、不可重复。优化了一个点也不知道是否真的有进步。

**解决方案**：

分三步建设评测体系：

```Plain Text
Phase 1: 离线评测（RAGAS + 召回评测）
  建设评测数据集（手动标注 + 生产数据提取）
  自动化指标计算（answer_correctness, context_recall 等）
  可重复执行，每次迭代跑一遍

Phase 2: 生产数据评测
  从 ES 读取真实用户对话
  LLM-as-Judge 批量打分
  多版本 A/B 对比

Phase 3: 线上监控
  用户点赞/点踩反馈采集
  使用量埋点统计
  评测结果 → 优化 → 再评测的闭环
```

**效果**：从"凭感觉"到"看数据"——每次优化都有量化指标验证。

### **困难 5：Java \+ Python 异构系统的集成**

**问题描述**：

PA 的核心架构是 Java Server \+ Python RAG Server，两者有不同的部署环境、依赖管理和版本节奏。

```Plain Text
Java: Spring Boot + MyBatis + Maven
Python: Flask + LangChain + PyTorch

痛点:
  - 接口定义不一致（Java 用 DTO，Python 用 dict）
  - 错误处理不对齐
  - 部署节奏不同步
  - 问题排查需要跨团队
```

**解决方案**：

```Plain Text
1. 接口契约化
   RefereeCallRequestDTO / RefereeCallResponseDTO
   结构化请求/响应，两端代码生成

2. 超时 + 熔断
   connectionTimeout = 6min, readTimeout = 10min
   SentinelOkHttpInterceptor 熔断降级

3. 日志对齐
   两端都使用 traceId 做关联
   Python 端日志也携带 requestId

4. 独立部署
   各自 K8s 集群，独立扩缩容
   通过 HTTP 通信，中间件解耦
```

### **面试话术**

> 最大的困难其实是"Agent 不可控带来的不安全感"——你不知道它下一步会调什么工具、会不会跑偏、回答有没有幻觉。解决的思路不是限制 Agent（那就失去了 Agent 的价值），而是让执行链路透明化（Trace 体系）、效果可量化（评测体系）、出错有兜底（熔断降级 \+ 用户反馈）。这三板斧建起来之后，Agent 的迭代才有了数据驱动的基础，团队的信心也建立起来了。
> 
> 



