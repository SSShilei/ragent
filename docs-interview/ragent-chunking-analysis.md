# Ragent Chunking 深度解析

> 基于源码级分析。覆盖 Parser 矩阵、Block 模型、block-aware 分块流程、双文本嵌入、与 AgentFlow 对比、六种业界策略评估、完整案例。

---

## 一、Ragent vs AgentFlow 的 Chunking 对比

### 1.1 Ragent（Java）— 两层架构，结构感知优先

入口是 `StructuredChunkingService.chunk()`（`StructuredChunkingService.java:87`），三路分发：

```
blocks 非空 → block-aware 分发（MinerU/Tika 解析的强类型 Block）
blocks 为空 → legacy 文本策略（FIXED_SIZE 或 STRUCTURE_AWARE）
chunkSize=-1 → 整篇文档一个 chunk
```

**核心路径：block-aware 分发**

Parser（MinerU/Tika）先产出 6 种强类型 Block，`BlockAwareChunkerDispatcher` 逐个分发：

| Block 类型 | Chunker | 策略 |
|:---|:---|:---|
| HeadingBlock | HeadingHandler | 不产 chunk，累积 outlinePath（如 `["第3章", "3.2 销售分析"]`）注入后续 chunk |
| ParagraphBlock | ParagraphChunker | 按 token 切分，不跨 heading 边界 |
| **TableBlock** | **TableChunker** | **双文本设计**：`content`=markdown 表格给 LLM 看，`embeddingText`=key-value（`姓名:张三;年龄:25`）给 Embedding 用；每个 chunk 带完整表头；`sectionContext` 写 sheet 名+表头摘要 |
| ImageBlock | ImageChunker | 原子块，渲染 `![caption](url)` |
| CodeBlock | CodeChunker | 原子块（代码切碎危害大） |
| ListBlock | ListChunker | 短列表 atomic，长列表按项分组 |

分发后 **ChunkPacker** 做贪心合并：PARAGRAPH/LIST/IMAGE 相邻小块拼到接近 maxChars 预算，TABLE/CODE 保持原子；断块处用块级重叠衔接上下文。

**降级路径：legacy 文本策略**
- **FIXED_SIZE**：按字符滑动窗口 + overlap，边界对齐到换行→中文标点→英文句末（仅后跟空白才认，防切碎 URL）
- **STRUCTURE_AWARE**：Markdown 友好，扫描 Heading/CodeFence/Atomic/Paragraph 块，在块边界切分，min/target/max 预算控制

**VectorChunk 元数据**：`outlinePath`、`blockType`、`sectionContext`、`sourceBlockIds`、`assets`、`embeddingText`（独立于 content）

### 1.2 AgentFlow/PowerAgent（Python）— 扩展名路由，分类治理

入口是 `rag_flow.py` → `chunk_splitting()`，按文件扩展名自动调度（`RAG 文档处理与 OCR 面试 Q&A 0728.md:736`）：

```
扫描件(pdf/png/jpg) → OcrChunker
  ├── BlockMerge：跨页表格合并 + 页眉页脚过滤
  ├── TXTSplitter：按段落切（512 token, 50 overlap）
  └── Tabular 子管线

纯文本/Markdown
  ├── 有结构 → SeparatorRecursiveSplitter（递归分隔符：空行→换行→句号→分号→逗号）
  ├── 固定分隔符 → SeparatorSplitter
  └── 兜底 → GeneralSplitter（TokenTextSplitter, chunk_size=1024, overlap=200）

表格 → TabularRowSplitter / TabularMultiLevelHeaderSplitter
Excel → TableChunk（逐 cell）
视频 → VideoASRSplitter（ASR 时间窗口）
医保 → IntelliMedicalInsuranceSplitter（章节层级）
```

AgentFlow 的分块策略覆盖了从纯文本到 OCR 扫描件、从表格到视频的各种场景，是"分类治理"思路。

### 1.3 关键差异总结

| 维度 | Ragent | AgentFlow |
|:---|:---|:---|
| **表格处理** | **双文本**（markdown 展示 + key-value 嵌入），这是最大亮点 | 逐行拆分+层级标题保留，无双文本 |
| **解析器** | MinerU/Tika → 强类型 Block 体系 | LlamaIndex → 灵活但弱类型 |
| **Chunk 元数据** | 6 个富字段（outlinePath/blockType/sectionContext 等） | 靠 ES 索引字段 + association_info |
| **分块后处理** | ChunkPacker 贪心合并 + MetadataEnrichment 回表补齐 | ChunkMerge 多路融合 + ReRank |
| **默认大小** | block-aware 可配，FIXED_SIZE=512，STRUCTURE_AWARE target=1400 | GeneralSplitter=1024，TXTSplitter=512，表格=5000 |
| **父子文档** | 一套 chunk + MetadataEnrichment 回表还原顺序（等价父文档） | 无显式机制，靠 ChatContextFilter+quoteQA 弥补 |
| **分块调度** | 按 Block 类型路由 | 按文件扩展名路由 |

> Ragent 的 chunking 强在"结构感知深度"——表格的双文本嵌入、outlinePath 注入、ChunkPacker 合并都是精心设计的；AgentFlow 的 chunking 强在"覆盖面广度"——从 OCR 到视频到医保，场景覆盖更全，但每个策略的深度不如 Ragent。

---

## 二、Parser 矩阵：6 个解析器 × 支持的具体格式

### 2.1 ParserType 枚举

```java
// ParserType.java — 6 个解析器类型
TIKA("Tika")           // Apache Tika，处理纯文本
MARKDOWN("Markdown")    // commonmark-java，处理 Markdown
EXCEL_POI("ExcelPoi")   // Apache POI，处理 Excel
CSV("Csv")              // RFC4180 解析，处理 CSV
MINERU("MinerU")        // MinerU SaaS API，处理 PDF/Word/PPT
IMAGE("Image")          // VLM 图生文，处理 PNG/JPG/SVG
```

