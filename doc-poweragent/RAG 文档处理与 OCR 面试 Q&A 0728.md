# RAG 文档处理与 OCR 面试 Q\&A 0728

---

<a id="q1"></a>
## **Q1: 支持的文档格式有哪些？不同格式有什么处理策略？** {#q1}

### **支持的格式与解析器**

### **不同格式的策略差异**

#### **PDF**

PDF 在解析前先判断类型：

- **文本型**（文字可选中的电子 PDF）：PyMuPDF 直接提取文本、图片坐标。`page.get_text("dict")` 获取结构化数据

- **扫描型**（拍照/传真件）：走 OCR 流程。`ocr_parser.load_data()` 将文件二进制传给 OCR 服务 → 等待返回结构化 Block 结果

#### **图片**

不是直接调 OCR，而是有**两种处理策略**可选（`ocr_parser.py:247-251`）：

```Python
# parse_strategy 可同时包含多个选项
self.parse_way = "ocr"          # content_extract: 识别文字
self.image_extract = True       # image_extract: 提取文档中的图片，上传 S3，链接拼入文本
self.image_text_extract = True  # image_text_extract: 用 VLM 识别图片中的文字
```

#### **XLSX**

走 OCR 服务的 Excel 专用接口（`ocr_url_excel`），不是通用的 OCR API。返回格式与普通文档不同——按 Sheet 组织（`{sheet_name, res: [block列表]}`），需要格式适配后才能统一处理（`ocr_parser.py:517-521`）。

---

<a id="q2"></a>
## **Q2: OCR 怎么用的？原理是什么？**

### **OCR 服务的调用方式**

本项目**不是自研 OCR 模型**，而是接入公司内部的 OCR 服务。调用方式有三种（`ocr_parser.py:471-510`）：

```Plain Text
load_data(file_path, file_name)
  │
  ├── 是 Excel？ → _request_ocr_api(ocr_url_excel, ...)  ← Excel 专用接口
  │
  ├── 渠道类型是 SaaS（百度云/阿里云/华为等）？→ SaasOcrParser.parser() ← 第三方 OCR
  │
  ├── 环境开启了异步 OCR proxy？→ _request_ocr_via_proxy(ocr_url_proxy, ...) ← 异步轮询
  │
  └── 默认 → _request_ocr_api(ocr_url, ...)  ← 同步 HTTP 调用
```

**同步调用**（`_request_ocr_api`，第 354\-393 行）:

```Python
# 将文件二进制 + 参数 POST 给 OCR 服务
files = {"image_binary": image_binary}
ocr_params = {
    "parse_way": self.parse_way,        # "ocr" 或 "ocr_text"
    "basename": file_name,
    "file_format": file_format,         # .pdf/.png/.jpg
    "item_base64": 1,                   # 要求返回图片 base64
}
# Excel 表格不分页拆分
if "excel" in gen_url:
    ocr_params["table_split"] = 0
    ocr_params["item_base64"] = 0

response = requests.post(gen_url, files=files, data=ocr_params, timeout=7200)
```

**异步轮询**（`_request_ocr_via_proxy`，第 265\-352 行）:

```Python
# 提交任务 → 获取 jobId → 轮询状态 → 下载结果
res = requests.post(ocr_parse_url, data={"fileUrl": file_url, "ocrParseWay": parse_way})
job_id = res["data"]["jobId"]

timeout = 36000  # 10 小时超时
while True:
    if now >= due_time: raise Timeout
    job_res = requests.get(job_status_url, params={"jobId": job_id})
    if job_res["code"] == 200:  # 完成 → 下载 resultUrl → 返回
    if job_res["code"] == 425:  # 失败 → 抛异常
    time.sleep(5)               # 每 5s 轮询一次
```

### **OCR 返回结果的格式**

OCR 服务返回的结构是**按页组织的 Block 列表**：

```Python
result = [
    # page 0
    [
        ["标题", box_coords, [["1.1 概述", x0, y0, x1, y1]], ...],
        ["文字", box_coords, [["这是正文内容", x0, y0, x1, y1], ...], base64_data],
        ["有线表", box_coords, {"table_struct": {"row": 5, "col": 4, "cells": [...]}, "describe_html": "<table>..."}],
        ["图片", box_coords, [["图片alt文字", ...]], base64_string_of_image],
        ["页眉", box_coords, [["公司名称", ...]]],
        ["页脚", box_coords, [["第1页", ...]]],
    ],
    # page 1
    [...]
]
```

