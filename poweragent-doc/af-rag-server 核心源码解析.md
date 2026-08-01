# af\-rag\-server 核心源码解析

> 面向面试的 RAG \+ Agent 平台（Python）核心源码总结。 对应文档：`af-rag-server-architecture.md`。 关联项目：`agentflow-server`（Java 后端）—— 二者通过 Pinpoint 全链路追踪 / HTTP 工具调用互通（见 `source-code-analysis-09-10-trace-eval-deploy.md`）。
> 
> 

---

## **项目总览与双应用架构**

**技术栈**：Python 3\.11 \+ Flask \+ FastAPI \+ Google ADK \+ LlamaIndex/LangChain \+ Milvus/ES \+ Redis \+ MySQL \+ S3

af\-rag\-server 是"双应用"结构，由 supervisor 管理两个进程：

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

- **Flask RAG 应用（app\.py，端口 50000）**：RAG 算法服务、代码执行、自动 Agent 入口、GraphRAG、文档/网页/飞书解析、包管理、记忆向量化、RAGAS 评估、监控。

- **FastAPI Agent 应用（agent/app\.py，端口 10001）**：基于 Google ADK 的多智能体系统，SSE 流式返回。

**核心设计要点**：

---

## **核心文件清单**

```Plain Text
af-rag-server/
├── app.py                                       250 行  ★ Flask 主应用/路由注册
├── src/
│   ├── rag_algorithm/
│   │   ├── handlers.py                          895 行  ★ RAG 核心处理器/异步调度
│   │   ├── rag_flow.py                          604 行  ★ RAG 流水线编排(解析/分块/抽取)
│   │   └── tasks_file_parsing.py                406 行  ★ Huey 异步解析任务链
│   ├── component/
│   │   ├── parser/
│   │   │   ├── ocr_parser.py                    579 行  ★ OCR 解析器(MAIP 代理/表格)
│   │   │   └── vlm_parser.py                    175 行  ★ VLM 多模态解析器
│   │   ├── chunk_split/
│   │   │   ├── general_chunker.py               122 行  ★ 通用分块(按页+Token)
│   │   │   └── chunk_processor.py               130 行  ★ 预处理/关联信息附加
│   │   ├── retrieve/
│   │   │   ├── milvus_retrieve.py                54 行  ★ Milvus 向量检索
│   │   │   └── multiway_retrieve.py              60 行  ★ 多路检索聚合
│   │   ├── rerank/rag_base_rerank.py             36 行  ★ 重排序抽象基类
│   │   └── pycode/
│   │       ├── code_runner.py                    50 行  ★ 沙箱代码执行器
│   │       └── guards.py                        187 行  ★ builtins 白名单
│   ├── tools/pinpoint_context.py                 28 行  ★ Pinpoint 上下文(contextvars)
│   ├── server_api_tools/
│   │   ├── s3helper.py                          328 行  ★ S3 读写/JSONL 分块存储
│   │   └── mem0_helper.py                       812 行  ★ Mem0 记忆引擎
│   ├── memory/
│   │   ├── memory_vector.py                     157 行  记忆向量 CRUD 路由
│   │   ├── tasks.py                              83 行  记忆异步任务
│   │   └── scheduler.py                         179 行  记忆定时调度(RedisLock)
│   ├── apps/graphrag/
│   │   ├── graphrag.py                          784 行  ★ GraphRAG 抽取/查询
│   │   └── storage/networkx_storage.py          239 行  ★ NetworkX 图存储/Leiden 聚类
│   ├── eval/
│   │   ├── ragas_eval.py                        171 行  ★ RAGAS 评测
│   │   └── ragas_handlers.py                     95 行  评测路由
│   ├── web_parse/
│   │   ├── parse_manager.py                     350 行  网页解析路由
│   │   └── parse_url.py                         975 行  ★ Jina/FireCrawl 抓取
│   └── feishu_parse/
│       ├── get_token.py                         125 行  飞书 token(内外双通道)
│       ├── get_docdetail.py                     308 行  文档详情
│       └── feishu2markdown.py                   399 行  ★ 飞书 block → Markdown
└── agent/
    ├── app.py                                   152 行  ★ FastAPI Agent 应用/SSE
    ├── auto_agent/
    │   ├── executor.py                           67 行  AutoAgent 框架分发
    │   └── framework_adapter/adk/
    │       ├── executor.py                      443 行  ★ ADK 执行器(会话/事件/修复)
    │       ├── tool.py                          259 行  ★ CommonTool/KnowledgeTool
    │       └── custom_planner.py                123 行  ★ 中文 Planner
    ├── agents/intent_agent/
    │   ├── agent.py                              88 行  ★ 意图判定根 Agent
    │   ├── explicit_branch.py                   106 行  明确分支(策略/MCP 任务)
    │   └── utils.py                              84 行  工具判定意图
    ├── utils/
    │   ├── event_packer.py                      438 行  ★ SSE 事件打包/repack
    │   └── mcp_tools.py                         172 行  ★ MCP 客户端
    └── apps/mcp_client.py                       —       MCP 路由
```

---

## **RAG 处理流水线**

### **3\.1 Flask 应用入口与路由注册（app\.py）**

```Python
# app.py —— Flask 主应用（端口 50000）
def configure_resources(app):
    # 注册各类 RAG 服务：
    #   /rag_algorithm/*      —— RAG 算法服务
    #   /rag_algorithm/code_runner —— 代码执行
    #   /auto_agent/execute   —— 自动 Agent
    #   /graphrag/extract, /graphrag/query/global/map —— GraphRAG
    #   /parse/*              —— 文档/网页/飞书解析
    #   /user/<user_id>/package/* —— 包管理
    #   /vector/memory/*      —— 记忆向量化
    #   /redis_cache, /parse_html, /metrics
```

> **面试要点**：
> 
> - 双应用如何共享存储？—— 通过环境配置（Nacos）加载同一套 Redis/MySQL/ES/Milvus/S3 连接。
> 
> - Flask 侧为何用 `gunicorn + gevent`？—— `gevent.monkey.patch_all()` 让阻塞 IO（网络/S3/DB）协程化，配合线程池承载长耗时 RAG 任务。
> 
> 

### **3\.2 RAG 异步任务入口（handlers\.py，895 行）**

```Python
def handle_rag_process(**kwargs):
    task_id = uuid4().hex          # 任务 ID
    request_id = kwargs.get("request_id")   # 透传
    trans_metadata = kwargs.get("trans_metadata")  # 渗透参数
    callback_url = kwargs.get("callback_url")      # 回调地址

    # 按任务类型分发到线程池或 Huey 队列
    if task_type in executor_map:
        executor = get_executor(task_type)      # ThreadPoolExecutor(max_workers=3)
        future = executor.submit(run_task, task_id, ...)
    else:
        queue.enqueue(...)                      # Huey Redis worker

    # 结果回调：失败重试 3 次，间隔 1s；非 502 则中断
    for attempt in range(3):
        resp = requests.post(callback_url, json=result)
        if resp.status_code != 502:
            break
        time.sleep(1)
```

