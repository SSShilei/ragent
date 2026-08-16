# Function Calling 设计方案

> 为 ragent 增加 LLM 自主工具调用能力，并与现有 MCP 工具体系、7 步 Pipeline、infra-ai 多模型路由深度整合。

---

## 一、现状分析

### 1.1 ragent 当前模式：意图树路由（确定性）

```
用户问题 → ③ LLM 意图打分 → 命中 MCP 叶子节点 → 节点 mcpToolId 写死
  → LLMMcpParameterExtractor 额外调一次 LLM 提取参数
  → 执行工具 → 结果拼进 Prompt → 单次 LLM 生成
```

| 维度 | 当前值 |
|---|---|
| 工具选择 | 意图树叶子节点硬绑定 `mcpToolId`（数据库配置） |
| 参数提取 | 额外调一次 LLM（`LLMMcpParameterExtractor`） |
| 多轮循环 | 不支持——工具结果拼进 Prompt，单次生成 |
| 工具并行 | 多个 MCP 意图并行执行（`mcpBatchExecutor`） |
| 错误恢复 | 工具失败 → `isError=true` 拼进上下文，LLM 看到但不能重试 |

### 1.2 已有基础设施（可直接复用）

| 组件 | 作用 | 文件 |
|---|---|---|
| `McpToolRegistry` / `McpClientToolExecutor` | MCP 工具注册/发现/调用 | `rag/core/mcp/` |
| `Tool.inputSchema` | 工具参数 JSON Schema | MCP SDK |
| `ChatRequest.enableTools` | 预留的 Function Calling 开关 | `framework/convention/ChatRequest.java` |
| `RoutingLLMService` | 多模型路由 + 断路器 + 首包探测 | `infra-ai/...` |
| `ModelHealthStore` | CLOSED/OPEN/HALF_OPEN 三态断路器 | `infra-ai/...` |
| `ProbeStreamBridge` | 流式首包超时 60s → 切换模型 | `infra-ai/...` |
| `StreamTaskManager` | 跨节点流式取消 | `rag/service/handler/` |
| `FairDistributedRateLimiter` | 全局公平限流 | `rag/service/ratelimit/` |
| `OpenAIStyleSseParser` | SSE 逐行解析框架 | `infra-ai/chat/` |
| `mcpBatchExecutor` | MCP 工具并行执行线程池 | `rag/config/` |

### 1.3 缺失清单

| # | 缺失 | 影响 |
|---|---|---|
| 1 | MCP Tool Schema → OpenAI `tools` 参数格式转换 | 工具列表无法传给 LLM |
| 2 | SSE `tool_calls` delta 流式解析 | 无法从流式响应中提取工具调用 |
| 3 | ReAct 循环（调工具→回填→再请求） | 只能单次工具调用 |
| 4 | `max_llm_calls` 硬上限 | 死循环风险 |
| 5 | 单工具执行超时 | 慢工具阻塞整个循环 |
| 6 | 工具结果截断 | 返回过长撑爆上下文 |
| 7 | Pipeline checkpoint | 实例挂 → 全丢 |
| 8 | 模型输出不符合预期时的自动重试 | 全靠人工调 prompt |

---

## 二、总体架构设计

### 2.1 分层架构

```
┌─ 决策层（新增）── ReAct Loop ─────────────────────────────┐
│  AgentLoopExecutor                                         │
│  ├─ while (round < maxLlmCalls) { llmCall → parse → act } │
│  ├─ 循环控制: max_llm_calls, tokenBudget, 重复检测         │
│  └─ checkpoint: 每轮后存 Redis                              │
└────────────────────────────────────────────────────────────┘
        │ LLM 返回 tool_calls
        ▼
┌─ 转换层（新增）── Schema Adapter ──────────────────────────┐
│  McpToolSchemaConverter                                    │
│  ├─ Tool.inputSchema (JSON Schema) → OpenAI tools 参数     │
│  └─ 95% 透传, 仅处理 $ref / enum / default 边缘字段       │
└────────────────────────────────────────────────────────────┘
        │ 匹配 tool_name → toolId
        ▼
┌─ 执行层（已有）── MCP 工具体系 ────────────────────────────┐
│  McpToolRegistry.getExecutor(toolId)                       │
│  ├─ McpClientToolExecutor.execute(params)                  │
│  ├─ 超时保护: Future.get(timeout)                          │
│  └─ 结果截断: maxToolOutputLength                          │
└────────────────────────────────────────────────────────────┘
```

