# RAG 网页爬取 + 数据清洗 + 手动分块技术方案

> 目标：在 ragent 知识库入库链路中新增"网页爬取 → 数据清洗 → markdown 化 → 前端手动分块"能力。
> 爬虫采用独立 Python 服务（requests/Playwright），支持静态抓取与 JS 渲染，考虑代理防限流。

---

## 一、需求确认（已对齐）

| 维度 | 结论 |
|---|---|
| 爬虫形态 | 独立 Python 爬虫服务（Flask/FastAPI），Java 侧编排调度 |
| 清洗深度 | 正文为主：去噪（导航/页脚/广告/脚本）+ 转 markdown |
| 动态程度 | 纯静态可抓 + 需 JS 渲染场景，Playwright 兜底 |
| 代理策略 | 支持代理池，防止限流被封 |
| 手动 chunk | 清洗后的 markdown 展示在前端，用户点击位置划分块边界 |
| 与现有链路关系 | 复用 `RemoteFileFetcher`/`ScheduleRefreshProcessor` 的调度与入库能力 |

---

## 二、总体架构

```
┌────────────────────  Java 端 (ragent bootstrap)  ────────────────────┐
│                                                                      │
│  WebCrawlController (HTTP 入口)                                       │
│      │ ① 提交爬取任务 (url / 渲染模式 / 代理策略 / 知识库)              │
│      ▼                                                                │
│  WebCrawlService                                                      │
│      │ 调 Python 爬虫服务 → 拿清洗后 markdown                          │
│      │ 存 markdown 到文件存储 (RustFS) + 元数据入库                     │
│      ▼                                                                │
│  ManualChunkService（手动分块管理）                                    │
│      │ 前端展示 markdown → 用户点分块 → 边界配置落库                    │
│      ▼                                                                │
│  KnowledgeDocumentService（复用现有入库链路）                          │
│      按边界切分 → embed → pgvector + t_knowledge_chunk                │
└──────────────────────────────────────────────────────────────────────┘
                            │ HTTP (1 次请求 / 任务)
                            ▼
┌────────────────────  Python 端 (spider-server)  ─────────────────────┐
│                                                                      │
│  app.py (Flask/FastAPI)                                              │
│      │ /crawl  同步: 爬取+清洗+转markdown → 返回 markdown 文本          │
│      ▼                                                                │
│  engines/                                                             │
│   ├─ StaticCrawler  (requests + BeautifulSoup)  → 静态页              │
│   └─ BrowserCrawler (Playwright)                → JS 渲染页           │
│      ▼                                                                │
│  cleaners/                                                            │
│   ├─ readability.py (Readability/Readability-lxml)  → 正文提取        │
│   ├─ trafilatura.py (Trafilatura)                  → 去噪/正文         │
│   └─ html2md.py     (自定义 HTML→Markdown 转换)    → markdown         │
│      ▼                                                                │
│  proxy/                                                               │
│   └─ ProxyPool（代理池 + 随机 UA + 请求间隔 + 重试退避）                 │
└──────────────────────────────────────────────────────────────────────┘
```

### 关键决策

1. **爬虫独立 Python 服务**，理由：
   - requests/Playwright 生态成熟，动态渲染、反爬对抗成本低
   - 爬虫逻辑迭代频繁（选择器、清洗规则）不影响 Spring Boot 主应用
   - 可独立扩缩容，特殊站点加代理不阻塞主线
2. **清洗与爬取分离**：爬引擎只负责"拿到 HTML"，清洗引擎负责"HTML → 正文 → markdown"，可单独替换清洗器
3. **手动分块配置与 chunk 数据分离**：用户点击产生的"边界配置"先落库，确认后再真正切分入库，避免误点直接污染向量库

---

## 三、Python 爬虫服务设计

### 3.1 服务布局

```text
spider-server/
├── app.py                     # Flask 应用, 路由注册, 统一异常
├── engines/
│   ├── __init__.py
│   ├── static_crawler.py      # requests + BeautifulSoup
│   └── browser_crawler.py     # Playwright
├── cleaners/
│   ├── __init__.py
│   ├── readability.py         # 正文提取 (Readability)
│   ├── trafilatura.py         # 离线正文提取
│   └── html2md.py             # HTML → Markdown 转换
├── proxy/
│   ├── proxy_pool.py          # 代理池管理
│   └── ua_pool.py             # User-Agent 池
├── models.py                  # pydantic 请求/响应模型
├── config.py                  # 配置（超时/重试/代理来源）
└── requirements.txt
```

### 3.2 接口设计

```http
POST /api/v1/crawl
```

请求体：

