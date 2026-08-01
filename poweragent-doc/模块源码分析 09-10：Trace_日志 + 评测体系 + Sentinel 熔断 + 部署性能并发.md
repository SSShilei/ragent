# 模块源码分析 09\-10：Trace/日志 \+ 评测体系 \+ Sentinel 熔断 \+ 部署性能并发

> 对应面试题：Q33 Trace 追踪与日志结构化 / Q34 跨 Session 日志 / Q35 评测体系 / Q36 幻觉治理 / Q37 部署性能并发 / Q38 项目难点 前置阅读：`source-code-analysis-02-03-04`（SSE 流式与 ChatNode）、`source-code-analysis-05-08`（插件/记忆/多模态）
> 
> 

---

## **一、Trace 与日志 \(09\)**

### **1\.1 核心文件清单**

```Plain Text
agentflow-server/
├── agent-flow-app/agent-flow-serving/
│   └── .../application/util/
│       └── SSEUtils.java                    ★ SSE 订阅注册表 (105 行) ConcurrentHashMap<reqId, SseEmitter>
│
├── agent-flow-app/agent-flow-workflow/
│   └── .../application/listeners/
│       └── AutoAgentChatSSEListener.java    ★ 链路核心：事件计时 + traceId 落库 (544 行)
│
├── agent-flow-app/agent-flow-agents/
│   └── .../domain/po/
│       ├── AutoAgentChatItem.java           ★ 对话消息表 (trace_id/running_time) (153 行)
│       ├── AutoAgentChatItemDetail.java     ★ 对话明细表 (trace_id/execute_time)
│       └── AutoAgentChat.java               ★ 会话表 (chat_id)
│
├── agent-flow-server/
│   ├── .../controller/autoagent/
│   │   └── AutoAgentChatController.java     ★ 入口：requestId 生成 + SSE 订阅
│   ├── .../interceptors/
│   │   └── PlatformInterceptor.java         ★ MDC 注入 requestId/uri (76 行)
│   └── src/main/resources/
│       └── logback-spring.xml               ★ 结构化日志 + 异步 (104 行)
│
└── af-rag-server/agent/.../framework_adapter/   ★ Python 端 EventSource 回调流
    └── okhttp3.sse.EventSourceListener
```

### **1\.2 三层 Trace 设计全景**

```Plain Text
HTTP 请求
  │  PlatformInterceptor.preHandle → MDC.put("requestId", UUID) / MDC.put("uri", ...)
  ▼
AutoAgentChatController
  │  requestId 为空则生成 UuidUtil.getUUID()  →  dto.setRequestId(requestId)
  │  stream=true → emitter = SSEUtils.addSub(requestId)   ★ requestId 即 SSE 频道 ID
  ▼
okhttp3 EventSource 异步流
  │  AutoAgentChatSSEListener.onEvent
  │    time = now - eventStartTime;  totalTime += time;   ★ 每事件耗时
  │    SSEUtils.pubMsg(reqId, event, json) → 实时推前端
  ▼
onClosed
  │  CompletableFuture.runAsync(asyncSaveChat) → 异步落库
  ▼
AutoAgentChatItem.trace_id = dto.getRequestId()   ★ requestId == traceId
AutoAgentChatItemDetail.trace_id = 同上 + execute_time = 单事件耗时
```

**设计核心**：同一个 `requestId` 贯穿「HTTP 请求 → SSE 频道 → 数据库 trace\_id」三个环节，**HTTP 请求与流式回传、落库记录三者天然绑定**，无需额外生成链路号。

### **1\.3 SSEUtils\.java — SSE 订阅注册表**

**文件**: `.../agent-flow-serving/.../application/util/SSEUtils.java` **行数**: 105 行

```Java
public class SSEUtils {
    // 超时时间 10 分钟
    private static final Long DEFAULT_TIME_OUT = 10 * 60 * 1000L;
    // 全局订阅表: reqId -> SseEmitter (并发安全)
    private static final Map<String, SseEmitter> subscribeMap = new ConcurrentHashMap<>();

    public static SseEmitter addSub(String reqId) {
        SseEmitter emitter = subscribeMap.get(reqId);
        if (null == emitter) {
            emitter = new SseEmitterUTF8(DEFAULT_TIME_OUT);
            emitter.onError((e) -> closeSub(reqId));     // 异常关闭
            emitter.onTimeout(() -> closeSub(reqId));    // 10 分钟超时关闭
            emitter.onCompletion(() -> closeSub(reqId)); // 正常完成关闭
            subscribeMap.put(reqId, emitter);
        }
        return emitter;
    }

    public static void pubMsg(String reqId, String event, String msg) {
        SseEmitter emitter = subscribeMap.get(reqId);
        if (null != emitter) {
            emitter.send(event().name(" " + event).data(" " + msg));
        }
    }

    public static void closeSub(String reqId) {
        emitter.complete();
        subscribeMap.remove(reqId);   // 无论成败都从表里移除
    }
}
```

