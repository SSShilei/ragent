# Ragent 面试笔记索引

> `/home/shilei/IdeaProjects/ragent/docs-interview/` 下共 3 个 Markdown 文件。
> 使用方式：搜索关键词定位到对应文件，再根据行号精确定位。

---

## 文件概览

| 文件 | 行数 | 定位 |
|:---|:---|:---|
| `ragent-interview-notes.md` | 1116 | 原版：侧重管线细节、切分案例、设计模式 |
| `ragent-interview-notes-2.md` | 1381 | 扩展版：侧重表结构、意图树、RRF 归一化、Agentic RAG |
| `ragent-chunking-analysis.md` | — | **Chunking 专题**：Parser 矩阵、Block 模型、分块流程、与 AgentFlow 对比、六种业界策略评估 |
| `ragent-intent-rewrite-config.md` | — | **意图树 & Query 重写**：t_intent_node 配置 SQL、KB 意图识别流程、并行/串行关系、改写模型选型、temperature 调参 |

**两文件互补**：`notes.md` 的五(大表切分)、六(父子文档)、九(向量库与Milvus)、十二(Rerank模型详解)、十三(设计模式)、十四(线程池) 在 `notes-2.md` 中没有或更简略；`notes-2.md` 的二(数据库表结构)、十八(意图树与Agent流转)、十九(RRF归一化)、二十(Agentic RAG)、二十一(Query决策树) 是独有的深度章节。

| `ragent-core-mechanisms-2.md` | — | **核心机制第二篇**：Context 组装（chunk→Prompt）、Rerank 详解（cross vs bi-encoder、模型路由）、限流并发（Redis 公平队列 + MinerU 限流）、SSE 流式（多模型 fallback + 首包超时探测） |
| `ragent-core-mechanisms-3.md` | — | **核心机制第三篇**：全链路 Trace、分布式调度、MCP 工具调用、评测体系、降级策略、Pipeline 数据通道、飞书文档接入、权限 & 多租户 |
| `ragent-deep-dive-1.md` | — | **深水区（上）**：检索召回排查（6 层框架）、Agent 状态机（暂停/恢复/死循环防）、MCP 通信与安全 |
| `ragent-deep-dive-2.md` | — | **深水区（下）**：RAG 安全（间接注入/Text2SQL/脱敏）、Agent 评测（9 维指标）、生产工程（SSE 重连/取消推理/Prompt Cache/Token 预算） |

---

## 快速导航（按主题分类）

### 项目概览 & 技术选型
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 项目定位/四层模块/技术栈 | 一 (line 8) | 一 (line 8) |
| vs Spring AI / LangChain4j | line 31 | — |
| 四大模块职责 | line 20 | line 18 |

### 数据库表结构（20 张表）
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 全表 Snowflake 主键设计 | — | 2.2 (line 45) |
| 逻辑删除 deleted 字段 | — | 2.3 (line 62) |
| 会话域表 (t_conversation/t_message) | — | 2.4 (line 75) |
| 知识库域表 (t_knowledge_base/document/chunk) | — | 2.5 (line 145) |
| 意图域表 (t_intent_node) | — | 2.6 (line 282) |
| 全链路追踪表 (t_rag_trace_run/node) | — | 2.7 (line 327) |
| 入库流水线表 (t_ingestion_pipeline/task) | — | 2.8 (line 366) |
| 审计日志表 (t_biz_change_log) | — | 2.9 (line 386) |
| 面试常见追问 | — | 2.10 (line 416) |

### 用户问答完整管线（7 步）
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 7 步总览 | 二 (line 37) | 三 (line 443) |
| ① loadMemory() 并行加载 | line 64 | line 450 |
| ② rewriteQuery() 改写拆分 | line 79 | line 459 |
| ③ resolveIntents() 意图分类 | line 112 | line 467 |
| ④⑤ 短路机制 (歧义引导/闲聊) | line 131 | line 471 |
| ⑥ retrieve() 检索引擎 | line 141 | — |
| ⑦ streamRagResponse() 流式输出 | line 145 | — |

