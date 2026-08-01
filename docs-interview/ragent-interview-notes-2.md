# Ragent AI — 面试学习笔记

> 基于源码级分析的企业级 Agentic RAG 平台。
> 每个结论有对应代码文件与行号支撑。

---

## 一、项目概览

| 维度 | 数据 |
|:---|:---|
| 后端 | Java (Spring Boot 3)，约 40000 行 / 400+ 源文件 |
| 前端 | React 18 + TypeScript，约 18000 行 |
| 数据库 | PostgreSQL 20 张业务表 + Redis + Milvus/PGVector |
| 部署 | Docker Compose |
| 定位 | 企业内部部署型 RAG，非 SaaS 多租户 |

### 四大模块

| 模块 | 职责 |
|:---|:---|
| **framework** | 通用基础设施：异常体系、幂等、Snowflake ID、用户上下文透传、SSE 封装 |
| **infra-ai** | 模型基础设施：多供应商抽象、模型路由、熔断降级、健康检查、Rerank、Embedding |
| **bootstrap** | 业务核心：RAG 全链路（会话、检索、意图识别、知识库、入库流水线） |
| **mcp-server** | MCP 协议服务端，独立部署，提供工具（天气查询、工单等） |

---

## 二、数据库表结构设计分析（面试要点）

### 2.1 整体 20 张表分四个域

```
会话域:    t_user, t_conversation, t_message, t_conversation_summary, t_message_feedback, t_sample_question
知识库域:  t_knowledge_base, t_knowledge_document, t_knowledge_chunk, t_knowledge_document_chunk_log,
           t_knowledge_document_schedule, t_knowledge_document_schedule_exec, t_knowledge_vector
意图域:    t_intent_node, t_query_term_mapping
追踪域:    t_rag_trace_run, t_rag_trace_node
审计域:    t_biz_change_log
入库域:    t_ingestion_pipeline, t_ingestion_pipeline_node, t_ingestion_task, t_ingestion_task_node
```

---

### 2.2 主键设计 — 全表统一 Snowflake 雪花 ID

每张表的主键都是 `VARCHAR(20)`，使用 Snowflake 算法生成。**设计考量**：

| 方案 | 优点 | 缺点 |
|:---|:---|:---|
| 自增 ID | 简单 | 分布式多实例冲突、暴露业务量、不适合分库分表 |
| UUID | 全局唯一 | 36 字符长，索引膨胀，无序导致 B+Tree 页分裂 |
| **Snowflake（本项目）** | 64bit 全局唯一、趋势递增、B+Tree 友好、19 位数字 | 依赖时钟 |

**面试话术**：“我们所有表统一使用 Snowflake 算法生成 VARCHAR(20)​ 的主键。
选型原因是：相比自增 ID，它支持分布式部署，不会因为多实例写入产生冲突；相比 UUID，它是趋势递增的，对 InnoDB 的 B+Tree 非常友好，避免了随机插入导致的页分裂和碎片问题。
Snowflake 生成的 ID 是 64 位整数，转成十进制后固定 19 位，用 VARCHAR(20) 存储既能保证跨数据库的兼容性，又不影响索引性能。
另外，全局唯一的特性让我们在未来做分库分表时，不需要重构主键策略，系统的扩展性会更好。唯一的代价是对服务器时钟有依赖，我们通过 NTP 同步 + WorkerID 持久化来规避时钟回拨风险。”

---

### 2.3 逻辑删除 — 所有表统一 `deleted SMALLINT DEFAULT 0`

**面试拷问**："为什么每张表都有 deleted 字段？"

- **数据安全**：用户误删可以恢复，客服排查问题时需要看历史数据
- **审计合规**：`t_biz_change_log` 的 before/after snapshot 引用的原始记录必须保留
- **会话连续性**：消息被删除不影响历史上下文的加载（加载时过滤 `deleted=0`）
- **索引有效性**：`SELECT ... WHERE deleted=0 ORDER BY create_time` 走联合索引

**UNIQUE 约束中的 deleted**：`t_ingestion_pipeline` 的约束是 `UNIQUE (name, deleted)`，允许同名 Pipeline 被删除后重新创建——已删除的 name+deleted=1，新建的 name+deleted=0，不冲突。

---

### 2.4 会话域表设计

#### t_conversation — 会话列表

```sql
id VARCHAR(20) PRIMARY KEY,
conversation_id VARCHAR(20) NOT NULL,  -- 会话 ID（业务标识）
user_id VARCHAR(20) NOT NULL,
title VARCHAR(128) NOT NULL,           -- LLM 自动生成标题
last_time TIMESTAMP,                   -- 最后活跃时间
UNIQUE (conversation_id, user_id)
INDEX idx_user_time (user_id, last_time)  -- 支持"我的会话列表按时间排序"
```

**设计要点**：
- `id` 是主键（Snowflake），`conversation_id` 是业务标识。**主键和业务键分离**——conversation_id 由应用生成，可以在分库分表时做路由键
- 标题由 LLM 从首轮对话中自动生成（`ConversationTitleGenerator`），提示词约定了 max 30 字
- `last_time` 设计：每发一条消息更新一次，不需要 count 查询就能按活跃度排序

#### t_message — 消息表（短记忆存储）

```sql
id VARCHAR(20) PRIMARY KEY,
conversation_id VARCHAR(20) NOT NULL,
user_id VARCHAR(20) NOT NULL,
role VARCHAR(16) NOT NULL,           -- "user" / "assistant"
content TEXT NOT NULL,
thinking_content TEXT,               -- 深度思考中间推理
thinking_duration INTEGER,           -- 思考耗时（秒）
INDEX idx_conversation_user_time (conversation_id, user_id, create_time)
```

**设计要点**：
- `role` 用 `VARCHAR(16)` 而非 `ENUM`：PostgreSQL 没有 ENUM（MySQL 有），VARCHAR 普适
- `thinking_content` 存储 Claude/DeepSeek-R1 等模型的 chain-of-thought 中间推理，与 `content` 分离——前端可折叠展示思考过程
- **索引覆盖加载模式**：`idx_conversation_user_time` 是联合索引（conversation_id, user_id, create_time），`loadHistory` 查询覆盖索引不回表

#### t_conversation_summary — 摘要表（长记忆存储）

```sql
id VARCHAR(20) PRIMARY KEY,
conversation_id VARCHAR(20) NOT NULL,
user_id VARCHAR(20) NOT NULL,
last_message_id VARCHAR(20) NOT NULL,  -- 摘要覆盖到的最后一条消息 ID
content TEXT NOT NULL,
INDEX idx_conv_user (conversation_id, user_id)
```

**设计要点**：
- **与消息表分离**：摘要不是消息的一种——不需要 role、不需要 thinking_content。独立存储避免表膨胀（一个会话只有几条摘要 vs 几百条消息）
- `last_message_id` 是关键设计：每次查 `loadLatestSummary` 时用它判断"旧消息有没有滑出窗口需要更新摘要"，避免重复摘要已覆盖的消息
- **每次 INSERT 新摘要而非 UPDATE**：保留历史摘要版本，方便排查摘要质量问题。查最新用 `ORDER BY update_time DESC LIMIT 1`

#### t_message_feedback — 反馈表

```sql
message_id VARCHAR(20) NOT NULL,
vote SMALLINT NOT NULL,              -- 1: 赞, -1: 踩
reason VARCHAR(255),                 -- 预置选项
comment VARCHAR(1024),               -- 自由评论
UNIQUE (message_id, user_id)         -- 一人一消息只能反馈一次
```

**设计要点**：
- `UNIQUE (message_id, user_id)` 防重复投票——同一个用户对同一条消息只能有一个反馈
- `reason` + `comment` 分离：reason 是预置选项（"不准确""不完整""已过时"），comment 是自由文本。便于统计反馈原因分布
- **DPO 数据基础**：vote=1 的是 chosen 候选，vote=-1 的是 rejected 候选

---

### 2.5 知识库域表设计

#### t_knowledge_base — 知识库

```sql
id VARCHAR(20) PRIMARY KEY,
name VARCHAR(128) NOT NULL,
embedding_model VARCHAR(64) NOT NULL,    -- 此知识库绑定的 Embedding 模型
collection_name VARCHAR(64) NOT NULL,    -- 对应 Milvus 中的 collection 标量值
UNIQUE (collection_name)
```

**设计要点**：
- **每个知识库绑定自己的 embedding 模型**：不同模型产出的向量不可混用（维度不同、语义空间不同）。这个字段保证了切换模型时不会影响到其他知识库
- `collection_name` 是逻辑隔离键（不是物理 collection），用于 Milvus 内的标量过滤：`filter = 'collection_name == "hr_kb"'`

#### t_knowledge_document — 文档

```sql
id VARCHAR(20) PRIMARY KEY,
kb_id VARCHAR(20) NOT NULL,
doc_name VARCHAR(256) NOT NULL,
enabled SMALLINT DEFAULT 1,
chunk_count INTEGER DEFAULT 0,
file_url VARCHAR(1024) NOT NULL,         -- 对象存储路径
file_type VARCHAR(16) NOT NULL,          -- PDF/Word/Excel/URL
file_size BIGINT,
process_mode VARCHAR(16) DEFAULT 'chunk',  -- chunk / pipeline
status VARCHAR(16) DEFAULT 'pending',      -- pending/running/success/failed
source_type VARCHAR(16),                   -- file / url
source_location VARCHAR(1024),             -- 原始 URL（URL 类型文档）
schedule_enabled SMALLINT,                 -- 定时刷新开关
schedule_cron VARCHAR(64),                 -- Cron 表达式
chunk_strategy VARCHAR(32),               -- 切分策略
chunk_config JSONB,                        -- 切分配置 JSON
pipeline_id VARCHAR(20),                  -- Pipeline 模式下的 pipeline ID
```

**面试要点**：

