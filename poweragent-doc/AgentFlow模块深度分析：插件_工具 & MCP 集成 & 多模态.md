# AgentFlow模块深度分析：插件/工具 \& MCP 集成 \& 多模态

# **模块深度分析：插件/工具 \& MCP 集成 \& 多模态**

---

## **一、插件/工具模块**

### **1\.1 总体架构**

```Plain Text
┌─────────────────────────────────────────────────────────────────┐
│  插件/工具生态                                                     │
│                                                                  │
│  创建 (PluginController) → 发布到商店 (PluginStoreController)     │
│      │                          │                                │
│      │ 个人插件                   │ 社区插件                         │
│      ▼                          ▼                                │
│  ┌──────────┐              ┌──────────┐                          │
│  │ PERSONAL │              │COMMUNITY │                          │
│  │ source   │              │ source   │                          │
│  └────┬─────┘              └────┬─────┘                          │
│       │                         │                                │
│       └────────┬────────────────┘                                │
│                ▼                                                 │
│  ┌─────────────────────────────────────────┐                    │
│  │       RunPluginServiceImpl               │                    │
│  │       @NodeType(PLUGIN_MODULE)           │                    │
│  │                                          │                    │
│  │  1. authPluginCanUse()  —— 权限校验       │                    │
│  │  2. getPluginRuntimeById() —— 加载插件    │                    │
│  │  3. transModules() —— 转为 DAG 节点       │                    │
│  │  4. WorkFlowEngine.dispatchModules()     │                    │
│  │     —— 插件内部就是一个子 WorkFlow!         │                    │
│  └─────────────────────────────────────────┘                    │
│                                                                  │
│  插件类型:                                                        │
│  ├── PluginHttpServiceImpl  (@PLUGIN_HTTP) —— HTTP 调用           │
│  ├── PluginPythonServiceImpl(@PLUGIN_PYTHON) —— Python 代码执行    │
│  ├── PluginNLPServiceImpl   (@PLUGIN_NLP) —— NLP 服务             │
│  └── RunPluginServiceImpl   (@PLUGIN_MODULE) —— 复合插件(子流程)   │
└─────────────────────────────────────────────────────────────────┘
```

### **1\.2 技术实现**

#### **1\.2\.1 复合插件：插件即子 Workflow**

这是 AgentFlow 插件系统最核心的设计——**插件内部就是一个完整的 DAG 子流程**：

```Java
// RunPluginServiceImpl.execute()
@Service
@NodeType(FlowNodeTypeEnum.PLUGIN_MODULE)
public class RunPluginServiceImpl implements ModuleService {
    
    public Map<String, Object> execute(DispatchData dispatchData) {
        // 1. 加载插件定义
        RuntimePlugin plugin = getPluginRuntimeById(pluginId, source, dispatchData);
        
        // 2. 将插件的 nodes 转为 WorkflowEngine 可执行的 ModuleItemType
        List<ModuleItemType> modules = transModules(plugin);
        // 插件内部节点: [pluginInput, pluginHttp, pluginPython, pluginOutput]
        
        // 3. 找到插件入口节点
        ModuleItemType inputModule = modules.stream()
            .filter(item -> item.getFlowType().equals(PLUGIN_INPUT))
            .findFirst().orElse(null);
        
        // 4. 注入参数 + 调用 WorkFlowEngine 执行插件内部流程
        ChatDispatchParam chatDispatchParam = new ChatDispatchParam();
        chatDispatchParam.setModules(modules);
        chatDispatchParam.setStartParams(startParams);
        
        FlowContext flowContext = workFlowEngine.dispatchModules(chatDispatchParam);
        // ↑ 核心：插件内部走完整的 WorkFlowEngine 执行
        
        // 5. 收集插件输出
        return buildResponse(dispatchData, plugin, flowContext);
    }
}
```

**面试关键点**: 插件不是一个简单的函数调用，而是一个**嵌套的 WorkFlow 实例**。这意味着插件内部可以包含 LLM 对话、知识库检索、条件分支、循环等任意节点组合。

#### **1\.2\.2 插件来源：个人 vs 社区**

```Java
// pluginId 格式: "source-pluginId" 或 "pluginId"
// PERSONAL-abc123  → 个人插件
// COMMUNITY-xyz789 → 社区插件
// abc123           → 默认为个人插件

private SplitCombinePlugin splitCombinePluginId(String id) {
    String[] splitRes = id.split("-");
    if (splitRes.length == 1) {
        return PERSONAL;  // 无前缀 = 个人插件
    }
    return splitRes[0];   // 有前缀 = source
}
```

