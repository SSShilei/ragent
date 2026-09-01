# PowerAgent 简历 Q&A（0820 补充）

> 2026-08-20 补充：tool_calls 响应结构 + 长期记忆内容设计。
> 与 `poweragent-resume-qa-0804.md` 互补，本文件聚焦 LLM 工具调用协议和记忆系统设计。

---

<a id="q1"></a>
## Q1: 详细解释 tool_calls 响应结构

### 一次llm调用完整结构


```json
{
  "id": "call_1",
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "我需要先查找2024年奥运会的主办城市，然后再查询该城市的人口。",
        "tool_calls": [   // 如果是原生函数调用格式，这里会有具体结构；此处我们用自定义JSON
          {
            "type": "function",
            "function": {
              "name": "search",
              "arguments": "{\"query\": \"2024年奥运会主办城市\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": { "prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150 }
}
```

### 逐字段详解

#### ① `id`: 响应 ID

```
"id": "call_1"
```

本次 API 响应的唯一标识，用于日志追踪和排查（对应 Langfuse 追踪的 requestId/trace 概念）。

#### ② `choices`: 候选数组

```
"choices": [ { ... } ]
```

Chat Completions 的候选列表。默认 `n=1` 时只有一个。每个 choice 是一次"生成的回答"，包含 `message` 和 `finish_reason`。

#### ③ `message`: assistant 的消息内容（核心）

```
"message": {
  "role": "assistant",      ← 角色：assistant
  "content": "我需要先查找...",   ← 文本内容（思考说明）
  "tool_calls": [ ... ]      ← 工具调用
}
```

**`content`: 文本内容**——LLM 在调工具前，先用自然语言说明"我要做什么"，相当于 ReAct 里的 REASONING/PLANNING 部分。

**关键点**：content 和 tool_calls 可以共存——LLM 先说一句"我要查奥运会主办城市"，然后调 search 工具。两个字段是独立的。

**`tool_calls`: 工具调用数组（LLM 的"决定"）**

```
"tool_calls": [
  {
    "type": "function",           ← 类型：函数调用
    "function": {
      "name": "search",           ← 工具名
      "arguments": "{...}"        ← 参数（JSON 字符串！）
    }
  }
]
```

| 字段 | 含义 | 关键点 |
|---|---|---|
| `type` | "function" | 固定值，目前只有 function 类型 |
| `function.name` | 工具名 | LLM 从你提供的 tools 列表里选出来的 |
| `function.arguments` | 参数 | **JSON 字符串，不是对象**，需要解析 |

**⚠️ 最容易踩的坑**：`arguments` 是**字符串**，不是 JSON 对象。要 `JSON.parse(arguments)` 才能拿到真正的参数对象。

```
"arguments": "{\"query\": \"2024年奥运会主办城市\"}"
              ↑ 这是字符串，需要 JSON.parse 转成:
{"query": "2024年奥运会主办城市"}
```

#### ④ `finish_reason`: 为什么结束（循环控制的关键）

```
"finish_reason": "tool_calls"
```

**这是整个响应对 Agent 循环最重要的一行**。它告诉服务端"这一轮 LLM 为什么要停"：

| finish_reason | 含义 | 服务端行为 |
|---|---|---|
| `stop` | LLM 正常回答完 | **循环结束**，把 content 返回给用户 |
| `tool_calls` | LLM 要调工具 | **继续循环**，执行工具，结果回填，再请求 LLM |
| `length` | 达到 max_tokens | 截断，可能不完整 |
| `content_filter` | 被内容审核拦截 | 处理违规 |

**Agent 循环的判断逻辑**：

```python
if resp.choices[0].finish_reason == "tool_calls":
    # 有工具调用 → 执行工具 → 结果回填 → 下一轮
    for tc in resp.choices[0].message.tool_calls:
        result = execute_tool(tc.function.name, json.loads(tc.function.arguments))
        messages.append({"role": "tool", "tool_call_id": tc.id, "content": result})
    continue  # 回到循环
else:
    # finish_reason == "stop" → LLM 回答完了 → 返回
    return resp.choices[0].message.content
```

#### ⑤ `usage`: token 统计

```
"usage": {
  "prompt_tokens": 120,        ← 输入 token
  "completion_tokens": 30,     ← 输出 token
  "total_tokens": 150          ← 总数
}
```

对应 token 成本归因——`prompt_tokens × 输入单价 + completion_tokens × 输出单价`。

**注意**：这里的 token 只是"这一轮"的。Agent 循环里每轮都返回一个 usage，总成本 = 所有轮次 usage 累加。

### 这个响应在 Agent 循环里怎么被消费