1. **`status` 状态机**：`pending → running → success/failed`。delete/update 接口直接拒绝 RUNNING 状态——保证并发安全
2. **`process_mode` 双模式**：CHUNK 模式（直配 chunk_strategy + chunk_config）vs PIPELINE 模式（走完整 5 节点流水线）。通过 `validateAndNormalizeChunkConfig()` 校验配置的合法性
3. **`schedule_*` 字段放在 document 表而非 schedule 表**：URL 类型文档的定时刷新配置是其固有属性，与文档 1:1 关系，不需要额外 JOIN
4. **`chunk_config` 用 JSONB**：不同切分策略的参数不同——FIXED_SIZE 需要 chunkSize+overlapSize，STRUCTURE_AWARE 需要 targetChars+maxChars+minChars。JSONB 支持灵活 schema，不需要 EAV 模式
5. **`kb_id` 索引**：`CREATE INDEX idx_kb_id ON t_knowledge_document (kb_id);`——查询"某个知识库下的所有文档"是最频繁的后台操作

#### t_knowledge_chunk — 分块表

```sql
id VARCHAR(20) PRIMARY KEY,
kb_id VARCHAR(20) NOT NULL,
doc_id VARCHAR(20) NOT NULL,
chunk_index INTEGER NOT NULL,        -- 块在文档中的序号（用于还原原文顺序）
content TEXT NOT NULL,
content_hash VARCHAR(64),            -- SHA-256 内容哈希
char_count INTEGER,                  -- 字符数
token_count INTEGER,                 -- Token 数
enabled SMALLINT DEFAULT 1,
INDEX idx_doc_id (doc_id)
```

**设计要点**：
- `chunk_index` 的重要性：删旧写新时按 doc_id 全量替换，chunk_index 用于上下文组装时按原文顺序拼接。没有这个字段，同文档的 chunk 只能按检索相关性排列——LLM 看到的是乱序碎片
- `content_hash`：SHA-256 去重键。DedupPostProcessor 用 id 去重，FusionPostProcessor 用 SHA-256 去重（id 为空时）。比 String.hashCode() 可靠——32 位哈希碰撞不可忽略（"Aa" 和 "BB" 的 hashCode 相同）
- `char_count` + `token_count`：可为后续的"按 token 数做窗口"提供数据基础，当前仅记录未使用

**面试追问**："chunk 表和向量表的关系？"

`t_knowledge_chunk`（MySQL）存 chunk 元数据 + 正文，`t_knowledge_vector`（PGVector）或 Milvus 存向量 + metadata。两者通过 chunk_id 关联。富化处理器查询 `t_knowledge_chunk` 回表补齐 docId/docName/chunkIndex。**双写是在一个 Spring 事务中的**：`transactionOperations.executeWithoutResult`。

#### t_knowledge_document_chunk_log — 分块执行日志

```sql
doc_id VARCHAR(20) NOT NULL,
status VARCHAR(16) NOT NULL,
extract_duration BIGINT,    -- 文本提取耗时 ms
chunk_duration BIGINT,      -- 切分耗时 ms
embed_duration BIGINT,      -- Embedding 耗时 ms
persist_duration BIGINT,    -- DB 持久化耗时 ms
total_duration BIGINT,      -- 总耗时 ms
chunk_count INTEGER,
error_message TEXT,
```

**设计要点**：
- **4 阶段耗时拆分**：Extract → Chunk → Embed → Persist。排查慢文档问题时可精确定位是 MinerU 解析慢（extract），还是 Embedding API 慢（embed），还是大表写入慢（persist）
- **每次重分块 INSERT 新记录**：保留历史日志，方便对比不同切分策略的性能

#### t_knowledge_document_schedule — 定时刷新调度表

```sql
doc_id VARCHAR(20) NOT NULL,          UNIQUE
kb_id VARCHAR(20) NOT NULL,
cron_expr VARCHAR(64),
enabled SMALLINT DEFAULT 0,
next_run_time TIMESTAMP,              -- 下次执行时间
last_run_time TIMESTAMP,
last_success_time TIMESTAMP,
last_status VARCHAR(16),              -- SUCCESS/SKIPPED/FAILED
last_error VARCHAR(512),
last_etag VARCHAR(256),               -- 上次 HTTP ETag
last_modified VARCHAR(256),           -- 上次 HTTP Last-Modified
last_content_hash VARCHAR(128),       -- 上次 SHA-256 内容哈希
lock_owner VARCHAR(128),              -- 分布式锁持有者
lock_until TIMESTAMP,                 -- 锁过期时间
INDEX idx_next_run (next_run_time),
INDEX idx_lock_until (lock_until),
```

**面试要点**：

1. **分布式锁实现在 DB 而非 Redis**：`lock_owner` 存储实例标识（`kb-schedule-host1:uuid`），`lock_until` 存过期时间。抢锁是 UPDATE 带 WHERE 的乐观锁。心跳续约 + 过期自动释放。选 DB 锁而非 Redis 锁的原因：锁信息和调度记录在同一行，一致性更强
2. **三级变更检测的基线值**：last_etag、last_modified、last_content_hash 持久化在调度表中。每次刷新写回最新值，供下次 HEAD 请求对比
3. **`next_run_time` 索引**（idx_next_run）：scan() 的核心查询 `WHERE enabled=1 AND next_run_time <= now()` 走索引
4. **两阶段锁**：`lock_until` 先抢行级锁（idx_lock_until 加速过期锁扫描），`lock_owner` 再校验持有者身份。两步组合保证了"不会因为实例宕机导致调度永不被执行"

#### t_knowledge_document_schedule_exec — 调度执行记录

```sql
schedule_id VARCHAR(20) NOT NULL,
doc_id VARCHAR(20) NOT NULL,
status VARCHAR(16) NOT NULL,          -- SUCCESS/SKIPPED/FAILED
message VARCHAR(512),
file_name VARCHAR(512),               -- 下载后的文件名
file_size BIGINT,
content_hash VARCHAR(128),            -- 下载后的 SHA-256
etag VARCHAR(256),                    -- 下载后的 ETag
last_modified VARCHAR(256),           -- 下载后的 Last-Modified
INDEX idx_schedule_time (schedule_id, start_time)
```

**设计要点**：
- **与主表分离**：一条调度记录对应多条执行记录（1:N），方便排查"哪次执行失败、为什么失败"
- `message` 记录 SKIPPED 原因（"远程文件未变化""文档正在分块中""定时已关闭"），运营可视

---

### 2.6 意图域表设计

#### t_intent_node — 意图树节点

```sql
intent_code VARCHAR(64) NOT NULL,       -- 业务唯一标识（如 biz-oa-intro）
name VARCHAR(64) NOT NULL,              -- 展示名
level SMALLINT NOT NULL,                -- 0:DOMAIN 1:CATEGORY 2:TOPIC
parent_code VARCHAR(64),                -- 父节点 code（自引用树结构）
kind SMALLINT DEFAULT 0,                -- 0:KB 知识库 1:SYSTEM 闲聊 2:MCP 工具
collection_name VARCHAR(128),           -- 知识库节点 → 关联 collection
top_k INTEGER,                          -- 节点级 TopK
mcp_tool_id VARCHAR(128),               -- MCP 节点 → 工具 ID
prompt_snippet TEXT,                    -- 提示词片段（注入到 <rules>）
param_prompt_template TEXT,             -- MCP 参数提取提示词
sort_order INTEGER DEFAULT 0,           -- 同级排序
enabled SMALLINT DEFAULT 1,
```

**设计要点**：

1. **邻接表模型（parent_code）存储树**：不是嵌套集（nested set）也不是物化路径。理由：意图树的节点数不多（几十个），读多写少，邻接表最简单。`IntentTreeFactory` 从 DB 加载后用 `flatten()` 展平 + `fillFullPath()` 构建内存树，Redis 缓存整棵树
2. **`kind` 三态枚举**：一个字段决定整个检索策略。KB→走多通道检索，MCP→走工具调用+参数提取，SYSTEM→直接 LLM 回复
3. **`prompt_snippet` 的注入时机**：在 `DefaultContextFormatter.formatKbContext()` 中渲染成 `<rules>` 标签，拼入最终 Prompt。不是改 system prompt 而是改 context 模板——per-intent 的规则注入
4. **`param_prompt_template` MCP 专属**：`McpParameterExtractor` 读取此字段做定制化参数提取。"查天气"的工具和"查订单"的工具需要的参数提取提示词完全不同
5. **`top_k` 可为 NULL**：NULL 表示使用默认 TopK。只有明确配置的节点才覆盖默认值，灵活性最大化

#### t_query_term_mapping — 术语归一化

```sql
domain VARCHAR(64),                    -- 领域（可选）
source_term VARCHAR(128) NOT NULL,     -- 用户常说的词
target_term VARCHAR(128) NOT NULL,     -- 知识库中的标准词
match_type SMALLINT DEFAULT 1,         -- 1:精确 2:模糊
priority INTEGER DEFAULT 100,          -- 优先级（越小越优先）
enabled SMALLINT DEFAULT 1,
```

**设计要点**：
- **加载策略**：`loadMappings()` 按 `priority DESC, LENGTH(source_term) DESC` 排序——长词优先替换（"平安保险公司"在"平安"之前），避免短词把长词的一部分误替换
- **Redis 缓存**：`QueryTermMappingCacheManager` 做缓存读写。mapping 变更是低频操作（管理后台修改），读是高频（每次问答），Redis 命中率接近 100%
- **安全替换**：`QueryTermMappingUtil.applyMapping()` 判断"如果当前位置已经是 targetTerm 的开头则不重复替换"——防止把"平安保险公司"替换成"平安保险公司公司"

---

### 2.7 全链路追踪表设计

#### t_rag_trace_run + t_rag_trace_node — 树形调用链路