### 2.2 路由机制与优先级

`DocumentParserSelector.selectByMimeType()` 按 Spring `@Order` 排序，返回第一个支持该 MIME 的解析器：

| Parser | @Order | 支持的 MIME 类型 | 输出 Block |
|:---|:---|:---|:---|
| **MinerU** | HIGHEST | `application/pdf`, `*wordprocessingml*`, `*msword*`, `*presentationml*`, `*powerpoint*`（**不含 Excel，Excel 默认走 POI**） | 全部 6 种 |
| **ExcelPoi** | +10 | `*spreadsheet*`, `*ms-excel*`, `*excel*` | TableBlock |
| **Csv** | +15 | `text/csv`, `application/csv`, `text/comma-separated-values` | TableBlock |
| **Markdown** | +20 | `text/markdown`, `text/x-markdown`, `text/plain` | 除 ImageBlock 外 5 种 |
| **Image** | +30 | `image/png`, `image/jpg`, `image/jpeg`, `image/svg+xml` | ImageBlock |
| **Tika** | LOWEST | **仅** `text/*`, `application/json`, `application/xml`, `application/xhtml+xml`, `application/rtf`（v1.1 已收紧，不再处理 PDF/Word） | 仅 ParagraphBlock |

Tika v1.1 收紧的关键变化：

```java
// TikaDocumentParser.supports() — 明确排除了 PDF/Word/Excel/Markdown/CSV
if (lower.startsWith("text/markdown")) return false;   // 交给 Markdown
if (lower.equals("text/csv")) return false;             // 交给 Csv
// 仅接受 text/* 与 application/json|xml 等纯文本类型
// PDF/Word/PPT 已由 MinerU 接管
```

### 2.3 每种 Block 的生产者矩阵

```
                    HeadingBlock  ParagraphBlock  TableBlock  ImageBlock  CodeBlock  ListBlock
MinerUDocumentParser     ✅            ✅             ✅          ✅          ✅         ✅
MarkdownDocumentParser   ✅            ✅             ✅          ✗          ✅         ✅
TikaDocumentParser       ✗            ✅             ✗          ✗          ✗         ✗
ExcelDocumentParser      ✗            ✗              ✅          ✗          ✗         ✗
CsvDocumentParser        ✗            ✗              ✅          ✗          ✗         ✗
ImageDocumentParser      ✗            ✗              ✗           ✅          ✗         ✗
```

**MinerU 是唯一覆盖全部 6 种 Block 的解析器，但注意：MinerU 不直接输出 Block。**

### 2.4 MinerU 的六步异步解析流程

`MinerUDocumentParser.parseStructured()` 的实际执行链路（`MinerUDocumentParser.java:126`）：

```
1. Redisson 分布式信号量获取许可（限制跨实例并发解析数）
2. MinerUClient.requestUpload() — 申请上传链接，拿 batchId + 上传 URL
3. MinerUClient.uploadFile() — 把源文件字节 PUT 到 MinerU OSS
4. MinerUPollingExecutor.submitAndAwait() — 阻塞轮询等待解析完成
5. MinerUClient.downloadZip() — 下载结果 zip
6. MinerUResultUnpacker.unpack() — 解包为 Block 列表（图片自动上传 RustFS）
```

**关键参数**（`BatchSubmitRequest`）：`ocr`、`enableTable`、`enableFormula`、`language`。

### 2.5 MinerU 不是直接输出 Block——两层分工架构

MinerU API 返回的是一个 **zip 文件**，里面只有 `{markdown内容, 图片文件}`。Ragent 拿到后的处理链：

```
MinerU zip → 解包得到 {markdown内容, 图片文件}
  → 上传图片到 RustFS，拿公开 URL
  → MinerUResultUnpacker 把 markdown 喂给 commonmark-java 解析 AST
  → UnpackVisitor 遍历 AST，产生 Block 列表
```

真正的两层分工：

```
┌─────────────────────────────────────────────────┐
│  第一层：文档 → 可解析的中间格式                  │
│                                                 │
│  MinerU    PDF/Word/PPT  → 高质量 markdown        │
│  Tika      纯文本       → 平文本                  │
│  POI       Excel       → NormalizedTable(DTO)    │
│  commonmark .md         → AST (直接)             │
│  VLM       图片        → 中文描述 + OCR           │
│  CSV       逗号分隔     → RFC4180 解析            │
└──────────────────────┬──────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│  第二层：可解析格式 → 统一 Block IR               │
│                                                 │
│  commonmark AST  → Heading/Paragraph/Table/      │
│  (MinerU路径+        Code/List/Image Block        │
│   Markdown路径                                    │
│   共用同一套代码)                                  │
│                                                 │
│  POI NormalizedTable → TableBlock                │
│  CSV RFC4180 解析     → TableBlock                │
│  VLM 图生文          → ImageBlock                 │
│  Tika 平文本          → ParagraphBlock (only)     │
└─────────────────────────────────────────────────┘
```

**MinerU 解决的是"PDF/Word/PPT 这种二进制格式 → 结构化 markdown"的问题，markdown → 6 种 Block 是 Ragent 自己的代码。没有 MinerU，Ragent 也能从 Markdown 文件产出 6 种 Block（通过 MarkdownDocumentParser）；但没有 MinerU，PDF/Word/PPT 就只能走 Tika 降级为 ParagraphBlock 平文本，丢失全部结构信息。**

### 2.6 commonmark-java AST 解析器

`commonmark-java` 是一个开源 Markdown 解析器。Ragent 用它做第二层转换：markdown 字符串 → AST 树 → Block 列表。

