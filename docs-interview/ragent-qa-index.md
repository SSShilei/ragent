# Ragent 面试问题全集索引（可点击跳转）

> 点击"定位"列的链接即可跳转到对应章节。本文件与目标文档同在 docs-interview/。
> VS Code / IDEA：Ctrl+单击跳转。路径用 `<...>` 包裹以兼容空格与特殊字符。

---

## 1. Chunking / 文档分块（★ 核心亮点）

| 问题 | 定位 |
|:---|:---|
| Ragent 和 AgentFlow 的 Chunking 有什么区别？ | [一、对比](<ragent-chunking-analysis.md#chunking-compare>) |
| Parser 矩阵：6 个解析器×格式×路由 | [二、Parser 矩阵](<ragent-chunking-analysis.md#parser-matrix>) |
| MinerU 六步异步解析流程 | [2.4](<ragent-chunking-analysis.md#mineru-six-step>) |
| MinerU 不直接输出 Block——两层分工 | [2.5](<ragent-chunking-analysis.md#mineru-two-layer>) |
| commonmark AST 解析器原理 | [2.6](<ragent-chunking-analysis.md#commonmark-ast>) |
| 6 种 Block 详细结构 | [三、Block 模型](<ragent-chunking-analysis.md#block-model>) |
| TableChunker 双文本嵌入 | [4.4](<ragent-chunking-analysis.md#table-chunker>) |
| BlockAwareChunkerDispatcher 分发机制 | [4.2](<ragent-chunking-analysis.md#chunker-dispatcher>) |
| HeadingHandler 不产 chunk 只累积路径 | [4.3](<ragent-chunking-analysis.md#heading-handler>) |
| CodeChunker/ListChunker/ImageChunker | [4.5-4.7](<ragent-chunking-analysis.md#code-chunker>) |
| ChunkPacker 贪心合并与块级重叠 | [4.8](<ragent-chunking-analysis.md#chunk-packer>) |
| STRUCTURE_AWARE vs Block-Aware | [5.2.1](<ragent-chunking-analysis.md#structure-aware-vs-blockaware>) |
| FIXED_SIZE 三层边界对齐 + normalizeText | [5.1](<ragent-chunking-analysis.md#fixed-size>) |
| VectorChunk 元数据字段（11 个） | [六、VectorChunk](<ragent-chunking-analysis.md#vector-chunk-metadata>) |
| 完整案例：解析到入库 4 阶段 | [七、完整案例](<ragent-chunking-analysis.md#full-example>) |
| Parent-Child 等价方案 | [八、Parent-Child](<ragent-chunking-analysis.md#parent-child>) |
| 六种业界分块策略评估 | [九、业界策略](<ragent-chunking-analysis.md#industry-strategies>) |
| Chunking 面试话术 | [十、面试话术](<ragent-chunking-analysis.md#interview-talk-points>) |
| Metadata：博客 7 字段 × Ragent 对照 | [十一、Metadata](<ragent-chunking-analysis.md#metadata-details>) |
| 每个 chunker 注入字段 + 合并规则 | [11.4](<ragent-chunking-analysis.md#metadata-chunker-fields>) |
| Metadata 完整生命周期 | [11.5](<ragent-chunking-analysis.md#metadata-lifecycle>) |
| embedding_model 字段缺失分析 | [11.3](<ragent-chunking-analysis.md#metadata-field-analysis>) |

---

## 2. Query 重写与决策树

| 问题 | 定位 |
|:---|:---|
| 意图树和 Query 重写并行还是串行？ | [三、关系](<ragent-intent-rewrite-config.md#intent-rewrite-relation>) |
| Query 重写完整链路 | [3.2](<ragent-intent-rewrite-config.md#rewrite-strategy>) |
| Multi-Query 改造前后对比 | [3.7](<ragent-intent-rewrite-config.md#multi-query-compare>) |
| Multi-Query 触发条件 | [3.5](<ragent-intent-rewrite-config.md#multi-query-trigger>) |
| 精确实体短路正则设计 | [3.8](<ragent-intent-rewrite-config.md#exact-entities-bypass>) |
| 完整 Query 决策树 6 档 | [3.9](<ragent-intent-rewrite-config.md#query-decision-tree>) |
| 改写 query 用哪个模型？ | [五、改写模型](<ragent-intent-rewrite-config.md#rewrite-model>) |
| 多轮对话指代消解 | [5.5](<ragent-intent-rewrite-config.md#coref-resolution>) |
| temperature 参数调优 | [六、temperature](<ragent-intent-rewrite-config.md#temperature>) |

---

## 3. 意图识别

| 问题 | 定位 |
|:---|:---|
| 意图树有默认实现吗？怎么配置？ | [一、意图树配置](<ragent-intent-rewrite-config.md#intent-tree-config>) |
| KB 意图识别 classifyTargets 6 步 | [2.1](<ragent-intent-rewrite-config.md#classify-targets>) |
| 意图识别四个消费点 | [2.2](<ragent-intent-rewrite-config.md#intent-consumers>) |
| promptSnippet vs promptTemplate | [1.3](<ragent-intent-rewrite-config.md#intent-node-table>) |
| 业内方案对比（5 方案） | [2.4](<ragent-intent-rewrite-config.md#industry-intent-compare>) |

---

## 4. Context 组装 / Prompt 工程

| 问题 | 定位 |
|:---|:---|
| ContextFormatter 三层分支 | [1.1](<ragent-core-mechanisms-2.md#context-three-branches>) |
| CONTEXT_FORMAT_PATH 模板结构 | [1.2](<ragent-core-mechanisms-2.md#context-single-intent>) |
| 等价父文档：docId 分组 + chunkIndex 排序 | [1.3](<ragent-core-mechanisms-2.md#context-doc-group>) |
| MetadataEnrichment 补什么？ | [1.3](<ragent-core-mechanisms-2.md#context-doc-group>) |
| 上下文压缩为什么不学 Claude Code？ | [六、生产工程](<ragent-deep-dive-2.md#production-engineering>) |
| ★ 上下文压缩/摘要/短期记忆三者关系（完整链路解析） | [压缩全链路详解](<ragent-context-compression-deep-dive.md>) |
| 压缩触发条件（四条命门）？ | [3. 压缩引擎内部](<ragent-context-compression-deep-dive.md#compress-engine>) |
| 摘要是同步还是异步？什么时候生效？ | [7. 异步模型详解](<ragent-context-compression-deep-dive.md#compress-async-model>) |
| 摘要写入了短期记忆吗？存哪？ | [1. 三个概念的物理边界](<ragent-context-compression-deep-dive.md#compress-concepts>) / [5. 摘要写入存储](<ragent-context-compression-deep-dive.md#compress-summary-storage>) |
| 压缩算法的具体公式和参数？ | [4. LLM 摘要调用的精确参数](<ragent-context-compression-deep-dive.md#compress-llm-params>) / [8. 配置项全景](<ragent-context-compression-deep-dive.md#compress-config>) |
| 多轮对话中不相关问题怎么去除噪声？ | [13. 多轮对话中的噪声去除](<ragent-context-compression-deep-dive.md#compress-noise-removal>) |
| 去噪的改写窗口怎么裁？为什么过滤 System 摘要？ | [13.2 防线①](<ragent-context-compression-deep-dive.md#compress-noise-defense>) |
| 为什么不做语义相关度过滤历史？ | [13.3 现状的局限](<ragent-context-compression-deep-dive.md#compress-noise-limits>) |
| 进阶：历史相关度过滤/话题感知方案对比 | [13.4 进阶方案](<ragent-context-compression-deep-dive.md#compress-noise-advanced>) |
| ★ AgentFlow 短期记忆摘要和上下文压缩有关吗？ | [14. AgentFlow 短期记忆摘要与上下文压缩的关系](<ragent-context-compression-deep-dive.md#compress-agentflow>) |
| Ragent vs PA 压缩方案场景对比 | [15. 两种方案场景对比](<ragent-context-compression-deep-dive.md#compress-scenario-compare>) |
| ★ 上下文压缩的 8 个痛点全景分析 | [16. 上下文压缩的痛点分析](<ragent-context-compression-deep-dive.md#compress-pain-points>) / [17. 痛点总结与解决思路](<ragent-context-compression-deep-dive.md#compress-pain-summary>) |
| ★ Claude Code 的上下文压缩策略（五层防线） | [18. Claude Code 的上下文压缩与记忆体系](<ragent-context-compression-deep-dive.md#compress-claude-code>) |
| Claude Code 记忆系统的四级分层 + T0-T3 社区方案 | [18.3 记忆系统四级文件层级](<ragent-context-compression-deep-dive.md#compress-claude-memory>) |
| Claude Code Subagents 上下文隔离机制 | [18.4 Subagents：上下文隔离的架构级解法](<ragent-context-compression-deep-dive.md#compress-claude-subagents>) |
| ★ 三种方案终局对比（Ragent vs PA vs Claude Code） | [19. 三种方案的终局对比](<ragent-context-compression-deep-dive.md#compress-final-compare>) |
| 面试加分回答模板（你们和 Claude Code 比怎么样？） | [20. 面试加分回答模板](<ragent-context-compression-deep-dive.md#compress-interview-template>) |
| ★ 四种方案横向对比：各自解决什么、带来什么、互相学什么 | [21. 四种方案的横向对比](<ragent-context-compression-deep-dive.md#compress-four-way-compare>) |
| 理想上下文压缩方案的分层设计 | [21.5 终局思考](<ragent-context-compression-deep-dive.md#compress-ideal-design>) |
| ★ Badcase 怎么处理？（失败模式 + 三层应对策略） | [22. Badcase 处理](<ragent-context-compression-deep-dive.md#compress-badcase>) |
| Badcase 面试话术 | [22.4 面试话术](<ragent-context-compression-deep-dive.md#compress-badcase-interview>) |
| 四系统 Badcase 处理对照表 | [22.5 对照表](<ragent-context-compression-deep-dive.md#compress-badcase-table>) |

---

## 5. 检索 / Rerank / RRF

| 问题 | 定位 |
|:---|:---|
| 检索召回率低怎么排查？ | [一、排查路径](<ragent-deep-dive-1.md#recall-troubleshoot>) |
| Rerank：cross vs bi-encoder | [二、Rerank](<ragent-core-mechanisms-2.md#rerank-details>) |
| Rerank 模型怎么排序打分？ | [2.1](<ragent-core-mechanisms-2.md#bi-cross-encoder>) |
| Rerank 归因日志解读 | [2.4](<ragent-core-mechanisms-2.md#rerank-attribution>) |

---

## 6. 限流 / 并发控制

| 问题 | 定位 |
|:---|:---|
| ChatQueueLimiter SSE 入口限流 | [3.1](<ragent-core-mechanisms-2.md#chat-queue-limiter>) |
| FairDistributedRateLimiter 分布式公平队列 | [3.2](<ragent-core-mechanisms-2.md#fair-rate-limiter>) |
| 公平队列时间线 + 分步详解 | [3.2](<ragent-core-mechanisms-2.md#fair-rate-limiter>) |
| 5 个安全机制对照表 | [3.2](<ragent-core-mechanisms-2.md#fair-rate-limiter>) |

---

## 7. SSE 流式 / 多模型 Fallback

| 问题 | 定位 |
|:---|:---|
| SSE 完整链路 | [4.1](<ragent-core-mechanisms-2.md#sse-overall>) |
| 多模型 Fallback | [4.2](<ragent-core-mechanisms-2.md#multi-model-fallback>) |
| 前端取消反向释放 permit | [4.5](<ragent-core-mechanisms-2.md#frontend-cancel>) |
| SSE 断线重连方案 | [6.1](<ragent-deep-dive-2.md#sse-reconnect>) |
| 用户停止后如何真正取消推理 | [6.2](<ragent-deep-dive-2.md#cancel-inference>) |

---

## 8. 安全

| 问题 | 定位 |
|:---|:---|
| 间接 Prompt Injection——恶意简历案例 | [4.1](<ragent-deep-dive-2.md#indirect-prompt-injection>) |
| Text2SQL AST 校验/只读/脱敏 | [4.2](<ragent-deep-dive-2.md#text2sql-security>) |
| 敏感数据分层识别 | [4.3](<ragent-deep-dive-2.md#sensitive-data>) |
| MCP 安全风险矩阵 | [3.3](<ragent-deep-dive-1.md#mcp-security>) |

---

## 9. 评测

| 问题 | 定位 |
|:---|:---|
| 9 维评测指标体系 | [5.1](<ragent-deep-dive-2.md#eval-metrics>) |
| EvalController 能自动算的指标 | [5.2](<ragent-deep-dive-2.md#eval-ragent>) |
| 成本分析模型 | [5.4](<ragent-deep-dive-2.md#cost-analysis>) |

---

## 10. 生产工程

| 问题 | 定位 |
|:---|:---|
| SSE 断线重连与断点续传 | [6.1](<ragent-deep-dive-2.md#sse-reconnect>) |
| Prompt Cache / 语义缓存 / 错误命中 | [6.4](<ragent-deep-dive-2.md#prompt-cache>) |
| Token 预算与租户成本控制 | [6.5](<ragent-deep-dive-2.md#token-budget>) |

---

## 11. Agent 状态机 / MCP

| 问题 | 定位 |
|:---|:---|
| Agent 任务状态机 | [二、状态机](<ragent-deep-dive-1.md#agent-state-machine>) |
| 避免工具重复执行 | [2.3](<ragent-deep-dive-1.md#avoid-duplicate-tools>) |
| MCP 完整通信模型 | [3.1](<ragent-deep-dive-1.md#mcp-comm-flow>) |
| MCP vs Function Calling vs OpenAPI | [3.2](<ragent-deep-dive-1.md#mcp-vs-fc>) |

---

## 12. 全链路 / Trace / 调度

| 问题 | 定位 |
|:---|:---|
| @RagTraceNode 全链路 Trace | [core-mechanisms-3](<ragent-core-mechanisms-3.md#rag-trace>) |
| 文档自动同步三级变更检测 | [core-mechanisms-3](<ragent-core-mechanisms-3.md#distributed-schedule>) |
| Pipeline 数据通道 vs 直接分块 | [core-mechanisms-3](<ragent-core-mechanisms-3.md#pipeline-vs-chunk>) |
| 降级策略 | [core-mechanisms-3](<ragent-core-mechanisms-3.md#degradation-strategy>) |

---

## 13. 基础笔记

项目概览 / 7 步流水线 / 数据库 20 张表 / 长短期记忆 / 向量库 Milvus：
- [ragent-interview-notes.md](<ragent-interview-notes.md>)
- [ragent-interview-notes-2.md](<ragent-interview-notes-2.md>)

---

## 说明

- 链接格式 `[text](<文件.md#标题锚点>)`，锚点 = 目标文件的章节标题或 `<a id>`
- 在 IDEA 中打开本文件，按住 Ctrl 点击链接跳转（Markdown 预览里是普通点击）
- 含特殊字符的标题已优先使用源文件中的 `<a id="...">` 短锚点（如 `#heading-handler`）；若仍失败，在源文件 Ctrl+F 搜标题
