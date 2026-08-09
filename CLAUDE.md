# ragent 项目

## 项目概况

企业级 RAG 知识库问答系统，Java 17 + Spring Boot 3，Maven 多模块。

| 模块 | 职责 |
|---|---|
| `framework` | 通用框架：SSE 发送器、统一返回、幂等切面、异常处理 |
| `infra-ai` | LLM 客户端：多模型路由、断路器、SSE 解析、流式回调 |
| `bootstrap` | 主应用：7 步 Pipeline、意图树、多路检索、MCP 工具 |
| `mcp-server` | 独立 MCP Server（天气/搜索/工单/销售工具） |
| `frontend` | React 前端（SSE 消费、chatStore） |

## 开发环境

| 组件 | 端口 | 说明 |
|---|---|---|
| PostgreSQL | 5433 | 主库 `ragent`，用户 admin/admin |
| Redis | 6379 | 密码 123456 |
| Elasticsearch | 9200 | IK 分词，索引 `rag_keyword_store` |
| RocketMQ | 9876 | Docker 容器 |
| RustFS | 9000 | S3 兼容存储，桶 `ragent-sources`/`ragent-assets` |
| 应用 | 9090 | context-path `/api/ragent` |

```bash
./startup.sh         # 一键启动所有基础设施
/opt/maven/bin/mvn   # Maven 路径
```

MinerU API Key 通过环境变量 `MINERU_API_KEY` 注入。

## 核心架构

### 7 步对话 Pipeline（`StreamChatPipeline.execute()`）

```
① loadMemory      并行加载摘要+历史
② rewriteQuery    LLM 改写+多问句拆分+Multi-Query 变体
③ resolveIntents  并行对每个子问题做 LLM 意图打分
④ handleGuidance  歧义时反问澄清 → return（短路）
⑤ handleSystemOnly 纯闲聊直接回答 → return（短路）
⑥ retrieve        KB 多通道检索+MCP 工具执行
⑦ streamRagResponse Prompt 组装→LLM 流式输出
```

### 意图树（`t_intent_node` 表）

三层体系（DOMAIN→CATEGORY→TOPIC），叶子节点 `kind` 决定分发：
- `kind=0`(KB) → 定向检索
- `kind=1`(SYSTEM) → 闲聊短路
- `kind=2`(MCP) → 调远端工具

LLM 一次性对所有叶子打分，`score>=0.35` 且 `top 3` 的命中。Redis 缓存 key：`ragent:intent:tree`。

### 检索 4 通道 → 4 后处理器

```
VectorSearch(向量) → KeywordSearch(ES BM25) → GraphSearch(LightRAG) → WebSearch(You.com)
    → Dedup → RRF 融合(k=60) → Rerank(cross-encoder) → MetadataEnrichment
```

### 关键稳定性机制

- **模型断路器**：`ModelHealthStore` CLOSED→OPEN→HALF_OPEN 三态，连续失败阈值 2，半开 30s
- **流式降级**：`ProbeStreamBridge` 首包超时 60s → 切换候选模型
- **分布式限流**：`FairDistributedRateLimiter` ZSet 公平队列+Lua 原子抢占
- **幂等**：`@IdempotentSubmit`(Redisson 锁) + `@IdempotentConsume`(Redis Lua)
- **流式取消**：`StreamTaskManager` Redis 标记+RTopic 跨节点广播
- **MinerU 缓存**：`MinerUDocumentParser` RustFS 缓存 zip，同文件重分块跳过 API

## 常见排查

| 问题 | 原因 | 解决 |
|---|---|---|
| ES `analyzer [ik_smart] has not been configured` | 官方 ES 镜像无 IK 插件 | `elasticsearch-plugin install --batch file:///tmp/ik.zip` + 重启 |
| `hnsw.iterative_scan` ERROR 刷屏 | pgvector 0.6.0 不支持（需 0.8+），DEBUG 级静默降级 | 误报，不影响 |
| "你好"走检索不短路 | `t_intent_node` 闲聊节点 `kind` 错配为 MCP(2) | 改为 SYSTEM(1)，清 Redis `ragent:intent:tree` |
| 同一文件重分块调 MinerU API | 无解析缓存 | `mineru.cache-enabled: true`（已开启） |

## ⚠️ 自定义要求（最高优先级）

### 前置要求

1. 会话开始时阅读 readme.md、.sql 和核心代码，熟悉数据库表设计、分层职责与依赖规则
2. 严格遵守 CLAUDE.md 中的要求

### 需求审查

1. 用户需求可能模糊或有误，需深度思考、通过提问逐步澄清
2. 遇到不确定的问题必须提问，分析方案优缺点后提供最佳选择
3. 一次提问 3 个左右，多轮迭代至需求清晰
4. 需求清晰后完整复述理解+执行计划，用户确认后再编码

### 代码编写

1. 架构优先，扮演高级架构师角色
2. 按 TDD 执行，代码通过测试点
3. 依赖倒置+设计模式，参考项目已有风格
4. 接口需鉴权、幂等、参数校验，处理边界情况
5. 关注点分离，避免冗长函数
6. 关键位置中文注释+英文日志，不用 debug 日志，同文件内日志内容不重复
7. 不用全限定类名声明变量（如 `com.xxx.req.AddReq domainReq`）
8. 错误码按业务细化，不用大一统错误码

### 代码审查（写完自动执行）

1. 编译检查语法错误
2. 逐条对照用户原始需求审查，不满足则制定修复计划
3. 审查分层合理性、代码风格一致性
4. 审查安全、性能问题
5. 审查接口幂等性、边界情况
6. 审查 .sql 设计是否满足需求
7. 问题反馈：位置+原因+修复方案，用户确认后修复

### 其他

- 用中文回答
- 每次回答总结：需求+改动+架构优势，审查问题需报告

## 相关文档

面试准备见 `.claude/memory/`，包含 ragent 笔记索引和 PowerAgent 文档索引。
