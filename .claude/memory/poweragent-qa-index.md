# PowerAgent 面试问题全集索引

> 按模块分类。标注来源文件（缩写见底部），同类问题合并一行。

---

## 1. Agent 循环与推理

| 问题 | 来源 |
|:---|:---|
| Agent 循环流程/ReAct 怎么跑的？源码在哪？ | 核心Q1 / 美团Q10 / 源码Q1 / WorkflowQ7 |
| Plan&Execute 和 ReAct 两种范式有什么区别？各适合什么场景？ | 美团Q11 |
| CustomPlanner 定制了什么？强制四阶段、分级展示、截断 | 源码Q1 |
| 为什么选 Google ADK 不选 LangGraph？ | 核心Q1 / 美团Q4 |
| 失败后怎么自动重规划？LLM 在 REASONING 阶段自己判断 | 核心Q1 |
| PlanReAct 四阶段具体怎么跑的？PLANNING→ACTION→REASONING→FINAL_ANSWER | 核心Q1 |
| 根 Agent 和 5 个子 Agent 怎么分工？总机转接模型 | 核心Q7 / 美团Q19 |
| 子 Agent 怎么调起的？transfer_to_agent 和 function_call 的关系 | 核心Q7 / 源码Q2 |
| State 字典跨 Agent 透传——字段结构、只读约束、使用阶段 | 核心Q7 / 源码Q5 |
| 主 Agent 给子 Agent 分发是并行还是串行？为什么不能并行？ | 核心Q7 |
| Subagent 设计——独立上下文隔离、不污染父 Agent | 美团Q19 / 核心Q7 |
| Agent 嵌套循环/死循环三层防御 | 核心Q2 / 美团Q7/Q12/Q18 / 源码Q11 |
| Agent 稳定性兜底：防幻觉/重试/兜底/异常处理 | OCRQ6 / 美团Q6 |
| Agent 开发怎么调试？草稿模式是什么？ | 源码Q8 |

---

## 2. 多 Agent 通信与上下文

| 问题 | 来源 |
|:---|:---|
| 上下文怎么分层组装？System Prompt + Memory + Tools Result + Conversation | 核心Q3/Q4 / 美团Q12/Q13 / 源码Q9 |
| 上下文超长三级处理：快速路径→JTokkit截断→LLM摘要压缩 | 核心Q5 / 美团Q14 / 源码Q5 |
| 压缩丢工具调用历史导致重复调用怎么解决？成对删除、整轮裁剪 | 核心Q6 / 美团Q15 |
| 长期运行上下文膨胀怎么处理？ | 美团Q16 |
| Token 超限完整处理链路 | 模块02Q6 |
| Agent 和 LLM 之间怎么通信？上下文怎么传？LiteLlm 模型适配 | 源码Q6 |
| AutoAgentRequest 关键字段 | WorkflowQ8 |
| 多智能体"无限循环"或"通信冗余"怎么解决？ | 美团Q18 |

---

## 3. 记忆系统

| 问题 | 来源 |
|:---|:---|
| Memory 怎么做？怎么插入上下文？长度问题？ | 美团Q28 / 源码Q10 |
| Mem0 记忆系统实现细节 | 美团Q29 |
| 长短期记忆区分与保存，结构化 vs 文本是否冲突 | 美团Q30 |
| 短期记忆 Redis Session vs DB（一个 LRANGE vs N 次 SQL） | 美团Q28 |
| 长期记忆摘要为什么不记答案？（RAG 最新检索冲突） | 美团Q28 |
| 长期记忆 Mem0 向量检索 vs LLM 结构化摘要选型 | 模块05Q6 |
| 短期长期记忆怎么协同？ | 模块05Q7 |
| 会话变量跨对话持久化（globalVariables → Redis Session） | 美团Q30 |
| Agent session 和 state 管理 | 源码Q5 |
| 会话和记忆管理 | WorkflowQ5 |
| 长期记忆和会话记忆是两套系统（缺点） | 核心Q33⑧ |

---

## 4. RAG 检索与召回

| 问题 | 来源 |
|:---|:---|
| RAG 项目整体架构和完整构建流程（12 步） | 核心Q20 / 美团Q20 / WorkflowQ12 |
| 检索引擎基于 Milvus 还是 ES？ | 模块02Q1 |
| 召回策略：稠密/稀疏/混合 | 核心Q23 / 美团Q23 |
| RRF 融合为什么比直接加权好？ | 模块02Q2 |
| 为什么要单独做 Rerank？cross-encoder vs bi-encoder | 核心Q24 / 美团Q24 |
| 检索空结果怎么处理？ | 模块02Q3 |
| RAG 文档隔离怎么做？关联文档和术语文档怎么处理？ | 核心Q26 / 美团Q26 |
| Code-RAG 怎么设计？ | 核心Q27 / 美团Q27 |
| Agentic RAG / 主动式 RAG | 核心Q21 / 美团Q21 |
| RRF k 值固定（缺点） | 核心Q33⑦ |

---

## 5. 意图识别与 Query Rewrite

| 问题 | 来源 |
|:---|:---|
| 意图识别到底是什么？ | 核心Q28 |
| 意图识别和 Rewrite 怎么实现的？提示词怎么写？ | 核心Q22 / 美团Q22 |
| 意图识别和 Rewrite 的关系 | 核心Q29 |
| 如何判断用户 Query 和上文有关联？（多轮对话指代消解） | 核心Q22 |
| 混合路由：确定性场景走代码逻辑，开放场景走 Prompt 语义分发 | 核心Q22 |

---

## 6. RAG 文档处理与切分