```json
{
  "url": "https://example.com/docs/page1",
  "render_mode": "auto",            // prescript_static | playwright | auto
  "wait_selector": null,            // 渲染等待选择器（可选）
  "use_proxy": true,
  "max_bytes": 5242880,
  "timeout_ms": 30000
}
```

响应体：

```json
{
  "code": "0",
  "data": {
    "title": "页面标题",
    "markdown": "# 章节1\n\n正文内容...\n\n## 小节\n\n...",
    "html": null,                    // 清洗后的 HTML（调试用，可选）
    "content_hash": "sha256...",     // markdown 全文 hash
    "crawler_type": "requests",      // 实际使用的爬取引擎
    "proxy_used": "1.2.3.4:8080",    // 实际使用的代理
    "crawl_ms": 1234,                // 耗时
    "size_bytes": 20480
  },
  "message": "ok"
}
```

错误码（按业务细化，不用大一统错误码）：

```text
CRAWL_FETCH_FAILED     — 网络/HTTP 错误
CRAWL_EMPTY_CONTENT    — 抓取内容为空
CRAWL_SIZE_LIMIT       — 超出大小限制
CLEAN_NO_MAIN_CONTENT  — 清洗后未提取到正文
CRAWL_PROXY_FAILED     — 代理不可用（已重试仍失败）
CRAWL_TIMEOUT          — 超时
CRAWL_DOMAIN_FORBIDDEN — 域名不在白名单内
```

**异常 → 错误码映射（Python 内部异常统一转 HTTP 错误码）**：

```python
# app.py 统一异常处理器
ERROR_CODE_MAP = {
    FetchFailed:          "CRAWL_FETCH_FAILED",
    CrawlEmptyContent:    "CRAWL_EMPTY_CONTENT",
    SizeLimitExceeded:    "CRAWL_SIZE_LIMIT",
    CleanNoMainContent:   "CLEAN_NO_MAIN_CONTENT",
    ProxyFailed:          "CRAWL_PROXY_FAILED",
    CrawlTimeout:         "CRAWL_TIMEOUT",
    DomainForbidden:      "CRAWL_DOMAIN_FORBIDDEN",
}

@app.errorhandler(Exception)
def handle_error(e):
    code = ERROR_CODE_MAP.get(type(e), "INTERNAL_ERROR")
    return jsonify({"code": code, "message": str(e), "data": None}), 200
```

Playwright 路径的异常归类：
- `goto` / `wait_for_selector` 超时 → `CrawlTimeout` → `CRAWL_TIMEOUT`
- 渲染后正文过短 / 等待选择器超时 → `CleanNoMainContent` → `CLEAN_NO_MAIN_CONTENT`
- 导航返回 4xx/5xx 或被反爬拦截 → `FetchFailed` → `CRAWL_FETCH_FAILED`

### 3.3 爬取引擎选择

```python
async def crawl(url, render_mode, wait_selector, timeout_ms, proxy=None):
    if render_mode == "playwright":
        return await BrowserCrawler.crawl(url, wait_selector=wait_selector,
                                          timeout_ms=timeout_ms, proxy=proxy)
    if render_mode == "requests":
        return StaticCrawler.fetch(url, timeout_ms=timeout_ms, proxy=proxy)

    # auto: 先静态抓，判定为动态空壳则回退 Playwright
    try:
        html = StaticCrawler.fetch(url, timeout_ms=timeout_ms, proxy=proxy)
        if looks_dynamic(html):
            raise DynamicPageDetected()
        return html
    except (FetchFailed, DynamicPageDetected):
        logger.info(f"静态抓取无法获取正文，回退 Playwright 渲染: {url}")
        return await BrowserCrawler.crawl(url, wait_selector=wait_selector,
                                          timeout_ms=timeout_ms, proxy=proxy)
```

**动态渲染特征判断** (`looks_dynamic`)：
- HTML 主体内容极少（正文区几乎为空的 script shell）
- `<div id="app">` / `<div id="root">` / `<div id="__nuxt">` 等 SPA 挂载点
- 页面含大量 `<script>` 但 `<article>`/`<p>` 内容不足

### 3.3.1 Playwright 浏览器实例管理

浏览器单例 + 信号量控制并发，避免每次请求都 launch（launch 耗时 ~2s）：