```sql
-- 一次问答一条 run 记录
t_rag_trace_run:
  trace_id VARCHAR(64) UNIQUE,         -- 全局唯一链路 ID
  entry_method VARCHAR(256),           -- 入口方法
  task_id VARCHAR(20),                 -- SSE 任务 ID
  conversation_id VARCHAR(20),
  user_id VARCHAR(20),
  status VARCHAR(16),                  -- RUNNING/SUCCESS/ERROR
  duration_ms BIGINT,
  extra_data TEXT,                     -- 扩展 JSON

-- 每个步骤一条 node 记录
t_rag_trace_node:
  trace_id VARCHAR(20) NOT NULL,       -- 属于哪条链路
  node_id VARCHAR(20) NOT NULL,        -- 节点 ID
  parent_node_id VARCHAR(20),          -- 父节点（形成调用树）
  depth INTEGER DEFAULT 0,             -- 深度
  node_type VARCHAR(16),               -- REWRITE/INTENT/RETRIEVE/...
  node_name VARCHAR(128),              -- @RagTraceNode 的 name
  status VARCHAR(16),                  -- 节点级状态
  duration_ms BIGINT,
  extra_data TEXT,
  UNIQUE (trace_id, node_id)
```

**设计要点**：

1. **树形而非扁平**：`parent_node_id` + `depth` 维护调用层级。`@RagTraceNode` AOP 切面自动记录 span 的父子关系——AOP 从 ThreadLocal 读当前 span，新 span 的 parent = 当前 span
2. **run 和 node 分离**：run 是一次问答的顶层记录，node 是各步骤的详细记录。管理后台可以展开一次问答的完整调用树——前端用 `parent_node_id` + `depth` 渲染树形表格
3. **`extra_data TEXT` 存储 JSON**：节点可以写自定义数据（如意图分类的候选列表、检索的 chunk 数量），管理后台按 tab 展示
4. **`task_id` 关联 SSE**：通过 task_id 可以关联到流式任务的取消/中断事件

---

### 2.8 入库流水线表设计

```
t_ingestion_pipeline  ──1:N──> t_ingestion_pipeline_node
        │                              │
        │  (task 引用 pipeline 模板)    │  (模板: 节点类型+配置+条件)
        ▼                              ▼
t_ingestion_task      ──1:N──> t_ingestion_task_node
                                    (实例: 节点状态+耗时+输出)
```

**设计要点**：

- **模板与实例分离**：Pipeline + PipelineNode 是模板（定义流程），Task + TaskNode 是实例（记录执行）。同一套模板可以被多次执行，每次产生新的 Task
- **`settings_json JSONB` + `condition_json JSONB`**：每个节点有不同的配置 schema（Parser 有 MIME 规则，Chunker 有策略参数），JSONB 无需为每种配置建表
- **`next_node_id` + `condition_json`**：节点支持条件分支——condition 表达式求值为 true 时才走 NextNode。`ConditionEvaluator` 用 SpEL 或简单 JSON 表达式做条件求值
- **TaskNode 的 `output_json TEXT`**：存储节点的全量输出（如 Parser 产出的文本块数、Chunker 产出的 chunk 数），供排查 Pipeline 执行失败时定位

---

### 2.9 审计日志表 — t_biz_change_log

```sql
biz_type VARCHAR(64) NOT NULL,       -- 业务对象类型（如 KNOWLEDGE_DOCUMENT）
biz_id VARCHAR(64) NOT NULL,         -- 业务对象主键
operation_type VARCHAR(32) NOT NULL, -- CREATE/UPDATE/DELETE
action_desc VARCHAR(512),            -- 操作描述
before_snapshot JSONB,               -- 变更前快照
after_snapshot JSONB,                -- 变更后快照
change_diff JSONB,                   -- 变更差异
operator_id VARCHAR(64),             -- 操作人 ID
operator_name VARCHAR(128),          -- 操作人名称
success BOOLEAN DEFAULT TRUE,
class_name VARCHAR(255),             -- 触发类名
method_name VARCHAR(255),            -- 触发方法名
ip VARCHAR(64),
user_agent VARCHAR(512),
INDEX (biz_type, biz_id),
INDEX (create_time)
```

**设计要点**：

1. **AOP 驱动**：`@LogRecord` 注解 + AOP 切面自动记录——业务代码无需手动写审计逻辑
2. **`JSONB` 存快照**：before_snapshot 用 `BeanUtil.copyProperties` 序列化，after_snapshot 同理。change_diff 可由应用层计算（对比两个 JSONB）或由 PostgreSQL 的 JSONB 运算符计算
3. **`class_name` + `method_name`**：出问题时能定位到具体方法，比"文档-更新"这种泛泛的日志有用得多
4. **条件记录**：`condition = BizChangeLogContext.RECORD_CONDITION`——只在配置开启时才记录，生产环境可按需关闭以省存储

---

### 2.10 面试常见追问

**问：为什么全表用 VARCHAR(20) 存 ID 而不是 BIGINT？**

Snowflake 生成的 ID 是 64bit（19 位十进制）。BIGINT 也能存，但以下场景 VARCHAR 更好：
- 前端 JavaScript 的 Number 类型只能精确表示到 2^53（约 16 位），后端传 JSON 时 BIGINT 会丢失精度
- VARCHAR 可以直接拼接到 URL 中（RESTful API），BIGINT 在 JS 端需要额外处理
- 未来如果要更换 ID 生成策略（如改用 ULID 的 26 字符），VARCHAR 不需要改表结构

**问：chunk 表和向量表为什么要分开？**

因为向量存储是可替换的（Milvus / PGVector）。MySQL 存 chunk 元数据保证了解析/检索/富化的核心数据不依赖向量库。如果从 Milvus 切换到 PGVector，只需要替换 VectorStoreService 实现，chunk 表不受影响。

**问：schedule 表为什么把锁字段放在业务表里而不是用专门的锁表？**

分布式锁的常见做法是用 Redis SETNX 或单独一张 lock 表。这个项目把锁放在业务记录行里（lock_owner, lock_until），好处是：
- 锁和业务数据在同一行，抢锁+读配置在一个 UPDATE-SELECT 里完成
- 不需要额外的 Redis 依赖
- 锁的状态（谁持有、何时过期）是业务记录的一部分，管理后台可以直接展示
- 缺点：DB（而非 Redis）承担锁操作，高并发下不如 Redis 快。但调度的 QPS 很低（每秒最多几十个文档），DB 锁完全够用

**问：JSONB 字段怎么建索引？**

`t_knowledge_vector` 的 metadata 字段建了 GIN 索引（`USING gin(metadata)`），支持 `@>`（包含）、`?`（键存在）等 JSONB 运算符查询。`t_knowledge_document.chunk_config` 没有建索引——它是读取后应用层解析，不需要按 JSON 内部字段查询。

---

## 三、一次用户问答的完整管线

```
用户提问 → ① loadMemory → ② rewriteQuery → ③ resolveIntents
  → ④ handleGuidance(短路) → ⑤ handleSystemOnly(短路) → ⑥ retrieve → ⑦ streamRagResponse
```

### ① loadMemory()：并行加载

```java
// DefaultConversationMemoryService.load() — 两条并行查询
CompletableFuture<ChatMessage> summaryFuture = ...loadSummaryWithFallback();
CompletableFuture<List<ChatMessage>> historyFuture = ...loadHistoryWithFallback();
// allOf 等待 → attachSummary(summary, history) → 摘要在前，历史在后
```

### ② rewriteQuery()：术语归一化 + LLM 改写

1. `QueryTermMappingService.normalize()` — DB 规则（Redis 缓存），"平安保司"→"平安保险公司"
2. `MultiQuestionRewriteService.rewriteWithSplit()` — LLM 改写拆分
   - 保留专有名词/限制条件，删除礼貌用语/无关描述
   - 多问号拆、抽象对比不拆（拆了丢对比信息）
   - 失败回退规则：按 `[？?。；;\n]` 分隔符切

### ③ resolveIntents()：并行意图分类

每个子问题通过 intentClassifyExecutor 并行调 LLM 分类。三种节点：KB(检索)/MCP(工具)/SYSTEM(闲聊)。score<0.35 过滤，每子问题最多 3 个意图。

### ④⑤ 短路

歧义→推选项让用户选；全 SYSTEM→直接 LLM 回复不走检索。

---

## 四、多路检索 + RRF + Rerank

### 四路并行

| 通道 | 优先级 | 核心能力 |
|:---|:---|:---|
| INTENT_DIRECTED | 1 | 意图路由到特定 collection，精准 |
| KEYWORD | 5 | BM25 精确匹配（订单号、错误码） |
| VECTOR_GLOBAL | 10 | 语义理解（安全网：意图分<0.6 触发） |
| WEB_SEARCH | 20 | 联网搜索（配置 YDC_API_KEY） |

### 后处理器链

```
① Dedup (order=1): 按 id 去重，多路命中保留高分 / 高优先级通道版本
② RRF Fusion (order=5): score = Σ 1/(k+rank+1), k=60
③ Rerank (order=10): cross-encoder 精排 (qwen3-rerank), 候选池由 rerankCandidateLimit=50 控制
④ MetadataEnrich (order=20): 回表补 docId/docName/chunkIndex
```

### RRF 参数调优

- k 越大，名次差异越不敏感 → 适合通道质量参差不齐时
- k 越小，高名次优势突出 → 适合某通道明显更准时
- rerankCandidateLimit: 太小截掉好候选，太大 Rerank 延迟飙升。经验值 40-100

---

## 五、文档切分策略

### 三路分发 — StructuredChunkingService

```
chunkSize=-1 → 整篇单块
blocks 非空 → block-aware（按 Block 类型分发专属 chunker）
纯文本 → legacy 策略（FIXED_SIZE / STRUCTURE_AWARE）
```

### 两种文本策略

| 策略 | 默认参数 | 特点 |
|:---|:---|:---|
| FIXED_SIZE | 512 + overlap 128 | 滑动窗口 + 边界对齐，修复 URL 断行，不吞列表项 |
| STRUCTURE_AWARE | target 1400, max 1800, min 600 | 识别 Heading/Code/Atomic/Para 四种块，只在块边界切 |

### block-aware 精细切分

解析器(MinerU/Tika)产出强类型 Block，按类型分发：