**Block 类型**（`parser_block_to_text` 函数，第 181\-215 行）: \| block\_class \| 含义 \| 处理方式 \| \|\-\-\-\-\-\-\-\-\-\-\-\-\-\|\-\-\-\-\-\-\|\-\-\-\-\-\-\-\-\-\| \| 标题 / X级标题 \| 文档标题（带层级） \| 加粗标记（`text`），提取到标题树用于构建段落上下文 \| \| 文字 / 下描述 / 页眉 / 上描述 \| 正文段落 \| 拼接文字行，根据坐标信息判断段落边界 \| \| 有线表 / 无线表 \| 表格 \| `table_struct` → `std_table`（标准化表格格式）；`describe_html` 为 HTML 表格 \| \| 图片 \| 图片 \| 可选：VLM 识别文字 / 上传 S3 保留链接 \| \| 图表 \| 图表 \| 提取图表中的文字标注 \| \| 公式 \| LaTeX 公式 \| 保留 LaTeX 源码 \| \| 二维码 / 印章 \| 不需要的内容 \| 丢弃（返回空字符串） \|

### **OCR 的预处理与后处理**

**块级合并**（`block_merge.py` — `BlockMerge.process()`）:

- **页眉/页脚过滤**（`filt_header_footer`，第 233\-267 行）：第一遍扫描提取全文的页眉/页脚文本（长度≥5）。第二遍用编辑距离匹配——相似度 \> 0\.95 的 text block 判定为重复页眉/页脚，从结果中移除

- **跨页表格合并**（`merge_tables`，第 141\-178 行）：上一页末尾是表格、下一页开头也是表格 → 判断列数是否匹配（`is_same_table`）→ 同表则合并 HTML 或调整行号偏移合并 cells

- **表格格式标准化**（`table_block_add_std_table`，第 97\-138 行）：`table_struct`（row/col/cells \+ 合并单元格）→ `std_table`（data/HTML/LATEX/NL 四种格式）

**文本切分**（`txt_split.py` — `TXTSplitter.process()`）:

- **版面分析**（`DocLayoutInf`，第 18\-193 行）：

    - 正文字体大小：统计所有文字 block 中每行的字体高度（y1\-y0），取出现次数最多的作为正文字体

    - 标题层级：从 block\_class 中提取"X级标题"的级别号，标题字体小于正文 0\.9 倍则判定为非标题

    - 文档结构树：跟踪当前标题路径（如 `1. 概述 -> 1.1 背景`），每个文字 block 都带上它归属的标题路径

- **段落边界判断**（`is_same_paragraph`，第 255\-284 行）：两个文字行是否属于同一段落？判断条件：字体大小接近（ratio \> 0\.6）且字体大小 \> 行间距 / 本行结尾超过上行结尾超过 5 字符（新起一段）/ 上行以标点结尾且距离右边界超过 2 字符（段落结束）

- **跨页段落合并**（`process` 第 389\-397 行）：上一页末 block 是文字、block 的最后一个段落没有结束标点 → 与下一页首 block 的第一个段落合并

- **小段落合并**（`merge_node`，第 306\-337 行）：同标题路径 \+ 同类型的文字节点合并到接近 chunk\_size 大小。标题不同或类型不同则不合并

---

<a id="q3"></a>
## **Q3: OCR 出现错字和截断怎么处理？**

### **错字处理——三层机制（基于源码）**

**第一层：OCR 服务层面的置信度**

OCR 服务本身对识别结果有置信度评估。代码中通过 `error_pages` 字段（`ocr_parser.py:578`）返回异常页面：

```Python
error_pages = res_json.get("errorPages", [])
```

有 error\_pages 的页面会被标记，后续可以人工复核或跳过。

**第二层：VLM 兜底识别图片中的文字**

对于 OCR 无法处理的图片场景（手写体、印章、特殊字体），用 VLM（视觉语言模型）识别（`ocr_parser.py:407-415`）：

```Python
if self.image_text_extract:
    if block_base64:
        block_text = self.vlm_parser.request_vlm_model(block_base64[0])
        block_content[0][0] = block_text  # 用 VLM 结果替换 OCR 结果
```

适用场景：图片中的文字标注、图表标题、印章文字、手写签名。

**第三层：术语库归一化（Java 端）**

检索前通过术语库（Term Entry）做标准化替换——把 OCR 误识的术语纠正为标准形式（`DenseVectorService.queryTermAndReplace`）。

### **截断处理——三类场景**

**场景一：跨页段落截断**

`txt_split.py` 第 389\-397 行：

```Python
# 上一页末段没有结束标点 → 与下一页首段合并
if block_index == 0 and page_index != 0 and pre_block[0] == '文字' \
        and new_node_with_context[-1]['content'][-1] not in '.?:!。？：！':
    pre_node = new_node_with_context[-1]
    extension_node = block_chunk_nodes[0]
    pre_node['content'] += extension_node['content']  # 直接拼接
```

**场景二：跨页表格截断**