> **面试要点**：
> 
> - 为什么 `request_id` 单独透传？—— 与 `task_id` 解耦：task\_id 标识任务实例，request\_id 标识一次业务请求，贯穿 Pinpoint 全链路，便于多任务回溯同一请求。
> 
> - 线程池为何按任务类型隔离？—— 防止某类长任务耗尽全局线程，`max_workers=3` 控制并发度，避免打爆下游 S3/LLM。
> 
> - 回调 3 次重试 \+ 502 中断：下游网关重启时可恢复，真正的业务错误（非 502）不再浪费重试。
> 
> 

### **3\.3 RAG 流水线编排（rag\_flow\.py，604 行）**

解析方式映射（按文件类型/解析器分发）：

```Python
PARSING_METHODS = {
    "general": {
        ".pdf": OcrFileReader, ".docx": ..., ".txt": ..., ".csv": ...,
        ".md": ..., ".html": ...,
    },
    "vlm": {".pdf": VLMParser, ...},          # 多模态解析
    "intelli_medical_insurance": {".docx": ...},  # 保险专用
}

def file_parsing(task_id, parse_way="general", **kwargs):
    # 1) 从 S3 下载源文件 → 2) convert_to_pdf 转 PDF
    # 3) 按 parse_way 查 PARSING_METHODS 获取 Reader 解析
    # 4) 转换后的 PDF 上传到 settings.S3_CONVERT_SAVE_PATH
    ...
```

分块方式自动判定（根据解析器 \+ 文件类型推断）：

```Python
def chunk_splitting(task_id, parse_way, parser_info, chunk_config, ...):
    chunks = load_chunks_json(task_id)   # 从 S3 读解析结果
    # 自动判定分块方法：
    #   OcrFileReader + pdf/docx/ppt/png → ocr
    #   xls 表格行 > 20000 → general，否则 → ocr
    #   VLMParser → general
    method = auto_detect(parser_info, file_type)
    splitter = {
        "ocr":   OcrChunker(use_old_table_split=True),
        "general": GeneralSplitter(...),
        "separator": SeparatorSplitter(...),      # 分隔符分块
        "intelli_medical_insurance": IntelliMedicalInsuranceSplitter(...),
        "separator_recursive": SeparatorRecursiveSplitter(...),
        "video_asr": VideoASRSplitter(...),
    }[method]
    nodes = splitter.split(chunks)
    add_chunk_index(nodes)               # 补充 chunk_index 元信息
    return nodes
```

抽取三件套（逐 chunk 调用，异常降级为空）：

```Python
def keyword_extracting(task_id, chunks, ...):
    # 每个 chunk 独立调用 LLM 抽关键词，失败返回 []
    # 返回 { chunk_id: keywords } 映射
def summary_extracting(...):   # 摘要
def qa_extracting(...):        # QA 对
```

视频解析（ASR \+ 抽帧）：

```Python
def video_parsing(task_id, ...):
    asr = ASRHelper.offline_recognize_speech(...)     # 离线语音识别
    asr.offline_polling_result(...)                    # 轮询识别结果
    # 构造 PowerAgentVideoNode，带 start_time/end_time/middle_time
    # 首/中/尾三帧 + vlm_switch 时用 VLMParser 解析帧图
    # 帧图上传到 settings.S3_IMAGE_SAVE_PATH
```

分隔符默认配置（`main()`）：

```Python
separator_default = {
    "separator": "。|cn_period",
    "recursive": True,
    "spec_separator": ["[a-z]", "第.{1,3}章|chapter", "第.{1,3}条|article"],
    ...
}
```

> **面试要点**：
> 
> - `parse_way`（解析方式）与解析器解耦：通过 `PARSING_METHODS` 字典把"文件扩展名 → Reader 类"做成配置，新增解析器只需注册一行。
> 
> - 分块方法自动判定：根据 `parser_info`（用哪种解析器）\+ 文件类型做规则路由，而非由前端硬编码，避免"PDF 用 OCR 解析却走了 general 分块"的错配。
> 
> - 抽取失败不阻断流水线：`keyword/summary/qa` 任一失败只影响该 chunk，返回空列表，保证下游向量化不整体失败。
> 
> 

### **3\.4 完整 RAG 处理链**

```Plain Text
文档上传 → file_parsing(解析) → chunk_splitting(分块)
    ↓
keyword / summary / qa 抽取（LLM）
    ↓
Embedding → Milvus/ES（向量存储）
    ↓
Retrieve(检索: Milvus/MultiWay/TF-IDF) → Rerank(Bge) → LLM 生成
```

---

## **文档解析**

### **4\.1 OCR 解析器（ocr\_parser\.py，579 行）**

三种解析路径：

```Python
def load_data(self, ...):
    # 按 content_extract / image_extract / image_text_extract / html_describe 分发
    if parse_way == "content_extract":   # OCR 文本
        ...
    elif parse_way == "image_extract":   # 纯图像
        ...
    elif parse_way == "image_text_extract":  # VLM 图像+文本
        vlm = VLMParser(...)
    ...
```

MAIP OCR 代理任务轮询：

```Python
def _request_ocr_via_proxy(self, ...):
    # 提交 OCR 代理任务 → 轮询结果
    timeout = 36000            # 10 小时超时
    err_count = 0
    while time.time() < end:
        resp = requests.get(status_url)
        if resp.code in (200, 227, 425):   # 进行中/部分成功
            break
        elif resp.code == 425:             # 异常
            err_count += 1
            if err_count >= 10:
                raise Exception("OCR 失败")
            time.sleep(5)
```

Block → 文本转换：

```Python
def parser_block_to_text(self, block, ...):
    block_type = block["type"]
    if block_type == "标题":        return f"**{text}**"
    if block_type in ("文字", "下描述", "页眉", "上描述"): return text
    if block_type in ("有线表", "无线表"):
        return self._table2text(table)     # Markdown 管道表格
    if block_type == "公式":        return block_content
    if block_type in ("二维码", "印章"): return ""   # 无文本
```

表格标准化（行列合并 → 多格式输出）：

```Python
def table_block_add_std_table(self, table, ...):
    # cells + merged_cells 构建
    # 输出 HTML / LATEX / NL(自然语言) 三种序列化
```

