# AgentFlow\-Server 基础架构文档

# **AgentFlow\-Server 基础架构文档**

## **项目概述**

**项目名称**: agent\-flow \(agentflow\-server\) **组织**: com\.msxf\.pai **版本**: 1\.0\.0 **技术栈**: Java 11 \+ Spring Boot 2\.3\.12\.RELEASE \+ Maven 多模块 **描述**: AgentFlow 服务——AI Agent 工作流编排与管理平台

## **模块架构**

agentflow\-server 采用 Maven 多模块架构，分为四层：

```Plain Text
agent-flow (pom) ─── pom.xml (父POM, 版本/依赖统一管理)
├── agent-flow-server      —— 服务启动与 Web 层
├── agent-flow-common      —— 公共组件层
├── agent-flow-api         —— API 接口定义层 (SPI)
│   ├── agent-flow-agents-api
│   ├── agent-flow-datasets-api
│   ├── agent-flow-densevector-api
│   ├── agent-flow-knowledges-api
│   ├── agent-flow-plugins-api
│   ├── agent-flow-prompt-api
│   └── agent-flow-workflow-api
└── agent-flow-app         —— 应用实现层
    ├── agent-flow-agents
    ├── agent-flow-datasets
    ├── agent-flow-densevector
    ├── agent-flow-knowledges
    ├── agent-flow-mq
    ├── agent-flow-plugin
    ├── agent-flow-prompt
    ├── agent-flow-serving
    ├── agent-flow-user-guidance
    └── agent-flow-workflow
```

### **2\.1 agent\-flow\-server \(服务启动层\)**

**入口类**: `com.msxf.pai.agent.server.AgentFlowServiceApplication`

主要职责：

- 启动 Spring Boot 应用

- 配置 MyBatis Mapper 扫描 \(`com.msxf.pai.*.*.domain.repository`\)

- 配置组件扫描 \(`com.msxf.pai`\)

- 启用 Feign 客户端

- 启用异步、定时任务、重试、AOP

核心包结构：

```Plain Text
agent-flow-server/src/main/java/com/msxf/pai/agent/server/
├── AgentFlowServiceApplication.java   —— 启动入口
├── biz/                               —— 业务逻辑
│   ├── agenteval/                     —— Agent 评估
│   ├── approve/                       —— 审批流程
│   └── dataflow/                      —— 数据流业务
├── builtIn/tool/                      —— 内置工具
├── config/                            —— 配置类
├── controller/                        —— REST 控制器 (60+)
│   ├── autoagent/                     —— 自动 Agent
│   ├── memory/                        —— 记忆管理
│   ├── openapi/                       —— 开放 API
│   ├── term/                          —— 术语管理
│   └── v2/                            —— V2 版本 API
├── filter/                            —— 过滤器
├── handler/                           —— 处理器
├── interceptors/                      —— 拦截器
├── listener/                          —— 事件监听器
├── openapi/                           —— OpenAPI 定义
└── util/                              —— 工具类
```

核心 Controller 列表：

- **Agent**: AgentController, AgentConfigController, AgentShareController, AgentSnapshotController, AgentStatisticController, AgentUserPermissionController, AgentUserPersonaController

- **Chat**: ChatController, ChatItemController, ChatItemDetailController

- **Knowledge**: KnowledgeController, KnowledgeDataController, KnowledgeDatasetController, KnowledgeFlowController, KnowledgeGraphController, KnowledgeMetadataController, KnowledgeTagController, KnowledgeTaskController

- **Workflow**: ModuleController, PluginController, PluginShareController, PluginStoreController

- **Prompt**: PromptController, PromptDetailVersionController

- **Model**: ModelController

- **Eval**: EvalDatasetController, EvalPresetDatasetController, EvalTemplateController, MultiRoundEvalDatasetController

- **Share**: ShareCenterController, ShareCenterUserRankController, AgentShareConfigController

- **Approve**: ApproveApiController, ApproveCenterController, ApproveConfigController

- **Other**: ScheduleTaskController, McpServerController, TemplateController, PermissionController, PlanController, IndexController, HomePageController

### **2\.2 agent\-flow\-common \(公共组件层\)**

核心包结构：