### 2.2 与现有 Pipeline 的关系

**不能替换现有的 7 步 Pipeline**——意图树路由在确定性场景（FAQ、KB 检索）下仍然是最优方案。Function Calling 应该作为 Pipeline 的一个**可选的增强模式**：

```
StreamChatPipeline.execute()

  ... ①~⑤ 保持不变 ...

  ⑥ retrieve()  ← 保持不变（KB 检索）

  ┌─ 新增分支 ──────────────────────────────────────────┐
  │ if (ctx.requiresAgentLoop()) {                        │
  │     agentLoopExecutor.run(ctx);  // 进入 ReAct 循环  │
  │ } else {                                             │
  │     streamRagResponse(ctx, retrievalCtx);  // 原逻辑  │
  │ }                                                    │
  └──────────────────────────────────────────────────────┘
```

触发条件：意图包含 MCP 节点 **且** `enableFunctionCalling=true`。

### 2.3 数据流

```
用户: "查北京天气并对比去年同日"
    │
    ▼
Pipeline ①~⑥: loadMemory → rewriteQuery → resolveIntents → retrieve(KB)
    │  retrievalCtx: {kbContext: "去年同日气温：28°C"}
    ▼
AgentLoopExecutor.run():
    │
    ├─ Round 1:
    │   │ buildChatRequest(messages + retrievalCtx + tools)
    │   │ LLM 返回: tool_calls=[{name:"weather_query", args:{city:"北京", date:"2026-08-12"}}]
    │   │ McpToolRegistry.getExecutor("weather_query").execute(args)
    │   │ 结果: {temp:32, weather:"晴"}
    │   │ messages.add(tool_result)
    │   │ checkpoint → Redis
    │   │ continue
    │
    ├─ Round 2:
    │   │ LLM 返回: "北京今天32°C，比去年同日的28°C高4°C，更热。"
    │   │ isTextAnswer → break
    │
    └─ 流式推送最终答案
```

---

## 三、核心模块设计

### 3.1 McpToolSchemaConverter（Schema 转换）

```java
/**
 * 将 MCP Tool.inputSchema (JSON Schema) 转为 OpenAI FunctionDeclaration 格式。
 * 95% 字段直接透传，仅处理不兼容的边缘属性。
 */
@Component
public class McpToolSchemaConverter {

    /**
     * 批量转换：MCP Tool 列表 → OpenAI tools 数组
     */
    public List<Map<String, Object>> convertTools(List<Tool> mcpTools) {
        return mcpTools.stream()
            .map(this::convertOne)
            .toList();
    }

    /**
     * 单个工具转换
     *
     * MCP: {name, description, inputSchema: {type, properties, required}}
     *   ↓
     * OpenAI: {type: "function", function: {name, description, parameters: {...}}}
     */
    private Map<String, Object> convertOne(Tool tool) {
        JsonNode schema = tool.inputSchema();

        // 1. 清理 JSON Schema 中 OpenAI 不支持的字段
        JsonNode cleanSchema = removeUnsupportedFields(schema);
        // 移除: $schema, $ref, additionalProperties, definitions,
        //       oneOf/anyOf/allOf（扁平化为 type:string）

        // 2. 包装为 OpenAI 格式
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", tool.name(),
                "description", tool.description(),
                "parameters", cleanSchema
            )
        );
    }
}
```

关键处理：`$ref` 内联解析（递归替换引用）、`oneOf/anyOf` 降级为宽松 string、`enum` 保留（OpenAI 支持）。

### 3.2 ToolCallsStreamParser（流式 tool_calls 解析）