### 多路检索 + RRF + Rerank
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 四路并行检索 (VECTOR/KEYWORD/GRAPH/WEB) | 三 (line 164) | 四 (line 477) |
| 全局检索安全网 | line 191 | — |
| 后处理器链 (Dedup→RRF→Rerank→MetadataEnrich) | line 201 | line 488 |
| RRF 参数调优 (k=60) | line 260 | line 497 |
| RRF 归一化深度解析 (分数不可比问题) | — | 十九 (line 921) |
| 五条路五种分数量纲分析 | — | 19.1 (line 923) |
| RRF 只信名次不信分数 | — | 19.2 (line 951) |
| 三步融合流水线 | — | 19.3 (line 994) |
| 三种隐式权重分配 | — | 19.4 (line 1029) |
| k 值调参指南 | — | 19.6 (line 1073) |
| RRF 面试话术模板 | — | 19.7 (line 1086) |

### 文档切分策略
| 主题 | notes.md 行号 | notes-2.md 行号 | chunking-analysis.md |
|:---|:---|:---|:---|
| 三路分发 (StructuredChunkingService) | 四 (line 285) | 五 (line 505) | 一 (架构总览) |
| 两种文本策略 (FIXED_SIZE/STRUCTURE_AWARE) | line 287 | line 515 | 四 (Legacy 策略) |
| block-aware 精细切分 (表格案例) | line 342 | line 522 | 三 (分块流程) |
| ChunkPacker 贪心合并 | line 403 | line 535 | 三.3 |
| 大表切分完整案例 | 五 (line 426) | line 539 | — |
| 父子文档等价实现 | 六 (line 465) | line 543 | 七 (Parent-Child 等价方案) |
| **Parser 矩阵 / Block 模型** | — | — | **二（专题）** |
| **TableChunker 双文本嵌入** | — | — | **三.2（专题）** |
| **vs AgentFlow 对比** | — | — | **八（专题）** |
| **六种业界策略评估** | — | — | **九（专题）** |
| **完整案例（Markdown→入库）** | — | — | **六（专题）** |
| **面试话术** | — | — | **十（专题）** |

### 长短期记忆
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 存储：为什么用关系表而非 JSON | 七 (line 529) | 六 (line 549) |
| 短期记忆：滑动窗口详解 | line 542 | — |
| 长期记忆：渐进式摘要详解 | line 559 | — |
| 摘要触发机制 | — | line 567 |
| 摘要设计原则 | — | line 571 |

### 意图识别 & Agent 流转
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 意图树管理界面 | 八 (line 633) | — |
| 意图多重价值 (路由/参数提取/置信度/歧义引导) | line 649 | 九 (line 618) |
| TinyBERT 讨论 | line 718 | — |
| 意图树物理结构 (DOMAIN→CATEGORY→TOPIC) | — | 18.1 (line 688) |
| 意图树加载与缓存 | — | 18.2 (line 720) |
| LLM 一次性评所有叶子节点 | — | 18.3 (line 734) |
| 意图→执行完整流转链路 | — | 18.4 (line 766) |
| "Agent" 本质 (不是独立 Agent 循环) | — | 18.5 (line 814) |
| 歧义引导触发逻辑 | — | 18.6 (line 848) |
| 意图树扩展性 | — | 18.7 (line 875) |

### Agent/MCP 工具调用
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 触发不是独立 Agent 循环 | 十 (line 766) | 七 (line 577) |
| 为什么不搞 Agent loop | line 785 | — |
| MCP 架构 (tool auto-discovery) | — | line 583 |
| 工具超时与线程池隔离 | line 795 | — |
| 业界方案对比 | line 809 | line 590 |

### 文档自动同步 & 分布式调度
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 完整时序案例 | 十一 (line 822) | — |
| 仅 URL 类型支持 | — | 八 (line 596) |
| 分布式锁安全设计 (DB 乐观锁) | line 883 | — |
| 三级变更检测 (ETag→Last-Modified→SHA-256) | line 902 | line 602 |
| 事务原子写入 | line 911 | line 608 |
| 安全防护 | — | line 612 |

### Agentic RAG & 架构对比
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| Agentic RAG 定义与光谱 | — | 二十 (line 1098) |
| Ragent 在 Agentic RAG 光谱中的位置 | — | 20.2 (line 1108) |
| Ragent Agentic 机制详解 | — | 20.3 (line 1128) |
| vs Full Agent (ReAct Loop) 对比 | — | 20.4 (line 1196) |
| 为什么选管线而非 Full Agent | — | 20.5 (line 1211) |
| 升级到 Full Agent 的路径 | — | 20.6 (line 1224) |
| Query 决策树分析 | — | 二十一 (line 1260) |
| 已实现 vs 未实现的 Query 策略 | — | 21.2-21.3 (line 1292) |

