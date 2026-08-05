# 模块源码分析 09\-10：Trace/日志 \+ 评测体系 \+ Sentinel 熔断 \+ 部署性能并发

# 模块源码分析 09\-10：Trace/日志 \+ 评测体系 \+ Sentinel 熔断 \+ 部署性能并发



> 对应面试题：Q33 Trace 追踪与日志结构化 / Q34 跨 Session 日志 / Q35 评测体系 / Q36 幻觉治理 / Q37 部署性能并发 / Q38 项目难点
> 
> 前置阅读：`source-code-analysis-02-03-04`（SSE 流式与 ChatNode）、`source-code-analysis-05-08`（插件/记忆/多模态）
> 
> 



---



## 一、Trace 与日志 \(09\)



### 1\.1 核心文件清单



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



### 1\.2 三层 Trace 设计全景



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



### 1\.3 SSEUtils\.java — SSE 订阅注册表



**文件**: `.../agent-flow-serving/.../application/util/SSEUtils.java`

**行数**: 105 行



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

### 1\.4 AutoAgentChatSSEListener\.java — 事件计时 \+ traceId 落库



**文件**: `.../agent-flow-workflow/.../application/listeners/AutoAgentChatSSEListener.java`

**行数**: 544 行

**角色**: okhttp3 `EventSourceListener`，消费 RAG 服务的流式事件（function\_call / function\_response / thinkResult / finalResult）



**① 构造函数绑定 reqId**（line 71\-81）



```Java
public AutoAgentChatSSEListener(SessionUserInfo userInfo, ChatDto dto, AgentSnapshot agentSnapshot, Date startTime, CountDownLatch latch, ScheduledExecutorService scheduler) {
    this.reqId = dto.getRequestId();   // ★ 核心：reqId 来自请求 DTO
    this.chatType = dto.getDraftMode();
    this.userInfo = userInfo;
    this.dto = dto;
    this.scheduler = scheduler;
    this.latch = latch;
    this.agentSnapshot = agentSnapshot;
    this.startTime = startTime;
    this.statisticUsageService = SpringBeanUtils.getBean(StatisticAutoAgentUsageImpl.class);
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

    if (!isJsonObject(data)) {
        agentRagVO.setError_message(data);
        agentRagVO.setError_code("ATA_60000");          // ★ 错误码
        SSEUtils.pubMsg(reqId, AutoAgentChatTypeEnum.ERROR.getValue(), JSONObject.toJSONString(agentRagVO));
        ragVOLists.add(agentRagVO);
        ragMapLists.put(reqId, ragVOLists);
        return;
    }

    JsonObject jsonObject = JsonParser.parseString(data).getAsJsonObject();
    JsonElement content = null;
    if (jsonObject.has(AutoAgentChatTypeEnum.CONTENT.getValue())
            && !jsonObject.get(AutoAgentChatTypeEnum.CONTENT.getValue()).isJsonNull()) {
        content = jsonObject.get(AutoAgentChatTypeEnum.CONTENT.getValue());
    }

    // 非 JSON → 视为异常消息
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
    log.info("****** : sse close : *******");
    List<AutoAgentRagVO> ragVO = getRagVO(reqId);
    if (!CollectionUtils.isEmpty(ragVO) && !chatType) {
        CompletableFuture.runAsync(new MsxfRunnable(() -> {
            try {
                asyncSaveChat(ragVO);              // ★ 异步落库, 不阻塞返回
            } catch (Exception e) {
                log.error("save autoAgent chat data fail ", e);
            }
        }), ExecutorUtil.antoAgentChatExecutor);
    }
    if (!chatType && null != agentSnapshot) {
        CompletableFuture.runAsync(new MsxfRunnable(() -> {
            try {
                // 埋点记录智能体调用数据
                statisticUsageService.statisticAutoAgentChatUsages(userInfo, agentSnapshot, dto, startTime, ragVO);
            } catch (Exception e) {
                log.error("Failed to statistic autoagent chat data,agentId:{}, chatId: {} ",
                        agentSnapshot.getAgentId(),
                        dto.getChatId(), e);
            }
        }), ExecutorUtil.antoAgentChatExecutor);
    }
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
        .createBy(String.valueOf(userInfo.getUserId()))
        .updateBy(String.valueOf(userInfo.getUserId()))
        .userId(String.valueOf(userInfo.getUserId()))
        .userName(userInfo.getUserName())
        .createTime(new Date())
        .updateTime(new Date())
        .teamId(String.valueOf(userInfo.getOrgId()))
        .tenantId(userInfo.getTenantCode())
        .teamName(userInfo.getOrgCode())
        .globalVariables(dto.getVariables() != null ? JSON.toJSONString(dto.getVariables()) : null)
        .runningTime(totalTime)                    // ★ 整个流累计耗时
        .build();

if (CollectionUtils.isNotEmpty(dto.getFileList())) {
    itemUser.setChatFileInfo(JSON.parseArray(JSON.toJSONString(dto.getFileList())));
}
items.add(itemUser);
for (AutoAgentRagVO autoAgentRagVO : ragVO) {
    CustomMetadata customMetadata = autoAgentRagVO.getCustom_metadata();
    if (!ObjectUtils.isEmpty(customMetadata) && !ObjectUtil.isEmpty(autoAgentRagVO.getFinalResult())) {
        String itemId = autoAgentRagVO.getChatItemId();
        AutoAgentChatItem itemAi = BeanUtil.copyProperties(itemUser, AutoAgentChatItem.class);
        itemAi.setChatObj(AutoAgentChatTypeEnum.AI.getValue());
        itemAi.setChatValue(autoAgentRagVO.getFinalResult());
        itemAi.setChatItemId(itemId);
        items.add(itemAi);
    }
}
if (CollectionUtils.isNotEmpty(events)) {
    for (int i = 0; i < events.size(); i++) {
        AutoAgentChatItemDetail detail = createAutoAgentChatDetail(userInfo, traceId, chatItemId);
        detail.setDetailData(events.get(i));                    // 每事件原始 JSON
        detail.setExecuteTime(ObjectUtils.isEmpty(ragVO.get(i).getTime()) ? 0L : ragVO.get(i).getTime());   // ★ 每事件耗时 → 可做节点级耗时分
        itemDetails.add(detail);
    }
}
autoAgentChatItemService.saveBatch(items);
autoAgentChatItemDetailService.saveBatch(itemDetails);
events.clear();
```



**错误码设计**（line 106\-109, 261\-266）:



|错误码|含义|触发点|
|---|---|---|
|`ATA_60000`|响应内容非 JSON / 连接异常|`isJsonObject(data)` 为 false；`onFailure` 非响应失败|
|`ATA_70000`|HTTP 响应失败|`onFailure` 中 `response.isSuccessful()` 为 false|
|`ATA_20000`|工具级异常（可容忍，不置 runStatus=0）|`parseError` 读取服务端 error\_code|



**面试要点**: `totalTime` 是「累加相邻事件间隔」，而非简单的结束\-开始时间差 —— 原因是流式事件可能长时间无事件（如 LLM 思考中），用时间戳差值会包含静默期，而累加间隔更贴近「真实执行耗时」。每个事件在 `AutoAgentChatItemDetail` 中保留单事件耗时 \+ 原始 JSON，可支撑**逐节点耗时分分析**（Q33 的「定位慢节点」能力）。