```java
/**
 * 全缓冲拼装策略：收集所有 tool_calls delta chunk，
 * 按 index 拼成完整的 JSON 字符串，流结束后一次性解析。
 */
public class ToolCallsStreamParser {

    // key = tool_call index (0,1,2...)
    private final Map<Integer, StringBuilder> argumentsBuffers = new HashMap<>();
    private final Map<Integer, String> toolNames = new HashMap<>();
    private final Map<Integer, String> toolCallIds = new HashMap<>();

    /**
     * 在 OpenAIStyleSseParser 的 onDelta 回调中调用
     */
    public void feedToolCallDelta(int index, String callId,
                                   String name, String argumentsDelta) {
        if (callId != null) toolCallIds.put(index, callId);
        if (name != null) toolNames.put(index, name);
        if (argumentsDelta != null) {
            argumentsBuffers.computeIfAbsent(index, k -> new StringBuilder())
                           .append(argumentsDelta);
        }
    }

    /**
     * 流结束时调用，返回完整的 ToolCall 列表
     */
    public List<ToolCall> finish() {
        List<ToolCall> results = new ArrayList<>();
        for (int i = 0; i < toolCallIds.size(); i++) {
            StringBuilder buf = argumentsBuffers.get(i);
            if (buf == null) continue;
            String argsJson = buf.toString();
            if (argsJson.isBlank()) continue;

            Map<String, Object> args = parseJson(argsJson);
            results.add(new ToolCall(
                toolCallIds.get(i),
                toolNames.get(i),
                args
            ));
        }
        return results;
    }
}
```

非流式降级：检测到 tool_calls 时也可内部用非流式 API 重请求一次拿完整结果，作为兜底。

### 3.3 AgentLoopExecutor（ReAct 循环）

```java
/**
 * ReAct 循环执行器：while(未结束) { 调LLM→解析tool_call→执行→回填 }
 */
@Slf4j
@Component
public class AgentLoopExecutor {

    // 三层防御
    private static final int MAX_LLM_CALLS = 15;          // 硬上限
    private static final int MAX_CONSECUTIVE_SAME_TOOL = 3; // 重复检测
    private static final int MAX_TOOL_OUTPUT_LENGTH = 4000; // 结果截断
    private static final Duration SINGLE_TOOL_TIMEOUT = Duration.ofSeconds(30);

    private final LLMService llmService;
    private final McpToolRegistry toolRegistry;
    private final McpToolSchemaConverter schemaConverter;
    private final ToolCallsStreamParserFactory parserFactory;
    private final Executor mcpBatchExecutor;
    private final StringRedisTemplate redis;  // checkpoint

    /**
     * 执行 Agent 循环，返回最终文本回答
     */
    public String run(AgentLoopContext ctx) {
        List<ChatMessage> messages = ctx.getMessages();
        List<Tool> tools = ctx.getTools();

        int round = 0;
        String lastToolName = null;
        int consecutiveSameCall = 0;

        while (round < MAX_LLM_CALLS) {
            round++;

            // ① 构建请求（带 tools），发送非流式便于解析 tool_calls
            ChatRequest req = ChatRequest.builder()
                .messages(new ArrayList<>(messages))
                .tools(schemaConverter.convertTools(tools))
                .temperature(0D)
                .build();

            LLMResponse resp = llmService.chat(req); // 非流式

            // ② 判断是文本回答还是工具调用
            if (resp.hasToolCalls()) {
                List<ToolCall> toolCalls = resp.getToolCalls();

                // 并行执行所有 tool_call
                List<CompletableFuture<ToolResult>> futures = toolCalls.stream()
                    .map(tc -> CompletableFuture.supplyAsync(
                        () -> executeWithTimeout(tc), mcpBatchExecutor))
                    .toList();

                List<ToolResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

                // 重复检测
                for (ToolCall tc : toolCalls) {
                    if (tc.name().equals(lastToolName)) {
                        consecutiveSameCall++;
                        if (consecutiveSameCall >= MAX_CONSECUTIVE_SAME_TOOL) {
                            log.warn("连续 {} 次调用同一工具 {}，终止循环", consecutiveSameCall, tc.name());
                            return "工具调用似乎陷入循环，请换一种方式提问。";
                        }
                    } else {
                        consecutiveSameCall = 0;
                    }
                    lastToolName = tc.name();
                }

                // 回填结果
                for (ToolResult result : results) {
                    messages.add(ChatMessage.tool(
                        result.toolCallId(),
                        truncate(result.output(), MAX_TOOL_OUTPUT_LENGTH)
                    ));
                }

                // checkpoint
                saveCheckpoint(ctx.getTaskId(), round, messages);
                continue;

            } else {
                // ③ 文本回答 → 返回
                return resp.getContent();
            }
        }
        // 超限兜底
        return "处理步骤超过上限，请简化问题后重试。";
    }

    /**
     * 单工具执行 + 超时保护
     */
    private ToolResult executeWithTimeout(ToolCall tc) {
        try {
            McpToolExecutor executor = toolRegistry.getExecutor(tc.name())
                .orElseThrow(() -> new RuntimeException("工具未注册: " + tc.name()));

            return CompletableFuture
                .supplyAsync(() -> executor.execute(tc.args()))
                .get(SINGLE_TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new ToolResult(tc.id(), "工具执行超时，请换参数重试或换工具");
        } catch (Exception e) {
            return new ToolResult(tc.id(), "工具执行异常: " + e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...(已截断)";
    }

    private void saveCheckpoint(String taskId, int round, List<ChatMessage> messages) {
        try {
            String key = "ragent:agent:loop:" + taskId;
            String json = objectMapper.writeValueAsString(
                new Checkpoint(round, messages, System.currentTimeMillis()));
            redis.opsForValue().set(key, json, Duration.ofMinutes(5));
        } catch (Exception e) {
            log.warn("Agent loop checkpoint 写入失败 taskId={}", taskId, e);
        }
    }
}
```