### 向量库 & Milvus
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| PGVector/Milvus 双实现切换 | 九 (line 728) | — |
| 共享 Collection 隔离策略 | line 738 | — |
| 向量量级计算 | line 753 | — |

### Rerank 模型
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| cross-encoder vs bi-encoder | 十二 (line 927) | — |
| 模型路由与降级 | line 946 | — |
| DPO 展望 | line 975 | — |

### 设计模式 & 线程池
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 策略模式 — SearchChannel | 十三 (line 987) | 十 (line 627) |
| 注册表模式 — McpToolRegistry | line 1004 | — |
| 责任链模式 — 后处理器链 | line 1017 | — |
| sealed interface — ChunkingOptions | line 1032 | — |
| 线程池架构 | 十四 (line 1044) | — |
| 8 个专用线程池 | — | 十一 (line 641) |

### 面试话术模板
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| "介绍一下你做的 RAG 项目" | 十五 (line 1061) | 十二 (line 647) |
| "难点在哪里？" | line 1075 | line 659 |
| "有什么可以优化的？" | line 1079 | line 663 |
| "为什么主键全用 VARCHAR(20)？" | — | line 670 |
| "为什么用 JSONB 而不是 EAV？" | — | line 674 |
| "分布式锁为什么放 DB 而不是 Redis？" | — | line 678 |

### 附录
| 主题 | notes.md 行号 | notes-2.md 行号 |
|:---|:---|:---|
| 核心文件索引 (按模块列出关键文件) | 附录 (line 1088) | 附录 (line 893) |

---

## 按面试高频问题速查

### Q: 介绍一下 Ragent 项目
- `notes.md` line 8-19 (项目概览), line 1061-1073 (话术模板)
- `notes-2.md` line 8-27 (项目概览), line 647-658 (话术模板)

### Q: 一次用户问答的完整链路？
- `notes.md` line 37-163 (7 步详解)
- `notes-2.md` line 443-475 (精简版)

### Q: 多路召回怎么做的？为什么需要 RRF？
- `notes.md` line 164-283 (完整解析)
- `notes-2.md` line 477-503 + line 921-1096 (RRF 深度归一化)

### Q: 文档切分策略？大表怎么切？
- `notes.md` line 285-464 (切分+大表+父子文档)
- `notes-2.md` line 505-547 (精简版)

### Q: 长短期记忆怎么实现？
- `notes.md` line 529-631 (完整链路)
- `notes-2.md` line 549-575

### Q: Agent/MCP 工具调用机制？
- `notes.md` line 766-821
- `notes-2.md` line 577-594

### Q: 意图识别怎么做的？意图树是什么？
- `notes.md` line 633-727
- `notes-2.md` line 618-625 + line 686-891 (意图树深度解析)

### Q: 分布式调度/文档同步怎么做的？
- `notes.md` line 822-926
- `notes-2.md` line 596-616

### Q: Ragent 是 Agentic RAG 吗？和 ReAct 什么关系？
- `notes-2.md` line 1098-1258 (二十) + line 1260-1380 (二十一)

### Q: 数据库表怎么设计的？为什么主键用 VARCHAR(20)？
- `notes-2.md` line 29-441 (完整的二)

### Q: 用了哪些设计模式？
- `notes.md` line 987-1043 (十三)
- `notes-2.md` line 627-640 (十)

### Q: 线程池怎么规划的？
- `notes.md` line 1044-1060 (十四)
- `notes-2.md` line 641-646 (十一)

---

## 注意事项

1. **两文件互补**：面试准备建议两文件都看，`notes.md` 侧重管线细节和代码案例，`notes-2.md` 侧重架构对比和深度解析
2. `notes-2.md` 章节编号跳过了十三-十七，实际只有 一~十二 + 十八~二十一，不是缺失
3. 两个文件都引用具体源码文件+行号，面试时可作为可信度背书
4. 优先阅读 `notes-2.md` 的十八-二十一（意图树/Agentic RAG/RRF归一化/Query决策树），这些是面试加分深水区
5. **Chunking 专题**见 `ragent-chunking-analysis.md`，覆盖 Parser/Block/分块/Metadata 全链路，下方有独立速查表
6. `notes.md` 的五(大表切分)、六(父子文档) 是两个完整的场景拆解案例，适合被问"举个例子"时展开
7. 项目日志文件：`logs/ragent.log`（`bootstrap/src/main/resources/logback-spring.xml` 配置，按天滚动保留 7 天，rag 包 DEBUG 级别）