| Chunker | 关键处理 |
|:---|:---|
| **TableChunker** | 每块带完整表头；content=markdown 展示用，embeddingText=key-value 嵌入用（"部门: 研发部; 预算: 500万"） |
| **ParagraphChunker** | 按 maxChars + overlap 滑动窗口 |
| **CodeChunker** | 按 maxChars 切（保持代码完整性） |
| **ListChunker** | 短列表(≤15) atomic 整块，长列表按 10 项拆 |
| **ImageChunker** | embeddingText 不含 URL 只取文字描述 |
| **HeadingHandler** | 不产 chunk，累积 outlinePath 章节路径 |

### ChunkPacker

各 chunker 只拆不并 → ChunkPacker 贪心打包：相邻 PARAGRAPH/LIST/IMAGE 合并到 maxChars。TABLE/CODE 保持原子不合并。断块处块级重叠（完整块尾部复现）。

### 大表切分

双额度：行数硬上限(rowsPerChunk=50) + 体量预算(maxChars)。贪心累加，单行超预算时保持整行原子自成一块。每块带完整表头 + sectionContext。

### 父子文档等价实现

不维护两套 chunk。通过 docId 聚合 + outlinePath + sectionContext 三套元数据达成等同效果：检索命中精细 chunk → 按 docId 分组 → 按 chunkIndex 还原原文顺序 → `<content source="员工手册">` 标注来源。

---

## 六、长短期记忆

### 存储（关系表，非 JSON）

```sql
t_message: id, conversation_id, user_id, role, content, thinking_content, thinking_duration
t_conversation_summary: id, conversation_id, user_id, last_message_id, content
```

**为什么不是 JSON？** 消息数据是典型 OLTP 场景——按会话+时间查询、排序、部分更新，关系表天然支持。JSON 适合配置类数据（意图树节点配置）。

### 加载

每次问答 `load()` 并行加载摘要和历史：
- 摘要：`SELECT ... ORDER BY update_time DESC LIMIT 1`
- 历史：`SELECT ... ORDER BY create_time DESC LIMIT N`（N = historyKeepTurns×2）
- 合并：`[<conversation-summary>摘要</conversation-summary>] [消息1] [消息2] ...`

### 摘要触发

`summaryEnabled=true` + 角色=ASSISTANT + 消息数≥summaryStartTurns → 异步执行 → Redis 锁 → 渐进式摘要窗口计算 → LLM 生成摘要（≤200 字符）→ INSERT 新记录

### 摘要设计

**绝对禁止记录具体答案**——只记话题+状态+约束，不记答案。RAG 实时检索最新文档，旧答案会和最新内容冲突。

---

## 七、Agent/MCP 工具调用

### 触发（非 Agent loop）

意图节点 kind=MCP + mcpToolId → McpParameterExtractor 调 LLM 提取参数 → Registry 查执行器 → McpClientToolExecutor.execute() 同步阻塞调用。多工具并行执行（mcpBatchExecutor 线程池）。

### 架构

```
bootstrap[:9090] ──HTTP SSE──▶ mcp-server[:9099]
  McpClientToolExecutor           McpServerConfig + WeatherMcpExecutor 等
```

### 业界方案

同步阻塞+线程池隔离（本项目）、异步回调+SSE（OpenAI）、流式回调（Claude tool_use）、Agent loop/ReAct（LangChain）。企业场景工具少、延迟可预测，同步阻塞+线程池隔离足够。优化方向：给 CompletableFuture 加 `.orTimeout()`。

---

## 八、文档自动同步

### 仅 URL 类型支持

配置 cron + schedule_enabled → upsertSchedule() 写调度表 → scan() 每 10s 扫描 → 分布式锁抢单 → ScheduleRefreshProcessor.process()

### 三级变更检测

1. HTTP HEAD → 对比 ETag/Last-Modified → 相同 SKIPPED（零流量）
2. 下载 + SHA-256 → 对比 contentHash → 相同 SKIPPED
3. 确认变化 → 重新下载→解析→切分→嵌入→事务原子写入

### 原子写入

一个 Spring 事务中：删旧 chunks(MySQL) + 写新 chunks(MySQL) + 删旧向量(Milvus) + 写新向量(Milvus) + 更新文档状态

### 安全防护

DB 乐观锁、心跳续约、CAS 状态变更（RUNNING 拒绝并发操作）、60s 卡死恢复、文件切换+状态写回失败仍保留执行记录

---

## 九、意图识别的多重价值

每次对话必然触发。除了多路召回路由：
- KB/MCP/SYSTEM 三路分发
- Prompt 定制注入（`promptSnippet` → `<rules>` 标签，per-intent 领域适配）
- TopK 独立控制、歧义引导、全局检索安全网（意图分<0.6 降级）、MCP 参数提取模板

---

## 十、设计模式实战

| 模式 | 落地 |
|:---|:---|
| 策略模式 | SearchChannel, ChunkingStrategy, PostProcessor, McpToolExecutor |
| 注册表模式 | McpToolRegistry（自动发现 Bean，新增零配置）, IntentNodeRegistry |
| 责任链模式 | 后处理器链（Dedup→RRF→Rerank→Enrich），模型降级链 |
| 装饰器模式 | ProbeBufferingCallback（不修改回调增加首包探测） |
| 模板方法 | IngestionNode 基类 |
| sealed interface | ChunkingOptions（编译期类型安全） |
| AOP | @RagTraceNode（全链路追踪）, @ChatRateLimit（限流） |

---

## 十一、8 个专用线程池

mcpBatchExecutor, ragContextExecutor, ragRetrievalExecutor, intentClassifyExecutor, memoryLoadExecutor, memorySummaryExecutor, knowledgeChunkExecutor, defaultIntentClassifier — 全用 TtlExecutors 包装保证上下文透传。

---

## 十二、面试话术模板

### "介绍一下你做的 RAG 项目"

> Ragent 是一个企业级 RAG 平台。我负责核心检索链路。文档入库方面，基于结构化 Block 的分块策略——PDF 通过 MinerU 解析产出标题/表格/代码等 Block，按类型分发专属切分器。表格用 key-value 嵌入替代 markdown 表格，参考了 RAGFlow/STC 的做法。
>
> 检索方面，四路并行检索（意图定向+关键词 BM25+向量+联网），RRF 融合消除分数量纲差异，Rerank cross-encoder 精排。整个链路可观测——全链路 Trace 记录每一步的耗时。
>
> 记忆方面，滑动窗口+渐进式摘要双层架构。摘要只记话题不记答案，避免与实时检索冲突。
>
> 工程方面：三态熔断器、分布式调度锁+心跳、8 个专用线程池 TTL 上下文透传、逻辑删除+审计日志。总共约 40000 行 Java + 20 张业务表。

### "难点在哪里？"

> 最大难点是表格检索准确率。Embedding 模型读不懂 markdown 竖线对齐——列名和值的语义关系在向量化后丢失。解决方案：嵌入阶段用 key-value 格式渲染表格，把"部门: 研发部"这种语义关系写进字面文本。每个表格切片带 sectionContext 标明表身份（sheet 名+列名），切碎了也不丢上下文。

### "有什么可以优化的？"

> 1. 工具调用加超时（CompletableFuture.orTimeout）
> 2. 记忆读路径加 Redis 缓存（当前每次查 DB）
> 3. ChunkPacker 合并策略可用语义边界代替纯体量预算
> 4. RRF 的 k 值可根据通道质量动态自适应

### "为什么主键全用 VARCHAR(20)？"

> Snowflake 生成 64bit（19 位十进制）。VARCHAR 的原因：前端 JS Number 精度只有 2^53，BIGINT 在 JSON 序列化时会丢失精度；VARCHAR 可以直接拼 URL；未来换 ID 策略（如 ULID 26 字符）不需要改表结构。

### "为什么用 JSONB 而不是 EAV 模式存配置？"

> EAV（Entity-Attribute-Value）扩展性好但查询复杂。JSONB 在 PostgreSQL 中支持索引（GIN）、支持 `@>` 包含查询。切分配置、Pipeline 节点配置这类"每个节点 schema 不同且不需要按内部字段查询"的场景，JSONB 是最优解。

### "分布式锁为什么放 DB 而不是 Redis？"

> 调度的 QPS 很低（每秒几十个文档），DB 锁完全够用。DB 锁的优势：锁信息和业务记录在同一行，抢锁+读配置在一个 UPDATE-SELECT 中完成；不需要额外 Redis 依赖；管理后台可直接展示锁状态。心跳续约防止持有者宕机，过期自动释放。

---

---

## 十八、意图识别策略树与 Agent 流转机制

### 18.1 意图树的物理结构

意图树用 **邻接表模型** 存储，`t_intent_node` 表关键字段：

```sql
intent_code   parent_code   name         level     kind     collection_name   mcp_tool_id    top_k   prompt_snippet
biz           NULL          业务系统      DOMAIN(0) KB       NULL              NULL           NULL    NULL
biz-oa        biz            OA系统       CATEGORY  KB       NULL              NULL           NULL    NULL
biz-oa-intro  biz-oa        OA系统介绍   TOPIC(2)  KB       oa_collection     NULL           5       "请使用OA术语"
biz-oa-flow   biz-oa        OA审批流程   TOPIC(2)  KB       oa_collection     NULL           10      NULL
mcp-weather   NULL          天气查询      TOPIC(2)  MCP      NULL              weather_query  NULL    NULL
sys-chat      NULL          日常闲聊      TOPIC(2)  SYSTEM   NULL              NULL           NULL    "你好，我是小码"
```

**三层级枚举** — `IntentLevel` 类：

| 层级 | 枚举值 | 含义 |
|:---|:---|:---|
| DOMAIN(0) | 领域 | 顶层业务域（如"业务系统""集团信息化""系统交互"） |
| CATEGORY(1) | 分类 | 中层子系统（如"OA系统""保险系统""人事"） |
| TOPIC(2) | 主题 | 叶子节点，具体知识主题（如"OA系统介绍""审批流程"） |

