# PowerAgent 简历亮点 —— 面试问题与回答

> 仅场景化描述。不贴源码，解释类/机制的作用。

---

## 一、DAG 拓扑执行引擎

### 简历原文
> 设计 DAG 拓扑执行引擎，用户拖拽节点画布 → 引擎自动解析 JSON → 识别入口节点 → 按拓扑顺序递归执行，支持同步/并发双模式。采用 @NodeType 注解 + ModuleFactory 工厂模式，30+ 内置节点启动自动注册，新增节点无需修改引擎内核。

---

**Q1: 前端拖拽的 JSON 长什么样？引擎怎么变成可执行节点？**

前端画布上的连线最终变成一份 JSON：每个节点有`moduleId`（唯一身份）、`flowType`（是 LLM 节点还是检索节点）、`inputs`（接收什么数据）、`outputs`（产出什么）、`edges`（连向下游哪几个节点）。

引擎拿到 JSON 后，遍历每个节点，根据 `flowType` 查一张**类型到执行器的注册表**（ModuleFactory 维护的），匹配到对应的执行器——比如 `CHAT_NODE` 对应 LLM 对话执行器、`DATASET_SEARCH` 对应检索执行器。然后包装成带运行状态的对象（输入就绪没、执行开始了吗、执行多久了），放回运行列表。

这就像**地铁调度中心收到一张运行图**——每个站点（节点）有类型（换乘站/终点站）、有上游下游客流（inputs/outputs），调度员不需要知道每个站内部怎么运作，只需要按图调度列车（数据流）。json 就是那张运行图。

---

**Q2: 入口节点怎么识别？多入口怎么办？**

遍历所有节点，找**没有任何人连接它的输入端**的节点。这就像找**河流的源头**——上游没有任何支流汇入的地方就是源头。多个源头（多入口）→ 全部作为首批节点同时启动。

---

**Q3: 同步和并发模式什么时候切换？**

前端画布上有一个开关——用户勾选"并发执行"就传 `concurrency: 1`。

- **同步模式**（不勾选）：像走楼梯，一个节点跑完再跑下一个，先跑完的等后跑完的一起往下走
- **并发模式**（勾选）：像多部电梯同时开，同层无依赖的节点全部并行，等这一层所有电梯都到了再开下一层

为什么需要两种：同步模式适合节点少、逻辑简单的工作流（省线程池开销）；并发模式适合多路检索这种"两边同时查、汇合后再处理"的场景。

---

**Q4: 并发模式下，怎么等一批节点全跑完？为什么不能用简单的线程池？**

两个工具配合：

- **CountDownLatch（倒计时门闩）**：就像一个计数器——3 个节点并行跑，门闩设 3。每跑完一个节点（无论成功失败）就"滴"一声减 1，减到 0 时门打开，引擎放行继续处理下一层
- **CompletionService（邮件收发室）**：在当前实现下，引擎必须等本层最慢节点跑完才能处理下一层；CompletionService 仅用于规避按索引取结果的阻塞风险，而非实现“提前触发下游”。
两个组合的优势：门闩确保全批完成（不会丢节点），收发室让引擎按实际完成顺序处理结果（不会因最慢的节点拖住所有下游）。

---

**Q5: 中间节点执行失败，下游怎么处理？**

关键要区分三种情况——不是"自动跳过"这么简单：

**情况一：独立分支——不受影响**

```
失败节点 n2(向量检索) ← 挂了
    ↓ 无输出

n3(关键词检索) → 正常完成 ← 完全不依赖 n2，照常跑
```

失败节点画在边上的、与它无关的平行分支照常执行。这是"不中断流水线"的真实含义。

**情况二：直接依赖——等输入，等不到就阻塞**

```
失败节点 n2 → 挂了，没产出
    ↓ 边：n2.output → n4.input_1

n4(Rerank) → 检查 input_1 一直为空 → "输入没满足" → 不执行
```

引擎有个**输入就绪检查**（moduleCanRun）：节点只有所有 input 都有值才执行。失败节点没产出 → 下游节点的 input 永远为空 → 不执行。**依赖失败节点的下游会被间接阻塞**，不是自动跳过。

**此时三条出路**：

| 出路 | 机制 | 结果 |
|:---|:---|:---|
| **重试成功** | 节点配置了超时和重试次数，首次失败自动重试 | 最常见：重试后正常产出 → 下游照常 |
| **降级兜底** | 重试全失败后，引擎用节点预设的兜底值（moduleDefaultOutput）当作输出传给下游 | 下游拿到值 → 继续跑，但用的是备用数据 |
| **无降级** | 既没重试成功也没配置兜底值 | 下游永远等 → 前端画布亮红灯 → 人工介入 |

**情况三：间接依赖（链路后半段）——级联阻塞**

```
n2(挂了) → n4(等 n2) → n5(等 n4) → n6(等 n5)
              ↑            ↑            ↑
           都在等        都在等        都在等
```