```java
// 两个解析器共用同一配置
private static final Parser PARSER = Parser.builder()
        .extensions(List.of(TablesExtension.create()))  // 开 GFM 表格扩展
        .build();
```

输入 Markdown → AST → Block 的映射：

```
# 标题           → Heading(level=1)    → HeadingBlock
普通段落         → Paragraph           → ParagraphBlock
| 列1 | 列2 |    → GFM TableBlock      → TableBlock (headers + rows)
- 项目列表       → BulletList          → ListBlock
1. 有序列表      → OrderedList         → ListBlock(ordered=true)
```java          → FencedCodeBlock     → CodeBlock
![](img.png)     → Image               → ImageBlock
```

遍历过程（`MarkdownDocumentParser.BlockExtractingVisitor`，行 133-281）：

```java
public void visit(Heading heading) {
    blocks.add(new HeadingBlock(..., heading.getLevel(), extractInlineText(heading)));
}
public void visit(Paragraph paragraph) {
    blocks.add(new ParagraphBlock(..., extractInlineText(paragraph)));
}
public void visit(CustomBlock customBlock) {
    if (customBlock instanceof TableBlock tableBlock) {
        handleTable(tableBlock);  // 提取 headers + rows → TableBlock
    }
}
```

**MinerU 路径和 Markdown 路径共用同一套 commonmark AST → Block 的代码**（`MinerUResultUnpacker.UnpackVisitor` 和 `MarkdownDocumentParser.BlockExtractingVisitor`），差异仅在于：
- MinerU 路径多了"段首 Image 提升为独立 ImageBlock + RustFS URL 替换"
- MinerU 路径有 `visit(HtmlBlock)` 处理 HTML 表格
- Markdown 路径的 `ImageBlock` 无 RustFS AssetRef

### 2.7 各 Parser 的 Block 生产细节

**TikaDocumentParser**（`TikaDocumentParser.java:68`）：
```java
// 只按 \n\n+ 空行分段，输出 ParagraphBlock 列表
// 复杂版面文档(PDF/Word/PPT)应路由到 MinerU，不走 Tika
for (String segment : text.split("\\n{2,}")) {
    blocks.add(new ParagraphBlock(UUID..., prov, List.of(), segment.strip()));
}
```

**ExcelDocumentParser**（`ExcelDocumentParser.java:44`）：用 `ExcelTableNormalizer` 处理合并单元格展开填充、多行表头展平拼接（如 `"财务|收入"`）、超链接 cell 内联为 `[text](url)`、公式 cell 求值+回退缓存值。每个 Sheet 产出一个 `TableBlock`。

**CsvDocumentParser**（`CsvDocumentParser.java:39`）：Tika `AutoDetectReader` 自动探测字符集（UTF-8/GBK/UTF-16）+ 剥离 BOM；RFC4180 解析，支持引号包裹字段、字段内逗号/换行、`""` 转义；首行为表头，其余为数据行，产出单个 `TableBlock`。

**ImageDocumentParser**（`ImageDocumentParser.java:46`）：SVG 先用 Batik 渲染成 PNG；然后用 VLM 把图片转成"中文描述 + 图中文字 OCR"作为 `description`，同时原图上传 asset-bucket 供答复展示。产出单个 `ImageBlock`，`description` 进 embedding 负责召回。

---

## 三、Block 模型详解

### 3.1 公共基类：sealed interface Block

```java
// Block.java — 编译期穷举所有子类型
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = HeadingBlock.class, name = "heading"),
    @JsonSubTypes.Type(value = ParagraphBlock.class, name = "paragraph"),
    @JsonSubTypes.Type(value = TableBlock.class, name = "table"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = CodeBlock.class, name = "code"),
    @JsonSubTypes.Type(value = ListBlock.class, name = "list")
})
public sealed interface Block permits HeadingBlock, ParagraphBlock, TableBlock,
                                      ImageBlock, CodeBlock, ListBlock {
    String id();                    // UUID，唯一标识
    Provenance provenance();        // 来源：文件 + sheet 名
    List<String> outlinePath();     // 章节层级路径，解析阶段为空
}
```

**设计意图**：
- `sealed interface` 保证编译期穷举，新增 Block 类型时所有 switch 必须显式处理
- 每个子类强类型字段，告别 `Map<String,Object>` 垃圾桶
- `id()` 提供唯一标识，供 `AssetRef.sourceBlockId` 与资产 key 规则引用
- markdown 不在 Block 上，chunker 渲染时按需生成——**Block 是 IR 不是终态**

### 3.2 Provenance（溯源信息）

```java
public record Provenance(String sourceFile, String sheetName) {
    public static Provenance ofFile(String sourceFile) {
        return new Provenance(sourceFile, null);
    }
    public static Provenance ofExcelCell(String sourceFile, String sheetName) {
        return new Provenance(sourceFile, sheetName);
    }
}
```

### 3.3 六种 Block 详细结构

```
Block (sealed interface)
├── id: String              — UUID，唯一标识
├── provenance: Provenance  — sourceFile + sheetName(Excel专用)
├── outlinePath: List<String> — 解析阶段为空，ChunkerNode 阶段 HeadingHandler 注入
│
├── HeadingBlock
│   ├── level: int          — 1-6（对应 # ~ ######）
│   └── text: String        — 标题文本
│
├── ParagraphBlock
│   └── text: String        — 段落文本
│
├── TableBlock
│   ├── headers: List<String> — 列名（多行表头已展平，分隔符拼接如 "财务|收入"）
│   ├── rows: List<List<String>> — 数据行（合并单元格已展开填充）
│   └── captionText: String — 表格标题（可空）
│
├── ImageBlock
│   ├── asset: AssetRef     — RustFS 上的图片 URL + MIME + sourceBlockId
│   ├── caption: String     — 图片标题（如 "图3-1:系统架构图"）
│   ├── altText: String     — 无障碍替代文本
│   └── description: String — VLM 图生文：自包含知识文本（说明图是什么 + 完整OCR）
│                             MinerU/Tika 不产此字段为 null
│
├── CodeBlock
│   ├── language: String    — 编程语言（如 "java"、"bash"），可空
│   └── code: String        — 代码内容
│
└── ListBlock
    ├── ordered: boolean    — 有序/无序
    └── items: List<String> — 列表项内容