```python
# spider-server/engines/browser_crawler.py
import asyncio
from playwright.async_api import async_playwright, Browser, BrowserContext

class BrowserManager:
    """Playwright 浏览器单例，通过信号量限制并发渲染数"""
    _browser: Browser = None
    _lock = asyncio.Lock()
    _semaphore: asyncio.Semaphore = None

    @classmethod
    async def get(cls, concurrency: int = 4) -> Browser:
        async with cls._lock:
            if cls._browser is None or not cls._browser.is_connected():
                pw = await async_playwright().start()
                cls._browser = await pw.chromium.launch(
                    headless=True,
                    args=[
                        "--no-sandbox",                     # 容器内必须
                        "--disable-blink-features=AutomationControlled",  # 反检测
                        "--disable-dev-shm-usage",          # /dev/shm 过小避免崩溃
                    ],
                )
                cls._semaphore = asyncio.Semaphore(concurrency)
        return cls._browser

    @classmethod
    async def release_slot(cls):
        cls._semaphore.release()

    @classmethod
    async def acquire_slot(cls):
        async with cls._lock:
            sem = cls._semaphore
        await sem.acquire()

    @classmethod
    async def close(cls):
        async with cls._lock:
            if cls._browser is not None:
                await cls._browser.close()
                cls._browser = None
```

### 3.3.2 上下文隔离 + 反检测 + 资源拦截

每次请求创建**独立 BrowserContext**（隔离 cookie/会话），并注入反检测脚本、拦截非必要资源（图片/字体/媒体）以提速：

```python
import random
from urllib.parse import urlparse

UA_POOL = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Safari/605.1.15",
    # ... 更多常见 UA
]

async def build_context(browser: Browser, proxy: str | None) -> BrowserContext:
    """每个请求独立 context: 反检测 + 代理 + 资源拦截"""
    context = await browser.new_context(
        viewport={"width": 1366, "height": 768},
        user_agent=random.choice(UA_POOL),
        locale="zh-CN",
        timezone_id="Asia/Shanghai",
        proxy={"server": proxy} if proxy else None,
    )

    # 反检测: 抹掉 navigator.webdriver 特征
    await context.add_init_script(
        "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
    )

    # 资源拦截: 图片/字体/媒体/统计请求直接中止, 页面上只剩正文所需资源
    async def block_unneeded(route):
        resource_type = route.request.resource_type
        if resource_type in ("image", "font", "media", "beacon"):
            await route.abort()
        else:
            await route.continue_()

    await context.route("**/*", block_unneeded)
    return context
```

### 3.3.3 渲染等待策略（交互集成的核心）

`domcontentloaded` 比 `networkidle` 快得多；SPA 内容完整性靠 `wait_for_selector` 或渲染后校验兜底：

```python
class BrowserCrawler:
    @staticmethod
    async def crawl(url: str, wait_selector: str | None,
                    timeout_ms: int, proxy: str | None) -> str:
        browser = await BrowserManager.get()
        await BrowserManager.acquire_slot()
        try:
            context = await build_context(browser, proxy)
            async with context:
                page = await context.new_page()

                # ① 导航: 走 domcontentloaded, 不等网络完全空闲
                try:
                    await page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
                except asyncio.TimeoutError:
                    raise CrawlTimeout(f"页面导航超时: {url}")

                # ② 渲染等待: 有等待选择器则等关键内容出现, 否则给首屏 JS 执行时间
                if wait_selector:
                    try:
                        await page.wait_for_selector(wait_selector,
                                                     timeout=min(timeout_ms, 15000))
                    except asyncio.TimeoutError:
                        raise CleanNoMainContent(f"等待选择器超时: {wait_selector}")
                else:
                    await page.wait_for_timeout(1500)   # 默认给足首屏渲染时间

                # ③ 滚动到底, 触发懒加载区域渲染
                try:
                    await page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                    await page.wait_for_timeout(500)
                except Exception:
                    pass    # 页面不支持滚动(如纯静态)时忽略

                # ④ 渲染结果校验: 正文仍过短 → 判定无有效内容
                text_len = await page.evaluate("document.body.innerText.length")
                if text_len < 200:
                    raise CleanNoMainContent(f"渲染后正文过短: {text_len}")

                return await page.content()   # 渲染完成的最终 HTML
        finally:
            await BrowserManager.release_slot()
```

### 3.3.4 渲染流程时序

```
             静态请求            静态失败 / 判定动态空壳
  发请求 ───────────────► ─────────────────────────────►  Playwright 渲染
                          │                                  │ ① goto(domcontentloaded)
                          │                                  │ ② wait_for_selector / 定时等待
                          ▼                                  │ ③ 滚动触底
                   返回清洗后的                              ▼ ④ 校验正文长度
                   markdown                              page.content() 拿到完整 HTML
                                                              │
                                                              ▼
                                                         进入 3.4 清洗管线
```

**自动回退的判定时机**：静态抓取成功但正文过短（<200 字符），或命中 SPA 挂载点，视为"拿不到内容"，抛 `DynamicPageDetected` 走 Playwright。