> **面试要点**：
> 
> - OCR 结果以块（block）为单位带结构化类型（标题/表格/公式…），`parser_block_to_text` 按类型决定渲染策略——这是 RAG 保留文档结构的关键，比"全部拼成纯文本"召回质量高得多。
> 
> - 代理任务轮询的容错：`err_count>=10` 才失败，且 425 区分"可重试"与"终态"，避免抖动把长任务误杀。
> 
> - 表格不走纯文本：`_table2text` 转 Markdown 管道表，保留行列语义，供后续表格问答。
> 
> 

### **4\.2 VLM 多模态解析器（vlm\_parser\.py，175 行）**

```Python
def request_vlm_model(self, image_base64, prompt, ...):
    payload = {
        "contents": [{"parts": [
            {"text": prompt},
            {"image": {"image_url": f"data:image/jpeg;base64,{image_base64}"}},
        ]}]
    }
    for attempt in range(3):
        resp = requests.post(vlm_url, json=payload, timeout=...)
        if resp.status_code == 429:       # 限流
            time.sleep(60)                # 等 60s 重试
            continue
        return resp.json()

def load_data(self, ...):
    # PDF → convert_pdf_to_jpeg（逐页转图）→ 每张图请求 VLM
    # 可选 upload_s3_image 把结果图传 S3 追加展示
```

> **面试要点**：
> 
> - VLM 直接把 PDF 页渲染成 JPEG → base64 → 图像多模态请求，绕过传统 OCR 的版面问题（图表、印章、手写）。
> 
> - 429 退避：60s 固定等待，最多 3 次——LLM 网关限流时用"慢退避"而非指数退避，避免多个 worker 同时放大流量。
> 
> 

---

## **分块（Chunk）**

### **5\.1 通用分块（general\_chunker\.py，122 行）**

```Python
class GeneralSplitter:
    def split(self, chunks):
        nodes = []
        for page in chunks:                 # 按页切片
            page_label = page.get("location", {}).get("page_label")
            text = parser_block_to_text(page)   # 复用 block 转换
            nodes.append(TextNode(text=text, metadata={"page_label": page_label}))
        # 二次切分：Token 级，避免超出模型窗口
        splitter = TokenTextSplitter(
            chunk_size=1024, chunk_overlap=200, separator=" "
        )
        return self.chunk_processor.process_chunks(splitter.split_nodes(nodes))
```

> **面试要点**：
> 
> - **两段式分块**：先按页（保留 page\_label 定位），再按 Token（1024/200 重叠）——页面是文档结构的天然边界，Token 切分是模型窗口的硬约束。
> 
> - chunk\_overlap=200（≈20%）：补偿切分点丢失的语义上下文，是召回率 vs 冗余的经典折中。
> 
> 

### **5\.2 预处理与关联信息（chunk\_processor\.py，130 行）**

```Python
_PREPROCESS_FUNCS = {
    "remove_line_breaks": remove_line_breaks,          # 去换行
    "remove_urls": remove_urls,                        # 去 URL(仅 ASCII 字符，防止吞中文)
    "replace_whitespace_line_tabs": replace_whitespace_line_tabs,  # 空白归一
    "remove_emails": remove_emails,                    # 去邮箱
}

def process_chunks(self, nodes):
    for node in nodes:
        processed_text = self.preprocess_text(text)     # 按配置顺序应用
        # 关联信息拼入文本，供检索命中携带上下文：
        #   associated_filename: xxx.pdf
        #   associated_headings: [第1章, ...]  ← extract_headings 正则 ^#{1,6}\s*(.+)
        new_ck['text'] = "\n".join(parts + [processed_text])
```

> **面试要点**：
> 
> - `remove_urls` 特意只匹配 ASCII 字符集 `[A-Za-z0-9\-._~:/?#...]`，避免正则贪婪吞掉 URL 之后的中文——多语言文档清洗的典型坑。
> 
> - `associated_filename / associated_headings` 以纯文本前缀注入 chunk，等于隐式注入元信息，让重排/LLM 无需额外查元数据即可感知来源与层级。
> 
> 

---

## **检索与重排**

### **6\.1 Milvus 向量检索（milvus\_retrieve\.py，54 行）**

```Python
class MilvusRetriever(BaseRetriever):
    def _retrieve(self, query_bundle, **kwargs):
        query_embedding = self.embed_model.get_query_embedding(query_bundle.query_str)
        results = self.milvus_collection.search(
            data=[query_embedding],
            anns_field="embedding",
            param={"metric_type": "IP", "params": {"nprobe": 512}},
            limit=self.limit * 2,                     # 多召回一倍，供过滤后裁减
            expr=expr,                                # 文件过滤 expr
        )
        # md5_document_map: node_id → node
        # 过滤、按 score 降序、裁到 self.limit
```

> **面试要点**：
> 
> - `limit * 2` 策略：向量检索先多取一倍候选，经文件过滤（单文档问答场景 `retrieve_filter_by_file_name`）后裁减，保证过滤后仍足量。
> 
> - `metric_type="IP"`（内积）匹配归一化 embedding；`nprobe=512` 是召回与延迟的平衡点。
> 
> 

### **6\.2 多路检索聚合（multiway\_retrieve\.py，60 行）**

```Python
class MultiWayRetriever(BaseRetriever):
    def _retrieve(self, query_bundle, **kwargs):
        if self.query_handler and self.query_handler.query_HyDE:
            query_bundle = self.query_handler.query_HyDE(query_bundle)  # 查询改写
        for key, retriever in self.retrievers.items():
            nodes = retriever.retrieve(query_bundle)
            if isinstance(retriever, TfIdfVectorIndexRetriever):
                # 恢复原文本并保留关键词
                node.metadata['origin_text_before_keywords'] = ...
                node.metadata['keywords_text'] = ...
            for node in nodes:
                node.metadata['retriever'] = key       # 标注来源路
        return simple_deduplicate(nodes)               # 按 document.id_ 去重
```

> **面试要点**：
> 
> - 每路检索结果打上 `metadata['retriever']` 来源标签——后续可追踪"这条命中来自向量/关键词/混合"，也支撑可解释性。
> 
> - HyDE（查询改写）可选开启：先用 LLM 生成假设文档再检索，提升短查询召回。
> 
> - 去重基于 `document.id_` 而非 node\_id：同一文档多块命中只保留一条，避免重排被重复来源淹没。
> 
> 

### **6\.3 重排抽象基类（rag\_base\_rerank\.py，36 行）**

```Python
class RagBaseRerank(BaseNodePostprocessor):
    def _postprocess_nodes(self, nodes, query_bundle=None):
        # 包装 CBEventType.RERANKING 回调事件 + SaveContextHandler
        return self.rerank_process(nodes, query_bundle)
    def rerank_process(self, nodes, query_bundle):   # 抽象方法
        raise NotImplementedError
```