| 问题 | 来源 |
|:---|:---|
| 支持的文档格式有哪些？处理策略？ | OCRQ1 |
| OCR 怎么用的？原理？ | OCRQ2 |
| OCR 错字和截断处理 | OCRQ3 |
| 什么时候用 LLM，什么时候用脚本解析？ | OCRQ4 |
| 文档切片策略？召回不到数据怎么处理？ | 核心Q25 / 美团Q25 |
| 除了父子文档还有什么分块方式？怎么选型？ | OCRQ7 |

---

## 7. 工具 / Skill / MCP

| 问题 | 来源 |
|:---|:---|
| Skill 机制做什么？开发做了哪些？会放代码吗？ | 美团Q6/Q7 |
| MCP 和 Skill 区别？和 CLI 区别？ | 美团Q8 |
| 工具链能否 Skill 化？ | 美团Q9 |
| 插件和 Dify Tool 区别？ | 模块05Q1 |
| 五种工具类型执行链路 | 模块05Q2 |
| 工具 Schema 自动生成让 LLM 理解 | 模块05Q3 |
| MCP vs Plugin 选型 | 模块05Q4 |
| MCP 工具注册到 Agent | 模块05Q5 |
| Agent 工具注册和 LLM 感知 | 源码Q3 |
| 工具调用全链路 | 源码Q4 |
| Agent 怎么决定调哪个工具？ | 模块02Q4 |
| 工具系统如何设计？ | WorkflowQ4 |
| 加新工具完整步骤 | 源码Q13 |
| 工具调用是同步 HTTP 阻塞（缺点） | 核心Q33⑥ |

---

## 8. WorkFlow / DAG 引擎

| 问题 | 来源 |
|:---|:---|
| DAG 引擎介绍：入口识别→JSON解析→同步/并发双模式→递归执行 | 核心Q30 / WorkflowQ1 |
| @NodeType+ModuleFactory 注册机制，50+节点类型 | 核心Q30 |
| 同步递归 vs 线程池并发，CountDownLatch+CompletionService | 核心Q30 |
| 节点失败降级：重试+moduleDefaultOutput 兜底 | 核心Q30 |
| 执行器单例化：Bean vs 节点实例区分，100画布并发安全 | 核心Q30 |
| FlowContext 字段：switchMap去重、输入就绪检查、变量池 | 核心Q30 |
| AgentFlow DAG vs Dify | 核心Q31 |
| vs LangChain 核心区别 | WorkflowQ3/Q9 |
| agentflow-server 是 workflow 编排吗？ | WorkflowQ9 |
| AgentFlow 功能模块+技术要点 | WorkflowQ10 |
| WorkFlow 引擎——内存执行挂了全丢（缺点） | 核心Q33① / OCRQ5 |
| 意图路由太粗——5 子 Agent 无法细粒度 FAQ（缺点） | 核心Q33② |
| 没有模型熔断降级（缺点） | 核心Q33⑤ |

---

## 9. DataFlow 离线引擎

| 问题 | 来源 |
|:---|:---|
| DataFlow 离线跑批引擎：双层状态机、DB驱动调度（非消息队列） | 核心Q32 |
| DataFlow 与 AgentFlow 联动：节点状态/调度/容错 | WorkflowQ11 |
| DataFlow 30s 轮询间隙（缺点） | 核心Q33③ |
| 为什么不用 Kafka？（状态和任务同表一致、分钟级延迟够用、减少中间件） | 核心Q32 |

---

## 10. 评测 / 可观测性 / Trace

| 问题 | 来源 |
|:---|:---|
| 评测方法是什么？ | 模块09Q35 |
| Trace 结构，日志怎么结构化？ | 模块09Q33 |
| 跨 Session 日志系统 | 模块09Q34 |
| 幻觉怎么减少？ | 模块09Q36 |
| 可观测性怎么实现？ | WorkflowQ6 |
| Agent 快照版本管理 | 模块02Q5 |

---

## 11. 部署 / 性能 / 并发

| 问题 | 来源 |
|:---|:---|
| 部署性能与并发控制？ | 模块09Q37 |
| 项目难点？ | 模块09Q38 |
| SSE 用 reqId 频道广播 vs WebSocket？ | 模块02Q7 |
| LLM 模型路由怎么做？ | 模块02Q8 |
| streaming 端到端怎么实现？ | 源码Q7 |
| Java↔Python 跨语言调用开销（缺点） | 核心Q33④ |

---

## 12. 系统对比

| 问题 | 来源 |
|:---|:---|
| AgentFlow DAG vs Dify | 核心Q31 |
| vs LangChain | WorkflowQ3/Q9 |
| harness 对比 | 美团Q3 |
| 为什么不用 Claude Code | 美团Q5 |
| AgentFlow 核心缺点与解决方案（8 个） | 核心Q33 |

---

## 13. 面试话术 / 简历

| 问题 | 来源 |
|:---|:---|
| 自我介绍，重点介绍 Agent 项目 | 美团Q1 |
| Agent 项目核心架构 | 美团Q2 |
| 一条请求完整执行链路 | WorkflowQ2 |
| 上传文档到知识库可检索全链路（12 步） | WorkflowQ12 |
| 文件上传和多模态实现 | 源码Q12 |

---

## 来源缩写

| 缩写 | 文件 | 行数 |
|:---|:---|:---|
| 美团 | 美团 Agent Q&A.md | 4626 |
| 核心 | Agent 核心机制面试 Q&A.md | 2012 |
| 源码 | Agent 开发面试源码 Q&A.md | 882 |
| Workflow | Agent Workflow 面试 Q&A.md | 1517 |
| 模块02 | 模块源码分析 02：知识库_搜索.md | 2861 |
| 模块05 | 模块源码分析 05-08.md | 1835 |
| 模块09 | 模块源码分析 09-10.md | 1553 |
| OCR | RAG 文档处理与 OCR Q&A.md | 779 |