**三种意图类型** — `IntentKind` 枚举：

| Kind | code | 含义 | 执行路径 |
|:---|:---|:---|:---|
| KB | 0 | 知识库检索 | 走多通道检索 + RRF + Rerank |
| MCP | 2 | 工具调用 | 走参数提取 + 工具执行 |
| SYSTEM | 1 | 系统交互 | 直接 LLM 回复，不走检索 |

**只有叶子节点参与分类打分**。`isLeaf()` 判断 `children` 是否为空。非叶子节点（DOMAIN、CATEGORY）只做结构分组，不出现在分类提示词中。

### 18.2 意图树的加载与缓存

```java
// DefaultIntentClassifier.loadIntentTreeData() — 三层缓存
1. 从 Redis 读取整棵树 (IntentTreeCacheManager.getIntentTreeFromCache)
2. Redis miss → 从 DB 加载 (loadIntentTreeFromDB) → 回填 Redis
3. 构建内存结构: flatten(展平) → 过滤叶子节点 → id2Node Map

// IntentTreeFactory 承担 DB 到内存的转换:
// DO → IntentNode → parent/children 组装 → fillFullPath(DOMAIN > CATEGORY > TOPIC)
```

**为什么缓存整棵树而非逐节点查询？** 意图树节点少（几十个），整棵树序列化成本低。每次分类需要所有叶子节点信息，逐节点查询反而更慢。Redis 缓存 + 本地内存双级缓存，命中后零 DB 查询。

### 18.3 分类流程 — LLM 一次性评所有叶子节点

```java
// DefaultIntentClassifier.classifyTargets(question)
// 1. 构建提示词 — 把所有叶子节点的 id/path/description/examples/kind 展平
for (IntentNode node : leafNodes) {
    sb.append("- id=").append(node.getId());
    sb.append("  path=").append(node.getFullPath());
    sb.append("  description=").append(node.getDescription());
    sb.append("  type=").append(node.isMCP() ? "MCP" : node.isSystem() ? "SYSTEM" : "KB");
    sb.append("  examples=").append(String.join(" / ", node.getExamples()));
}

// 2. 调 LLM: temperature=0.1, topP=0.3 — 极低温度保证分类一致性
ChatRequest request = ChatRequest.builder()
    .messages(List.of(ChatMessage.system(systemPrompt), ChatMessage.user(question)))
    .temperature(0.1D).topP(0.3D).thinking(false).build();
String raw = llmService.chat(request);

// 3. 解析 JSON 响应: [{"id":"biz-oa-intro","score":0.92,"reason":"问题询问OA系统整体功能"}]
```

**提示词 `intent-classifier.st` 的核心约束**：

```
# 实体导向问题 → 强匹配：关键实体必须在 id/path/description/examples 中出现
# 主题导向问题 → 主题词匹配 path/description
# 评分标准: >0.8 强匹配 / 0.4-0.8 中等 / <0.4 弱相关 → 建议返回空数组
# 数量控制: 默认返回 1 个，明确多问题时最多 3 个
# 系统限定: 问"OA系统"时不要选"保险系统"分类（跨系统防护）
```

### 18.4 从意图到执行 — 完整流转链路

```
用户提问: "北京今天天气怎么样？审批流程是什么？"
  │
  ▼
rewriteQuery(): LLM 改写拆分
  rewrittenQuestion: "北京天气查询 审批流程"
  subQuestions: ["北京天气查询", "审批流程"]
  │
  ▼
resolveIntents(): 每个子问题并行分类
  ┌─ "北京天气查询" → [{node: mcp-weather, score: 0.95, kind: MCP}]
  └─ "审批流程"     → [{node: biz-oa-flow, score: 0.88, kind: KB}]
  │
  ▼
IntentResolver.mergeIntentGroup():
  mcpIntents = [NodeScore(mcp-weather, 0.95)]
  kbIntents  = [NodeScore(biz-oa-flow, 0.88)]
  │
  ▼
RetrievalEngine.retrieve(): 并行执行
  ┌─ KB 路径: retrieveAndRerank()
  │    → kbIntents → MultiChannelRetrievalEngine.retrieveKnowledgeChannels()
  │    → 多通道检索 + RRF + Rerank → KbResult
  │
  └─ MCP 路径: executeMcpAndMerge()
       → mcpIntents → mcpBatchExecutor 线程池并行
       → McpParameterExtractor.extractParameters() — LLM 提取工具参数
       → McpClientToolExecutor.execute() — 同步调用 mcpClient.callTool()
       → ContextFormatter.formatMcpContext() — 格式化工具结果为 <data> 标签
  │
  ▼
RetrievalContext:
  mcpContext = "<data>【北京 今日天气】\n日期: 2024年...\n天气: 晴\n温度: 18°C ~ 28°C</data>"
  kbContext  = "<content source='OA系统文档'>OA审批包括提交申请→部门审批→人事审批三步</content>"
  │
  ▼
RAGPromptService.buildStructuredMessages(): 场景选择
  hasMcp()=true && hasKb()=true → PromptScene.MIXED
  → 使用 answer-chat-mcp-kb-mixed.st 模板
  → KB 证据 + MCP 证据 拼入同一个 Prompt
  │
  ▼
streamLLMResponse(): LLM 流式生成回复
  "今天北京天气晴朗，18°C~28°C。审批流程包括三步：提交申请→部门审批→人事审批。"
```

### 18.5 "Agent" 的本质 — 不是独立 Agent 循环

**面试核心话术**：这个项目的 "Agent" 不是 LangChain 的 ReAct loop（思考→行动→观察→思考...）。它采用的是**意图驱动的管线分流**模式：

```
意图树节点 = 微型 Agent 配置
  - KB 节点：Agent 的行为是"检索知识库"
  - MCP 节点：Agent 的行为是"调用工具获取实时数据"
  - SYSTEM 节点：Agent 的行为是"闲聊回复"

每个节点携带：
  - collection_name → 去哪里查
  - mcp_tool_id → 调哪个工具
  - top_k → 查多少条
  - prompt_snippet → 回答有什么规则
  - param_prompt_template → 工具参数怎么提取
```

**为什么不做 Agent loop？**

| 考量 | Agent Loop（ReAct） | 意图管线分流（本项目） |
|:---|:---|:---|
| 多步推理 | ✅ 支持 "查订单→根据金额查优惠→计算折扣" | ❌ 单步执行 |
| 延迟 | ❌ 每步一次 LLM 调用，3 步=3 次 LLM 延迟 | ✅ 一次分类 + 一次工具调用 |
| Token 消耗 | ❌ 每步要带完整历史 | ✅ 只调一次 LLM 分类 |
| 确定性 | ❌ LLM 可能反复改参数、无限循环 | ✅ 意图稳定 |
| 企业场景适配 | ❌ 企业场景工具调用少、逻辑简单 | ✅ 够用 |

**"意图 Agent 间的流转"** 在这个架构中表现为：

1. **同一轮对话中的并行执行**：多个子问题的 KB 和 MCP 意图通过线程池**并行**处理。不是 A→B→C 的顺序流转，而是"同时触发，各自执行，结果合并"
2. **跨轮流转**：通过记忆系统传递上下文。上一轮的摘要会作为 system 消息注入下一轮，LLM 自动衔接。这是隐式的 state machine，不是显式的 Agent 路由
3. **歧义引导 = 人机交互的流转**：当意图不清时，`IntentGuidanceService` 生成选项让用户选择 → 用户选择后下一轮意图直接命中 → 这是一种人参与的 Agent 流转

### 18.6 歧义引导的触发逻辑

```java
// IntentGuidanceService.detectAmbiguity() — 三道关卡
1. filterCandidates(): 只取 KB 意图中 score >= 0.35 的
2. 按 "系统节点" 分组：取每个系统内最高分的意图节点
3. 候选数 < 2 → 不触发

4. shouldSkipGuidance() — 快速通道:
   - 第一名分数/第二名分数 < 0.65(threshold-margin) → 意图明确，跳过
   - 用户问题中包含系统名(如"OA") → 用户已经指明了，跳过

5. confirmAmbiguity() — 确认歧义:
   - 第二名/第一名 >= 0.8 → 直接判定歧义
   - 第二名/第一名 在 [0.65, 0.8) → 调 LLM 二次确认 (ambiguityLLMChecker)
   - 第二名/第一名 < 0.65 → 不触发

6. 生成引导选项:
   "您想了解的是哪方面的数据安全规定？
    1. OA系统的数据安全
    2. 保险系统的数据安全"
```

**关键阈值**：
- `ambiguityScoreRatio = 0.8`：第二名分数达到第一名 80% 以上才触发歧义
- `ambiguityMargin = 0.15`：边界区间宽度，区间内调 LLM 二次确认

### 18.7 意图树的扩展性

**新增一个知识库节点**：
1. 在管理后台意图树编辑器中添加叶子节点
2. 配置：name、description、examples、collection_name、top_k、prompt_snippet
3. 保存 → DB INSERT → 清 Redis 缓存 → 下次分类自动生效
4. **不需要改代码、不需要重启服务**

**新增一个 MCP 工具节点**：
1. 在 mcp-server 中新增执行器类（实现 McpServerFeatures.SyncToolSpecification）
2. 在管理后台意图树中新增节点，kind=MCP，mcp_tool_id = 工具名
3. bootstrap 的 `McpClientAutoConfiguration` 启动时自动通过 MCP 协议发现新工具
4. **MCP 工具和意图树节点解耦** — 工具实现在 mcp-server，路由配置在意图树

**面试话术**："意图树的设计本质上是一个可配置的路由表 + 策略配置中心。每个叶子节点独立配置 collection_name、top_k、prompt_snippet、mcp_tool_id。新增知识库或工具不需要改检索管线代码——配置节点即生效。这是策略模式 + 注册表模式在配置层的体现。"

---

## 附录：核心文件索引

