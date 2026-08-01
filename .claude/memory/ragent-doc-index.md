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

**两文件互补**：`notes.md` 的五(大表切分)、六(父子文档)、九(向量库与Milvus)、十二(Rerank模型详解)、十三(设计模式)、十四(线程池) 在 `notes-2.md` 中没有或更简略；`notes-2.md` 的二(数据库表结构)、十八(意图树与Agent流转)、十九(RRF归一化)、二十(Agentic RAG)、二十一(Query决策树) 是独有的深度章节。

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
5. `notes.md` 的五(大表切分)、六(父子文档) 是两个完整的场景拆解案例，适合被问"举个例子"时展开
