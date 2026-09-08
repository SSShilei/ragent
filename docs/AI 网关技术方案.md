# AI 网关技术方案

> 目标：在 ragent 之上新增 **对外 OpenAI 兼容的 AI 网关**，统一收口各家大模型能力，为下游业务系统提供「API Key 鉴权 + 四维流量控制 + 模型别名路由 + 用量审计」标准接口。
> 复用现有 `infra-ai` 的路由/熔断/流式降级/SSE 解析，只新增网关治理层。

---

## 一、需求确认（已对齐）

| 决策项 | 结论 |
|---|---|
| 核心定位 | **对外 OpenAI 兼容代理**：暴露 `/v1/chat/completions`、`/v1/embeddings`、`/v1/models` 等，下游系统用 API Key 消费，网关内做鉴权/配额/限流/审计 |
| 流量控制 | **四维全上**：QPS 限流 + 并发控制 + Token 配额 + 消息削峰 |
| 与 infra-ai 关系 | **复用 infra-ai**（路由/熔断/流式降级/SSE 解析），只新增网关层 |
| 密钥管理 | **入站 Key + DB 管理**：签发/吊销/绑定租户/配额，存库；上游供应商 Key 沿用 infra-ai 配置 |

---

## 二、总体架构

```
┌───────────────── 下游业务系统 ─────────────────┐
│  curl / SDK / 自有应用, 持 sk-xxx 入站 Key      │
└──────────────────────┬─────────────────────────┘
                       │ POST /v1/chat/completions  (SSE / JSON)
                       ▼
┌─────────────────  ai-gateway 应用 ────────────────────────────────┐
│                                                                   │
│  ① 入站层       OpenAI 兼容 REST Controller                       │
│                 /v1/chat/completions /v1/embeddings /v1/models    │
│      ▼                                                             │
│  ② 鉴权层       ApiKeyAuth: Bearer sk-xxx → Redis/DB 查租户+权限   │
│      ▼                                                             │
│  ③ 治理层       四维流量控制(执行顺序固定)                          │
│                 1. QPS 限流      — 按 key+model, Redis 令牌桶        │
│                 2. 消息削峰      — 按 token 权重排队, 小请求优先      │
│                 3. 并发控制      — 复用 FairDistributedRateLimiter   │
│                 4. Token 配额    — 按 key/租户月预算, Redis 原子累计  │
│      ▼                                                             │
│  ④ 路由层       AliasRouter: 别名(gpt-4o) → 真实 ModelTarget        │
│                 ├─ 查 t_ai_model_alias 绑定关系                     │
│                 └─ 复用 infra-ai ModelRoutingExecutor(熔断/fallback)│
│      ▼                                                             │
│  ⑤ 执行层       infra-ai LLMService/EgEmbedding/RerankService       │
│                 + ProbeStreamBridge(首包超时切候选) → 流式事件       │
│      ▼                                                             │
│  ⑥ 审计层       请求日志 + 用量累计(异步批量写)                     │
└────────────────────────────────────────────────────────────────────┘
```

**模块边界**：新建 `ai-gateway` Maven 模块（独立 Spring Boot 应用），依赖 `infra-ai` + `framework`，不改动 infra-ai 已有代码——网关层只做"入站治理"，模型能力全部委托 infra-ai。

---

## 三、模块与依赖

```
ai-gateway/
├── src/main/java/com/nageoffer/ai/ragent/gateway/
│   ├── controller/
│   │   ├── ChatCompletionsController.java      ★ /v1/chat/completions
│   │   ├── EmbeddingsController.java           ★ /v1/embeddings
│   │   └── ModelsController.java               ★ /v1/models
│   ├── auth/
│   │   ├── ApiKeyAuthenticator.java            ★ Bearer Key 解析+鉴权
│   │   └── GatewayPrincipal.java               ★ 请求上下文(租户/key/模型权限)
│   ├── governance/                              ★ 四维流量控制
│   │   ├── QpsLimiter.java
│   │   ├── MessageShapingLimiter.java
│   │   ├── ConcurrencyLimiter.java
│   │   └── TokenQuotaService.java
│   ├── route/
│   │   ├── AliasRouter.java                    ★ 模型别名 → ModelTarget
│   │   └── ModelPermissionService.java
│   ├── audit/
│   │   ├── RequestLogService.java
│   │   └── UsageAggregationService.java         ★ 按月用量汇总(异步)
│   ├── dao/entity/  +  mapper/
│   │   ├── AiTenantDO.java
│   │   ├── AiApiKeyDO.java
│   │   ├── AiModelAliasDO.java
│   │   ├── AiRequestLogDO.java
│   │   └── AiUsageMonthDO.java
│   └── config/
│       ├── GatewayRateLimitProperties.java
│       └── GatewayAuthProperties.java
└── src/main/resources/application.yml
```