n2 始终没产出 → 整条依赖链路全部阻塞，像断掉的电线后面灯泡全灭。

---

**Q6: 重试 3 次全失败后降级什么？**

每个节点在画布上可以配置一个**兜底值**（`moduleDefaultOutput`）。就像给工位预设了"机器坏了先用手动模式顶一顶"的备用方案。重试 3 次全失败后，引擎把兜底值当作输出传给下游，让依赖它的节点拿到值继续跑——这样整条流水线不会被一个节点的失败完全阻断。

**重点**：兜底值不是"自动跳过"——而是"用备用数据顶替"。下游拿到的不是失败节点的真实输出，而是预设的替代数据。这是保障依赖链路不断的关键设计。

---

**Q7: DAG 有环怎么办？会死循环吗？**

当前引擎**不在运行时检测环**——它假设前端画布保证无环（前端校验）。但有一个被动保护：每个节点被调起后，引擎的**去重表**（switchMap）会记录"这个节点的这个输入已被赋值过"。如果因为环导致同一节点被二次调度，去重表看到"这个输入已经有了"，不会再赋值，节点实际上不会重复执行。

这个机制不完美——它不阻止环存在，但阻止了环导致的无限制循环。生产环境需要在前端画布保存时做**拓扑排序校验**（BFS/DFS 检测回边），发现环直接拒绝保存。

---

**Q8: 两上游同时给同一下游 input 赋值——冲突？**

引擎用**线程安全的键值表**（ConcurrentHashMap）管理跨节点数据传递。如果两个上游线程同时写入同一个下游 input key，后写覆盖前写——是安全的，不会出现数据损坏。

但"该用哪个值"需要在画布上通过边的连接精确控制。同层的并行节点通常有独立输出 key（比如向量检索输出 `vector_result`、关键词检索输出 `keyword_result`），不会冲突。

---

**Q9: 50 个节点的 DAG 怎么防 OOM？**

先澄清一个关键区分——启动加载的 Bean 和画布上的节点是**两个不同的东西**：

```
启动时创建（一次性，全局共享）：
  50 种节点类型 = 50 个执行器 Bean
  每种类型只有 1 个实例，全局共享一份
  → 这是"菜谱"——50 道菜 50 本菜谱，放在后厨架子上，不随点单变化

每次请求创建（临时，请求结束释放）：
  前端 JSON → loadModules() → 创建节点元数据对象列表
  每个只存：moduleId + name + flowType + inputs/outputs 引用
  → 这是"点的菜"——每次下单新建 50 份订单记录，吃完丢弃
```

**关键：50 个画布节点不会创建 50 个执行器对象**。运行时引擎拿到节点后，只读它的 `flowType` 字符串字段，然后去映射表查：

```
节点 n1(flowType="CHAT_NODE") → 查表 → 拿到共享的 ChatCompletionServiceImpl 单例
节点 n2(flowType="CHAT_NODE") → 查表 → 拿到同一个 ChatCompletionServiceImpl 单例
节点 n3(flowType="DATASET_SEARCH") → 查表 → 拿到共享的 DatasetSearchServiceImpl 单例
```

50 个节点都是 CHAT_NODE → 全部调用同一个 Bean 实例 → 不存在"为每个节点创建独立执行器"。

**真正防 OOM 的三个机制**：

| 机制 | 保护什么 | 原理 |
|:---|:---|:---|
| **执行器单例化** | 内存不随节点数膨胀 | 1 类型 = 1 Bean，全局共享 |
| **线程池固定大小** | CPU 不被耗尽 | 并发数有上限，不随节点数线性增长 |
| **节点元数据轻量** | 单次请求内存可控 | 只存标识和引用，不深拷贝大文本 |

**结果队列**（CompletionService）容量有限更多是控制**并发完成的堆积速度**而非内存——它按完成顺序排列，通常只有同层并行节点数（最多 10-20 个），不会积压到内存临界。

**当前没有节点数量硬上限**——50 个节点在合理范围内，但主要保护来自执行器单例化，而非我之前说的"引用不复制"。那个是次要的，核心是**节点对象和执行器对象的分离设计**。

---

**Q10: @NodeType 注册机制对比"直接写一个实现类列表"的优势？**

直接写一个实现类列表（`List<ModuleService>`）只能拿到"所有实现"，不知道哪个对应哪个类型。

`@NodeType` 注解的作用：每个执行器类上写一个标签（`@NodeType("CHAT_NODE")`），引擎启动时扫描所有带这个标签的类，自动建一张**类型→执行器的映射表**。运行时引擎拿到 `flowType` 后，直接查表 O(1) 拿到对应的执行器。

这就像**餐厅里的菜单代码**——每种菜（节点类型）对应一个菜谱（执行器），新加一道菜只需要在菜谱类上贴个标签，厨师长（ModuleFactory）开机自动扫描，不需要改菜单系统的代码。

---

**Q10.5: flowType 有哪些？新增一个节点需要定义什么？**