### 3.3.5 性能与并发约束

| 指标 | 值 / 措施 |
|---|---|
| 每请求 resource | 拦截图片/字体后可低至 ~1MB 以下（原页面可能 10MB+） |
| 并发数 | 默认 4（信号量控制），单实例容器 ~8 为上限 |
| 单页渲染耗时 | 2~5s（goto + 等待 + 滚动），比静态抓取慢，但可控 |
| 内存占用 | 每个页面上下文约 100~150MB，容器需预留 |
| 上下文清洗 | 每请求独立 context，`async with` 自动关闭，无 cookie 串污染 |

### 3.3.6 Jina Reader 兜底通道（可选，默认关闭）

自研两通道（静态 / Playwright）都失败时，可将 `r.jina.ai/{url}` 作为**第三级兜底**。它会把网页抓取并直接返回 markdown，反爬由 Jina 的 IP 池承接。

```
三级回退链:
  ① requests 静态抓取 ──失败/空壳──► ② Playwright 渲染 ──仍失败──► ③ Jina Reader
```

```python
# orchestrator.py
async def crawl_with_fallback(url, render_mode, wait_selector, timeout_ms,
                              proxy, allow_jina=False):
    try:
        return await crawl(url, render_mode, wait_selector, timeout_ms, proxy)
    except (FetchFailed, CleanNoMainContent, CrawlTimeout) as e:
        if not allow_jina:
            raise
        logger.warning(f"自研通道抓取失败，回退 Jina: {url} err={e}")
        return await jina_fetch(url)          # GET r.jina.ai/{url} → markdown
```

- 开关 `allow_jina` 由 Java 侧请求体透传，默认 false（合规：内容不过第三方）
- 白名单/内部站点强制关闭该通道
- Jina 返回的已是 markdown，下游直接进 3.4.5 质量校验层，无需重新清洗

### 3.4 清洗管线（五层清洗模型）

清洗按"从粗到细"分五层，每层一个独立处理器，可单独替换、可单测：

```
原始 HTML
  ↓ L1 结构层: 正文区域定位（双引擎 + 降级）
  ↓ L2 噪音层: 剔除与正文无关的 DOM（导航/页脚/广告/脚本）
  ↓ L3 文本层: 清理文本（空白/重复/boilerplate/编码）
  ↓ L4 转换层: HTML → Markdown（保结构, 不自研一把梭）
  ↓ L5 质量层: 校验清洗结果（长度/空块/标题相关性）
  ↓
markdown 全文
```

#### 3.4.1 L1 结构层：正文区域定位

目标"找到哪里是正文"，手段是给 DOM 打分：

| 策略 | 做法 | 效果 |
|---|---|---|
| 语义标签优先 | 优先取 `<main>`/`<article>` 标签 | 现代站点最可靠 |
| 文本密度打分 | 节点文本量 / 链接文本量，正文通常文本多、链接少 | Readability 核心算法 |
| 双引擎降级 | **Readability 优先 → 失败回退 Trafilatura** | 文章/博客 vs 新闻/学术互补 |

```python
# cleaners/extractor.py
def extract_main_html(html: str) -> str:
    """L1: 正文区域提取, Readability 失败回退 Trafilatura"""
    main_html = readability_extract(html)          # Readability-lxml
    if not main_html or len(clean_text(main_html)) < 200:
        logger.info("Readability 提取正文失败，回退 Trafilatura")
        main_html = trafilatura_extract(html)
    return main_html
```

#### 3.4.2 L2 噪音层：剔除无关 DOM

正文定位后清掉噪音节点（配置化选择器集合，可扩展）：

```
通用噪音选择器（批量 remove）:
  nav / aside / footer / 页面顶部重复 header
  script / style / noscript / iframe / form
  .ad / .ads / .banner / .advertisement / [class*=advert]
  .cookie-banner / [class*=cookie] / .newsletter / .subscribe / .share

隐藏元素: display:none | visibility:hidden | aria-hidden=true
```

```python
# cleaners/dom_cleaner.py
NOISE_SELECTORS = ["nav", "aside", "footer", "script", "style",
                   "noscript", "iframe", "form",
                   ".ad", ".ads", ".banner", ".advertisement",
                   ".cookie-banner", ".newsletter", ".subscribe"]

def remove_noise(root):
    """L2: 批量移除噪音节点 + 隐藏元素"""
    for sel in NOISE_SELECTORS:
        for node in root.select(sel):
            node.decompose()
    for node in root.select("[style*=display\\:none], [aria-hidden=true]"):
        node.decompose()
```

#### 3.4.3 L3 文本层：清理文本内容