`block_merge.py` 第 46\-82 行（关键逻辑）：

```Python
# 检测上一页末尾是表格
if block[0] in target_block_type and block_index == len(page) - 1:
    pre_table_page_index = page_index
    pre_table_block_index = block_index

# 检测下一页开头是表格 → 判断是否为同一张表
if block_index == 0 and pre_table_page_index is not None:
    if self.is_same_table(doc_text, pre_table_block, page_index):
        self.merge_tables(doc_text, pre_table_block, page_index)  # 合并
```

**表格合并逻辑**（`merge_tables`，第 141\-178 行）：

- 有 `describe_html`（HTML 表格）：去除第一张表的 `</table>` 末尾 \+ 第二张表的 `<table>` 开头 → 拼接

- 无 `describe_html`：调整第二张表的行号偏移（`start_row += row_offset`），合并 cells 到第一张表

**场景三：行内截断**

`ocr_parser.py` 的 `_table2text` 不做行内截断处理——它只管表格渲染。行内截断依赖 OCR 服务本身的能力（检测框 margin 扩大、字符边界检测）。

### **整体策略总结**

### **面试怎么讲**

> OCR 我们不是自研模型，而是接入公司内部的 OCR 服务。关键代码在 `ocr_parser.py`——同步 HTTP 上传文件二进制、异步轮询 jobId 两种模式。OCR 返回的是按页组织的 Block 列表，包含标题层级、正文段落、有线表/无线表、图片、页眉页脚等强类型 Block。
> 
> 后处理有两个重要步骤：`BlockMerge` 做跨页表格合并——检测上下页表格列数是否一致，一致就合并；`TXTSplitter` 做版面分析——统计正文字体大小、建立标题树、判断段落边界。段落边界判断很实用：字体大小接近且大于行间距 = 同一段落；上行以标点结尾且靠近右边界 = 段落结束。
> 
> 错字处理不是靠语言模型，而是靠 VLM 兜底识别图片文字 \+ 术语库标准化替换（把 OCR 误识的术语纠正为标准形式）。截断分三种情况：跨页段落检测末页无标点自动合并、跨页表格检测列数匹配自动合并 HTML/cells、小碎块同标题下合并到 chunk\_size。
> 
> 

---

## **附录：业内方案对比**

### **文档解析引擎**

### **OCR 引擎**

### **OCR 纠错方案**

### **跨页表格处理**

---

<a id="q5"></a>
## **Q5: 主 Agent 和子 Agent 之间怎么交互？信息怎么传递？**

### **交互机制——ADK 的 transfer 模式**

本项目基于 Google ADK 的 `transfer_to_agent` 机制。主 Agent（IntentAgent）不做业务处理，只做**路由决策**：

```Plain Text
用户消息
    │
    ▼
IntentAgent (LLM 推理)
    │
    ├── "查看策略" → transfer_to_agent(strategy_view_agent)
    ├── "分析数据" → transfer_to_agent(strategy_agent)
    ├── "查元数据" → transfer_to_agent(metadata_agent)
    └── 社交互动  → 直接回复，不分发
           │
           ▼
    子 Agent 执行完成
           │
           ▼
    控制权返回 IntentAgent → 生成最终回答
```

### **信息传递方式——State 透传**

ADK 的 `CallbackContext.state` 是一个全局 dict，所有 Agent 共享。主 Agent 写入 → 子 Agent 读取 → 子 Agent 写入 → 主 Agent 读取。

**主 Agent 写入，子 Agent 读取的数据**（`intent_agent/agent.py` → `before_agent_callback`）：

```Python
# 写入位置: intent_agent/agent.py 第 24-41 行
callback_context.state[TEMP_METADATA_LABEL] = label          # 前端 label 标记
callback_context.state[TEMP_USER_SELECTED_STRATEGY_ID] = id   # 用户选中的策略 ID
callback_context.state["request_params"] = {...}              # 完整请求参数

# 读取位置: 各子 Agent 从 state 中取
# strategy_view_agent/agent.py 第 23 行:
request_params = ctx.session.state.get("request_params", {})
```

**哪些不需要传**：

### **子 Agent 需要返回什么？**

子 Agent 的返回是一个 **Event**，其中 `content.parts[0].text` 是核心结果。格式有两类：

**成功场景**（`strategy_view_agent/agent.py` 第 50\-80 行）：

```Python
def _pack_success_event(self, ctx, mcp_resp):
    part_text = [
        {"key": FRONTEND_KEY_DATA,
         "value": "好的，当前有N个策略类型，共M条策略...",
         "valueType": "string"},
        {"key": FRONTEND_KEY_DATA,
         "value": {"buttonType": "strategyListShow"},
         "valueType": "object"},  # ← 前端按钮数据
    ]
    return Event(
        author=self.name,
        content=Content(parts=[Part(text=json.dumps(part_text))]),
    )
```