```

JSON 序列化示例：

```json
{
  "@type": "table",
  "id": "a1b2c3d4-...",
  "provenance": {"sourceFile": "员工手册.md", "sheetName": null},
  "outlinePath": [],
  "headers": ["级别", "基本工资", "绩效系数"],
  "rows": [["P6", "15000", "1.2"], ["P7", "22000", "1.5"]],
  "captionText": null
}
```

### 3.4 ParsedDocument 和 ParseResult

```java
// 解析器统一输出：有序 Block 列表 + 文档级元数据
public record ParsedDocument(List<Block> blocks, Map<String, Object> metadata) {}

// 老的平文本解析结果（向后兼容）
public record ParseResult(String text, Map<String, Object> metadata) {}
```

---

## 四、Block-Aware 分块流程详解

### 4.1 StructuredChunkingService 入口

```java
// StructuredChunkingService.java:87
public List<VectorChunk> chunk(List<Block> blocks, String fallbackText,
                               ChunkingMode mode, ChunkingOptions options,
                               Integer rowsPerChunk) {
    // 不分块（chunkSize=-1）：整篇合成单个 chunk
    if (isWholeDocument(options)) {
        return wholeDocumentChunk(blocks, fallbackText);
    }
    // blocks 非空 → block-aware 分发
    if (blocks != null && !blocks.isEmpty()) {
        return blockAwareChunkerDispatcher.dispatch(blocks, toBlockChunkConfig(options, rowsPerChunk));
    }
    // blocks 为空 → legacy 文本策略
    if (!StringUtils.hasText(fallbackText)) return List.of();
    return chunkingStrategyFactory.requireStrategy(mode).chunk(fallbackText, options);
}
```

三路判断的优先级：不分块哨兵 > block-aware > legacy 文本策略。

### 4.2 BlockAwareChunkerDispatcher 分发机制

`BlockAwareChunkerDispatcher.dispatch()`（`BlockAwareChunkerDispatcher.java:62`）：

```java
for (Block b : blocks) {
    if (b instanceof HeadingBlock h) {
        outlinePath = headingHandler.update(outlinePath, h);  // 不产 chunk
        continue;
    }
    ChunkContext ctx = ChunkContext.of(outlinePath, config, chunkIndex);
    List<VectorChunk> chunks = chunkOne(b, ctx);
    result.addAll(chunks);
    chunkIndex += chunks.size();
}
// 后处理：贪心打包
return chunkPacker.pack(result, config.maxChars(), config.overlapChars());
```

`chunkOne()` 的 instanceof 分发链：

```java
private List<VectorChunk> chunkOne(Block b, ChunkContext ctx) {
    if (b instanceof ParagraphBlock p) return paragraphChunker.chunk(p, ctx);
    if (b instanceof TableBlock t)     return tableChunker.chunk(t, ctx);
    if (b instanceof ImageBlock i)     return imageChunker.chunk(i, ctx);
    if (b instanceof CodeBlock c)      return codeChunker.chunk(c, ctx);
    if (b instanceof ListBlock l)      return listChunker.chunk(l, ctx);
    throw new IllegalStateException("Unsupported Block type");
}
```

### 4.3 HeadingHandler — 不产 chunk，只累积路径

HeadingHandler 维护一个按标题级别更新的路径列表：

```
输入: # 员工手册                 → outlinePath = ["员工手册"]
输入: ## 第一章 入职流程         → outlinePath = ["员工手册", "第一章 入职流程"]
输入: ### 1.1 准备材料           → outlinePath = ["员工手册", "第一章 入职流程", "1.1 准备材料"]
输入: ## 第二章 考勤制度         → outlinePath = ["员工手册", "第二章 考勤制度"]
```

**不产 chunk 的设计理由**：标题是结构信息，不是可检索的知识。把标题文本作为单独 chunk 入向量库毫无意义——没有人会搜"第一章 入职流程"。正确的是把标题注入后续 chunk 的 `outlinePath`，让 LLM 看到命中 chunk 时知道它属于哪个章节。

### 4.4 TableChunker：双文本嵌入（Ragent 最大亮点）

`TableChunker.java:42-86`。每个 chunk 包含三种文本：

```java
// content：markdown 表格 → 给 LLM 展示
content = """
| 级别 | 基本工资 | 绩效系数 |
|---|---|---|
| P6 | 15000 | 1.2 |
| P7 | 22000 | 1.5 |"""

// embeddingText：key-value 格式 → 给 Embedding 向量化
embeddingText = """
headers=级别, 基本工资, 绩效系数
级别: P6; 基本工资: 15000; 绩效系数: 1.2
级别: P7; 基本工资: 22000; 绩效系数: 1.5"""