---

## Chunking 专题速查（ragent-chunking-analysis.md）

### Q: Ragent 和 AgentFlow 的 Chunking 有什么区别？
- 一 (line 7)：完整对比表 + 一句话总结

### Q: MinerU 能处理什么格式？MinerU 直接输出 Block 吗？
- 二.2 (line 93)：路由优先级表
- 二.4 (line 130)：MinerU 六步异步流程
- 二.5 (line 145)：两层分工架构（MinerU → markdown → commonmark → Block）

### Q: commonmark AST 解析器是什么？
- 二.6 (line 188)：原理、映射表、代码示例

### Q: 6 种 Block 各是什么结构？有什么用？
- 三.3 (line 293)：完整字段说明 + JSON 示例

### Q: TableChunker 的双文本嵌入是怎么做的？
- 四.4 (line 425)：content vs embeddingText 详细对比 + 为什么需要

### Q: outlinePath 是怎么注入到每个 chunk 的？
- 四.3 (line 412)：HeadingHandler 算法（同级替换/上级追加/顶级重置/跳级补齐）
- 十一.4 (line 979)：每个 chunker 注入的字段对照表

### Q: sectionContext 是什么？和 outlinePath 有什么区别？
- 四.4 (line 425)：TableChunker 的 sectionContext 构造
- 四.7 (line 476)：ImageChunker 的 sectionContext

### Q: 按 block 类型分流 chunker 是什么意思？每种 block 的切分策略有什么不同？
- 四.2 (line 380)：Dispatcher instanceof 分发链
- 四.4-4.7：TableChunker/CodeChunker/ListChunker/ImageChunker 策略差异

### Q: ChunkPacker 做了什么？合并时元数据怎么变？
- 四.8 (line 484)：贪心合并 + 块级重叠
- 十一.4 (line 979)：合并时各字段变化规则表

### Q: STRUCTURE_AWARE 和 block-aware 是什么关系？
- 五.2 (line 545)：STRUCTURE_AWARE 三步流程
- 五.2.1 (line 568)：vs Block-Aware 对比（6 个覆盖不到的能力）

### Q: Parent-Child 怎么做的？和传统方案有什么区别？
- 八 (line 758)：零成本等价方案对比

### Q: 六种业界分块策略你们用了哪些？为什么有些不用？
- 九 (line 781)：逐策略分析 + 总结矩阵

### Q: Metadata（元数据）怎么设计的？有哪些字段？
- 十一.1 (line 896)：Ragent chunk 完整元数据
- 十一.2 (line 926)：博客 7 字段 vs Ragent 对照
- 十一.4 (line 979)：每个 chunker 注入的字段对照表
- 十一.5 (line 1018)：元数据完整生命周期图
- 十一.7 (line 1081)：做对了什么、缺了什么

### Q: 面试时怎么讲 Chunking？
- 十 (line 854)：4 个高频问题的标准回答模板

---

## 意图树 & Query 重写 专题速查（ragent-intent-rewrite-config.md）

### Q: 意图树有默认实现吗？怎么配？
- 一.1 (line ~)：DefaultIntentClassifier 是默认实现，但无内置节点
- 一.2：三级加载链（Redis → DB → 空）
- 一.3：t_intent_node 表关键字段 + **promptSnippet vs promptTemplate 区别**（源码 + 生效时机）
- 一.4：t_intent_node 表完整 INSERT SQL
- 一.5：配完后的链路变化（向量通道从全局转意图定向）

### Q: KB 意图怎么识别？
- 二.1：classifyTargets 6 步流程（叶子收集 → Prompt → LLM 打分 → 排序 → 三类分流 → 作用域选择）
- 二.2：**意图识别不只用在检索中**（四个消费点：歧义引导/闲聊短路/检索分流/答题 Prompt 拼装）
- 二.3：**四个消费点汇总图**
- 二.4：**业内方案对比**（无意图/规则路由/语义路由/LLM分类/多Agent委托 5 个方案 + 选型决策框架）

