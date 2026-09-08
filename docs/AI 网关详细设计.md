# AI 网关详细设计

> 上游概要：`docs/AI 网关技术方案.md`（需求确认 + 总体架构 + 数据表 + 四维治理概念）
> 本文为**可编码级详细设计**：包结构、数据库 DDL、OpenAI 兼容接口、鉴权、治理逐维实现、流式透传、审计、类签名。
> 标注"【默认】"处为待确认点已落默认值，可调整。

---

## 〇、默认决策（待确认，先按此落地）

| 决策点 | 默认值 | 可改方向 |
|---|---|---|
| 模型别名路由 | Phase 1 建 `t_ai_model_alias` 表做 DB 路由 | 改为纯 infra-ai 候选组 |
| 管理端形态 | 先 REST API（管理接口），前端后置 | 直接上界面 |
| 配额请求前预估 | `input + min(max_tokens, 20%配额余量)`，预留 20% 缓冲 | 精确预估不预留 |
| `/v1/rerank` | Phase 1 不做，预留路由 + `t_ai_model_alias.capability='rerank'` | 需要则排入 Phase 3.5 |

---

## 一、包结构与模块

```
ai-gateway/
├── pom.xml                        (parent=ragent, 依赖 infra-ai, framework, mybatis-plus, redisson)
└── src/main/
    ├── java/com/nageoffer/ai/ragent/gateway/
    │   ├── AiGatewayApplication.java
    │   ├── controller/                      # 入站 OpenAI 兼容层(鉴权后)
    │   │   ├── ChatCompletionsController.java
    │   │   ├── EmbeddingsController.java
    │   │   └── ModelsController.java
    │   ├── controller/admin/                # 管理端 REST(需管理角色)
    │   │   ├── AdminApiKeyController.java
    │   │   └── AdminModelAliasController.java
    │   ├── auth/
    │   │   ├── ApiKeyAuthenticator.java      # Bearer 解析 + 校验 + 缓存
    │   │   ├── GatewayPrincipal.java          # 请求上下文
    │   │   └── GatewayAuthInterceptor.java    # HandlerInterceptor 统一鉴权
    │   ├── governance/
    │   │   ├── GovernancePipeline.java        # 四维串行编排
    │   │   ├── QpsLimiter.java
    │   │   ├── MessageShapingLimiter.java
    │   │   ├── ConcurrencyLimiter.java
    │   │   ├── TokenQuotaService.java
    │   │   └── LimiterResult.java
    │   ├── route/
    │   │   ├── AliasRouter.java
    │   │   └── ModelPermissionService.java
    │   ├── exec/
    │   │   ├── ChatProxyExecutor.java         # 调 infra-ai, 处理 stream/非stream
    │   │   └── EmbeddingProxyExecutor.java
    │   ├── audit/
    │   │   ├── RequestLogService.java
    │   │   └── UsageAggregationService.java
    │   ├── dao/entity/  +  mapper/
    │   │   ├── AiTenantDO / AiTenantMapper
    │   │   ├── AiApiKeyDO / AiApiKeyMapper
    │   │   ├── AiModelAliasDO / AiModelAliasMapper
    │   │   ├── AiTenantModelDO / AiTenantModelMapper   # 租户-模型授权
    │   │   ├── AiRequestLogDO / AiRequestLogMapper
    │   │   └── AiUsageMonthDO / AiUsageMonthMapper
    │   └── config/
    │       ├── AiGatewayProperties.java        # 网关治理参数(见 §十)
    │       ├── RedisKeyConstant.java
    │       └── LuaScriptRegistry.java          # 集中管理 Redis Lua
    └── resources/
        ├── application.yml
        └── db/schema-ai-gateway.sql
```

依赖方向：`controller → governance/route/exec → infra-ai`，全部经 `auth` 层拦截。

---

## 二、数据库详细设计（DDL）

### 2.1 t_ai_tenant —— 租户

```sql
CREATE TABLE t_ai_tenant (
    id                 VARCHAR(20) NOT NULL PRIMARY KEY,
    tenant_name        VARCHAR(128) NOT NULL,
    status             SMALLINT NOT NULL DEFAULT 1,          -- 1启用 0停用
    monthly_token_quota BIGINT NOT NULL DEFAULT 0,           -- 月 token 配额 0=不限
    qps_limit          INTEGER NOT NULL DEFAULT 0,           -- 租户级 QPS 0=继承全局
    concurrency_limit  INTEGER NOT NULL DEFAULT 0,           -- 租户级并发 0=继承全局
    created_by         VARCHAR(20),
    create_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE t_ai_tenant IS 'AI 网关租户';
```

