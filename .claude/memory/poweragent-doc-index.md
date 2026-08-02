# PowerAgent 文档索引

> `/home/shilei/IdeaProjects/ragent/doc-poweragent/` 下共 16 个文档（已去重）。
> 使用方式：搜索关键词定位到对应文件，再根据行号精确定位。

---

## 快速导航（按主题分类）

### 项目概览 & 简历
| 文档 | 行数 | 核心内容 |
|:---|:---|:---|
| `简历亮点提炼（合并版）.md` | 403 | PA+Ragent 双项目简历话术，DAG引擎/Agent协作/RAG优化/记忆系统 STAR 写法 |
| `AF-RAG-Server 基础架构文档.md` | 268 | Python 端 Flask+FastAPI 架构全貌 |
| `AgentFlow-Server 基础架构文档.md` | 254 | Java 端 AgentFlow 架构全貌 |
| `DataFlow-Server 基础架构文档.md` | 273 | Java 端 DataFlow 离线跑批架构 |

### 源码分析（按模块）
| 文档 | 行数 | 核心内容 |
|:---|:---|:---|
| `af-rag-server 核心源码解析2.md` | **10401** | **最大文件**，Python 端完整源码逐文件分析（含 RAG管线/OCR/分块/检索/Agent/GraphRAG/Mem0/RAGAS/飞书/Pinpoint） |
| `模块源码分析 01：工作流编排引擎 (1).md` | 1123 | WorkFlowEngine 逐行分析，50+节点类型，面试20问 |
| `模块源码分析 02：知识库_搜索.md` | 2861 | 知识库检索（ES KNN+BM25+RRF+Rerank）+ Agent管理 + Chat对话 |
| `模块源码分析 05-08：插件_工具 + MCP + 记忆 + 多模态 (1).md` | 1835 | 插件网关/MCP集成/Mem0记忆/多模态，面试20问 |
| `模块源码分析 09-10：Trace_日志 + 评测体系 + Sentinel 熔断 + 部署性能并发 (1).md` | 1553 | 全链路Trace/RAGAS评测/Sentinel熔断/部署并发 |
| `DataFlow-Server 源码分析.md` | 1629 | DataFlow 离线引擎（Argo+K8s+双层状态机+存储入库） |
| `Agent表结构.sql` | 434 | mx_agentflow 库 14 张表完整 DDL |

### 基础架构
| 文档 | 行数 | 核心内容 |
|:---|:---|:---|
| `AF-RAG-Server 基础架构文档.md` | 268 | Python 端 Flask+FastAPI 双应用架构 |
| `AgentFlow-Server 基础架构文档.md` | 254 | Java 端 AgentFlow 架构 |
| `DataFlow-Server 基础架构文档.md` | 273 | Java 端 DataFlow 离线跑批架构 |

### 面试 Q&A（按主题）
| 文档 | 行数 | 核心内容 |
|:---|:---|:---|
| `美团 Agent  Q&A.md` | **4626** | **最全面**的面试 Q&A：Agent 项目(Q1-Q12)+Skill/MCP(Q1-Q7)+RAG(Q20-Q27)+Memory(Q28-Q33)+评测/幻觉/部署(Q35-Q38) |
| `Agent 核心机制面试 Q&A.md` | 2012 | Agent 循环(Q1-Q7)+RAG(Q20-Q27)+Ragent对比+意图识别(Q28-Q29)+DAG引擎(Q30-Q32)+AgentFlow缺点(Q33) |
| `Agent Workflow 面试 Q&A.md` | 1517 | Agent架构+一条请求链路+LangChain对比+工具系统+PlanReAct+12步文档入库全链路 |
| `Agent 开发面试源码 Q&A.md` | 882 | Agent 开发面试专项（源码级回答） |
| `RAG 文档处理与 OCR 面试 Q&A 0728.md` | 779 | RAG 文档处理/OCR 专项面试 |

---

## 按面试问题类型速查