// sectionContext：表身份，随每块嵌入 = contextual chunking
sectionContext = "headers=级别, 基本工资, 绩效系数"
```

**为什么需要双文本**：markdown 表格的列名↔值靠位置对齐，embedding 模型读不懂位置。对比：

```
"| P6 | 15000 | 1.2 |"                       ← embedding 看到一坨乱码
"级别: P6; 基本工资: 15000; 绩效系数: 1.2"     ← embedding 看到清晰的属性-值关系
```

key-value 把语义关系写进字面，sparse/dense 检索均更优（参考 RAGFlow、STC 的做法）。

**切分规则**：
- 按 key-value 行渲染长度累加到 maxChars 预算
- `rowsPerChunk` 仅作硬上限（默认 50）
- 单行体量超预算时保持整行原子，自成一块
- 每个 chunk 都包含完整表头（数据行切到哪个 chunk，表头就跟到哪个 chunk）

### 4.5 CodeChunker — 原子保护

```java
// CodeChunker: 代码切碎危害大，永不切分
// 一个 FencedCodeBlock 或 IndentedCodeBlock → 一个 VectorChunk
```

### 4.6 ListChunker — 自适应

```
短列表（items < maxListItems=15） → atomic，整个列表一个 chunk
长列表（items >= 15）            → 按 itemsPerChunk=10 分组
```

### 4.7 ImageChunker — 原子保护 + 可选图生文

```java
// 渲染为 ![caption](rustfsUrl)
// embeddingText 优先用 description（VLM 图生文），无 description 则用 content
// 原子块：切碎 markdown 图片链接会导致前端渲染失败
```

### 4.8 ChunkPacker — 贪心合并与块级重叠

`ChunkPacker.java:40-243`。各 chunker 只负责"单个 block 内"的切分，天然是"只拆不并"。

**合并规则**：

```
MERGEABLE_TYPES = {"PARAGRAPH", "LIST", "IMAGE"}  // 可流动块
TABLE / CODE → 原子块，不参与合并，且断开合并链

贪心策略：
  buffer 累加相邻的可合并块，直到加入下一块会超 maxChars
  → flush 缓冲区（合并为一个 chunk）
  → 从缓冲区尾部取完整块作为下一轮的重叠前缀
```

**块级重叠**（非字符级）：

```java
// overlapTail: 取缓冲区尾部若干完整块，累计不超过 overlapChars 预算
// 保证跨块上下文连续，但不切碎段落/列表项/标题
private static List<VectorChunk> overlapTail(List<VectorChunk> buffer, int budget) {
    // 从后往前累加完整的 MERGEABLE 块
}
```

**合并时元数据合并**（`merge()` 方法）：
- `content` 按 `\n\n` 拼接
- `embeddingText` 逐块拼接（保留图片的描述文本）
- `outlinePath` 取最长公共前缀（跨了多个 heading 的块回退到共同上级）
- `blockType` 同质保留，异质归为 `PARAGRAPH`
- `sourceBlockIds` 去重并集
- `sectionContext` 取首个非空值

---

## 五、Legacy 文本策略（blocks 为空时的降级路径）

### 5.1 FIXED_SIZE — FixedSizeTextChunker

`FixedSizeTextChunker.java:43-324`。不是简单的按字符数切——做了三层优化：

**① normalizeText() 预处理**：

```
修复 URL 被换行拆开:    dingtalk.\ncom  →  dingtalk.com
修复中文词中间软换行:    商\n保通       →  商保通
保护列表项不被误吞:      \n2. xxx       →  不合并（检测数字+./））
保护空行段落边界:        ≥2 连续换行    →  不合并（段落分隔符）
```

**② adjustToBoundary() 边界对齐**（向前 lookup 不超过 overlap）：

```
1. 换行符 \n           → 优先在段落边界切
2. 中文标点 。！？      → 次优在句末切
3. 英文标点 .!?（仅后跟空白才认）→ 避免切碎 URL 域名
```

**③ 强制推进防止死循环**：如果 `end <= lastEnd`（回退过头），直接跳到 `targetEnd`。

### 5.2 STRUCTURE_AWARE — StructureAwareTextChunker

`StructureAwareTextChunker.java:44-327`。Markdown 友好的结构感知降级方案。

**三步流程**：

```
1. segmentToBlocks(text) — 线性扫描，识别 4 种逻辑块：
   HEADING  → /^#{1,6}\s+.*$/   → 独立块（自动成为边界）
   CODE     → 围栏 ``` 隔离      → 独立块
   ATOMIC   → 图片/链接行        → 独立块（切碎渲染就崩）
   PARA     → 空行分隔的段落     → 多行合并

2. packBlocksToChunks(blocks, min, target, max) — 仅在块边界切分：
   贪心累加块直到接近 target，不超过 max
   若当前 chunk 不足 min 且下一块能加则"忍一次超限"

3. materialize(text, ranges, overlap) — 物化为 VectorChunk
   可选的 overlap：复制上一 chunk 尾部子串到下一 chunk 开头
```

**与递归字符切分的本质差异**：递归字符的问题是"能找到分隔符但不知道分隔符的意义"。`\n\n` 可能是段落边界也可能是表格内的空行。STRUCTURE_AWARE 先分类再分块，避免了这个问题。

### 5.3 ChunkingMode 枚举与配置

```java
public enum ChunkingMode {
    FIXED_SIZE("fixed_size")        // chunkSize=512, overlapSize=128
    STRUCTURE_AWARE("structure_aware") // targetChars=1400, maxChars=1800, minChars=600, overlapChars=0
}
```

配置通过 `sealed interface ChunkingOptions` 传递（策略模式），两个子类：
- `FixedSizeOptions(chunkSize, overlapSize)`
- `TextBoundaryOptions(targetChars, overlapChars, maxChars, minChars)`

---

## 六、VectorChunk 元数据字段

`VectorChunk.java:43-118`。比 LangChain Document 的 metadata 更结构化：

