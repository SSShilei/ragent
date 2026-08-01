# AF\-RAG\-Server 基础架构文档

## **项目概述**

**项目名称**: af\-rag\-server **技术栈**: Python 3\.11 \+ Flask \+ FastAPI \+ Google ADK **描述**: AI RAG \(检索增强生成\) 服务平台——文档解析、向量检索、多智能体系统

## **整体架构**

af\-rag\-server 是一个双应用架构：

```Plain Text
af-rag-server/
├── app.py                    —— Flask 主应用 (RAG 服务, 端口 50000)
├── agent/app.py              —— FastAPI Agent 应用 (智能体系统, 端口 10001)
├── agent/                    —— Agent 智能体模块
├── src/                      —— RAG 核心源码
├── encoders/                 —— Tiktoken 编码器缓存
├── pyproject.toml            —— Poetry 依赖管理
├── requirements.txt          —— Pip 依赖
├── Dockerfile                —— 容器化
├── supervisord.conf          —— 进程管理
├── start.sh                  —— 启动脚本
└── gunicorn_conf.py          —— Gunicorn 配置
```

## **Flask RAG 应用 \(app\.py\)**

### **3\.1 应用架构**

```Plain Text
Flask App (端口 50000)
├── RAG 算法服务         —— /rag_algorithm/*
├── 代码执行服务          —— /rag_algorithm/code_runner
├── 自动 Agent           —— /auto_agent/execute
├── GraphRAG             —— /graphrag/*
├── 文档解析             —— /parse/*
├── 网页爬取             —— /parse/crawl_*, /parse/parse_*
├── 飞书文档解析          —— /parse/feishu/*
├── 包管理               —— /user/<user_id>/package/*
├── 适配器               —— /api/adapters/*
├── Redis 缓存           —— /redis_cache
├── HTML 解析            —— /parse_html
├── 记忆向量化            —— /vector/memory/*
├── RAGAS 评估           —— /rag_algorithm/ragas_evaluate
├── FastAPI 转发         —— 代理到 FastAPI Agent 应用
├── 视频解析             —— /rag_algorithm/video*
├── 国际化               —— 请求头 X-LANG 控制
└── 监控指标             —— /metrics (Prometheus)
```

### **3\.2 src/ 源码结构**

```Plain Text
src/
├── agent/                   —— Agent 相关
│   ├── auto_agent/          —— 自动 Agent (模型/执行器/错误)
│   ├── model/               —— Agent 模型定义
│   ├── prompt/              —— Agent Prompt
│   └── tools/               —— Agent 工具
├── apps/                    —— 应用模块
│   ├── adapters/            —— 适配器
│   ├── graphrag/            —— GraphRAG (知识图谱 RAG)
│   │   └── storage/         —— 图存储
│   ├── package/             —— 用户代码包管理
│   └── fastapi_transfer.py  —— FastAPI 转发代理
├── cache/                   —— Redis 缓存管理
├── common/                  —— 公共模块
├── component/               —— RAG 核心组件
│   ├── chunk/               —— 文档分块
│   │   ├── excel_transform/ —— Excel 转换
│   │   ├── ocr_res_transform/—— OCR 结果转换
│   │   └── tabular_transform/—— 表格转换
│   ├── chunk_split/         —— 分块策略
│   ├── parser/              —— 文档解析器
│   ├── postprocess/         —— 后处理
│   ├── pycode/              —— Python 代码执行
│   ├── rerank/              —— 重排序
│   ├── retrieve/            —— 检索
│   └── server_component/    —— 服务组件
├── eval/                    —— RAGAS 评估
├── feishu_parse/            —— 飞书文档解析
├── html_parser/             —— HTML 解析
├── i18n/                    —— 国际化 (zh_CN/en_US)
├── maip/                    —— MAIP 平台集成
├── memory/                  —— 记忆向量化 (Mem0)
│   ├── memory_vector.py     —— 向量 CRUD
│   └── scheduler.py         —— 记忆调度
├── models/                  —— SQLAlchemy 数据模型
├── ops/                     —— 运维监控
├── rag_algorithm/           —— RAG 算法实现
│   ├── handlers.py          —— 核心 RAG 处理器
│   └── ...
├── server_api_tools/        —— 服务端工具
│   ├── s3helper.py          —— S3 文件操作
│   └── vector_collections/  —— 向量集合管理
├── tools/                   —— 工具集
│   ├── pinpoint_context.py  —— Pinpoint 链路追踪
│   └── tabular_serialize/   —— 表格序列化
├── web_parse/               —— 网页解析
│   └── parse_manager.py     —— 爬虫管理 (Jina/FireCrawl)
└── web_scraper_parser/      —— 网页抓取解析
```

## **FastAPI Agent 应用 \(agent/app\.py\)**

### **4\.1 应用架构**

```Plain Text
FastAPI App (端口 10001)
├── /ping                    —— 健康检查
├── /api/auto-agent/run      —— 自动 Agent 执行 (SSE 流式)
├── /mcp/*                   —— MCP 客户端路由
└── /multi-agent/*           —— 多智能体路由
```

### **4\.2 智能体系统 \(基于 Google ADK\)**