### 1\.5 AutoAgentChatItem\.java — 链路字段



**文件**: `.../agent-flow-agents/.../domain/po/AutoAgentChatItem.java`

**行数**: 153 行



|字段|列名|说明|
|---|---|---|
|`traceId`|`trace_id`|**执行链路 Id** = 请求 requestId|
|`runningTime`|`running_time`|对话耗时（毫秒）= 事件间隔累加|
|`userGoodfeedback` / `userBadfeedback`|`user_goodfeedback` / `user_badfeedback`|点赞/点踩，`updateStrategy = FieldStrategy.IGNORED`（更新时不覆盖为 null）|
|`chatItemId`|`chat_item_id`|消息 Id，`detail.item_id` 关联明细表|



**面试要点**: 用户点踩数据与 `trace_id` 同表存储 → 线上出现坏回答时，可通过「点踩消息 → traceId → 明细表逐事件」**完整还原该次请求的每个工具调用与耗时**，是评测/复盘的数据底座。



### 1\.6 MDC 日志上下文 — PlatformInterceptor



**文件**: `.../agent-flow-server/.../interceptors/PlatformInterceptor.java`

**行数**: 76 行



```Java
public boolean preHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object o) throws Exception {
    this.initLogParams(httpServletRequest);
    // 校验平台参数
    String platformTag = httpServletRequest.getHeader("X-Platform");
    if (StringUtils.isBlank(platformTag)) {
        platformTag = httpServletRequest.getHeader("Platform");
    }
    // 平台标识不能为空
    if (StringUtils.isBlank(platformTag)) {
        throw new BusinessException(AgentFlowCommonErrorCode.AUTH_PARAM_PLATFORM_NO_EXIST);
    }
    platformTag = platformTag.replace("-","");
    // userId校验不能为空
    if (StringUtils.isBlank(httpServletRequest.getHeader("userid"))) {
        throw new BusinessException(AgentFlowCommonErrorCode.AUTH_PARAM_USER_ID_NO_EXIST);
    }
    // 平台端 无限制
    if (AgentFlowCommonConstant.PLATFORM_TAG.equalsIgnoreCase(platformTag)) {
        return true;
    }
    // 租户端
    String tenantid = httpServletRequest.getHeader("tenantid");
    if (AgentFlowCommonConstant.PLATFORM_TAG_TENANT.equalsIgnoreCase(platformTag)) {
        if (StringUtils.isBlank(tenantid)) {
            throw new BusinessException(AgentFlowCommonErrorCode.AUTH_PARAM_TENANT_ID_NO_EXIST);
        }
    }
    // 用户端
    String orgid = httpServletRequest.getHeader("orgid");
    if (AgentFlowCommonConstant.PLATFORM_TAG_ORG.equals(platformTag)) {
        if (StringUtils.isBlank(tenantid)) {
            throw new BusinessException(AgentFlowCommonErrorCode.AUTH_PARAM_TENANT_ID_NO_EXIST);
        }
        if (StringUtils.isBlank(orgid)) {
            throw new BusinessException(AgentFlowCommonErrorCode.AUTH_PARAM_ORG_ID_NO_EXIST);
        }
    }

    return true;
}

private void initLogParams(HttpServletRequest httpServletRequest) {
    String requestId = UUID.randomUUID().toString();
    MDC.put("requestId", requestId);
    MDC.put("uri", httpServletRequest.getRequestURI());
}
```



**面试要点**:

- `PlatformInterceptor` 在业务之前先 `MDC.put`，把 requestId/uri 写进当前线程的日志上下文，logback pattern 通过 `%X{requestId}` 取用（本项目 pattern 用的是框架注入的 `%X{PtxId}`/`%X{PspanId}`，见下）。

- MDC 是 **ThreadLocal 的实现**：同一请求内所有日志自动带 requestId；但异步线程（`CompletableFuture.runAsync`）**不会自动继承 MDC**，需要显式传递 —— 这是跨线程日志串联的经典坑点。

### 1\.7 分布式 Trace 字段 PtxId/PspanId



**日志 pattern**（logback\-spring\.xml line 14）:

```XML
<property name="pattern" value="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %-40.40logger{39}-[TxId : %X{PtxId} , SpanId : %X{PspanId}]- %msg%n"/>
```



**谁在填 PtxId/PspanId**（来自底层框架与数据流任务）:



|填充处|值|作用|
|---|---|---|
|数据流 Job（`KnowledgeContextModelNodeJob` 等）|`MDC.put("PtxId", datasetId)`, `MDC.put("PspanId", datasetId + "_" + contextNodeId)`|一次数据处理 = 一条 Trace，每个节点 = 一个 Span|
|`OkHttpRomoteClient`|将 MDC 中的 PtxId/PspanId 作为 HTTP Header 转发|跨服务传递链路上下文（RPC 侧）|
|`FcidEventHandler`（Lark 消息）|`MDC.put("PspanId", "Lark-MSG-" + System.currentTimeMillis())`|消息推送独立 Span|



**面试要点（Q33「日志怎么结构化」）**：日志结构 = 时间戳 \+ 级别 \+ 线程 \+ 截断的 Logger 名 \+ **TxId/SpanId 两个链路维度**。TxId 定位「是哪一条业务请求」，SpanId 定位「这条请求里的哪一个处理步骤」。多服务调用时，调用方把 TxId/SpanId 写进 HTTP Header，被调方读取后写入自己的 MDC，实现**跨服务、跨进程的整条链路可追踪**。



### 1\.8 logback\-spring\.xml — 日志落地与异步



**文件**: `.../agent-flow-server/src/main/resources/logback-spring.xml`

**行数**: 104 行



**Appender 矩阵**:



|Appender|文件|级别|特性|
|---|---|---|---|
|`STDOUT`|控制台|ALL|pattern 输出|
|`${appName}-info`|`pai-server-info-30dt.log`|INFO|滚动 30 天 \+ 单文件 1024MB 切分|
|`${appName}-error`|`pai-server-error-30dt.log`|**ERROR**|`ThresholdFilter<ERROR>`，只落 ERROR|
|`${appName}-perf`|`pai-server-perf-30dt.log`|\-|专属 Logger `LOGGER_PERFORMANCE`，`additivity=false`|
|`${appName}-info-ASYNC`|写 info 文件|INFO|**AsyncAppender 异步缓冲**|



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

### 1\.9 面试核心问答 — Trace/日志