#### **1\.2\.3 HTTP 插件**

```Java
// PluginHttpServiceImpl —— 完整的 HTTP 客户端插件
@NodeType(FlowNodeTypeEnum.PLUGIN_HTTP)
public class PluginHttpServiceImpl implements ModuleService {
    
    public Map<String, Object> execute(DispatchData dispatchData) {
        // 1. 构建请求 (支持变量替换)
        HttpRequestProps props = buildProps(dispatchData);
        // system_httpReqUrl, system_httpMethod, system_httpHeader
        // system_httpParams, system_httpJsonBody, timeout, retry
        
        // 2. 变量替换: {{variable}} → 实际值
        parseHeader/Params/ReqUrl/RequestBody(props, concatVariables);
        
        // 3. 认证: Bearer / Basic Auth
        addAuth(request, props);
        
        // 4. 带重试的 HTTP 调用
        for (int i = 0; i < maxRetryTimes; i++) {
            HttpResponse response = request.execute();
            if (response.isOk()) {
                parseJson(formatResponse, response.body());
                break;
            }
        }
        
        // 5. 兜底: defaultJsonResult
        if (!success && defaultJsonResult != null) {
            parseJson(formatResponse, defaultJsonResult);
        }
    }
}
```

**面试关键点**: HTTP 插件支持 Bearer/Basic 认证、自动重试、变量替换、JSON 扁平化解析、请求失败兜底值。

#### **1\.2\.4 Python 插件**

```Java
// PluginPythonServiceImpl —— 远程 Python 代码执行
@NodeType(FlowNodeTypeEnum.PLUGIN_PYTHON)
public class PluginPythonServiceImpl implements ModuleService {
    
    public Map<String, Object> execute(DispatchData dispatchData) {
        // 1. 提取 pythonProjectId 和 mainCode
        // 2. 更新 Python 包代码 (如有修改)
        pythonUpdateCode(projectId, mainCode, userId);
        //    → POST af-rag-server/user/{userId}/package/{projectId}/entry
        
        // 3. 执行 Python 代码
        pythonRun(args, projectId, mainCode, userId, asyncParams);
        //    → POST af-rag-server/user/{userId}/package/{projectId}/execution
        //    或 POST af-rag-server/user/{userId}/package/execution (临时执行)
        
        // 4. 180s 超时，OkHttp 连接池
        OkHttpClient client = new OkHttpClient()
            .connectTimeout(180, SECONDS)
            .readTimeout(180, SECONDS);
    }
}
```

**面试关键点**: Python 插件在 af\-rag\-server 的沙箱中执行，Java 端通过 HTTP 调用。支持持久化包（有 projectId）和临时执行（只有 mainCode）两种模式。

#### **1\.2\.5 工具调用统一网关**

所有 5 种工具类型统一通过 `/api/v1/tools/run` 执行：

```Plain Text
Python Agent (af-rag-server)
    │
    │ POST /api/v1/tools/run
    │ {id, name, type, params, draftMode, agentId}
    │
    ▼
AgentFlow Server (Java)
    │
    ├── type="plugin"    → PluginService 执行插件
    ├── type="mcp"       → MCP Client 调用 MCP Server
    ├── type="workflow"  → WorkFlowEngine 执行子流程
    ├── type="autoAgent" → AutoAgent 嵌套调用
    └── type="dataset"   → KnowledgeSearchService 知识库检索
```

### **1\.3 技术难点**

#### **难点一：复合插件即 Workflow 的递归安全**

```Java
// 插件内部可以嵌套另一个插件 → 可能无限递归
// 解决方案：
// 1. WorkFlowEngine 的递归深度由 DAG 结构自然限制
// 2. 插件执行也会走 executeWithTimeout → 超时保护
// 3. ADK Runner 的 max_llm_calls=20 全局兜底
```

#### **难点二：动态参数注入**

```Java
// 插件入口有两种参数：
// - 固定参数 (在插件定义中声明的 input)
// - 动态参数 (DYNAMIC_INPUT_KEY，由外部传入)
private Map<String, Object> buildParams(hasDynamicInput, inputModule, startParams) {
    // 固定参数 → params
    for (String key : startParams.keySet()) {
        if (inputModule has input with this key) {
            params.put(key, startParams.get(key));
        } else {
            dynamic.put(key, startParams.get(key));  // 动态参数
        }
    }
    params.put("DYNAMIC_INPUT_KEY", dynamic);
}
```

