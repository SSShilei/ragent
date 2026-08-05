# PowerAgent 面试问题全集索引（可点击跳转）

> 按模块分类。点击"来源"列链接直接跳到对应问题。Ctrl+单击跳转（IDEA Markdown 预览）。
> 链接格式：`[来源题号](../doc-poweragent/文件.md#q题号)`，锚点已加到源文件。

---

## 1. Agent 循环与推理

| 问题 | 来源 |
|:---|:---|
| Agent 循环流程/ReAct 怎么跑的？源码在哪？ | [核心Q1](../doc-poweragent/Agent 核心机制面试 Q&A.md#q1) / [美团Q10](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q10) / [源码Q1](../doc-poweragent/Agent 开发面试源码 Q&A.md#q1) / [WorkflowQ7](../doc-poweragent/Agent Workflow 面试 Q&A.md#q7) |
| Plan&Execute 和 ReAct 两种范式有什么区别？ | [美团Q11](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q11) |
| CustomPlanner 定制了什么？ | [源码Q1](../doc-poweragent/Agent 开发面试源码 Q&A.md#q1) |
| 为什么选 Google ADK 不选 LangGraph？ | [核心Q1](../doc-poweragent/Agent 核心机制面试 Q&A.md#q1) / [美团Q4](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q4) |
| 失败后怎么自动重规划？ | [核心Q1](../doc-poweragent/Agent 核心机制面试 Q&A.md#q1) |
| PlanReAct 四阶段具体怎么跑的？ | [核心Q1](../doc-poweragent/Agent 核心机制面试 Q&A.md#q1) |
| 根 Agent 和 5 个子 Agent 怎么分工？ | [核心Q7](../doc-poweragent/Agent 核心机制面试 Q&A.md#q7) / [美团Q19](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q19) |
| 子 Agent 怎么调起的？transfer_to_agent 和 function_call 的关系 | [核心Q7](../doc-poweragent/Agent 核心机制面试 Q&A.md#q7) / [源码Q2](../doc-poweragent/Agent 开发面试源码 Q&A.md#q2) |
| State 字典跨 Agent 透传 | [核心Q7](../doc-poweragent/Agent 核心机制面试 Q&A.md#q7) / [源码Q5](../doc-poweragent/Agent 开发面试源码 Q&A.md#q5) |
| 主 Agent 给子 Agent 分发是并行还是串行？ | [核心Q7](../doc-poweragent/Agent 核心机制面试 Q&A.md#q7) |
| Subagent 设计——独立上下文隔离 | [美团Q19](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q19) / [核心Q7](../doc-poweragent/Agent 核心机制面试 Q&A.md#q7) |
| Agent 嵌套循环/死循环三层防御 | [核心Q2](../doc-poweragent/Agent 核心机制面试 Q&A.md#q2) / [美团Q7](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q7) / [美团Q12](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q12) / [美团Q18](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q18) / [源码Q11](../doc-poweragent/Agent 开发面试源码 Q&A.md#q11) |
| Agent 稳定性兜底：防幻觉/重试/兜底 | [OCRQ6](../doc-poweragent/RAG 文档处理与 OCR 面试 Q&A 0728.md#q6) / [美团Q6](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q6) |
| Agent 开发怎么调试？草稿模式？ | [源码Q8](../doc-poweragent/Agent 开发面试源码 Q&A.md#q8) |

---

## 2. 多 Agent 通信与上下文

| 问题 | 来源 |
|:---|:---|
| 上下文怎么分层组装？ | [核心Q3](../doc-poweragent/Agent 核心机制面试 Q&A.md#q3) / [核心Q4](../doc-poweragent/Agent 核心机制面试 Q&A.md#q4) / [美团Q12](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q12) / [美团Q13](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q13) / [源码Q9](../doc-poweragent/Agent 开发面试源码 Q&A.md#q9) |
| 上下文超长三级处理 | [核心Q5](../doc-poweragent/Agent 核心机制面试 Q&A.md#q5) / [美团Q14](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q14) / [源码Q5](../doc-poweragent/Agent 开发面试源码 Q&A.md#q5) |
| 压缩丢工具调用历史导致重复调用？ | [核心Q6](../doc-poweragent/Agent 核心机制面试 Q&A.md#q6) / [美团Q15](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q15) |
| 长期运行上下文膨胀？ | [美团Q16](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q16) |
| Token 超限完整处理链路 | [模块02Q6](../doc-poweragent/模块源码分析 02：知识库_搜索.md#q6) |
| Agent 和 LLM 之间怎么通信？ | [源码Q6](../doc-poweragent/Agent 开发面试源码 Q&A.md#q6) |
| AutoAgentRequest 关键字段 | [WorkflowQ8](../doc-poweragent/Agent Workflow 面试 Q&A.md#q8) |
| 多智能体"无限循环"或"通信冗余"？ | [美团Q18](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q18) |

---

## 3. 记忆系统

| 问题 | 来源 |
|:---|:---|
| Memory 怎么做？怎么插入上下文？ | [美团Q28](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q28) / [源码Q10](../doc-poweragent/Agent 开发面试源码 Q&A.md#q10) |
| Mem0 记忆系统实现细节 | [美团Q29](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q29) |
| 长短期记忆区分与保存 | [美团Q30](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q30) |
| 短期记忆 Redis Session vs DB | [美团Q28](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q28) |
| 长期记忆摘要为什么不记答案？ | [美团Q28](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q28) |
| 长期记忆 Mem0 vs LLM 摘要选型 | [模块05Q6](../doc-poweragent/模块源码分析 05-08：插件_工具 %2B MCP %2B 记忆 %2B 多模态 (1).md#q6) |
| 短期长期记忆怎么协同？ | [模块05Q7](../doc-poweragent/模块源码分析 05-08：插件_工具 %2B MCP %2B 记忆 %2B 多模态 (1).md#q7) |
| 会话变量跨对话持久化 | [美团Q30](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q30) |
| Agent session 和 state 管理 | [源码Q5](../doc-poweragent/Agent 开发面试源码 Q&A.md#q5) |
| 会话和记忆管理 | [WorkflowQ5](../doc-poweragent/Agent Workflow 面试 Q&A.md#q5) |
| 长期记忆和会话记忆是两套系统（缺点） | [核心Q33](../doc-poweragent/Agent 核心机制面试 Q&A.md#q33) |

---

## 4. RAG 检索与召回

| 问题 | 来源 |
|:---|:---|
| RAG 整体架构和完整构建流程（12 步） | [核心Q20](../doc-poweragent/Agent 核心机制面试 Q&A.md#q20) / [美团Q20](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q20) / [WorkflowQ12](../doc-poweragent/Agent Workflow 面试 Q&A.md#q12) |
| 检索引擎基于 Milvus 还是 ES？ | [模块02Q1](../doc-poweragent/模块源码分析 02：知识库_搜索.md#q1) |
| 召回策略：稠密/稀疏/混合 | [核心Q23](../doc-poweragent/Agent 核心机制面试 Q&A.md#q23) / [美团Q23](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q23) |
| RRF 融合为什么比直接加权好？ | [模块02Q2](../doc-poweragent/模块源码分析 02：知识库_搜索.md#q2) |
| 为什么要单独做 Rerank？ | [核心Q24](../doc-poweragent/Agent 核心机制面试 Q&A.md#q24) / [美团Q24](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q24) |
| 检索空结果怎么处理？ | [模块02Q3](../doc-poweragent/模块源码分析 02：知识库_搜索.md#q3) |
| RAG 文档隔离？关联文档和术语？ | [核心Q26](../doc-poweragent/Agent 核心机制面试 Q&A.md#q26) / [美团Q26](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q26) |
| Code-RAG 怎么设计？ | [核心Q27](../doc-poweragent/Agent 核心机制面试 Q&A.md#q27) / [美团Q27](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q27) |
| Agentic RAG / 主动式 RAG | [核心Q21](../doc-poweragent/Agent 核心机制面试 Q&A.md#q21) / [美团Q21](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q21) |
| RRF k 值固定（缺点） | [核心Q33](../doc-poweragent/Agent 核心机制面试 Q&A.md#q33) |

---

## 5. 意图识别与 Query Rewrite

| 问题 | 来源 |
|:---|:---|
| 意图识别到底是什么？ | [核心Q28](../doc-poweragent/Agent 核心机制面试 Q&A.md#q28) |
| 意图识别和 Rewrite 怎么实现？ | [核心Q22](../doc-poweragent/Agent 核心机制面试 Q&A.md#q22) / [美团Q22](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q22) |
| 意图识别和 Rewrite 的关系 | [核心Q29](../doc-poweragent/Agent 核心机制面试 Q&A.md#q29) |
| 多轮对话指代消解 | [核心Q22](../doc-poweragent/Agent 核心机制面试 Q&A.md#q22) |
| 混合路由：确定性走代码，开放走语义 | [核心Q22](../doc-poweragent/Agent 核心机制面试 Q&A.md#q22) |

---

## 6. RAG 文档处理与切分

| 问题 | 来源 |
|:---|:---|
| 支持的文档格式？处理策略？ | [OCRQ1](../doc-poweragent/RAG 文档处理与 OCR 面试 Q&A 0728.md#q1) |
| OCR 怎么用的？原理？ | [OCRQ2](../doc-poweragent/RAG 文档处理与 OCR 面试 Q&A 0728.md#q2) |
| OCR 错字和截断处理 | [OCRQ3](../doc-poweragent/RAG 文档处理与 OCR 面试 Q&A 0728.md#q3) |
| 什么时候用 LLM，什么时候用脚本？ | [OCRQ4](../doc-poweragent/RAG 文档处理与 OCR 面试 Q&A 0728.md#q4) |
| 文档切片策略？召回不到数据？ | [核心Q25](../doc-poweragent/Agent 核心机制面试 Q&A.md#q25) / [美团Q25](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q25) |
| 除了父子文档还有什么分块方式？ | [OCRQ7](../doc-poweragent/RAG 文档处理与 OCR 面试 Q&A 0728.md#q7) |

---

## 7. 工具 / Skill / MCP

| 问题 | 来源 |
|:---|:---|
| Skill 机制做什么？ | [美团Q6](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q6) / [美团Q7](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q7) |
| MCP 和 Skill 区别？和 CLI 区别？ | [美团Q8](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q8) |
| 工具链能否 Skill 化？ | [美团Q9](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q9) |
| 插件和 Dify Tool 区别？ | [模块05Q1](../doc-poweragent/模块源码分析 05-08：插件_工具 %2B MCP %2B 记忆 %2B 多模态 (1).md#q1) |
| 五种工具类型执行链路 | [模块05Q2](../doc-poweragent/模块源码分析 05-08：插件_工具 %2B MCP %2B 记忆 %2B 多模态 (1).md#q2) |
| 工具 Schema 自动生成 | [模块05Q3](../doc-poweragent/模块源码分析 05-08：插件_工具 %2B MCP %2B 记忆 %2B 多模态 (1).md#q3) |
| MCP vs Plugin 选型 | [模块05Q4](../doc-poweragent/模块源码分析 05-08：插件_工具 %2B MCP %2B 记忆 %2B 多模态 (1).md#q4) |
| MCP 工具注册到 Agent | [模块05Q5](../doc-poweragent/模块源码分析 05-08：插件_工具 %2B MCP %2B 记忆 %2B 多模态 (1).md#q5) |
| Agent 工具注册和 LLM 感知 | [源码Q3](../doc-poweragent/Agent 开发面试源码 Q&A.md#q3) |
| 工具调用全链路 | [源码Q4](../doc-poweragent/Agent 开发面试源码 Q&A.md#q4) |
| Agent 怎么决定调哪个工具？ | [模块02Q4](../doc-poweragent/模块源码分析 02：知识库_搜索.md#q4) |
| 工具系统如何设计？ | [WorkflowQ4](../doc-poweragent/Agent Workflow 面试 Q&A.md#q4) |
| 加新工具完整步骤 | [源码Q13](../doc-poweragent/Agent 开发面试源码 Q&A.md#q13) |
| 工具调用是同步 HTTP 阻塞（缺点） | [核心Q33](../doc-poweragent/Agent 核心机制面试 Q&A.md#q33) |

---

## 8. WorkFlow / DAG 引擎

| 问题 | 来源 |
|:---|:---|
| DAG 引擎介绍：入口识别→JSON解析→双模式→递归 | [核心Q30](../doc-poweragent/Agent 核心机制面试 Q&A.md#q30) / [WorkflowQ1](../doc-poweragent/Agent Workflow 面试 Q&A.md#q1) |
| @NodeType+ModuleFactory 注册机制 | [核心Q30](../doc-poweragent/Agent 核心机制面试 Q&A.md#q30) |
| 同步递归 vs 线程池并发 | [核心Q30](../doc-poweragent/Agent 核心机制面试 Q&A.md#q30) |
| 节点失败降级：重试+moduleDefaultOutput | [核心Q30](../doc-poweragent/Agent 核心机制面试 Q&A.md#q30) |
| 执行器单例化：Bean vs 节点实例 | [核心Q30](../doc-poweragent/Agent 核心机制面试 Q&A.md#q30) |
| FlowContext 字段 | [核心Q30](../doc-poweragent/Agent 核心机制面试 Q&A.md#q30) |
| AgentFlow DAG vs Dify | [核心Q31](../doc-poweragent/Agent 核心机制面试 Q&A.md#q31) |
| vs LangChain 核心区别 | [WorkflowQ3](../doc-poweragent/Agent Workflow 面试 Q&A.md#q3) / [WorkflowQ9](../doc-poweragent/Agent Workflow 面试 Q&A.md#q9) |
| agentflow-server 是 workflow 编排吗？ | [WorkflowQ9](../doc-poweragent/Agent Workflow 面试 Q&A.md#q9) |
| AgentFlow 功能模块+技术要点 | [WorkflowQ10](../doc-poweragent/Agent Workflow 面试 Q&A.md#q10) |
| WorkFlow 内存执行挂了全丢（缺点） | [核心Q33](../doc-poweragent/Agent 核心机制面试 Q&A.md#q33) / [OCRQ5](../doc-poweragent/RAG 文档处理与 OCR 面试 Q&A 0728.md#q5_6) |
| 意图路由太粗（缺点） | [核心Q33](../doc-poweragent/Agent 核心机制面试 Q&A.md#q33) |
| 没有模型熔断降级（缺点） | [核心Q33](../doc-poweragent/Agent 核心机制面试 Q&A.md#q33) |

---

## 9. DataFlow 离线引擎

| 问题 | 来源 |
|:---|:---|
| DataFlow 双层状态机、DB 驱动调度 | [核心Q32](../doc-poweragent/Agent 核心机制面试 Q&A.md#q32) |
| DataFlow 与 AgentFlow 联动 | [WorkflowQ11](../doc-poweragent/Agent Workflow 面试 Q&A.md#q11) |
| DataFlow 30s 轮询间隙（缺点） | [核心Q33](../doc-poweragent/Agent 核心机制面试 Q&A.md#q33) |
| 为什么不用 Kafka？ | [核心Q32](../doc-poweragent/Agent 核心机制面试 Q&A.md#q32) |

---

## 10. 评测 / 可观测性 / Trace

| 问题 | 来源 |
|:---|:---|
| 评测方法是什么？ | [模块09Q35](../doc-poweragent/模块源码分析 09-10：Trace_日志 + 评测体系 + Sentinel 熔断 + 部署性能并发 (1).md#q35) |
| Trace 结构，日志怎么结构化？ | [模块09Q33](../doc-poweragent/模块源码分析 09-10：Trace_日志 + 评测体系 + Sentinel 熔断 + 部署性能并发 (1).md#q33) |
| 跨 Session 日志系统 | [模块09Q34](../doc-poweragent/模块源码分析 09-10：Trace_日志 + 评测体系 + Sentinel 熔断 + 部署性能并发 (1).md#q34) |
| 幻觉怎么减少？ | [模块09Q36](../doc-poweragent/模块源码分析 09-10：Trace_日志 + 评测体系 + Sentinel 熔断 + 部署性能并发 (1).md#q36) |
| 可观测性怎么实现？ | [WorkflowQ6](../doc-poweragent/Agent Workflow 面试 Q&A.md#q6) |
| Agent 快照版本管理 | [模块02Q5](../doc-poweragent/模块源码分析 02：知识库_搜索.md#q5) |

---

## 11. 部署 / 性能 / 并发

| 问题 | 来源 |
|:---|:---|
| 部署性能与并发控制？ | [模块09Q37](../doc-poweragent/模块源码分析 09-10：Trace_日志 + 评测体系 + Sentinel 熔断 + 部署性能并发 (1).md#q37) |
| 项目难点？ | [模块09Q38](../doc-poweragent/模块源码分析 09-10：Trace_日志 + 评测体系 + Sentinel 熔断 + 部署性能并发 (1).md#q38) |
| SSE reqId 频道 vs WebSocket？ | [模块02Q7](../doc-poweragent/模块源码分析 02：知识库_搜索.md#q7) |
| LLM 模型路由怎么做？ | [模块02Q8](../doc-poweragent/模块源码分析 02：知识库_搜索.md#q8) |
| streaming 端到端怎么实现？ | [源码Q7](../doc-poweragent/Agent 开发面试源码 Q&A.md#q7) |
| Java↔Python 跨语言调用开销（缺点） | [核心Q33](../doc-poweragent/Agent 核心机制面试 Q&A.md#q33) |

---

## 12. 系统对比

| 问题 | 来源 |
|:---|:---|
| AgentFlow DAG vs Dify | [核心Q31](../doc-poweragent/Agent 核心机制面试 Q&A.md#q31) |
| vs LangChain | [WorkflowQ3](../doc-poweragent/Agent Workflow 面试 Q&A.md#q3) / [WorkflowQ9](../doc-poweragent/Agent Workflow 面试 Q&A.md#q9) |
| harness 对比 | [美团Q3](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q3) |
| 为什么不用 Claude Code | [美团Q5](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q5) |
| AgentFlow 核心缺点与解决方案（8 个） | [核心Q33](../doc-poweragent/Agent 核心机制面试 Q&A.md#q33) |

---

## 13. 面试话术 / 简历

| 问题 | 来源 |
|:---|:---|
| 自我介绍，重点介绍 Agent 项目 | [美团Q1](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q1) |
| Agent 项目核心架构 | [美团Q2](../doc-poweragent/美团%20Agent%20%20Q%26A.md#q2) |
| 一条请求完整执行链路 | [WorkflowQ2](../doc-poweragent/Agent Workflow 面试 Q&A.md#q2) |
| 上传文档到知识库全链路（12 步） | [WorkflowQ12](../doc-poweragent/Agent Workflow 面试 Q&A.md#q12) |
| 文件上传和多模态实现 | [源码Q12](../doc-poweragent/Agent 开发面试源码 Q&A.md#q12) |

---

## 说明

- 源文件（doc-poweragent/ 下）已给每个问题标题加了 `<a id="qN">` 锚点
- 链接格式 `[来源题号](../doc-poweragent/文件名.md#qN)`，Ctrl+单击跳转
- 美团文件名含空格和 `&`，已做 URL 编码（%20、%26）
- OCR 文件两个 Q5：`#q5`=主/子Agent交互，`#q5_6`=WorkFlow内存执行
- 核心 Q33 是 8 个缺点合在一题，跳转后用 Ctrl+F 搜"问题一/问题二..."定位