**面试要点**:

- **为什么用 ****`ConcurrentHashMap`**** 而非普通 Map**：SSE 的订阅、发送、关闭发生在不同线程（请求线程/OkHttp 回调线程/超时线程），必须并发安全。

- **为什么 reqId 不用自增 ID**：reqId 由前端发起 HTTP 请求时携带（后端兜底生成 UUID），SSE 建立连接前就有值，天然作为频道键；后续 DB 落库也复用同一值 → 三段贯通。

- **三种关闭回调全指向 ****`closeSub`**：`onError`/`onTimeout`/`onCompletion` 统一收口，保证订阅表不泄漏（否则每个会话泄漏一个 SseEmitter）。

### **1\.4 AutoAgentChatSSEListener\.java — 事件计时 \+ traceId 落库**

**文件**: `.../agent-flow-workflow/.../application/listeners/AutoAgentChatSSEListener.java` **行数**: 544 行 **角色**: okhttp3 `EventSourceListener`，消费 RAG 服务的流式事件（function\_call / function\_response / thinkResult / finalResult）

**① 构造函数绑定 reqId**（line 71\-81）

```Java
public AutoAgentChatSSEListener(SessionUserInfo userInfo, ChatDto dto, AgentSnapshot agentSnapshot,
                                Date startTime, CountDownLatch latch, ScheduledExecutorService scheduler) {
    this.reqId = dto.getRequestId();   // ★ 核心：reqId 来自请求 DTO
    ...
}
```

**② 事件计时**（line 90\-146）— 每收到一个流式事件，记录与上一事件的时间间隔

```Java
private long eventStartTime = System.currentTimeMillis(); // 添加时间戳变量
private long totalTime;                                   // 总耗时

@Override
public void onEvent(@NotNull EventSource eventSource, String id, String type, @NotNull String data) {
    events.add(data);
    AutoAgentRagVO agentRagVO = new AutoAgentRagVO();
    long currentTime = System.currentTimeMillis();
    // 计算间隔时间
    long time = currentTime - eventStartTime;
    // 更新时间戳
    eventStartTime = currentTime;
    totalTime = totalTime + time;
    agentRagVO.setTime(time);           // ★ 单事件耗时
    agentRagVO.setChatItemId(chatItemId);
    ...
    // 非 JSON → 视为异常消息
    if (!isJsonObject(data)) {
        agentRagVO.setError_message(data);
        agentRagVO.setError_code("ATA_60000");          // ★ 错误码
        SSEUtils.pubMsg(reqId, AutoAgentChatTypeEnum.ERROR.getValue(), JSONObject.toJSONString(agentRagVO));
        return;
    }
    parseError(jsonObject, agentRagVO);
    CustomMetadata customMetadata = parseCustomMetadata(agentRagVO, jsonObject);
    // 工具异常(非 ATA_20000) → 置 runStatus=0 并推送 TOOL_ERROR
    if (ObjectUtils.isNotEmpty(agentRagVO.getError_message())
            && !"ATA_20000".equalsIgnoreCase(agentRagVO.getError_code())) {
        runStatus = 0;
        if (dto.getStream()) {
            SSEUtils.pubMsg(reqId, AutoAgentChatTypeEnum.TOOL_ERROR.getValue(), JSONObject.toJSONString(agentRagVO));
        }
    } else {
        parseFunction(jsonObject, content, agentRagVO);  // 解析 function_call/response + thinkResult/finalResult
        if (dto.getStream()) { outputMessage(agentRagVO, customMetadata); }
    }
    ragVOLists.add(agentRagVO);
    ragMapLists.put(reqId, ragVOLists);
}
```

**③ 关闭后异步落库**（line 198\-229）— 不阻塞主链路

```Java
@Override
public void onClosed(@NotNull EventSource eventSource) {
    scheduler.shutdown();
    if (!dto.getStream()) { latch.countDown(); }   // 非流式: 唤醒等待线程
    else { SSEUtils.complete(reqId); }             // 流式: 通知前端 END
    List<AutoAgentRagVO> ragVO = getRagVO(reqId);
    if (!CollectionUtils.isEmpty(ragVO) && !chatType) {
        CompletableFuture.runAsync(new MsxfRunnable(() -> {
            asyncSaveChat(ragVO);                  // ★ 异步落库, 不阻塞返回
        }), ExecutorUtil.antoAgentChatExecutor);
    }
    // 埋点记录智能体调用数据
    ...
}
```

**④ traceId 落库**（line 351\-420）— requestId 原样写入两张表