```
Round 1: LLM 返回上述响应
  ├─ finish_reason = "tool_calls" → 继续循环
  ├─ 解析 tool_calls[0]:
  │    name = "search"
  │    args = JSON.parse("{\"query\": \"2024年奥运会主办城市\"}")
  ├─ 执行 search({query: "2024年奥运会主办城市"}) → 返回 "巴黎"
  ├─ 结果回填 messages:
  │    {"role": "tool", "tool_call_id": "call_1", "content": "巴黎"}
  └─ 下一轮 LLM（带着工具结果）→ LLM 决定下一步

Round 2: LLM 看到"巴黎"，继续调 search({query: "巴黎人口"})
  └─ ...直到 finish_reason = "stop"，LLM 输出最终答案
```

### 流式版的区别

非流式是"一次返回完整 JSON"。流式下，结构被拆成多个 delta chunk：

```
chunk 1: {"delta": {"content": "我需要先查找..."}}
chunk 2: {"delta": {"tool_calls": [{"index": 0, "id": "call_1", "function": {"name": "search"}}]}}
chunk 3: {"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "{\"que"}}]}}
chunk 4: {"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "ry\": \"...\"}"}}]}}
chunk 5: {"finish_reason": "tool_calls"}
```

**这就是 ToolCallsStreamParser 要解决的问题**——`arguments` 被拆成多段，要跨 chunk 拼装成完整 JSON 再解析（全缓冲策略）。

### 面试话术

> tool_calls 响应是 LLM 决定调工具的信号。核心字段是 `message.tool_calls`（工具名 + 参数）和 `finish_reason`（为什么结束）。`finish_reason = "tool_calls"` 是 Agent 循环继续的信号，`"stop"` 是结束信号。
>
> 两个易踩的坑：一是 `arguments` 是 JSON 字符串不是对象，要 JSON.parse；二是流式下 arguments 被拆成多个 chunk，要跨 chunk 拼装（全缓冲策略）。
>
> token 成本从 `usage` 字段拿，但这是单轮的——Agent 循环的总成本是所有轮次 usage 累加。

---

<a id="q2"></a>
## Q2: 长期记忆具体存什么内容？（topic? QA? 摘要?）

### 答案：分项目、分场景，三者都可能

| 类型 | 存什么 | 谁在用 | 为什么不存别的 |
|---|---|---|---|
| **摘要（ragent）** | 话题 + 状态 + 约束 | ragent | 不记答案防冲突，FAQ 场景旧答案会过时 |
| **fact（mem0/PowerAgent）** | 用户偏好/画像 | PowerAgent | 个性化场景要记住"用户是谁"，不是"聊了什么" |
| **QA（完整问答对）** | 问题 + 答案 | 一般不直接存 | 答案会过时、占空间、和检索结果冲突 |

### ragent 的长期记忆：摘要存什么

ragent 的长期记忆 = `t_conversation_summary` 表里的**渐进式摘要**，内容由 `conversation-summary.st` 模板约束。

**实际内容结构（3 个维度）**：

```
<conversation-summary>
用户咨询了【话题1】（已解答）、【话题2】（当时无记录）。
约束：时间范围、地点、预算等用户明确约束。
关键词：人事政策, 假期
</conversation-summary>
```

| 维度 | 存什么 | 例子 |
|---|---|---|
| **话题** | 用户问过什么主题 | "年假计算规则"、"病假政策" |
| **状态** | 每个话题的处理状态 | 已解答 / 当时无记录 / 部分解答 / 待确认 |
| **约束** | 用户明确的条件限制 | 时间范围、地点、预算 |

**为什么"不记答案"（关键设计）**：

```
如果摘要记"产品 A 库存 150 件"，但知识库当天更新为"产品 A 已下架"
→ LLM 看到两份冲突信息，不知道该信谁

摘要只记"用户曾咨询产品A的库存"（话题）
检索结果提供"当前正确答案"（最新事实）
→ 两路信息源头不同、不冲突
→ 摘要负责"用户关心什么"，检索负责"当前正确答案"
```

**这是 FAQ 场景的记忆设计精髓**：长期记忆不存"答案"（答案会过时、会和检索冲突），只存"用户关心什么话题、问到什么程度、有什么约束"。

### PowerAgent 的长期记忆：mem0 存什么

PowerAgent 用 mem0（ES 向量库），存的是**个性化 fact**，不是摘要：

```
mem0 存的内容（记忆条目）:
  "用户偏好电池续航>5000mAh"       ← 用户偏好
  "用户上周查过产品A库存"           ← 历史行为
  "用户所在地区是北京"              ← 用户画像
```

**和 ragent 的本质区别**：

| | ragent 摘要 | PowerAgent mem0 |
|---|---|---|
| 内容 | 话题 + 状态 + 约束 | 个性化 fact（偏好/画像） |
| 形式 | 一段压缩文本 | 多条独立向量条目 |
| 检索 | 不检索，直接读最新摘要 | 向量相似度召回 |
| 适用 | FAQ（避免旧答案冲突） | 个性化（记住用户偏好） |