**现有 50+ 种节点分 7 大类**：

```
对话与问答（核心）:
  CHAT_NODE（LLM 对话）    DATASET_SEARCH_NODE（知识库检索）
  QUESTION_INPUT（问题入口） ANSWER_NODE（指定回复）
  CLASSIFY_QUESTION（问题分类） CONTENT_EXTRACT（文本提取）
  HISTORY_NODE（聊天记录）   GRAPH_RAG_CHAT_NODE（图谱对话）

流程控制:
  tfSwitch（条件分支 if/else）  loopNode（循环容器）
  loopStart（循环起点）        loopBreak（循环中断）
  fileSwitch（文件类型分支）    fileFilter（文件筛选）

数据处理（插件/脚本）:
  pluginModule（文本加工/判断器）  pluginPython（Python 代码）
  pluginHttp（HTTP 插件）          textEditor（新版文本加工）
  scriptCode（脚本代码）           jsonNode（JSON 处理）
  customFeedback（自定义反馈）

外部连接:
  httpRequest（HTTP 请求）  mcpTool（MCP 工具）
  app（调用另一个画布应用）   agent（MCP Agent）

多模态:
  imgCompletion（图像理解）   audioCompletion（语音识别）
  videoCompletion（视频理解） imageGenerate（图片生成）
  voiceGenerate（语音合成）   videoKeyframesExtract（视频抽帧）

记忆与会话:
  termMemoryNode（长期记忆）  sessionAssignment（会话变量赋值）

安全与合规:
  keywordsReplacement（关键词替换）  sensitivityDetection（敏感词）
  standardCheck（规范检测）          complianceCheck（合规检测）
```

**新增一个节点只需要三步**：

```
第一步：在枚举里加一行
  FlowNodeTypeEnum 加一个枚举值，给它唯一的字符串标识
  → MY_NEW_NODE("myNewNode")

第二步：写一个执行器类，实现执行接口 + 贴标签
  @NodeType(MY_NEW_NODE)              ← 标签把"新节点类型"绑定到这个枚举
  public class MyNewNodeImpl implements ModuleService {
      execute(DispatchData data) {
          // 从 data 拿输入参数
          // 跑业务逻辑
          // return 一个 Map 作为输出，下游通过边取这里的 key
      }
  }

第三步：配置超时重试（可选）
  yml 里配 maxRuntime（最大执行秒数）+ retryTime（重试次数 ≤3）
  不配用默认：无超时、重试 1 次
```

**不需要做**：不改引擎内核（WorkFlowEngine）、不改 ModuleFactory（它启动时自动扫描新标签）、不改 FlowContext。这就是注解驱动 SPI 的扩展性——框架与实现解耦。

**三层解释**：

```
对产品经理：
  "我们有 50 多种节点，覆盖对话、检索、条件分支、HTTP、多模态、安全合规。
   加新节点像菜单加菜——起名字、写菜谱、贴标签，后厨开机自动识别。"

对技术面试官：
  "枚举定义类型，执行接口定义契约，@NodeType 注解绑定类型到实现。
   ModuleFactory 启动时扫描所有带标签的 Bean 建映射表，
   运行时 getService(type) O(1) 查表。新增节点三步，引擎零改动。"

对架构师：
  "注解驱动的 SPI 模式实现扩展点。局限是不支持热加载（映射表启动后只读），
   要支持动态注册需把 map 换 ConcurrentHashMap 并暴露 register 方法。
   executeWithTimeout 用接口默认方法统一超时重试模板，各节点可覆盖。"
```

---

**Q11: 可以不停服务热加载新的节点类型吗？**

当前不支持。原因：映射表是启动时一次性建的，之后不变。但改造方向明确——从配置中心/文件系统监听到新类 → 注入映射表。唯一的难点是 Spring 管理的 Bean（生命周期由容器控制）不能被热加载绕过，热加载的类需要单独管理。

---

**Q12: RAG 两路并行检索的场景，引擎怎么跑？**

```
用户问题 → LLM 节点(接收)
              ↓ 连线 → 两条分叉
        检索节点(向量)    检索节点(关键词)
              ↓                ↓
              └── 汇合线 ──────┘
                    ↓
              Rerank 节点
                    ↓
              LLM 节点(答题)
```

引擎看到这两条分叉之间没有互相依赖的连线 → 判断为"同层可并行" → 同时投给线程池。两个都跑完 → 下一层 Rerank 看到两个上游 input 都已到位 → 启动。Rerank 完成后递归到 LLM 答题节点。

整个过程不需要写一行"先等这个再等那个"的代码——引擎根据连线自动推理执行顺序。

---

## 二、多 Agent 自主推理与协作

