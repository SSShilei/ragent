# PowerAgent 简历 Q&A（0826 补充：SSE 流式专题）

> 2026-08-26 补充：SSE 流式实现原理、面试追问、输出完成性保证、监控方案。
> 与 `poweragent-resume-qa-0820.md`（tool_calls/长期记忆/断点续传）互补，本文件聚焦 SSE 流式传输。

---

<a id="q1"></a>
## Q1: SSE 流式怎么实现的？

### 底层机制：Servlet 3.1 异步 + SseEmitter

```
用户请求 → Controller 返回 SseEmitter（不是直接返回响应体）
  → Spring 用 Servlet 3.1 AsyncContext 挂起连接
  → 请求线程释放，Tomcat 工作线程不被占用
  → 后续任意线程往 emitter.send() 写数据 → 推给客户端
  → 连接保持，直到 complete() / 超时 / 出错
```

```java
// RAGChatController
@GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
public SseEmitter chat(@RequestParam String question, ...) {
    SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());  // 5 分钟
    ragChatService.streamChat(question, conversationId, deepThinking, emitter);
    return emitter;  // 立即返回，请求线程释放
}
```

**关键**：Controller 返回 `SseEmitter` 后**立即返回**，真正的流式输出由异步线程（模型流式回调）往 emitter 写。

### SSE 协议格式

```
服务端推给客户端的是文本流，每条事件格式:

event: message\ndata: {"type":"response","delta":"你好"}\n\n

event: 事件名（客户端据此分流）
data:  JSON 数据
空行:  事件分隔符（\n\n）
```

### 事件协议（`SSEEventType`）

| event | data | 时机 |
|---|---|---|
| `meta` | `{conversationId, taskId}` | 初始化 |
| `message` | `{type: "think"\|"response", delta}` | 流式内容 |
| `finish` | `{messageId, title}` | 生成完成 |
| `done` | `"[DONE]"` | 流结束 |
| `cancel` | `{messageId, title}` | 用户取消 |
| `reject` | `{type:"response", delta:"系统繁忙"}` | 限流拒绝 |

### SseEmitterSender 线程安全封装

```java
public class SseEmitterSender {
    private final AtomicBoolean closed = new AtomicBoolean(false);  // ★ 只关一次

    public void sendEvent(String eventName, Object data) {
        if (closed.get()) return;              // 已关闭不再发
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            fail(e);                            // 发送失败 → 标记关闭
        }
    }

    public void complete() {
        if (closed.compareAndSet(false, true)) {   // CAS 保证只 complete 一次
            emitter.complete();
        }
    }
}
```

**为什么用 AtomicBoolean**：LLM 回调线程和超时线程可能同时触发 complete()，不加保护会重复关闭抛异常。

### 完整流式链路

```
Controller 返回 SseEmitter
    │
    ▼
ChatQueueLimiter 排队（限流）
    │ 拿到执行权
    ▼
StreamChatPipeline 7 步（记忆→改写→意图→检索→组装）
    │
    ▼
llmService.streamChat(request, callback)   ← 传入回调
    │
    ▼
RoutingLLMService → ProbeStreamBridge（首包探测）
    │
    ▼
LLM 流式响应（OkHttp SSE 解析）
    │ 逐 token 回调
    ▼
StreamChatEventHandler.onContent()
    ├─ 按 messageChunkSize 切块
    └─ sender.sendEvent("message", {type:"response", delta})
    │
    ▼ 生成完成
onComplete()
    ├─ 落库（memoryService.append）
    ├─ sender.sendEvent("finish", {messageId, title})
    ├─ sender.sendEvent("done", "[DONE]")
    └─ sender.complete()
```

---

<a id="q2"></a>
## Q2: 面试追问及回答

### 追问 1：为什么用 SseEmitter 不用 WebFlux 的 Flux?

```
项目是 Servlet 栈（spring-boot-starter-web + Tomcat）
  → SseEmitter 基于 Servlet 3.1 AsyncContext，够用
  → 换 WebFlux = Tomcat→Netty + 全栈 Reactive 改造（Spring Security、MyBatis 全要换）
  → LLM 流式场景两者效果无差别
```

### 追问 2：请求线程会不会被阻塞？