返回结构是固定的 JSON 列表，每项包含：

- **`key`**: 固定 `FRONTEND_KEY_DATA`（前端数据标记）

- **`value`**: 实际内容（字符串或对象）

- **`valueType`**: `"string"`（展示文本）或 `"object"`（前端组件数据，如按钮配置）

**失败场景**（同文件第 42\-48 行）：

```Python
def _pack_retry_event(self, ctx):
    return Event(
        author=self.name,
        content=Content(parts=[Part(text=MSG_NOTIFICATION_RETRY)]),
    )
    # MSG_NOTIFICATION_RETRY = "我似乎未能分析出最终结果，请您再试一次吧。"
```

**原则**：子 Agent 返回的是"前端可直接消费的结果"，不是中间数据。主 Agent 不做二次加工，直接转发给前端。

### **主 Agent 是否感知子 Agent 失败/重试/超时？**

**不感知**。这是 ADK 框架的设计决策——transfer 是同步调用，对主 Agent 完全透明。

```Plain Text
IntentAgent: transfer_to_agent(strategy_agent)  ← 调用后挂起
    │
    ▼  (控制权交给 strategy_agent)
    │
StrategyAgent: 执行 PlanReAct 循环 ...
    │  ├── 调 LLM → OK
    │  ├── 调工具 → 失败 → 重试 → OK
    │  └── 调 LLM → 超时 → 重试 3 次 → 全部失败
    │       → 返回 MSG_NOTIFICATION_RETRY
    │
    ▼  (控制权返回 IntentAgent)
    │
IntentAgent: 收到子 Agent 的 text 输出
    │  └── text 里是 "我似乎未能分析出最终结果..."
    │      → IntentAgent 无法区分这是"子 Agent 失败了"还是"子 Agent 正常回答"
    │      → 直接透传给前端
```

**关键**：主 Agent 感知到的只有子 Agent 返回的 `Event.content.parts[0].text`。它不知道这个 text 是正常结果还是兜底重试消息。失败/重试/超时全部在子 Agent 内部消化，不向主 Agent 暴露。

从代码可以验证这一点（`strategy_view_agent/agent.py`）：

```Python
async def _run_async_impl(self, ctx):
    try:
        mcp_resp = await insightMCPTools.execute(...)
        if not mcp_resp:
            yield self._pack_retry_event(ctx)    # 失败 → 返回兜底文本
        else:
            yield self._pack_success_event(ctx, mcp_resp)  # 成功 → 正常结果
    except Exception as e:
        yield self._pack_retry_event(ctx)  # 异常 → 同样返回兜底文本
```

**异常兜底有两层**（`multi_agent/api.py` 第 102\-113 行）：

```Python
# 第一层: Runner 级别的异常兜底
try:
    async for event in intent_runner.run_async(...):
        ...
except Exception as e:
    retry_event = Event(author=AGENT_INTENT_AGENT,
                        content=Content(parts=[Part(text=MSG_NOTIFICATION_RETRY)]))
    return [retry_event]

# 第二层: 子 Agent 内部异常 → 返回兜底 Event 给主 Agent
# 主 Agent 视其为普通文本输出，不做特殊处理
```

第一层是 Runner 级别的（整个对话异常），第二层是子 Agent 级别的（子 Agent 执行失败）。主 Agent 本身不参与任何失败处理逻辑。

### **为什么这样设计？**

### **面试怎么讲**

> 主 Agent 和子 Agent 之间通过 ADK 的 `transfer_to_agent` 机制交互，数据通过 `CallbackContext.state` 这个全局 dict 透传。主 Agent 不做业务处理，只做路由决策——它把用户问题分给正确的子 Agent，子 Agent 执行完成后再把控制权交回来。
> 
> 信息传递有两类：主 Agent 写给子 Agent 的（请求参数、业务上下文、用户选中的策略 ID），和子 Agent 写回给主 Agent 的（前端可直接消费的结果文本）。不传递的是子 Agent 内部的工具调用历史、LLM 推理链、原始数据——这些是子 Agent 的内部实现细节，主 Agent 不需要知道。
> 
> 最关键的设计：**主 Agent 不感知子 Agent 的失败/重试/超时**。子 Agent 内部有完整的 try/except 兜底，所有异常统一返回"请再试一次"的消息。主 Agent 只看到子 Agent 返回的文本，区分不了是正常结果还是失败兜底。这样主 Agent 的 Prompt 可以保持极简——它只需要知道"分发给谁"，不需要理解"出了什么问题"。
> 
> 

---