### 简历原文
> 基于 Google ADK 实现 PlanReAct 自主推理，LLM 按"制定计划→执行工具→中间推理→最终答案"四阶段循环决策，失败后尝试自动重规划；设计根 Agent 与子 Agent 的协作架构，通过 State 字典实现跨 Agent 信息透传。实施混合路由策略：确定性业务场景走代码逻辑（消除特定场景幻觉），开放场景走 Prompt 语义分发。三层死循环防御。

---



### PlanReAct 自主推理

**Q1: PlanReAct 四阶段怎么跑的？**


用**转接电话**类比：用户打进总机（根 Agent），"帮我查下我的保单"。

```
Round 1:
  PLANNING: "先查保单信息，再分析理赔状态，最后告诉用户下一步怎么做"
  ACTION:   调用了 knowledge_search、data_query
  REASONING: "保单每年缴 5000，已缴 3 年，还需 2 年。用户可能想问理赔..."

Round 2:
  ACTION:   调 claim_status_check（查到用户有一笔在途理赔）
  REASONING: "在途理赔状态是审核中，用户还需要提供身份证照片"

FINAL_ANSWER:
  "先生，您的保单还需 2 年缴满，有一笔理赔正在审核中，麻烦补充身份证照片..."
```

**失败的自动重规划**：LLM 在 REASONING 阶段自己判断——"数据获取失败，这个信息应该用另一个工具查"→ 下一轮 PLANNING 调整计划。引擎不主动干预，LLM 看到错误后自己改方案。

---

**Q2: CustomPlanner 定制了什么？**

三件事：

1. **强制四阶段输出**：给 LLM 的 system prompt 里写死"必须先 PLANNING 再 ACTION 再 REASONING 最后 FINAL_ANSWER"，不能跳步
2. **分级展示**：PLANNING 部分（"我正在查你的保单信息..."）展示给用户——让用户知道自己在等什么。ACTION 和 REASONING 隐藏——避免泄露内部工具调用细节
3. **思考过程截断**：只保留 PLANNING，切掉 REASONING 后面可能泄露的业务判断逻辑

---

**Q3: 为什么选 Google ADK 不是 LangGraph？**

ADK 内置了"让 LLM 自己循环直到出最终答案"的能力——引擎自动管理什么时候调工具、什么时候生成答案。LangGraph 用状态图需要显式定义"节点 A → 节点 B → 回到节点 A"的循环边。

对 PlanReAct 这种"不一定走几步、每步走什么由 LLM 自己决定"的模式，ADK 的自动循环管理更自然。同时保留了 LangGraph 作为备选——换框架只需改一个参数。

---

### 多 Agent 协作架构

**Q4: 根 Agent 和 5 个子 Agent 怎么分工？**

用**公司总机**类比：

```
总机小姐(IntentAgent):
  "您好，请问有什么可以帮您？"
  
  "查表结构和字段名" → 转接 Metadata 部门
  "创建或修改策略"    → 转接 Strategy 部门
  "看策略效果数据"    → 转接 StrategyView 部门
  "做数据分析"       → 转接 DirectAnalysis 部门
  "查执行日志"       → 转接 RunData 部门
  
  每个部门有自己独立的:
  - 工作手册（system prompt）—— 知道该怎么做
  - 工具箱（tools）—— 有自己的专用工具
  - 甚至自己的下属部门（sub_agents）
```

总机不自己做业务——她只判断"该谁来"，然后把电话转过去。部门独立处理完后，把结果告诉总机，总机再回复你。

---

**Q5: 转接怎么发生的？"transfer_to_agent" 具体怎么工作？**

```
你自己: "帮我查下我的保单"
          ↓
总机(根 Agent): LLM 判断 → "这是策略查询的事"
                → 输出路由指令："转接策略查看部门"
          ↓
引擎收到指令:
  ① 保存总机当前的通话记录（保留上下文）
  ② 给策略部门接通一个新线路（新建独立上下文，只把你的原始问题带过去）
  ③ 策略部门独立处理——查自己的工具、跑自己的推理
  ④ 处理完 → 把结果打包通过内线传回总机
  ⑤ 总机恢复通话 → 拿到结果 → 组织语言 → 回复你
```

整个过程的关键设计：策略部门内部的工具调用和推理过程**不会污染**总机的通话记录。总机只看到"转接→拿到结果"，看不到策略部门内部的几步操作。

---

**Q6: State 字典怎么跨 Agent 透传？**

所有 Agent 共享一个**全局信息板**（State 字典）。根 Agent 在开始处理前，把请求参数（org_id、用户身份、会话 key 等）贴到信息板上。任何子 Agent 在处理过程中，随时可以看这块信息板。

对比直接传参：传参需要每个 Agent 的接口都声明"我需要 org_id、user_id、..."——5 个 Agent 各写一遍。全局信息板一次贴、所有人看，不需要声明。

---

**Q6.5: 完整例子——"查看当前有哪些策略"的主子 Agent 状态流转**

源码位置标注在每一步。一个请求从进入到 SSE 推送的完整链路。