### 2.2 t_ai_api_key —— 入站 Key

```sql
CREATE TABLE t_ai_api_key (
    id                 VARCHAR(20) NOT NULL PRIMARY KEY,
    tenant_id          VARCHAR(20) NOT NULL,
    key_name           VARCHAR(64),
    key_hash           VARCHAR(64) NOT NULL,                 -- sha256(明文key)
    key_prefix         VARCHAR(16) NOT NULL,                 -- 展示前缀 sk-xxxx
    status             SMALLINT NOT NULL DEFAULT 1,          -- 1启用 0吊销
    expire_time        TIMESTAMP,
    monthly_token_quota BIGINT NOT NULL DEFAULT 0,           -- key 级月配额 0=继承租户
    qps_limit          INTEGER NOT NULL DEFAULT 0,           -- key 级 QPS 0=继承租户
    last_used_at       TIMESTAMP,
    create_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_gw_key_hash ON t_ai_api_key (key_hash);
CREATE INDEX idx_gw_key_tenant ON t_ai_api_key (tenant_id);
COMMENT ON TABLE t_ai_api_key IS 'AI 网关入站 API Key';
```

**Key 生成规则**：`sk-` + 22 位随机（`IdUtil.fastSimpleUUID()` 取安全字符），明文仅签发时返回一次；库中只存 SHA-256。吊销 = `status=0` + 删除 Redis 缓存。

### 2.3 t_ai_model_alias —— 模型别名

```sql
CREATE TABLE t_ai_model_alias (
    id           VARCHAR(20) NOT NULL PRIMARY KEY,
    alias_name   VARCHAR(64) NOT NULL,                       -- 下游可见: gpt-4o
    capability   VARCHAR(16) NOT NULL,                       -- chat/embedding/rerank
    provider     VARCHAR(32),                                -- NULL=走 infra-ai 候选组
    model        VARCHAR(64),                                -- 真实模型名(候选组模式为空)
    qps_limit    INTEGER NOT NULL DEFAULT 0,                 -- 模型级 QPS 0=不设
    enabled      SMALLINT NOT NULL DEFAULT 1,
    priority     INTEGER NOT NULL DEFAULT 100,
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_gw_alias ON t_ai_model_alias (alias_name, capability);
COMMENT ON TABLE t_ai_model_alias IS 'AI 网关模型别名';
```

`provider/model` 均为空 → 走 infra-ai 候选模型组（用 infra-ai 的 fallback/熔断能力）；非空 → 网关定向调用该供应商真实模型。

### 2.4 t_ai_tenant_model —— 租户-模型授权

```sql
CREATE TABLE t_ai_tenant_model (
    id           VARCHAR(20) NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(20) NOT NULL,
    alias_id     VARCHAR(20) NOT NULL,
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_gw_tm ON t_ai_tenant_model (tenant_id, alias_id);
COMMENT ON TABLE t_ai_tenant_model IS '租户可访问的模型授权';
```

空授权 = 租户可访问全部 enabled 别名（Phase 1 简化），有行 = 白名单制。

### 2.5 t_ai_request_log —— 请求日志

```sql
CREATE TABLE t_ai_request_log (
    id            VARCHAR(20) NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(20) NOT NULL,
    api_key_id    VARCHAR(20) NOT NULL,
    alias_name    VARCHAR(64),
    capability    VARCHAR(16),
    endpoint      VARCHAR(32),
    req_id        VARCHAR(32),                               -- 网关生成, 贯穿日志/审计
    input_tokens  INTEGER,
    output_tokens INTEGER,
    status        VARCHAR(16),                               -- success/rate_limited/...
    error_code    VARCHAR(32),
    latency_ms    INTEGER,
    ip            VARCHAR(64),
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_gw_log_time ON t_ai_request_log (create_time, tenant_id);
COMMENT ON TABLE t_ai_request_log IS '网关请求日志(异步批量写入)';
```

### 2.6 t_ai_usage_month —— 月度用量(配额来源)