### **1\.4 面试重点**

### **1\.5 业界对比**

---

## **二、MCP 集成模块**

### **2\.1 架构**

```Plain Text
┌──────────────────────────────────────────────────┐
│  AgentFlow (Java)                                │
│                                                   │
│  McpServerController (/api/v1/mcp-server)         │
│    ├── 注册 MCP Server (URL + 配置)               │
│    └── 管理 MCP Server 列表                        │
│                                                   │
│  节点类型:                                         │
│    ├── mcpTool → 调用 MCP Tool                    │
│    └── agent   → 调用 MCP Agent                   │
└──────────────────────┬───────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────┐
│  af-rag-server (Python)                          │
│                                                   │
│  /mcp/* 路由 (mcp_client.py)                      │
│    ├── MCP Client 连接到 MCP Server               │
│    ├── 协议: JSON-RPC over SSE/stdio              │
│    └── Tool 发现: tools/list → tools/call         │
│                                                   │
│  MCP 工具注册为 ADK BaseTool                      │
│    └── tool_type="mcp" → CommonTool               │
└──────────────────────────────────────────────────┘
```

### **2\.2 工具注册流程**

```Plain Text
1. 用户在 UI 注册 MCP Server URL
2. McpServerController → 保存到 McpServer 表
3. Agent 配置时选中 MCP Server → tool_type="mcp"
4. Agent 发布时 → agentMeta.tools.mcpInfoList
5. Python ADKExecutor 启动时:
   - 遍历 mcpInfoList
   - 为每个 MCP Tool 创建 CommonTool(tool_type="mcp")
   - CommonTool.run_async() → HTTP POST agentflow-server/api/v1/tools/run
6. AgentFlow Server 接收请求:
   - type="mcp" → 调用 MCP Client → 转发到 MCP Server
   - 返回结果给 Python Agent
```

### **2\.3 面试重点**

---

## **三、多模态模块**

### **3\.1 架构**

AgentFlow 的多模态能力分为**理解**和**生成**两类：

```Plain Text
多模态节点 (FlowNodeTypeEnum):

理解类 (输入 → 文本输出):
├── imgCompletion         —— 图像理解 (GPT-4V / Claude Vision)
├── audioCompletion       —— 语音识别 (ASR)
├── videoCompletion       —— 视频理解
└── videoKeyframesExtract —— 视频关键帧提取

生成类 (文本 → 富媒体输出):
├── imageGenerate  —— 文生图 (DALL-E / Stable Diffusion)
├── videoGenerate  —— 文生视频 (Sora / Runway)
└── voiceGenerate  —— 文生语音 (TTS)

流程控制:
├── fileSwitch —— 文件类型判断器 (路由到不同处理)
└── fileFilter —— 文件筛选器
```

### **3\.2 多模态处理流程**

```Plain Text
用户上传文件 (图片/音频/视频)
    │
    ▼
fileSwitch 判断文件类型
    ├── image/* → imgCompletion (图像理解)
    ├── audio/* → audioCompletion (语音转文字)
    └── video/* → videoKeyframesExtract (抽帧)
                      │
                      ▼
                 imgCompletion (逐帧理解)
                      │
                      ▼
                 chatNode (综合理解结果回答)

或:

用户文本指令
    │
    ▼
imageGenerate → 生成图片 → 返回 fileInfos
videoGenerate → 生成视频 → 返回 fileInfos
voiceGenerate → 生成语音 → 返回 fileInfos
```

### **3\.3 Agent 级别的多模态支持**

```Java
// AutoAgent.enableUploadFiles = true
// → Agent 可接收文件上传

// Python 端处理多模态:
// ADKExecutor.getPrompt():
text += """
## 重要
当用户想要生成一个多模态内容，但你无法提供多模态文件时，直接仅回复下面文本内容：
很遗憾，当前我没有直接生成{图片/视频/音频/文件}的能力和对应的工具调用。
不过你可以调用多模态模型和工具进行支持。
"""

// 多模态工具调用:
CommonTool.run_async() → 返回包含 fileInfos 的 result
→ ADKExecutor.fill_custom_metadata()
→ event.custom_metadata["fileInfos"] = fileInfos
→ 前端渲染文件 (图片/视频/语音)
```

### **3\.4 面试重点**

---

## **四、完整模块矩阵总结**