```
Step 1: 请求进入 Python 服务器
───────────────────────────────────────
源码: agent/apps/multi_agent/api.py

POST /api/multi-agent/chat
  Body: {"question": "查看当前有哪些策略", "chatId": "abc123"}
  → state_delta = {"request_params": {...}, "metadata": {...}}
  → RunConfig(max_llm_calls=20)
  → 创建 IntentAgent 的 Runner


Step 2: 组装 IntentAgent 的 System Prompt
───────────────────────────────────────
源码: executor.py, ADKExecutor.get_prompt()

拼给 LLM 的 System Prompt 有 5 层:
  ① IntentAgent 的角色设定（promptInfo）
  ② 5 个子 Agent 的描述 + 各自的 tools
  ③ 路由规则（intent_agent/prompt.py）:
       "策略查看 → strategy_view_agent，关键词：查看、显示、列出"
       "决策优先级：社交互动 > 元数据 > 策略查看 > 用户分析 > 通用策略"
  ④ PlanReAct 指令："先 PLANNING → ACTION → REASONING → FINAL_ANSWER"
  ⑤ 背景知识 + 长期记忆（如果有）


Step 3: IntentAgent 的 LLM 判断意图
───────────────────────────────────────

LLM 看到的用户消息: "查看当前有哪些策略"
LLM 看到的系统提示: "策略查看 → strategy_view_agent，关键词：查看"

LLM 输出:
  /*ACTION*/
  transfer_to_agent({agent: "strategy_view_agent"})


Step 4: ADK Runner 创建子 Agent 的独立上下文
───────────────────────────────────────
源码: Google ADK Runner 内部（框架代码）

Runner 检测到 function_call.name == "transfer_to_agent":
  → ① 冻结 IntentAgent 的当前对话（保留，不传给子 Agent）
  → ② 找到 strategy_view_agent 的定义:
        instruction: "你是策略效果查看专家..."
        tools: [list_strategies, get_strategy_detail]
  → ③ 创建 strategy_view_agent 的独立 Contents:
        只带原始用户消息: "查看当前有哪些策略"
        State 字典自动透传（request_params/metadata 可读）


Step 5: strategy_view_agent 独立执行 PlanReAct
───────────────────────────────────────

Round 1:
  PLANNING: "先列出所有运行中的策略"
  ACTION: list_strategies({})
  → 工具返回: [{name:"策略A", status:"运行中", conversion:"12%"},
              {name:"策略B", status:"已暂停", conversion:"9%"},
              {name:"策略C", status:"运行中", conversion:"15%"}]
  REASONING: "共 3 个策略，A 和 C 运行中，B 已暂停"

FINAL_ANSWER: "当前共有 3 个策略：策略A(运行中,12%)、B(已暂停,9%)、C(运行中,15%)"


Step 6: 结果返回给 IntentAgent
───────────────────────────────────────

Runner 把子 Agent 的 FINAL_ANSWER 包装成 function_response:
  function_call: transfer_to_agent({agent: "strategy_view_agent"})  
  function_response: "当前共有 3 个策略..."  ← 子 Agent 的最终答案

IntentAgent 的上下文追加这两条:
  model: transfer_to_agent({agent: "strategy_view_agent"})  ← 调了哪个子 Agent
  user:  function_response("当前共有 3 个策略...")           ← 结果是什么

关键设计: IntentAgent 只看到"调了谁 + 返回了什么"，
看不到子 Agent 内部 PLANNING/ACTION/REASONING/工具调用的细节


Step 7: IntentAgent 生成最终回答 + SSE
───────────────────────────────────────
源码: executor.py

IntentAgent REASONING: "子 Agent 返回了策略列表，我直接告诉用户"
FINAL_ANSWER: "当前共有 3 个策略：..."

SSE 推给前端: 用户看到"正在查询策略列表..." → "3 个策略：..."
```


State 流转总结:

```
请求入口         IntentAgent              strategy_view_agent     IntentAgent(回复)
  │                  │                          │                     │
  │ state_delta ────→│ 读取 + 推理               │                     │
  │ (request_params)  │                          │                     │
  │                   │ transfer_to_agent ──────→│ 独立推理             │
  │                   │ (只传原始 user 消息)      │ 读取 state          │
  │                   │                          │ PlanReAct 循环       │
  │                   │                          │ 调 list_strategies   │
  │                   │           FINAL_ANSWER ←─│                     │
  │                   │ ← function_response ──── │                     │
  │                   │ REASONING → FINAL_ANSWER │                     │
  │                   │ ── SSE ─────────────────────────────────────→  前端
  │                   │                          │                     │
```

---

### 混合路由策略

**Q7: 确定性场景 vs 开放场景怎么分界？**

```
"查看当前有哪些策略" → 代码直接调用策略列表 API → 结果渲染
  → 确定性场景：不需要 LLM 理解，不需要推理，直接取

"分析策略A的转化率变化并给出优化建议" → LLM 判断意图 → 路由到对应部门
  → 开放场景：需要理解、需要拆步、需要多部门协作
```