依赖关系：`ai-gateway → infra-ai → framework`；`ai-gateway` 复用 `infra-ai` 的 `RoutingLLMService`、`ModelRoutingExecutor`、`ProbeStreamBridge`、`OpenAIStyleSseParser`、`TokenCounterService`。

---

## 四、数据表设计

### 4.1 租户 t_ai_tenant

```sql
CREATE TABLE t_ai_tenant (
    id            VARCHAR(20)   NOT NULL PRIMARY KEY,
    tenant_name   VARCHAR(128)  NOT NULL,
    status        SMALLINT      NOT NULL DEFAULT 1,   -- 1启用 0停用
    monthly_token_quota BIGINT  NOT NULL DEFAULT 0,   -- 月 token 总配额, 0=不限
    created_by    VARCHAR(20),
    create_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE t_ai_tenant IS 'AI 网关租户';
```

### 4.2 入站 API Key t_ai_api_key

```sql
CREATE TABLE t_ai_api_key (
    id            VARCHAR(20)   NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(20)   NOT NULL,
    key_name      VARCHAR(64),                          -- 便于管理端识别
    key_hash      VARCHAR(64)   NOT NULL,               -- SHA-256, 不存明文
    key_prefix    VARCHAR(16)   NOT NULL,               -- sk-xxxx 前段展示用
    status        SMALLINT      NOT NULL DEFAULT 1,     -- 1启用 0吊销
    expire_time   TIMESTAMP,
    monthly_token_quota BIGINT  NOT NULL DEFAULT 0,     -- 0=继承租户
    last_used_at  TIMESTAMP,
    create_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_gw_key_hash ON t_ai_api_key (key_hash);
COMMENT ON TABLE t_ai_api_key IS 'AI 网关入站 API Key';
```

- 只存 `key_hash`（SHA-256），签发时明文仅返回一次
- 吊销 = `status=0`，请求时直接从 Redis 缓存失效

### 4.3 模型别名 t_ai_model_alias

```sql
CREATE TABLE t_ai_model_alias (
    id            VARCHAR(20)   NOT NULL PRIMARY KEY,
    alias_name    VARCHAR(64)   NOT NULL,               -- 下游看到的名字: gpt-4o / deepseek-r1
    capability    VARCHAR(16)   NOT NULL,               -- chat / embedding / rerank
    provider      VARCHAR(32),                          -- 为空则走 infra-ai 候选模型组
    model         VARCHAR(64),                          -- 真实模型名
    enabled       SMALLINT      NOT NULL DEFAULT 1,
    priority      INTEGER       NOT NULL DEFAULT 100,
    create_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_gw_alias_name ON t_ai_model_alias (alias_name, capability);
COMMENT ON TABLE t_ai_model_alias IS 'AI 网关模型别名映射';
```

- **别名 → 真实模型**解耦：下游固定用 `gpt-4o` 别名，网关可随时把别名切到不同供应商，下游无感
- `provider+model` 为空时走 infra-ai 候选组（多模型 fallback），非空时定向打到指定模型

### 4.4 请求日志 t_ai_request_log

```sql
CREATE TABLE t_ai_request_log (
    id            VARCHAR(20)   NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(20)   NOT NULL,
    api_key_id    VARCHAR(20)   NOT NULL,
    alias_name    VARCHAR(64),
    endpoint      VARCHAR(32),                          -- chat_completions / embeddings
    req_id        VARCHAR(32),
    input_tokens  INTEGER,
    output_tokens INTEGER,
    status        VARCHAR(16),                          -- success / rate_limited / quota_exceeded / ...
    latency_ms    INTEGER,
    ip            VARCHAR(64),
    create_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_gw_log_time ON t_ai_request_log (create_time);
COMMENT ON TABLE t_ai_request_log IS 'AI 网关请求日志(异步批量写入)';
```

### 4.5 月度用量 t_ai_usage_month