| 字段 | 类型 | 用途 |
|:---|:---|:---|
| `chunkId` | String | Snowflake 雪花 ID |
| `index` | Integer | 文档内序号，从 0 递增 |
| `content` | String | 展示 + 回填 LLM 上下文（表格为 markdown） |
| `embeddingText` | String | 嵌入专用，表格用 key-value；`@JsonIgnore` 不持久化 |
| `blockType` | String | PARAGRAPH / TABLE / IMAGE / CODE / LIST / HEADING |
| `outlinePath` | List\<String\> | 章节路径，如 `["第3章", "3.2 销售分析"]` |
| `sectionContext` | String | 表格 sheet 名 + 表头摘要，检索时拼入 LLM 上下文 |
| `sourceBlockIds` | List\<String\> | 来源 Block.id，精确溯源 |
| `assets` | List\<AssetRef\> | 图片引用（URL + MIME + sourceBlockId），检索时注入 |
| `metadata` | Map\<String,Object\> | 扩展元数据 |
| `embedding` | float[] | 向量嵌入，`@JsonIgnore` 不序列化 |

---

## 七、完整案例：一份 Markdown 文件从解析到入库

输入 `员工手册.md`：

```markdown
# 员工手册

## 第一章 入职流程

新员工入职需要完成以下步骤。

### 1.1 准备材料

请准备以下材料：

- 身份证原件及复印件
- 学历证书复印件
- 一寸照片 2 张

### 1.2 薪资结构

| 级别 | 基本工资 | 绩效系数 |
|------|---------|---------|
| P6   | 15000   | 1.2     |
| P7   | 22000   | 1.5     |
| P8   | 32000   | 2.0     |

新人第一年按80%发放。

## 第二章 考勤制度

考勤规则如下：

1. 工作日 9:00-18:00
2. 迟到 30 分钟内不扣薪
3. 每月 3 次免打卡机会
```

### 阶段 1：MarkdownDocumentParser → ParsedDocument

commonmark-java 解析 AST，`BlockExtractingVisitor` 遍历产出 12 个 Block：

```json
{
  "blocks": [
    {"@type":"heading", "level":1, "text":"员工手册"},
    {"@type":"heading", "level":2, "text":"第一章 入职流程"},
    {"@type":"paragraph", "text":"新员工入职需要完成以下步骤。"},
    {"@type":"heading", "level":3, "text":"1.1 准备材料"},
    {"@type":"paragraph", "text":"请准备以下材料："},
    {"@type":"list", "ordered":false, "items":["身份证原件及复印件","学历证书复印件","一寸照片 2 张"]},
    {"@type":"heading", "level":3, "text":"1.2 薪资结构"},
    {"@type":"table", "headers":["级别","基本工资","绩效系数"], "rows":[["P6","15000","1.2"],["P7","22000","1.5"],["P8","32000","2.0"]]},
    {"@type":"paragraph", "text":"新人第一年按80%发放。"},
    {"@type":"heading", "level":2, "text":"第二章 考勤制度"},
    {"@type":"paragraph", "text":"考勤规则如下："},
    {"@type":"list", "ordered":true, "items":["工作日 9:00-18:00","迟到 30 分钟内不扣薪","每月 3 次免打卡机会"]}
  ],
  "metadata": {"parser":"Markdown","mimeType":"text/markdown","blocks":12}
}
```

### 阶段 2：StructuredChunkingService → BlockAwareChunkerDispatcher 分发

```
Block[0] HeadingBlock("员工手册", level=1)
  → HeadingHandler.update → outlinePath = ["员工手册"]

Block[1] HeadingBlock("第一章 入职流程", level=2)
  → outlinePath = ["员工手册", "第一章 入职流程"]

Block[2] ParagraphBlock("新员工入职需要完成以下步骤。")
  → ParagraphChunker → VectorChunk{index=0, content="新员工入职...", outlinePath=["员工手册","第一章 入职流程"]}

Block[3] HeadingBlock("1.1 准备材料", level=3)
  → outlinePath = ["员工手册", "第一章 入职流程", "1.1 准备材料"]

Block[4] ParagraphBlock("请准备以下材料：")
  → VectorChunk{index=1, content="请准备以下材料：", outlinePath=[..., "1.1 准备材料"]}

Block[5] ListBlock(items=3)
  → ListChunker: 3 < maxListItems(15) → atomic
  → VectorChunk{index=2, content="- 身份证原件...\n- 学历证书...\n- 一寸照片...", blockType="LIST"}

Block[6] HeadingBlock("1.2 薪资结构", level=3)
  → outlinePath = ["员工手册", "第一章 入职流程", "1.2 薪资结构"]

Block[7] TableBlock(headers=["级别","基本工资","绩效系数"], rows=[3行])
  → TableChunker: 双文本
  → VectorChunk{index=3,
      content="| 级别 | 基本工资 | 绩效系数 |\n|---|---|---|\n| P6 | 15000 | 1.2 |\n| P7 | 22000 | 1.5 |\n| P8 | 32000 | 2.0 |",
      embeddingText="headers=级别, 基本工资, 绩效系数\n级别: P6; 基本工资: 15000; 绩效系数: 1.2\n...",
      blockType="TABLE", outlinePath=[..., "1.2 薪资结构"]}

Block[8] ParagraphBlock("新人第一年按80%发放。")
  → VectorChunk{index=4, content="新人第一年按80%发放。"}

Block[9] HeadingBlock("第二章 考勤制度", level=2)
  → outlinePath = ["员工手册", "第二章 考勤制度"]

Block[10] ParagraphBlock("考勤规则如下：")
  → VectorChunk{index=5, ...}

Block[11] ListBlock(ordered=true, items=3)
  → VectorChunk{index=6, content="1. 工作日 9:00-18:00\n2. 迟到 30 分钟内不扣薪\n3. 每月 3 次免打卡机会", blockType="LIST"}
```

### 阶段 3：ChunkPacker 贪心合并