**为什么确定性场景不用 LLM**：LLM 可能"编造一个不存在的策略名"。代码调用 API 得到的是确定的列表，零幻觉。混合路由就是把这两种场景分开——能确定的不走 LLM，需要推理的才走。

---

### 三层死循环防御

**Q8: 三层各卡在哪个环节？**

| 层级 | 怎么防 | 什么时候触发 |
|:---|:---|:---|
| 单节点超时 | 每个节点设最大执行时间，超时自动终止 | 秒级，运行中最先触发 |
| LLM 调用上限 | 一局对话最多调 20 次 LLM，到 20 次强制终止 | 分钟级，运行中兜底 |
| 循环引用检测 | 部署前静态分析 Agent 之间的调用链，发现环拒绝上线 | 发布时，预防性 |

如果 LLM 第 19 次还没出最终答案：第 20 次时引擎强制终止 → 把截至当前的所有推理结果拼成兜底答案返回（不算成功，但不会无限烧 token）。

---

## 三、上下文管理与记忆系统

### 简历原文
> Token 三级管控；在线/离线双引擎；短期记忆 Redis Session + 长期记忆渐进式摘要；会话变量跨对话持久化。

---

### Token 三级管控

**Q1: 每级什么时候触发？**

```
开门红检查（第 0 级）:
  消息总字符数 < 模型上限的一半 → 直接放行，不计算
  → 大部分短对话都走这条快车道

精确账房（第 1 级）:
  超过半上限 → 用精确 token 计数器（JTokkit）逐条算
  → 超了就从最早的开始裁，裁一条 user 消息同时裁掉配对的 AI 回复
  → 保证不会出现"孤立的 AI 回答没有对应问题"

请秘书总结（第 2 级）:
  裁完还是超 → 调一个小 LLM 生成语义摘要（代替原始历史）
  → 摘要后如果还是超 → 直接抛异常拒答
```

---

**Q2: 短期记忆为什么用 Redis Session 不用 DB？**

Redis 一个 `LRANGE` 命令把当前会话所有消息全量取回——一个网络往返。DB 需要 `SELECT ... ORDER BY create_time LIMIT N`——每次 SQL 往返 + 磁盘 I/O。快 5-10 倍。同时异步写 DB 做持久化兜底（Redis 重启不丢数据）。

---

**Q3: 长期记忆的摘要为什么不记答案？**

如果摘要记载"产品 A 库存 150 件"，但知识库当天更新为"产品 A 已下架"，LLM 看到两份冲突信息，不知道该信谁。

摘要只记**话题和行为**："用户曾咨询产品A的库存"。检索结果提供**最新事实**。两路信息源头不同、不冲突——摘要负责"用户关心什么"，检索负责"当前正确答案"。

---

**Q4: 会话变量怎么跨对话持久化？**

节点执行后，把一些关键信息（用户所在地区、当前选择的产品、身份角色）写入一个**全局共享的变量池**。这个池子存入 Redis Session。下次同一用户打开新对话、或同一个会话的不同轮次，引擎从池子里恢复这些变量，后续节点直接引用。

典型场景：用户在对话里选了"北京地区"→ 这个选择写入变量池 → 之后所有查询都默认带"北京地区"过滤，不需要用户每次重说。

---

### 在线/离线双引擎

**Q5: 为什么不用同一个引擎跑所有任务？**

```
用户对话（毫秒级）  vs  文档 ETL（分钟级）
     ↓                      ↓
  Workflow 引擎          DataFlow 引擎
  (在线, DAG)           (离线, Argo+K8s)
     ↓                      ↓
         用 DB 做解耦中介:
     AgentFlow 写任务记录
     → DataFlow 定时轮询取任务
     → 完成后回写任务状态
```

三个原因：

- **SLA 不同**：在线对话需要毫秒级返回，离线 ETL 可能需要分钟级处理（大文件解析），混在同一个引擎里在线会被离线拖慢
- **资源隔离**：在线线程池不能被离线大文件解析占满
- **复杂度分层**：DataFlow 集成 K8s 编排大任务，Workflow 不需要这层复杂性

---

### 超长上下文与幻觉

**Q6: 压缩过程中丢失工具调用历史怎么办？**

裁剪以"用户发了一条消息"为边界——不会把"用户提问 → LLM 调工具 → 工具返回结果 → LLM 回答"这一整轮对话拆散。裁掉整轮旧对话时，这一轮里的工具调用和结果也一起裁掉——不会出现孤立的工具调用残留。

摘要的 Prompt 里也明确要求"保留关键事实和工具调用结果"。

---

**Q7: 出现幻觉怎么追溯？**

每次 LLM 调用都被全量记录到 **Langfuse**（一个开源的 LLM 调用追踪平台）——输入 token 数、输出 token 数、花了多少钱、LLM 看到了什么检索结果、生成了什么。