```Java
String chatItemId = UuidUtil.getUUID();
String traceId = dto.getRequestId();              // ★ requestId == traceId
AutoAgentChatItem itemUser = AutoAgentChatItem.builder()
        .agentId(dto.getAgentId())
        .chatId(chatId)
        .chatObj(AutoAgentChatTypeEnum.HUMAN.getValue())
        .chatItemId(chatItemId)
        .traceId(traceId)
        .chatValue(dto.getQuestion())
        ...
        .runningTime(totalTime)                    // ★ 整个流累计耗时
        .build();
...
for (int i = 0; i < events.size(); i++) {
    AutoAgentChatItemDetail detail = createAutoAgentChatDetail(userInfo, traceId, chatItemId);
    detail.setDetailData(events.get(i));                    // 每事件原始 JSON
    detail.setExecuteTime(ragVO.get(i).getTime());          // ★ 每事件耗时 → 可做节点级耗时分
    itemDetails.add(detail);
}
autoAgentChatItemService.saveBatch(items);
autoAgentChatItemDetailService.saveBatch(itemDetails);
```

**错误码设计**（line 106\-109, 261\-266）:

**面试要点**: `totalTime` 是「累加相邻事件间隔」，而非简单的结束\-开始时间差 —— 原因是流式事件可能长时间无事件（如 LLM 思考中），用时间戳差值会包含静默期，而累加间隔更贴近「真实执行耗时」。每个事件在 `AutoAgentChatItemDetail` 中保留单事件耗时 \+ 原始 JSON，可支撑**逐节点耗时分分析**（Q33 的「定位慢节点」能力）。

### **1\.5 AutoAgentChatItem\.java — 链路字段**

**文件**: `.../agent-flow-agents/.../domain/po/AutoAgentChatItem.java` **行数**: 153 行

**面试要点**: 用户点踩数据与 `trace_id` 同表存储 → 线上出现坏回答时，可通过「点踩消息 → traceId → 明细表逐事件」**完整还原该次请求的每个工具调用与耗时**，是评测/复盘的数据底座。

### **1\.6 MDC 日志上下文 — PlatformInterceptor**

**文件**: `.../agent-flow-server/.../interceptors/PlatformInterceptor.java` **行数**: 76 行

```Java
public boolean preHandle(HttpServletRequest httpServletRequest,
                         HttpServletResponse httpServletResponse, Object o) throws Exception {
    this.initLogParams(httpServletRequest);   // ★ 先注入 MDC
    // 校验平台参数
    String platformTag = httpServletRequest.getHeader("X-Platform");
    ...
    if (AgentFlowCommonConstant.PLATFORM_TAG.equalsIgnoreCase(platformTag)) { return true; }
    ...
}

private void initLogParams(HttpServletRequest httpServletRequest) {
    String requestId = UUID.randomUUID().toString();
    MDC.put("requestId", requestId);          // ★ 每个 HTTP 请求一个 requestId
    MDC.put("uri", httpServletRequest.getRequestURI());
}
```

**面试要点**:

- `PlatformInterceptor` 在业务之前先 `MDC.put`，把 requestId/uri 写进当前线程的日志上下文，logback pattern 通过 `%X{requestId}` 取用（本项目 pattern 用的是框架注入的 `%X{PtxId}`/`%X{PspanId}`，见下）。

- MDC 是 **ThreadLocal 的实现**：同一请求内所有日志自动带 requestId；但异步线程（`CompletableFuture.runAsync`）**不会自动继承 MDC**，需要显式传递 —— 这是跨线程日志串联的经典坑点。

### **1\.7 分布式 Trace 字段 PtxId/PspanId**

**日志 pattern**（logback\-spring\.xml line 14）:

```XML
<property name="pattern" value="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %-40.40logger{39}-[TxId : %X{PtxId} , SpanId : %X{PspanId}]- %msg%n"/>
```

**谁在填 PtxId/PspanId**（来自底层框架与数据流任务）:

**面试要点（Q33「日志怎么结构化」）**：日志结构 = 时间戳 \+ 级别 \+ 线程 \+ 截断的 Logger 名 \+ **TxId/SpanId 两个链路维度**。TxId 定位「是哪一条业务请求」，SpanId 定位「这条请求里的哪一个处理步骤」。多服务调用时，调用方把 TxId/SpanId 写进 HTTP Header，被调方读取后写入自己的 MDC，实现**跨服务、跨进程的整条链路可追踪**。

### **1\.8 logback\-spring\.xml — 日志落地与异步**

**文件**: `.../agent-flow-server/src/main/resources/logback-spring.xml` **行数**: 104 行

**Appender 矩阵**:

```XML
<appender name="${appName}-info-ASYNC" class="ch.qos.logback.classic.AsyncAppender">
    <!-- 不丢失日志: 队列 80% 满时不丢弃 TRACE/DEBUG/INFO -->
    <discardingThreshold>0</discardingThreshold>
    <!-- 队列深度 10 万 -->
    <queueSize>100000</queueSize>
    <appender-ref ref="${appName}-info"/>
</appender>

<root level="INFO">
    <appender-ref ref="STDOUT"/>
    <appender-ref ref="${appName}-info-ASYNC"/>
    <appender-ref ref="${appName}-error"/>
</root>
```

**面试要点（Q33/Q37 日志与性能）**:

- **`discardingThreshold=0`**** \+ ****`queueSize=100000`**：默认 AsyncAppender 在队列 80% 满时会丢 INFO 以下日志，置 0 表示「永不丢日志」，代价是极端高并发下同步阻塞 —— 对 Agent 业务（要求完整链路）是正确取舍。

- **ERROR 单独文件**：用 `ThresholdFilter` 过滤，线上只 grep error 文件即可定位异常，不用在 info 里捞。

- **perf 日志独立 Logger \+ ****`additivity=false`**：性能日志不向 root 冒泡，避免写两份。

- **30 天滚动 \+ 1024MB 切分**：`TimeBasedRollingPolicy` \+ `SizeAndTimeBasedFNATP`，兼顾时间与大小两个维度。

- 文件末尾 `include` 了 `sentinel-logback-appender.xml`（Sentinel 日志通过 logback 统一输出）。

### **1\.9 面试核心问答 — Trace/日志**

---

## **二、评测体系 \(10\.1 \- 10\.7\)**

### **2\.1 评测体系全景**

```Plain Text
三类评测入口（同一套数据/Agent/模型参数）
        │
        ├── ① RAGAS 离线评测 (数据集 Q&A)
        │       RagasEvalService → POST /rag_algorithm/ragas_evaluate (Python)
        │       指标: context_recall + answer_correctness
        │
        ├── ② 召回/重排 LLM-as-judge 评测 (数据集)
        │       RecallEvalService / RerankEvalService
        │       指标: relevance_score (1-5) → 命中阈值判 P/R
        │
        └── ③ 线上真实数据评测 (生产对话回流)
                ProductionDataEvaluationService → 读 ChatItemES
                MultiRoundDataEvaluationService → 多轮 role B/C
                指标: 自定义 LLM-as-judge + 聚合 score
        │
        ▼
    AgentEvaluationProcessRagas / _Recall / _Rerank 明细表
        ▼
    aggregateResultV2V3() → 汇总得分 + 阈值判定 (ScoreThreshold=0.8)
```

### **2\.2 RAGAS 评测 — Python 端**

**文件**: `C:\Users\shilei.he\PA\af-rag-server\src\eval\ragas_eval.py` **行数**: 171 行

```Python
class RAGASEval:
    # 全部指标（评估时按需裁剪）
    all_metrics = [
        "answer_correctness", "answer_relevancy", "answer_similarity",
        "context_precision", "context_recall", "context_relevancy", "faithfulness"
    ]
    # 默认指标
    default_metrics = ["answer_correctness", "answer_similarity", "context_recall", "faithfulness"]

    def evaluate(self, ...):
        run_config = RunConfig(
            max_workers=4,      # 并行线程
            max_retries=1,      # 重试次数
            max_wait=600,       # 单任务最大等待(秒)
            thread_timeout=600, # 线程超时(秒)
        )
        result = evaluate(dataset, metrics=metrics,
                          llm=llm_model, embeddings=embeddings,
                          raise_exceptions=False,  # 单条失败不中断整批
                          run_config=run_config)
        # NaN(空上下文等) → -1，保证能进数据库
        res_dict = {k: (-1 if is_nan(v) else v) for k, v in result.items()}
```

**面试要点**: 7 个候选指标、4 个默认指标，但 Java 侧只取 2 个 —— 因为 **Agent 产品形态决定指标**：`context_recall`（上下文是否覆盖答案所需知识）评估 RAG 召回，`answer_correctness`（答案是否正确）评估最终生成；其余指标（如 faithfulness、answer\_relevancy）在 Agent 场景区分度低且耗时翻倍。

### **2\.3 RAGAS 评测 — Java 端**

**文件**: `.../agent-flow-server/.../biz/agenteval/RagasEvalService.java` **行数**: 254 行

```Java
// 固定指标：上下文召回 + 答案正确性
private static final List<String> metrics = Arrays.asList("context_recall", "answer_correctness");

public void executeRagasEval(AgentEvaluationData data, AgentSnapshot agent,
                             ChatOutputVO chatOutput, AgentEvaluationParameterV1DTO parameterDTO,
                             SessionUserInfo userInfo) {
    // 1) 查询评测裁判模型（失败重试 3 次 × 5000ms）
    ModelListVO modelListVO = qaEvalAgentChatService.queryEvalModel(parameterDTO.getLlm().getServiceUniCode(), 3, 5000, userInfo);
    // 2) 从对话结果取最后 CHAT_NODE 的引用上下文
    List<String> contexts = getContexts(data, chatOutput, modelListVO.getChannelName());
    // 3) 取答案 + 敏感词清洗
    String answer = chatOutput.getChoices().get(0).getMessage().getContent();
    answer = agentEvalKeywordLimitUtil.batchReplaceSensitiveWords(answer, "", modelListVO.getChannelName());
    // 4) 调 Python RAGAS
    RefereeCallResponseDTO resp = getRefereeCallResponseDTO(data, agent, parameterDTO, contexts, answer, userInfo);
    // 5) 结果落库（含脱敏后的 judgmentResult）
    ...
}
```