```
去空白         → 多个连续空行合并为 1, 去行首尾空白
去重复         → 移动版站点常把正文渲染两遍, 检测(去重后长度 < 原文一半 → 判重复)
去 boilerplate → 版权行 / 免责声明 / 备案号 等固定话术
编码归一化     → GBK/GB2312 误判 → UTF-8 修复
```

#### 3.4.4 L4 转换层：HTML → Markdown

| 元素 | 转换策略 | 注意点 |
|---|---|---|
| h1~h6 | `#`~`######` | 保留层级，这是手动分块 `outlinePath` 的来源 |
| 段落/列表 | 段落换行、`-`/`1.` | 相邻块空行分隔 |
| 表格 | 撑成 markdown 表格 或 转段落 | 列数>6 转段落，避免 markdown 表格炸开 |
| 代码块 | ```` ``` ```` 包裹 | 保留语言标注 |
| 图片 | **转存 RustFS 为主，见 3.4.6** | 由图片策略决定 |
| 链接 | `[text](href)` | 去掉 tracking 参数（`?utm_*`/`?spm=*`） |

**关键点**：不要用 `html2text`/`markdownify` 一把梭——它们表格/代码块转换质量不稳定。方案是"L1 提正文 + 自研 HTML→Markdown 遍历器"，两者解耦，各自可单测。

#### 3.4.5 L5 质量校验层：判定清洗是否成功

```
① 正文长度阈值: 清洗后 < 200 字符 → 判定无正文, 触发降级(换引擎/Jina)
② 标题相关性: 正文语言/主题与 <title> 不一致 → 可能是误抓
③ 空块检测: 转换后无有效段落 → 返回 CLEAN_NO_MAIN_CONTENT
```

```python
def validate_clean_result(markdown: str, title: str) -> None:
    """L5: 校验清理结果, 不通过则抛 CleanNoMainContent"""
    text = re.sub(r"[\s#*`\-]", "", markdown)
    if len(text) < 200:
        raise CleanNoMainContent(f"清洗后正文过短: {len(text)}")
    if title and not is_charset_consistent(title, text[:100]):
        logger.warning(f"正文与标题语言不一致: title={title[:50]}")
```

#### 3.4.6 图片策略（已决策：转存 RustFS 为主、外链兜底）

```python
# cleaners/image_policy.py
class ImagePolicy:
    """图片三级策略:
    ① RustFS 转存: 正文图片下载到 RustFS, 图片链接换为内链(企业文档可控, 默认)
    ② 保留外链: 公开可访问的图片直接留原始 URL(降级)
    ③ 丢弃: 装饰性图片(icon/logo/spinner)不落 markdown
    """
```

- 默认对正文内 `<img>` 下载转存 RustFS（复用 `FileStorageService` 通道思路），防止外链失效/防盗链
- 转存失败跳过该图不阻塞整篇清洗；超过 `max_images`（默认 20）只存前 20 张
- 识别 `class*=icon|logo|spinner|emoji` 的装饰图直接丢弃，不下载

#### 3.4.7 站点模板清洗（预留扩展，Phase 1 不做）

对固定源（如内部 Wiki / 官网文档站）可配置 CSS selector 模板，正文定位从"通用算法"升级为"模板精确定位"：

```yaml
site-templates:
  - domain: "wiki.corp.internal"
    title: "h1.title"
    content: "article#main-body"
    remove: [".toc", ".edit-links", ".breadcrumb"]
    next_page: "a.next"
```

- 模板命中时跳过 L1 通用打分，直接用模板提取，质量最高
- 该能力作为接口预留，Phase 1 只做通用清洗，后续按需接入批量站点

### 3.5 代理与防限流（对接动态代理服务）

代理来源为**外部动态代理服务**（按需从代理服务接口拉取，运行期可刷新）：

```python
class ProxyPool:
    def __init__(self, retriever, min_healthy=3, refresh_interval_s=300):
        self.retriever = retriever          # 动态代理服务客户端
        self.pool = self.retriever.fetch()  # 启动时拉一批
        self.blacklist = {}                 # proxy -> 失败计数
        self.last_refresh = time.time()

    def refresh_if_stale(self):
        # 定期刷新代理池（如每 5 分钟），避免代理失效
        if time.time() - self.last_refresh > self.refresh_interval_s:
            self.pool = self.retriever.fetch()
            self.last_refresh = time.time()

    def get(self):
        self.refresh_if_stale()
        healthy = [p for p in self.pool if self.blacklist.get(p, 0) < 3]
        return random.choice(healthy if healthy else self.pool)

    def report_failure(self, proxy):
        self.blacklist[proxy] = self.blacklist.get(proxy, 0) + 1

    def report_success(self, proxy):
        self.blacklist[proxy] = 0