```sql
CREATE TABLE t_ai_usage_month (
    id            VARCHAR(20)   NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(20)   NOT NULL,
    api_key_id    VARCHAR(20)   NOT NULL,
    month         VARCHAR(7)    NOT NULL,               -- 2026-09
    input_tokens  BIGINT        NOT NULL DEFAULT 0,
    output_tokens BIGINT        NOT NULL DEFAULT 0,
    request_count BIGINT        NOT NULL DEFAULT 0,
    create_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_gw_usage ON t_ai_usage_month (tenant_id, api_key_id, month);
COMMENT ON TABLE t_ai_usage_month IS 'AI 网关月度用量(配额扣减来源)';
```

---

## 五、请求流转时序

```
下游: POST /v1/chat/completions
  Authorization: Bearer sk-abc123
  body: {"model":"gpt-4o","messages":[...],"stream":true}
      │
      ▼
① 入站: ChatCompletionsController
      ├─ 解析 Bearer → ApiKeyAuthenticator
      │    ├─ key_hash = sha256(sk-abc123)
      │    ├─ Redis GET gw:key:{hash} → 未命中查 DB
      │    ├─ 校验 status=1 && 未过期 && 缓存到 Redis(30min TTL)
      │    └─ 组装 GatewayPrincipal {tenantId, keyId, keyQuota, tenantQuota}
      ▼
② 治理(顺序执行, 任一不过即 429/份配额错误):
      ├─ QpsLimiter:       Redis 确比(INCREX), key+model 维度
      ├─ MessageShaping:   预估 input_tokens(复用 TokenCounterService)+ output max_tokens
      │                     按权重入 Redis 公平队列, 大请求让道
      ├─ ConcurrencyLimiter: Redis 原子抢占并发额度(复用现有思路)
      └─ TokenQuotaService: 校验月用量 < 配额(先读缓存, 完成后再 INCR)
      ▼
③ 路由: AliasRouter
      ├─ 查 t_ai_model_alias: gpt-4o → {provider:xxx, model:yyy}
      ├─ 权限校验: 租户是否被授予该别名(后续可加 t_ai_tenant_model 关系表)
      └─ 构造 ModelTarget(或走 infra-ai 候选组)
      ▼
④ 执行: infra-ai RoutingLLMService(复用熔断/fallback/streaming)
      └─ Provider 流式事件 → OpenAIStyleSseParser → 转发给下游 SSE
      ▼
⑤ 完成: 取 usage → TokenQuotaService.consume(原子 INCR 用量表 + Redis)
          审计 → RequestLogService 异步批量落库
```

---

## 六、四维流量控制设计

### 6.1 QPS 限流

- 维度：`(key_id, alias_name)` 粒度；可扩展全局维度
- 实现：Redis 令牌桶（Lua 原子），`max_qps` 配置级联 key > 租户 > 全局默认
- 超限返回 `429 + GW_RATE_LIMITED`，可带 `Retry-After`

```java
public static final String QPS_SCRIPT = """
    local key = KEYS[1]
    local rate = tonumber(ARGV[1])
    local capacity = tonumber(ARGV[2])
    local now = tonumber(ARGV[3])
    local last = tonumber(redis.call('hget', key, 'last'))
    local tokens = tonumber(redis.call('hget', key, 'tokens'))
    if last == nil then last = now tokens = capacity
    else
      tokens = math.min(capacity, tokens + (now - last) * rate / 1000)
    end
    if tokens < 1 then return 0 end
    redis.call('hset', key, 'last', now, 'tokens', tokens - 1)
    return 1
""";
```

### 6.2 消息削峰

- 权重 = `input_tokens + max_tokens`（复用 `TokenCounterService` 预估）
- 复用 `FairDistributedRateLimiter` 的 Redis ZSet 公平队列：小请求先出，大请求排队
- 排队有 `max_wait_seconds`（默认 20s），超时返回 `GW_QUEUE_TIMEOUT`

### 6.3 并发控制

- 复用 `FairDistributedRateLimiter` 全局并发额度（bootstrap 已有同款实现，网关侧独立实例或统一 Redis key 前缀隔离）
- `acquire` 成功后持有租约，`finally` 释放，链路超时自动 expire

### 6.4 Token 配额

- 预算来源：key > 租户 > 无限（级联取最小可用）
- 月度滚动：`t_ai_usage_month` 按月累计，月初自动开新行
- 请求前检查：`used + 本请求预估(max_tokens) > quota` → 拒绝 `GW_QUOTA_EXCEEDED`
- 请求后扣减：以真实 `usage` 计，Redis Lua `INCRBY` 原子累加，异步写 DB
- **流式中途超配额**：允许本次完成，下次拒绝（避免半截流浪费）；后续可加"超配额立即断流"开关

---