|\#|问题|答案定位|
|---|---|---|
|1|Trace 用什么作为链路 ID？|requestId（UUID），由 Controller 兜底生成，全链路复用|
|2|requestId 如何贯穿三层？|HTTP 请求（Controller）→ SSE 频道键（SSEUtils\.addSub）→ DB trace\_id（listener\.saveItem）|
|3|SSE 订阅表用什么容器？|`ConcurrentHashMap<reqId, SseEmitter>`，三回调统一 closeSub|
|4|SSE 超时多少？|10 分钟（`DEFAULT_TIME_OUT = 10 * 60 * 1000L`）|
|5|对话总耗时怎么算？|累加相邻事件间隔 `totalTime += now - eventStartTime`，非结束减开始|
|6|单节点耗时存在哪？|`AutoAgentChatItemDetail.executeTime`，原始 JSON 存 `detailData`|
|7|日志结构化的两个维度？|`%X{PtxId}`（请求）/ `%X{PspanId}`（步骤）|
|8|MDC 在哪注入？|`PlatformInterceptor.initLogParams`：requestId \+ uri|
|9|跨服务如何传递链路？|OkHttpRomoteClient 将 PtxId/PspanId 作为 Header 转发|
|10|异步落库会不会丢数据？|`CompletableFuture.runAsync` 交给 `antoAgentChatExecutor`，异常被 catch 记录|
|11|错误码怎么分级？|ATA\_60000 非 JSON/连接异常、ATA\_70000 响应失败、ATA\_20000 工具级可容忍|
|12|为什么用 SSE 不用 WebSocket？|单向推送足够，SSE 基于 HTTP、天然复用 reqId 频道|
|13|异步日志怎么防丢失？|`discardingThreshold=0` \+ `queueSize=100000`|
|14|ERROR 日志为什么独立文件？|`ThresholdFilter<ERROR>`，定位问题不用捞 info|
|15|点踩与 Trace 怎么关联？|同一张 `auto_agent_chat_item` 表，`user_badfeedback` \+ `trace_id` 联合还原链路|



---



## 二、评测体系 \(10\.1 \- 10\.7\)



### 2\.1 评测体系全景



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



### 2\.2 RAGAS 评测 — Python 端



**文件**: `C:\Users\shilei.he\PA\af-rag-server\src\eval\ragas_eval.py`

**行数**: 171 行



```Python
class RAGASEval(BaseEval):
    def __init__(
        self,
        llm_name: str = "qwen1.5-7b",
        llm_url: str = "",
        embedding_name: str = "embedding-peg",
        embedding_url: str = "",
        metrics: Optional[List[str]] = None,
        **kwargs,
    ) -> None:
        """
        初始化 RAGASEval
        :param llm_name: 要调用的 llm 模型名称
        :param llm_url: 要调用的 llm 模型接口
        :param embedding_name: 要调用的 embedding 模型名称
        :param embedding_url: 要调用的 embedding 模型名称
        :param metrics: 评估指标 list，可选项见 all_metrics，可以为 None, 可以为空列表，可以多选
        """
        super().__init__()

        all_metrics = [
            "answer_correctness",
            "answer_relevancy",
            "answer_similarity",
            "context_precision",
            "context_recall",
            "context_relevancy",
            "faithfulness",
        ]
        default_metrics = [
            "answer_correctness",
            "answer_similarity",
            "context_recall",
            "faithfulness",
        ]
        self.set_llm(llm_name, llm_url, **kwargs)
        self.set_embedding(embedding_name, embedding_url, **kwargs)
        if metrics:
            assert isinstance(
                metrics, list
            ), f"type of parameter 'metrics' is not list."
            not_supported_ = [m for m in metrics if m not in all_metrics]
            assert (
                len(not_supported_) == 0
            ), f"not supported metric: {', '.join(not_supported_)} "
            self.metric_names = metrics
        else:
            self.metric_names = default_metrics
        self.metrics = list()
        for metric_name in self.metric_names:
            package = importlib.import_module("ragas.metrics")
            self.metrics.append(getattr(package, metric_name))

        self.metric_res = None

    def eval(self, dataset: Dict[str, List]) -> None:
        dataset = Dataset.from_dict(dataset)
        run_config = RunConfig(
            max_workers=4, max_retries=1, max_wait=600, thread_timeout=600
        )
        self.metric_res = evaluate(
            dataset=dataset,
            llm=self.llm,
            embeddings=self.embedding,
            metrics=self.metrics,
            run_config=run_config,
            raise_exceptions=False,
        ).to_pandas()

    def get_res_dict(self):
        res = self.metric_res[self.metric_names].to_dict(orient="list")
        for k, v in res.items():
            for i, score in enumerate(v):
                if np.isnan(score):
                    v[i] = -1
        return res
```



**面试要点**: 7 个候选指标、4 个默认指标，但 Java 侧只取 2 个 —— 因为 **Agent 产品形态决定指标**：`context_recall`（上下文是否覆盖答案所需知识）评估 RAG 召回，`answer_correctness`（答案是否正确）评估最终生成；其余指标（如 faithfulness、answer\_relevancy）在 Agent 场景区分度低且耗时翻倍。



### 2\.3 RAGAS 评测 — Java 端



**文件**: `.../agent-flow-server/.../biz/agenteval/RagasEvalService.java`

**行数**: 254 行