```

- `retriever` 抽象封装动态代理服务协议，初始实现对接具体代理服务商 API
- 抓取失败（403/429/超时）→ 换下一个代理重试，最多 2 次
- 高失败率的代理自动进入黑名单并被剔除

防限流策略：
- 随机 UA 池（`ua_pool.py` 内置一组常见浏览器 UA）
- 失败重试：403/429/超时 → 换代理重试，最多 2 次
- 请求间隔：同一域名的连续请求加随机 sleep(1~3s)
- 可通过配置决定是否启用 delays（避免拖慢抓取速度）
- 域名白名单：仅允许抓取配置授权的域名，白名单外直接拒绝（见 4.6 节）

### 3.6 部署形态

- 独立容器，Dockerfile 基于 python:3.11-slim + `playwright install chromium`
- 端口：独立（如 10002）
- 建议跑一个常驻 worker，Java 侧同步 HTTP 调用（超时 60s，内部超时 30s）；量大时再演进为 MQ 异步
- 无状态，天然可多副本

---

## 四、Java 侧集成设计

### 4.1 现有可复用能力（不重复造轮子）

| 现有能力 | 位置 | 复用方式 |
|---|---|---|
| URL 文档模型 `sourceType=url` | `KnowledgeDocumentDO` | 新增 `sourceType=webpage` 或复用 URL |
| 定时调度 | `ScheduleRefreshProcessor` | 网页刷新沿用同一状态机 |
| 变更检测 | `RemoteFileFetcher` ETag/ContentHash | markdown 全文 ContentHash 变化才重分块 |
| 入库链路 | `KnowledgeDocumentService.runChunkTask` | 手动 chunk 也走 `persistChunksAndVectorsAtomically` |
| 文件存储 | `FileStorageService` (RustFS) | 清洗后 markdown 落文件存储，便于预览/重分块 |

### 4.2 新增组件

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/
├── controller/WebCrawlController.java        ★ 爬取 + 手动分块 HTTP 入口
├── service/
│   ├── WebCrawlService.java                  ★ 调 Python 爬虫服务 → 落库
│   └── ManualChunkService.java               ★ 手动分块边界管理
├── client/
│   └── SpiderClient.java                     ★ Python 爬虫服务 HTTP 客户端（WebClient）
├── dao/entity/
│   ├── KnowledgeDocumentMarkdownDO.java      ★ 清洗 markdown 元数据（新表）
│   └── KnowledgeDocumentChunkEditDO.java     ★ 手动分块边界（新表）
└── enums/
    ├── CrawlerType.java                      ★ 爬取引擎枚举
    └── ChunkBoundType.java                   ★ 手动分块类型
```

### 4.3 数据模型（新增表）

**t_knowledge_document_markdown**（清洗后的 markdown 源）

```sql
CREATE TABLE t_knowledge_document_markdown (
    id            VARCHAR(20)     NOT NULL PRIMARY KEY,
    doc_id        VARCHAR(20)     NOT NULL,
    title         VARCHAR(512),
    content_hash  VARCHAR(64)     NOT NULL,
    md_file_url   VARCHAR(512),              -- markdown 存文件存储的 URL
    size_bytes    BIGINT,
    crawler_type  VARCHAR(16),               -- requests / playwright
    proxy_used    VARCHAR(64),
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_md_doc ON t_knowledge_document_markdown (doc_id);
COMMENT ON TABLE t_knowledge_document_markdown IS '清洗后网页 markdown 源';
```

**t_knowledge_document_chunk_edit**（手动分块边界配置）

```sql
CREATE TABLE t_knowledge_document_chunk_edit (
    id          VARCHAR(20) NOT NULL PRIMARY KEY,
    doc_id      VARCHAR(20) NOT NULL,
    md_id       VARCHAR(20) NOT NULL,         -- 关联 markdown 源
    edit_index  INTEGER     NOT NULL,         -- 块序号（用户点击顺序）
    md_start    INTEGER,                      -- markdown 起止偏移（字符区间）
    md_end      INTEGER,
    outline     VARCHAR(512),                 -- 可选: 来源标题, 便于阅读
    status      SMALLINT    NOT NULL DEFAULT 0,  -- 0=草稿, 1=已入库, 2=已失效
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_edit_doc ON t_knowledge_document_chunk_edit (doc_id, md_id);
COMMENT ON TABLE t_knowledge_document_chunk_edit IS '网页手动分块边界配置';
```

**手动分块存储 vs 直接把边界塞进 chunk 表**：独立 `chunk_edit` 表，好处是"边界配置"是草稿态、可反复调整，只有用户点"确认入库"才真正切分写入 `t_knowledge_chunk`。