```Plain Text
agent-flow-common/src/main/java/com/msxf/pai/agent/common/
├── alert/                  —— 告警
├── config/                 —— 公共配置
├── constants/              —— 常量
├── content/                —— 内容管理
├── document/               —— 文档处理 (ID 生成等)
├── dto/                    —— 通用 DTO
├── entity/                 —— 通用实体 (常量/枚举/DTO)
├── enums/                  —— 通用枚举
├── exception/              —— 异常定义
├── job/                    —— 定时任务基础
├── monitor/                —— 监控 (注解/AOP/DTO/处理器)
├── mybatis/                —— MyBatis 注解
├── po/                     —— 基础 PO
├── reader/                 —— 读取器
├── redis/                  —— Redis 工具
├── service/                —— 公共服务
├── thread/                 —— 线程工具
├── tool/                   —— 工具类
├── utils/                  —— 通用工具
└── vo/                     —— 通用 VO
```

### **2\.3 agent\-flow\-api \(接口定义层\)**

每个 API 模块定义领域接口 \(SPI\)，不包含实现：

### **2\.4 agent\-flow\-app \(应用实现层\)**

每个 app 模块采用 **DDD 分层架构**：

```Plain Text
模块/
├── domain/           —— 领域层
│   ├── enums/        —— 领域枚举
│   ├── po/           —— 持久化对象
│   └── repository/   —— 仓储 (Mapper)
└── application/      —— 应用层
    ├── client/       —— 对外客户端
    │   ├── dto/      —— 数据传输对象
    │   ├── service/  —— 客户端服务
    │   └── vo/       —— 视图对象
    ├── convert/      —— 对象转换 (MapStruct)
    ├── job/          —— 定时任务
    ├── event/        —— 事件
    ├── listener/     —— 监听器
    ├── runner/       —— 启动运行器
    ├── monitor/      —— 监控
    └── service/      —— 应用服务
        ├── autoagent/—— 自动 Agent 服务
        ├── handler/  —— 处理器
        ├── memory/   —— 记忆服务
        └── support/  —— 支撑服务
```

核心模块说明：

## **技术栈详情**

## **架构模式**

### **4\.1 分层架构 \(DDD\)**

```Plain Text
┌──────────────────────────────────┐
│    Controller (Web 层)           │  ← 请求处理、参数校验
├──────────────────────────────────┤
│    Application Service (应用层)   │  ← 业务流程编排、事务管理
├──────────────────────────────────┤
│    Domain (领域层)                │  ← PO/枚举/Repository
├──────────────────────────────────┤
│    Infra (基础设施层)             │  ← MyBatis/Redis/ES
└──────────────────────────────────┘
```

### **4\.2 API/SPI 分离模式**

- **agent\-flow\-api**: 定义接口 \(SPI\)，供外部模块依赖

- **agent\-flow\-app**: 提供实现，内部可替换

### **4\.3 关键设计**

- **Mapper 扫描**: 统一扫描 `domain.repository` 包下的 Mapper 接口

- **组件扫描**: 跨包扫描 `com.msxf.pai` 及子模块

- **Feign 客户端**: 用于跨服务调用 \(如 DataOps\)

- **AOP 切面**: 操作日志、监控埋点

- **MapStruct**: Entity ↔ DTO ↔ VO 转换

- **分页**: PageHelper 统一分页

## **核心业务域**

- **Agent 管理**: Agent 创建、配置、共享、快照、版本管理

- **Chat 对话**: 多轮对话、对话历史、上下文管理

- **Knowledge 知识库**: 知识数据集、知识流、知识图谱、知识标签

- **Workflow 工作流**: 模块编排、节点连接、工作流引擎

- **Plugin 插件**: 插件市场、插件共享、插件安装

- **Prompt 管理**: Prompt 模板、版本管理

- **Model 模型**: 模型配置、模型服务

- **Memory 记忆**: 对话记忆、用户记忆

- **Auto Agent**: 自动 Agent 策略执行

- **Evaluation**: Agent 评估、数据集评估

- **Approval**: 审批流程

- **Share**: 资源共享与权限

## **服务启动**

```Java
@SpringBootApplication
@MapperScan("com.msxf.pai.*.*.domain.repository")
@ComponentScan("com.msxf.pai")
@EnableFeignClients
@EnableScheduling
@EnableAsync
@EnableRetry
@EnableAspectJAutoProxy
public class AgentFlowServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentFlowServiceApplication.class, args);
    }
}
```

## **配置管理**

使用 Maven profile 管理多环境：

- 通过 `platform-common-v2` / `platform-support-*` 等公司内部平台包进行统一配置

- 支持 Nacos 配置中心

- 数据库密码使用 Jasypt 加密