<a id="q4"></a>
## **Q4: 什么时候用 LLM，什么时候用正常脚本去解析？**

### **一句话**

> 脚本处理"结构"和"规则"——全部免费、全部可测试。LLM 只用来填脚本填不了的"语义缺口"——按调用次数付钱、当且仅当脚本搞不定时才用。
> 
> 

### **本项目的实际决策矩阵**

从代码中可以清晰看到这个分层逻辑：

```Plain Text
文档处理全链路：

① 文件格式判断 (脚本) —— 一次正则匹配，零成本
   if re.match(r".*\.xls(x?)$", file_name) → Excel 专用接口

② OCR 文字提取 (脚本) —— 调 OCR 服务，返回结构化 Block
   requests.post(ocr_url, files=image_binary)

③ 页眉页脚过滤 (脚本) —— 编辑距离算法，零 LLM 成本
   StringTools.edit_distance(block_text, header) > 0.95 → 判定为重复，移除

④ 跨页表格合并 (脚本) —— 列数规则匹配，零 LLM 成本
   is_same_table: 上一页末表格列数 == 下一页首表格列数 → 合并

⑤ 段落边界判断 (脚本) —— 坐标系数学计算，零 LLM 成本
   is_same_paragraph: 字体大小比 > 0.6 且 字体大小 > 行间距 → 同一段落

⑥ ★ 图片文字识别 (VLM/LLM) ★ —— 脚本无法判断图片语义
   image_text_extract → VLM 识别图片中的文字/手写/印章

⑦ ★ 低置信度文本纠错 (LLM, 未实现) ★ —— 扩展方向
   当前只用了 VLM 覆盖图片场景，还没扩展到文本场景
```

### **决策框架**

### **具体的分界线**

**脚本能搞定的事（本项目全部用脚本）**:

**必须用 LLM 的事（本项目用 VLM）**:

**还没做但应该用 LLM 的事**（扩展方向）:

### **业内的相同思路**

### **面试怎么讲**

> 这个问题的核心是"成本"和"确定性"的权衡。脚本处理的都是确定性任务——判断表格是不是同一张，看列数就行，看 100 次答案都一样。LLM 处理的是非确定性任务——识别图片中的文字、理解图表含义，这些没有明确规则。
> 
> 我们的实际做法是：整个文档处理链路中，从文件格式判断到页眉过滤到跨页表格合并到段落边界判断——全部是脚本。只有一处调了 VLM：`image_text_extract` 开启时，对 OCR 无法处理的图片块做视觉识别。其他 AI 能力（摘要、关键词提取）放在入库后的 docStorage 节点，不影响解析管线的稳定性。
> 
> 未来如果要扩展 LLM 的使用，原则是不动现有脚本管线——只在脚本输出结果上叠加 LLM 增强（如对低置信度 OCR 文字做 LLM 纠错，不是替代 OCR，是增强 OCR 结果）。
> 
> 

### **建议的下一步演进**

基于对业界的了解，如果 PA 要进一步优化文档处理：

1. **短中期（低成本）**: 在 `ocr_parser.py` 的 `_parse_ocr_result` 后增加 LLM 纠错——选择性地对低置信度文本块调 LLM 修正（不是全量，控制 token 成本）。目前 VLM 只覆盖了"图片中的文字"场景，可以扩展到"扫描质量差的文字行"

2. **中期**: 引入 MinerU 或 Unstructured 作为解析器选项，和现有 OCR 服务并行——质量优先场景用 MinerU，批量场景用现有服务。`load_data` 的路由逻辑加一个分支即可，不动其他地方

3. **长期**: 表格 key\-value 嵌入（参考 Ragent 的做法）——在 `_table2text` 或 docStorage 入库时，生成两份文本：一份 markdown 给 LLM 看、一份 key\-value 给 Embedding 用。此优化已经在 chunk 的 dual\-text 设计上有基础了

<a id="q5_6"></a>
## **Q5: WorkFlow 引擎——内存执行，挂了全丢**

**问题**: 整个 Workflow 在一个 JVM 进程中跑，`FlowContext` 是内存对象。进程崩溃 → 所有中间状态丢失。

```Plain Text
用户对话 → dispatchModules() → 执行了 5 个节点 → JVM OOM
                                           ↓
                                    全部白跑，用户看到超时
```

**现状**: `ModuleService.executeWithTimeout()` 对单节点做了超时 \+ 重试 3 次，`moduleDefaultOutput` 兜底。但这些是单节点级别的，Workflow 级别没有 checkpoint。

**改进方向**: 对非实时场景增加 checkpoint——每执行完一个节点把 `FlowContext` 序列化到 Redis，进程恢复后从最近的 checkpoint 继续。代价是每次 checkpoint 有序列化开销，折中方案是对非对话场景（API 调用 Workflow）开启。