### 4.4 交互流程（手动 chunk，前端调分块 API）

分块计算完全由后端承担，前端只做"展示 markdown + 收集用户选中/确认动作"，所有切分逻辑通过调用后端分块 API 完成。

```
① 用户提交爬取: POST /api/v1/web-crawl (前端调用)
    → WebCrawlService 调 SpiderClient /crawl
    → markdown 存文件存储 + 元数据落 t_knowledge_document_markdown
    → 返回 markdown + mdId 给前端展示

② 用户在前端浏览 markdown, 选中一段 → 点击"划分为一块"
    → 前端调用 POST /api/v1/web-crawl/{docId}/chunk-edits (调分块API)
    → ManualChunkService 收到 {start, end} 边界集合
    → 落 t_knowledge_document_chunk_edit (status=0 草稿)

③ 用户确认入库: POST /api/v1/web-crawl/{docId}/chunk-edits/commit (前端调用)
    → 后端执行实际切分 (调分块 API): 读 markdown 全文 + 边界配置
    → 按边界切片 → 生成 VectorChunk 列表
    → 复用 knowledgeChunkService.batchCreate + vectorStoreService.indexDocumentChunks
    → chunk_edit.status 置为 1 (已入库)

④ 分块预览/回显: GET /api/v1/web-crawl/{docId}/preview
    → 返回 markdown + 已保存的 {start, end} 边界, 前端高亮显示

⑤ 若网页重新爬取且内容变化 (contentHash 不同)
    → 旧 chunk_edit 标记 status=2 失效
    → 前端重新展示新 markdown, 用户可再分块
```

前端不计算任何偏移/切片，提交的只是用户选中区域的 `{start, end}` 偏移量，由后端分块 API 统一处理和校验。

### 4.5 Java 接口设计

```java
@RestController
@RequestMapping("/api/v1/web-crawl")
public class WebCrawlController {

    /**
     * 提交网页爬取任务：调 Python 爬虫 → 清洗 → markdown 落库
     */
    @PostMapping
    public WebCrawlVO crawl(@RequestBody @Valid WebCrawlRequest req) {
        // 参数校验: url 必须合法 http(s), kbId 存在, renderMode 枚举合法
        return webCrawlService.crawl(req);
    }

    /**
     * 保存手动分块边界（草稿态，不入库）
     */
    @PostMapping("/{docId}/chunk-edits")
    public void saveChunkEdits(@PathVariable String docId,
                               @RequestBody @Valid List<ChunkEditRequest> edits) {
        manualChunkService.saveEdits(docId, edits);
    }

    /**
     * 确认手动分块并入库（幂等：同 mdId + contentHash 重复 commit 直接返回）
     */
    @PostMapping("/{docId}/chunk-edits/commit")
    public CommitResult commitChunkEdits(@PathVariable String docId) {
        return manualChunkService.commit(docId);
    }

    /**
     * 拉取分块预览：markdown + 已保存的边界（用于前端回显）
     */
    @GetMapping("/{docId}/preview")
    public PreviewVO preview(@PathVariable String docId) {
        return manualChunkService.preview(docId);
    }
}
```

**幂等设计**：
- `crawl`：同一 `(kbId, url)` 短时间内重复调用，先查 `t_knowledge_document_markdown` 是否有相同 contentHash，有则直接返回缓存
- `commit`：写库前校验 `chunk_edit.md_id + content_hash`，重复提交直接返回成功（不重复 embed/入库）

**鉴权**：复用现有 `UserContext`（登录用户 + 知识库权限校验，kb 归属校验）

### 4.6 域名白名单（安全合规）

仅允许抓取经授权的站点，白名单外一律拒绝，防止 SSRF 与恶意抓取：

**配置**（`application.yml` + 数据库可覆盖）：

```yaml
rag:
  web-crawl:
    whitelist-enabled: true
    whitelist-domains:
      - "*.example.com"
      - "*.corp-docs.internal"
```

**校验位置（Java 侧，双层）**：

```java
// ① 提交爬取任务时校验 URL 域名是否在白名单内
private void validateWhitelist(String url) {
    if (!properties.isWhitelistEnabled()) return;
    String host = UriComponentsBuilder.fromUriString(url).build().getHost();
    if (host == null) {
        throw new ClientException("URL 不合法，无法解析域名");
    }
    boolean allowed = properties.getWhitelistDomains().stream()
        .anyMatch(pattern -> DomainMatcher.match(pattern, host));
    if (!allowed) {
        throw new ClientException("域名不在白名单内，禁止抓取: " + host);
    }
}
```