```Plain Text
IntentAgent (意图判定智能体)
├── MetadataAgent       —— 元数据提取
├── StrategyAgent       —— 策略分析与生成
│   ├── StrategyCreateAgent —— 策略创建
│   ├── AnalysisAgent      —— 数据分析
│   ├── CodeAgent          —— 代码生成
│   └── DecisionAgent      —— 决策分析
├── StrategyViewAgent   —— 策略查看
├── DirectAnalysisAgent —— 直接数据分析
│   ├── ParamExtractAgent     —— 参数提取
│   └── ExecuteDataflowAgent  —— 执行 DataFlow
└── RunDataAgent        —— 数据运行
```

### **4\.3 Agent 核心流程**

```Plain Text
用户请求 → AutoAgentExecutor
    ↓
IntentAgent (意图识别)
    ├── 明确分支 (Explicit Branch): 策略发布检查、策略使用检查、MCP 任务创建
    ├── 工具调用判断意图
    └── Sub-agent 路由
        ├→ MetadataAgent (元数据)
        ├→ StrategyAgent (策略生成)
        ├→ StrategyViewAgent (策略查看)
        ├→ DirectAnalysisAgent (直接分析)
        └→ RunDataAgent (数据运行)
    ↓
SSE Streaming Response (流式返回)
```

### **4\.4 核心组件**

```Plain Text
agent/
├── agents/                    —— 智能体定义
│   ├── intent_agent/          —— 意图判定
│   │   ├── agent.py           —— Agent 定义
│   │   ├── prompt.py          —— Prompt 模板
│   │   ├── explicit_branch.py —— 明确分支处理
│   │   └── utils.py           —— 工具函数
│   ├── metadata_agent/        —— 元数据 Agent
│   ├── strategy_agent/        —— 策略 Agent
│   │   ├── strategy_create_agent/  —— 创建
│   │   ├── analysis_agent/        —— 分析
│   │   ├── code_agent/            —— 编码
│   │   └── decision_agent/        —— 决策
│   ├── strategy_view_agent/   —— 策略查看
│   ├── direct_analysis_agent/ —— 直接分析
│   │   ├── param_extract_agent/   —— 参数提取
│   │   └── execute_dataflow_agent/—— 执行 DataFlow
│   └── run_data_agent/        —— 数据运行
├── auto_agent/                —— 自动 Agent 框架
│   ├── executor.py            —— Agent 执行器 (核心)
│   ├── error.py               —— 错误处理
│   └── model.py               —— 请求/响应模型
├── apps/                      —— 应用
│   ├── langfuse_client.py     —— Langfuse 追踪
│   ├── mcp_client.py          —— MCP 客户端
│   ├── multi_agent/           —— 多智能体 API
│   └── pinpoint_agent.py      —— Pinpoint 监控
└── services/                  —— 服务
    ├── google_adk/            —— Google ADK 封装
    └── schedule/              —— 调度器
```

## **技术栈详情**

### **5\.1 Flask RAG 应用依赖**

### **5\.2 FastAPI Agent 应用依赖**

## **关键设计特点**

### **6\.1 双应用架构**

```Plain Text
┌─────────────────────┐
                    │   Nginx / 网关       │
                    └──────┬──────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
     ┌────────────┐ ┌──────────┐ ┌──────────┐
     │ Flask App  │ │FastAPI   │ │ External │
     │ Port 50000 │ │Port 10001│ │ Services │
     │ (RAG Svc)  │ │(Agent)   │ │          │
     └──────┬─────┘ └────┬─────┘ └──────────┘
            │            │
            └────────────┘
          共享 Redis / MySQL / ES / Milvus
```

### **6\.2 多智能体编排 \(Google ADK\)**

- **IntentAgent**: 根 Agent，负责意图识别和路由分发

- **Sub\-agents**: 各子 Agent 独立处理特定领域任务

- **回调机制**: `before_agent_callback` / `before_model_callback` 用于预处理

- **流式响应**: 通过 SSE \(Server\-Sent Events\) 实时推送执行结果

- **心跳机制**: 每 10 秒发送心跳防止连接超时

### **6\.3 RAG 处理流水线**

```Plain Text
文档上传 → Parser (解析) → Splitter (分块)
    ↓
Keywords (关键词提取) → Summary (摘要)
    ↓
Embedding → Milvus/ES (向量存储)
    ↓
Retrieve (检索) → Rerank (重排序) → LLM 生成
```

### **6\.4 国际化**

- 通过请求头 `X-LANG` 控制语言 \(en\_US / zh\_CN\)

- `I18nManager` 管理多语言配置

- `PlaceholderReplacer` 处理占位符替换

### **6\.5 监控与追踪**

- **Pinpoint**: APM 分布式链路追踪 \(Java ↔ Python 全链路\)

- **Prometheus**: `/metrics` 端点暴露指标

- **Langfuse**: LLM 调用追踪与可观测性

## **容器化**

```Dockerfile
# 基于 supervisor 管理双进程
# Flask: gunicorn + gevent worker
# FastAPI: uvicorn worker
```

Supervisor 配置管理两个应用进程：

- `flask_app`: Gunicorn 启动 Flask \(端口 50000\)

- `fastapi_app`: Uvicorn 启动 FastAPI \(端口 10001\)