用户投诉"答错了"→ 进 Langfuse 搜那次对话的 ID → 回放 LLM 当时看到的完整上下文（检索结果 + 历史消息 + system prompt）→ 定位是检索结果误导了 LLM（检索到错误文档），还是 LLM 自己瞎编的（检索结果里明明没有，但 LLM 编了）。前者修检索，后者调 temperature 或加限制 Prompt。

同时检索结果自带**来源标记**（quoteMark），LLM 引用某个 chunk 时必须注明来源。如果引用了一个找不到对应 chunk 的内容——是幻觉，需要强化引用约束。<｜end▁of▁thinking｜>

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="Write">
<｜｜DSML｜｜parameter name="content" string="true"># PowerAgent 简历亮点 —— 面试问题与回答

> 仅场景化描述。不贴源码，解释类/机制的作用。


# PowerAgent 简历面试 Q&A 补充（0804）

> 以下为 8月4日讨论的补充内容。✓ = 已在本文档前面章节覆盖，★ = 本文档前面章节未覆盖的新内容。

---

## 一、DAG 引擎补充

### ✓ Q5 修正：中间节点失败的三情况分析
已在本文档 Q5 — 独立分支/直接依赖（三条出路）/间接依赖（级联阻塞）

### ✓ Q9 修正：执行器 Bean vs 节点实例
已在本文档 Q9 — 1 类型 = 1 单例 Bean，50 个画布节点共享 50 个执行器，不是 50×50

### ★ 补充 Q9：100 个画布同时用同一个执行器，怎么并发安全？（本文档 Q9 未覆盖的深度追问）

**单例 ≠ 串行**。100 个画布同时执行 CHAT_NODE，100 个线程同时调同一个执行器实例，没问题。关键在**无状态设计**。

```
执行器 (单例, 无状态):
  → 只含注入的依赖（LLMService、Mapper 等）
  → 不持任何可变的实例字段
  → 每次调用 execute(data) 时所有中间状态存方法局部变量

FlowContext (每次请求 1 个):
  → 全局变量池、switchMap、chatResponse
  → 100 个画布 = 100 个独立的 FlowContext
```

**职责分离**：执行器负责"怎么做"，状态数据放在"这次执行谁的东西"里。这是无状态单例并发安全的核心。

**反例陷阱**：如果某个执行器里写了 `private int callCount`（可变实例字段），100 线程同时 ++ → 竞态。正确做法是用 ThreadLocal 或 ConcurrentHashMap。

### ✓ Q10.5：flowType 有哪些、新增节点定义什么
已写入 — 7 大类 50+ 种 + 三步定义 + 三层解释

---

## 二、多 Agent 补充

### ✓ Q6.5：完整例子"查看当前有哪些策略"
已在本文档 Q6.5 — 7 步链路 + State 流转 ASCII 图

### ★ 子 Agent 是怎么调起的？function_call 的三种用途

`function_call` 是 LLM 调任何工具的通用方式，不是专门调子 Agent 的：

```
① 调外部工具（API/数据库）
   function_call(list_strategies, {})
   function_call(data_analysis, {metric: "conversion_rate"})
   → 子 Agent 内部自己调自己的工具

② 调子 Agent（控制权转移）
   function_call(transfer_to_agent, {agent: "strategy_view_agent"})
   → 唯一"把任务派给另一个 Agent"的方式

③ 并行调多工具（parallel function calling）
   Round N: function_call(A) + function_call(B)
   → 同一轮同时发两个，互不依赖
```

子 Agent 在 IntentAgent 的 tools 列表里注册为 `transfer_to_agent`——跟普通 tool 一样声明，LLM 自己决定何时调用。Runner 检测到 `transfer_to_agent` 时走专门路径（创建独立上下文、转移控制权、回收），不走普通 tool execute。

### ★"基于 Google ADK 实现 PlanReAct 自主推理"具体是什么

五个具体行为，不是"用了 ADK 这个库"一句话：

| 你写的 | ADK 替你做的 |
|:---|:---|
| CustomPlanner 三处定制指令文本 | Runner 的循环管理、工具注册调用、四阶段输出解析 |
| RunConfig(max_llm_calls=20) | 循环计数、超限强制终止 |
| tools 列表里注册 transfer_to_agent | 子 Agent 上下文隔离、结果回传 |
| ADKExecutor 里组装 Prompt 和 event | LiteLlm 做模型适配，ADK 格式 → OpenAI 格式 |
| before_model_callback 裁剪历史 | 每轮 LLM 调用前后的回调钩子 |

### ★ State 字典详解

**字段结构**（入口 api.py 唯一一次写入）：

```
state_delta = {
    "request_params": {
        "user_id": "u1",
        "chat_id": "abc123",
        "metadata": {
            "label": "strategy_view",  // 关键：显式分支判断
            "org_code": "BJ",
        }
    },
    "metadata": req.metadata,         // reqId、source、timestamp
    HEADER_NAME_ORG_CODE_OUT: org_code  // 租户码 → LLM HTTP Header
}
```