| 功能 | 核心文件 |
|:---|:---|
| 问答管线 | `StreamChatPipeline.java` |
| 查询改写 | `MultiQuestionRewriteService.java`, `user-question-rewrite.st` |
| 意图分类 | `DefaultIntentClassifier.java`, `IntentResolver.java`, `intent-classifier.st` |
| 多通道检索 | `MultiChannelRetrievalEngine.java` |
| RRF 融合 | `FusionPostProcessor.java` |
| Rerank 精排 | `RerankPostProcessor.java`, `BaiLianRerankClient.java` |
| 上下文组装 | `DefaultContextFormatter.java`, `context-format.st` |
| 切分入口 | `StructuredChunkingService.java`, `ChunkerNode.java` |
| 文本策略 | `FixedSizeTextChunker.java`, `StructureAwareTextChunker.java` |
| 表格切分 | `TableChunker.java` |
| 打包器 | `ChunkPacker.java` |
| 记忆 | `DefaultConversationMemoryService.java`, `JdbcConversationMemorySummaryService.java` |
| 文档同步 | `KnowledgeDocumentScheduleJob.java`, `ScheduleRefreshProcessor.java` |
| 变更检测 | `RemoteFileFetcher.java` |
| 向量库 | `MilvusVectorStoreService.java` |
| MCP | `McpClientToolExecutor.java`, `WeatherMcpExecutor.java` |
| 评测 | `EvalController.java` |
| 模型降级 | `ModelRoutingExecutor.java` |
| 限流 | `FairDistributedRateLimiter.java` |
| 配置 | `application.yaml`, `SearchChannelProperties.java`, `MemoryProperties.java` |
| 表结构 | `resources/database/schema_pg.sql` |

---

## 十九、多路召回归一化 — 分数不可比问题的完整解决方案

### 19.1 问题本质：五条路五种分数量纲

一个 chunk 可能在多条通道中被召回，但每条通道的分数含义完全不同：

| 通道 | 分数来源 | 量纲 | 取值范围 | 备注 |
|:---|:---|:---|:---|:---|
| **向量** | `cosine_similarity(q_vec, d_vec)` | 无量纲（夹角余弦） | [-1, 1] | 实际几乎全为正数 |
| **关键词** | `BM25(q, d)` | 有量纲（词频×逆文档频率） | [0, +∞) | 不同查询/文档的 BM25 不可直接比较 |
| **知识图谱** | `LightRAG.relevance_score` | 各后端自定 | [0, 1] 或自由 | 取决于后端实现 |
| **联网搜索** | `1.0f / (rank + 1)` | 无量纲（名次倒数） | (0, 1] | 人工构造的名次递减分 |

**一个具体例子说明为什么不能直接加权**：

```
用户: "OA系统的审批流程"
向量通道: chunk_A (cosine=0.82, rank=0), chunk_B (cosine=0.75, rank=1)
关键词通道: chunk_A (BM25=15.3, rank=2), chunk_C (BM25=18.7, rank=0)

问题1: 直接按分数加权平均？
  0.82 + 15.3 = ?  — 量纲不同，毫无意义

问题2: 各通道归一化到 [0,1] 再加权？
  向量归一化: chunk_A=0.82, chunk_B=0.75 (保持线性)
  BM25 归一化: chunk_A=15.3÷18.7=0.818, chunk_C=18.7÷18.7=1.0
  → BM25 的 15.3 vs 18.7 可能只是微小差异，
    但除最大值归一化后变成了 0.818 vs 1.0 的巨大差距 — 失真严重
```

### 19.2 解决方案：RRF — 只信名次，不信分数

核心思想来自学术论文 _Reciprocal Rank Fusion outperforms Condorcet and individual rank learning methods_：

```java
// FusionPostProcessor.fuseByRrf() — 核心实现
score(chunk) = Σ_channel 1 / (k + rank_channel + 1)

// k = 60（默认），rank 从 0 开始
// rank 取的是 SearchChannelResult.getChunks() 中各通道的原始召回顺序
```

**为什么名次是跨通道可比的？**

```
向量通道 rank=0: 本通道最像的 chunk（不管原始 cosine 是 0.99 还是 0.52）
关键词通道 rank=0: 本通道 BM25 最高的 chunk（不管分数是 100 还是 5）

→ "rank=0" 在所有通道中都有相同的语义：这是该通道认为最相关的 chunk
→ 这个共识是跨通道可比的
```

**RRF 的数学特性**：

```
1/(k + rank) 关于 rank 是递减函数（k=60）：

rank 0:  1/61 = 0.01639
rank 1:  1/62 = 0.01613  (降 1.6%)
rank 2:  1/63 = 0.01587  (降 1.6%)
rank 3:  1/64 = 0.01563  (降 1.5%)
rank 5:  1/66 = 0.01515  (降 7.6%  from rank 0)
rank 10: 1/71 = 0.01408  (降 14.1% from rank 0)
rank 20: 1/81 = 0.01235  (降 24.7% from rank 0)
rank 50: 1/111= 0.00901  (降 45.0% from rank 0)

特点：
- 高名次(rank 0-5)之间差异小 → winners stay on top，不会因为微小差异来回换位
- 低名次(rank 20+)贡献极小 → 自动过滤噪音
- k 越大曲线越平 → 名次差异被压缩，各通道更平等
- k 越小高名次越有优势 → 领头羊更突出
```

### 19.3 完整的三步融合流水线

```
阶段 1: 各通道原始分（量纲不可比，仅用于通道内排序）
  ├─ 向量通道: chunk_A(cosine=0.82), chunk_B(0.75), chunk_C(0.68)  [按 cosine 降序]
  ├─ 关键词通道: chunk_C(BM25=18.7), chunk_D(15.3), chunk_A(12.1)  [按 BM25 降序]
  └─ 联网搜索: chunk_E(1/1=1.0), chunk_F(1/2=0.5)                    [按 You.com 返回顺序]

阶段 2: Dedup 去重合并（order=1）
  chunk_A 在向量和关键词都出现 → 保留向量通道的 chunk_A(因为优先级: 向量>图谱>关键词>联网)
  chunk_C 在向量和关键词都出现 → 保留向量通道的 chunk_C
  
  合并去重后: chunk_A(0.82), chunk_B(0.75), chunk_C(0.68), chunk_D(BM25=15.3), chunk_E(1.0), chunk_F(0.5)
  这些分数已经没啥用了 — RRF 只看名次

阶段 3: RRF 名次级融合（order=5）— 依据各通道原始顺序
  chunk_A 在向量排 rank 0，在关键词排 rank 2:
       RRF = 1/(60+0+1) + 1/(60+2+1) = 0.0164 + 0.0159 = 0.0323
  chunk_C 在向量排 rank 2，在关键词排 rank 0:
       RRF = 1/(60+2+1) + 1/(60+0+1) = 0.0159 + 0.0164 = 0.0323 (对称)
  chunk_D 只在关键词排 rank 1:
       RRF = 1/(60+1+1) = 0.0161  (单通道命中，相当于多路命中的一半)
  chunk_E 只在联网排 rank 0:
       RRF = 1/(60+0+1) = 0.0164

  → RRF 排序: chunk_A(0.0323) = chunk_C(0.0323) > chunk_E(0.0164) > chunk_D(0.0161)
  → 多路命中的 chunk_A 和 chunk_C 排最前
  → 截断到 rerankCandidateLimit=50 → 送入 Rerank

阶段 4: Rerank 精排（order=10）— 最终分值
  cross-encoder 输出 relevance_score [0, 1]:
  chunk_A=0.91, chunk_C=0.85, chunk_D=0.79, ...
  → 最终排序由 Rerank 分数决定
```

### 19.4 权重分配 — RRF 框架下的三种隐式权重

**RRF 本身不需要显式权重**，但系统通过三种机制控制各通道的影响力：

**机制一：通道优先级（Dedup 阶段去重时用）**

```java
// DeduplicationPostProcessor.getChannelPriority()
VECTOR(1) > GRAPH(3) > KEYWORD(5) > WEB_SEARCH(20)
// 同一个 chunk 多通道命中时，保留高优先级通道版本的分数（元数据）
// 虽然 RRF 不用这个分数，但后续 MetadataEnrichment 富化时用 channel=1 的信息更丰富
```

**机制二：召回数量（topKMultiplier）**

```yaml
rag.search.channels:
  vector.intent-directed.top-k-multiplier: 2   # 召回 2×topK
  vector.global.candidate-budget: 100           # 全局检索召回 100 个
  keyword.top-k-multiplier: 2                   # 召回 2×topK
  web-search.count: 5                           # 最多 5 条
```

**这实质上是最重要的"隐式权重"**——向量通道贡献了 50-100 个候选（ranks 0-99），关键词可能只贡献 5-10 个（取决于 ES 索引规模）。多贡献的通道 = 更多 rank 位置 = 更多机会贡献 RRF 分数。

**机制三：k 值（名次敏感度）**

k=60 意味着 rank 0 和 rank 10 的 RRF 分差 14%。如果某通道返回的前 5 个都是高质量 chunk（但 rank 6-50 是噪音），减小 k 到 20 可以让 rank 0-5 的优势更突出（差 24%），从而减少后面噪音的干扰。

**为什么不加显式加权系数？**

RRF 的设计哲学是：**如果需要在 RRF 上再加权重，说明通道本身的召回质量有问题，应该优化通道而非调权重**。学术上也证实 RRF 不加权版本在大多数场景下不弱于加权版本。

### 19.5 单通道时的处理

```java
// FusionPostProcessor.process() — 单通道直接跳过 RRF
List<RetrievedChunk> ranked = results != null && results.size() > 1
        ? fuseByRrf(chunks, results)  // 多通道 → RRF 融合
        : chunks;                      // 单通道 → 保持原召回顺序不变
```

只有一条路时没有"融合"的对象，原顺序 = 最优顺序。

### 19.6 k 值调参指南（结合场景）