**循环控制的四层防御**：

| 层 | 机制 | 值 |
|---|---|---|
| 硬上限 | `MAX_LLM_CALLS` | 15 |
| 重复调用检测 | 连续 N 次同工具同参数 → 终止 | 3 次 |
| Token 预算 | 累计 token 超限 → 终止 | 后续可配置 |
| LLM 自主终止 | LLM 自己判断"够了"输出文本回答 | 依赖模型判断 |

### 3.4 SSE tool_calls 流式推送（可选增强）

非流式（`chat`）获取 tool_calls 最简单可靠，但 LLM 响应可能 2-5s 无输出。如需流式体验：

```java
// OpenAIStyleSseParser 新增分支
if (delta.has("tool_calls")) {
    JsonArray toolCalls = delta.getAsJsonArray("tool_calls");
    for (JsonElement tc : toolCalls) {
        JsonObject tcObj = tc.getAsJsonObject();
        int index = tcObj.get("index").getAsInt();
        String callId = tcObj.has("id") ? tcObj.get("id").getAsString() : null;
        JsonObject function = tcObj.getAsJsonObject("function");
        String name = function.has("name") ? function.get("name").getAsString() : null;
        String args = function.has("arguments") ? function.get("arguments").getAsString() : null;

        parser.feedToolCallDelta(index, callId, name, args);
    }
    return; // tool_calls 不给 onContent 回调
}
```

### 3.5 Pipeline Checkpoint（实例恢复）

参考 Q13 讨论过的 checkpoint 方案。

```java
// StreamChatContext 新增
public void saveCheckpoint(String stage, Object payload) {
    String key = "ragent:pipeline:checkpoint:" + taskId;
    String json = objectMapper.writeValueAsString(new Checkpoint(stage, payload));
    stringRedisTemplate.opsForValue().set(key, json, Duration.ofMinutes(5));
}

public Checkpoint loadCheckpoint() {
    String json = stringRedisTemplate.opsForValue()
        .get("ragent:pipeline:checkpoint:" + taskId);
    return json == null ? null : objectMapper.readValue(json, Checkpoint.class);
}
```

Pipeline `execute()` 恢复逻辑：