## 七、复用 infra-ai 的对接点

| 网关需求 | 复用 infra-ai 组件 | 网关新增 |
|---|---|---|
| 流式聊天 | `RoutingLLMService` / `ProbeStreamBridge` / `OpenAIStyleSseParser` / `StreamCallback` | 入站 Controller + SSE 转发 |
| 非流式聊天 | `LLMService` / `OpenAIResponseParser` | 入站参数透传 |
| Embedding | `EmbeddingService` / `RoutingEmbeddingService` | `/v1/embeddings` 入站 |
| Rerank(可选) | `RerankService` / `RoutingRerankService` | 后续按需 |
| Token 预估 | `TokenCounterService`(启发式) | 削峰/配额前预估 |
| 熔断/故障转移 | `ModelRoutingExecutor` + `ModelHealthStore` | 不重写, 透传错误码 |
| SSE 解析 | `OpenAIStyleSseParser` | 统一 token 流 → SSE 帧 |

---

## 八、错误码（按业务细化）

```text
GW_INVALID_REQUEST        — 请求体不合法 / 参数缺失
GW_KEY_MISSING            — 未携带 API Key
GW_KEY_INVALID            — Key 格式错误或不存在
GW_KEY_REVOKED            — Key 已吊销
GW_KEY_EXPIRED            — Key 已过期
GW_MODEL_FORBIDDEN        — 租户无该模型访问权限
GW_MODEL_NOT_FOUND        — 模型别名不存在
GW_RATE_LIMITED           — QPS 限流(429)
GW_QUEUE_TIMEOUT          — 排队超时
GW_CONCURRENCY_LIMITED    — 并发达到上限
GW_QUOTA_EXCEEDED         — Token 配额不足
GW_UPSTREAM_ERROR         — 上游模型调用失败(透传 infra-ai 细节)
GW_INTERNAL_ERROR         — 网关内部错误
```

---

## 九、幂等 / 安全 / 边界

| 关注点 | 处理 |
|---|---|
| Key 安全 | 只存 SHA-256；签发明文仅返回一次；支持吊销即时失效(Redis 清缓存) |
| 配额原子性 | 检查与扣减分开，扣减 Lua INCRBY 原子；并发下不超扣 |
| 并发释放 | `try/finally` 释放租约；Redis 租约带 TTL 兜底防泄漏 |
| 审计不阻塞 | 请求日志异步批量写，失败降级记日志，不影响主链路 |
| SSRF 防护 | 别名 provider 指向内网模型网关需白名单(与爬虫白名单同思路) |
| SSE 中断 | 客户端断开 → 取消上游流(复用 `StreamCancellationHandles`) |
| 敏感字段 | 日志不打印 full key、messages 正文脱敏 |
| 重放 | 网关不天然防重放，靠配额与限流兜底；对账可查 request_log |

---

## 十、实施步骤

| 阶段 | 内容 | 交付物 |
|---|---|---|
| Phase 1 | `ai-gateway` 骨架：入站 Key 鉴权 + `/v1/models` + `/v1/chat/completions` 非流式转发 infra-ai | 一条链路通：sk-key → 鉴权 → 路由别名 → 模型调用 → 返回 |
| Phase 2 | 流式 chat：SSE 解析 + 转发 + 取消透传 | 流式可用 |
| Phase 3 | `/v1/embeddings` + Token 预估接入 | embedding 链路 |
| Phase 4 | 四维治理：QPS / 削峰 / 并发 / 配额 + 错误码 | 流量控制全上 |
| Phase 5 | 审计：请求日志 + 月度用量 + 配额扣减原子化 | 可查可用量 |
| Phase 6 | 管理端：Key 签发/吊销/配额 CRUD 接口（REST，非前端优先） | 数字化管理 |
| Phase 7 | 租户-模型权限关系表 + GET /v1/models 权限过滤 | 多租户隔离完备 |

---

## 十一、待确认细节

1. **模型别名映射方式**：Phase 1 直接走 infra-ai 候选组（别名仅一个名字），还是先建 `t_ai_model_alias` 表 DB 路由？（建议：Phase 1 先 DB 表，因为需要给配额/权限字段）
2. **管理端形态**：先做 REST API（供内部调用/后续接前端），还是直接上管理界面？
3. **配额粒度**：`max_tokens` 预估纳入请求前配额检查，是否会误拒"输出很长但配额刚好"的请求？（可选：预留 20% 缓冲）
4. **是否需要 `/v1/rerank`**：下游有重排需求才做，Phase 1 可先不做