```Java
// 固定指标：上下文召回 + 答案正确性
private static final List<String> metrics = Arrays.asList("context_recall", "answer_correctness");

public void executeRagasEval(AgentEvaluationData data,
                             AgentSnapshot agent,
                             ChatOutputVO chatOutput,
                             AgentEvaluationParameterV1DTO parameterDTO,
                             SessionUserInfo userInfo) {

    ModelListVO modelListVO = qaEvalAgentChatService.queryEvalModel(parameterDTO.getLlm().getServiceUniCode(), 3, 5000, userInfo);
    List<String> contexts = getContexts(data, chatOutput, modelListVO.getChannelName());
    String answer = StrUtil.EMPTY;
    if (CollectionUtil.isNotEmpty(chatOutput.getChoices())) {
        answer = chatOutput.getChoices().get(0).getMessage().getContent();
    }
    answer = agentEvalKeywordLimitUtil.batchReplaceSensitiveWords(answer, "", modelListVO.getChannelName());

    // 执行Ragas评测
    RefereeCallResponseDTO resp = getRefereeCallResponseDTO(data, agent, parameterDTO, contexts, answer, userInfo);

    // 保存ragas评测结果
    AgentEvaluationProcessRagas processRagas = new AgentEvaluationProcessRagas();
    processRagas.setEvalId(data.getEvalId());
    processRagas.setAgentId(agent.getAgentId());
    processRagas.setAgentVersion(agent.getVersion().toString());
    processRagas.setDataItemId(data.getDataItemId());
    processRagas.setAnswer(answer);
    processRagas.setEvalStatus(AgentEvaluationStatusEnum.SUCCESS.code());
    if (Objects.nonNull(resp)) {
        if ("0".equals(resp.getCode()) && Objects.nonNull(resp.getResult())) {
            RefereeCallJudgmentResultDTO result = resp.getResult();
            String resultJson = JSONUtil.toJsonStr(result);
            String cleanResult = agentEvalKeywordLimitUtil.batchReplaceSensitiveWords(resultJson, "", modelListVO.getChannelName());
            processRagas.setJudgmentResult(cleanResult);
        }else {
            processRagas.setJudgmentResult("");
            processRagas.setEvalStatus(AgentEvaluationStatusEnum.FAILED.code());
            String failReason = StrUtil.isNotBlank(resp.getMsg()) && resp.getMsg().length() > 400 ? StrUtil.sub(resp.getMsg(), 0, 395) + " ..." : resp.getMsg();
            processRagas.setFailReason(failReason);
        }
    } else {
        processRagas.setEvalStatus(AgentEvaluationStatusEnum.FAILED.code());
        processRagas.setFailReason(MessageUtils.getMessage(MessageKeyConstants.MESSAGE_API_SERVER_RAGASEVALSERVICE_REQUEST_RAGAS_SERVICE_RETURN_IS_EMPTY));
    }
    processRagas.setChatItemId(data.getDataItemId().toString());
    ragasService.save(processRagas);
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

### 2\.4 召回/重排 LLM\-as\-judge 评测



**文件**:

- `.../biz/agenteval/RecallEvalService.java` \(221 行\)

- `.../biz/agenteval/RerankEvalService.java` \(220 行\)

两者结构完全一致，只是排序字段不同（`recallSimilarity` vs `rerankSimilarity`）。



```Java
public void executeRecallEval(SessionUserInfo userInfo, ModelListVO evalModelVO, AgentEvaluationData data, AgentSnapshot agent,String promptTemplate) {
    log.info("Execute [evalId={}, dataItemId={}, agentId={}, version={}] recall fragment evaluation", data.getEvalId(), data.getDataItemId(), agent.getAgentId(), agent.getVersion());

    AgentEvaluationDataResult dataResult = dataResultService.findDataResultByAgentIdAndDataId(data.getEvalId(), agent.getAgentId(), agent.getVersion().toString(), data.getDataItemId());
    if (dataResult == null) {
        throw new BusinessException(AgentFlowCommonErrorCode.AGENT_EVAL_NO_CHAT_RESULT);
    }
    List<AgentEvaluationProcessRecall> recallDataList = processRecallService.findRecallData(data.getEvalId(), data.getDataItemId(), agent.getAgentId(), agent.getVersion().toString());

    if (CollectionUtil.isEmpty(recallDataList)) {
        return;
    }
    // @since 4.3.x 提示词模板直接提供
    // 兼容旧模板
    String prompt = StringUtils.isBlank(promptTemplate) ? getQATestPromptFromTemplate() : promptTemplate;

    Map<String, List<AgentEvaluationProcessRecall>> evalRecallDataMap = recallDataList.stream()
            .collect(Collectors.groupingBy(AgentEvaluationProcessRecall::getRecallModelId));
    for (Map.Entry<String, List<AgentEvaluationProcessRecall>> entry : evalRecallDataMap.entrySet()) {
        // 筛选召回分数前N进行评测打分
        List<AgentEvaluationProcessRecall> evalRecallDataList = entry.getValue().stream()
                .sorted(Comparator.comparing(AgentEvaluationProcessRecall::getRecallSimilarity).reversed())
                .limit(agentEvalProperties.getRecallDataEvalLimit())
                .collect(Collectors.toList());
        for (AgentEvaluationProcessRecall recallData : evalRecallDataList) {
            if (AgentEvaluationRecallRerankStatusEnum.NOT_EVAL.code().equals(recallData.getRecallEvalStatus())) {
                executeModelChatEvalRecall(userInfo, recallData, evalModelVO, prompt, data.getQuestion(), recallData.getSegmentContent());
            }
        }
        // 非分数前N忽略评测打分
        List<Long> evalRecallIds = evalRecallDataList.stream().map(AgentEvaluationProcessRecall::getId).collect(Collectors.toList());
        for (AgentEvaluationProcessRecall recallData : recallDataList) {
            if (!evalRecallIds.contains(recallData.getId())) {
                ignoreModelChatEvalRecall(recallData);
            }
        }
    }
}
```



**单条打分**（executeModelChatEvalRecall）:

```Java
ModelParamDTO modelParamDTO = new ModelParamDTO();
ModelParamDTO.MessageItem systemMessageItem = new ModelParamDTO.MessageItem();
systemMessageItem.setRole("system");
systemMessageItem.setContent(prompt);                    // ★ 评测模板做 system
ModelParamDTO.MessageItem userMessageItem = new ModelParamDTO.MessageItem();
userMessageItem.setRole("user");
userMessageItem.setContent(StrUtil.format("query:{}, context:{}", query, context));
modelParamDTO.setModel(evalModelVO.getServiceAddressList().get(0).getAddress());
modelParamDTO.setModelName(evalModelVO.getName());
modelParamDTO.setServedModelName(evalModelVO.getServedModelName());
modelParamDTO.setStream(false);                          // ★ 非流式同步打分
modelParamDTO.setMessages(CollUtil.newArrayList(systemMessageItem, userMessageItem));
modelParamDTO.setStop(CollUtil.newArrayList("<|im_end|>"));  // 兼容特定模型的结束符
modelParamDTO.setUserInfo(userInfo);
modelParamDTO.setConnectTime(10*60*1000);
modelParamDTO.setReadTime(10*60*1000);
ModelOutputDTO modelOutputDTO = new ModelOutputDTO();
try {
    modelOutputDTO = modelManageService.modelProcess(modelParamDTO);
} catch (Exception ex) {
    log.error("[evalId={}, dataItemId={}, agentId={}, version={}, chatItemId={}, segmenteId={}] Call model eval recall score failed",
            recallData.getEvalId(), recallData.getDataItemId(),
            recallData.getAgentId(), recallData.getAgentVersion(),
            recallData.getChatItemId(), recallData.getSegmentId(),
            ex);
    processRecallService.updataRecallEvalFailed(recallData.getId(), AgentFlowCommonConstant.SCORE_FAIL);
    return;
}
List<ModelOutputDTO.ChatChoiceDTO> chatChoiceDTOList = modelOutputDTO.getChoices();
if (CollectionUtil.isEmpty(chatChoiceDTOList)) {
    processRecallService.updataRecallEvalFailed(recallData.getId(), AgentFlowCommonConstant.SCORE_FAIL);
    return;
}
ModelOutputDTO.ChatChoiceDTO chatChoiceDTO = chatChoiceDTOList.get(0);
String content = chatChoiceDTO.getMessage().getContent();
if (!JSONUtil.isJsonObj(content)) {
    log.warn("[evalId={}, dataItemId={}, agentId={}, version={}, chatItemId={}, segmenteId={}] Extract json content from recall fragment evaluation: {}",
            recallData.getEvalId(), recallData.getDataItemId(),
            recallData.getAgentId(), recallData.getAgentVersion(),
            recallData.getChatItemId(), recallData.getSegmentId(),
            content);
    content = ReUtil.getGroup1("(\\{[\\s\\S\\n]+\\})", content);  // 容错：从文本中抠出 JSON
}
if (!JSONUtil.isJsonObj(content)) {
    log.error("The evaluation recall fragment [evalId={}, dataItemId={}, agentId={}, version={}, chatItemId={}, segmenteId={}] did not obtain the correct JSON format result. The result content is: {}",
            recallData.getEvalId(), recallData.getDataItemId(),
            recallData.getAgentId(), recallData.getAgentVersion(),
            recallData.getChatItemId(), recallData.getSegmentId(),
            content);
    processRecallService.updataRecallEvalFailed(recallData.getId(), AgentFlowCommonConstant.JSON_FAIL);
    return;
}
JSONObject relevanceScoreJson = JSONUtil.parseObj(content);
BigDecimal relevanceScore = relevanceScoreJson.get("relevance_score", BigDecimal.class);
if (relevanceScore == null) {
    log.error("The evaluation recall fragment [evalId={}, dataItemId={}, agentId={}, version={}, chatItemId={}, segmenteId={}] did not receive a rating result. The result content is: {}",
            recallData.getEvalId(), recallData.getDataItemId(),
            recallData.getAgentId(), recallData.getAgentVersion(),
            recallData.getChatItemId(), recallData.getSegmentId(),
            content);
    processRecallService.updataRecallEvalFailed(recallData.getId(), AgentFlowCommonConstant.SCORE_FAIL);
    return;
}
processRecallService.updataRecallEvalScore(recallData.getId(), relevanceScore);
```



**失败标记**:

|状态|常量|触发|
|---|---|---|
|`SCORE_FAIL`|`AgentFlowCommonConstant.SCORE_FAIL`|模型调用异常 / 无 choices / relevance\_score 缺失|
|`JSON_FAIL`|`AgentFlowCommonConstant.JSON_FAIL`|响应体正则抠不出 JSON|
|null|null|非前 N 被忽略|



**面试要点**:

- **为什么只评前 N**：一次对话可能召回几十个片段，全量 LLM 打分成本太高；召回/重排关心的是「排在前面的准不准」，所以按相似度降序取前 N（默认 3）。

- **打分与检索模型隔离**：裁判模型来自 `evalModelVO`（评测参数），片段来源是 Agent 自己的召回/重排模型 —— 这就是「LLM\-as\-judge」中的 judge 独立。

- **容错三步**：非 JSON 先正则抠 `{...}`，仍非 JSON 记 JSON\_FAIL，JSON 但缺字段记 SCORE\_FAIL —— 每个失败点都有独立状态，评测报告可区分「模型挂了」还是「没解析出来」。

- 同样的模板机制：`promptTemplate` 为空时从 `AGENT_EVAL_QA_TEST_PROMPT` 用例取默认模板（兼容旧版本）。

### 2\.5 评测配置 AgentEvalProperties



**文件**: `.../agent-flow-common/.../config/AgentEvalProperties.java`

**行数**: 101 行

**前缀**: `application.agent.eval`



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



### 2\.6 评测编排 QAEvalAgentChatService



**文件**: `.../agent-flow-server/.../biz/agenteval/QAEvalAgentChatService.java`

**行数**: 435 行



**① 评测时调用 Agent 引擎**（executeAgentChatCompletions）:

```Java
private ChatOutputVO executeAgentChatCompletions(SessionUserInfo userInfo, AgentEvaluationData data, AgentSnapshot agent, Boolean saveRecallData) {
    log.info("Execute evaluation task [evalId={}, dataItemId={}], execute agent chat", data.getEvalId(), data.getDataItemId());

    WorkFlowParamDTO param = buildRequestParam(userInfo, data, agent);
    String url = agentEngineUrl + "/api/v1/chat/completions";
    HttpRequest httpRequest = HttpUtil.createPost(url).header(LanguageConstants.LANGUAGE_HTTP_HEADER, LocaleContextHolder.getLocale().toString());
    String bodyString = JSON.toJSONString(param, SerializerFeature.DisableCircularReferenceDetect);
    log.info("Execute evaluation task [evalId={}, dataItemId={}] Start agent chat, request: {}", data.getEvalId(), data.getDataItemId(), bodyString);
    httpRequest.body(bodyString);
    Map<String, String> headers = new HashMap<>();
    headers.put("orgid", userInfo.getOrgId() == null ? null : userInfo.getOrgId().toString());
    headers.put("orgcode", userInfo.getOrgCode());
    headers.put("userid", userInfo.getUserId() == null ? null : userInfo.getUserId().toString());
    headers.put("username", userInfo.getUserName());
    headers.put("tenantid", userInfo.getTenantCode());
    httpRequest.addHeaders(headers);
    httpRequest.setConnectionTimeout(10*60*1000);
    httpRequest.setReadTimeout(10*60*1000);

    Date startTime = new Date();
    Date endTime = new Date();

    ChatOutputVO chatOutput = null;

    try (HttpResponse httpResponse = httpRequest.execute()) {
        endTime = new Date();
        if (!httpResponse.isOk()) {
            log.error("Evaluation agent chat call failed [evalId={}, dataItemId={}], response status={}, error message: {}",
                    data.getEvalId(),
                    data.getDataItemId(),
                    httpResponse.getStatus(),
                    httpResponse.body());
            saveEvalDataResult(data, agent, param.getChatId(), param.getResponseChatItemId(), startTime, endTime, chatOutput, false, AgentFlowCommonConstant.CAHT_ERR);
            return null;
        }
        if (JSONUtil.isJsonObj(httpResponse.body())) {
            chatOutput = JSONUtil.toBean(httpResponse.body(), ChatOutputVO.class);
        }
        if (chatOutput == null) {
            log.error("Evaluation agent chat call failed [evalId={}, dataItemId={}], response status={}, body: {}",
                    data.getEvalId(),
                    data.getDataItemId(),
                    httpResponse.getStatus(),
                    httpResponse.body());
            saveEvalDataResult(data, agent, param.getChatId(), param.getResponseChatItemId(), startTime, endTime, chatOutput, false, AgentFlowCommonConstant.CAHT_ERR);
            return null;
        }
        saveEvalDataResult(data, agent, param.getChatId(), param.getResponseChatItemId(), startTime, endTime, chatOutput, true, "");
        if (saveRecallData) {
            saveRecallRerankData(data, agent, param.getResponseChatItemId(), chatOutput);
        }
        log.info("Execute evaluation task [evalId={}] Execute agent chat [dataItemId={}] Completed", data.getEvalId(), data.getDataItemId());
    } catch (Exception e) {
        log.error("Evaluating agent dialogue call failure [evalId={}, dataItemId={}]", agent.getAgentId(), data.getDataItemId(), e);
        saveEvalDataResult(data, agent, param.getChatId(), param.getResponseChatItemId(), startTime, endTime, chatOutput, false, AgentFlowCommonConstant.CAHT_ERR);
        return null;
    }
    return chatOutput;
}
```



**请求参数构建**（buildRequestParam）—— 关键在 `source = EVALUATION`，让引擎侧区分「评测调用」不记用户会话:

```Java
private WorkFlowParamDTO buildRequestParam(SessionUserInfo userInfo, AgentEvaluationData data, AgentSnapshot agent) {

    String reqId = IdUtil.fastSimpleUUID();
    String chatId = IdUtil.fastSimpleUUID();
    String chatItemIdQuestion = IdUtil.fastSimpleUUID();
    String chatItemIdAnswer = IdUtil.fastSimpleUUID();
    List<MessageItemDTO> messages = new ArrayList<>();
    MessageItemDTO itemDTO = new MessageItemDTO();
    itemDTO.setRole("user");
    itemDTO.setDataId(chatItemIdQuestion);
    itemDTO.setContent(data.getQuestion());
    messages.add(itemDTO);

    WorkFlowParamDTO param = new WorkFlowParamDTO();
    param.setStream(false);
    param.setShare(false);
    param.setChatId(chatId);
    param.setResponseChatItemId(chatItemIdAnswer);
    param.setAppId(agent.getAgentId());
    param.setMessages(messages);
    param.setReqId(reqId);
    param.setDetail(true);
    param.setUserInfo(userInfo);
    param.setSource(ChatSourceEnum.EVALUATION.code());
    param.setVersion(agent.getVersion());
    param.setAppId(agent.getAgentId());
    try {
        Map<String, Object> variables = JSON.parseObject(data.getGlobalVars(), Map.class);
        if (null != variables) {
            param.setVariables(variables);
        }
    } catch (Exception e) {
        log.warn("The global variable is not in JSON format, please check the original file [evalId={}, dataItemId={}]", data.getEvalId(), data.getDataItemId());
    }
    return param;
}
```



**② 裁判模型查询重试**（防模型服务抖动导致评测误失败）:

```Java
public ModelListVO queryEvalModel(String llmServiceUniCode, int retryTimes, long retryInterval, SessionUserInfo userInfo) {
    ModelListParamDTO modelListParamDTO = new ModelListParamDTO();
    modelListParamDTO.setStatus(0);
    modelListParamDTO.setType(0);
    modelListParamDTO.setUserInfo(userInfo);
    ModelListVO modelListVO = null;
    int retryCount = 0;
    while (retryCount < retryTimes + 1) {
        retryCount++;
        List<ModelListVO> modelListVOS = modelService.queryModelList(modelListParamDTO);
        modelListVO = modelListVOS.stream().filter(m -> llmServiceUniCode.equals(m.getServiceUniCode())).findFirst().orElse(null);
        if (null != modelListVO) {
            break;
        }
        try {
            Thread.sleep(retryInterval);
            log.info("Try again {} times to obtain evaluation model information based on the model serviceUniCode [llmServiceUniCode={}]", retryCount, llmServiceUniCode);
        } catch (InterruptedException e) { }
    }
    if (modelListVO == null) {
        throw new BusinessException(AgentFlowCommonErrorCode.AGENT_EVAL_LLM_NOT_EXISTS);
    }
    return modelListVO;
}
```



**③ 统一线程池工厂**:

```Java
public static ThreadPoolExecutor createEvalExecutor(Integer coolPoolSize, Integer maxPoolSize, String namePrefix) {
    String threadNamePrefix = StrUtil.isBlank(namePrefix) ? "eval-pool-" : namePrefix;
    ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(coolPoolSize,
            maxPoolSize, 60,
            TimeUnit.SECONDS, new LinkedBlockingDeque<>(1000),      // 有界队列 1000
            ThreadFactoryBuilder.create().setNamePrefix(threadNamePrefix).build(),
            new ThreadPoolExecutor.AbortPolicy());                  // 满了抛异常, 快速失败
    return threadPoolExecutor;
}
```



**面试要点**:

- `source=EVALUATION`：评测请求与真实用户请求在引擎侧区分，**不污染用户对话记录、不计入生产埋点**。

- `AbortPolicy + 有界队列 1000`：评测任务量大且可预测，宁可快速失败也不无限堆积 —— 避免评测任务把内存打爆。

### 2\.7 线上真实数据评测 ProductionDataEvaluationService



**文件**: `.../agent-flow-server/.../biz/agenteval/ProductionDataEvaluationService.java`

**行数**: 417 行



**核心差异：数据来源是生产 ES，不是人工数据集**



```Java
// 生产数据从 ES 读取 (ChatItemES)，而不是 MySQL 数据集 —— 注释掉的旧 SQL 版可见迁移意图
private List<ChatItem> findChatItemAIData(AgentEvaluationAgentDTO agentDTO, AgentSnapshot agentSnapshot) {
//        LambdaQueryWrapper<ChatItem> chatItemQueryWrapper = new LambdaQueryWrapper<ChatItem>()
//                .eq(ChatItem::getAgentId, agentSnapshot.getAgentId())
//                .eq(ChatItem::getAgentVersion, agentSnapshot.getVersion())
//                .eq(ChatItem::getObj, ChatRoleEnum.AI.getValue())
//                .ge(ChatItem::getCtime, agentDTO.getDataStartTime())
//                .le(ChatItem::getCtime, agentDTO.getDataEndTime())
//                .isNotNull(ChatItem::getDataId)
//                .orderByAsc(ChatItem::getTime)
//                .last(StrUtil.format("limit {}", agentDTO.getDataAmount()));
//        return chatItemService.list(chatItemQueryWrapper);
    ChatItemESQueryDTO dto = new ChatItemESQueryDTO();
    dto.setAgentId(agentSnapshot.getAgentId());
    dto.setAgentVersion(agentSnapshot.getVersion());
    dto.setDateStart(agentDTO.getDataStartTime().getTime());
    dto.setDateEnd(agentDTO.getDataEndTime().getTime());
    dto.setPageSize(agentDTO.getDataAmount());
    dto.setOrderBy("time");
    List<ChatItemES> es = chatItemESService.findES(dto);
    return chatItemES2ChatItem(es);
}