> **面试要点**：
> 
> - 重排器走 LlamaIndex 的 `BaseNodePostprocessor` 生命周期，事件化便于 Pinpoint/日志记录每阶段耗时与命中数。
> 
> - 具体实现（bge\_rerank\.py / peg\_rerank\.py）继承 `rerank_process`：召回 N 条 → 模型打分 → 重排序 → 截断，是"宽召回、精重排"的落点。
> 
> 

---

## **代码执行沙箱（pycode）**

### **7\.1 执行器（code\_runner\.py，50 行）**

```Python
def exec_code(code: str, kwargs: dict, func="main", **options) -> dict:
    exec_globals = {
        "__builtins__": safe_builtins,          # 白名单 builtins
        "s3_read_file": s3_read_file,           # 注入受控 IO
        "s3_write_file": s3_write_file,
    }
    with redirect_stdout(io.StringIO()) as f:
        try:
            byte_code = compile(code, filename="<inline code>", mode="exec")
            exec(byte_code, exec_globals)
            if callable(exec_globals.get(func)):
                result = exec_globals[func](**kwargs)
            else:
                raise Exception(replacer.replace("{src.component.py-code.main_function_missing}"))
        except BaseException as e:
            lines = traceback.format_exc().splitlines()
            if len(lines) >= 3:
                lines = lines[0:1] + lines[3:]   # 去掉 rag 框架内部栈帧
            err_msg = "\n".join(lines)
            exec_success = False
        output = f.getvalue()                    # 捕获 print 输出
    return {"stdout": output, "ret": json.dumps(result, ensure_ascii=False), "exc": err_msg}
```

### **7\.2 builtins 白名单（guards\.py，187 行）**

```Python
danger_names = [
    "compile", "dir", "exec", "execfile", "file", "globals", "input",
    "locals", "open", "raw_input", "vars", "buffer", "eval", "intern",
    "memoryview", "reload",
]
safe_builtins = {k: v for k, v in vars(builtins).items()
                 if k not in danger_names and not k.startswith("_")}
# Python 3.11+ 额外保留 ExceptionGroup 以便 BaseException 捕获
```

> **面试要点**：
> 
> - **不是 AST 黑名单，而是 builtins 白名单**：从 `vars(builtins)` 中剔除 `open/eval/exec/compile/globals/input` 等危险入口，用户代码根本无法引用它们——比"字符串扫描危险词"更可靠（重名覆盖、别名都绕不过）。
> 
> - 用户可用的 IO 只有注入的 `s3_read_file/s3_write_file`，把"读数据"收窄到受控的 S3 通道，杜绝任意文件读写。
> 
> - 细节：`BaseException`（而非 `Exception`）捕获，防止用户 `sys.exit`/`KeyboardInterrupt` 逃逸；traceback 削掉中间两行框架栈帧，让报错只暴露用户代码。
> 
> - 局限（可追问点）：纯 Python 层白名单挡不住 `ctypes`/C 扩展内存破坏，真隔离仍需容器/子进程 \+ seccomp。
> 
> 

---

## **Huey 异步任务链与 S3 存储**

### **8\.1 优先级队列（tasks\_file\_parsing\.py，406 行）**

```Python
OcrTaskQueueEnum = {SMALLFILE: <1MB, FILE: 1-30MB, BIGFILE: >30MB}

def run_ocr_smallfile_task(priority, task_id):      # 通用包装
    return run_ocr_file_task(task_id, parse_way="ocr")

# 用 @huey_ocr_file_priority.priority_task() 装饰，首个参数是 priority，
# Huey 据此决定进哪个 Redis 队列
ocr_task_queue_mapping = {
    "ocr_convert_to_pdf":   {SMALLFILE: run_ocr_smallfile_task, ...},
    "ocr_file_splitting":   {...},
    "ocr_file_parsing":     {...},
    "ocr_result_merging":   {...},
}
```

多阶段流水线 \+ 合并锁：

```Python
def ocr_result_merging(task_id, ...):
    # setnx 合并锁：同一任务多文件并发解析后只允许一个线程合并
    lock = redis.setnx(f"merge:{task_id}", ...)
    if not lock: return
    # 状态码：int(time.time()) = 进行中；1 = 成功；0 = 失败
    # 按页码排序 → return_full_document 重新拼接 → 回调
```

> **面试要点**：
> 
> - 按文件大小分三档队列：小文件快速通道、大文件慢通道，避免大文件阻塞小文件 SLA——经典"按资源特征隔离队列"。
> 
> - `setnx` 合并锁：多文件并行解析各自回写，最后合并阶段用分布式锁保证只合并一次；状态用"时间戳=进行中"避免误判超时。
> 
> 

### **8\.2 S3 JSONL 分块存储（s3helper\.py，328 行）**

```Python
S3_PATH_RE = r"s3://(.*?)/"           # _split_s3_path 解析 bucket/key

def save_chunks_json(task_id, chunks):
    # 每行一个 JSON dict，ensure_ascii=False，跳过空行
    # 路径约定：settings.S3_SAVE_PATH.format(task_type, task_id)
def load_chunks_json(task_id):
    # 逐行 json.loads 还原 chunks

def upload(self, key, data):
    if size >= 250 * 1024 * 1024:      # ≥250MB 走分片
        # boto3 transfer_config multipart_threshold=250MB, chunk=5MB
```

> **面试要点**：
> 
> - **JSONL 而非 JSON 数组**：大任务结果可流式读写，无需整体加载进内存；单行损坏不影响其他行解析。
> 
> - 路径约定 `format(task_type, task_id)`：把"任务类型 \+ 任务 ID"作为对象键层级，天然支持按任务清理、迁移。
> 
> - 250MB 分片阈值 \+ 5MB 分片：S3 单 PUT 上限 5GB，但 \>250MB 时串行整传极慢，分片并发上传可提速且支持断点续传。
> 
> 

---

## **Agent 智能体系统（FastAPI \+ Google ADK）**

### **9\.1 FastAPI 应用与 SSE 流式（agent/app\.py，152 行）**

```Python
def create_fastapi_app():
    app = FastAPI()
    app.add_middleware(PinPointMiddleWare)          # Pinpoint 埋点
    if pinpoint_agent.set_agent_info():
        app.add_middleware(RawContextMiddleware)    # 上下文注入
    app.include_router(mcp_client_router, prefix="/mcp")         # MCP 客户端
    app.include_router(multi_agent_router, prefix="/multi-agent")
    run_scheduler()                                  # 启动调度器
    return app

@app.post("/api/auto-agent/run")
async def execute_autoagent(request: AutoAgentRequest):
    queue = asyncio.Queue()                          # 事件队列
    async def heartbeat():
        while True:
            await queue.put({"type": "data", "data": "\n"})
            await asyncio.sleep(10)                  # 10s 心跳防断连
    task = asyncio.create_task(executor.execute(...))# 后台执行
    return StreamingResponse(stream(queue, task), media_type="text/event-stream")
```