```
不会。Controller 返回 SseEmitter 后立即释放请求线程
  → AsyncContext 挂起连接，Tomcat 工作线程复用
  → 后续 LLM 回调线程往 emitter 写
  → 这就是"异步化"——高并发下不占线程
```

### 追问 3：并发写 emitter 会怎样？线程安全吗？

```
LLM 回调线程、超时线程、取消线程可能同时操作 emitter
  → SseEmitter 本身非线程安全
  → 用 AtomicBoolean closed 保护：CAS 保证 complete() 只执行一次
  → sendEvent 前检查 closed，已关闭不再写
```

### 追问 4：客户端断开怎么办？

```
客户端断开 → emitter.send() 抛异常（ClientAbortException / Broken pipe）
  → SseEmitterSender.fail() → completeWithError → 标记 closed
  → 后续不再发
  → StreamTaskManager 取消 LLM 调用（省 token，不浪费推理）

关键: 断开的"断管道"是预期行为，不是错误
  → GlobalExceptionHandler 应静默处理 ClientAbortException（不加 ERROR 日志）
```

### 追问 5：连接超时怎么办？

```
SseEmitter 构造时传超时（5 分钟）
  → 超时 → emitter.onTimeout → SseEmitterSender 标记 closed
  → 触发 LLM 取消（StreamTaskManager）
```

### 追问 6：SSE 是单向的，怎么支持"停止生成"？

```
SSE 只能服务端→客户端
  → 停止用独立的 POST /rag/v3/stop?taskId=xxx
  → 后端 StreamTaskManager:
      Redis 标记取消 + RTopic 广播 → 各节点取消 LLM 调用
      → 取消时保存已生成内容 → 发 cancel + done 事件
```

### 追问 7：消息怎么切块的？为什么？

```
messageChunkSize 配置（默认 1）
  → LLM 回调 onContent 每段 → 按块大小切 → 发 message 事件
  → 控制前端渲染频率（1 的话逐字，N 的话逐段）
```

### 追问 8：断线重连怎么做的？

```
前端 useStreamResponse:
  fetch + ReadableStream 读 SSE
  → 失败 → 指数退避重连（600ms → 1200ms，最多 2 次）
  → 但后端没有"根据 taskId 恢复流"（checkpoint 缺口）
  → 重连 = 发新请求 = 新对话（现状）
```

### 追问 9：和 WebSocket 的区别？为什么选 SSE？

| | SSE | WebSocket |
|---|---|---|
| 方向 | 单向（服务端→客户端） | 双向 |
| 协议 | HTTP 原生 | 独立协议 |
| 自动重连 | ✅ 原生 | ❌ 自己实现 |
| 适用 | 推送流式文本（LLM 生成） | 双向交互（聊天室/游戏） |
| 复杂度 | 低 | 高 |

**LLM 流式是纯推送，SSE 足够**，且自动重连、HTTP 兼容（好过代理）是额外优势。

### 追问 10：跨节点/负载均衡下 SSE 怎么处理？

```
问题: SSE 连接绑死实例（连接建立在哪台，推送就在哪台）
  → 负载均衡要配 sticky session（粘性会话），否则轮询会把请求打到别的实例

ragent 的分布式能力:
  StreamTaskManager 用 Redis + RTopic 实现跨节点取消
  → 但 SSE 推送本身还是绑死实例（实例挂了连接断，重连是新请求）
```

---

<a id="q3"></a>
## Q3: SSE 输出完成性如何保证？

### 核心原则：用"明确信号"定义完成，不靠"猜"

```
SSE 流式输出没有"关闭连接 = 完成"这种隐含语义
  → 必须用显式的 done 事件标记"我推完了"
  → 前端收到 done 才算完成，连接断开不算
```

### 完成序列（保证完成性）

```java
// StreamChatEventHandler.onComplete()
void onComplete() {
    // ① 先落库（回答持久化到 t_message）
    memoryService.append(conversationId, userId, assistant_message);

    // ② 再发 finish（带 messageId + title）
    sender.sendEvent(FINISH, new CompletionPayload(messageId, title));

    // ③ 最后发 done（明确完成信号）
    sender.sendEvent(DONE, "[DONE]");

    // ④ 关闭连接
    sender.complete();
}
```

**关键设计：先落库，再发完成信号**。