private List<ChatItem> findChatItemHumanData(AgentEvaluationAgentDTO agentDTO, AgentSnapshot agentSnapshot, List<String> dataIds) {
    ChatItemESQueryDTO dto = new ChatItemESQueryDTO();
    dto.setAgentId(agentSnapshot.getAgentId());
    dto.setAgentVersion(agentSnapshot.getVersion());
    dto.setDateStart(agentDTO.getDataStartTime().getTime());
    dto.setDateEnd(agentDTO.getDataEndTime().getTime());
    dto.setOrderBy("time");
    List<ChatItemES> es = chatItemESService.findChatItemESHumanData(dto,dataIds);
    return chatItemES2ChatItem(es);
}

// HUMAN/AI 按 dataId 配对成一条评测样本
List<String> dataIds = chatItemAIDataList.stream().map(ChatItem::getDataId).collect(Collectors.toList());
List<ChatItem> chatItemHumanDataList = this.findChatItemHumanData(agentParam, agentSnapshot, dataIds);
Map<String, List<ChatItem>> chatItemHumanDataMap = chatItemAIDataList.stream()
        .collect(Collectors.groupingBy(ChatItem::getDataId));
for (ChatItem chatItemAI : chatItemAIDataList) {
    if (chatItemHumanDataMap.containsKey(chatItemAI.getDataId())) {
        ChatItem chatItemHumanData = chatItemHumanDataMap.get(chatItemAI.getDataId()).get(0);
        // 以 HUMAN 侧为准建评测样本，AI 侧取答案/耗时 → 一条 dataId = 一条样本
    }
}
// 多 Agent A/B 评测: evalAgentInfoMap 可同时放新旧两个版本（agentId+version），逐条对比
```



**线程池与取消机制**:

```Java
int totalEvalData = dataList.size();
AtomicInteger completedEvalDataCounter = new AtomicInteger(0);