| k 值 | rank 0 vs rank 3 | rank 0 vs rank 10 | 适用场景 |
|:---|:---|:---|:---|
| **20** | 1/21=0.0476 vs 1/24=0.0417 (差 12%) | 1/21=0.0476 vs 1/31=0.0323 (差 32%) | 某通道明显更准，需要保护高名次优势 |
| **60（默认）** | 1/61=0.0164 vs 1/64=0.0156 (差 4.8%) | 1/61=0.0164 vs 1/71=0.0141 (差 14%) | 通道质量相当，依赖多路命中提权 |
| **100** | 1/101=0.0099 vs 1/104=0.0096 (差 2.9%) | 1/101=0.0099 vs 1/111=0.0090 (差 9%) | 通道质量参差不齐，给所有通道均等机会 |

**调参实践**：
- 如果发现关键词通道的好结果总被向量通道的同义反复排挤 → 调大 k（如 80-100）
- 如果发现 RRF 融合后排名混乱（真正相关的 chunk 排到后面） → 调小 k（如 30-50）
- 如果只有向量通道 → k 值无关紧要（单通道不走 RRF）

### 19.7 面试话术模板

> "多路召回最大的难点是**分数量纲不可比**——向量的余弦相似度是 [-1,1] 的无量纲值，BM25 分数取决于文档长度和词频没有上界。你没法直接加权平均，因为 0.82 和 15.3 根本没有可比性。即使先把各路分数分别归一化到 [0,1]，也会扭曲原始分数的分布——比如 BM25 的 15.3 和 18.7 可能只是一点微小差异，但除最大值归一化后就变成了 0.82 和 1.0。
>
> 我们用的是 **RRF（Reciprocal Rank Fusion）**，核心理念是**只信名次，不信分数**。公式 `Σ 1/(k+rank+1)`，k 默认 60。因为 `rank=0` 在所有通道中语义相同——都是"本通道认为最相关的 chunk"，这是跨通道唯一的共识信号。
>
> 同一个 chunk 如果在多条通道都排前列，RRF 分会比单通道命中高出一倍，这天然实现了**多路命中的提权**。比如一个 chunk 在向量通道排 rank 0、在关键词排 rank 2，RRF = 1/61 + 1/63 = 0.032；而只在一个通道排 rank 0 的 chunk RRF = 0.016。多路命中自然靠前，不需要人为调权重。
>
> RRF 之后还有 Rerank cross-encoder 做精排，这是最终的语义相关性分数。RRF 的角色是粗排——把多路不可比的结果统一到一个可比的尺度上，并缩减候选池给 Rerank。

---

## 二十、Ragent 与 Agentic RAG 的关系

### 20.1 什么是 Agentic RAG

先厘清概念。业界说的 **Agentic RAG** 通常有两层含义：

**定义 A（狭义）— 带工具调用的 RAG**：RAG 系统不仅能检索文档，还能调用外部工具（天气 API、数据库查询、计算器等）获取动态数据。检索 + 工具结果混合后一起喂 LLM。

**定义 B（广义）— 自主规划与决策的 RAG**：Agent 有自主规划能力——分析问题→分解子任务→决定查什么/调什么工具→观察结果→决定下一步→循环直到得到答案。即 Observe-Plan-Act 循环。

### 20.2 Ragent 在 Agentic RAG 光谱中的位置

```
纯 RAG ────────── Agentic RAG (狭义) ────────── Full Agent（广义）
  │                    │                              │
  ①检索+生成           ②检索+工具调用+意图路由          ③自主规划+多步推理+工具链
                    ▲ Ragent 在这里                      （LangChain ReAct、AutoGPT）
```

Ragent 处于 **第②层**，具有以下 Agentic 能力：

| 能力 | 实现 | 与 Full Agent 的差距 |
|:---|:---|:---|
| **工具调用** | MCP 协议 + McpClientToolExecutor | ✅ 具备，但单次调用，无工具链 |
| **意图路由** | 意图树三路分发 KB/MCP/SYSTEM | ✅ 具备，但路由是一次性决策，不迭代 |
| **参数提取** | McpParameterExtractor 调 LLM | ✅ 具备，LLM 从问题中提取工具参数 |
| **多步推理** | 无 | ❌ 不具备，无法 "查订单→根据金额查优惠→计算价格" |
| **自主决策** | 无 | ❌ 不具备，不会自主选择"要不要再查一次" |
| **反馈循环** | 无 | ❌ 不具备，工具结果不回传给 Agent 做二次决策 |

### 20.3 Ragent 的 Agentic 机制详解

#### 机制一：意图驱动的工具发现与注册

```java
// McpClientAutoConfiguration.init() — 启动时自动发现远程工具
for (McpClientProperties.ServerConfig server : servers) {
    McpSyncClient client = McpClient.sync(transport)   // 通过 MCP 协议连接 mcp-server
        .clientInfo(new Implementation("ragent-bootstrap", "1.0.0"))
        .build();
    client.initialize();                    // 握手
    List<Tool> tools = client.listTools(); // 发现远程工具列表
    for (Tool tool : tools) {
        toolRegistry.register(new McpClientToolExecutor(client, tool)); // 自动注册
    }
}
```

**这是 Agentic 的第一步：环境感知**。系统启动时自动扫描 MCP Server 上可用的工具，注册到工具注册表。工具定义（name、description、JSON Schema）由 mcp-server 端维护，bootstrap 端零配置。

#### 机制二：意图驱动的工具选择

```java
// RetrievalEngine.buildSubQuestionContext() — 按意图类型分流
List<NodeScore> kbIntents = NodeScoreFilters.kb(intent.nodeScores());   // KB 意图
List<NodeScore> mcpIntents = NodeScoreFilters.mcp(intent.nodeScores()); // MCP 意图

// KB 路径 → 多通道检索
KbResult kbResult = retrieveAndRerank(intent, kbIntents, topK);

// MCP 路径 → 参数提取 + 工具调用
String mcpContext = executeMcpAndMerge(intent.subQuestion(), mcpIntents);
```

**两条路并行执行**，结果在 `RetrievalContext` 中合并。LLM 的最终 Prompt 中 KB 证据和 MCP 数据混在一起。

#### 机制三：LLM 驱动的参数提取

```java
// McpParameterExtractor.extractParameters() — LLM 从自然语言问题中提取结构化参数
// 输入: "北京今天热不热"
// 输出: {"city": "北京", "queryType": "current"}

// 每个 MCP 节点可以有自定义参数提取提示词 (paramPromptTemplate)
// 天气查询: "从用户问题中提取城市名和查询类型"
// 销售查询: "从用户问题中提取地区、产品线、时间范围"
```

#### 机制四：意图节点 = 微型 Agent 配置

```yaml
# 每个意图叶子节点就是一个 Agent 的配置单元:
biz-oa-flow:       # 节点 ID
  kind: KB          # Agent 行为: 检索知识库
  collection: oa    # 去哪查
  top_k: 10         # 查多少
  prompt_snippet: "请使用OA术语"  # 回答约束

mcp-weather:       # 节点 ID
  kind: MCP         # Agent 行为: 调工具
  mcp_tool_id: weather_query   # 调哪个工具
  param_prompt: "提取城市和查询类型"  # 怎么提参数

sys-chat:          # 节点 ID
  kind: SYSTEM      # Agent 行为: 闲聊
  prompt_template: "你好，我是企业助手小码"  # 回复风格
```

### 20.4 与 Full Agent（ReAct Loop）的对比

以一个具体问题来对比：

> 用户："查一下北京销售团队的业绩，如果超过 1000 万就帮我生成一份报告"

| 维度 | Full Agent (ReAct) | Ragent 当前实现 |
|:---|:---|:---|
| **Step 1** | Thought: 需要先查业绩 → Action: 调用 sales_query | 意图分类命中 mcp-sales |
| **Step 2** | Observation: 业绩 1200 万 → Thought: 超过 1000 万，需要生成报告 | **不具备**。工具结果直接返回给 LLM，不做逻辑判断 |
| **Step 3** | Action: 调用 report_generate | **不具备**。无法根据上一步结果自动触发下一步 |
| **结果** | 自动完成查业绩→判断→生成报告 | 返回销售数据，用户需要自己判断并手动请求生成报告 |

**Ragent 的管线是一次性的**：意图分类→工具调用→结果拼 Prompt→LLM 生成。中间不会根据工具返回结果做二次决策。

### 20.5 为什么 Ragent 选择管线而非 Full Agent？

| 考量 | Full Agent Loop | Ragent 管线 |
|:---|:---|:---|
| **延迟** | 每步一次 LLM 调用 × 不确定步数（可能 3-10 轮） | 固定：1 次 LLM 分类 + 并行工具调用 + 1 次 LLM 生成 |
| **Token 消耗** | 每步带完整对话历史 | 只分类一次 |
| **稳定性** | LLM 可能选错工具、无限循环、幻觉操作 | 意图树约束了可选工具范围 |
| **企业场景适配** | 大多数企业 QA 不需要多步推理 | ✅ 一次问答一次回答，大多数场景够用 |
| **可解释性** | 多步推理链路难以追溯 | 每次问答链路清晰（Trace） |

**面试话术**：
> "Agentic RAG 是一个光谱，Ragent 处于光谱的第二层——具备工具调用和意图路由能力，但不是全自主的多步推理 Agent。我们选择管线的核心原因是 **企业场景的确定性需求**。大多数企业 QA（查政策、查天气、查订单）不需要多步推理，单次意图分类+工具调用已经覆盖了 90% 的场景。管线的延迟可控（固定两轮 LLM 调用）、成本可预测、行为可解释，这比 Agent loop 的灵活性在企业场景中更重要。"

### 20.6 如果要升级到 Full Agent

当前架构已经具备了升级基础：

1. **工具注册表已就绪**：`McpToolRegistry` 管理所有可用工具
2. **参数提取已实现**：`McpParameterExtractor` 可以从自然语言中提取参数
3. **意图树可扩展**：新增节点即可新增 Agent 能力

**改造路径**：将 `StreamChatPipeline` 中的线性固定流程替换为循环决策流程：