### **JVM 堆内存与并发能力**

**堆内存配置**：

4GB 堆对 agentflow\-server 足以支撑数百并发会话——WorkFlow 是"编排"而非"计算"，主要堆开销来自 FlowContext 对象、SSE 响应缓冲区、ES 查询结果反序列化，都不是内存密集型操作。

**并发控制瓶颈**（不在 JVM 堆，在下游依赖）：

**实际压测参考**（4GB JVM，单机部署）：WorkFlow 模式约 **200～300 并发会话**（无 LLM 调用延迟堆积）；AutoAgent 模式约 **50～100**，因为 ADK PlanReAct 循环在 Python 端执行，Java 端主要是 SSE 透传等待。

**OOM 真正的高危场景**（不是堆太小，而是这三类泄漏）：

Sentinel 在这的作用是熔断下游（LLM/ES），防止上游请求堆积导致连锁 OOM——比堆大小更关键的保护层。

---

<a id="q6"></a>
## **Q6: Agent 稳定性做了哪些兜底？（防幻觉/循环调用/重试/兜底）**

### **防幻觉——三层机制**

从软到硬，逐层收紧：

**第一层：Prompt 硬约束**（软——靠 LLM 遵守）

每个 Agent 的 Prompt 里写死了禁止性规则：

IntentAgent（`intent_agent/prompt.py:15-18`）：

```Plain Text
**严禁行为：**
- 禁止返回JSON格式数据
- 禁止基于上下文直接生成业务回答
- 禁止提供分析、建议或解释
- 所有业务问题必须通过 transfer_to_agent 分发，无例外
```

AnalysisAgent（`analysis_agent/prompt.py:69-81`）：

```Plain Text
- 严格基于原始数据：仅使用获取的 source_data
- 禁止数据编造：不使用外部或虚构数据
- 数据获取失败(status=="error") → 立即停止 → 只返回 msg 字段内容
```

CodeAgent（`code_agent/prompt.py:27-53`）约束字段名"必须在给定的字段列表里，切勿胡编乱造"。

**第二层：后处理拦截**（硬——代码执行）

`event_packer.py:24-44` —— 检测 LLM 误输出到最终结果的内容：

```Python
WRONG_ANSWER_START_TEXTS = [
    "transfer_to_agent",
    "<tool_call>",
    "For context",
]
for start_text in WRONG_ANSWER_START_TEXTS:
    if text.startswith(start_text):
        return MSG_NOTIFICATION_RETRY  # "我似乎未能分析出最终结果..."
```

命中说明 LLM 没有遵守 Prompt 约束（把内部指令或工具调用输出到了最终结果），直接替换为兜底消息。

**第三层：最终事件校验**（硬）

`check_final_event`（`event_packer.py:377-393`）—— 最终 Event 的 text 为空 → 注入 `MSG_NOTIFICATION_RETRY`。连 Event 都没有 → `create_fake_final_event` 创建一个假的兜底 Event。

---

### **循环调用防护——四种手段**

**手段一：****`disallow_transfer_to_parent/peers`**

所有子 Agent 设置 `disallow_transfer_to_parent=True, disallow_transfer_to_peers=True`（`decision_agent/agent.py:154-155` 等），阻止子 Agent 回调父级或同级，从根本上阻断 A→B→A 循环。

**手段二：****`max_llm_calls=20`**

`multi_agent/api.py:87` —— ADK Runner 级别的硬上限。达到 20 次 LLM 调用后 Runner 抛出异常，外层 catch 捕获后返回兜底消息。

**手段三：显式工具调用（修复模型升级导致的循环 Bug）**

`analysis_agent/agent.py:86-95` —— 2025年8月模型更新后新版模型会反复调用工具形成无限循环。修复方式是在 `before_agent_callback` 提前把工具执行完，结果注入 state，不让 LLM 自主决定是否调工具：

```Python
async def _explicit_call_tool(callback_context: CallbackContext):
    """更新模型到新版本后，使用tools会不停循环
    此处在callback中将函数先执行完毕，不让agent决定是否执行工具"""
    question = get_current_question(callback_context)
    ret = await get_analysis_detail_tool(question, callback_context)
    callback_context.state[TEMP_ANALYSIS_AGENT_TOOL_RESULT] = text
```

**手段四：****`before_model_callback`**** 历史截断**

ADK executor 中将对话历史截断为 `historyRound + 1` 轮，防止上下文溢出导致 LLM 行为异常。

---

### **兜底——三层 Fallback**

**兜底消息有两个等级**（`common/msg.py`）：