### Q: 意图树和 Query 重写是并行的吗？每次对话都重写吗？
- 三.1：不并行，串行（rewriteQuery → resolveIntents）
- 三.2：当前策略完整链路（含 Multi-Query 变体扩展）
- 三.3：两个开关 query-rewrite.enabled + multi-query.enabled
- 三.4：按开关组合的 LLM 调用次数表
- 三.5：变体扩展触发条件（短 query ≤10 字符才触发）
- 三.6：变体生成 details（T=0.7 买多样性，原始 rewrite 排第一）
- 三.7：**Multi-Query 改造前后对比**（数据模型/重写/检索/核心差异表）+ 面试话术
- 三.8：**Query 决策树第一档：精确实体短路**（ExactEntityDetector 正则设计 + maybeBypassForExactEntity 实现 + 6 个关键设计决策 + 测试覆盖 + 面试话术）
- 三.9：**Ragent 当前 Query 决策树完整版**（6 档流程图 + 与业界决策树对照矩阵 + 5 个特点 + Q&A：为什么没 Step-back/HyDE）

### Q: RAGChatServiceImpl 实现的是哪个对话？
- 四：用户问答 SSE 流式对话；RAGChatServiceImpl 是事务边界（限流+Trace+SSE），流水线大脑在 StreamChatPipeline

### Q: 改写 query 用哪个模型？
- 五：当前用默认 chat 模型 deepseek-v4-flash；改写/答题共用主链路；优化建议：让改写走 ollama 小模型
- 五.5：**多轮对话与指代消解**（改写带最近 2 轮历史 + Prompt 指代规则 + `user-question-rewrite.st` 模板 + 业内 4 种方案对比）

### Q: temperature 参数有什么作用？
- 六.6.2：数学原理 softmax(logit/T)
- 六.6.4：Ragent 多个调用点温度调优（改写 0.1 / 变体 0.7 / 意图 0.1 / 答题 0.0）
- 六.6.5：为什么 RAG 答题用 0.0（防幻觉）
- 六.6.6：temperature=0 也不保证 100% 可复现（需配合 topP=1）

---

## 核心机制第二篇 专题速查（ragent-core-mechanisms-2.md）

### Q: 检索回来的 chunk 怎么拼成 Prompt？"等价父文档"具体怎么实现？
- 一.1：DefaultContextFormatter 三层分支（无意图/单意图/多意图）
- 一.2：单意图 → renderSnippetRules + renderChunksGroupedByDoc + renderKbSection **+ CONTEXT_FORMAT_PATH 模板结构（kb-section/snippet-rules/kb-doc-block）**
- 一.3：**按 docId 分组 + 组内按 chunkIndex 排序还原原文顺序**（等价父文档的核心实现）+ **MetadataEnrichment 只补齐 3 个元数据字段（docId/chunkIndex/docName），不取内容**
- 一.4：多意图合并去重（chunkId 去重 LinkedHashMap 保序）

### Q: Rerank 模型怎么用的？cross-encoder 比 bi-encoder 好在哪？
- 二.1：Bi-encoder vs Cross-encoder 对比表（原理/速度/精度/阶段）
- 二.2：为什么 Rerank 放 RRF 之后（减候选量 31→17，省近一半成本）
- 二.3：模型路由（qwen3-rerank via gitee）
- 二.4：归因日志解读（向量 vs 关键词 Rerank 前后存活率）

### Q: 限流和并发控制怎么做的？
- 三.1：ChatQueueLimiter → SSE 入口限流（开关/排队/reject/bind 取消）
- 三.2：**FairDistributedRateLimiter 详解**（解决什么问题 + 完整时间线 + 分步详解入队/轮询/原子抢占/取消链路/状态机 + 5 个安全机制对照表）
- 三.3：MinerU 解析限流（独立 5 槽）

### Q: SSE 流式输出怎么做的？模型挂了怎么处理？
- 四.1：SSE 完整链路（pipeline → RoutingLLMService → 多模型 fallback → SseEmitter）
- 四.2：**多模型 Fallback**（逐个尝试 + 健康标记 + 60s 首包超时）
- 四.3：健康状态存储（failure-threshold=2 + open-duration-ms=30000 半开）
- 四.4：首包超时探测（LlmFirstPacketProbe + TTFT Trace）
- 四.5：前端取消 → cancelBinder 反向释放 permit

---

## 核心机制第三篇 专题速查（ragent-core-mechanisms-3.md）

### Q: 全链路追踪怎么做的？
- 一.1：TTL 透传（TransmittableThreadLocal + 深拷贝 NODE_STACK）
- 一.2：AOP 采集（@RagTraceNode → startNode/finishNode/pushNode/popNode）
- 一.3：t_rag_trace_run + t_rag_trace_node 表结构 + 调用树还原