**子 Agent 使用阶段**：
- `before_agent_callback`：读取 label 判断显式分支、读取 org_code 限制工具范围
- 工具函数内部：按 org_code 过滤数据
- PLANNING/REASONING/FINAL_ANSWER 阶段不读 State

**是否可以修改**：技术上可读写，但当前项目**所有子 Agent 只读**。入口 api.py 写一次，之后全局只读。跨 Agent 数据传递通过 return value 的 function_response，不通过 State 字典。

### ★ 主 Agent 给子 Agent 的分发是并行还是串行

**串行**。PlanReAct 循环每轮只有一个 ACTION → Runner 执行 → 结果回来 → LLM 看到结果再输出 next action。子 Agent 的 `transfer_to_agent` 是 ACTION 的一种——Runner 必须等它完整跑完，拿到 FINAL_ANSWER，才能继续主 Agent 的下一轮。

### ★ 为什么不能像 Claude Code 一样并行分发多 Agent

三个根本矛盾：

| 矛盾 | 解释 |
|:---|:---|
| **控制权模型** | ADK 每 session 只有一个 Runner，transfer_to_agent 是"暂停-接管-交还"，不是"投递-回调"。并行需要每个子 Agent 独立 session + 独立 Runner |
| **State 并发写** | 多个子 Agent 同时写 State 字典 → 数据竞争。Parallel tool call 有独立沙箱，子 Agent 没有 |
| **LLM 推理依赖** | PlanReAct 依赖"看到结果再决定下一步"。并行分发时 LLM 不知道 B 该用什么参数。A 返回后 LLM 可能误判"够了"跳过 B |

**真正可行的并行**是**同一个子 Agent 内部并行调多个工具**（parallel function calling）——同一轮输出多个 function_call 给不同 tool，互不依赖。ADK 原生支持，不需要改架构。

---

## 三、FlowContext 详解

**文件**: `workflow/domain/po/FlowContext.java`，139 行，19 个字段分 5 类：

### 会话标识
| 字段 | 用途 |
|:---|:---|
| `chatId` | 本次对话的会话 ID |
| `chatItemId` | 本次消息 ID |

### 执行控制
| 字段 | 用途 |
|:---|:---|
| `switchMap` | **去重表**，防同一 input 被重复赋值 |
| `runStatus` | 是否仍在运行，异常时设为 FALSE |
| `errorMessage` | 最后异常信息 |
| `loopEndCondition` | 循环节点终止条件 |

### 节点拓扑
| 字段 | 用途 |
|:---|:---|
| `moduleItemTypeMap` | 节点 ID → 节点对象的映射表 |

### 数据累积
| 字段 | 用途 |
|:---|:---|
| `chatAnswerText` | 回答文本拼接池 |
| `chatResponse` | 结构化结果列表（前端画布展示） |
| `structOutput` | 结构化 JSON 输出 |
| `additionalInfo` | Agent 附加业务信息 |

### 全局状态 & 资源
| 字段 | 用途 |
|:---|:---|
| `globalVariables` | **全局变量池** (ConcurrentHashMap) |
| `executorService` | 并发模式线程池 |
| `completionService` | 并发模式邮件收发室 |
| `runningTime` | 总执行时间 |

### Token 统计（仅引擎内部）
`totalTokens` / `inputTokens` / `outputTokens`，私有不参与序列化。

**生命周期**：每次请求 new → load 节点 → init 线程池 → 执行 → tryDestroy → 随请求结束释放。

---

## 四、AF-RAG-Server 补充

### ★ SSE 流式与心跳

| 问题 | 怎么解决的 | 机制 |
|:---|:---|:---|
| LLM 流式输出怎么实时推送 | Agent 每生成一段 put 进队列 | asyncio.Queue + SSE |
| Agent 生成慢长连接被误杀 | 独立 Task 每 10s 塞空帧 | heartbeat |
| Agent 卡住心跳也被卡死 | 两个生产者 Task 独立并行 | 职责隔离 |
| 请求不合法浪费 LLM 调用 | Pydantic 入口先校验 | 参数校验前置 |
| 排障怎么追踪全链路 | reqId 贯穿 Langfuse span 树 | trace middleware |
| 调用方传的 reqId 格式错 | 校验 hex，不合法自动生成 | reqId 校验 |
| MCP 和多 Agent 路由混一起 | 两个 include_router 分开 | 双路由挂载 |

### asyncio.Queue 生产者-消费者

两个生产者 Task（Agent + heartbeat）写队列，一个消费者 Task 读并 yield。None = 结束信号 → break + 取消心跳。

### 面试追问
- 心跳帧 `data: \n\n` 是 SSE 标准，浏览器自动忽略不污染消息边界
- 队列默认无界，可设 `maxsize=N` 做背压
- 客户端断开 → yield 抛异常 → 捕获停 Agent。主动"用户点停止"需要额外取消接口