```
合并前: [P0]=28字  [P1]=10字  [L2]=45字  [T3]=表格原子  [P4]=12字  [P5]=7字  [L6]=55字

假设 maxChars=512:
  buffer: [P0] 28字 → +P1=38字 → +L2=83字 → 继续
  遇到 T3(表格,非MERGEABLE) → flush([P0,P1,L2]) → 合并块1 {"员工手册", "第一章 入职流程"}
  T3 原样保留 → 合并块2
  buffer: [P4] 12字 → +P5=19字 → +L6=74字 → flush → 合并块3 {"员工手册", "第二章 考勤制度"}

合并后: 3 个 VectorChunk → ChunkEmbeddingService
```

### 阶段 4：ChunkEmbeddingService → 向量库

- T3（表格）用 `embeddingText`（key-value）调 Embedding 模型
- 其他 chunk 用 `content`（markdown）调 Embedding 模型
- 写入 Milvus/PGVector

---

## 八、Parent-Child 等价方案（零额外成本）

```
传统 Parent-Child：
  父 chunk(大) ──→ Embedding ① ──→ 向量库
  子 chunk(小) ──→ Embedding ② ──→ 向量库
  检索命中子 chunk → 关联查询父 chunk → 喂 LLM
  成本: 2x Embedding + 2x 存储 + 1x 关联查询

Ragent 等价方案：
  只有一套 chunk(正常粒度) ──→ Embedding ──→ 向量库
  检索命中 chunk → MetadataEnrichment 回表
  → 按 docId 分组 → 按 chunkIndex 排序
  → 还原原文顺序 → 取相邻 chunk 拼入上下文
  成本: 1x Embedding + 1x 存储 + 元数据字段
```

本质是通过 **outlinePath + sectionContext + chunkIndex 排序** 三个元数据实现了"父文档"效果，不需要存储两套 chunk。Blog 说的"索引量 ×2、多一次关联查询"在 Ragent 中不存在。

AgentFlow 没有显式的父子文档机制，靠 `ChatContextFilter` 上下文截断 + `quoteQA` 组合达到类似效果。

---

## 九、业界六种分块策略评估

> 这六种不是并列选项，而是三层递进——前三种是"怎么切"（算法），后三种是"按什么边界切"（结构约束）。

### 9.1 固定长度 (Fixed-size by Token)

**博客说的**：按 Token 硬切 + overlap，快速验证 RAG 可行性。风险：条件/结论被切断，语义破碎。

**实战真相**：
- Ragent 的 `FixedSizeTextChunker` 不是简单按字符数切——做了三层边界对齐（换行→中文标点→英文句末）+ normalizeText() 修复 URL/CJK 软换行
- AgentFlow 的 `GeneralSplitter` 用 LlamaIndex `TokenTextSplitter(chunk_size=1024, overlap=200)`，就是博客说的朴素版本
- **Ragent 在这个策略上比博客描述的多做了边界对齐和 URL 修复**

### 9.2 递归字符 (Recursive Character Splitter)

**博客说的**：按优先级递归找分隔符：`\n\n` → `\n` → `。` → ` ` 。通用文本适用。风险：无结构感知，章节边界无法识别。

**实战真相**：
- AgentFlow 有 `SeparatorRecursiveSplitter`（`chunk_size=1024, overlap=200`），标准 LangChain 风格
- Ragent **没有独立的递归字符切分器**。但 `StructureAwareTextChunker` 的扫描逻辑是同一思路的升级版——先扫描为 4 种逻辑块再在块边界切分，避免了"`\n\n` 可能是段落边界也可能是表格内空行"的问题

### 9.3 语义切分 (Semantic/Embedding-based splitting)

**博客说的**：计算相邻句子的 Embedding 相似度，相似度骤降处切分。默认参数下平均块仅 43 Token。

**实战真相**：Ragent 和 AgentFlow **都没有实现**。原因：
1. **成本不可接受**：每个文档要对每句话做一次 Embedding + 相似度计算
2. **43 Token 的块太碎了**：检索精度看似高但召回块缺上下文，LLM 看不懂
3. **相似度"骤降"不可靠**：主题过渡是渐进的，阈值极其敏感

### 9.4 结构感知 (Structure-aware by Headings)

**博客说的**：按标题/章节层级切分。适用于 Markdown/HTML。风险：严重依赖上游解析质量。

**这是两个项目差距最大的策略。**

**Ragent 的 block-aware 是结构感知的完全体**：
- Parser 产出 6 种强类型 Block
- HeadingHandler 累积 outlinePath 注入每个 chunk
- 每个 chunk 带 `outlinePath`（如 `["员工手册", "第一章 入职流程", "1.2 薪资结构"]`）和 `sectionContext`
- 检索时 LLM 看到的不仅是 chunk 文本，还知道它属于哪个章节

**AgentFlow 的结构感知更接近博客描述的朴素版本**：
- `SeparatorRecursiveSplitter` 知道分隔符优先级但不知道文档结构
- OCR 管线的 `TXTSplitter` 有标题树 + 小段落合并，但不产 outlinePath、不区分 block 类型

### 9.5 页面级 (Page-level)

**博客说的**：按物理页边界切。适用于金融报告/法律文档。风险：随机导出的 PDF 页边界 ≠ 语义边界。

**实战真相**：
- Ragent 的 `Provenance` 可记录页码信息，但**当前没有页面级分块策略**。这是合理的选择——页面是渲染层概念，向量检索关心的是语义层
- AgentFlow 的 OCR 管线特别做了**跨页段落合并**和**跨页表格合并**——检测末页无标点自动合并、检测上下页表格列数匹配自动合并 HTML/cells。这恰恰说明：**页面级分块需要配套的跨页检测逻辑**，不是简单的 `split by page`

### 9.6 Parent-Child (父子文档)

**博客说的**：小块检索（子 chunk 做 Embedding）+ 大块上下文（父 chunk 做 LLM 输入）。风险：索引量 ×2，多一次关联查询。