**请求体构建**（getRefereeCallRequestDTO）: question \+ contexts \+ answer \+ groundTruth（数据集标准答案）\+ metrics \+ **llmType/llmUrl \+ embeddingType/embeddingUrl（来自评测参数，不是 Agent 自己的模型）**。

**上下文提取**（getContexts）:

```Java
// 找出最后一个 AI 对话组件 → 拿引用知识
for (ChatHistoryItemResType item : chatOutput.getResponseData()) {
    if (FlowNodeTypeEnum.CHAT_NODE.getValue().equals(item.getModuleType())) {
        ai = item;   // 最后一个 CHAT_NODE
    }
}
List<SearchDataResponseItemType> quoteList = ai.getQuoteList();
// 每条引用格式化为 "question: %s \nanswer: %s" 作为 context
String format = "question: %s \nanswer: %s";
```

**HTTP 调用**:

```Java
String url = ragServerHost + "/rag_algorithm/ragas_evaluate";
HttpRequest httpRequest = HttpUtil.createPost(url)
        .header(LANGUAGE_HTTP_HEADER, LocaleContextHolder.getLocale().toString());
httpRequest.setConnectionTimeout(6 * 60 * 1000);   // 连接 6 分钟
httpRequest.setReadTimeout(10 * 60 * 1000);        // 读 10 分钟
headers.put("Org-Code", orgCode);                  // 租户隔离
```

**面试要点**:

- **评测裁判模型与 Agent 生产模型分离**：`llmType/llmUrl/embeddingType/embeddingUrl` 从评测参数传入，避免「用 Agent 自己的模型评自己」的偏差。

- **context 不是原始 chunk**，而是格式化为 `question: xxx \nanswer: xxx` 的引用对 —— 让 RAGAS 对「知识来源」做语义判段。

- 长超时（6/10 分钟）是因为 RAGAS 的 LLM\-as\-judge 内部会多次调用模型打分。

- 结果与失败原因都落 `AgentEvaluationProcessRagas` 表，`failReason` 截断到 400 字符。

### **2\.4 召回/重排 LLM\-as\-judge 评测**

**文件**:

- `.../biz/agenteval/RecallEvalService.java` \(221 行\)

- `.../biz/agenteval/RerankEvalService.java` \(220 行\)

两者结构完全一致，只是排序字段不同（`recallSimilarity` vs `rerankSimilarity`）。

```Java
public void executeRecallEval(SessionUserInfo userInfo, ModelListVO evalModelVO,
                              AgentEvaluationData data, AgentSnapshot agent, String promptTemplate) {
    // 1) 必须有对话结果，否则抛 AGENT_EVAL_NO_CHAT_RESULT
    AgentEvaluationDataResult dataResult = dataResultService.findDataResultByAgentIdAndDataId(...);
    // 2) 取本次对话的召回片段列表
    List<AgentEvaluationProcessRecall> recallDataList = processRecallService.findRecallData(...);
    // 3) 按召回模型分组 → 每组按相似度降序 → 只评前 N 条
    Map<String, List<AgentEvaluationProcessRecall>> map = recallDataList.stream()
            .collect(Collectors.groupingBy(AgentEvaluationProcessRecall::getRecallModelId));
    for (Map.Entry<String, List<AgentEvaluationProcessRecall>> entry : map.entrySet()) {
        List<AgentEvaluationProcessRecall> topN = entry.getValue().stream()
                .sorted(Comparator.comparing(AgentEvaluationProcessRecall::getRecallSimilarity).reversed())
                .limit(agentEvalProperties.getRecallDataEvalLimit())   // 默认 3
                .collect(Collectors.toList());
        for (AgentEvaluationProcessRecall recallData : topN) {
            if (NOT_EVAL.equals(recallData.getRecallEvalStatus())) {
                executeModelChatEvalRecall(userInfo, recallData, evalModelVO, prompt,
                        data.getQuestion(), recallData.getSegmentContent());
            }
        }
        // 非前 N → 直接标记忽略，不做评分
        topN.forEach(t -> { ... });
    }
}
```

**单条打分**（executeModelChatEvalRecall）:

```Java
ModelParamDTO modelParamDTO = new ModelParamDTO();
systemMessageItem.setRole("system");
systemMessageItem.setContent(prompt);                        // ★ 评测模板做 system
userMessageItem.setRole("user");
userMessageItem.setContent(StrUtil.format("query:{}, context:{}", query, context));
modelParamDTO.setModel(evalModelVO.getServiceAddressList().get(0).getAddress());
modelParamDTO.setStream(false);                              // ★ 非流式同步打分
modelParamDTO.setStop(CollUtil.newArrayList("<|im_end|>"));  // 兼容特定模型的结束符
modelParamDTO.setConnectTime(10 * 60 * 1000);
modelParamDTO.setReadTime(10 * 60 * 1000);
ModelOutputDTO modelOutputDTO = modelManageService.modelProcess(modelParamDTO);

// 提取 JSON 中的 relevance_score
if (!JSONUtil.isJsonObj(content)) {
    content = ReUtil.getGroup1("(\\{[\\s\\S\\n]+\\})", content);  // 容错：从文本中抠出 JSON
}
JSONObject json = JSONUtil.parseObj(content);
BigDecimal relevanceScore = json.get("relevance_score", BigDecimal.class);
processRecallService.updataRecallEvalScore(recallData.getId(), relevanceScore);
```