> **面试要点**：
> 
> - **asyncio\.Queue 作为生产\-消费桥梁**：Agent 执行器（可能是同步/阻塞操作）把事件塞进队列，SSE 流独立消费——响应不阻塞执行、执行不阻塞响应。
> 
> - **10s 心跳**：Nginx/网关默认 60s 空闲断连，Agent 思考可能超 60s，心跳保证连接存活。
> 
> - `reqId → langfuse span("ai-insight-agent")` \+ `X-Trace-Id` 响应头，把一次 Agent 会话做成一条可观测链路。
> 
> 

### **9\.2 ADK 执行器（executor\.py，443 行）**

会话管理（Redis 持久化 \+ 历史重放）：

```Python
def _init_session(self, user_id, session_id):
    # InRedisSessionService：删除旧会话 → 新建 → 把 chat_history 作为事件重放
    # 保证多轮对话上下文恢复
```

事件合并与元数据填充：

```Python
def execute(self, ...):
    runner = Runner(adk_app, run_async=True,
                    state_delta={HEADER_NAME_ORG_CODE_OUT: tenantid, DATASET_INFO: []})
    buffer = []                     # 2 事件缓冲
    async for event in runner.astream(app_request, session):
        event = self.fill_custom_metadata(event)
        # function_call + function_response 合并为一条后推送（事件打包）
        yield f"event: {event.type}\ndata: {event.model_dump_json()}\n\n"
```

元数据标准化（多 Agent 事件 → 前端统一结构）：

```Python
def fill_custom_metadata(self, event):
    # number 计数、is_runner_start 标记、type="llm"
    # function_call/function_response：取 id + name
    # 从工具结果提取 fileInfos
    # PLANNING_TAG → 填充 plan 字段
    # thought 字段
```

Prompt 动态替换：

```Python
def get_prompt(self, agent_name, ...):
    # {{k}} 变量替换
    # 引用 QA 背景、长期记忆 longTermMemory、showSource 引用展示
    # 多模态降级兜底
```

LLM 网关适配（LiteLLM）：

```Python
def llm(self, ...):
    modelId = model.split("_", 1)[-1]            # 去掉 "maip_" 前缀
    base_url = base_url.removesuffix("/chat/completions")
    max_completion_tokens = 10240
    # max_retries=0：由上层控制重试，避免双层重试放大延迟
```

工具列表组装：

```Python
def tools(self, ...):
    # knowledgeInfoList → 每个知识库一个 KnowledgeTool
    # pluginInfoList / mcpInfoList / workflowInfoList / agentInfoList
    #   → 每个一个 CommonTool
```

工具调用后记录数据来源：

```Python
def after_tool_call(self, tool, args, result):
    # 记录 tool_type / avatar
    # 提取 [id, collectionId, datasetId] 写入 state["DATASET_INFO"]
    # 供前端"引用来源"展示
```

历史裁剪（窗口控制）：

```Python
def before_model_callback(self, callback_context, ...):
    # 把 contents 裁剪到 historyRound 轮用户对话，控制 token 成本
```

引用修复（LLM 输出脏数据清洗）：

```Python
def fix_quote(self, content, quotes):
    # quoteMark/quoteList 正则提取 → repair_json 修复 → 引用编号重排
    # 移除 "参考文献：" 后缀
```

> **面试要点**：
> 
> - **`max_retries=0`**** 但上层事件流天然重试**：LLM 网关已限流时，让流式事件带着错误返回，由框架/用户层决定——避免 SDK 同步重试把 SSE 连接挂住。
> 
> - **function\_call \+ function\_response 缓冲合并**：ADK 单事件模型与前端"一问一答"展示模型不一致，2 事件合并成"一个函数调用回合"推送，前端渲染更连贯。
> 
> - 会话放 Redis：多实例 Agent 服务可共享会话，`session_id` 支持断线续传；历史重放用事件（而非原始消息），保证状态机一致。
> 
> 

### **9\.3 工具层（tool\.py，259 行）**

```Python
class CommonTool(BaseTool):
    # headers: userId / userName / tenantId / orgId / orgCode
    async def run_async(self, tool_context, **kwargs):
        # POST {AGENT_FLOW_SERVER_URL}/api/v1/tools/run
        #   (timeout: connect=10, read=300)
        if res["code"] != "10000": return fail(...)
        # tool_context.actions.skip_summarization 跳过总结
        # 工具产出文件时 → fileInfos → 回答 "已为您生成以下文件："

class KnowledgeTool(BaseTool):
    # tool_type = "dataset"
    def _get_declaration(self):
        return {
            "topK": 150, "rrfSwitch": True, "vectorSearchRatio": 0.6,
            "searchType": 0, "numCandidates": 200,
            "searchMode": {"1": "embedding", "2": "fullTextRecall", "3": "mixedRecall"},
            "reRankerSwitch": useRerank, "recallLimit": ...,
            "userChatInput": query, "similarity": minScore,
            "fullTextSearchRatio": 0.4, "limit": retrieve_max_length,
        }
    # 结果过滤为 [id, collectionId, datasetId, q]
    # 空结果走 backupStrategy 自定义回答兜底
```

> **面试要点**：
> 
> - CommonTool 是"通往 Java 后端的桥"：`AGENT_FLOW_SERVER_URL/api/v1/tools/run`，插件/MCP/工作流/自动 Agent 统一走这一入口——Python 侧不实现具体业务，只做参数透传与结果整形，单一职责清晰。
> 
> - KnowledgeTool 直接透传混合检索参数（向量/全文/混合三模式 \+ rrf 融合 \+ 0\.6 向量占比）：RAG 参数由上层 Agent 动态决定而非固定，是"让 LLM 决定怎么检索"的 Agentic RAG 落点。
> 
> - 文件工具结果只回 `id`：大文件内容不塞进 LLM 上下文，前端按 id 拉取/展示，控制 token 成本。
> 
> 

### **9\.4 中文 Planner（custom\_planner\.py，123 行）**

```Python
class CustomPlanner(PlanReActPlanner):
    PLANNER_INSTRUCTION = "你是一个计划生成器..."       # 中文系统指令
    REASONING_INSTRUCTION = "你是一个推理引擎..."
    FINAL_ANSWER_INSTRUCTION = "...直接给出最终答案"
    # _handle_non_function_call_parts / _only_keep_plan 处理非函数调用文本
```

> **面试要点**：
> 
> - 继承 `PlanReActPlanner` 仅重写指令文案与计划清洗逻辑：计划阶段（Planner）与执行阶段（ReAct）分离，长任务先出 Plan 再逐步执行，比纯 ReAct 更可控、可展示。
> 
> 