### Q: 文档自动同步（分布式调度）怎么做的？
- 二.1：四阶段流程（扫描 → 变更检测 → 原子切换 → 锁续期）
- 二.2：**三级变更检测**（ETag → Last-Modified → SHA-256）+ 为什么三级
- 二.3：DB 乐观锁 + 心跳续期 + 安全防护

### Q: MCP 工具调用机制？
- 三.1：架构（意图 MCP 节点 → registry → extractor → executor）
- 三.3：LLM 驱动的参数提取 + 意图节点自定义 Prompt
- 三.4：mcpBatchExecutor 并行执行

### Q: 评测体系怎么设计的？
- 四.1：EvalController 纯检索评测（不调 LLM 消除随机性）
- 四.2：intentLeafIds 评估意图 Top-1 准确率

### Q: 降级策略有哪些？
- 五.1：LLM 多模型 fallback（60s 首包超时 + 健康标记）
- 五.2：Query 重写失败 → 术语归一化兜底
- 五.3：Embedding fallback chain
- 五.5：关键词索引 best-effort 不阻塞主链路

### Q: Pipeline 和直接分块的区别？
- 六：IngestionEngine DAG 编排 vs 三步固定链路

### Q: 飞书文档怎么接？
- 七：FeishuFetcher + Token 认证 + Docx 在线文档 vs 二进制附件

### Q: 权限 & 多租户怎么隔离？
- 八：Sa-token + kb_id 维度隔离（非 SaaS 多租户）

---

## 深水区专题速查

### ragent-deep-dive-1.md（上）

#### Q: 检索召回率低怎么排查？
- 一.1：分层排查框架（Query→意图→通道→后处理→Chunk→Embedding 六层）
- 一.2：诊断 SQL（向量维度检查、混库检测、ES 数据验证）

#### Q: Agent 任务状态机怎么设计？服务重启后任务怎么恢复？
- 二.1：CREATED → FINAL_ANSWER 六状态转换表
- 二.2：DB 乐观锁恢复 + 中间结果持久化 + 幂等标记

#### Q: 怎么避免工具重复执行？
- 二.3：三层防护（幂等 token + 结果缓存 + DB UNIQUE 约束）

#### Q: 用户暂停/取消任务怎么实现？
- 二.4：StreamTaskManager Redis 跨实例广播取消链路

#### Q: 死循环怎么管控？
- 二.5：三层防御表（max_llm_calls 硬上限 / Future.get(timeout) 超时 / 预检 DFS）+ Ragent Pipeline 特有防护

#### Q: MCP 完整通信模型？和 Function Calling 的区别？
- 三.1：MCP 五步通信流程
- 三.2：MCP / Function Calling / OpenAPI 对比表

#### Q: MCP 安全风险与防护？
- 三.3：风险矩阵（Server 注入/数据外泄/冒充/权限过宽/DDOS）
- 三.4：Ragent 当前安全层 + 缺失的防护

### ragent-deep-dive-2.md（下）

#### Q: RAG 安全不是 Prompt 里写一句"不要泄露数据"？
- 四.1：间接 Prompt Injection + 四层防护（结构隔离 → 内容扫描 → 降级兜底 → 审计）
- 四.2：Text2SQL AST 校验 + 只读限制 + 成本控制
- 四.3：敏感数据分层识别（规则 → NER → LLM 三层递进）
- 四.4：安全处置预案

#### Q: Agent 怎么评测？
- 五.1：9 维指标体系（成功率/工具准确率/不必要调用率/引用正确率/事实一致性/接管率/延迟/完成时间/成本）
- 五.2：Ragent EvalController 能自动算的指标
- 五.4：成本分析模型

#### Q: SSE 断线重连怎么处理？
- 六.1：Ragent 当前实现 vs 完整的重连方案（taskId + lastEventId 进度恢复）

#### Q: 用户停止生成后怎么真正取消推理？
- 六.2：Ragent 完整取消链路（前端 → cancelBinder → Ticket.cancel → StreamTaskManager → Redis 广播 → StreamCancellationHandle）

#### Q: Prompt Cache、语义缓存、错误命中怎么取舍？
- 六.4：Prompt Cache 适合 system prompt 不变部分 / RAG 不该做语义缓存 / 错误命中防法（TTL + knowledge_version key）

#### Q: Token 预算和租户成本控制怎么搞？
- 六.5：TokenBudget 模型 + 成本归因到每个 LLM 调用点