```Python
MSG_NOTIFICATION_RETRY = "我似乎未能分析出最终结果，请您再试一次吧。"
MSG_NOTIFICATION_DEFAULT = "洞察助手正在成长中，您的问题有点复杂，暂时无法洞察出结论，可以联系我们进行能力探讨。"
```

`_RETRY` 用于临时失败（重试可能解决），`_DEFAULT` 用于明确无法处理的场景。

**Runner 级别兜底代码**（`multi_agent/api.py:85-113`）：

```Python
try:
    run_config = RunConfig(max_llm_calls=20)
    events = [
        repack_resp_parts(event)
        async for event in intent_runner.run_async(...)
    ]
    if events:
        check_final_event(events[-1])
    return events
except Exception as e:
    # 执行过程中出现异常（调用模型失败等），做兜底回复
    retry_event = Event(
        author=AGENT_INTENT_AGENT,
        content=Content(parts=[Part(text=MSG_NOTIFICATION_RETRY)]),
    )
    return [repack_resp_parts(retry_event)]
```

**子 Agent 内部兜底**（`strategy_view_agent/agent.py`）：

```Python
async def _run_async_impl(self, ctx):
    try:
        mcp_resp = await insightMCPTools.execute(...)
        if not mcp_resp:
            yield self._pack_retry_event(ctx)    # 失败 → 兜底文本
        else:
            yield self._pack_success_event(ctx, mcp_resp)
    except Exception as e:
        yield self._pack_retry_event(ctx)  # 异常 → 同样兜底文本
```

---

### **重试机制——三种重试，策略各不相同**

**代码生成重试的差异化逻辑**（`strategy_create_agent/agent.py:299-329`）：

```Python
for _ in range(MULTI_AGENT_GEN_CODE_RETRY_COUNT):  # 默认 = 1
    code = event.content.parts[0].text
    tmp = exec_code(data["code"], func="process_csv_data", kwargs={"dataframe": dataframe})
    if tmp["flag"]:
        return res  # 成功 → 返回
    else:
        # 失败 → 把失败信息写入 state，下一次 LLM 可以看到
        ctx.session.state[TEMP_CURRENT_PYCODE] = data["code"]
        ctx.session.state[TEMP_CURRENT_PYCODE_EXCEPTION] = tmp["msg"]
```

---

### **达到最大轮数后**

**关键发现**：AutoAgent 的 `max_llm_calls` 被注释掉了，这是一个已知但未修复的保护缺口。

---

### **整体架构总览**

```Plain Text
用户请求 → Runner (max_llm_calls=20)
            │
            ├── IntentAgent
            │   ├── Prompt 硬约束（防幻觉层1）
            │   ├── transfer_to_agent 分发
            │   └── disallow_transfer_to_parent/peers（防循环）
            │
            ├── 子 Agent
            │   ├── before_agent_callback 显式调工具（防循环，修复模型Bug）
            │   ├── try/except 内部兜底（兜底层1）
            │   ├── MSG_NOTIFICATION_RETRY / _DEFAULT（两级兜底文案）
            │   └── 代码重试 & 差异化（重试 + 差异化）
            │
            ├── Runner 级别 except（兜底层2 — 覆盖整个对话异常）
            │
            └── 后处理（event_packer）
                ├── _post_process_text 拦截错误输出（防幻觉层2）
                ├── check_final_event 空结果兜底（兜底层3）
                └── create_fake_final_event 最终保底
```

### **面试怎么讲**

> Agent 稳定性是分层的。防幻觉有三层——Prompt 硬约束、后处理拦截、最终事件校验。循环防护有四种手段——`disallow_transfer_to_parent` 阻断 A→B→A、`max_llm_calls=20` 硬上限、显式工具调用（解决2025年8月模型更新后的循环 Bug）、历史截断。兜底也是三层——子 Agent 内部、Runner 级别、Event 后处理。
> 
> 重试最有意思的是代码生成重试——失败的代码和异常会注入 state，LLM 在下一次能看见"上次为什么错"，这是唯一有差异化的重试。其他重试（LLM 调用、MCP 工具）都是相同参数的重复尝试，只针对临时故障。
> 
> 有一个值得注意的缺口：AutoAgent 的 ADK 执行器里 `max_llm_calls=20` 被注释掉了，目前没有硬性上限，如果有人问你"AutoAgent 怎么防止无限循环"，可以说这里还没做完。
> 
> 

---

<a id="q7"></a>
## **Q7: 除了父子文档分块还有什么分块方式？怎么选型的？**

### **全部分块策略一览**

PA 实现的分块策略覆盖了从纯文本到 OCR 扫描件、从表格到视频的各种场景：

### **关于"父子文档分块"**

已在 `agent-core-mechanism-qa.md:1410-1476` 中总结。PA（Ragent 架构）的父子文档是**一套 chunk \+ 元数据回表补齐**的"等价父文档"方案：