ThreadPoolExecutor evalExecutor = QAEvalAgentChatService.createEvalExecutor(agentEvalProperties.getProdPoolSize(), agentEvalProperties.getProdPoolSize(), "prod-eval-");
List<Future<?>> futures = new ArrayList<>();
for (Map.Entry<AgentEvaluationData, AgentEvaluationDataResult> dataEntry : dataToResultMap.entrySet()) {

    Future<?> future = evalExecutor.submit(() -> {
        try {
           AgentEvaluationStatusEnum evalStatusBeforeMetric = agentEvaluationService.getAgentEvaluationStatusByEvalId(agentEvaluation.getEvalId());
            log.info("The Production Agent Evaluation {} status {} before calc", agentEvaluation.getEvalId(), evalStatusBeforeMetric.getCode());
            if (AgentEvaluationStatusEnum.CANCELED.equals(evalStatusBeforeMetric)){
                log.info("The Production Agent Evaluation {} canceled, task will returned", agentEvaluation.getEvalId());
                return;
            }
            this.executeCustomMetricEval(userInfo, parameterDTO, evalModelVO, dataEntry.getKey(), dataEntry.getValue());
            // 计算完成进度
            completedEvalDataCounter.getAndIncrement();
            int progress = completedEvalDataCounter.get() * 100 / totalEvalData;
            agentEvaluation.setCompleteCount(completedEvalDataCounter.get());
            agentEvaluation.setProgress(progress);
            agentEvaluationService.updateAgentEvaluationByEvalIdIfStatusNotCancel(agentEvaluation);
        } catch (Exception ex) {
            log.error("Execute custom evaluation exception [evalId={}, dataItemId={}, agentId={}, version={}]:",
                    dataEntry.getKey().getEvalId(), dataEntry.getKey().getDataItemId(),
                    dataEntry.getValue().getAgentId(), dataEntry.getValue().getAgentVersion(),
                    ex);
        }
    });
    futures.add(future);
}
for (Future<?> f : futures) {
    try {
        f.get();
    } catch (Exception e) {
        log.error("Execute custom evaluation exception:", e);
    }
}
```



**面试要点**: 线下数据集评测只能发现「数据集覆盖到的问题」，**线上真实对话回流评测**才能暴露生产分布的问题。A/B 双版本同时评测可直接量化「新版本是否更好」，取消机制 \+ 进度计数保证长任务可中断、可观察。



### 2\.8 多轮对话评测 MultiRoundDataEvaluationService



**文件**: `.../agent-flow-server/.../biz/agenteval/MultiRoundDataEvaluationService.java`

**行数**: 368 行



- 支持 roleB/roleC 等多角色多轮对话评测（区别于单轮 Q\&A）。

- 构建会话：把多轮文本按角色拼接成完整对话上下文，再交 LLM\-as\-judge 打分。

- `executeCustomMetricEval`：正则 `(\[[\s\n]*\{[\s\S\n]+\}[\s\n]*\])` 从模型输出中**抠出 JSON 数组**（多条打分），`reason` 截断到 200 字符。

- 同样支持 CANCELED 检查、`aggregateResultV2V3` \+ `saveV4Result` 聚合落库。

### 2\.9 面试核心问答 — 评测



|\#|问题|答案定位|
|---|---|---|
|1|RAGAS 用了哪些指标？|只取 `context_recall` \+ `answer_correctness`（Python 侧定义 7 个）|
|2|为什么只取 2 个指标？|匹配 Agent 形态：召回覆盖率 \+ 答案正确性，其余区分度低且耗时翻倍|
|3|评测裁判模型哪来的？|评测参数 `llmType/llmUrl/embeddingType/embeddingUrl`，与 Agent 生产模型分离|
|4|RAGAS 怎么调用？|POST `/rag_algorithm/ragas_evaluate`，连接 6min/读 10min，Org\-Code 租户头|
|5|context 怎么构造？|取最后 CHAT\_NODE 的 quoteList，格式化为 `question:\nanswer:`|
|6|召回/重排评测怎么打分？|LLM\-as\-judge，system=评测模板，user=`query:{}, context:{}`，非流式|
|7|为什么只评前 N？|成本控制 \+ 只看排前面的准不准，默认 3（1\-20 可配）|
|8|解析失败的容错链？|非 JSON → 正则抠 `{...}` → 仍失败记 JSON\_FAIL，缺字段记 SCORE\_FAIL|
|9|评测线程池怎么设计？|有界队列 1000 \+ AbortPolicy \+ 60s keepalive，参数化池大小|
|10|线上评测数据从哪来？|生产 ChatItemES（ES），按 agentId/version/日期范围 \+ dataId 配对 HUMAN/AI|
|11|怎么对比新旧版本？|多 Agent A/B 评测（evalAgentInfoMap 同时跑）|
|12|长任务怎么防挂死？|每阶段检查 CANCELED \+ AtomicInteger 进度|
|13|多轮对话怎么评测？|roleB/roleC 多角色拼接上下文，自定义指标正则抠 JSON 数组|
|14|结果怎么判定过不过？|阈值 0\.8：answerCorrectness/contextRecall/recallChunk/rerankChunk|
|15|敏感词怎么处理？|`batchReplaceSensitiveWords` 对答案和判定结果都脱敏后再入库|



---



## 三、Sentinel 熔断降级 \(10\.8\)



### 3\.1 Sentinel 使用全景



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



### 3\.2 SentinelOkHttpInterceptor\.java



**文件**: `.../agent-flow-common/.../config/SentinelOkHttpInterceptor.java`

**行数**: 77 行

**类型**: `okhttp3.Interceptor`（用于 OkHttp 出站调用）



```Java
public class SentinelOkHttpInterceptor implements Interceptor {