### QA 为什么不直接存

完整 QA 对（问题+答案）占了大量 token、答案会过时、和检索结果冲突。所以要么压缩成摘要（ragent），要么提炼成 fact（mem0），都不存原始 QA。

### 记忆内容的三层设计（完整图）

```
┌─ 短期记忆（会话内）─────────────────────────┐
│  对话原文（最近 8 轮）                       │
│  存 Redis Session / MySQL t_message         │
└────────────────────────────────────────────┘
┌─ 长期记忆（跨会话）─────────────────────────┐
│  ragent: 话题+状态+约束（渐进式摘要）         │
│  mem0:   用户偏好/画像（向量 fact）          │
└────────────────────────────────────────────┘
┌─ 元记忆（关于用户/项目）────────────────────┐
│  Claude Code: user/feedback/project/reference │
│  存"为什么这么做"、"用户是谁"                │
└────────────────────────────────────────────┘
```

### 面试话术

> 长期记忆存什么取决于场景。ragent 存的是**话题 + 状态 + 约束**的摘要——不记答案，因为 FAQ 场景答案会过时、会和检索结果冲突。摘要负责"用户关心什么话题、问到什么程度、有什么约束"，检索负责"当前正确答案"，两路信息源头不同不冲突。
>
> PowerAgent 用 mem0 存的是**个性化 fact**（用户偏好、画像），因为它的场景是要记住"用户是谁"而不是"聊了什么"。
>
> 一般不直接存完整 QA 对——答案会过时、占空间、和检索冲突。要么压缩成摘要，要么提炼成 fact。

---

<a id="q3"></a>
## Q3: 断点续传的问题？哪些场景怎么解决的？

### 先分场景：哪几种"断"法

断点续传不是一种问题，是**四类场景**，每类解决方案不同：

| 场景 | 断在哪里 | 危害 | 解决方案 |
|---|---|---|---|
| ① SSE 流式中断 | 对话生成中实例挂了/网络断 | 用户看到一半，回答丢失 | 前端重连 + Redis checkpoint |
| ② Agent ReAct 循环中断 | 第 N 轮挂了 | 前面 N 轮白跑，只能重来 | 每轮 checkpoint 存 Redis |
| ③ 文档入库 Pipeline 中断 | docParse 完，textChunk 挂 | 从零重跑，重复调 MinerU | 节点级断点 + 幂等重试 |
| ④ Workflow 引擎中断 | FlowContext 在内存 | 中间状态全丢（最大缺陷） | FlowContext 序列化到 Redis |

### 场景 ①：SSE 流式中断 → 前端重连 + 已累积内容保存

**现状（ragent 已有部分）**：

```
用户点停止生成 / 实例挂
  → StreamTaskManager.cancel()
  → onCancelSupplier 回调: 把已生成的内容 append 到对话记录（不丢已答部分）
  → SSE 发 CANCEL + DONE
```

**缺口**：不能"从断点继续生成"——保存的是"到此为止"，不是"从这继续"。

**解决方案（层 1：Pipeline checkpoint）**：

```java
// StreamChatContext 每阶段存 checkpoint 到 Redis
public void saveCheckpoint(String stage, Object payload) {
    String key = "ragent:pipeline:checkpoint:" + taskId;
    stringRedisTemplate.opsForValue().set(key, json, Duration.ofMinutes(5));
}

// 恢复: 实例 B 读 checkpoint，跳到对应阶段继续
public void execute(StreamChatContext ctx) {
    Checkpoint cp = ctx.loadCheckpoint();
    if (cp != null) {
        ctx.restore(cp);
        resumeFrom(ctx, cp.getStage());  // 从断点继续，跳过已完成步骤
        return;
    }
    // 正常 7 步
}
```

**效果**：实例 A 第 6 步检索完挂 → 前端重连带 taskId → 实例 B 读 Redis checkpoint → 发现已到 `after_retrieve` → 直接第 7 步 Prompt 组装 + LLM 生成，前 6 步全跳过。

### 场景 ②：Agent ReAct 循环中断 → 每轮 checkpoint

**现状（PowerAgent ADK）**：Session 有 Redis（对话历史），但**无 checkpoint**——Agent 循环到第 5 轮实例挂了，前面 4 轮没快照，只能重来。

**解决方案**：

```python
# 每轮 LLM 调用后，把中间状态存 Redis
def save_checkpoint(task_id, round, messages):
    redis.set(f"agent:checkpoint:{task_id}", json.dumps({
        "round": round,
        "messages": messages,   # 当前对话状态（含 tool_call + tool_result）
    }), ex=300)

# 恢复时从断点继续
def resume(task_id):
    cp = redis.get(f"agent:checkpoint:{task_id}")
    if cp:
        # 跳过前面 N 轮，从 cp["round"] 继续
        ...
```