### **9\.5 意图判定根 Agent（intent\_agent/agent\.py，88 行）**

```Python
def before_agent_callback(self, callback_context):
    # label → TEMP_METADATA_LABEL
    check_strategy_publish(callback_context)   # 策略发布分支
    check_strategy_use(callback_context)       # 策略使用分支

def before_model_callback(self, callback_context):
    if label == LABEL_RUN_CREATE: return        # 跳过
    if label == LABEL_RUN_START:
        request_create_task_mcp_tool(...)       # MCP 任务创建
    if INTENT_ANALYSIS_WITH_TOOL_FLAG:
        judge_intent_by_tool(callback_context)  # 工具判定意图

root_agent = CustomLlmAgent(
    name=AGENT_INTENT_AGENT,
    model=ModelFactory.create_model(use_custom_llm_model=True),
    sub_agents=[metadata_agent, strategy_agent, strategy_view_agent,
                direct_analysis_agent, run_data_agent],
    include_contents="none",
)
```

### **9\.6 明确分支（explicit\_branch\.py，106 行）**

```Python
def check_strategy_use(callback_context):
    # TEMP_USER_SELECTED_STRATEGY_ID：用户明确选择了策略
def check_strategy_publish(callback_context):
    # get_strategy_detail → Content(buttonType="strategyPublish")
    # 走按钮交互分支
def request_create_task_mcp_tool(callback_context):
    # init_file_data_by_id 填充 listValue
    # insightMCPTools.execute(INSIGHT_MCP_TOOL_CREATE_TASK_SCHEDULE)  # MCP 建任务
```

### **9\.7 工具判定意图（utils\.py，84 行）**

```Python
def judge_intent_by_tool(callback_context):
    # 调用 MCP INSIGHT_MCP_TOOL_FETCH_INTENT 由服务端判定意图
    _valid_intent = ["strategy", "strategy_view", "metadata",
                     "direct_analysis", "run_data", "other"]
    if intent in _valid_intent and intent != "other":
        # 返回 function_call: transfer_to_agent({"agent_name": intent})
        return function_call(...)
    # "other" → MSG_NOTIFICATION_DEFAULT（无需子 Agent）
```

> **面试要点**：
> 
> - 意图判定"双轨"：**明确分支**（规则/按钮/会话状态，如策略发布检查）先行，**工具判定**（MCP 服务端 LLM 判定）兜底——确定性规则不依赖 LLM，模糊请求才交给模型。
> 
> - `transfer_to_agent` 是 ADK 原生路由机制：以 function\_call 形式切换子 Agent，Agent 间状态通过 session/state 传递。
> 
> - `before_agent_callback` 可在模型调用前改写上下文/注入内容，`before_model_callback` 在调用前劫持——回调链是 ADK 的"中间件"。
> 
> 

### **9\.8 SSE 事件打包（event\_packer\.py，438 行）**

```Python
WRONG_ANSWER_START_TEXTS = ["transfer_to_agent", "<tool_call>", "For context"]

def repack_resp_parts(self, agent_name, response_parts):
    funcs = {
        "decision_agent": decision_agent_repack,
        "strategy_create_agent": strategy_create_agent_repack,
        "analysis_agent": analysis_agent_repack,
        "run_data_agent": run_data_agent_repack,
    }
    repack = funcs.get(agent_name, default_repack)
    return repack(response_parts)   # deepcopy 后重排

def _post_process_text(self, text):
    if text.startswith(tuple(WRONG_ANSWER_START_TEXTS)):
        return MSG_NOTIFICATION_RETRY        # 换一种表达重试
```

```Python
# run_data_agent_repack 示例：把内部字段映射为前端展示结构
def run_data_agent_repack(self, response_parts):
    # label: RUN_FIELD_CONFIRM / RUN_CREATE_CONFIRM
    # TEMP_TABLE_FIELDS / TEMP_METADATA_FIELDS 映射表字段
    # fileId 兜底 → MUIL_AGENT_ANSWER
```

> **面试要点**：
> 
> - **每个子 Agent 一个 repack 函数**：前端展示结构与 LLM 内部输出解耦——模型输出的"中间态"（字段确认、计划、数据流）被转成前端可渲染的卡片/按钮，这是多 Agent 产品化的关键胶水层。
> 
> - `WRONG_ANSWER_START_TEXTS` 兜底：`transfer_to_agent`/`<tool_call>` 泄漏到正文时，不展示原样，而是替换为友好通知——输出保洁。
> 
> 

### **9\.9 MCP 客户端（mcp\_tools\.py，172 行）**

```Python
class MCPClientTools(MCPToolset):     # 官方 Toolset 方式（SseConnectionParams）
    ...

class MCPClientToolsNew:               # 底层裸客户端
    # raw sse_client + ClientSession
    def execute_retry(self, tool_name, arguments, read_timeout_seconds):
        ...
    def execute(self, ...):            # retry=2
        ...

insightMCPTools = MCPClientToolsNew(url=INSIGHT_MCP_SERVER_URL)  # 全局单例
```

> **面试要点**：
> 
> - MCP 双封装：`MCPToolset` 走官方高层 API，`MCPClientToolsNew` 走裸 `sse_client`——裸客户端可控超时与重试，适用于对延迟敏感的意图判定/建任务工具。
> 
> 

---

## **GraphRAG（知识图谱 RAG）**

### **10\.1 实体/关系抽取（graphrag\.py，784 行）**

```Python
DEFAULT_TUPLE_DELIMITER = "<|>"
DEFAULT_RECORD_DELIMITER = "##"
DEFAULT_COMPLETION_DELIMITER = "<|COMPLETE|>"
GRAPH_FIELD_SEP = "<SEP>"

def extract_entities(records, context, ...):
    # 按 tuple/record delimiter 解析 LLM 结构化输出
    # glean 循环：对低覆盖率再补一轮（max_gleanings=1）
    # 返回 entities / relations 三元组

def graphrag_extract(...):
    # 文本 → chunk → extract_entities → 建图(upsert_node/upsert_edge)
    # → generate_community_report 生成社区报告 → 返回
    return {"entities": ..., "relations": ..., "reports": ..., "graph": ...}
```

### **10\.2 图存储与社区发现（networkx\_storage\.py，239 行）**

```Python
class NetworkXStorage:
    graph = nx.Graph()                  # 无向图

    def _leiden_clustering(self, graph):
        return hierarchical_leiden(
            graph, max_cluster_size=10, random_seed=0xDEADBEEF,
        )   # 层次聚类：自顶向下，每层 ≤10 节点

    def _node2vec_embed(self, ...):     # 图嵌入
```

### **10\.3 全局查询（map 阶段）**