- **传统父子文档**：存两套 chunk（父级 \+ 子级），双倍 Embedding 成本，需维护父子关系

- **Ragent 等价实现**：只存一套 chunk（子级粒度），检索命中后通过 `MetadataEnrichment` 回表补齐元数据（docId/chunkIndex/docName）→ 按 docId 分组 → 按 chunkIndex 还原原文顺序 → 效果等同于取父 chunk 全文，但省去双倍存储和 Embedding 成本

AgentFlow（`af-rag-server`）侧没有显式的父子文档机制，靠 `ChatContextFilter` 上下文截断 \+ `quoteQA` 的组合达到类似效果。

### **选型决策树**

```Plain Text
文档来源
│
├── 扫描件 / 拍照图片 / 不可选中的 PDF
│   └── OcrChunker
│       ├── BlockMerge：跨页表格合并 + 页眉页脚过滤
│       ├── TXTSplitter：按段落切分（512 token，50 overlap）
│       └── Tabular 子管线：检测到表格结构时启用
│
├── 纯文本 / 可选中 PDF / Markdown
│   ├── 有明确结构（Markdown、代码）→ SeparatorRecursiveSplitter
│   │   （按优先级递归：空行→换行→句号→分号→逗号）
│   ├── 固定分隔符格式 → SeparatorSplitter
│   └── 无特殊结构 → GeneralSplitter（TokenTextSplitter）
│       （chunk_size=1024，最通用的兜底方案）
│
├── 表格数据（从 OCR 或其他来源提取）
│   ├── 扁平单行表头 → TabularRowSplitter（每行一个 chunk）
│   ├── 多层嵌套表头 → TabularMultiLevelHeaderSpliter
│   │   （保留层级关系，chunk_size=5000）
│   └── 知识图谱构建 → TabularKnowledgeExtractor
│       （提取 entity-attribute-value 三元组）
│
├── Excel 文件
│   └── TableChunk（逐 cell 按坐标提取）
│
├── 视频
│   └── VideoASRSplitter（按 ASR 时间窗口分组）
│
└── 医保/法规文档
    └── IntelliMedicalInsuranceSplitter（按章节层级）
```

### **实际调度的入口**

分块策略并非由用户直接选择，而是由 `rag_flow.py` 中的 `chunk_splitting()` 方法根据文件扩展名和其他属性自动调度：

```Python
# 伪代码逻辑
if doc_extension in (pdf, png, jpg, jpeg):
    → OcrChunker（走 OCR 管线）
else:
    → GeneralSplitter / SeparatorSplitter 等
```

### **检索后处理**

无论用哪种分块策略，检索后都要经过融合和重排：

1. **ChunkMerge**（`postprocess/chunk_process.py`）—— 加权多路召回融合，max\_chunks=10

2. **ReRank**（同上）—— Reranker API 重排，similarity\_cutoff=0\.5，保留 topK

3. **RRF 融合**（Java 侧 `DatasetConcatServiceImpl`）—— `1/(k+rank)` 评分，兜底方案

### **选型核心原则**

1. **不离谱原则**：chunk 不能太大（超出模型 context 窗口），不能太小（失去语义完整性）。PA 的 chunk\_size 集中在 500\-1024，层级标题类放宽到 5000

2. **结构感知优先**：有结构（标题、层级、表格）就用结构拆分，无结构才用纯 token 切割。结构拆分能让每个 chunk 语义自包含

3. **表格特殊对待**：表格是 RAG 最难的场景之一——逐行拆分\+层级标题保留，是为了保证行级检索时能带回列名和上下文

4. **OCR 管线是组合式**：OCR 不是一种分块策略，而是一套管线（合并→文本提取→表格识别），最终分块还是落到 TXT 或 Tabular 策略上

5. **前处理决定上限**：PA 在分块前做大量前处理（BlockMerge 跨页合并、页眉页脚过滤、段落合并），这些决定了分块的初始质量

### **面试怎么讲**

> PA 的分块策略是"分类治理"的思路。扫描件走 OCR 管线（合并→布局感知→表格识别），普通文本走递归分隔符或 Token 切割，表格按行或层级标题拆分，视频按 ASR 时间窗口分组。
> 
> 选型的核心是**结构感知**：有结构的用结构拆，保证语义完整；无结构的用 Token 拆，保证长度可控。表格是最难的场景，所以单独做了行拆分和层级标题保留。整体策略是"先分类、再分块、最后融合"。
> 
> 另外，PA 的父子文档是"等价父文档"方案——只存一套 chunk，检索后通过元数据回表补齐上下文，省去双倍 Embedding 成本。具体实现见 `agent-core-mechanism-qa.md:1410-1476`。
> 
> 