**关键**：checkpoint 存的是"执行状态"（第几轮、当前 messages），不是"对话历史"（Session）。两者不同——Session 恢复上下文，checkpoint 恢复执行进度。

### 场景 ③：文档入库 Pipeline 中断 → 节点级断点 + 幂等重试

**现状（ragent IngestionEngine）**：
- MQ 消息未 ACK → RocketMQ 重投给另一消费者（at-least-once）
- 但重投后**从零重新执行**（分块+向量化+入库），不是从断点继续
- `persistChunksAndVectorsAtomically` 事务保证"全有或全无"，半途挂事务回滚

**解决方案（层 2：断点续跑）**：

```java
// IngestionEngine.executeChain 改造
private void executeChain(...) {
    String currentNodeId = context.getLastCompletedNodeId() != null
        ? nodeConfigMap.get(context.getLastCompletedNodeId()).getNextNodeId()  // 从断点继续
        : findStartNode(nodeConfigMap);

    while (currentNodeId != null) {
        context.setCurrentNodeId(currentNodeId);
        saveContextSnapshot(context);      // 执行前持久化"正要执行谁"
        NodeResult result = executeNode(context, config);
        context.setLastCompletedNodeId(currentNodeId);
        saveContextSnapshot(context);      // 完成后标记
        currentNodeId = config.getNextNodeId();
    }
}
```

**效果**：`docParse(成功) → textChunk(成功) → summary(执行中挂)` → 恢复后查到 `lastCompletedNodeId=textChunk` → 从 `summary` 继续，不重复跑前两个节点。

**配合 MinerU 缓存**：即使从零重跑，同一文件重分块命中 SHA-256 缓存跳过 MinerU API（首次 7s → 缓存 <1s），大幅降低重跑成本。

### 场景 ④：Workflow 引擎中断 → FlowContext 序列化

**现状（PowerAgent agentflow WorkFlowEngine）**：

```java
// FlowContext 是整个 Workflow 的内存对象
// 进程崩溃 → 所有中间状态丢失
// 文档明确承认: "Workflow 级别没有 checkpoint"（最大缺陷）
```

**解决方案**：

```java
// 每执行完一个节点，把 FlowContext 序列化到 Redis
// 进程恢复后从最近的 checkpoint 继续
FlowContext snapshot = flowContext.toSerializable();
redis.set("workflow:checkpoint:" + workflowId, objectMapper.writeValueAsString(snapshot));

// 折中: 只对非对话场景（API 调用 Workflow）开启
// 对话场景有序列化开销，权衡后不开启
```

### 通用原则：三层防护

```
① 持久化（状态落 Redis/DB）—— 中断可恢复的前提
② 幂等（重试安全）—— 重跑不产生脏数据
③ 缓存（重跑成本低）—— 即使重跑也快（MinerU 缓存）

没有①，只能重来；没有②，重来会重复；没有③，重来很贵
```

### 面试话术

> 断点续传要分场景。SSE 流式中断——已生成的回答先落库（StreamTaskManager 取消回调），前端重连带 taskId，实例 B 读 Redis checkpoint 从断点继续生成；Agent ReAct 循环中断——每轮 LLM 调用后把执行状态（第几轮 + messages）存 Redis checkpoint，注意 checkpoint 存"执行进度"而非"对话历史"，两者不同；文档入库中断——MQ at-least-once 保证重投 + 节点级断点（lastCompletedNodeId）+ MinerU 缓存让重跑也快；Workflow 引擎中断——FlowContext 序列化到 Redis，但对话场景因序列化开销可折中只对非对话场景开启。
>
> 通用原则是三层防护：持久化（可恢复）、幂等（重试安全）、缓存（重跑便宜）。当前两个项目的现状是——PowerAgent 的 ADK Session 有 Redis 但无 checkpoint，FlowContext 纯内存是最大缺陷；ragent 的入库有 MQ 重投但无断点续跑。这些是需要补的能力。

---

## 总结

三个问题覆盖了三个层面：**协议层**（tool_calls 是 LLM 调工具的机器语言）、**记忆层**（长期记忆存"用户关心什么"而非"答案是什么"）、**可靠性层**（断点续传分四类场景，靠"持久化 + 幂等 + 缓存"三层防护）。共同点都是"省成本 + 避免冲突"的工程权衡——tool_calls 的 arguments 字符串 + finish_reason 控制循环、长期记忆的"话题而非答案"避免新旧冲突、断点续传的"持久化 + 幂等"避免重跑浪费。