```Python
def graphrag_map(...):
    # final_support_points 按重要性 score 过滤 score>0，降序排列
    # 交给 LLM 综合回答
```

> **面试要点**：
> 
> - GraphRAG 的"提取→图→社区→报告→全局/本地查询"五段式：社区报告让 LLM 只读"每个社区的摘要"，而非全图节点，控制全局查询的上下文量。
> 
> - Leiden 层级聚类（max\_cluster\_size=10）：社区分层，回答时逐层下钻，避免单社区过大撑爆上下文。
> 
> - 固定 `random_seed=0xDEADBEEF`：聚类结果可复现——评估/线上一致，否则同一文档两次抽取的社区划分不同，缓存失效。
> 
> 

---

## **Mem0 记忆引擎（mem0\_helper\.py，812 行）**

### **11\.1 记忆核心（自定义 Memory 子类）**

```Python
class MyMemory(Memory):
    def _create_memory(self, ...):        # @override 自定义记忆 id
        ...
    def _search_vector_store(self, ...):  # @override 修复 user_id 过滤 bug
        # 按 user_id 过滤，避免跨用户检索到他人记忆
    def _update_memory(self, ...):        # @override 更新逻辑

Calculator.ebbinghaus_decay = lambda t: 0.005 * t ** (-0.087)  # 遗忘曲线
# B=0.008941 → 约 20% 记忆保留率

VectorStatus = {NORMAL: 0, FORGETED: 1, DELETED: 2}
```

### **11\.2 混合检索 \+ 加权排序**

```Python
def query_memory_es_mix(self, user_id, query, ...):
    # ES script_score:
    #   score = 0.2 * cosine + 0.8 * (keyword / max_keyword)
    # 最终：sort_score = score * weight / max_weight
    #   （weight 由记忆时效/频次动态调整）

def llm_filter_query_result(self, ...):
    # LLM 二次过滤：从召回记忆里剔除无关项
```

### **11\.3 记忆冲突消除（conflict\_agent）**

```Python
def add_memory(...):
    # 写前查冲突：conflict_agent(LLM) 判断新旧记忆是否矛盾
    # 新增/重复权重：add_weight * 0.2x；相减权重 sub_weight * 0.5x
```

### **11\.4 异步任务与定时遗忘（memory/）**

```Python
# tasks.py：@huey_memory_vector.task() 异步写库
# scheduler.py：
scheduler.add_job(
    func=collect_forgotten_memories,   # 每日遗忘扫描
    trigger=CronTrigger(day_of_week='0-6', hour=1, minute=0),
)
# RedisLock 分布式互斥：key = "memory:scheduler:" + md5(URL)
# 防止多实例重复执行每日遗忘
```

> **面试要点**：
> 
> - **记忆 = 向量 \+ 结构化状态**：Mem0 之上叠加 `VectorStatus`（正常/遗忘/删除）状态机，配合每日 Cron 的"遗忘"——不是只增不删的向量库，而是带生命周期的记忆。
> 
> - 混合检索 `0.2·cosine + 0.8·keyword`：语义为主 \+ 关键词纠偏，比纯向量更稳（记忆短句语义区分度低）。
> 
> - `conflict_agent` 用 LLM 判重/判冲突，权重惩罚（x0\.2 / x0\.5）让矛盾记忆自然沉底而非删除——保留证据链，支持"记忆纠偏"。
> 
> 

---

## **RAGAS 评测体系（eval/）**

```Python
class RAGASEval(BaseEval):
    all_metrics = [          # 7 项指标
        "answer_correctness", "answer_relevancy", "answer_similarity",
        "context_precision", "context_recall", "context_relevancy",
        "faithfulness",
    ]
    default_metrics = [      # 默认 4 项
        "answer_correctness", "answer_similarity", "context_recall", "faithfulness",
    ]

def eval(self, ...):
    dataset = Dataset.from_dict(...)
    config = RunConfig(
        max_workers=4, max_retries=1, max_wait=600, thread_timeout=600,
    )
    # importlib 动态加载指标模块 + getattr 取类，raise_exceptions=False
    result = evaluator.evaluate(dataset, ...)
    # NaN → -1 归一化输出
```

> **面试要点**：
> 
> - 指标分"答案质量"（correctness/relevancy/similarity）与"上下文质量"（precision/recall/relevancy/faithfulness）两组：前者看生成，后者看检索——RAG 调优时两类指标要分开看，检索差则后四者低。
> 
> - `importlib` 动态加载指标：新指标只需"实现文件 \+ 名字"，无需改路由——评测框架的可扩展点。
> 
> - `RunConfig(max_workers=4)`：并行评测单条样本，`max_retries=1` 容忍 LLM 评测偶发失败，`NaN→-1` 让前端可区分"低分"与"缺失"。
> 
> 

---

## **Web / 飞书解析**

### **13\.1 网页抓取（parse\_url\.py，975 行）**

```Python
def get_html(url, retry=30, ...):
    # 抓取失败重试 30 次（网络抖动容忍）
def fetch_with_jina(url):    # GET https://r.jina.ai/{url}  读取 Jina Reader
def fetch_with_firecrawl(url, ...):
    # clean_payload 判断含 crawl 字段 → /crawl 提交爬取任务
    # 否则 → /scrape 直接抓取单页
# 一页 = 一个 PowerAgentTextNode → save_chunks_json（不做真实分块）
```

### **13\.2 飞书双通道鉴权（get\_token\.py，125 行）**

```Python
# 内网通道：随机 token "t-" + 36 位随机串，双向存 Redis（内网签名代理）
# 外网通道：真实 tenant_access_token，TTL = expire - 10s 提前刷新
```

### **13\.3 飞书文档 → Markdown（feishu2markdown\.py，399 行）**

```Python
class BlockType(IntEnum):
    table = 31; quote = 34; list = 19; code = 14; image = 27; todo = 17

def _build_tree(self, blocks):
    # parent_id → children_map，构建父子树
def _render_block(self, block, ...):
    # 按 BlockType 渲染：表格(bitable row=17)、引用、列表、代码、图片→S3 链接
```

> **面试要点**：
> 
> - 飞书鉴权"内外双通道"：内网走签名代理（免真实 token，Redis 存映射），外网走正式 token 并提前 10s 刷新——同一套代码适配两类环境，token 不泄漏给内网服务。
> 
> - `_build_tree` 先把扁平的 block 列表按 `parent_id` 建树，再递归渲染：飞书文档的嵌套结构（列表嵌套、表格内块）必须树化才能正确转 Markdown。
> 
> 

---

## **Pinpoint 全链路追踪（Java ↔ Python）**

### **14\.1 上下文传递（pinpoint\_context\.py，28 行）**