**失败标记**: \| 状态 \| 常量 \| 触发 \| \|\-\-\-\-\-\-\|\-\-\-\-\-\-\|\-\-\-\-\-\-\| \| `SCORE_FAIL` \| `AgentFlowCommonConstant.SCORE_FAIL` \| 模型调用异常 / 无 choices / relevance\_score 缺失 \| \| `JSON_FAIL` \| `AgentFlowCommonConstant.JSON_FAIL` \| 响应体正则抠不出 JSON \| \| null \| null \| 非前 N 被忽略 \|

**面试要点**:

- **为什么只评前 N**：一次对话可能召回几十个片段，全量 LLM 打分成本太高；召回/重排关心的是「排在前面的准不准」，所以按相似度降序取前 N（默认 3）。

- **打分与检索模型隔离**：裁判模型来自 `evalModelVO`（评测参数），片段来源是 Agent 自己的召回/重排模型 —— 这就是「LLM\-as\-judge」中的 judge 独立。

- **容错三步**：非 JSON 先正则抠 `{...}`，仍非 JSON 记 JSON\_FAIL，JSON 但缺字段记 SCORE\_FAIL —— 每个失败点都有独立状态，评测报告可区分「模型挂了」还是「没解析出来」。

- 同样的模板机制：`promptTemplate` 为空时从 `AGENT_EVAL_QA_TEST_PROMPT` 用例取默认模板（兼容旧版本）。

### **2\.5 评测配置 AgentEvalProperties**

**文件**: `.../agent-flow-common/.../config/AgentEvalProperties.java` **行数**: 101 行 **前缀**: `application.agent.eval`

```Java
@ConfigurationProperties(prefix = "application.agent.eval")
@Data
public class AgentEvalProperties {
    private Integer qaPoolSize = 1;      // QA 评测线程池核心/最大 (1-10)
    private Integer nonQaPoolSize = 1;   // 非 QA 评测线程池
    private Integer prodPoolSize = 1;    // 线上数据评测线程池
    private Integer recallDataEvalLimit = 3;  // 召回/重排只评前 N (1-20)
    private Double answerCorrectnessThreshold = 0.8;  // 答案正确性阈值
    private Double contextRecallThreshold = 0.8;      // 上下文召回阈值
    private Double recallChunkThreshold = 0.8;        // 召回片段命中阈值
    private Double rerankChunkThreshold = 0.8;        // 重排片段命中阈值
    private Boolean cacheStatus = true;               // 评测结果缓存开关
}
```

**面试要点**: 评测是**重资源、可配置**的流程 —— 池大小、前 N 数量、判定阈值全部参数化，避免每次改评测策略都要发版。阈值 0\.8 意味着召回/重排片段 relevance\_score ≥ 0\.8 才记为命中，聚合结果再与 answerCorrectness/contextRecall 阈值联合判定整体通过与否。

### **2\.6 评测编排 QAEvalAgentChatService**

**文件**: `.../agent-flow-server/.../biz/agenteval/QAEvalAgentChatService.java` **行数**: 435 行

**① 评测时调用 Agent 引擎**（executeAgentChatCompletions）:

```Java
String url = agentEngineUrl + "/api/v1/chat/completions";
// 10 分钟超时, orgid/orgcode/userid/username/tenantid 头, stream=false
// reqId/chatId/chatItemIds 由 buildRequestParam 组装
// source = ChatSourceEnum.EVALUATION  → 引擎侧区分「评测调用」不记用户会话
```

**② 裁判模型查询重试**:

```Java
public ModelListVO queryEvalModel(String serviceUniCode, int retry, long sleepMs, SessionUserInfo userInfo) {
    // 最多重试 3 次, 间隔 5000ms → 防模型服务抖动导致评测误失败
}
```

**③ 统一线程池工厂**:

```Java
public static ThreadPoolExecutor createEvalExecutor(int coolPoolSize, int maxPoolSize, String namePrefix) {
    return new ThreadPoolExecutor(coolPoolSize, maxPoolSize,
            60, TimeUnit.SECONDS,
            new LinkedBlockingDeque<>(1000),          // 有界队列 1000
            new ThreadFactoryBuilder().setNamePrefix(namePrefix + "eval-thread-").build(),
            new ThreadPoolExecutor.AbortPolicy());    // 满了抛异常, 快速失败
}
```

**面试要点**:

- `source=EVALUATION`：评测请求与真实用户请求在引擎侧区分，**不污染用户对话记录、不计入生产埋点**。