### Agent 循环/ReAct/PlanReAct
- `美团 Agent  Q&A.md` Q1(line 676), Q11(line 761)
- `Agent 核心机制面试 Q&A.md` Q1(line 5)
- `Agent Workflow 面试 Q&A.md` Q7(line 250)

### Agent 死循环防御
- `美团 Agent  Q&A.md` Q12(line 862)
- `Agent 核心机制面试 Q&A.md` Q2(line 112)

### 上下文组装/超长处理/压缩
- `美团 Agent  Q&A.md` Q12-Q19 (lines 1643-2374)
- `Agent 核心机制面试 Q&A.md` Q3-Q7 (lines 187-650)

### DAG 工作流编排引擎
- `模块源码分析 01：工作流编排引擎 (1).md` 逐行分析
- `Agent 核心机制面试 Q&A.md` Q30(line 1642)
- `AgentFlow模块深度分析：工作流编排引擎 & Agent 管理.md`

### 工具调用网关/MCP
- `模块源码分析 05-08 (1).md` 插件/工具(line 11)+MCP(line 690)
- `美团 Agent  Q&A.md` Q8(line 518)
- `Agent Workflow 面试 Q&A.md` Q4(line 144)

### 知识库检索/混合召回/RRF/Rerank
- `模块源码分析 02：知识库_搜索.md` line 422-699
- `美团 Agent  Q&A.md` Q23(line 2762), Q24(line 2841)
- `Agent 核心机制面试 Q&A.md` Q23(line 850), Q24(line 939)

### RAG 文档切分/解析
- `af-rag-server 核心源码解析2.md` 文档解析(line 1439)+分块(line 1799)
- `美团 Agent  Q&A.md` Q25(line 2893)
- `RAG 文档处理与 OCR 面试 Q&A 0728.md`

### 记忆系统（短期/长期/Mem0）
- `美团 Agent  Q&A.md` Q28-Q32 (lines 3148-3659)
- `模块源码分析 05-08 (1).md` 记忆(line 1014)
- `af-rag-server 核心源码解析2.md` Mem0(line 6479)

### 评测体系（RAGAS/召回率/准确率）
- `模块源码分析 09-10 (1).md` 评测(line 541)
- `美团 Agent  Q&A.md` Q35(line 3885)
- `af-rag-server 核心源码解析2.md` RAGAS(line 7796)

### 意图识别/Query Rewrite
- `Agent 核心机制面试 Q&A.md` Q22(line 765), Q28(line 1427), Q29(line 1556)
- `美团 Agent  Q&A.md` Q22(line 2575)

### AgentFlow vs Dify/LangChain
- `Agent 核心机制面试 Q&A.md` Q31(line 1824), Q33(line 1931)
- `Agent Workflow 面试 Q&A.md` Q3(line 97)

### DataFlow 离线引擎
- `DataFlow-Server 源码分析.md` 全篇
- `Agent 核心机制面试 Q&A.md` Q32(line 1858)

### 部署/性能/并发/Sentinel 熔断
- `模块源码分析 09-10 (1).md` Sentinel(line 1289)+部署性能并发(line 1418)
- `美团 Agent  Q&A.md` Q37(line 4276)

### 项目对比（PA vs Ragent vs AgentFlow）
- `Agent 核心机制面试 Q&A.md` Part C (lines 1224-1411)
- `简历亮点提炼（合并版）.md` 互补关系(line 366)

---

## 注意事项

1. `af-rag-server 核心源码解析2.md`(10401行) 是全量源码逐行注释版，查实现细节用这个
2. 模块源码分析系列中 `(1).md` 后缀的是完整版（含面试问答），覆盖了对应的精简版
3. `美团 Agent  Q&A.md` 是最全面的面试 Q&A（4626 行），覆盖 Agent + RAG + Memory + 评测 + 部署
4. 文档中 `executor.py:247-249` 这类行号引用是源码锚点，面试时可直接引用
5. `模块源码分析 02：知识库_搜索.md` 中的模块03(Agent管理)和模块04(Chat对话)与此文件合并，标题未体现