- Java 侧先校验域名白名单（第一道防线，拒绝非法请求）
- Python 爬虫服务侧再校验一次（第二道防线，防止绕过 Java 直连）
- 可利用 `WebCrawlRequest` 携带 `kbId`，白名单支持按知识库覆盖

**SSRF 防范**：解析出 IP 后校验非内网段（127.0.0.0/8、10.0.0.0/8、172.16.0.0/12、192.168.0.0/16 等），禁止抓取内网地址。

---

## 五、手动 chunk 与增量更新方案的衔接

若用户的可点击分块产生的是**基于 markdown 偏移区间**的边界配置，天然可以与上一轮的增量更新方案对接：

- `chunk_edit.outline` 字段可提取为 `t_knowledge_chunk.outline_path`（增量 diff 的稳定身份）
- `md_start/md_end` 区间内容 hash 计算后作为 `content_hash` 参与 diff
- 网页内容变化 → 重新爬取 → 旧边界失效 → 用户重分块 → 增量 diff 只入库变化部分

---

## 六、异常与边界情况

| 场景 | 处理 |
|---|---|
| 爬取超时/失败 | 返回细化错误码，前端可重试；语义化 message |
| 代理不可用 | Python 侧换代理重试 2 次；全失败返回 `CRAWL_PROXY_FAILED` |
| 清洗后无正文 | 返回 `CLEAN_NO_MAIN_CONTENT`，不落库 |
| markdown 超限 | `max_bytes` 上限（默认 5MB），超出拒绝 |
| JS 渲染页静态抓不到 | auto 模式自动回退 Playwright |
| 用户分块后有内容没覆盖到 | commit 时校验边界是否覆盖全文，未覆盖部分单独提示 |
| 边界重叠/为空块 | commit 时做校验：start<end、相邻块不重叠 |
| 重复 commit | 幂等：mdId+contentHash 已入库则直接返回 |
| 回车/等特殊字符 | 分块切分记录偏移，不按文本关键词，避免歧义 |

---

## 七、实施步骤

| 阶段 | 内容 | 交付物 |
|---|---|---|
| Phase 1 | Python 爬虫服务骨架：静态爬取 + Readability 清洗 + 转 markdown + Flask API + 域名白名单校验 | `spider-server` 可独立跑通 `/crawl` 静态页 |
| Phase 2 | Playwright 渲染引擎 + auto 回退逻辑 | 动态页可抓 |
| Phase 3 | 动态代理服务对接 + UA 池 + 重试退避 | 防限流稳定 |
| Phase 4 | Java 端 `SpiderClient` + `WebCrawlController` + 两张新表 + 域名白名单 + SSRF 校验 | 网页爬取入库链路通 |
| Phase 5 | 手动分块交互（前端调分块 API：展示 markdown + 提交/确认分块） | 前后端闭环 |
| Phase 6 | 与增量更新方案衔接（outline_path / content_hash 对接） | 手动块也可增量 |
| Phase 7 | 定时刷新网页 + 变更检测 + 旧边界失效 | 全自动刷新链路 |

---

## 八、风险评估

| 风险 | 等级 | 缓解 |
|---|---|---|
| 网站反爬升级导致抓取失败 | 中 | 动态代理池 + 多引擎兜底 + 手动兜底重试 |
| JS 渲染性能开销大 | 中 | Playwright 仅 auto 检测到动态才用；可配置并发数 |
| 故意/意外抓取被限制站点的合规风险 | 高 | 仅允许抓取白名单域名 + SSRF 内网 IP 拦截 + 抓取日志留痕 |
| 手动分块交互成本高 | 低 | 提供"按标题自动分块"一键替代手动点击 |
| markdown 转换质量不均 | 中 | 表格/代码块单测覆盖;Readability 失败走 Trafilatura 兜底 |
| 动态代理服务不稳定 | 中 | 代理池定期刷新 + 高失败代理黑名单剔除 |

---

## 九、待确认细节（已确认）

以下决策已在需求确认阶段敲定：

| 决策项 | 结论 |
|---|---|
| 前端交互形态 | **调分块 API**：前端不承接切分逻辑，只负责展示 markdown + 收集用户选中/确认动作，所有分块计算由后端分块 API 统一完成 |
| 代理来源 | **对接外部动态代理服务**：运行期按需拉取代理，定期刷新，高失败代理自动剔除 |
| 爬虫服务部署 | **接入 `startup.sh` 一键启动**：Python 爬虫服务作为独立进程，由 `startup.sh` 拉起 |
| 域名白名单 | **有**：Java 侧 + Python 侧双层白名单校验，并做 SSRF 内网 IP 拦截 |