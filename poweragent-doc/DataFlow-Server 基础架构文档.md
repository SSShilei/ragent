# DataFlow\-Server 基础架构文档

# **DataFlow\-Server 基础架构文档**

## **项目概述**

**项目名称**: dataflow\-server **组织**: com\.msxf\.pai **版本**: 1\.0\.0 **技术栈**: Java 11 \+ Spring Boot 2\.3\.12\.RELEASE \+ Spring Cloud Hoxton\.SR12 **描述**: AF 的 DataFlow 数据流处理引擎——知识数据 ETL 流水线执行与编排

## **模块架构**

dataflow\-server 是单模块 Maven 项目，内部采用 **DDD 分层架构**：

```Plain Text
dataflow-server/
├── resource/         —— 资源层 (控制器 + 任务编排)
├── application/      —— 应用服务层
├── domain/           —— 领域层
├── beans/            —— 数据对象层 (DTO/VO/Enum)
├── infra/            —— 基础设施层
└── feign/            —— 远程调用客户端
```

## **分层详解**

### **3\.1 Resource 层 \(资源/接口层\)**

```Plain Text
resource/
├── controller/
│   ├── BITableController.java          —— BI 表格接口
│   ├── BasicController.java            —— 基础接口
│   ├── BigDataController.java          —— 大数据平台接口
│   ├── CallbackController.java         —— 回调接口
│   ├── ConfigRefreshController.java    —— 配置刷新接口
│   ├── EmployResource.java             —— 雇佣/人员接口
│   ├── ScheduleController.java         —— 调度接口
│   ├── TableEditController.java        —— 表格编辑接口
│   ├── TestController.java             —— 测试接口
│   ├── TestComponentController.java    —— 组件测试接口
│   ├── DynamicConverterTest.java       —— 动态转换测试
│   └── ExcelParquetTestController.java —— Excel/Parquet 测试
└── task/
    ├── DataFlowContextTask.java        —— 数据流核心执行器
    ├── ArgoApiService.java             —— Argo 工作流 API 服务
    └── config/                         —— 任务配置
```

### **3\.2 Application 层 \(应用服务层\)**

```Plain Text
application/
├── component/
│   ├── common/                              —— 通用组件
│   ├── knowledgeflow/                       —— 知识流处理组件
│   └── insightflow/                         —— 洞察流处理组件
│       ├── FilterRepeatsModuleService       —— 去重
│       ├── PplrstModuleService              —— PPL 处理
│       ├── SensitiveWordsFilterModuleService—— 敏感词过滤
│       └── SentenceCompleteModuleService    —— 语句补全
├── executor/
│   └── DataflowExecutorService.java         —— 数据流执行服务
├── handler/                                  —— 处理器
├── converter/                                —— 数据转换器
├── excel/                                    —— Excel 处理
│   ├── AlgorithmDataListener                 —— 算法数据监听
│   ├── ClusterEmbeddingListener              —— 聚类 Embedding 监听
│   ├── ClusterMergeListener                  —— 聚类合并监听
│   ├── TagStatisticsDataListener             —— 标签统计监听
│   └── WriteBITableListener                  —— BI 表格写入监听
├── dataops/                                  —— 数据运维 (DataOps)
├── model/
│   ├── ModelQueryService.java               —— 模型查询服务
│   └── OfflineBatchModelType.java           —— 离线批处理模型类型
├── schedule/                                 —— 调度
├── message/feishu/                           —— 飞书消息
├── maip/                                     —— MAIP 平台集成
├── httpfs/                                   —— HTTP 文件系统 (HDFS)
└── util/                                     —— 工具类
    ├── BITableUtils                          —— BI 表格工具
    ├── CSVUtils                              —— CSV 工具
    ├── CyclingLabelUtils                     —— 标签循环工具
    ├── ExcelUtils                            —— Excel 工具
    ├── OfflineModelTextUtils                 —— 离线模型文本工具
    ├── MessageUtils                          —— 消息工具
    └── merge/                                —— 合并工具
```

### **3\.3 Domain 层 \(领域层\)**

```Plain Text
domain/
├── model/
│   ├── Knowledge.java                 —— 知识实体
│   ├── KnowledgeDataset.java          —— 知识数据集实体
│   ├── KnowledgeFlowContext.java      —— 知识流上下文实体
│   ├── KnowledgeFlowContextNode.java  —— 知识流上下文节点实体
│   ├── KnowledgeFlowNode.java         —— 知识流节点实体
│   ├── componenterror/                —— 组件错误模型
│   └── graph/                         —— 图模型
├── service/
│   ├── KnowledgeDatasetService        —— 数据集服务
│   ├── KnowledgeFlowContextService    —— 流上下文服务
│   ├── KnowledgeFlowContextNodeService—— 流上下文节点服务
│   ├── KnowledgeFlowNodeService       —— 流节点服务
│   ├── KnowledgesService              —— 知识服务
│   ├── es/                            —— ES 服务
│   ├── graph/                         —— 图服务
│   ├── sampling/                      —— 采样服务
│   └── componenterror/               —— 组件错误服务
└── monitor/
    └── handler/                        —— 监控处理器
```

### **3\.4 Beans 层 \(数据对象\)**