```java
// 升级为 Agent Loop 的伪代码
while (!taskComplete && iterations < maxIterations) {
    // 1. 让 LLM 自主决策下一步: 继续检索？调工具？输出结果？
    PlanStep step = llm.plan(history, availableTools, retrievalResults);
    
    switch (step.action) {
        case RETRIEVE -> results = retrievalEngine.retrieve(step.intents, step.topK);
        case CALL_TOOL -> toolResults = toolRegistry.execute(step.toolId, step.params);
        case ANSWER -> { taskComplete = true; streamResponse(); }
    }
}
```

**改造代价**：延迟和成本倍数增长（每步一次 LLM 调用），需要增加 `maxIterations` 安全阀防止死循环。

### 20.7 面试话术模板

> "Ragent 是一个 **Agentic RAG 系统**，但这里的 Agentic 不是指 LangChain 那种 ReAct 自主决策循环，而是指**意图驱动的管线分流**——通过三层意图树将用户问题路由到 KB 检索、MCP 工具调用或 SYSTEM 闲聊三条执行路径。
>
> 之所以不搞 Agent loop，是因为企业场景以确定性交互为主。用户问'年假几天''北京天气''订单状态'，都是一次问答即可完成。Agent loop 的多步推理收益不大，但延迟加倍、成本不可控。
>
> 我们的 Agentic 体现在四个层面：第一，通过 MCP 协议自动发现和注册远程工具；第二，意图分类时 LLM 自主判断该走检索还是工具调用还是闲聊；第三，工具参数由 LLM 从自然语言中提取；第四，KB 和 MCP 结果在最终 Prompt 中融合，LLM 综合双方信息生成答案。这是一种**务实的 Agentic 设计**——在保证确定性和性能的前提下，最大化自动化能力。"

---

## 二十一、Query 决策树分析 — Ragent 的实现情况

### 21.1 理论决策树 vs Ragent 实际实现

将业界常见的 RAG Query 预处理决策树与 Ragent 代码逐节点对比：

```
理论决策树                            Ragent 实现情况
══════════                           ════════════════
含精确数字/人名/型号/日期？
  ├─ 是 → metadata filter           ❌ 未显式实现
  └─ 否 → 继续

Query 质量差（口语化/缩写/错别字）？
  ├─ 是 → Query 改写                ✅ MultiQuestionRewriteService
  └─ 否 → 继续

问题过于具体，缺背景知识？
  ├─ 是 → Step-back                 ❌ 未实现
  └─ 否 → 继续

Query 很短（<10字）或零样本？
  ├─ 是 → HyDE                      ❌ 未实现
  └─ 否 → 继续

问题多义/歧义/多跳？
  ├─ 是 → Multi-Query               ⚠️ 部分实现
  └─ 否 → 直接检索
```

搜索 `HyDE`、`StepBack`、`step-back`、`step_back`、`MultiQuery` 在代码库中**零命中**。这三种策略 Ragent 当前完全未实现。

### 21.2 已实现的部分

#### ✅ Query 改写（覆盖决策树的第二层）

**代码入口**：`MultiQuestionRewriteService.rewriteWithSplit()`

```java
// 两阶段处理
1. 术语归一化（QueryTermMappingService.normalize()）— DB 规则 + Redis 缓存
2. LLM 改写 + 拆分（callLLMRewriteAndSplit()）— 调 LLM

// 提示词 user-question-rewrite.st 覆盖了决策树列举的所有退化场景：
- 口语化：   "报销咋整" → "报销流程"
- 缩写：     术语归一化映射 "平安保司" → "平安保险公司"
- 错别字：   LLM 自动纠错
- 指代消解：  "它的数据库" → 结合历史还原为 "OA系统的数据库"
- 多问句拆分："A怎么用？B呢？" → ["A怎么用", "B怎么用"]
```

**配置开关**：`rag.query-rewrite.enabled: true`。关闭时走纯规则兜底（术语归一化 + 按标点分隔符切分）。

#### ⚠️ 歧义引导 + 拆分（部分覆盖决策树的第五层）

**歧义引导**：`IntentGuidanceService.detectAmbiguity()` — 当意图分类遇到同名主题跨系统歧义时，推送选项让用户选择而非自动拆成多个 query。

**拆分逻辑**：改写的 `should_split` 判断 + 规则兜底的 `ruleBasedSplit()`。但拆分只用于"一个用户问题包含多个独立子问题"的显式场景，不存在 Multi-Query 的"对同一问题生成多种表述并行检索"的策略。

### 21.3 未实现的部分 — 为什么？

| 策略 | 为什么没实现 | 对 Ragent 当前场景的影响 |
|:---|:---|:---|
| **metadata filter 预判** | 精确匹配依赖 BM25 关键词通道（天然对数字/ID/人名敏感），不需要前置判断 | 低影响。关键词通道已覆盖精确匹配场景 |
| **Step-back** | 需要额外一次 LLM 调用生成"后退问题"，延迟翻倍。企业知识库通常结构良好，用户问题很少缺背景知识 | 低影响。意图树的三层结构（DOMAIN→CATEGORY→TOPIC）等效于"向上抽象" |
| **HyDE** | 企业对延迟敏感，HyDE 需要 LLM 先生成假设答案再检索，两阶段延迟不可接受 | 中影响。零样本或极短 query 时检索效果确实不如 HyDE |
| **Multi-Query** | 多条 query 的并行检索等同于多个意图并行分类 → 多路检索。不需要额外生成变体 | 低影响。意图拆分类似效果，但不覆盖同义变体检索 |

**面试话术**：
> "这个决策树很全面，但实际落地要考虑延迟和成本。Ragent 的设计原则是'**用意图路由替代盲目改写**'——Query 改写只做一轮（清理口语化+拆分），不做 HyDE（生成假设答案再检索，两轮 LLM 延迟不可接受），不做 Step-back（意图树的三层结构天然提供了向上抽象的路径），也不做 Multi-Query 变体生成（多个同义 query 同时检索的效果 ≈ 多路检索中的全局向量+关键词双保险）。用户如果有极短 query（如'年假'），意图分类能自动命中'人事制度'节点，collection 路由确保检索范围精准，不需要 HyDE 来弥补 query 信息的不足。"

### 21.4 Ragent 的实际 Query 处理决策树

对照代码，Ragent 的实际决策路径是：

```
用户 Query 进来
  │
  ▼
术语归一化（QueryTermMappingService）
  规则级：DB/Redis 缓存映射表，"平安保司"→"平安保险公司"
  │
  ▼
queryRewriteEnabled？
  ├─ false → 规则拆分（按标点切）→ 跳到意图分类
  └─ true  → LLM 改写 + 拆分（一次 LLM 调用完成所有清理）
             ├─ LLM 失败 → 规则拆分兜底
             └─ LLM 成功 → 拿到 rewrittenQuestion + subQuestions
  │
  ▼
意图分类（每个子问题并行调 LLM）
  ├─ KB 意图   → collection 路由 + 多路检索
  ├─ MCP 意图  → 工具参数提取 + 工具调用
  └─ SYSTEM 意图 → 直接 LLM 回复

对比理论的 5 层决策树，Ragent 实际只有 2 层：
  第 1 层：术语归一化（规则）
  第 2 层：LLM 改写 + 拆分（一次调用覆盖口语化/缩写/错别字/指代消解/多问句拆分）
  
这 2 层后直接进入意图分类 → 检索/工具调用。
```

### 21.5 如果要在 Ragent 中补齐这些策略

| 策略 | 实现复杂度 | 延迟影响 | 实现建议 |
|:---|:---|:---|:---|
| **metadata filter 预判** | 低 | 极低（规则判断） | 加一个 regex 预判：含数字/日期/ID 模式 → 关键词通道 topK 加倍，因为精确匹配场景 BM25 比向量更准 |
| **HyDE** | 中 | **高（+一次 LLM）** | 作为可选增强，配置开关 `rag.query.hyde-enabled: false`（默认关闭）。只有当 `queryRewriteEnabled=false` 且 query 字符数 < 阈值时才触发 |
| **Step-back** | 中 | **高（+一次 LLM）** | 利用现有的意图树路径信息。"后退"不需要 LLM——意图节点向上回溯父节点就是概念抽象。如被分类为 biz-oa-intro，父节点 biz-oa 的 description 就是"OA系统"，直接用于全库检索补充 |
| **Multi-Query** | 低 | 中（并行检索翻倍） | 改写阶段如果 LLM 返回 `should_split=false` 但 query 含歧义词，额外生成 1-2 个同义变体。与主 query 一起走多路检索，增加召回覆盖率 |

### 21.6 面试话术模板

> "你发的这个 Query 决策树很经典，覆盖了 HyDE、Step-back、Multi-Query 等常见优化策略。Ragent 的实现是其中的一个简化版——我们只做了两件事：规则级的术语归一化 + LLM 级别的改写拆分。其他层没有实现的原因主要是三个：
>
> 第一，**意图路由替代了 HyDE 和 Step-back**。HyDE 的本质是弥补短 query 的信息不足，但我们有三层意图树——用户说'年假'，意图分类能自动命中'人事制度→考勤→年假'这个路径，路由到精准的 collection，不需要 HyDE 生成假设文章。
>
> 第二，**延迟预算不允许两轮 LLM**。企业对问答延迟的容忍度通常在 3 秒内。一次 LLM 改写（~500ms）+ 一次 LLM 意图分类（~300ms）+ 检索（~200ms）+ LLM 生成（~2s）已经接近上限。再加 HyDE 或 Step-back 会再叠加一轮 LLM，用户体感明显。
>
> 第三，**关键词通道弥补了精确匹配的不足**。BM25 对订单号、错误码、人名的精确匹配天然优于向量，不需要额外的 metadata filter 预判。
>
> 如果未来要补齐，优先级是 Multi-Query > metadata filter > Step-back > HyDE。Multi-Query 实现成本最低（改写阶段多生成几个同义变体），metadata filter 是纯规则零延迟，HyDE 最重但零样本场景效果最好。"