- `AbortPolicy + 有界队列 1000`：评测任务量大且可预测，宁可快速失败也不无限堆积 —— 避免评测任务把内存打爆。

### **2\.7 线上真实数据评测 ProductionDataEvaluationService**

**文件**: `.../agent-flow-server/.../biz/agenteval/ProductionDataEvaluationService.java` **行数**: 417 行

**核心差异：数据来源是生产 ES，不是人工数据集**

```Java
// 生产数据从 ES 读取 (ChatItemES)，而不是 MySQL 数据集
ChatItemESQueryDTO queryDTO = new ChatItemESQueryDTO();
queryDTO.setAgentId(agentId);   queryDTO.setVersion(version);
queryDTO.setDateStart(dateStart); queryDTO.setDateEnd(dateEnd);
queryDTO.setPageSize(...);  queryDTO.setOrderBy("time");
List<ChatItemES> list = chatItemESService.findES(queryDTO);

// HUMAN/AI 按 dataId 配对成一条评测样本
findChatItemAIData(...) / findChatItemHumanData(...)

// 多 Agent A/B 评测: evalAgentInfoMap 可同时跑新旧两个版本对比
```

**线程池与取消机制**:

```Java
ThreadPoolExecutor evalExecutor =
        QAEvalAgentChatService.createEvalExecutor(
                agentEvalProperties.getProdPoolSize(),
                agentEvalProperties.getProdPoolSize(), "prod-eval-");
// 每个阶段执行前都检查用户是否取消 (CANCELED)
// AtomicInteger 记录进度 → 前端可看进度条
```

**面试要点**: 线下数据集评测只能发现「数据集覆盖到的问题」，**线上真实对话回流评测**才能暴露生产分布的问题。A/B 双版本同时评测可直接量化「新版本是否更好」，取消机制 \+ 进度计数保证长任务可中断、可观察。

### **2\.8 多轮对话评测 MultiRoundDataEvaluationService**

**文件**: `.../agent-flow-server/.../biz/agenteval/MultiRoundDataEvaluationService.java` **行数**: 368 行

- 支持 roleB/roleC 等多角色多轮对话评测（区别于单轮 Q\&A）。

- 构建会话：把多轮文本按角色拼接成完整对话上下文，再交 LLM\-as\-judge 打分。

- `executeCustomMetricEval`：正则 `(\[[\s\n]*\{[\s\S\n]+\}[\s\n]*\])` 从模型输出中**抠出 JSON 数组**（多条打分），`reason` 截断到 200 字符。

- 同样支持 CANCELED 检查、`aggregateResultV2V3` \+ `saveV4Result` 聚合落库。

### **2\.9 面试核心问答 — 评测**

---

## **三、Sentinel 熔断降级 \(10\.8\)**

### **3\.1 Sentinel 使用全景**

```Plain Text
注解标注点（两类入口）
        │
   EntryType.IN   ← 入站: 保护自身被调用方压垮
   │    e.g. KnowledgeDatasetV2Controller#datasetHitCount
   │
   EntryType.OUT  ← 出站: 保护对下游 (模型/引擎/脚本) 的调用
        │     e.g. AgentEngineClient#flowExecute/#newChat
        │          ModelManageManageServiceImpl#queryModelListByStatus
        │          PythonScriptExecutor#executeScript
        │          MmapUtil / BigDataUtil / RagServerApi / AbtestServiceImpl / DataflowServiceImpl ...
        ▼
   Sentinel 规则命中 (降级/熔断) 时
        ▼
   两个拦截器在 HTTP 请求头打标: X_SENTINEL = ruleName + "|" + (fallback 是否配置 ? Y : N)
        ▼
   下游服务读到头 → 自动走兜底逻辑 (fallback) 或快速失败
```

### **3\.2 SentinelOkHttpInterceptor\.java**

**文件**: `.../agent-flow-common/.../config/SentinelOkHttpInterceptor.java` **行数**: 77 行 **类型**: `okhttp3.Interceptor`（用于 OkHttp 出站调用）

```Java
public class SentinelOkHttpInterceptor implements Interceptor {
    // Sentinel 未禁用时生效
    private boolean sentinelEnabled = true;   // SentinelDefine

    @Override
    public Response intercept(Chain chain) throws IOException {
        // 从当前 Sentinel 上下文取规则名
        String ruleName = ...;  // 上游 thread-local 中记录的规则
        // 是否配置了 fallback
        String fallbackVal = ...;
        if (fallbackVal != null) {
            chain.request().header(X_SENTINEL, ruleName + "|" + X_FALLBACK_YES); // "Y"
        } else {
            chain.request().header(X_SENTINEL, ruleName + "|" + X_FALLBACK_NO);  // "N"
        }
        return chain.proceed(request);
    }
}
```

### **3\.3 SentinelHutoolRequestInterceptor\.java**