```Plain Text
beans/
├── annotation/          —— 自定义注解 (如 @KnowledgeNodeType)
├── common/constants/    —— 常量 (Algorithm/Knowledge/Language/Model/Python/RegExp)
├── dto/                 —— 数据传输对象 (70+)
│   ├── bigData/         —— 大数据平台 DTO
│   ├── model/           —— 模型 DTO (含 baidu/qwen 模型)
│   ├── azure/           —— Azure DTO
│   ├── dataops/         —— DataOps DTO
│   ├── es/              —— ES DTO
│   ├── graph/           —— 图 DTO
│   ├── knowledgeflow/   —— 知识流 DTO
│   ├── maip/            —— MAIP 平台 DTO
│   ├── node/            —— 节点 DTO
│   ├── nodedata/        —— 节点数据 DTO
│   ├── ragserver/       —— RAG 服务 DTO
│   └── third/           —— 第三方 DTO
├── enums/               —— 枚举 (含 argo 子包)
├── form/                —— 表单对象 (含 bigdata)
├── vo/                  —— 视图对象 (aiData/azure/bigData)
└── exception/           —— 异常定义
```

### **3\.5 Infra 层 \(基础设施\)**

```Plain Text
infra/
├── annotation/           —— 自定义注解
├── aop/                  —— AOP 切面
├── config/
│   ├── fastx/            —— FastX 日志配置
│   ├── feign/            —— Feign 配置
│   ├── i18n/             —— 国际化配置
│   └── monitor/          —— 监控配置
├── interceptor/          —— 拦截器
├── persistence/
│   ├── mapper/           —— MyBatis Mapper
│   ├── mybatis/          —— MyBatis 配置
│   ├── redis/            —— Redis 操作
│   └── util/             —— 持久化工具
└── third/                —— 第三方服务封装
```

### **3\.6 Feign 层 \(远程调用\)**

```Plain Text
feign/
├── constants/            —— Feign 常量
├── dataops/              —— DataOps 服务调用
└── maip/                 —— MAIP 平台调用
```

## **核心技术栈**

## **启动入口**

```Java
@SpringBootApplication
@MapperScan("com.msxf.pai.dataflow.infra.persistence.mapper")
@ComponentScan("com.msxf.pai")
@EnableAsync
@EnableFeignClients
public class DataflowApplication implements ApplicationRunner {

    public static void main(String[] args) {
        SpringApplication.run(DataflowApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        // Argo 环境判断
        if (isArgo) {
            // 执行 dataflow 任务或分段调度
            knowledgeFlowContextTask.whileGetNodes(id);
            // 或 knowledgeFlowContextTask.stageSchedulingExecute(...)
        }
    }
}
```

## **执行模式**

### **6\.1 正常模式**

作为 Spring Boot Web 服务运行，通过 Controller 提供 REST API。

### **6\.2 Argo 编排模式**

当环境变量 `isArgo=true` 时：

1. 通过命令行参数 `dataflowTaskId` 和 `contextNodeId` 接收任务

2. 执行数据流处理 \(DataFlowContextTask\)

3. 支持两种调度方式：

    - **全量调度**: `whileGetNodes(id)` —— 逐个节点执行

    - **分段调度**: `stageSchedulingExecute(dataflowTaskId, contextNodeId)` —— 指定上下文节点执行

4. 完成后调用 Argo API 终止任务并退出进程 \(`System.exit(0)`\)

## **多环境配置**

```Plain Text
profiles/
├── local/      —— 本地开发 (默认激活)
├── mx-uat/     —— MX UAT 环境
├── zkj-uat/    —— ZKJ UAT 环境
├── zkj-prod/   —— ZKJ 生产环境
└── cloud/      —— 云环境 (含 Nacos/Sentinel/Netty 等)
```

## **数据流执行流程**

```Plain Text
用户请求 → Controller → DataFlowContextTask
    ↓
加载 KnowledgeFlowContext (数据流上下文)
    ↓
加载 KnowledgeFlowContextNode (上下文节点)
    ↓
加载 KnowledgeFlowNode (流节点定义)
    ↓
DataflowExecutorService 执行
    ↓
DataflowComponentFactory 创建组件实例
    ↓
各类 ModuleService 执行处理逻辑
    ├── KnowledgeFlow 组件 (知识处理)
    ├── InsightFlow 组件 (洞察分析)
    └── Common 组件 (通用处理)
    ↓
结果写回 / 数据输出
```

## **核心业务域**

- **Knowledge \(知识\)**: 知识数据的核心实体

- **KnowledgeDataset \(数据集\)**: 知识数据集管理

- **KnowledgeFlowContext \(流上下文\)**: 数据流执行上下文

- **KnowledgeFlowNode \(流节点\)**: 数据流节点定义

- **KnowledgeFlowContextNode \(上下文节点\)**: 执行时的节点实例

- **DataFlow 执行引擎**: 节点编排、组件调度、结果处理

- **BI Table 集成**: 飞书多维表格读写

- **BigData 集成**: HDFS/Spark 大数据处理

- **AI Model**: LLM 调用 \(Azure OpenAI / 阿里 DashScope / 百度\)

- **Excel/CSV/Parquet**: 多种数据格式解析与生成