    /**
     * 有业务降级
     */
    public static final String X_FALLBACK_YES = "Y";

    /**
     * 无业务降级
     */
    public static final String X_FALLBACK_NO = "N";

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request okRequest = chain.request();
        Request cumstomRequest = null;

        final Context c = ContextUtil.getContext();
        if (c != null) {
            ResourceWrapper resourceWrapper = c.getCurEntry().getResourceWrapper();
            if (resourceWrapper instanceof SentinelDefineGetter) {
                SentinelDefine sentinelDefine = ((SentinelDefineGetter) resourceWrapper).getSentinelDefine();
                if (sentinelDefine != null) {
                    if (!sentinelDefine.isDisabled()) {
                        String degradeFlag = okRequest.headers().get(X_SENTINEL);
                        if (!StringUtils.hasText(degradeFlag)) {
                            cumstomRequest = okRequest.newBuilder().addHeader(X_SENTINEL, toRuleName(sentinelDefine.getRuleName()) + "|" + toHeaderVal(sentinelDefine.getFallback())).build();
                            if(log.isDebugEnabled()){
                                log.debug("熔断标识正常url={}", okRequest.url());
                            }
                        }
                    }
                }
            }
        }
        if (cumstomRequest != null) {
            return chain.proceed(cumstomRequest);
        }
        return chain.proceed(okRequest);
    }


    private static String toRuleName(String name) {
        return name == null || name.length() == 0 ? "-" : name;
    }

    private static String toHeaderVal(String fallback) {
        return (fallback != null && fallback.length() > 0) ? X_FALLBACK_YES : X_FALLBACK_NO;
    }
}
```



### 3\.3 SentinelHutoolRequestInterceptor\.java



**文件**: `.../agent-flow-common/.../config/SentinelHutoolRequestInterceptor.java`

**行数**: 84 行

**类型**: `cn.hutool.http.HttpInterceptor`（用于 Hutool 出站调用）

**差异**: 仅对 `EntryType.OUT` 生效；逻辑与 OkHttp 版相同 —— 把 `ruleName|Y/N` 写进 `X_SENTINEL` 请求头。



### 3\.4 面试要点 — Sentinel



|\#|问题|答案|
|---|---|---|
|1|熔断用在什么场景？|Agent 依赖大量外部服务（模型服务/知识引擎/脚本/大数据），任一抖动会拖垮整体|
|2|IN 与 OUT 怎么区分？|IN 保护自己被调（Controller 层），OUT 保护对下游的调用（Client/工具层）|
|3|降级怎么向下游透传？|\`X\_SENTINEL = ruleName \+ "|
|4|两套拦截器为什么并存？|OkHttp 通道 \+ Hutool 通道覆盖不同 HTTP 客户端，规则一致|
|5|fallback 标记放头部而非 body？|Header 不参与序列化、下游网关可直接读，零解析成本|
|6|开关在哪？|`SentinelDefine` 可整体禁用，控制台动态调整|



---



## 四、部署/性能/并发 \(10\.9\)



### 4\.1 并发控制三板斧



|手段|实现|解决什么问题|
|---|---|---|
|有界线程池|`LinkedBlockingDeque(1000)` \+ `AbortPolicy` \+ 60s keepalive|评测/批量任务不无限堆积内存|
|异步落库|`CompletableFuture.runAsync(..., ExecutorUtil.antoAgentChatExecutor)`|对话主链路不被 DB 写阻塞|
|异步日志|AsyncAppender `discardingThreshold=0` \+ `queueSize=100000`|日志 I/O 不拖慢业务线程|



### 4\.2 性能优化手段



- **流式 SSE 而非同步等待**：token 边生成边推送，首字延迟（TTFT）极低。

- **快速路径跳过 token 计算**：`rawTextLen < maxTokens * 0.5` 直接跳过（见 02\-03\-04 文档）。

- **评测数据走 ES 而非 MySQL**：生产对话按 agentId/version/时间范围倒序分页读，避免大表扫全量。

- **30 天滚动日志**：单文件 1024MB 自动切分，避免大文件读写性能劣化。

### 4\.3 部署与稳定性



- `PlatformInterceptor` 在入口统一校验 platform/userid/tenantid/orgid —— 非平台/租户/组织标记的请求直接拒绝，防止跨租户数据访问。

- SSE 10 分钟超时 \+ 三回调统一收口，防止订阅表泄漏。

- 评测长任务每阶段检查 CANCELED，用户可中断。

- Sentinel 兜底 \+ `X_SENTINEL` 头透传，依赖方抖动时整体降级不雪崩。

### 4\.4 面试核心问答 — 部署/性能/并发



|\#|问题|答案定位|
|---|---|---|
|1|Agent 并发瓶颈在哪？|依赖外部模型服务 \+ 检索服务，本地线程池为有界队列 \+ 快速失败|
|2|为什么评测用 AbortPolicy？|任务量大且可预测，满了快速失败避免内存打爆|
|3|对话耗时为什么用累加间隔？|流式静默期不含真实执行，累加更准|
|4|日志异步会不会丢？|discardingThreshold=0 不丢，极端高并发才可能阻塞|
|5|怎么保证不跨租户？|PlatformInterceptor 统一校验 \+ 所有查询带 tenantId/orgId 过滤|
|6|模型服务抖动怎么办？|Sentinel 熔断 \+ fallback 头透传 \+ 裁判模型查询重试 3 次|
|7|大数据量对话怎么读？|ES 分页 \+ orderBy time 倒序，只读需要的字段|
|8|SSE 连接泄漏怎么防？|onError/onTimeout/onCompletion 统一 closeSub 移除|
|9|部署时的典型问题？|服务间依赖多（模型/检索/评测/大数据），靠熔断 \+ 重试 \+ 超时分级兜底|



---



## 五、Q33\-Q38 面试串讲（结合源码）



<a id="q33"></a>
### Q33: 怎样的结构才能更好地追踪整个 Trace？日志怎么结构化？



**三层 Trace 结构**：

1. **HTTP 层**：`PlatformInterceptor` 注入 MDC requestId/uri，`AutoAgentChatController` 兜底生成 requestId。

2. **流式层**：requestId 作为 SSE 频道键（`SSEUtils.subscribeMap`），一个会话一条流。

3. **持久化层**：`AutoAgentChatItem.trace_id = requestId`，明细表逐事件 `executeTime` \+ 原始 JSON。

**日志结构化**：`时间戳 | 级别 | 线程 | Logger | TxId : SpanId | 消息`。TxId 是请求维度、SpanId 是步骤维度；跨服务调用时 OkHttpRomoteClient 把两者写进 Header 透传，被调方写入自身 MDC —— 整条调用链在日志系统里可拼成一颗 span 树。



<a id="q34"></a>
### Q34: 跨 Session 是否需要记录日志系统？



**需要，但分层记录**：

- **会话级**：`AutoAgentChat`（chat\_id）记录会话标题/创建者/runStatus/agentVersion。

- **消息级**：`AutoAgentChatItem`（chat\_item\_id \+ trace\_id）记录每条 HUMAN/AI/FAIL 消息与总耗时。

- **明细级**：`AutoAgentChatItemDetail`（item\_id \+ trace\_id \+ detailData \+ executeTime）记录每事件原始数据。

跨 Session 的用户反馈（点赞/点踩）写在同一张 item 表（`user_goodfeedback/user_badfeedback`），通过 trace\_id 与历史消息关联。任何一次线上对话都能从「点踩 → traceId → 明细事件」完整复盘。



<a id="q35"></a>
### Q35: 项目的评测方法是什么？



见「二、评测体系」：RAGAS 离线指标（context\_recall \+ answer\_correctness）\+ 召回/重排 LLM\-as\-judge（relevance\_score）\+ 线上真实数据回流（ChatItemES \+ A/B \+ 多轮 roleB/C），阈值 0\.8 判定，全部结果落明细表可聚合可对比。



<a id="q36"></a>
### Q36: 幻觉怎么减少？



- **评测侧**：answer\_correctness 检查答案是否与 groundTruth 一致；context\_recall 检查上下文是否覆盖所需知识。

- **检索侧**：召回/重排 relevance\_score 过滤低相关片段（0\.8 阈值）。

- **工程侧**：检索空结果三级兜底（自定义回答 → tfSwitch 兜底分支 → LLM 自主判断），引用模板 quoteTemplate 让答案基于引用生成。

<a id="q37"></a>
### Q37: 部署时的性能与并发控制？



有界线程池（1000 队列 \+ AbortPolicy）、异步落库、异步日志、流式 SSE、ES 读评测数据、Sentinel 熔断 \+ fallback 透传、超时分级（SSE 10min / RAGAS 6\+10min / 模型 10min）。



<a id="q38"></a>
### Q38: 项目难点？



典型的三个方向（任选，结合源码讲）：

1. **流式 SSE 与落库的一致性**：请求在流关闭后异步落库，如何保证不丢（CompletableFuture \+ 专用 executor \+ 异常兜底）。

2. **评测体系从无到有**：如何做到模型独立（裁判模型与生产模型分离）、成本可控（只评前 N）、可对比（A/B）、可中断（CANCELED）。

3. **多服务链路追踪**：requestId 三段贯通 \+ MDC TxId/SpanId 跨服务透传，遇到问题能快速定位到节点级。