```
如果: 落库成功 → 发 done → 关闭          = 完整完成 ✅
如果: 落库成功 → 发 done 前实例挂          = 库里已有完整回答，前端重连可查到 ✅（不丢）
如果: 生成一半实例挂（未到 onComplete）    = 部分回答已落库（StreamTaskManager 取消回调）✅（不丢，但可能不完整）
```

### 完成性的三个保护层

| 层 | 机制 | 保证什么 |
|---|---|---|
| **落库先行** | onComplete 先 append 落库，再发 finish/done | 内容持久化，不因网络/连接问题丢失 |
| **取消保存** | StreamTaskManager 取消时回调保存已生成内容 | 用户停止/断连时部分回答不丢 |
| **明确信号** | done 事件 + messageId | 前端能精确判断"这次回答完成了" |

### 前端怎么判断完成

```
前端收到 done → 这次回答完成，可以落 UI 状态
前端收到 cancel → 用户主动停止（部分是完整的）
前端收到 reject → 限流拒绝（没有回答）
前端收到 error / 连接断开 → 未完成（异常）
前端重试后新请求 → 新会话（现状，无 checkpoint 恢复）
```

---

<a id="q4"></a>
## Q4: 如何监控 SSE 完成性？

### 监控指标（生产必看）

| 指标 | 怎么算 | 反映什么 |
|---|---|---|
| **完成率** | 发 done 的请求 / 总请求 | 核心健康指标 |
| **断连率** | ClientAbortException 次数 / 总请求 | 客户端中途关闭（可能网络差/用户离开） |
| **超时率** | emitter 超时次数 / 总请求 | 生成超 5 分钟 |
| **错误率** | onError 次数 / 总请求 | LLM 失败/路由全部失败 |
| **取消率** | cancel 次数 / 总请求 | 用户主动停止 |
| **平均流时长** | done 时间 - meta 时间 | 生成耗时趋势 |
| **TTFT** | 首个 token 时间 | 首包延迟（用户感知） |

### 已有的可观测工具

**1. 全链路 Trace（t_rag_trace_run + t_rag_trace_node）**

```
每次请求落库:
  t_rag_trace_run:  trace_id, status(SUCCESS/FAILED), duration_ms
  t_rag_trace_node: 每个阶段（改写/意图/检索/LLM路由/首包）的状态和耗时

→ 查 "哪些请求 status != SUCCESS" 就是未完成的
→ 查 "LLM_TTFT 节点耗时" 就是首包延迟
```

**2. 日志（主链路耗时日志）**

```
Chat pipeline start / end, elapsed=xxxms
Stage[1/7] ~ Stage[7/7] 各阶段耗时
→ 慢链路可定位
```

**3. 缺失的（要补的）**

```
❌ 无 Prometheus 指标暴露（/metrics）
❌ 无"完成率"聚合统计
❌ 无 SSE 断连/超时计数

要补: Micrometer + Prometheus
  sse_completed_total{done}
  sse_client_abort_total{broken_pipe}
  sse_timeout_total
  sse_error_total
  sse_cancel_total
```

### 监控方案设计

```
① 埋点: 在 SseEmitterSender 的 complete()/fail()/onTimeout() 加计数器
  → 用 Micrometer Counter，暴露 /actuator/prometheus

② 聚合: Prometheus 查询
  → 完成率 = sum(sse_completed) / sum(sse_total) by (conversation_type)

③ 告警:
  → 完成率 < 95% → 告警（LLM 服务有问题）
  → 断连率突增 → 告警（网络/前端问题）
  → 错误率 > 5% → 告警（模型路由全失败）

④ 归因: 未完成的请求 → 查 t_rag_trace_run status != SUCCESS
  → 定位是哪个阶段挂了
```

---

## 总结

SSE 流式是 ragent/PowerAgent 对话的核心传输层。**实现**靠 Servlet AsyncContext + SseEmitter（请求线程释放、回调线程推流），**完成性**靠"先落库再发 done"（内容持久化在完成信号前），**监控**靠"完成率 + 断连/超时/错误/取消率 + Trace 归因"。

三个面试核心点：
1. **异步化**：Controller 返回 SseEmitter 立即释放请求线程，不占 Tomcat 线程
2. **线程安全**：AtomicBoolean CAS 保证 complete() 只执行一次
3. **完成性**：先落库再发 done，断开是预期行为（ClientAbortException 静默处理），单向连接靠独立 stop 接口取消