```Python
import contextvars
trace_id = contextvars.ContextVar("pinpoint_trace_id", default="-")
span_id  = contextvars.ContextVar("pinpoint_span_id", default="-")

class PinpointContext:
    @staticmethod
    def set_trace_id(t): trace_id.set(t)
    @staticmethod
    def get_trace_id(): return trace_id.get()
```

### **14\.2 接入点**

```Python
# Flask before_request：
#   从 HTTP 头 pinpoint-traceid / pinpoint-spanid 读取
#   → PinpointContext.set_trace_id/set_span_id
#   → 日志过滤器 PinpointFilter 注入
#     日志格式：[TxId:<trace_id>,SpanId:<span_id>]
#   为什么用 contextvars 而非 threading.local？
#     → Agent 端是 asyncio 协程，threading.local 在 await 切换后会串上下文
#       contextvars 与 asyncio 原生配合，协程间自动隔离
```

> **面试要点**：
> 
> - **HTTP 头透传 traceid/spanid 打通 Java↔Python**：Java 侧（agentflow\-server）MDC 里的 `PtxId/PspanId`（见 `source-code-analysis-09-10`）随请求头进入 Python 侧 `PinpointContext`，实现"一次用户请求横跨 Java 编排层 \+ Python RAG/Agent 层"的单条链路可观测。
> 
> - `contextvars` vs `threading.local`：asyncio 中同一线程会调度多个协程，`threading.local` 会污染，`ContextVar` 由事件循环感知、await 后自动恢复——选型的正确性关键。
> 
> 

---

## **面试核心问答**

---

## **深度追问**

#### **Q1：双应用各自独立部署，如何避免数据/会话不一致？**

- **答案定位**：§1、§8、§9\.2

- 共享存储层（Redis/MySQL/ES/Milvus/S3）由配置（Nacos）统一注入，两应用不各自造数据；Agent 会话放 Redis（`InRedisSessionService`），多实例可横向扩容且断线续传；RAG 任务状态与分块结果以 task\_id 为键写 S3/Redis，Flask 与 FastAPI 都只通过"键 \+ 约定路径"读写，天然一致。

#### **Q2：RAG 任务失败如何保证不丢、可重试？**

- **答案定位**：§3\.2、§8

- 入口异步化（线程池/Huey 队列），任务对象落 Redis；回调失败重试 3 次、502 中断；Huey 任务链每步独立可重入；合并阶段 `setnx` 锁 \+ `int(time.time())` 进行中标记，重跑可识别"未完成"状态续跑。

#### **Q3：代码沙箱能挡住所有逃逸吗？真隔离方案？**

- **答案定位**：§7

- 不能。builtins 白名单挡 Python 层危险调用，但 `ctypes`、纯 C 扩展、资源耗尽（无限循环/内存）仍可能逃逸或拖垮进程。真隔离：Docker 容器 \+ seccomp/apparmor \+ CPU/内存限额 \+ 超时 kill，或 gVisor/Firecracker 微虚拟化。当前实现适合"受信用户的插件"而非"不可信公共代码"。

#### **Q4：Agent 的 SSE 为什么用队列而不是直接生成器？**

- **答案定位**：§9\.1

- Agent 执行是多步异步（多轮工具调用 \+ LLM 流式），直接在响应生成器里 await 会把"执行逻辑"和"传输协议"耦合；asyncio\.Queue 让执行器只负责产事件、SSE 只负责消费转发，心跳协程也能独立向队列写事件。生产者/消费者可独立扩缩。

#### **Q5：ADK** **`before_model_callback`** **里注入** **`judge_intent_by_tool`** **有什么用？为什么不用纯 LLM 判定？**

- **答案定位**：§9\.6–9\.7

- 用 MCP 服务端（INSIGHT\_MCP\_TOOL\_FETCH\_INTENT）判定意图，意味着意图识别的模型/规则可以独立演进、可 A/B、可缓存；纯 LLM 判定每次请求都花钱且不稳定。模型回调只在"需要判定"的请求上触发（`INTENT_ANALYSIS_WITH_TOOL_FLAG`），其余请求不增加额外 LLM 调用。明确分支（策略发布/使用、任务创建）走规则，进一步降低 LLM 依赖。

#### **Q6：GraphRAG 为什么比向量 RAG 更适合全局问题？**

- **答案定位**：§10

- 向量 RAG 按相似度取 topK，是"局部命中"，跨多文档聚合问题（"所有项目的共性风险"）会漏；GraphRAG 把实体/关系建图 → 社区发现 → 社区报告，全局查询先读社区报告（压缩后的全局摘要）再综合，上下文量可控且覆盖全图。代价是抽取/建图成本高，适合离线预构建。

#### **Q7：记忆的"遗忘"机制与向量库删除有什么区别？**

- **答案定位**：§11

- 删除是物理移除，丢失证据链；Mem0 用 `VectorStatus`（0 正常/1 遗忘/2 删除）\+ 每日 Cron 扫描 \+ 艾宾浩斯衰减，把过期记忆降权/下沉而非抹除；矛盾记忆通过 `conflict_agent` 判定后加权（x0\.2/x0\.5）而非覆盖，保留新旧两版本供追溯。检索时混合评分 `score*weight/max_weight` 自然淘汰低权重项。

#### **Q8：为什么 RAGAS 评测要分"答案类"与"上下文类"指标？**

- **答案定位**：§12

- 答案正确性/相关性/相似度评价"生成"质量，上下文精确率/召回率/相关性/忠实度评价"检索"质量。若只测答案类，检索退化会部分被 LLM 生成掩盖（LLM 能"脑补"）；两类同时测才能定位是检索环节还是生成环节的问题。faithfulness 专门检测生成是否忠于检索上下文（幻觉检测）。

#### **Q9：Pinpoint 在 asyncio（Agent 端）下如何不串链路？**

- **答案定位**：§14

- Java 侧 MDC（线程本地）在 Python 侧不能照搬：asyncio 单线程多协程，`threading.local` 会被 await 切换污染。改用 `contextvars.ContextVar`，事件循环在协程切换时自动保存/恢复 Context 副本，保证每个协程持有独立 traceid/spanid；日志通过 `PinpointFilter` 注入 `[TxId, SpanId]`，与 Java 侧日志对齐格式，跨语言对账。

#### **Q10：新接入一种 Agent 框架（如 LangGraph）如何扩展？**

- **答案定位**：§9（AutoAgentExecutor）

- `AutoAgentExecutor.execute` 按 `framework` 字段分发：`"adk" → ADKExecutor`、`"langgraph" → LangGraphExecutor`。新框架实现同一接口（`execute()` \+ SSE 事件输出 \+ 会话管理），即可复用 intent\_agent、event\_packer、CommonTool/KnowledgeTool、Pinpoint/Langfuse 追踪。框架与业务解耦是这套设计的扩展性来源。