```Plain Text
┌──────────────┬──────────────────┬─────────────────────────────┐
│ 模块          │ 核心实现          │ 面试关键词                    │
├──────────────┼──────────────────┼─────────────────────────────┤
│ 工作流编排    │ WorkFlowEngine   │ DAG+递归、@NodeType、       │
│              │ + ModuleFactory  │ CountDownLatch、switchMap    │
├──────────────┼──────────────────┼─────────────────────────────┤
│ Agent 管理   │ AutoAgentService │ Snapshot版本、覆盖发布、      │
│              │ + AgentSnapshot  │ DFS循环检测、draft模式        │
├──────────────┼──────────────────┼─────────────────────────────┤
│ 对话/Chat    │ ChatCompletion   │ Token截断+摘要、JTokkit、    │
│              │ + AISummary      │ quoteMark引用溯源            │
├──────────────┼──────────────────┼─────────────────────────────┤
│ 知识库/搜索   │ DatasetSearch    │ 混合召回+RRF+Rerank、        │
│              │ + KnowledgeSearch│ ES+Milvus、PEG Ranker        │
├──────────────┼──────────────────┼─────────────────────────────┤
│ 插件/工具     │ RunPlugin        │ 插件=子Workflow、5种工具类型  │
│              │ + CommonTool     │ 统一网关、插件市场             │
├──────────────┼──────────────────┼─────────────────────────────┤
│ MCP 集成     │ McpServer        │ JSON-RPC协议、标准化外部工具   │
│              │ + mcp_client     │ tools/list → tools/call      │
├──────────────┼──────────────────┼─────────────────────────────┤
│ 记忆         │ Mem0 + Redis     │ 长期记忆向量化、会话变量       │
│              │ + MemoryScheduler│ 字段可配置、Prompt拼接        │
├──────────────┼──────────────────┼─────────────────────────────┤
│ Prompt 管理  │ PromptDetailVer  │ {{var}}模板、版本快照         │
│              │ + MessageFormat  │ 中心化复用                   │
├──────────────┼──────────────────┼─────────────────────────────┤
│ 多模态       │ FlowNodeTypeEnum │ 8种模态节点、fileSwitch路由   │
│              │ + AiCompletion   │ 理解+生成、fileInfos透传      │
├──────────────┼──────────────────┼─────────────────────────────┤
│ DataFlow联动 │ DataFlowContext  │ 数据库驱动异步、双层状态机     │
│              │ Job + Task       │ 指数退避重试、S3数据流转      │
└──────────────┴──────────────────┴─────────────────────────────┘
```

### **全系统 Prompt 拼接全景（最终答案）**

```Plain Text
┌──────────────────────────────────────────────────────────────┐
│  用户: "根据产品手册，推荐一款电池容量最大的型号"                │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  System Prompt (Prompt管理 + 记忆模块)                        │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 你是一个产品推荐助手。{{role_description}}               │  │
│  │                                                        │  │
│  │ [history]                                              │  │
│  │ 用户之前关注电池续航，偏好5000mAh以上型号。(长期记忆摘要)    │  │
│  │ 上次对话讨论了产品A和产品B的参数对比。(对话历史摘要)         │  │
│  │                                                        │  │
│  │ 规则: {{rules}}                                         │  │
│  │ 1. 基于参考资料回答                                     │  │
│  │ 2. 标注引用来源                                         │  │
│  └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  Context (知识库/搜索模块)                                     │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 【背景知识】                                             │  │
│  │ [1] 产品A: 电池5000mAh, 价格2999元... (score:0.92)      │  │
│  │ [2] 产品B: 电池4500mAh, 价格2599元... (score:0.87)      │  │
│  │ [3] 产品C: 电池6000mAh, 价格3599元... (score:0.81)      │  │
│  └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  User Message (Chat模块)                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 根据产品手册，推荐一款电池容量最大的型号                      │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  LLM 生成 (Chat模块 → SSE 流式输出)                            │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 根据产品手册中的参数，电池容量最大的型号是**产品C**，       │  │
│  │ 容量为 6000mAh，价格为 3599 元。                          │  │
│  │                                                        │  │
│  │ 如果您关注性价比，产品A 以 2999 元提供 5000mAh 也值得考虑。 │  │
│  │                                                        │  │
│  │ 参考文献：                                               │  │
│  │ `quoteMark {"id":"chunk_003","quoteId":1,...}`          │  │
│  │ `quoteMark {"id":"chunk_001","quoteId":2,...}`          │  │
│  │ ```quoteList [...]```                                   │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```