```sql
CREATE TABLE t_ai_usage_month (
    id            VARCHAR(20) NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(20) NOT NULL,
    api_key_id    VARCHAR(20) NOT NULL,
    month         VARCHAR(7)  NOT NULL,                      -- yyyy-MM
    input_tokens  BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    request_count BIGINT NOT NULL DEFAULT 0,
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_gw_usage ON t_ai_usage_month (tenant_id, api_key_id, month);
COMMENT ON TABLE t_ai_usage_month IS '月度 token 用量(配额扣减)';
```

---

## 三、入站接口（OpenAI 兼容）

### 3.1 POST /v1/chat/completions

鉴权：`Authorization: Bearer sk-xxx`

请求体（透传 OpenAI 格式 + 网关扩展字段）：

```json
{
  "model": "gpt-4o",
  "messages": [{"role": "user", "content": "你好"}],
  "stream": true,
  "temperature": 0.7,
  "max_tokens": 1024,
  "reasoning_effort": "high",
  "gw": {                             // 网关扩展(可选)
    "request_id": "",                 // 下游自定义 reqId, 空则由网关生成
    "priority": 5                     // 削峰优先级 0-9, 9最高
  }
}
```

**非流式响应**（`stream=false`，兼容 OpenAI ChatCompletion）：

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "created": 1757123456,
  "model": "gpt-4o",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "..."},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}
}
```

**流式响应**（`stream=true`，SSE，`text/event-stream`）：

```
data: {"id":"chatcmpl-x","object":"chat.completion.chunk","created":1757123456,
       "model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

data: {"id":"chatcmpl-x",...,"choices":[{"index":0,"delta":{"content":"你"},"finish_reason":null}]}

data: {"id":"chatcmpl-x",...,"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

**错误响应**（OpenAI 风格，保证下游官方 SDK 可解析）：

```json
{
  "error": {
    "message": "You exceeded your current quota",
    "type": "quota_exceeded_error",
    "param": null,
    "code": "GW_QUOTA_EXCEEDED"
  }
}
```

HTTP 状态：鉴权失败 401；限流 429；配额 429；模型不存在 404；网关错误 500。

### 3.2 POST /v1/embeddings

```json
{
  "model": "bge-m3",
  "input": ["文本1", "文本2"]
}
```

响应：OpenAI Embedding 结构 `{object,data:[{embedding,index}],model,usage}`。

### 3.3 GET /v1/models

返回当前租户被授权且 enabled 的别名列表：

```json
{
  "object": "list",
  "data": [
    {"id": "gpt-4o", "object": "model", "owned_by": "ai-gateway"},
    {"id": "deepseek-r1", "object": "model", "owned_by": "ai-gateway"}
  ]
}
```

### 3.4 错误码 → HTTP 映射

| 网关 code | HTTP | type(OpenAI) | 说明 |
|---|---|---|---|
| GW_KEY_MISSING | 401 | invalid_request_error | 未带 Key |
| GW_KEY_INVALID | 401 | invalid_request_error | Key 不存在/格式错 |
| GW_KEY_REVOKED | 401 | invalid_request_error | Key 吊销 |
| GW_KEY_EXPIRED | 401 | invalid_request_error | Key 过期 |
| GW_MODEL_FORBIDDEN | 403 | permission_error | 租户无权限 |
| GW_MODEL_NOT_FOUND | 404 | invalid_request_error | 别名不存在 |
| GW_INVALID_REQUEST | 400 | invalid_request_error | 参数错 |
| GW_RATE_LIMITED | 429 | rate_limit_error | QPS 超限 |
| GW_QUEUE_TIMEOUT | 429 | rate_limit_error | 排队超时 |
| GW_CONCURRENCY_LIMITED | 429 | rate_limit_error | 并发超限 |
| GW_QUOTA_EXCEEDED | 429 | quota_exceeded_error | 配额不足 |
| GW_UPSTREAM_ERROR | 502 | api_error | 上游全失败 |
| GW_INTERNAL_ERROR | 500 | api_error | 网关内部 |

---

## 四、鉴权设计

### 4.1 流程

```
HandlerInterceptor.preHandle
  ① 取 header Authorization: Bearer sk-...
     → 缺 → 401 GW_KEY_MISSING
  ② keyHash = sha256Hex(sk-...)      (与签发时同算法)
  ③ Redis GET gw:key:{keyHash}
     → 命中: 反序列化 ApiKeyAuth(含 status/quota/expire)
     → 未命中: DB 查 t_ai_api_key by key_hash
        └─ 存在: 校验 status/expire → 写缓存 TTL 30min
        └─ 不存在: 401 GW_KEY_INVALID
  ④ 校验 status=1 && (expire==null || expire>now)
  ⑤ 装载 GatewayPrincipal 到 ThreadLocal(或 request attribute)
```

### 4.2 GatewayPrincipal

```java
public class GatewayPrincipal {
    private String tenantId;
    private String apiKeyId;
    private long monthlyQuota;      // key>tenant 级联后生效配额, 0=不限
    private int qpsLimit;           // key>tenant>global 级联后生效, 0=不限
    private int concurrencyLimit;   // 同上
}
```

### 4.3 缓存结构

```
Redis:
  gw:key:{sha256}  →  {tenantId, apiKeyId, status, monthlyQuota, qpsLimit,
                        concurrencyLimit, expireAt}    (TTL 30min, 吊销时 DEL)
  gw:qps:{tenantId}:{aliasName}      → 令牌桶状态(hash: tokens,last)
  gw:qps:{tenantId}:{apiKeyId}:{aliasName} → key 级令牌桶(可选)
  gw:queue:{aliasName}               → ZSet 公平队列(削峰)
  gw:conc:{tenantId}                 → 并发计数器/租约
  gw:usage:{yyyy-MM}:{apiKeyId}      → hash {in,out,count} (Lua 原子)
```

---

## 五、治理层实现

### 5.1 GovernancePipeline —— 串行编排

```java
@Component
@RequiredArgsConstructor
public class GovernancePipeline {
    private final QpsLimiter qpsLimiter;
    private final MessageShapingLimiter shapingLimiter;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final TokenQuotaService quotaService;

    /** 四维固定顺序: QPS → 削峰 → 并发 → 配额. 任一拒绝即短路, 返回错误码 */
    public void check(GatewayPrincipal p, String aliasName, int estInputTokens, int maxOutputTokens) {
        qpsLimiter.tryAcquire(p, aliasName).orElseThrow(GW_RATE_LIMITED);
        shapingLimiter.enter(p, aliasName, estInputTokens, maxOutputTokens).orElseThrow(GW_QUEUE_TIMEOUT);
        concurrencyLimiter.acquire(p, aliasName).orElseThrow(GW_CONCURRENCY_LIMITED);
        quotaService.preCheck(p, estInputTokens, maxOutputTokens).orElseThrow(GW_QUOTA_EXCEEDED);
    }

    /** 请求完成: 释放并发 + 扣减真实用量 */
    public void release(GatewayPrincipal p, int inputTokens, int outputTokens) {
        concurrencyLimiter.release(p);
        quotaService.consume(p, inputTokens, outputTokens);
    }
}
```

`LimiterResult` 携带内部租约句柄（释放并发用），此处简化为 `p` + ThreadLocal 持有租约。建议把并发租约放 `GatewayPrincipal` 的瞬态字段，`finally` 释放。

### 5.2 QpsLimiter —— Redis 令牌桶

```java
/** 维度: keyId 级 + alias 级, 取 key 配额(0则继承租户/全局) */
public class QpsLimiter {
    private static final String SCRIPT = """
        local tokens = redis.call('hget', KEYS[1], 'tokens')
        local last   = redis.call('hget', KEYS[1], 'last')
        local rate   = tonumber(ARGV[1])
        local cap    = tonumber(ARGV[2])
        local now    = tonumber(ARGV[3])
        if not tokens then
            redis.call('hset', KEYS[1], 'tokens', cap, 'last', now)
            redis.call('pexpire', KEYS[1], 3000)
            return 1
        end
        tokens = tonumber(tokens) + (now - tonumber(last)) * rate / 1000
        if tokens > cap then tokens = cap end
        if tokens < 1 then return 0 end
        redis.call('hset', KEYS[1], 'tokens', tokens - 1, 'last', now)
        redis.call('pexpire', KEYS[1], 3000)
        return 1
    """;
}
```

注意：token 桶空闲即 reset（pexpire 3s 无访问清空），避免长期 key 残留。

### 5.3 MessageShapingLimiter —— 削峰

复用 `FairDistributedRateLimiter` 的 ZSet 公平队列思路：

```
入队: ZADD gw:queue:{alias} {weight} {uuid:priority:ts}
       weight = estInputTokens + maxOutputTokens(小者先出)
       分数用 (priority 反序 * 大权重 + 时间) 使高优先级/小请求先出队
出队: ZRANGEBYSCORE 取队头 → 尝试并发额度 → 成功 ZREM
等待: 轮询/阻塞, 超时 maxWaitSeconds(默认20s) → GW_QUEUE_TIMEOUT
```

默认决策：Phase 1 仅在高峰（并发额度耗尽）时才进入实际排队，低峰直接放行，减少延迟。

### 5.4 ConcurrencyLimiter —— 并发控制

复用 bootstrap 已有的 `FairDistributedRateLimiter` 语义（网关侧独立实例，Redis key 前缀 `gw:conc:` 隔离）：

```
acquire: Redis Lua 原子计数, 未超限则 INCR 并返回租约; 超限排队(见削峰)
release: DECR + 通知队列
租约带 TTL(如 120s) 兜底, 防客户端断开泄漏
```

### 5.5 TokenQuotaService —— 配额

**配额级联生效**：`key.monthlyQuota > 0 ? key : tenant.monthlyQuota`，0 = 不限。

```java
/** 请求前预检: 已用 + 预估(带 20% 缓冲) <= 配额 才放行 */
public Optional<Void> preCheck(GatewayPrincipal p, int estIn, int maxOut) {
    if (p.monthlyQuota <= 0) return Optional.of(null);          // 不限
    long used = usageCache.get(p.apiKeyId, currentMonth());      // Redis 近似值
    long budget = estIn + (long) Math.min(maxOut, p.monthlyQuota * 0.2);
    return (used + budget) <= p.monthlyQuota ? Optional.of(null) : Optional.empty();
}

/** 请求完成后按真实 usage 原子扣减 */
public void consume(GatewayPrincipal p, int inTokens, int outTokens) {
    if (inTokens == 0 && outTokens == 0) return;
    // Lua: HINCRBY gw:usage:{month}:{keyId} in/out/count
    // 同步写 Redis, 异步批量刷 DB(t_ai_usage_month)
}
```

Redis 用量是"近似前置检查 + 快速扣减"，DB 是最终对账源。Redis 与 DB 的一致性由异步 flush + 每日/每小时 reconcile 保证。

---

## 六、路由与执行层

### 6.1 AliasRouter

```java
@Component
@RequiredArgsConstructor
public class AliasRouter {
    private final AiModelAliasMapper aliasMapper;

    /**
     * 别名 → 路由目标.
     * provider/model 为空 → 走 infra-ai 候选组(多个候选自动 fallback)
     * provider/model 非空 → 定向真实模型(构造单个 ModelTarget)
     */
    public RouteTarget resolve(String aliasName, String capability) {
        AiModelAliasDO alias = aliasMapper.selectOne(
            eq(alias_name, aliasName), eq(capability, capability), eq(enabled, 1));
        if (alias == null) throw new GwBizException(GW_MODEL_NOT_FOUND);

        ModelTarget target = resolveTarget(alias);   // 复用 infra-ai ModelTarget 构建
        return new RouteTarget(alias, target);
    }
}
```

### 6.2 ModelTarget 与 infra-ai 复用

复用 infra-ai 的 `ModelTarget` / `ModelCapability` / `ModelHealthStore` / `ModelRoutingExecutor`：

- 定向模型：构造 `ModelTarget(candidate=单候选, id=alias)`，走同一 `executeWithFallback`（单候选时退化为"熔断+重试一次"）
- 候选组模型：`resolveTarget` 从 infra-ai 候选列表按 capability 取全部 enabled 候选

**关键**：网关不重写熔断/fallback/SSE 解析，全部委托 infra-ai。网关异常转 `GW_UPSTREAM_ERROR` 并透传 infra-ai `RemoteException` 细节到日志。

### 6.3 权限校验

```java
// 租户-模型授权: t_ai_tenant_model 为空=全开放, 有行=白名单
public boolean hasPermission(String tenantId, String aliasId) {
    return tenantModelMapper.selectCount(eq(tenant_id, tenantId), eq(alias_id, aliasId)) == 0   // 全开放
        || tenantModelMapper.selectCount(...) > 0;
}
```

---

## 七、流式透传

```
下游 SSE Request → ChatProxyExecutor.streamChat()
  ├─ 调 infra-ai RoutingLLMService(stream=true), 传 StreamCallback
  ├─ 事件回调 → OpenAIStyleSseParser → 转 OpenAI chunk JSON → SseEmitter 下发
  ├─ 首包超时 → ProbeStreamBridge 切候选模型(复用)
  ├─ 客户端断开 → StreamCancellationHandles 取消上游
  └─ 流结束 → 聚合 usage → GovernancePipeline.release()
```

```java
@Controller
public class ChatCompletionsController {
    @PostMapping(value = "/v1/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest req, GatewayPrincipal principal) {
        // GovernancePipeline.check 在 Interceptor 之前已完成
        SseEmitter emitter = new SseEmitter(180_000L);
        chatProxyExecutor.streamChat(principal, req, emitter);
        return emitter;
    }
}
```

SSE 事件格式严格对齐 OpenAI（`chat.completion.chunk`），使 openai-java/ts-sdk 可直接消费。

---

## 八、审计与用量

### 8.1 RequestLogService —— 异步批量

```java
// 用 Disruptor/简单 BlockingQueue + 定时批量 flush
@Slf4j
@Component
public class RequestLogService {
    private final BlockingQueue<AiRequestLogDO> queue = new ArrayBlockingQueue<>(100_000);

    public void record(AiRequestLogDO logDO) {
        if (!queue.offer(logDO)) {
            log.warn("gateway request log queue full, drop one reqId={}", logDO.getReqId());
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void flush() {
        List<AiRequestLogDO> batch = new ArrayList<>();
        queue.drainTo(batch, 500);
        if (!batch.isEmpty()) {
            // 批量 INSERT
        }
    }
}
```

### 8.2 UsageAggregationService —— reconcile

每小时 reconcile：把 Redis 用量增量刷进 `t_ai_usage_month`，Redis 计数清减，保证 DB 为最终账本。

---

## 九、关键类签名速查

```java
// auth
public class ApiKeyAuthenticator {
    public GatewayPrincipal authenticate(String bearerToken);
}
public class GatewayPrincipal { /* tenantId/apiKeyId/quota/qps/concurrency + transient 并发租约 */ }

// governance
public interface Limiter { LimiterResult tryAcquire(...); void release(...); }
public class QpsLimiter implements Limiter { ... }
public class MessageShapingLimiter implements Limiter { ... }
public class ConcurrencyLimiter implements Limiter { ... }
public class TokenQuotaService { Optional<Void> preCheck(...); void consume(...); long currentUsed(...); }

// route/exec
public class AliasRouter { RouteTarget resolve(String alias, String capability); }
public class ChatProxyExecutor { void streamChat(GatewayPrincipal p, ChatRequest req, SseEmitter emitter); }
public class EmbeddingProxyExecutor { EmbeddingResult embed(GatewayPrincipal p, EmbeddingRequest req); }

// audit
public class RequestLogService { void record(AiRequestLogDO logDO); void flush(); }
public class UsageAggregationService { void reconcile(); }
```

---

## 十、配置项

```yaml
ai:
  gateway:
    global-qps: 100            # 全局默认 QPS
    global-concurrency: 50     # 全局默认并发
    queue-max-wait-seconds: 20 # 削峰排队上限
    queue-weight-token: 1      # 1 token = 权重1
    key-cache-ttl-seconds: 1800
    rate-limit-retry-after-seconds: 1
    # 无状态网关, Redis 为共享状态
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:123456}
```

---

## 十一、幂等 / 并发 / 边界汇总

| 场景 | 处理 |
|---|---|
| 并发扣配额 | Lua HINCRBY 原子；DB 由 reconcile 汇总，无双写竞争 |
| 流式中途客户端断开 | finally 释放并发租约；取消上游(StreamCancellationHandles) |
| 治理执行一半抛异常 | GovernancePipeline.check 已占用的并发在 catch 中释放(租约放 principal, finally release) |
| Key 吊销即时性 | DEL gw:key:{hash}，下一个请求即 401 |
| 配额预检并发抖动 | Redis 用量为近似值, 预检允许 5% 超卖, reconcile 后以 DB 为准 |
| 大请求 + 高优先级 | 削峰权重 = token 量, priority 字段可让高优请求插队 |
| Redis 故障降级 | 治理全部 fail-open(放行)并打日志+指标, 保证模型可用性优先; 可用配置 fail-close |