```java
public void execute(StreamChatContext ctx) {
    // 检查是否存在 checkpoint（实例崩溃恢复）
    Checkpoint cp = ctx.loadCheckpoint();
    if (cp != null) {
        ctx.restore(cp);
        resumeFrom(ctx, cp.getStage());
        return;
    }

    // 正常 7 步
    loadMemory(ctx);
    rewriteQuery(ctx);
    resolveIntents(ctx);
    // ... 后续
}
```

**效果**：

| Checkpoint 阶段 | 恢复后行为 |
|---|---|
| `after_rewriteQuery` | 跳过记忆加载，直接从意图识别开始 |
| `after_retrieve` | 跳过前 6 步，直接进入 Prompt 组装 |
| `agent_loop_round_3` | Agent 循环中恢复，继续第 4 轮 |

**限制**：
- 流式生成阶段无法从中间恢复（token 已推送）
- 中间结果较大时存 S3 而非 Redis（chunk 集合超 Redis value 限制时）

---

## 四、实施路线图

### Phase 1：最小可用（2-3 周，核心 1 人）

**目标**：跑通单 Agent + 单工具调用的完整链路。

```
① McpToolSchemaConverter   (Schema 转换)      ~200 行
② ToolCallsStreamParser    (流式解析)           ~120 行
③ AgentLoopExecutor        (ReAct 核心循环)     ~300 行
④ 修改 OpenAIStyleSseParser (tool_calls 分支)   ~40 行
⑤ 非流式兜底 ChatRequest    (enableTools=true)   ~30 行
⑥ 单元测试                                     ~200 行
```

里程碑：发 "北京今天天气怎么样"，LLM 通过 MCP 调 `weather_query`，拿到结果回答。

**不做**：StreamChatPipeline 集成、checkpoint、A/B。

### Phase 2：集成现有系统（2-3 周）

```
⑦ StreamChatPipeline 集成      (MCP 意图触发 AgentLoop)  ~100 行
⑧ 多工具并行执行               (已有 mcpBatchExecutor)     ~30 行
⑨ 循环控制三层防御              (已有 max_llm_calls + 重复检测) ~80 行
⑩ infra-ai 多模型路由集成       (已有，加 tool 参数)       ~50 行
⑪ Pipeline checkpoint Redis    (断点恢复)                 ~150 行
⑫ 集成测试                                                 ~300 行
```

里程碑："查北京天气并对比去年同日，再给穿衣建议"——多轮多工具 LLM 自主完成。

### Phase 3：生产化（3-4 周）

```
⑬ 工具结果截断 + Token 预算          ~80 行
⑭ 评测集成（EvalController 加 expectedToolCalls） ~100 行
⑮ Function Calling 质量监控指标（Prometheus）     ~150 行
⑯ A/B 分流（意图树路由 vs Function Calling）      ~120 行
⑰ 管理后台 Function Calling 开关配置             ~100 行
⑱ 文档/README                                   ~200 行
```

里程碑：灰度上线，A/B 对比意图树路由和 Function Calling 的效果和成本。

---

## 五、风险评估

| 风险 | 等级 | 缓解 |
|---|---|---|
| LLM 幻觉调用不存在的工具 | 中 | Schema 严格匹配 + `tool_choice: auto` + function name 精确白名单 |
| 工具返回超长撑爆上下文 | 高 | `MAX_TOOL_OUTPUT_LENGTH=4000` 硬截断 + 截断标记让 LLM 感知 |
| 死循环（调工具→不满意→再调→循环）| 高 | 四层防御: 硬上限 15 + 连续重复检测 + Token 预算 + 单工具超时 |
| 工具执行慢/超时阻塞主流程 | 中 | `Future.get(timeout)` + 独立线程池 + 超时回 `isError=true` |
| API 成本飙升 | 高 | 每轮 LLM 调用 token 翻倍，通过 `MAX_LLM_CALLS` + Token 预算控制 |
| 意图树和 Function Calling 并存导致行为不一致 | 中 | A/B 灰度 + Eval 对比指标后再全量 |
| 工具结果是敏感数据（通过 MCP 泄露） | 中 | MCP Server 层做脱敏 + `isError` 模式不返回原始异常堆栈 |

---

## 六、配置设计