**文件**: `.../agent-flow-common/.../config/SentinelHutoolRequestInterceptor.java` **行数**: 84 行 **类型**: `cn.hutool.http.HttpInterceptor`（用于 Hutool 出站调用） **差异**: 仅对 `EntryType.OUT` 生效；逻辑与 OkHttp 版相同 —— 把 `ruleName|Y/N` 写进 `X_SENTINEL` 请求头。

### **3\.4 面试要点 — Sentinel**

---

## **四、部署/性能/并发 \(10\.9\)**

### **4\.1 并发控制三板斧**

### **4\.2 性能优化手段**

- **流式 SSE 而非同步等待**：token 边生成边推送，首字延迟（TTFT）极低。

- **快速路径跳过 token 计算**：`rawTextLen < maxTokens * 0.5` 直接跳过（见 02\-03\-04 文档）。

- **评测数据走 ES 而非 MySQL**：生产对话按 agentId/version/时间范围倒序分页读，避免大表扫全量。

- **30 天滚动日志**：单文件 1024MB 自动切分，避免大文件读写性能劣化。

### **4\.3 部署与稳定性**

- `PlatformInterceptor` 在入口统一校验 platform/userid/tenantid/orgid —— 非平台/租户/组织标记的请求直接拒绝，防止跨租户数据访问。

- SSE 10 分钟超时 \+ 三回调统一收口，防止订阅表泄漏。

- 评测长任务每阶段检查 CANCELED，用户可中断。

- Sentinel 兜底 \+ `X_SENTINEL` 头透传，依赖方抖动时整体降级不雪崩。

### **4\.4 面试核心问答 — 部署/性能/并发**

---

## **五、Q33\-Q38 面试串讲（结合源码）**

### **Q33: 怎样的结构才能更好地追踪整个 Trace？日志怎么结构化？**

**三层 Trace 结构**：

1. **HTTP 层**：`PlatformInterceptor` 注入 MDC requestId/uri，`AutoAgentChatController` 兜底生成 requestId。

2. **流式层**：requestId 作为 SSE 频道键（`SSEUtils.subscribeMap`），一个会话一条流。

3. **持久化层**：`AutoAgentChatItem.trace_id = requestId`，明细表逐事件 `executeTime` \+ 原始 JSON。

**日志结构化**：`时间戳 | 级别 | 线程 | Logger | TxId : SpanId | 消息`。TxId 是请求维度、SpanId 是步骤维度；跨服务调用时 OkHttpRomoteClient 把两者写进 Header 透传，被调方写入自身 MDC —— 整条调用链在日志系统里可拼成一颗 span 树。

### **Q34: 跨 Session 是否需要记录日志系统？**

**需要，但分层记录**：

- **会话级**：`AutoAgentChat`（chat\_id）记录会话标题/创建者/runStatus/agentVersion。

- **消息级**：`AutoAgentChatItem`（chat\_item\_id \+ trace\_id）记录每条 HUMAN/AI/FAIL 消息与总耗时。

- **明细级**：`AutoAgentChatItemDetail`（item\_id \+ trace\_id \+ detailData \+ executeTime）记录每事件原始数据。

跨 Session 的用户反馈（点赞/点踩）写在同一张 item 表（`user_goodfeedback/user_badfeedback`），通过 trace\_id 与历史消息关联。任何一次线上对话都能从「点踩 → traceId → 明细事件」完整复盘。

### **Q35: 项目的评测方法是什么？**

见「二、评测体系」：RAGAS 离线指标（context\_recall \+ answer\_correctness）\+ 召回/重排 LLM\-as\-judge（relevance\_score）\+ 线上真实数据回流（ChatItemES \+ A/B \+ 多轮 roleB/C），阈值 0\.8 判定，全部结果落明细表可聚合可对比。

### **Q36: 幻觉怎么减少？**

- **评测侧**：answer\_correctness 检查答案是否与 groundTruth 一致；context\_recall 检查上下文是否覆盖所需知识。

- **检索侧**：召回/重排 relevance\_score 过滤低相关片段（0\.8 阈值）。

- **工程侧**：检索空结果三级兜底（自定义回答 → tfSwitch 兜底分支 → LLM 自主判断），引用模板 quoteTemplate 让答案基于引用生成。

### **Q37: 部署时的性能与并发控制？**

有界线程池（1000 队列 \+ AbortPolicy）、异步落库、异步日志、流式 SSE、ES 读评测数据、Sentinel 熔断 \+ fallback 透传、超时分级（SSE 10min / RAGAS 6\+10min / 模型 10min）。

### **Q38: 项目难点？**

典型的三个方向（任选，结合源码讲）：

1. **流式 SSE 与落库的一致性**：请求在流关闭后异步落库，如何保证不丢（CompletableFuture \+ 专用 executor \+ 异常兜底）。

2. **评测体系从无到有**：如何做到模型独立（裁判模型与生产模型分离）、成本可控（只评前 N）、可对比（A/B）、可中断（CANCELED）。

3. **多服务链路追踪**：requestId 三段贯通 \+ MDC TxId/SpanId 跨服务透传，遇到问题能快速定位到节点级。