**Ragent 实现了"零成本等价方案"**（详见第八章）。

### 9.7 总结矩阵

| 策略 | Ragent | AgentFlow | 实现深度差异 |
|:---|:---|:---|:---|
| 固定长度 | FIXED_SIZE（带边界对齐+URL修复） | GeneralSplitter（朴素 TokenTextSplitter） | Ragent 多做了三层边界对齐 |
| 递归字符 | 无独立实现，STRUCTURE_AWARE 是升级版 | SeparatorRecursiveSplitter | AgentFlow 有，Ragent 跳过了这一级 |
| 语义切分 | **无** | **无** | 成本/效果比不划算，两个项目都没做 |
| 结构感知 | block-aware（6 种 Block + outlinePath + sectionContext） | OCR管线（标题树 + 段落合并） | Ragent 远深于博客描述 |
| 页面级 | Provenance 可记录页码，但不用作分块边界 | OCR 管线有跨页合并（反页面切分） | 两者都不把页面当分块边界 |
| Parent-Child | 一套 chunk + 元数据回表（零额外成本） | ChatContextFilter + quoteQA | Ragent 方案更系统化 |

---

## 十、面试话术

### "介绍一下 Ragent 的文档切分策略"

> Ragent 的分块是两层架构。第一层是 Parser 层，6 个解析器按 MIME 类型分层路由——MinerU 处理 PDF/Word/PPT、POI 处理 Excel、commonmark 处理 Markdown、VLM 处理图片、Tika 兜底纯文本。MinerU 解决的是二进制格式到结构化 markdown 的转换，并不直接输出 Block。markdown → Block 这一步是 Ragent 自己的 commonmark AST 解析器做的，MinerU 路径和 Markdown 路径共用同一套代码。
>
> 第二层是 Chunker 层。解析器产出的强类型 Block（6 种 sealed interface）通过 BlockAwareChunkerDispatcher 分发到 6 个专属 Chunker。HeadingBlock 不产 chunk，只累积 outlinePath 注入后续块。最关键的差异化设计是表格的**双文本嵌入**——content 用 markdown 表格给 LLM 展示，embeddingText 用 key-value 格式给 Embedding 向量化。因为 embedding 模型读不懂 markdown 表格的列位置对齐，key-value 把"列名: 值"的语义关系写进字面，sparse/dense 检索都更准。每个表格 chunk 还带 `sectionContext`（sheet 名+表头摘要），做到 contextual chunking。
>
> 各 chunker 只拆不并，最后 ChunkPacker 做贪心合并，TABLE/CODE 保持原子块，PARAGRAPH/LIST/IMAGE 小块拼到 maxChars 预算，断块处用完整块级重叠保持上下文连续性。
>
> 跟业界典型的 Parent-Child 方案比，我们没有存两套 chunk。通过 outlinePath + sectionContext + chunkIndex 排序回表还原原文顺序，达到了同样的"扩大上下文窗口"效果，但省去了双倍 Embedding 和存储成本。

### "你们为什么用 MinerU？它解决了什么问题？"

> MinerU 解决的是"不可直接读取的二进制格式 → 高质量 markdown"这个最难的转换问题。PDF 里面的文字顺序、表格结构、公式识别都是业界难题。MinerU 做了阅读顺序排序、表格结构还原、公式 LaTeX 转换、图片提取这些事。
>
> 但它输出的仍然是 markdown，不是我们的 Block。Ragent 拿到 MinerU 的 markdown 后，用 commonmark-java 解析成 AST，再遍历 AST 产出 6 种强类型 Block。MinerU 和 Markdown 两条路径在 AST → Block 这一步共用完全相同的一套代码。所以 MinerU 是"高质量 markdown 供应商"，不是"Block 生产者"。
>
> 没有 MinerU，PDF/Word/PPT 就只能走 Tika 降级为 ParagraphBlock 平文本，表格变平文本、标题信息丢失、图片无法提取——全部结构信息损毁。

### "和 AgentFlow/Dify/LangChain 的分块有什么不同？"

> AgentFlow 是分类治理模式——按文件扩展名路由到不同 Splitter，覆盖面广但每种策略深度有限。Ragent 是结构感知优先——Parser 产出强类型 Block、Chunker 按 Block 类型精细处理、表格做双文本嵌入。AgentFlow 的表格就是逐行 TokenTextSplitter，不会区分 embedding 用和展示用的文本。
>
> LangChain 的 RecursiveCharacterTextSplitter 能找到分隔符但不知道分隔符的意义，`\n\n` 可能是段落边界也可能是表格内空行。Ragent 先分类再分块，避免了这个问题。Dify 也是类似的分隔符优先级模式。
>
> 另外 Ragent 的 `VectorChunk` 带 6 个富元数据字段（outlinePath/blockType/sectionContext/sourceBlockIds/assets/embeddingText），这些在 LangChain 的 Document.metadata 里是完全没有结构约束的自由 JSON。

### "六种分块策略你们用了哪些？为什么有些不用？"

> 结构感知是我们的主力策略，block-aware 分发 + outlinePath 注入 + 双文本嵌入。固定长度是降级兜底，但做了三层边界对齐。递归字符我们没有独立实现，STRUCTURE_AWARE 本质上是它的升级版。
>
> 语义切分（Embedding 相似度检测）我们没做——成本太高（每句话一次 Embedding），默认参数下平均块只有 43 Token，太碎；而且相似度"骤降"的阈值极难调准。页面级我们也不做——页面是渲染层概念，语义边界和物理边界是两回事，AgentFlow 特意做了跨页合并来对抗这个问题。
>
> Parent-Child 我们做了但不存两套 chunk——用 outlinePath + sectionContext + chunkIndex 排序回表还原原文顺序，效果等同但省去双倍成本。