```yaml
rag:
  agent:
    function-calling:
      enabled: false          # 总开关，默认关
      max-llm-calls: 15       # ReAct 循环硬上限
      max-tool-output-chars: 4000  # 单工具结果最大字符数
      max-consecutive-same-tool: 3  # 连续调用同一工具上限
      single-tool-timeout-seconds: 30  # 单工具执行超时
      checkpoint-enabled: true  # Redis checkpoint
      checkpoint-ttl-seconds: 300  # checkpoint 存活时间

  search:
    channels:
      mcp:
        function-calling-mode: auto  # auto(有MCP意图时) / always / off
```

---

## 七、评测闭环

### 7.1 现有评测适配

`/rag/eval` 接口扩展：

```java
// 新增字段
EvalResponse {
    // ... 现有字段保持不变 ...
    expectedToolCalls: List<String>,  // 评测集标注的期望工具调用（如 ["weather_query"]）
    actualToolCalls: List<String>,    // Function Calling 实际调用的工具
    toolCallRound: int,               // 总共几轮 LLM 调用
    totalTokens: long                 // 总 token 消耗
}
```

### 7.2 评测指标

| 指标 | 计算方式 |
|---|---|
| 工具调用准确率 | `actualToolCalls` ∩ `expectedToolCalls` / `expectedToolCalls` |
| 不必要调用率 | 调了工具但 LLM 最终没用到结果的比例 |
| 平均 LLM 调用轮次 | `avg(toolCallRound)` |
| Token 消耗 | `avg(totalTokens)` |
| 任务成功率 | 最终回答正确 + 工具未超时 + 未超 max_llm_calls |

### 7.3 回归防劣化

把意图树的 `intentLeafIds`（预期意图）扩展为 `expectedToolCalls`（预期工具调用）。评测集结构：

```json
{
  "question": "今天北京天气怎么样",
  "ground_truth": "北京今天晴, 25°C",
  "expectedToolCalls": ["weather_query"],
  "expectedArgs": {"city": "北京"},
  "reference_doc_ids": []
}
```

每次改检索参数/工具 Schema/循环上限都全量回归跑。评测结果自动对比意图树路由的基线指标。

---

## 八、业界方案对照

| 方案 | 优点 | 缺点 | 本项目是否采用 |
|---|---|---|---|
| **LangChain4j `@Tool`** | Java-native, Spring Boot 原生, 零跨语言 | 多 Agent 编排不成熟 | Phase 1 可用做快速验证 |
| **Spring AI** | Spring 官方 | Agent 能力最弱 | 不推荐（当前版本） |
| **Google ADK (跨语言)** | 多 Agent 编排最成熟 | 跨语言 HTTP, infra-ai 基础设施用不上 | 已有 PowerAgent 储备，ragent 暂不采用 |
| **基于 infra-ai 自研** | 完全可控, 与现有体系无缝衔接 | 开发工作量大 | **推荐主力方案** |

**推荐方案**：Phase 1 基于 infra-ai 自研最小 AgentLoopExecutor + McpToolSchemaConverter，参考 LangChain4j 的工具声明模式。不做跨语言接入 ADK——保留 infra-ai 的断路器、多模型路由、首包探测等基础设施优势。

---

## 九、与 PowerAgent 的架构对比

| 维度 | ragent (改造后) | PowerAgent (Google ADK) |
|---|---|---|
| Agent 循环 | 自研 AgentLoopExecutor | ADK Runner + PlanReActPlanner |
| 工具执行 | MCP（Java 直调） | HTTP → agentflow-server `/api/v1/tools/run` |
| 多 Agent | 暂无（Phase 3 未定） | `sub_agents` + `transfer_to_agent` |
| 语言 | 纯 Java | Python (ADK) + Java (agentflow) |
| 断路器 | infra-ai ModelHealthStore ✅ | Sentinel（已引入但未启用熔断） |
| 模型路由 | RoutingLLMService 多候选 fallback | LiteLLM 统一代理 |

两个项目定位互补：ragent 做高效 RAG 检索 + 单 Agent 工具调用，PowerAgent 做多 Agent 协作 + 复杂推理场景。
