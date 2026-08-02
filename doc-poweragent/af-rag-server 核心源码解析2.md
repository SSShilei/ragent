# af\-rag\-server 核心源码解析2

# **af\-rag\-server 核心源码解析**

> 面向面试的 RAG \+ Agent 平台（Python）核心源码总结。 对应文档：`af-rag-server-architecture.md`。 关联项目：`agentflow-server`（Java 后端）—— 二者通过 Pinpoint 全链路追踪 / HTTP 工具调用互通（见 `source-code-analysis-09-10-trace-eval-deploy.md`）。 本文所有代码块均为源码原样摘录（保留文件路径与行数，供面试时直接引用）。
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

### **3\.1 Flask 应用入口与路由注册（****`C:\Users\shilei.he\PA\af-rag-server\app.py`****，250 行）**

`create_app` 是应用装配点：先 `monkey.patch_all()`（gevent 协程化阻塞 IO），再依次完成 Pinpoint 埋点、Redis 集群初始化、国际化、RESTful 路由注册、记忆调度器启动：

```Python
# -*- coding:utf-8 -*-
from gevent import monkey

# 不要动
monkey.patch_all()  # noqa

import io
import mimetypes
import os
from pathlib import Path

from flask import Flask, g, send_file, request
from flask_restful import Api, Resource
from pinpointPy import monkey_patch_for_pinpoint, set_agent, use_thread_local_context
from pinpointPy.Flask import PinPointMiddleWare
from redis import RedisCluster
from prometheus_flask_exporter import PrometheusMetrics
from src.tools.pinpoint_context import PinpointContext
from src.agent.auto_agent import AutoAgent
from src.apps.adapters import bp as adapter_api
from src.apps.graphrag.api import GraphRAGExtract, GraphRAGMap
from src.apps.package import (
    PackageCreate,
    PackageDetail,
    PackageEntryCode,
    PackageExecution,
)
from src.cache import RedisManager
from src.eval.ragas_handlers import RagasEvaluation
from src.feishu_parse.get_docdetail import FeishuGetDocDetail
from src.feishu_parse.get_docinfo import FeishuGetDocInfo
from src.feishu_parse.get_token import FeishuGetToken
from src.globals import db, configure_huey
from src.html_parser import HtmlManager
from src.i18n import PlaceholderReplacer
from src.i18n.i18n_manager import I18nManager
from src.rag_algorithm import handlers as rag_handlers
from src.server_api_tools.s3helper import S3Helper
from src.web_parse import ParseSubUrls, ParseWeb
from src.web_parse.parse_manager import CrawlJinaReader, CrawlFire, CrawlFireStatus, AuthVerf

from src.apps.fastapi_transfer import (
    ForwardResource,
    FORWARD_ENDPOINTS,
    FORWARD_ENDPOINTS_SSE,
    SSEProxyResource,
)
from src.ops.metrics import metrics
from src.memory.memory_vector import AddVector, QueryVector, DeleteVector, UpdateVector
from src.memory.scheduler import run_memory_scheduler

os.environ["TIKTOKEN_CACHE_DIR"] = str(Path(os.getcwd()) / "encoders")


def create_app():
    from src import settings

    use_thread_local_context()
    monkey_patch_for_pinpoint()
    app = Flask(__name__)

    # 把Java端透传来的traceId / spanId拦截并写入PinpointContext
    @app.before_request
    def load_pinpoint_from_headers():
        trace_id = request.headers.get("pinpoint-traceid")
        span_id = request.headers.get("pinpoint-spanid")
        PinpointContext.set_trace_id(trace_id)
        PinpointContext.set_span_id(span_id)

    # 使用 Redis Cluster 初始化客户端
    redis_client = RedisCluster(
        url=settings.HUEY_REDIS_HOST,
        decode_responses=True,
        skip_full_coverage_check=True,
    )
    app.config["CACHE_INSTANCE"] = redis_client

    # configure with pinpoint
    app.wsgi_app = PinPointMiddleWare(app, app.wsgi_app)
    APP_ID = "af-rag-server"
    APP_NAME = "af-rag-server"
    COLLECTOR_HOST = settings.PINPOINT_HOST
    set_agent(APP_ID, APP_NAME, COLLECTOR_HOST, trace_limit=100)
    configure_app(app, settings)
    configure_huey(app)
    configure_resources(app)
    run_memory_scheduler()

    @app.before_request
    def before_request():
        """在每个请求之前，初始化 replacer 并根据请求头设置语言"""
        lang = request.headers.get("X-LANG", "en_US")  # 默认 "en"，从请求头获取
        if lang not in ["en_US", "zh_CN"]:  # 仅允许支持的语言，避免意外输入
            lang = "en_US"

        g.config_manager = I18nManager(lang=lang)
        g.config_manager.set_language(lang)
        g.replacer = PlaceholderReplacer(g.config_manager)

    # @app.teardown_request
    # def teardown_request(exception=None):
    #     """请求结束后清除 g 避免缓存"""
    #     removed_config = g.pop("config_manager", None)
    #     removed_replacer = g.pop("replacer", None)

    if metrics:
        metrics.init_app(app)

    PrometheusMetrics(app, path="/metrics")

    return app


def configure_app(app, settings=None):
    app.config["JSON_AS_ASCII"] = False
    app.config["JSONIFY_MIMETYPE"] = "application/json;charset=utf-8"
    app.config["RESTFUL_JSON"] = {"ensure_ascii": False}

    if settings.SQLALCHEMY_DATABASE_URI:
        app.config["SQLALCHEMY_DATABASE_URI"] = settings.SQLALCHEMY_DATABASE_URI
        if settings.SQLALCHEMY_POOL_RECYCLE or settings.SQLALCHEMY_POOL_PRE_PING:
            app.config["SQLALCHEMY_ENGINE_OPTIONS"] = {
                "pool_recycle": settings.SQLALCHEMY_POOL_RECYCLE,
                "pool_pre_ping": settings.SQLALCHEMY_POOL_PRE_PING,
            }
        configure_db(app)


def configure_db(app):
    from src import models  # noqa

    db.init_app(app)

    # create tables
    # with app.app_context():
    #     db.create_all()


def configure_resources(app):
    api = Api(app)
    # 测试接口
    api.add_resource(Ping, "/")
    api.add_resource(FileDownload, "/file/<path:file_path>")

    # RAG接口
    api.add_resource(rag_handlers.Parser, "/rag_algorithm/parser")
    api.add_resource(rag_handlers.Splitter, "/rag_algorithm/splitter")
    api.add_resource(rag_handlers.ReprocessResource, "/rag_algorithm/reprocess")
    api.add_resource(rag_handlers.KeywordResource, "/rag_algorithm/keywords")
    api.add_resource(rag_handlers.SummaryResource, "/rag_algorithm/summary")
    api.add_resource(rag_handlers.QAResource, "/rag_algorithm/qa_extractor")
    api.add_resource(rag_handlers.FunctionList, "/rag_algorithm/function_list")
    api.add_resource(RagasEvaluation, "/rag_algorithm/ragas_evaluate")
    api.add_resource(rag_handlers.VideoParser, "/rag_algorithm/video"),
    api.add_resource(rag_handlers.VideoASRCallback, "/rag_algorithm/video_callback"),
    api.add_resource(rag_handlers.VideoASRDownload, "/rag_algorithm/video_download")
    api.add_resource(rag_handlers.ExtractFrame, "/rag_algorithm/extract_frame")

    # python代码组件接口
    api.add_resource(rag_handlers.CodeRunner, "/rag_algorithm/code_runner")
    api.add_resource(rag_handlers.JsonRepair, "/rag_algorithm/json_repair")

    # TODO 这些路由注册应该统一在 user/pacakge 路由下
    api.add_resource(PackageCreate, "/user/<int:user_id>/package")
    api.add_resource(PackageDetail, "/user/<int:user_id>/package/<package_code>")
    api.add_resource(
        PackageEntryCode, "/user/<int:user_id>/package/<package_code>/entry"
    )
    # 注册到 /api/adapters/*
    app.register_blueprint(adapter_api)
    api.add_resource(
        PackageExecution,
        "/user/<int:user_id>/package/execution",
        "/user/<int:user_id>/package/<package_code>/execution",
    )
    api.add_resource(AutoAgent, "/auto_agent/execute")
    api.add_resource(GraphRAGExtract, "/graphrag/extract")
    api.add_resource(GraphRAGMap, "/graphrag/query/global/map")

    # redis缓存接口
    api.add_resource(RedisManager, "/redis_cache")
    api.add_resource(HtmlManager, "/parse_html")
    api.add_resource(ParseSubUrls, "/parse/parse_sub_urls")
    api.add_resource(ParseWeb, "/parse/parse_url")
    api.add_resource(CrawlJinaReader, "/parse/crawl_jina_reader")
    api.add_resource(CrawlFire, "/parse/crawl_fire")
    api.add_resource(CrawlFireStatus, "/parse/crawl_fire/task_status")
    api.add_resource(AuthVerf, "/parse/outer_auth_verf")
    api.add_resource(FeishuGetToken, "/parse/feishu/token")
    api.add_resource(FeishuGetDocInfo, "/parse/feishu/doc-info")
    api.add_resource(FeishuGetDocDetail, "/parse/feishu/doc-detail")

    # 转发到fastapi的接口
    for endpoint in FORWARD_ENDPOINTS:
        api.add_resource(
            ForwardResource, endpoint, endpoint=f"forward_{endpoint.replace('/', '_')}"
        )
    for endpoint in FORWARD_ENDPOINTS_SSE:
        api.add_resource(
            SSEProxyResource,
            endpoint,
            endpoint=f"forward_{endpoint.replace('/', '_')}",
            resource_class_kwargs={"endpoint_name": endpoint},
        )

    # mem0 vector 记忆向量化
    api.add_resource(AddVector, "/vector/memory/insert")
    api.add_resource(QueryVector, "/vector/memory/query")
    api.add_resource(DeleteVector, "/vector/memory/delete")
    api.add_resource(UpdateVector, "/vector/memory/update")


class Ping(Resource):
    @staticmethod
    def get():
        return {"code": 200, "msg": "succeed", "result": ""}

    @staticmethod
    def post():
        print("=" * 100, "\n（回调测试）这里是接收到的参数：")
        print(request.json)
        return {"code": 200, "msg": "succeed", "result": request.json}


class FileDownload(Resource):

    def get(self, file_path):
        file_path = f"s3://{file_path}"
        filename = file_path.rsplit("/", 1)[-1]
        fs = S3Helper().get_s3_filesystem()
        try:
            with fs.open(file_path) as f:
                return send_file(
                    io.BytesIO(f.read()),
                    mimetype=mimetypes.guess_type(file_path)[0],
                    as_attachment=True,
                    download_name=filename,
                )
        except FileNotFoundError:
            return f"File not found with path:{file_path}", 404


if __name__ == "__main__":
    app = create_app()

    # executor_map = {}
    # # 初始化KVCacheData，并传递Flask应用实例
    # kv_cache = KVCacheData(app)
    # RedisManager.kv_cache = kv_cache
    app.run(host="0.0.0.0", port=50000, debug=True)
```

> **面试要点**：
> 
> - 双应用如何共享存储？—— 通过环境配置（Nacos）加载同一套 Redis/MySQL/ES/Milvus/S3 连接。
> 
> - Flask 侧为何用 `gunicorn + gevent`？—— `gevent.monkey.patch_all()` 让阻塞 IO（网络/S3/DB）协程化，配合线程池承载长耗时 RAG 任务。
> 
> - Pinpoint 接入三步：`before_request` 读头 → `PinpointContext`（contextvars）→ `PinPointMiddleWare` 包装 wsgi\_app；`use_thread_local_context()` 保证 gevent 协程间上下文隔离。
> 
> - 国际化：`X-LANG` 请求头（仅允许 en\_US/zh\_CN）→ `g.config_manager` \+ `g.replacer`（占位符替换），错误消息里 `{src.xxx.yyy}` 即多语言 key。
> 
> 

### **3\.2 RAG 核心处理器与异步调度（****`C:\Users\shilei.he\PA\af-rag-server\src\rag_algorithm\handlers.py`****，895 行）**

`handlers.py` 是 RAG 服务的 HTTP 入口层（全部为 Flask\-RESTful Resource），核心机制：

- **双通道异步**：携带 `callback_url` → 线程池（`ThreadPoolExecutor`，每个任务类型独立 3 worker）或 Huey Redis 队列；无回调 → 同步执行。

- **`handle_rag_process`**：统一入参过滤（`func_args`）、任务 ID 生成、渗透参数（request\_id / trans\_metadata）、结果落 S3、回调重试。

- **`Parser`**：按文件大小选择解析队列（\<1MB SMALLFILE / \>30MB BIGFILE / 其余 FILE），实时解析走 maip 专属 OCR 通道。

#### **3\.2\.1 异步调度核心（get\_executor / run\_task\_asynchronous / handle\_rag\_process）**

```Python
executor_map = {}


def get_executor(task_type):
    global executor_map
    if task_type not in executor_map:
        executor_map[task_type] = ThreadPoolExecutor(max_workers=3)
    return executor_map[task_type]


def run_task_asynchronous(
    target_func,
    func_kwargs,
    task_id,
    request_id,
    save_result_to_s3,
    task_type,
    callback_url=None,
    trans_metadata=None,
):
    logger.info(f"run task asynchronous:【{task_id=}】【{request_id=}】")

    try:
        res = target_func(**func_kwargs)

        if task_type in ["parser", "parser_realtime"]:
            res, error_pages = res

        if save_result_to_s3:
            save_path = S3Helper.get_s3_config_save_path(
                task_type=task_type, task_id=task_id
            )
            for chunk in res:
                if not isinstance(chunk, str):
                    chunk["id_"] = chunk.get("id_", str(uuid.uuid4())).replace("-", "")
            S3Helper().save_chunks_json(res, save_path)
            return_json = {
                "code": 0,
                "msg": "success",
                "task_id": task_id,
                "result_path": save_path,
            }
        else:
            return_json = {
                "code": 0,
                "msg": "success",
                "task_id": task_id,
                "result": res,
            }

        if task_type in ["parser", "parser_realtime"]:
            return_json["error_pages"] = error_pages

    except ApiError as e:
        return_json = {
            "code": e.code,
            "msg": e.msg,
            "task_id": task_id,
            "result": [],
        }

    except Exception as e:
        logger.exception(e)
        return_json = {
            "code": -1,
            "msg": "未知异常，请联系管理员",
            "task_id": task_id,
            "result": [],
        }

    # 加上渗透参数
    return_json["request_id"] = request_id
    return_json["trans_metadata"] = trans_metadata

    logger.info(f"【{task_id=}】【{callback_url=}】处理结果: {return_json}")
    if callback_url:
        # 避免异步回调过快，agentflow数据库未写入
        for _ in range(3):
            time.sleep(1)
            try:
                resp = requests.post(callback_url, json=return_json)
                logger.info(f"【{task_id=}】回调结果: {resp.status_code} {resp.text}")

                if resp.status_code != 502:
                    break
            except Exception as e:
                logger.exception(e)

    return return_json
```

```Python
def handle_rag_process(
    req_json,
    target_func,
    func_args,
    save_result_to_s3=False,
    task_type="unknown",
    task_id=None,
    worker_type="thread",
    worker_task=None,
):
    """
    :param req_json: 接口获取到的json参数
    :param target_func: 处理函数
    :param func_args: 函数参数过滤列表
    :param save_result_to_s3: 结果是否保存到s3
    :param task_type: 任务类型（用于写入s3时创建文件路径）
    :param task_id: 任务id，用于追踪rag流程
    :param worker_type: 异步任务队列，worker类型
    :param worker_task: 异步任务队列，worker任务
    """
    task_id = task_id or str(uuid.uuid4())
    request_id = req_json.get("request_id")
    if isinstance(func_args, dict):
        func_kwargs = {k: req_json.get(v) for k, v in func_args.items()}
    else:
        func_kwargs = dict((k, v) for k, v in req_json.items() if k in func_args)
    logger.info(f"【{task_id=}】【{request_id=}】【{req_json=}】")
    logger.info(f"【{task_id=}】【{request_id=}】【{func_kwargs=}】")

    # 渗透参数
    trans_metadata = req_json.get("trans_metadata")
    callback_url = req_json.get("callback_url")
    if callback_url:
        # 异步
        if worker_type == "thread":
            executor = get_executor(task_type)
            executor.submit(
                run_task_asynchronous,
                target_func,
                func_kwargs,
                task_id,
                request_id,
                save_result_to_s3,
                task_type,
                callback_url,
                trans_metadata,
            )
            qsize = executor._work_queue.qsize()
        else:
            job = worker_task(
                target_func,
                func_kwargs,
                task_id,
                request_id,
                save_result_to_s3,
                task_type,
                callback_url,
                trans_metadata,
            )
            logger.info(f"【{task_id=}】【{job=}】")
            qsize = worker_task.huey.pending_count()

        return_json = {
            "code": 0,
            "msg": "asynchronous",
            "task_id": task_id,
            "tasks_number_of_queue": qsize,
        }
    else:
        # 同步
        return_json = run_task_asynchronous(
            target_func,
            func_kwargs,
            task_id,
            request_id,
            save_result_to_s3,
            task_type,
            callback_url,
            trans_metadata,
        )

    return return_json
```

> **面试要点**：
> 
> - `func_args` 过滤：只把接口 JSON 中命中的字段传给目标函数，避免透传脏参数；也支持 dict 形式做 key 重映射（`{k: req_json.get(v)}`）。
> 
> - `executor._work_queue.qsize()`（线程池排队数）与 `worker_task.huey.pending_count()`（Redis 待执行数）：返回队列压力，供 Java 侧感知任务积压。
> 
> - 回调前 `time.sleep(1)` \+ 502 重试：避免 agentflow 数据库尚未落库时回调落空；502 代表网关暂不可用，其它状态码直接 break。
> 
> - `chunk["id_"] = chunk.get("id_", str(uuid.uuid4())).replace("-","")`：为每个 chunk 生成 32 位无连字符 ID，保证重复回调幂等落库。
> 
> 

#### **3\.2\.2 Parser（文档解析入口 \+ 队列选择）**

```Python
class Parser(Resource):
    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @dataschema(
        {
            "request_id": str,
            "callback_url": str,
            "trans_metadata": dict,
            "source_metadata": dict,
            Optional("method", default="general"): In(
                ["general", "vlm", "intelli_medical_insurance"]
            ),
            Optional("parameters", default={}): {
                "channel_name": str,
                "ocr_url": str,
                Optional("parse_strategy", default=[]): list,
                Optional("return_full_document", default=True): bool,
                "vlm_name": str,
                "vlm_url": str,
                "system_prompt": str,
                "user_prompt": str,
            },
            Required("file_download_url"): str,
            "file_name": str,
            Optional("is_realtime", default=False): bool,
            Optional("priority", default=0): int,
        }
    )
    def post(self, **req_json):
        replacer = g.replacer
        req_json.update({"replacer": replacer})

        # 识别文件类型
        file_download_url = req_json.get("file_download_url")
        if req_json.get("file_name"):
            file_name = req_json.get("file_name")
        else:
            file_name = file_download_url.split("/")[-1]
            req_json["file_name"] = file_name
        file_type = get_file_type(file_name)
        file_size = S3Helper().get_file_size(file_download_url)

        # 判断选择的解析器是否支持该文件格式
        method = req_json.get("method")
        if file_type not in PARSING_METHODS[method].keys():
            raise ApiError(
                code=-1,
                msg=replacer.replace(
                    "{src.rag_algorithm.parser.common.support_file_type}"
                ),
            )

        parameters = req_json.get("parameters") or {}
        is_realtime = req_json.get("is_realtime")
        if is_realtime:
            # 实时文档解析需要使用单独 ocr 资源
            parameters["channel_type"] = "maip"
            parameters["use_async_ocr"] = False
            parameters["ocr_url"] = settings.OCR_URL_MAIP_REALTIME
            parameters["ocr_url_excel"] = settings.OCR_URL_MAIP_REALTIME_EXCEL
            parameters["parse_strategy"] = [
                "content_extract",
                "image_extract",
                "html_describe",
            ]
        else:
            # 非实时文档解析需要判断渠道
            channel_name = parameters.get("channel_name")
            channel_type = channel_name.split("-")[0] if channel_name else None
            parameters["channel_type"] = channel_type
            parameters["use_async_ocr"] = settings.USE_ASYNC_OCR

            if channel_type and channel_type == "maip":
                # maip 预置ocr服务需要使用svc直连接口
                parameters["ocr_url"] = settings.OCR_URL_MAIP
                parameters["ocr_url_excel"] = settings.OCR_URL_MAIP_EXCEL

            if not isinstance(parameters.get("parse_strategy"), list):
                parameters["parse_strategy"] = []

        org_code = request.headers.get("Org-Code")
        parameters["org_code"] = org_code
        req_json["parameters"] = parameters
        task_type = "parser_realtime" if is_realtime else "parser"
        callback_url = req_json.get("callback_url")
        if callback_url:
            # 判断文件大小
            from src.rag_algorithm import tasks_file_parsing

            priority = req_json.get("priority")
            task_id = str(uuid.uuid4())
            if file_size < 1:
                # 小文件解析队列
                queue = tasks_file_parsing.OcrTaskQueueEnum.SMALLFILE
            elif file_size > 30:
                # 大文件解析队列
                queue = tasks_file_parsing.OcrTaskQueueEnum.BIGFILE
            else:
                queue = tasks_file_parsing.OcrTaskQueueEnum.FILE

            req_json["queue"] = queue
            ocr_task = tasks_file_parsing.ocr_convert_to_pdf
            job = ocr_task(task_id, req_json)
            logger.info(f"【{task_id=}】【{job=}】【{req_json=}】")

            return {
                "code": 0,
                "msg": "asynchronous",
                "task_id": task_id,
                "task_number_of_queue": ocr_task.huey.pending_count(),
            }

        else:
            # 同步调用不支持解析大文件
            if file_size > settings.OCR_BIGFILE_SIZE and file_type in [
                ".pdf",
                ".docx",
                ".doc",
                ".pptx",
                ".ppt",
                ".xls",
                ".xlsx",
            ]:
                return {
                    "code": -1,
                    "msg": "大文件上传不支持同步解析",
                }

            return handle_rag_process(
                req_json=req_json,
                target_func=file_parsing,
                func_args=[
                    "source_metadata",
                    "method",
                    "parameters",
                    "file_download_url",
                    "file_name",
                    "replacer",
                ],
                save_result_to_s3=True,
                task_type=task_type,
            )
```

> **面试要点**：
> 
> - 队列三档：`file_size < 1MB → SMALLFILE`，`> 30MB → BIGFILE`，中间 `FILE`，提交给 Huey 的 `ocr_convert_to_pdf`（`@huey_ocr_file_priority.priority_task()`）。
> 
> - `is_realtime=true` 强制 `channel_type="maip"`、`use_async_ocr=False`，并使用 `OCR_URL_MAIP_REALTIME` 专属实时 OCR 通道；解析策略固定为 `content_extract + image_extract + html_describe`。
> 
> - 非实时时按 `channel_name.split("-")[0]` 取渠道；`maip` 渠道改用 svc 直连 `OCR_URL_MAIP / OCR_URL_MAIP_EXCEL`。
> 
> - `org_code` 从请求头 `Org-Code` 读取并注入参数，用于数据隔离；`g.replacer` 做多语言占位符替换（`{src.rag_algorithm.parser.common.support_file_type}` 即 i18n key）。
> 
> 

#### **3\.2\.3 Splitter / 关键词 / 摘要 / QA / 代码执行 / JSON 修复**

```Python
class Splitter(Resource):
    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @dataschema(
        {
            "is_realtime": bool,
            "request_id": str,
            "callback_url": str,
            "trans_metadata": dict,
            "method": str,
            "parameters": dict,
            "chunks": list,
            "chunks_path": str,
        }
    )
    def post(self, **req_json):
        is_realtime = req_json.get("is_realtime")
        task_type = "splitter_realtime" if is_realtime else "splitter"
        req_json.update({"replacer": g.replacer})
        return handle_rag_process(
            req_json=req_json,
            target_func=chunk_splitting,
            func_args=["method", "parameters", "chunks", "chunks_path", "replacer"],
            save_result_to_s3=True,
            task_type=task_type,
        )
```

```Python
class KeywordResource(Resource):
    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @dataschema(
        {
            "request_id": str,
            "callback_url": str,
            "trans_metadata": dict,
            Required("llm_parameters"): {
                Required("llm_name"): str,
                Required("llm_url"): str,
                "temperature": float,
                "max_tokens": int,
                "system_prompt": str,
                "user_prompt": str,
                "org_code": str,
            },
            Required("chunk_list"): [
                {Required("chunk_id"): str, Required("content"): str}
            ],
        }
    )
    def post(self, **req_json):
        from src.rag_algorithm.tasks import run_rag_keyword_task

        hdr = request.headers
        req_json["hdr"] = dict(hdr)
        org_code = hdr.get("Org-Code")
        llm_parameters = req_json.get("llm_parameters") or {}
        llm_parameters["org_code"] = org_code
        req_json["llm_parameters"] = llm_parameters

        return handle_rag_process(
            req_json=req_json,
            target_func=keyword_extracting,
            func_args=["llm_parameters", "chunk_list", "hdr"],
            task_type="keyword",
            worker_type="redis",
            worker_task=run_rag_keyword_task,
        )
```

> `SummaryResource` / `QAResource` 与 `KeywordResource` 结构完全一致，仅 `target_func=summary_extracting/qa_extracting`、`task_type=summary/qa_extract`、`worker_task=run_rag_summary_task/run_rag_qa_task`。三者都走 **redis worker**（Huey），而非线程池。
> 
> 

```Python
class CodeRunner(Resource):

    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @dataschema(
        {
            "is_realtime": bool,
            "request_id": str,
            "callback_url": str,
            "trans_metadata": dict,
            "code": str,
            "kwargs": dict,
            "func": str,
        }
    )
    def post(self, **req_json):
        is_realtime = req_json.get("is_realtime")
        task_type = "exec_code_realtime" if is_realtime else "exec_code"
        run_async = bool(req_json.get("callback_url"))
        req_json.update({'replacer': g.replacer})
        return handle_rag_process(
            req_json=req_json,
            target_func=exec_code,
            func_args=[
                "code",
                "kwargs",
                "func",
                'replacer'
            ],
            save_result_to_s3=run_async,
            task_type=task_type,
        )


class JsonRepair(Resource):
    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @dataschema(
        {
            Required("json_bad_string"): str,
            Required("json_keys"): list,
        }
    )
    def post(self, **req_json):
        json_string = req_json.get("json_bad_string")
        json_keys = req_json.get("json_keys")

        try:
            fix_count = 0
            max_count = 10
            # 尝试修复10次JSON字符串
            while fix_count < max_count:
                json_string = json_repair.repair_json(json_string, return_objects=True)
                if isinstance(json_string, dict):
                    break
                fix_count += 1

            json_values = {key: json_string.get(key, "") for key in json_keys}

            return {
                "code": 0,
                "msg": "",
                "data": {
                    "json_good_string": json_string,
                    "json_values": json_values,
                },
            }
        except Exception as e:
            logger.error(str(e))
            return {"code": -1, "msg": "修复JSON字符串接口错误", "data": {}}
```

> **面试要点**：`CodeRunner` 的 `save_result_to_s3=bool(callback_url)` —— 有回调才落 S3；`JsonRepair` 用 `json_repair` 循环最多 10 次直到能解析为 dict，用于清洗 LLM 输出的非标准 JSON。
> 
> 

#### **3\.2\.4 视频解析（VideoParser / VideoASRCallback / ExtractFrame）**

```Python
class VideoParser(Resource):
    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @dataschema(
        {
            "request_id": str,
            Required("callback_url"): str,
            "trans_metadata": dict,
            "source_metadata": dict,
            Optional("method", default="asr"): In(["asr"]),
            Required("parameters"): {
                Required("asr_model"): {
                    Required("engineModelType"): str,
                    Required("channel"): int,
                    Optional("vadMode", default=0): int,
                    Optional("speechSeparate", default=False): bool,
                    Optional("speedMode", default=False): bool,
                },
                Optional("video_frames", default=[]): list,
                "vlm_switch": bool,
                "vlm_name": str,
                "vlm_url": str,
                "system_prompt": str,
                "user_prompt": str,
            },
            Required("file_download_url"): str,
        }
    )
    def post(self, **req_json):
        # 识别文件类型
        file_download_url = req_json.get("file_download_url")
        file_name = file_download_url.split("/")[-1]
        req_json["file_name"] = file_name

        file_type = get_file_type(file_name)
        if file_type not in [
            ".mp4",
            ".mov",
            ".wmv",
            ".flv",
            ".avi",
            ".3gp",
            ".mkv",
        ]:
            raise ApiError(code=-1, msg=f"视频解析不支持文件类型: {file_type}")

        # 判断文件大小
        s3_helper = S3Helper()
        try:
            file_size = s3_helper.get_file_size(file_download_url)
        except Exception as e:
            logger.error(f"视频文件获取大小失败: {str(e)}")
            raise ApiError(code=-1, msg="视频文件获取失败")

        if file_size > 1024:
            raise ApiError(code=-1, msg="视频文件超过1G大小限制")

        org_code = request.headers.get("Org-Code")
        parameters = req_json.get("parameters")
        parameters["org_code"] = org_code

        if settings.ASR_CALLBACK:
            # 请求ASR录音文件转写接口
            asr_model_params = parameters.get("asr_model") or {}
            asr_helper = ASRHelper()
            audio_file_url = asr_helper.get_audio_file_url(file_download_url)
            task_id, file_id = asr_helper.offline_recognize_speech(
                audio_file_url, **asr_model_params
            )
            # 接口参数暂存Redis
            asr_task_key = f"asr_task:{task_id}"
            rc.set(asr_task_key, json.dumps(req_json))
            return {
                "code": 0,
                "msg": "asynchronous",
                "task_id": task_id,
                "tasks_number_of_queue": 0,
            }
        else:
            from src.rag_algorithm.tasks import run_rag_video_task

            return handle_rag_process(
                req_json=req_json,
                target_func=video_parsing,
                func_args=[
                    "source_metadata",
                    "method",
                    "parameters",
                    "file_download_url",
                    "file_name",
                ],
                save_result_to_s3=True,
                task_type="video_parsing",
                worker_type="redis",
                worker_task=run_rag_video_task,
            )
```

> `VideoASRCallback` 收到 ASR 异步回调后：从 Redis `asr_task:{task_id}` 取出原始参数 → 把 ASR 结果 JSON 存 S3（`settings.S3_ASR_SAVE_PATH + f"{task_id}.json"`）→ 回填 `asr_download_url` 后交给 `run_rag_video_task` 续跑。`ExtractFrame` 支持 `time_interval / frame_interval / key_frame` 三种抽帧模式，限制视频 ≤500MB。
> 
> 

### **3\.3 RAG 流水线编排（****`C:\Users\shilei.he\PA\af-rag-server\src\rag_algorithm\rag_flow.py`****，604 行）**

`rag_flow.py` 定义 RAG 全流程的纯函数编排层：解析 → 分块 → 后处理 → 关键词/摘要/QA 抽取 → 视频解析/抽帧。所有函数是 `handlers.py` 中 `handle_rag_process` 的 `target_func`。

#### **3\.3\.1 解析器注册表（PARSING\_METHODS）**

```Python
# 定义各解析器及其支持的格式
PARSING_METHODS = {
    "general": {
        ".pdf": OcrFileReader,
        ".docx": OcrFileReader,
        ".doc": OcrFileReader,
        ".pptx": OcrFileReader,
        ".ppt": OcrFileReader,
        ".xls": OcrFileReader,
        ".xlsx": OcrFileReader,
        ".png": OcrFileReader,
        ".jpg": OcrFileReader,
        ".jpeg": OcrFileReader,
        ".txt": GeneralTxtReader,
        ".csv": GeneralCsvReader,
        ".md": GeneralMarkdownTextReader,
        ".html": GeneralHtmlReader,
    },
    "vlm": {
        ".pdf": VLMParser,
        ".docx": VLMParser,
        ".doc": VLMParser,
        ".pptx": VLMParser,
        ".ppt": VLMParser,
        ".png": VLMParser,
        ".jpg": VLMParser,
        ".jpeg": VLMParser,
    },
    "intelli_medical_insurance": {
        ".docx": IntelliMdicalInsuranceParser,
    },
}
```

> **面试要点**：`PARSING_METHODS[method][file_type]` 双 key 索引 —— handlers\.py 用它在解析前校验"所选 method 是否支持该文件类型"，rag\_flow\.py 用它在运行时取出真正的 Reader 类。新增解析器 = 注册表加一行 \+ 实现 `load_data()` 返回 chunk\_nodes。
> 
> 

#### **3\.3\.2 file\_parsing（下载 → 转 PDF → 解析）**

```Python
def file_parsing(
    file_download_url: str,
    file_name: str,
    file_path: Optional[str] = "",
    method: Optional[str] = "general",
    parameters: Optional[Dict] = None,
    source_metadata: Optional[Dict] = None,
    **kwargs,
):
    parameters = parameters or {}
    replacer = kwargs.get("replacer")

    # 识别文件类型
    file_type = get_file_type(file_name)

    # 解析器
    FileReader = PARSING_METHODS[method][file_type]

    random_file_id = get_random_id()
    if not file_path:
        # 文件缓存目录
        cache_dir = get_cache_dir(random_file_id)
        # 下载/copy 文件
        local_file_name = f"{random_file_id}{file_type}"
        file_path = __download_file(file_download_url, cache_dir, local_file_name)

    if not parameters.get("convert_file"):
        try:
            if file_type == ".pdf":
                pdf_file_path = file_path
            else:
                pdf_file_path = convert_to_pdf(file_path)

            convert_s3_path = settings.S3_CONVERT_SAVE_PATH + f"{random_file_id}.pdf"
            S3Helper().upload_single_file(pdf_file_path, convert_s3_path)
        except:
            convert_s3_path = ""
        parameters["convert_file"] = convert_s3_path

    read_data = FileReader(**parameters).load_data(
        file_download_url, file_path, file_name, source_metadata
    )

    if issubclass(FileReader, OcrFileReader):
        chunk_nodes, error_pages = read_data
    else:
        chunk_nodes = read_data
        error_pages = []

    if "cache_dir" in locals() and os.path.exists(cache_dir):
        shutil.rmtree(cache_dir)

    chunks = [ck.to_pa_dict() for ck in chunk_nodes]
    if len(chunks) == 0:
        raise ApiError(
            code=-1,
            msg=replacer.replace("{src.rag_algorithm.parser.common.file_is_empty}"),
        )
    return chunks, error_pages
```

> **面试要点**：
> 
> - `convert_to_pdf` 统一把 docx/pptx/xls 等先转成 PDF 存 S3（`S3_CONVERT_SAVE_PATH`），OCR 只吃 PDF，天然统一了多格式入口。
> 
> - `OcrFileReader.load_data` 返回 `(chunk_nodes, error_pages)` 二元组，非 OCR 解析器返回 `chunk_nodes` \+ 空 `error_pages` —— `handlers.run_task_asynchronous` 据此把 error\_pages 一并透传给调用方。
> 
> - 临时缓存目录 `shutil.rmtree` 兜底清理；空解析结果抛 `ApiError`（i18n key：`file_is_empty`）。
> 
> 

#### **3\.3\.3 chunk\_splitting（自动分块方法探测 \+ 分发）**

```Python
def chunk_splitting(
    chunks: Optional[List[Dict]] = None,
    chunks_path: Optional[str] = None,
    method: Optional[str] = "general",
    parameters: Optional[Dict] = None,
    *args,
    **kwargs,
) -> List[Dict]:
    parameters = parameters or {}
    if chunks is None:
        assert chunks_path is not None, "缺少参数：chunks or chunks_path"
        chunks = S3Helper().load_chunks_json(chunks_path)

    if method == "video_asr":
        chunk_nodes = [PowerAgentVideoNode.from_pa_dict(ck) for ck in chunks]

    else:
        chunk_nodes = [PowerAgentTextNode.from_pa_dict(ck) for ck in chunks]

    if chunk_nodes:
        chunk_node = chunk_nodes[0]
        file_name = chunk_node.metadata.get("file_name")
        parser = chunk_node.metadata.get("parser_info", {}).get("parser")
        if parser == "OcrFileReader":
            file_type = os.path.splitext(file_name)[-1].lower()
            if method == "general" and file_type in [
                ".pdf",
                ".docx",
                ".doc",
                ".pptx",
                ".ppt",
                ".png",
                ".jpg",
                ".jpeg",
            ]:
                # html文件解析的通用分片方法使用 ocr 分片方法
                method = "ocr"
            elif file_type in [".xls", ".xlsx"]:
                ori_data = chunk_node.metadata.get("parser_info", {}).get("ori_data")
                if ori_data:
                    page = ori_data[0]
                    if page:
                        block_class, _, block_content, *block_base64 = page[0]
                        if block_content and isinstance(block_content, dict):
                            row = block_content.get("table_struct", {}).get("row")
                            if row and row > 20000:
                                method = "general"
                            else:
                                method = "ocr"
        elif parser == "VLMParser":
            method = "general"
    if method == "ocr":
        chunk_nodes = OcrChunker(**parameters, use_old_table_split=True).process(
            chunk_nodes, replacer=kwargs.get("replacer")
        )
    elif method == "general":
        chunk_nodes = GeneralSplitter(**parameters).split(chunk_nodes)
    elif method == "separator":
        chunk_nodes = SeparatorSplitter(**parameters).split(chunk_nodes)
    elif method == "intelli_medical_insurance":
        chunk_nodes = IntelliMedicalInsuranceSplitter().split(chunk_nodes)
    elif method == "separator_recursive":
        chunk_nodes = SeparatorRecursiveSplitter(**parameters).split(chunk_nodes)
    elif method == "video_asr":
        chunk_nodes = VideoASRSplitter(**parameters).split(chunk_nodes)
    else:
        raise ParameterException(f"unsupported chunk splitting method: {method}")

    chunks = [
        {
            **ck_nd.to_pa_dict(),
            "chunk_index": idx,
        }
        for idx, ck_nd in enumerate(chunk_nodes, 0)
    ]
    return chunks
```

> **面试要点**：
> 
> - **自动方法探测**：从 chunk 的 `metadata.parser_info.parser` 反查来源解析器。OCR 解析器来源 \+ 常规文件 → 强制 `method="ocr"`（按块语义切分）；Excel 来源则看 `table_struct.row`：行数 \>20000 的"大表"退化为 `general`（按 token 切），否则用 `ocr`（保留表格结构）。
> 
> - 分块结果统一追加 `"chunk_index"`，供后续召回 / 合并时还原顺序。
> 
> - 分块器是可插拔策略：`OcrChunker / GeneralSplitter / SeparatorSplitter / SeparatorRecursiveSplitter / IntelliMedicalInsuranceSplitter / VideoASRSplitter`，按 method 字典分发。
> 
> 

#### **3\.3\.4 reprocessing 与 LLM 抽取（merge/rerank/keyword/summary/qa）**

```Python
def reprocessing(
    query: str = "",
    chunks_list: Optional[List[Dict]] = None,
    chunks_path_list: Optional[list] = None,
    method: Optional[str] = "merge",
    parameters: Optional[Dict] = None,
) -> List[Dict]:
    parameters = parameters or {}

    if not chunks_list:
        if not chunks_path_list:
            return []
        chunks_list = []
        for chunks_path in chunks_path_list:
            chunks = S3Helper().load_chunks_json(chunks_path)
            chunks_list.append(chunks)

    assert isinstance(chunks_list, list) and isinstance(
        chunks_list[0], list
    ), f"请传入chunk的二维列表"
    chunk_nodes_list = []
    for chunks in chunks_list:
        chunk_nodes = [PowerAgentTextNode.from_pa_dict(ck) for ck in chunks]
        chunk_nodes_list.append(chunk_nodes)

    if method == "merge" or method in "多路召回合并":
        chunk_nodes = ChunkMerge(**parameters).merge(chunk_nodes_list)
        chunks = [ck_nd.to_pa_dict() for ck_nd in chunk_nodes]
    elif method == "rerank":
        chunks = ReRank(**parameters).reprocess(query, chunk_nodes_list)
    else:
        raise ApiError(code=-1, msg=f"不支持的召回方法: {method}")
    return chunks


def keyword_extracting(llm_parameters: Dict, chunk_list: List, hdr: Dict) -> List[Dict]:
    if not chunk_list:
        raise ApiError(code=-1, msg="切片内容为空")

    result = []
    keyword_extractor = KeywordExtractor(**llm_parameters, hdr=hdr)

    for chunk in chunk_list:
        try:
            keywords = keyword_extractor.extract_keywords(chunk["content"])
        except Exception as e:
            logger.exception(e)
            keywords = []

        result.append({"chunk_id": chunk["chunk_id"], "keywords": ",".join(keywords)})

    return result


def summary_extracting(llm_parameters: Dict, chunk_list: List, hdr: Dict) -> List[Dict]:
    if not chunk_list:
        raise ApiError(code=-1, msg="切片内容为空")

    result = []
    summary_extractor = SummaryExtractor(**llm_parameters, hdr=hdr)

    for chunk in chunk_list:
        try:
            summary_text = summary_extractor.extract_summary(chunk["content"])
        except Exception as e:
            logger.exception(e)
            summary_text = ""

        result.append({"chunk_id": chunk["chunk_id"], "summary": summary_text})

    return result


def qa_extracting(llm_parameters: Dict, chunk_list: List, hdr: Dict) -> List[Dict]:
    if not chunk_list:
        raise ApiError(code=-1, msg="切片内容为空")

    result = []
    qa_extractor = QAExtractor(**llm_parameters, hdr=hdr)

    for chunk in chunk_list:
        try:
            qa_list = qa_extractor.extract_qa(chunk["content"])
        except Exception as e:
            logger.exception(e)
            qa_list = []

        result.append({"chunk_id": chunk["chunk_id"], "qa_list": qa_list})

    return result
```

> **面试要点**：`keyword_extracting/summary_extracting/qa_extracting` 结构镜像 —— 每个 chunk 独立调 LLM，**单块失败降级为空**（`except Exception → 空值`），不拖垮整批；返回 `{chunk_id, keywords|summary|qa_list}` 供下游按 chunk 回填。`reprocessing` 支持 `merge`（多路召回合并去重）与 `rerank`（重排序）两种后处理。
> 
> 

#### **3\.3\.5 video\_parsing（ASR 分句 \+ 关键帧 \+ VLM 描述）**

```Python
def video_parsing(
    file_download_url: str,
    file_name: str,
    method: Optional[str] = "asr",
    parameters: Optional[Dict] = None,
    source_metadata: Optional[Dict] = None,
    asr_download_url: Optional[str] = None,
    **kwargs,
):
    asr_segments = []
    asr_helper = ASRHelper()
    s3_helper = S3Helper()
    if settings.ASR_CALLBACK and asr_download_url:
        data = s3_helper.load_chunks_json(asr_download_url)
        if data:
            asr_callback_json = data[0]
            asr_segments = asr_callback_json.get("data", {}).get("nbest", []) or []
    else:
        # 请求ASR录音文件转写接口
        asr_model_params = parameters.get("asr_model") or {}
        audio_file_url = asr_helper.get_audio_file_url(file_download_url)
        request_id, file_id = asr_helper.offline_recognize_speech(
            audio_file_url, **asr_model_params
        )
        asr_segments = asr_helper.offline_polling_result(
            request_id=request_id, file_id=file_id
        )

    if not asr_segments:
        raise ApiError(code=-1, msg="ASR语音转写结果为空")

    chunk_nodes = []
    for video_sentence in asr_segments:
        # 首帧时间
        start_time_millisecond = video_sentence.get("start_time")
        start_time = int(start_time_millisecond / 1000)
        # 尾帧时间
        end_time_millisecond = video_sentence.get("end_time")
        end_time = int(end_time_millisecond / 1000)
        # 中间帧时间
        middle_time = int((start_time + end_time) / 2)

        video_text = f"- 视频人声内容：{video_sentence.get('sentence')}\n- 视频时间区间：{convert_video_time(start_time)}-{convert_video_time(end_time)}\n"
        now_time = datetime.datetime.now().strftime(r"%Y-%m-%d %H:%M:%S")
        metadata = {
            "file_name": file_name,
            "create_date": now_time,
            "parser_info": {
                "parser": "VideoASRParser",
                "table_format": "MARKDOWN",
                "ori_data": video_sentence,
            },
            "convert_file": "",
        }
        chunk_nodes.append(
            PowerAgentVideoNode(
                text=video_text,
                metadata=metadata,
                source_metadata=source_metadata,
                location={"type": "pages", "value": []},
                start_time=start_time,
                end_time=end_time,
                middle_time=middle_time,
            )
        )
    ...
```

> `video_parsing` 每个 ASR 句段生成一个 `PowerAgentVideoNode`（带 `start_time/end_time/middle_time`）；若配置 `video_frames`，则按句段时间点抽帧、上传 S3，并可选开启 `vlm_switch` 用 VLM 模型对关键帧生成图文描述，拼接进 chunk 文本。
> 
> 

### **3\.4 完整 RAG 处理链**

```Plain Text
┌──────────────────────────────────────────────┐
                    │            Java 侧 agentflow-server           │
                    │  请求带 callback_url / request_id / Org-Code  │
                    └──────────────┬───────────────────────────────┘
                                   │ POST
              ┌────────────────────┼─────────────────────┐
              ▼                    ▼                     ▼
   ┌─────────────────┐   ┌──────────────────┐  ┌─────────────────┐
   │ /rag_algorithm/ │   │ /rag_algorithm/  │  │ /rag_algorithm/ │
   │  parser         │   │  splitter        │  │  code_runner    │
   └────────┬────────┘   └────────┬─────────┘  └────────┬────────┘
            │                     │                     │
            ▼                     ▼                     ▼
   handlers.handle_rag_process（统一入口）
   ├─ 有 callback_url → 异步
   │   ├─ thread 模式: ThreadPoolExecutor(task_type, max_workers=3)
   │   └─ redis  模式: Huey priority_task（keyword/summary/qa/video）
   └─ 无 callback_url → 同步直跑 run_task_asynchronous
            │
            ▼
   run_task_asynchronous
   ├─ target_func(**func_kwargs)   ← rag_flow 的纯函数
   │   file_parsing → chunk_splitting → reprocessing
   │   → keyword/summary/qa_extracting → video_parsing
   ├─ save_result_to_s3 → S3 JSONL（S3Helper.save_chunks_json）
   │   chunk["id_"] = uuid4 去连字符
   └─ callback_url → 3 次重试回调（sleep 1s，跳过 502）
            │
            ▼
   Java 侧写入 DB / 向量化入库 Milvus・ES
```

> **面试要点**：
> 
> - **同步/异步切换只在一个点**：`handle_rag_process` 判断 `callback_url`，上游不关心底层是线程池还是 Huey。
> 
> - 全链路幂等：每个任务生成 `task_id`（uuid4），chunk 带 `id_`，回调带 `request_id + trans_metadata` 渗透，失败可重放。
> 
> - 链路追踪闭环：Java 侧透传 `pinpoint-traceid/spanid`（app\.py `before_request` 读头 → PinpointContext）→ Python 侧处理后回调，两段链路在 Pinpoint 上串成一条 trace。
> 
> 

## **文档解析（OCR / VLM）**

### **4\.1 OCR 解析器（****`C:\Users\shilei.he\PA\af-rag-server\src\component\parser\ocr_parser.py`****，579 行）**

#### **4\.1\.1 block → 文本 转换（parser\_block\_to\_text / 表格标准结构）**

OCR 服务返回的是按页分组的 block 列表：`[block_class, pos, block_content, *base64]`。`parser_block_to_text` 把 block 还原为带格式文本 —— **标题加粗、有线表/无线表转 HTML 或 Markdown 表格、公式原样、二维码/印章丢弃**：

```Python
def parser_block_to_text(block: List[Any]) -> str:
    block_class, _, block_content, *block_base64 = block
    if "标题" in block_class:
        res = " ".join([_[0] for _ in block_content])
        return f"**{res}**"
    if block_class in ["文字", "下描述", "页眉", "上描述"]:
        res = " ".join([_[0] for _ in block_content])
        return res
    if block_class == "图片":
        # FIXME 因为上一步图片base64上传已经把 block_class 改成文字了，这里的逻辑实际不会运行到
        res = " ".join([_[0] for _ in block_content])

        return res
    if block_class == "图表":
        try:
            res = " ".join([_[0] for _ in block_content])
        except:
            res = ""
        return res
    if block_class == "页脚":
        res = " ".join([_[0] for _ in block_content])
        return res
    if block_class in ["有线表", "无线表"]:
        block, std_table = table_block_add_std_table(block)
        if std_table.get("HTML"):
            res = std_table["HTML"]
        else:
            res = "\n" + _table2text(block[2]["std_table"]["data"]["cells"]) + "\n"
        return res
    if block_class == "公式":
        return block_content
    if block_class in ["二维码", "印章"]:  # 不需要的
        return ""
    logger.info(f"block_class: {block_class} Neglected")
    return ""
```

`table_block_add_std_table` 把 OCR 的 `table_struct`（扁平 cell 数组 \+ 合并信息）归一化为 `std_table`（cells 二维矩阵 \+ merged\_cells \+ HTML/LaTeX/NL 四种形态），供下游表格还原 / 问答引用：

```Python
def table_block_add_std_table(table_block):
    """输入一个表格block，根据 table_struct 字段 更新/添加 std_table 字段"""
    new_table_block = copy.deepcopy(table_block)
    block_class, _, block_content, *_ = new_table_block
    assert block_class in {"有线表", "无线表"}, "Not a table block"

    html_table = ""

    if block_content and isinstance(block_content, dict):
        if block_content.get("describe_html", None):
            html_table = block_content["describe_html"]
        table_struct = block_content["table_struct"]

        nrow = table_struct["row"]
        ncol = table_struct["col"]
        cells = [["" for _ in range(ncol)] for _ in range(nrow)]
        merged_cells = []
        for cell in table_struct["cells"]:
            text = " ".join(cell["text"])
            start_row, start_col = cell["start_row"], cell["start_col"]
            end_row, end_col = cell["end_row"], cell["end_col"]
            cells[start_row][start_col] = text

            if start_row != end_row or start_col != end_col:
                merged_cells.append([start_row, start_col, end_row, end_col])
    else:
        block_content = {}
        new_table_block[2] = block_content
        cells = []
        merged_cells = []

    std_table = {
        "data": {"cells": cells, "merged_cells": merged_cells},
        "LATEX": "",
        "HTML": html_table,
        "NL": "",
        "offset": 0,
    }

    block_content["std_table"] = std_table
    return new_table_block, std_table
```

#### **4\.1\.2 OCR 结果后处理（\_parse\_ocr\_result：图片提取 / VLM 图片文字 / 表格）**

```Python
def _parse_ocr_result(self, ocr_res):
        parsed_res = []
        for page in ocr_res:
            new_page = []
            for block in page:
                block_class, _block_pos, block_content, *block_base64 = block
                if block_class == "图片":
                    block_class = "文字"
                    # 不提取图片文字，则将文字解析结果为空
                    for b in block_content:
                        b[0] = ""

                    if self.image_text_extract:
                        if block_base64:
                            try:
                                block_text = self.vlm_parser.request_vlm_model(
                                    block_base64[0]
                                )
                                block_content[0][0] = block_text
                            except:
                                pass

                    if self.image_extract:
                        # 提取文档图片，图片base64字符串需要上传s3，并把链接放在文本识别结果后面
                        if block_base64:
                            try:
                                s3_path = _upload_s3_image(block_base64[0])
                                if s3_path:
                                    if block_content:
                                        text = block_content[-1][0]
                                        block_content[-1][0] = text + " " + s3_path
                                    else:
                                        pos = _block_pos
                                        block_content = [
                                            [
                                                s3_path,
                                                pos[0][0],
                                                pos[0][1],
                                                pos[1][0],
                                                pos[1][0],
                                            ],
                                        ]
                            except:
                                pass

                elif block_class in ["有线表", "无线表"]:
                    if block_content and isinstance(block_content, dict):
                        if (
                            block_content.get("describe_html")
                            and not self.html_describe
                        ):
                            block_content["describe_html"] = ""

                # 删除base64字符串，避免解析文件内容过多，并兼容老版本解析结果
                new_page.append([block_class, _block_pos, block_content])

            parsed_res.append(new_page)

        return parsed_res
```

> **面试要点**：
> 
> - `parse_strategy` 三个开关：`content_extract`（是否真 OCR）/ `image_extract`（图片 base64 上传 S3，把链接拼到文本后，实现图文混排问答）/ `image_text_extract`（图片内文字用 VLM 提取）/ `html_describe`（表格深度解析保留 describe\_html，否则清空）。
> 
> - 图片 block 在 `_parse_ocr_result` 阶段就**改写为"文字"**，所以 `parser_block_to_text` 里"图片"分支实际到不了（源码 FIXME 注释已说明）。
> 
> - 处理完统一**剥离 base64**，避免文档 JSON 膨胀。
> 
> 

#### **4\.1\.3 load\_data（四条 OCR 通道选择）**

```Python
def load_data(
        self,
        file_download_url,
        file_path: str,
        file_name: str,
        source_metadata: Dict,
        extra_info: Optional[Dict] = None,
        fs: Optional[AbstractFileSystem] = None,
    ) -> List[PowerAgentTextNode]:

        if not file_name:
            file_name = os.path.split(file_path)[1]
        if not source_metadata:
            source_metadata = dict()

        is_excel = bool(re.match(r"(.*)\.xls(x?)$", file_name))
        if is_excel:
            # excel 文件走ocr excel解析接口
            res_json = self._request_ocr_api(
                gen_url=self.ocr_url_excel,
                file_path=file_path,
                file_name=file_name,
            )
        elif (
            self.channel_type
            and self.channel_type.lower() in SaasOcrParser.support_type()
        ):
            res_json = SaasOcrParser.parser(
                self.ocr_url, self.secret_key, file_path=file_path, fs=fs
            )
        elif self.use_async_ocr and settings.API_HOST:
            # 判断环境是否开启ocr proxy
            res_json = self._request_ocr_via_proxy(
                self.ocr_url_proxy, file_download_url, file_name
            )
        else:
            # 默认使用ocr
            res_json = self._request_ocr_api(
                gen_url=self.ocr_url, file_path=file_path, file_name=file_name
            )

        if res_json.get("error_code", "0") != "0":
            logger.error(f"Ocr parsing failed: 【{res_json=}】")
            raise ApiError(code=-1, msg=f"Ocr service parsing failed")

        # excel 结果格式适配
        if re.match(r"(.*)\.xls(x?)$", file_name) and isinstance(
            res_json["result"][0], dict
        ):
            # excel 接口的解析结果为：{'sheet_name': '事实与常识', 'res': [['有线表', [], {'table_struct': ...}]]}，转换为通用格式
            res_json["result"] = [page["res"] for page in res_json["result"]]

        ocr_res = self._parse_ocr_result(res_json["result"])
        ...
        error_pages = res_json.get("errorPages", [])
        return chunk_nodes, error_pages
```

> **面试要点**：
> 
> - **四条通道**优先级：① `.xls(x)` → 专用 excel OCR 接口；② saas 渠道（`channel_type` 命中 `SaasOcrParser.support_type()`）→ Saas OCR；③ 开启 `use_async_ocr` 且配置了 `API_HOST` → **proxy 异步轮询**（提交 job → 每 5s 轮询 `job_status_url`，状态码 `200=成功 / 227=处理中 / 425=失败`，超时 10 小时）；④ 默认同步直连 MAIP OCR。
> 
> - `return_full_document` 决定"整篇一个 chunk"还是"每页一个 chunk"，`location={"type":"pages","value":[页码]}` 记录归属页码，供后续检索定位。
> 
> - `error_pages` 原样透传给调用方（handlers 里的 `return_json["error_pages"]`）。
> 
> 

### **4\.2 VLM 多模态解析器（****`C:\Users\shilei.he\PA\af-rag-server\src\component\parser\vlm_parser.py`****，175 行）**

```Python
class VLMParser(BasicPowerAgentReader):
    """VLM parser."""

    def __init__(
        self,
        vlm_name: str,
        vlm_url: str,
        system_prompt: str,
        user_prompt: str,
        return_full_document: Optional[bool] = True,
        parse_strategy: Optional[list] = None,
        org_code: Optional[str] = None,
        **kwargs,
    ):
        self.vlm_name = vlm_name
        self.vlm_url = vlm_url
        self.system_prompt = system_prompt
        self.user_prompt = user_prompt
        self.return_full_document = return_full_document
        # 提取文档图片：图文混合问答需要开启
        parse_strategy = parse_strategy or []
        self.image_extract = "image_extract" in parse_strategy
        self.org_code = org_code
        self.__dict__.update(kwargs)

    def request_vlm_model(
        self,
        base64_string,
        temperature=0.7,
        top_p=0.8,
        repetition_penalty=1.05,
        max_tokens=4096,
    ):
        content = [
            {"type": "text", "text": self.user_prompt},
            {
                "type": "image_url",
                "image_url": {"url": f"data:image/jpeg;base64,{base64_string}"},
            },
        ]
        data = {
            "model": self.vlm_name,
            "messages": [
                {"role": "user", "content": content},
                {
                    "role": "system",
                    "content": [{"type": "text", "text": self.system_prompt}],
                },
            ],
            "temperature": temperature,
            "top_p": top_p,
            "repetition_penalty": repetition_penalty,
            "max_tokens": max_tokens,
            "stream": False,
        }
        headers = get_auth_headers(self.org_code)
        max_count = 3
        for i in range(max_count):
            try:
                resp = requests.post(self.vlm_url, json=data, headers=headers)
                logger.info(f"vlm_model: {self.vlm_name}, code: {resp.status_code}")

                if resp.status_code == 429:
                    # MAIP 限流等待60秒重试
                    time.sleep(60)
                    continue

                resp_json = resp.json()
                choices = resp_json.get("choices", [])
                content = choices[0]["message"]["content"] if choices else ""

                return content
            except Exception as e:
                logger.error(f"请求vlm解析失败: {str(e)}")
                continue

        return ""

    def load_data(
        self,
        file_download_url: str,
        file_path: str,
        file_name: str,
        source_metadata: Dict,
        **kwargs,
    ) -> List[PowerAgentTextNode]:
        # 默认在解析之前已经转换为PDF文件
        try:
            # 文件缓存目录
            random_file_id = get_random_id()
            cache_dir = get_cache_dir(random_file_id)
            parse_images = convert_pdf_to_jpeg(file_path, cache_dir)
        except Exception as e:
            logger.error(f"【{file_name=}】转换图片失败: \n{str(e)}")
            raise ApiError(code=-1, msg="转换图片失败")

        parse_results = []
        for image_path in parse_images:
            base64_string = encode_image(image_path)
            vlm_result = self.request_vlm_model(base64_string)
            if self.image_extract:
                s3_image_path = upload_s3_image(base64_string)
                if s3_image_path:
                    vlm_result = vlm_result + " " + s3_image_path

            parse_results.append(vlm_result)
        ...
        return chunk_nodes
```

> **面试要点**：
> 
> - 调用格式是 OpenAI 兼容接口：`messages` 里 system 走 `content` 数组，用户消息 `image_url` 传 `data:image/jpeg;base64,...`。
> 
> - 重试策略：最多 3 次；`429`（MAIP 限流）`sleep(60)` 后 continue，其它异常记日志后 continue；耗尽返回空串（下游降级为空文本）。
> 
> - `load_data` 把 PDF 逐页转 JPEG → 逐页调 VLM → 可选把图片上传 S3 并把链接拼在结果后；`return_full_document` 决定整篇合并 or 逐页拆 chunk。
> 
> 

## **分块（Chunking）**

### **5\.1 通用分块器（****`C:\Users\shilei.he\PA\af-rag-server\src\component\chunk_split\general_chunker.py`****，122 行）**

```Python
class GeneralSplitter:
    def __init__(
        self,
        chunk_size: int = 1024,
        chunk_overlap: int = 200,
        separator: str = None,
        chunk_preprocessing=None,
        association_info=None,
        **kwargs
    ) -> None:
        """
        Initialize GeneralSplitter.
        """
        if chunk_preprocessing is None:
            chunk_preprocessing = []
        if association_info is None:
            association_info = []
        self.chunk_preprocessing = chunk_preprocessing
        self.association_info = association_info
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self.separator = separator or " "

    def split(self, chunk_nodes: List[PowerAgentTextNode]) -> List[PowerAgentTextNode]:
        new_nodes = []
        for node in chunk_nodes:
            # ocr、vlm解析结果，需先按页切片，否则不知道切片的页码
            if (
                "parser_info" in node.metadata
                and node.metadata["parser_info"].get("parser") == "OcrFileReader"
                and node.metadata["parser_info"].get("ori_data")
            ):
                start_page = min(node.location["value"])
                res_json = node.metadata["parser_info"].get("ori_data")
                new_metadata = copy.deepcopy(node.metadata)
                new_metadata["parser_info"] = {"parser": "OcrFileReader"}

                for page_num, page in enumerate(res_json):
                    page_label = page_num + start_page
                    page_text = ""
                    if isinstance(page, dict):
                        page = page.get("res", [])
                    for block_num, block in enumerate(page):
                        page_text += parser_block_to_text(block) + "\n"

                    if not page_text.strip():
                        continue

                    location = {"type": "pages", "value": [page_label]}
                    new_nodes.append(
                        PowerAgentTextNode(
                            text=page_text,
                            metadata=new_metadata,
                            source_metadata=node.source_metadata,
                            location=location,
                        )
                    )
            elif (
                "parser_info" in node.metadata
                and node.metadata["parser_info"].get("parser") == "VLMParser"
                and node.metadata["parser_info"].get("ori_data")
            ):
                start_page = min(node.location["value"])
                res_json = node.metadata["parser_info"].get("ori_data")
                new_metadata = copy.deepcopy(node.metadata)
                new_metadata["parser_info"] = {"parser": "VLMParser"}

                for page_num, page_text in enumerate(res_json):
                    if not page_text.strip():
                        continue

                    page_label = page_num + start_page
                    location = {"type": "pages", "value": [page_label]}
                    new_nodes.append(
                        PowerAgentTextNode(
                            text=page_text,
                            metadata=new_metadata,
                            source_metadata=node.source_metadata,
                            location=location,
                        )
                    )
            else:
                new_nodes.append(node)

        # 重置解析信息
        for node in new_nodes:
            if "parser_info" in node.metadata:
                parser_info = node.metadata["parser_info"]
                parser_info = {
                    "parser": parser_info.get("parser", "") + ", ChunkSplitter"
                }
            else:
                parser_info = {"parser": "ChunkSplitter"}
            node.metadata["parser_info"] = parser_info

        documents = [ck_nd.to_document() for ck_nd in new_nodes]
        splitter = TokenTextSplitter(
            chunk_size=self.chunk_size,
            chunk_overlap=self.chunk_overlap,
            separator=self.separator,
            include_metadata=True,
        )
        split_documents = splitter(nodes=documents)
        processor = ChunkProcessor(
            chunk_preprocessing=self.chunk_preprocessing,
            association_info=self.association_info,
        )
        process_chunks_res = processor.process_chunks(split_documents)
        chunk_nodes = [
            PowerAgentTextNode.from_document(chunk) for chunk in process_chunks_res
        ]
        return chunk_nodes
```

> **面试要点**：
> 
> - **两段式分块**：先按页（从 `ori_data` 逐页还原 `parser_block_to_text` 文本，打页码标签）→ 再对整文档用 LlamaIndex `TokenTextSplitter`（按 token 计 1024，overlap 200）。
> 
> - 分块前 `parser_info.parser` 标记重置为 `OcrFileReader/VLMParser + ChunkSplitter`，标识已切分，避免重复切片；`parser_block_to_text` 复用 ocr\_parser 的 block 还原逻辑，保证分块与解析文本一致。
> 
> - 支持 OCR / VLM 两种来源的 `ori_data` 结构（list\[page\] vs list\[str\]），其它来源直接透传。
> 
> 

### **5\.2 预处理 \+ 关联信息附加（****`C:\Users\shilei.he\PA\af-rag-server\src\component\chunk_split\chunk_processor.py`****，130 行）**

```Python
# 文本预处理函数定义
def remove_line_breaks(text: str) -> str:
    # 移除换行符
    return text.replace('\n', '')


def remove_urls(text: str) -> str:
    """
    移除以 http://、https:// 或 www. 开头的 URL，
    并且只匹配 ASCII 范围内的 URL 字符，确保后面的中文不会被吞掉。
    """
    # 移除所有空格
    text_no_space = text.replace(" ", "")
    # \b           单词边界
    # (?:https?://|www\.)  匹配 http:// https:// 或 www.
    # [A-Za-z0-9\-._~:/?#[\]@!$&'()*+,;=%]+  URL 中可出现的 ASCII 字符集合
    url_pattern = r'(?:https?://|www\.)[A-Za-z0-9\-._~:/?#[\]@!$&\'()*+,;=%]+'
    return re.sub(url_pattern, '', text_no_space)


def replace_whitespace_line_tabs(text: str) -> str:
    # 将多个空格、换行符、制表符统一替换为一个空格
    return re.sub(r'[\s\n\t]+', ' ', text)


def remove_emails(text: str) -> str:
    # 更通用地移除电子邮件地址
    return re.sub(r'[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}', '', text)


# 映射预处理步骤名称到对应函数
_PREPROCESS_FUNCS: Dict[str, Callable[[str], str]] = {
    "remove_line_breaks": remove_line_breaks,
    "remove_urls": remove_urls,
    "replace_whitespace_line_tabs": replace_whitespace_line_tabs,
    "remove_emails": remove_emails,
}


class ChunkProcessor:
    """
    文本块处理器类，用于根据配置进行预处理并附加元信息。

    属性：
        chunk_preprocessing: 要应用的预处理步骤名称列表。
        association_info: 要附加的元信息字段名列表。
    """

    def __init__(
            self,
            chunk_preprocessing: List[str] = None,
            association_info: List[str] = None,
    ):
        self.chunk_preprocessing = chunk_preprocessing or []
        self.association_info = association_info or []

    @staticmethod
    def extract_headings(text: str) -> List[str]:
        """
        提取 Markdown 风格的标题（以1-6个#号开头的行）
        """
        return re.findall(r'^(?:#{1,6})\s*(.+)', text, flags=re.MULTILINE)

    def preprocess_text(self, text: str) -> str:
        """
        对文本应用配置好的预处理步骤
        """
        for step in self.chunk_preprocessing:
            func = _PREPROCESS_FUNCS.get(step)

            if func:
                text = func(text)
        return text

    def process_chunks(self, nodes: List[BaseNode]) -> List[BaseNode]:
        """
        接受 TextNode 列表，先转为 dict 处理后，再转换回 TextNode。
        """
        processed_nodes: List[BaseNode] = []

        for node in nodes:
            if not isinstance(node, dict):
                data = node.dict()
            else:
                data = node
            text = data.get('text', '')
            # 执行预处理
            processed_text = self.preprocess_text(text)

            # 拷贝一份，避免修改原始数据
            new_ck = data.copy()
            meta = data.get('metadata', {}).get('metadata', {})
            parts = []
            # 如果配置中包含 associated_filename，则提取文件名
            if 'associated_filename' in self.association_info:
                if meta.get('file_name'):
                    parts.append(f"associated_filename: {meta.get('file_name')}")
                else:
                    parts.append(f"associated_filename: None")

            # 如果配置中包含 associated_headings，则提取标题
            if 'associated_headings' in self.association_info:
                if 'extra_content' in meta:
                    title = meta['extra_content'].get('title')
                    if title:
                        parts.append(f"associated_headings: {title}")
                    else:
                        if self.extract_headings(text):
                            parts.append(f"associated_headings: {self.extract_headings(text)}")
                        else:
                            parts.append(f"associated_headings: None")
                else:
                    if self.extract_headings(text):
                        parts.append(f"associated_headings: {self.extract_headings(text)}")
                    else:
                        parts.append(f"associated_headings: None")
            parts.append(processed_text)
            new_ck['text'] = "\n".join(
                "\n".join(p) if isinstance(p, list) else p
                for p in parts
            )
            processed_nodes.append(TextNode(**new_ck))
        return processed_nodes
```

> **面试要点**：
> 
> - 预处理按名字查 `_PREPROCESS_FUNCS` 注册表依次应用（可插拔，配置驱动）。
> 
> - `associated_filename / associated_headings` 把"文件名 \+ 标题"以 `key: value` 形式**拼接到 chunk 文本头部**——使这些上下文进入 embedding 与 LLM 输入，提升"跨 chunk 结构问答"的召回质量（标题正则 `^(?:#{1,6})\s*(.+)` MULTILINE 抓 Markdown 标题）。
> 
> 

## **检索与重排**

### **6\.1 Milvus 向量检索（****`C:\Users\shilei.he\PA\af-rag-server\src\component\retrieve\milvus_retrieve.py`****，54 行）**

```Python
class MilvusRetriever(BaseRetriever):
    def __init__(self, documents: List[Node], read_only_milvus_collection: str, embed_model: BaseEmbedding, limit, **kwargs):
        super().__init__(**kwargs)
        milvus_config = ConfigTool().get_config('milvus')['connection']
        connections.connect(
            alias=milvus_config['alias'],
            user=milvus_config['user'],
            password=milvus_config['password'],
            host=milvus_config['host'],
            port=milvus_config['port']
        )
        self.md5_document_map = defaultdict(list)
        for node in documents:
            node_id = node.node_id
            self.md5_document_map[node_id].append(node)
        self.milvus_collection = Collection(read_only_milvus_collection)
        self.embed_model = embed_model
        self.limit = limit

    def _retrieve(self, query_bundle: QueryBundle) -> List[NodeWithScore]:
        query = query_bundle.query_str
        embed = self.embed_model.get_text_embedding(query)

        # 单文档问答时使用环境变量（仅限单线程测试时用）
        retrieve_filter_by_file_name = os.environ.get('retrieve_filter_by_file_name')
        expr = f"file_name == '{retrieve_filter_by_file_name}'" if retrieve_filter_by_file_name else None

        result = self.milvus_collection.search(
            data=[embed],
            anns_field="embedding",
            param={"metric_type": "IP", "params": {"nprobe": 512}},
            limit=self.limit * 2,
            expr=expr
        )
        nodes = []
        for hit in result[0]:
            if hit.id in self.md5_document_map:
                for node in self.md5_document_map[hit.id]:
                    nodes.append(NodeWithScore(node=node, score=hit.distance))

        # 增加一个sort
        nodes.sort(key=lambda _: _.score, reverse=True)
        return nodes[:self.limit]
```

> **面试要点**：
> 
> - 采用 LlamaIndex `BaseRetriever` 接口，`_retrieve` 里完成：query 嵌入 → `collection.search`（**内积 IP** 度量，nprobe=512，召回 `limit*2`）→ 按 `hit.id` 回表本地 `md5_document_map` 映射成 NodeWithScore → **按 score 降序排序截断**。
> 
> - `expr` 支持按 `file_name` 过滤（环境变量驱动，单文档测试用），生产上按 `org_code`/collection 隔离。
> 
> - `md5_document_map` 把文档 node\_id 与检索命中 ID 对齐，避免依赖 Milvus 存全量字段。
> 
> 

### **6\.2 多路召回聚合（****`C:\Users\shilei.he\PA\af-rag-server\src\component\retrieve\multiway_retrieve.py`****，60 行）**

```Python
class MultiWayRetriever(BaseRetriever):
    def __init__(self, custom_embedding: BaseEmbedding, expand_topk: int, retrievers: Dict[str, BaseRetriever],
                 query_handler, use_query_translation_retriever, **kwargs):
        super().__init__(**kwargs)
        self.retrievers = retrievers
        self.expand_topk = expand_topk
        self.custom_embedding = custom_embedding
        self.query_handler = query_handler
        self.use_query_translation_retriever = use_query_translation_retriever

    def _retrieve(self, query_bundle: QueryBundle) -> List[NodeWithScore]:
        print("更改前的query:", query_bundle.query_str)
        if self.use_query_translation_retriever:
            query_bundle, _ = self.query_handler.query_HyDE(query_bundle.query_str)
        print("召回的query:", query_bundle.query_str)

        final_results = []
        for key, retriever in self.retrievers.items():
            nodes = retriever.retrieve(query_bundle)
            if key == 'TfIdfVectorIndexRetriever':
                for node in nodes:
                    keywords_text = node.node.text
                    origin_text = node.metadata["origin_text_before_keywords"]
                    node.node.text = origin_text
                    node.metadata["keywords_text"] = keywords_text
                    # 存储node被抽取的retriever方式
                    node.metadata['retriever'] = key
                    final_results.append(node)
            else:
                for node in nodes:
                    # 存储node被抽取的retriever方式
                    node.metadata['retriever'] = key
                    final_results.append(node)

        # 召回后去重，扩大召回top-k数量
        final_results = self.simple_deduplicate(final_results)

        return final_results

    def simple_deduplicate(self, documents):
        deduplicate_out_doc = []
        node_ids = set()
        # 根据answer进行去重
        for document in documents:
            id_ = document.id_
            if id_ in node_ids:
                continue
            node_ids.add(id_)
            deduplicate_out_doc.append(document)

        return deduplicate_out_doc
```

> **面试要点**：
> 
> - `retrievers` 是一个 dict（向量 \+ 关键词等多路），逐个 `retrieve` 后**合并去重**（按 `document.id_`），实现"扩大召回 \+ 去重"。
> 
> - 可选开启 HyDE（`query_HyDE`）先由 LLM 生成假设性文档再检索。
> 
> - 每个命中 node 打标 `metadata['retriever'] = key`，标注它来自哪一路，供下游可解释 / 调权重；TfIdf 路还回填原文（`origin_text_before_keywords`），避免命中的是关键词改写文本。
> 
> 

### **6\.3 重排序抽象基类（****`C:\Users\shilei.he\PA\af-rag-server\src\component\rerank\rag_base_rerank.py`****，36 行）**

```Python
class RagBaseRerank(BaseNodePostprocessor):
    save_rerank_res: bool = False
    save_path: str = None

    @classmethod
    def class_name(cls) -> str:
        return "RagBaseRerank"

    # 添加callback
    def _postprocess_nodes(
            self,
            nodes: List[NodeWithScore],
            query_bundle: Optional[QueryBundle] = None,
    ) -> List[NodeWithScore]:
        if self.save_rerank_res:
            self.callback_manager.add_handler(SaveContextHandler(save_path=self.save_path))
        with self.callback_manager.event(CBEventType.RERANKING, \
                payload={EventPayload.NODES: nodes}) as rerank_event:
            process_res = self.rerank_process(nodes, query_bundle)
            rerank_event.on_end(payload={EventPayload.NODES: process_res})
        return process_res

    def rerank_process(
            self,
            nodes: List[NodeWithScore],
            query_bundle: Optional[QueryBundle] = None,
    ) -> List[NodeWithScore]:
        pass
```

> **面试要点**：`RagBaseRerank` 继承 LlamaIndex `BaseNodePostprocessor`，把重排结果通过 `callback_manager` 的 `RERANKING` 事件上报（Langfuse 追踪 / SaveContextHandler 落盘），具体重排算法由子类实现 `rerank_process`。这是典型的"模板方法 \+ 事件埋点"模式。
> 
> 

## **代码执行沙箱（code\_runner \+ guards）**

### **7\.1 核心文件清单**

```Plain Text
src/component/pycode/
├── code_runner.py   ★ 51 行 —— 沙箱执行入口 exec_code
├── guards.py        ★ 187 行 —— 受限 builtins 构造（安全白名单/黑名单）
└── utils.py         —— s3_read_file / s3_write_file 注入工具
```

### **7\.2 code\_runner\.py 完整源码**

```Python
import io
import json
import traceback
from contextlib import redirect_stdout

from flask import g

from src.component.pycode.guards import safe_builtins
from src.component.pycode.utils import s3_read_file, s3_write_file
from src.component.server_component.LogManager import logger


def exec_code(code: str, kwargs: dict, func="main", **options) -> dict:
    # Redirect stdout to a StringIO object
    result = ""
    output = ""
    err_msg = ""
    exec_success = True
    replacer = options.get('replacer')
    exec_globals = {
        "__builtins__": safe_builtins,
        "s3_read_file": s3_read_file,
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
                # 去掉第二、三行rag相关代码内容
                lines = lines[0:1] + lines[3:]
            err_msg = "\n".join(lines)

            exec_success = False

        # Get the print output from the StringIO buffer
        output = f.getvalue()
    ret = {
        "stdout": output,
        "ret": json.dumps(result, ensure_ascii=False) if exec_success else result,
        "exc": err_msg,
    }
    logger.info(f"Plugin execution result: {ret}")
    return ret
```

> **面试要点**：`exec_code(code, kwargs, func="main", **options)` 的核心是 `compile(mode="exec")` \+ `exec(byte_code, exec_globals)`。只注入 `safe_builtins` \+ `s3_read_file`/`s3_write_file` 三个名字；捕获 `BaseException`（含 SystemExit/KeyboardInterrupt），异常栈去掉中间两行 `rag` 框架行，只保留首行（异常类型）\+ 用户代码行；`stdout` 通过 `redirect_stdout(io.StringIO())` 捕获 `print` 输出；返回值统一为 `{"stdout", "ret", "exc"}` 三件套。
> 
> 

### **7\.3 guards\.py 完整源码**

```Python
import builtins
import sys

# 参考 RestrictedPython
_version = sys.version_info
IS_PY311_OR_GREATER = _version.major == 3 and _version.minor >= 11

_safe_builtins = {}

_safe_names = [
    "__build_class__",
    "None",
    "False",
    "True",
    "abs",
    "bool",
    "bytes",
    "callable",
    "chr",
    "complex",
    "divmod",
    "float",
    "hash",
    "hex",
    "id",
    "int",
    "isinstance",
    "issubclass",
    "len",
    "oct",
    "ord",
    "pow",
    "range",
    "repr",
    "round",
    "slice",
    "sorted",
    "str",
    "tuple",
    "zip",
]

_safe_exceptions = [
    "ArithmeticError",
    "AssertionError",
    "AttributeError",
    "BaseException",
    "BufferError",
    "BytesWarning",
    "DeprecationWarning",
    "EOFError",
    "EnvironmentError",
    "Exception",
    "FloatingPointError",
    "FutureWarning",
    "GeneratorExit",
    "IOError",
    "ImportError",
    "ImportWarning",
    "IndentationError",
    "IndexError",
    "KeyError",
    "KeyboardInterrupt",
    "LookupError",
    "MemoryError",
    "NameError",
    "NotImplementedError",
    "OSError",
    "OverflowError",
    "PendingDeprecationWarning",
    "ReferenceError",
    "RuntimeError",
    "RuntimeWarning",
    "StopIteration",
    "SyntaxError",
    "SyntaxWarning",
    "SystemError",
    "SystemExit",
    "TabError",
    "TypeError",
    "UnboundLocalError",
    "UnicodeDecodeError",
    "UnicodeEncodeError",
    "UnicodeError",
    "UnicodeTranslateError",
    "UnicodeWarning",
    "UserWarning",
    "ValueError",
    "Warning",
    "ZeroDivisionError",
]

if IS_PY311_OR_GREATER:
    _safe_exceptions.append("ExceptionGroup")

for name in _safe_names:
    _safe_builtins[name] = getattr(builtins, name)

for name in _safe_exceptions:
    _safe_builtins[name] = getattr(builtins, name)

# Wrappers provided by this module:
# delattr
# setattr

# Wrappers provided by ZopeGuards:
# __import__
# apply
# dict
# enumerate
# filter
# getattr
# hasattr
# iter
# list
# map
# max
# min
# sum
# all
# any

# Builtins that are intentionally disabled
# compile   - don't let them produce new code
# dir       - a general purpose introspector, probably hard to wrap
# execfile  - no direct I/O
# file      - no direct I/O
# globals   - uncontrolled namespace access
# input     - no direct I/O
# locals    - uncontrolled namespace access
# open      - no direct I/O
# raw_input - no direct I/O
# vars      - uncontrolled namespace access

# There are several strings that describe Python.  I think there's no
# point to including these, although they are obviously safe:
# copyright, credits, exit, help, license, quit

# Not provided anywhere.  Do something about these?  Several are
# related to new-style classes, which we are too scared of to support
# <0.3 wink>.  coerce, buffer, and reload are esoteric enough that no
# one should care.

# buffer
# bytearray
# classmethod
# coerce
# eval
# intern
# memoryview
# object
# property
# reload
# staticmethod
# super
# type

_safe_globals = {"__builtins__": _safe_builtins}


# 暂时只禁止危险的内置函数使用
danger_names = [
    "compile",
    "dir",
    "exec",
    "execfile",
    "file",
    "globals",
    "input",
    "locals",
    "open",
    "raw_input",
    "vars",
    "buffer",
    "eval",
    "intern",
    "memoryview",
    "reload",
]

safe_builtins = {}

for name in dir(builtins):
    if name not in danger_names:
        safe_builtins[name] = getattr(builtins, name)

safe_globals = {"__builtins__": safe_builtins}
```

> **面试要点**：guards\.py 参考 RestrictedPython 写了**两套**受限命名空间——`_safe_builtins`（白名单：`_safe_names` \+ `_safe_exceptions`，共 60\+ 个安全名字）和真正被 code\_runner 使用的 `safe_builtins`（黑名单：遍历 `dir(builtins)`，仅剔除 `danger_names` 中 16 个危险内置）。黑名单禁止的核心是：`compile`/`exec`/`eval`（防止再编译新代码）、`open`/`input`/`file`（禁 I/O）、`globals`/`locals`/`vars`/`dir`（禁命名空间与内省）、`reload`/`import`（禁动态加载）。注意是 `for name in dir(builtins)` 循环 \+ `getattr(builtins, name)`，不是字典推导式。
> 
> 

## **Huey 异步任务与 S3（tasks\_file\_parsing \+ s3helper）**

### **8\.1 核心文件清单**

```Plain Text
src/rag_algorithm/
├── tasks_file_parsing.py   ★ 406 行 —— Huey 异步文档解析任务（转换PDF→拆页→逐页OCR→合并）
└── utils.py                —— convert_to_pdf / split_pdf_by_page / OCR_SPLIT_FILE_TYPE

src/server_api_tools/
└── s3helper.py             ★ 328 行 —— S3 封装（上传/下载/分块/JSON存取/预签名URL）
```

### **8\.2 tasks\_file\_parsing\.py 完整源码**

```Python
import copy
import datetime
import os
import shutil
import time
from enum import Enum

import requests

from src import settings
from src.common.utils import get_cache_dir, get_file_type, get_random_id
from src.component.chunk.chunknode import PowerAgentTextNode
from src.component.server_component.LogManager import logger
from src.globals import (
    huey_ocr,
    huey_ocr_file_priority,
    huey_ocr_bigfile_priority,
    huey_ocr_smallfile_priority,
    rc,
)
from src.rag_algorithm.rag_flow import file_parsing
from src.rag_algorithm.utils import (
    OCR_SPLIT_FILE_TYPE,
    convert_to_pdf,
    split_pdf_by_page,
)
from src.server_api_tools.s3helper import S3Helper

# 拆分文件s3路径
S3_SPLITFILE_SAVE_PATH = settings.S3_SPLITFILE_SAVE_PATH + "{task_id}/split_files/"
# 解析文件s3路径
S3_CHUNKFILE_SAVE_PATH = settings.S3_RESULT_SAVE_PATH + "{task_id}/parse_files/"

# task_id 关联拆分文件列表s3路径 redis key
REDIS_SPLIT_FILE_KEY = "ocr_file:{task_id}:split_files"
# task_id 关联解析文件列表s3路径 redis key
REDIS_PARSE_FILE_KEY = "ocr_file:{task_id}:parse_files"
# task_id 关联解析错误页面列表 redis key
REDIS_ERROR_PAGE_KEY = "ocr_file:{task_id}:error_pages"
# task_id 拆分文件解析状态 redis key
REDIS_PARSE_STATUS_KEY = "ocr_file:{task_id}:{s3_file_path}:status"
# task_id 合并结果 redis 锁
REDIS_MERGE_LOCK_KEY = "ocr_file:{task_id}:merge_lock"


@huey_ocr_file_priority.priority_task()
def run_ocr_file_task(task_priority, target_func, func_kwargs):
    target_func(**func_kwargs)


@huey_ocr_bigfile_priority.priority_task()
def run_ocr_bigfile_task(task_priority, target_func, func_kwargs):
    target_func(**func_kwargs)


@huey_ocr_smallfile_priority.priority_task()
def run_ocr_smallfile_task(task_priority, target_func, func_kwargs):
    target_func(**func_kwargs)


class OcrTaskQueueEnum(Enum):
    FILE = 0
    BIGFILE = 1
    SMALLFILE = 2


ocr_task_queue_mapping = {
    OcrTaskQueueEnum.FILE: run_ocr_file_task,
    OcrTaskQueueEnum.BIGFILE: run_ocr_bigfile_task,
    OcrTaskQueueEnum.SMALLFILE: run_ocr_smallfile_task,
}


def callback_parse_failed(task_id, req_json, code, msg):
    return_json = {
        "code": code,
        "msg": msg,
        "task_id": task_id,
        "result": "",
    }

    if req_json.get("request_id"):
        return_json["request_id"] = req_json.get("request_id")

    # 加上渗透参数
    return_json["trans_metadata"] = req_json.get("trans_metadata")

    callback_url = req_json.get("callback_url")
    logger.info(f"【{task_id=}】【{callback_url=}】处理结果：{return_json}")
    if callback_url:
        resp = requests.post(callback_url, json=return_json)
        logger.info(f"【{task_id=}】回调结果：{resp.json()}")

    return return_json


def callback_parse_succeed(task_id, req_json, result_path, error_pages):
    return_json = {
        "code": 0,
        "msg": "success",
        "task_id": task_id,
        "result_path": result_path,
        "error_pages": error_pages,
    }

    if req_json.get("request_id"):
        return_json["request_id"] = req_json.get("request_id")

    # 加上渗透参数
    return_json["trans_metadata"] = req_json.get("trans_metadata")

    callback_url = req_json.get("callback_url")
    logger.info(f"【{task_id=}】【{callback_url=}】处理结果：{return_json}")
    if callback_url:
        resp = requests.post(callback_url, json=return_json)
        logger.info(f"【{task_id=}】回调结果：{resp.json()}")

    return return_json


@huey_ocr.task()
def ocr_convert_to_pdf(task_id, req_json):
    # 文件缓存目录
    file_download_url = req_json.get("file_download_url")
    file_name = req_json.get("file_name")
    file_type = get_file_type(file_name)
    cache_dir = get_cache_dir()
    file_path = os.path.join(cache_dir, file_name)

    try:
        logger.info(f"【{task_id=}】文件下载开始")
        s3_helper = S3Helper()
        s3_helper.download_file_s3(path_s3=file_download_url, path_local=file_path)
        logger.info(f"【{task_id=}】文件下载成功")

        # 转换PDF文件，原文预览，ocr解析使用
        if file_type == ".pdf":
            pdf_file_path = file_path
        else:
            pdf_file_path = convert_to_pdf(file_path)

        convert_s3_path = settings.S3_CONVERT_SAVE_PATH + f"{get_random_id()}.pdf"
        s3_helper.upload_single_file(path_local=pdf_file_path, path_s3=convert_s3_path)
        req_json["convert_file"] = convert_s3_path

        queue = req_json.get("queue", OcrTaskQueueEnum.FILE)
        ocr_task = ocr_task_queue_mapping.get(queue)
        task_type = "parser_realtime" if req_json.get("is_realtime") else "parser"

        if file_type in OCR_SPLIT_FILE_TYPE:
            # 需要先拆分文件再解析
            target_func = ocr_file_splitting
            func_kwargs = {"task_id": task_id, "req_json": req_json}
        else:
            from src.rag_algorithm.handlers import run_task_asynchronous

            target_func = run_task_asynchronous
            func_kwargs = {
                "target_func": file_parsing,
                "func_kwargs": req_json,
                "task_id": task_id,
                "request_id": req_json.get("request_id"),
                "save_result_to_s3": True,
                "task_type": task_type,
                "callback_url": req_json.get("callback_url"),
                "trans_metadata": req_json.get("trans_metadata"),
            }
        priority = req_json.get("priority", 0)
        job = ocr_task(priority, target_func, func_kwargs)
        logger.info(f"【{task_id=}】【{job=}】【{req_json=}】")

    except Exception as e:
        logger.exception(f"【{task_id=}】转换PDF失败: {str(e)}")
        callback_parse_failed(
            task_id, req_json, code=-1, msg="转换PDF失败，请转换PDF文件后重试"
        )
    finally:
        if os.path.exists(cache_dir):
            shutil.rmtree(cache_dir)


def ocr_file_splitting(task_id, req_json):
    try:
        # 文件缓存目录
        file_download_url = req_json.get("convert_file") or req_json.get(
            "file_download_url"
        )
        file_name = file_download_url.split("/")[-1]
        file_type = get_file_type(file_name)
        cache_dir = get_cache_dir()
        file_path = os.path.join(cache_dir, file_name)

        s3_helper = S3Helper()
        logger.info(f"【{task_id=}】文件下载开始")
        s3_helper.download_file_s3(path_s3=file_download_url, path_local=file_path)
        logger.info(f"【{task_id=}】文件下载成功")

        # 转换PDF文件，原文预览，ocr解析使用
        if file_type == ".pdf":
            pdf_file_path = file_path
        else:
            pdf_file_path = convert_to_pdf(file_path)

        if not req_json.get("convert_file"):
            convert_s3_path = settings.S3_CONVERT_SAVE_PATH + f"{get_random_id()}.pdf"
            s3_helper.upload_single_file(
                path_local=pdf_file_path, path_s3=convert_s3_path
            )
            req_json["convert_file"] = convert_s3_path

        s3_split_files = []
        # 上传分割后的小文件到S3
        split_file_names = split_pdf_by_page(pdf_file_path, cache_dir, prefix=task_id)
        for i, split_file_name in enumerate(split_file_names):
            split_file_path = os.path.join(cache_dir, split_file_name)
            split_s3_path = (
                S3_SPLITFILE_SAVE_PATH.format(task_id=task_id) + split_file_name
            )
            s3_helper.upload_single_file(
                path_local=split_file_path, path_s3=split_s3_path
            )
            s3_split_files.append(split_s3_path)
            logger.info(f"【{task_id=}】拆分文件上传第{i + 1}个：{split_s3_path}")

        # 等待上传文件
        logger.info(f"【{task_id=}】拆分文件上传成功")
        priority = req_json.get("priority")
        queue = req_json.get("queue", OcrTaskQueueEnum.FILE)
        ocr_task = ocr_task_queue_mapping.get(queue)
        for s3_path in s3_split_files:
            rc.rpush(REDIS_SPLIT_FILE_KEY.format(task_id=task_id), s3_path)
            func_kwargs = {
                "task_id": task_id,
                "req_json": req_json,
                "s3_file_path": s3_path,
            }
            job = ocr_task(priority, ocr_file_parsing, func_kwargs)
            logger.info(f"【{task_id=}】【{job=}】{s3_path}拆分文件解析进入队列")
    except Exception as e:
        logger.exception(f"【{task_id=}】文件拆分失败: {str(e)}")
        callback_parse_failed(task_id, req_json, code=-1, msg="文件拆分失败")
    finally:
        if os.path.exists(cache_dir):
            shutil.rmtree(cache_dir)


def ocr_file_parsing(task_id, req_json, s3_file_path):
    parse_status_key = REDIS_PARSE_STATUS_KEY.format(
        task_id=task_id, s3_file_path=s3_file_path
    )

    try:
        # 记录解析开始时间，表示正在解析中
        rc.set(parse_status_key, int(time.time()))
        logger.info(f"【{task_id=}】拆分文件解析开始：{s3_file_path}")

        split_file_name = s3_file_path.split("/")[-1]
        split_req_json = copy.deepcopy(req_json)
        parameters = split_req_json.get("parameters") or {}
        parameters["return_full_document"] = False

        split_req_json["file_download_url"] = s3_file_path
        split_req_json["file_name"] = split_file_name
        split_req_json["parameters"] = parameters

        chunks, error_pages = file_parsing(**split_req_json)

        chunk_file_name = split_file_name.split(".")[0] + ".json"
        chunk_s3_path = S3_CHUNKFILE_SAVE_PATH.format(task_id=task_id) + chunk_file_name
        S3Helper().save_chunks_json(chunks, chunk_s3_path)

        # 拆分文件记录解析成功状态
        for page in error_pages:
            rc.rpush(REDIS_ERROR_PAGE_KEY.format(task_id=task_id), page)
        rc.rpush(REDIS_PARSE_FILE_KEY.format(task_id=task_id), chunk_s3_path)
        rc.set(parse_status_key, 1)

        logger.info(f"【{task_id=}】拆分文件解析成功：{chunk_s3_path}")
    except Exception as e:
        # 记录拆分文件解析失败状态
        rc.set(parse_status_key, 0)
        logger.error(f"【{task_id=}】拆分文件解析失败：{s3_file_path}，{str(e)}")
    finally:
        # 检查是否解析完成
        priority = req_json.get("priority")
        queue = req_json.get("queue", OcrTaskQueueEnum.FILE)
        ocr_task = ocr_task_queue_mapping.get(queue)
        func_kwargs = {"task_id": task_id, "req_json": req_json}
        job = ocr_task(priority, ocr_result_merging, func_kwargs)


def ocr_result_merging(task_id, req_json):
    now = int(time.time())
    s3_split_files = rc.lrange(REDIS_SPLIT_FILE_KEY.format(task_id=task_id), 0, -1)
    for s3_file_path in s3_split_files:
        parse_status_key = REDIS_PARSE_STATUS_KEY.format(
            task_id=task_id, s3_file_path=s3_file_path
        )
        status = rc.get(parse_status_key)
        if status and status.isdigit():
            status = int(status)
            # 有任务正在解析并且未超时
            if status > 1 and now - status < 60 * 60 * 6:
                logger.info(f"【{task_id=}】正在解析{s3_file_path}")
                return
            elif status == 1:
                logger.info(f"【{task_id=}】解析成功：{status=}{s3_file_path}")
            else:
                logger.info(f"【{task_id=}】解析失败或超时：{status=}{s3_file_path}")
        else:
            # 有任务还未开始解析
            logger.info(f"【{task_id=}】还未解析：{s3_file_path}")
            return

    lock_key = REDIS_MERGE_LOCK_KEY.format(task_id=task_id)
    if rc.setnx(lock_key, 1):
        # 设置过期时间
        rc.expire(lock_key, 3600)
        logger.info(f"【{task_id=}】开始合并解析结果")
        s3_helper = S3Helper()
        parse_files = rc.lrange(REDIS_PARSE_FILE_KEY.format(task_id=task_id), 0, -1)
        # 根据拆分文件的序号进行排序，避免页码错乱
        parse_files.sort(key=lambda x: int(x.split("/")[-1].split("_")[1]))

        merge_chunk_nodes = []
        now_time = datetime.datetime.now().strftime(r"%Y-%m-%d %H:%M:%S")
        parameters = req_json.get("parameters") or {}
        return_full_document = parameters.get("return_full_document")
        if return_full_document:
            location = {"type": "pages", "value": []}
            page_text = ""
            ocr_res = []
            parser = None

            for s3_path in parse_files:
                chunks = s3_helper.load_chunks_json(s3_path)
                chunk_nodes = [PowerAgentTextNode.from_pa_dict(ck) for ck in chunks]
                if not chunk_nodes:
                    continue
                if not parser:
                    parser = (
                        chunk_nodes[0].metadata.get("parser_info", {}).get("parser")
                    )

                file_name = chunk_nodes[0].metadata.get("file_name")
                start_page = int(file_name.split("_")[-2])
                for chunk_node in chunk_nodes:
                    page_text += chunk_node.text
                    ocr_res.extend(
                        chunk_node.metadata.get("parser_info").get("ori_data")
                    )
                    page_num = start_page + chunk_node.location.get("value")[0]
                    location["value"].append(page_num)

            metadata = {
                "file_name": req_json.get("file_name"),
                "create_date": now_time,
                "parser_info": {
                    "parser": parser or "OcrFileReader",
                    "table_format": "MARKDOWN",
                    "ori_data": ocr_res,
                },
                "convert_file": req_json.get("convert_file"),
            }
            merge_chunk_nodes.append(
                PowerAgentTextNode(
                    text=page_text,
                    metadata=metadata,
                    source_metadata=req_json.get("source_metadata"),
                    location=location,
                )
            )

        else:
            for s3_path in parse_files:
                chunks = s3_helper.load_chunks_json(s3_path)
                chunk_nodes = [PowerAgentTextNode.from_pa_dict(ck) for ck in chunks]
                if not chunk_nodes:
                    continue

                file_name = chunk_nodes[0].metadata.get("file_name")
                start_page = int(file_name.split("_")[-2])
                for chunk_node in chunk_nodes:
                    page_num = start_page + chunk_node.location.get("value")[0]
                    chunk_node.metadata["file_name"] = req_json.get("file_name")
                    chunk_node.metadata["convert_file"] = req_json.get("convert_file")
                    chunk_node.location["value"] = [page_num]
                    merge_chunk_nodes.append(chunk_node)

        merge_chunk_data = [ck_nd.to_pa_dict() for ck_nd in merge_chunk_nodes]
        task_type = "parser_realtime" if req_json.get("is_realtime") else "parser"
        merge_s3_path = S3Helper.get_s3_config_save_path(
            task_type=task_type, task_id=task_id
        )
        s3_helper.save_chunks_json(merge_chunk_data, merge_s3_path)

        error_pages = rc.lrange(REDIS_ERROR_PAGE_KEY.format(task_id=task_id), 0, -1)
        if error_pages:
            error_pages.sort()

        callback_parse_succeed(task_id, req_json, merge_s3_path, error_pages)
        rc.delete(lock_key)
        logger.info(f"{task_id}合并完成：{merge_s3_path}")
    else:
        # 合并完成或正在合并
        logger.info(f"{task_id}已合并完成或正在合并")
```

> **面试要点**：tasks\_file\_parsing\.py 是**大文件异步解析链路**（配合 §3\.2 中 Parser 的队列选择）：
> 
> 1. **三优先级 Huey 队列**：`huey_ocr_file_priority` / `huey_ocr_bigfile_priority` / `huey_ocr_smallfile_priority`，包装函数签名统一为 `run_ocr_file_task(task_priority, target_func, func_kwargs)`，内部仅 `target_func(**func_kwargs)`，由 `ocr_task_queue_mapping`（`OcrTaskQueueEnum.FILE=0/BIGFILE=1/SMALLFILE=2`）选择。
> 
> 2. **ocr\_convert\_to\_pdf**：先 S3 下载 → 非 PDF 转 PDF（`convert_to_pdf`）→ 上传 `S3_CONVERT_SAVE_PATH` 存 `convert_file` → 若文件类型在 `OCR_SPLIT_FILE_TYPE` 走 `ocr_file_splitting`，否则直接 `run_task_asynchronous(file_parsing, ...)`。
> 
> 3. **ocr\_file\_splitting**：`split_pdf_by_page(pdf, cache_dir, prefix=task_id)` 按页拆 → 每页小文件传 `S3_SPLITFILE_SAVE_PATH/{task_id}/split_files/` → 逐个入队 `ocr_file_parsing`，同时 `rc.rpush(REDIS_SPLIT_FILE_KEY)` 记录拆分清单。
> 
> 4. **ocr\_file\_parsing**：`rc.set(parse_status_key, int(time.time()))` 记录开始时间 → `file_parsing(**split_req_json)`（`return_full_document=False`，只留单页）→ 结果存 `S3_CHUNKFILE_SAVE_PATH/{task_id}/parse_files/` → `rc.set(parse_status_key, 1)` 成功 / `0` 失败 → finally 总是再把 `ocr_result_merging` 入队。
> 
> 5. **ocr\_result\_merging**：Redis 状态机轮询——时间戳 `>1` 且 `now - status < 6h` 视为解析中直接 return；`==1` 成功；`0/超时` 失败；空 key 未开始。全部就绪后用 `REDIS_MERGE_LOCK_KEY`（`setnx` \+ `expire 3600`）加锁保证只有一个 worker 合并；按 `x.split("/")[-1].split("_")[1]` 对拆分文件序号排序防页码错乱；`return_full_document` 时把全部页文本/`ori_data` 拼成一个 `PowerAgentTextNode`，否则逐 chunk 修正 `location["value"] = [start_page + page_num]`。
> 
> 

### **8\.3 s3helper\.py 完整源码**

```Python
import json
import os
import re
import tempfile

import boto3
import s3fs

from src import settings
from src.component.server_component.LogManager import logger


def get_local_file_size(file_path):
    size = os.path.getsize(file_path) / (1024 * 1024)  # 转换为MB
    return size


class S3Helper:

    def __init__(self):
        self.endpoint = settings.S3_ENDPOINT
        self.access_key = settings.S3_ACCESS_KEY
        self.secret_key = settings.S3_SECRET_ACCESS_KEY
        self.region = settings.S3_REGION or None

        # 连接s3
        self.s3 = boto3.resource(
            service_name="s3",
            endpoint_url=self.endpoint,
            aws_access_key_id=self.access_key,
            aws_secret_access_key=self.secret_key,
            region_name=self.region,
        )

        self.client = boto3.client(
            service_name="s3",
            endpoint_url=self.endpoint,
            aws_access_key_id=self.access_key,
            aws_secret_access_key=self.secret_key,
            region_name=self.region,
        )

    def get_s3_filesystem(self):
        s3 = s3fs.S3FileSystem(
            key=self.access_key,
            secret=self.secret_key,
            client_kwargs={"endpoint_url": self.endpoint, "region_name": self.region},
        )
        return s3

    @staticmethod
    def get_s3_config_save_path(task_type, task_id):
        """
        task_type: parser or splitter
        """
        return settings.S3_SAVE_PATH.format(task_type=task_type, task_id=task_id)

    @staticmethod
    def _split_s3_path(path_s3):
        pattern = re.compile(r"s3://(.*?)/")
        # 使用findall方法找到所有匹配的字符串
        matches = pattern.findall(path_s3)
        if len(matches) < 1:
            raise Exception(f"s3地址解析失败：{path_s3}")
        bucket_name = matches[0]

        path_s3_no_prefix = path_s3.replace(f"s3://{bucket_name}/", "")
        return bucket_name, path_s3_no_prefix

    def create_bucket_if_not_exist(self, bucket_name):
        # all_buckets = self.client.list_buckets()['Buckets']
        # all_buckets = [_['Name'] for _ in all_buckets]
        # if bucket_name not in all_buckets:
        #     self.client.create_bucket(Bucket=bucket_name)
        #     print(f"存储桶 {bucket_name} 创建成功")

        try:
            # 尝试获取存储桶的信息
            self.client.head_bucket(Bucket=bucket_name)
            # print(f"存储桶 {bucket_name} 存在。")
        except self.client.exceptions.ClientError as e:
            # 如果抛出异常，则存储桶不存在或者没有权限访问
            error_code = e.response["Error"]["Code"]
            if error_code == "404":
                self.client.create_bucket(Bucket=bucket_name)
                print(f"存储桶 {bucket_name} 创建成功")
            else:
                print(f"存储桶 {bucket_name} 访问出错：{error_code}")

    def download_file_s3(self, path_s3, path_local):
        """
        下载
        :param path_s3: 带前缀   s3://agentflow/dev/knowledge/111/1715743625357/汽车使用手册.pdf
        :param path_local:
        :return:
        """
        bucket_name, path_s3_no_prefix = self._split_s3_path(path_s3)

        retry = 0
        while retry < 3:  # 下载异常尝试3次
            # logger.info(f'Start downloading files. | path_s3: {path_s3} | path_local: {path_local}')
            try:
                self.client.download_file(bucket_name, path_s3_no_prefix, path_local)
                file_size = os.path.getsize(path_local)
                print(
                    f"Downloading completed. | size: {round(file_size / 1048576, 2)} MB"
                )
                break  # 下载完成后退出重试
            except Exception as e:
                print(f"Download file failed. | Exception: {e}")
                retry += 1

                if retry >= 3:
                    print(f"load s3 file failed after max retry.")
                    raise Exception from e

    def upload_single_file(self, path_local, path_s3):
        """
        上传单个文件
        :param path_local:
        :param path_s3:
        :return:
        """
        bucket_name, path_s3_no_prefix = self._split_s3_path(path_s3)
        self.create_bucket_if_not_exist(bucket_name)
        file_size = get_local_file_size(path_local)

        try:
            with open(path_local, "rb") as f:
                if file_size < 250:
                    self.client.upload_fileobj(f, bucket_name, path_s3_no_prefix)
                    logger.info(
                        f"Uploading file successful. | src: {path_local} | dest: {path_s3}"
                    )

                else:
                    # 初始化分段上传
                    response = self.client.create_multipart_upload(
                        Bucket=bucket_name, Key=path_s3_no_prefix
                    )
                    upload_id = response["UploadId"]
                    part_number = 1
                    parts = []
                    while True:
                        data = f.read(5 * 1024 * 1024)  # 每次读取 5MB 数据
                        if not data:
                            break
                        response = self.client.upload_part(
                            Bucket=bucket_name,
                            Key=path_s3_no_prefix,
                            PartNumber=part_number,
                            UploadId=upload_id,
                            Body=data,
                        )
                        parts.append(
                            {"PartNumber": part_number, "ETag": response["ETag"]}
                        )
                        part_number += 1
                    # 完成分段上传
                    self.client.complete_multipart_upload(
                        Bucket=bucket_name,
                        Key=path_s3_no_prefix,
                        UploadId=upload_id,
                        MultipartUpload={"Parts": parts},
                    )
                    logger.info(
                        f"Uploading multipart file successful. | src: {path_local} | dest: {path_s3}"
                    )

        except Exception as e:
            logger.info(
                f"Upload data failed. | src: {path_local} | dest: {path_s3} | Exception: {e}"
            )
            return False
        return True

    def save_chunks_json(self, data, path_s3):
        """chunks dict 写入json文件"""
        if isinstance(data, list):
            data_str = "\n".join(
                [json.dumps(item, ensure_ascii=False) for item in data]
            )
        else:
            data_str = json.dumps(data, ensure_ascii=False)
        with tempfile.TemporaryDirectory() as temp_dir:

            # 在临时目录中创建一个文件
            path_local = os.path.join(temp_dir, "chunk.json")
            with open(path_local, "w", encoding="utf-8") as f:
                f.write(data_str)

            self.upload_single_file(path_local, path_s3)
            try:
                os.remove(path_local)
            except:
                pass

        logger.info(f"文件成功写入s3： {path_s3}")

    def load_chunks_json(self, path_s3):
        bucket_name, path_s3_no_prefix = self._split_s3_path(path_s3)
        content_object = self.s3.Object(bucket_name, path_s3_no_prefix)
        file_content = content_object.get()["Body"].read().decode("utf-8")
        data = []
        for line in file_content.split("\n"):
            if line is None or line.strip() == "":
                continue
            data.append(json.loads(line))
        if len(data) == 0:
            raise ValueError("输入chunk为空")
        return data

    def delete_s3_file(self, path_s3):
        """
        删除
        """
        bucket_name, path_s3_no_prefix = self._split_s3_path(path_s3)
        try:
            # copy
            # copy_source = {'Bucket': BUCKET_NAME, 'Key': path_s3}
            # s3.copy_object(CopySource=copy_source, Bucket=BUCKET_NAME, Key='is-zips-cache/' + file_name)
            self.client.delete_object(Bucket=bucket_name, Key=path_s3_no_prefix)
        except Exception as e:
            print(f"Delete s3 file failed. | Exception: {e}")
        print(f"Delete s3 file Successful. | path_s3 = {path_s3}")

    def get_files_list(self, dir_s3):
        print(f"Start getting files from s3.")
        bucket_name, dir_s3_no_prefix = self._split_s3_path(dir_s3)
        try:
            if dir_s3 is not None:
                if dir_s3_no_prefix:
                    all_obj = self.client.list_objects_v2(
                        Bucket=bucket_name, Prefix=dir_s3_no_prefix
                    )
                else:
                    all_obj = self.client.list_objects_v2(Bucket=bucket_name)

                # 获取某个对象的head信息
                # obj = s3.head_object(Bucket=BUCKET_NAME, Key=Prefix)
                # logger.info(f"obj = {obj}")
            else:
                all_obj = self.client.list_objects_v2(Bucket=bucket_name)

        except Exception as e:
            print(f"Get files list failed. | Exception: {e}")
            return

        contents = all_obj.get("Contents")
        print(f"--- contents = {contents}")
        if not contents:
            return []

        file_name_list = []
        for zip_obj in contents:
            # logger.info(f"zip_obj = {zip_obj}")
            file_size = round(zip_obj["Size"] / 1024 / 1024, 3)  # 大小
            # logger.info(f"file_path = {zip_obj['Key']}")
            # logger.info(f"LastModified = {zip_obj['LastModified']}")
            # logger.info(f"file_size = {file_size} Mb")
            # zip_name = zip_obj['Key'][len(start_after):]
            zip_name = zip_obj["Key"]

            file_name_list.append(zip_name)

        print(f"Get file list successful.")

        return file_name_list

    def get_file_size(self, path_s3):
        bucket_name, path_s3_no_prefix = self._split_s3_path(path_s3)
        object_info = self.client.head_object(Bucket=bucket_name, Key=path_s3_no_prefix)
        file_size = round(object_info.get("ContentLength", 0) / 1024 / 1024, 2)  # MB
        return file_size

    def generate_presigned_url(self, path_s3, expires_in=86400):
        bucket_name, object_name = self._split_s3_path(path_s3)
        return self.client.generate_presigned_url(
            "get_object",
            Params={"Bucket": bucket_name, "Key": object_name},
            ExpiresIn=expires_in,
        )
```

> **面试要点**：S3Helper 是贯穿全链路的存储底座——
> 
> 1. `_split_s3_path` 用正则 `s3://(.*?)/` 拆出 bucket，`path_s3.replace(...)` 得到无前缀路径。
> 
> 2. `upload_single_file`：文件 `< 250MB` 用 `upload_fileobj` 一次上传；`≥ 250MB` 走 **Multipart 分片上传**（`create_multipart_upload` → 每次 `f.read(5MB)` → `upload_part` 收集 `{PartNumber, ETag}` → `complete_multipart_upload`）。
> 
> 3. `save_chunks_json` 是 chunk 序列化标准格式：`"\n".join([json.dumps(item, ensure_ascii=False)])` —— **JSONL（每行一个 JSON 对象）**，`load_chunks_json` 逐行 `json.loads` 还原，空文件抛 `ValueError("输入chunk为空")`。
> 
> 4. `get_s3_config_save_path(task_type, task_id)` 生成标准结果路径 `settings.S3_SAVE_PATH.format(...)`；`generate_presigned_url` 默认 24h 有效期，用于前端预览/下载。
> 
> 

## **Agent 智能体系统（FastAPI / Google ADK）**

### **9\.1 应用架构与核心文件清单**

Agent 应用独立于 Flask RAG 服务运行，基于 **Google ADK** 实现多智能体编排，通过 SSE 流式返回执行结果。

```Plain Text
agent/
├── app.py                              —— FastAPI 入口 (端口 10001)
├── auto_agent/
│   ├── executor.py                     —— AutoAgentExecutor 执行器 (ADK/LangGraph 分发)
│   └── framework_adapter/adk/
│       ├── executor.py                 —— ADKExecutor ADK 执行器 (核心)
│       ├── tool.py                     —— 工具层 (KnowledgeTool/CommonTool)
│       └── custom_planner.py           —— 自定义任务规划器
├── agents/intent_agent/
│   ├── agent.py                        —— IntentAgent 意图判定根 Agent
│   ├── explicit_branch.py              —— 明确分支 (策略发布/使用/建任务)
│   └── utils.py                        —— 工具判定意图
└── utils/
    ├── mcp_tools.py                    —— MCP 客户端
    └── event_packer.py                 —— SSE 事件包装
```

```Plain Text
用户请求 → FastAPI /api/auto-agent/run (SSE)
    ↓
AutoAgentExecutor (framework 分发: adk | langgraph)
    ↓
ADKExecutor
    ├── _init_session —— Redis session 建/删/重放历史对话
    ├── Runner.run_async —— ADK 运行循环
    ├── fill_custom_metadata —— 事件打标 (number/type/function_call/plan/thought)
    ├── after_tool_call —— 工具类型/头像 + DATASET_INFO 记录
    ├── before_model_callback —— 截断历史轮数
    ├── after_model_callback —— fix_quote 重编引用序号
    └── CustomPlanner —— 中文 ReAct 计划
    ↓
IntentAgent (意图判定) → 5 个子 Agent (Metadata/Strategy/StrategyView/DirectAnalysis/RunData)
    ↓
event_packer.repack_resp_parts —— 按 agent 类型包装 Event → SSE 流式返回
```

### **9\.2 FastAPI 入口 agent/app\.py**

**`agent/app.py`**（152 行，FULL）：

```Python
"""
Data Intelligence Agent System

A multi-agent system for data analysis and intelligence tasks.
Built with Google ADK and A2A frameworks.
"""
import asyncio
import logging
import json

from pydantic import ValidationError

from auto_agent.error import format_errors
from auto_agent.model import AutoAgentRequest, User
from auto_agent.executor import AutoAgentExecutor


import click
import uvicorn
from fastapi import FastAPI, Request
from pinpointPy.Fastapi import PinPointMiddleWare, use_starlette_context
from starlette_context.middleware import RawContextMiddleware
from fastapi.responses import StreamingResponse

from apps import pinpoint_agent
from apps.langfuse_client import init_langfuse_client
from apps.mcp_client import router as mcp_client_router
from apps.multi_agent.api import router as multi_agent_router
from services.schedule.scheduler import run_scheduler
from utils.log_manager import logger
from utils.misc import set_trace_id
from utils.utils import generate_req_id


def create_fastapi_app():
    app = FastAPI()
    if pinpoint_agent.set_agent_info():
        use_starlette_context()
        app.add_middleware(PinPointMiddleWare)
        app.add_middleware(RawContextMiddleware)

    app.include_router(mcp_client_router, prefix="/mcp")
    app.include_router(multi_agent_router, prefix="/multi-agent")
    run_scheduler()
    langfuse = init_langfuse_client()

    @app.get("/ping")
    async def ping() -> str:
        return "pong"

    @app.middleware("http")
    async def trace_middleware(request: Request, call_next):
        data = await request.json()
        if langfuse:
            trace_id = data.get("reqId")
            try:
                # 可能前端、后端代码还没调整，此处加一个容错处理
                _ = int(trace_id, base=16)
            except Exception as e:
                logger.info(f"reqId cannot transfer to int: {trace_id=} {e}")
                trace_id = generate_req_id()

            with langfuse.start_as_current_span(name="ai-insight-agent", trace_context={"trace_id": trace_id}) as span:
                set_trace_id(trace_id=trace_id)
                session_id = data.get("session_id")
                if session_id:
                    span.update_trace(session_id=session_id)

                response = await call_next(request)
                response.headers["X-Trace-Id"] = trace_id  # just for debug use, backend donot need transfer to fe
                return response
        else:
            trace_id = data.get("reqId") or generate_req_id()
            set_trace_id(trace_id=trace_id)
            response = await call_next(request)
            response.headers["X-Trace-Id"] = trace_id
            return response

    @app.post("/api/auto-agent/run")
    async def auto_agent_run(request: Request, req: dict):
        logger.info(f"auto_agent----req:{req}")
        logger.info(f"request headers: {request.headers}")

        async def execute_autoagent(_req, q: asyncio.Queue):
            try:
                req = AutoAgentRequest(**_req)
                user = User(**request.headers)
            except ValidationError as e:
                logger.error(e)
                data = {"error_message": format_errors(e.errors())}
                await q.put(f"data: {json.dumps(data)}\n\n")
                await q.put(None)
                return
            try:
                executor = AutoAgentExecutor(req, user)
                async for res in executor.execute():
                    await q.put(res)
                await q.put(None)
                return
            except Exception as e:
                logger.exception(e)
                logger.info(f"[request_id={req.requestId}] agent 执行错误 {e}")
                data = {"error_message": str(e)}
                await q.put(f"data: {json.dumps(data)}\n\n")
                await q.put(None)

        async def heartbeat(q: asyncio.Queue):
            while True:
                await asyncio.sleep(10)
                await q.put("data: \n\n")

        async def event_generator(_req: dict):
            q = asyncio.Queue()
            heartbeat_task = asyncio.create_task(heartbeat(q))
            agent_task = asyncio.create_task(execute_autoagent(_req, q))

            while True:
                msg = await q.get()
                if msg is None:
                    break
                logger.info(msg)
                yield msg
            heartbeat_task.cancel()
            agent_task.cancel()

        return StreamingResponse(
            event_generator(req),
            media_type="text/event-stream",
        )

    return app


@click.command()
@click.option("--host", "host", default="127.0.0.1", help="Host to bind the server to")
@click.option("--port", "port", default=10001, help="Port to bind the server to")
@click.option("--log-level", "log_level", default="info", help="Logging level")
def run_adk_server(host: str, port: int, log_level: str = "info"):
    # Set logging level
    logging.getLogger().setLevel(getattr(logging, log_level.upper()))

    logger.info("Starting Data Intelligence Agent System...")
    logger.info(f"Server will be available at http://{host}:{port}")

    app = create_fastapi_app()

    # Start the server
    uvicorn.run(app, host=host, port=port, log_level=log_level.lower())


if __name__ == "__main__":
    run_adk_server()
```

> **面试要点**：
> 
> 1. **SSE 流式设计**：`/api/auto-agent/run` 返回 `StreamingResponse(media_type="text/event-stream")`；内部用 `asyncio.Queue` 作为生产者\-消费者桥——`execute_autoagent` 和 `heartbeat` 两个 Task 向队列写，`event_generator` 从队列读并 `yield`。队列收到 `None` 表示结束，最后 `heartbeat_task.cancel()` 取消心跳。
> 
> 2. **心跳机制**：每 10 秒往队列塞一条 `"data: \n\n"`，防止网关/浏览器 SSE 连接因超时被断开。
> 
> 3. **参数校验前置**：`AutoAgentRequest(**req)` / `User(**request.headers)` 用 Pydantic 反序列化，`ValidationError` 时返回 `{"error_message": format_errors(...)}`，先于 agent 执行兜底。
> 
> 4. **链路追踪**：`trace_middleware` 读请求体里的 `reqId`（校验 hex，不合法则 `generate_req_id()`），开启 Langfuse span `ai-insight-agent`，并把 `X-Trace-Id` 写回响应头用于排障；PinPoint 中间件按配置注入。
> 
> 5. **双路由挂载**：`/mcp/*`（MCP 客户端）与 `/multi-agent/*`（多智能体）由 `include_router` 挂载；`run_scheduler()` 启动后台调度。
> 
> 

### **9\.3 AutoAgentExecutor 执行器**

**`agent/auto_agent/executor.py`**（67 行，FULL）：

```Python
from typing import AsyncGenerator

from langfuse.types import TraceContext

from auto_agent.framework_adapter.langgraph_adapter.executor import LangGraphExecutor
from auto_agent.model import AutoAgentRequest, User
from auto_agent.observability import langfuse_instance
from utils.log_manager import logger


class AutoAgentExecutor:
    """AutoAgent 执行类"""

    def __init__(self, data: AutoAgentRequest, user: User):
        self.data = data
        self.user = user

    async def execute(self) -> AsyncGenerator[str, None]:
        if langfuse_instance:
            trace_context = TraceContext(trace_id=self.data.requestId.hex)
            with langfuse_instance.start_as_current_span(name=self.data.agentMeta.name, trace_context=trace_context):
                async for event in self._execute_impl():
                    yield event
        else:
            async for event in self._execute_impl():
                yield event

    async def _execute_impl(self) -> AsyncGenerator[str, None]:
        """执行入口"""
        from auto_agent.framework_adapter.adk.executor import ADKExecutor

        logger.info(f"[request_id={self.data.requestId.hex}] 开始执行")
        # 根据框架类型调用不通的执行器
        if self.data.framework == "adk":
            logger.info(f"[request_id={self.data.requestId.hex}] 运行框架为 adk")
            executor = ADKExecutor(
                self.data.agentMeta,
                draft_mode=self.data.draftMode,
                user=self.user,
                chat_id=self.data.chatId,
                request_id=self.data.requestId.hex,
                question=self.data.question,
                variables=self.data.variables,
                chat_history=self.data.chatHistory,
                rag_options=self.data.ragOptions,
                agent_id=self.data.agentMeta.agentId,
            )
            async for event in executor.execute():
                logger.info(f"[request_id={self.data.requestId.hex}] 返回事件 {event}")
                yield event

        elif self.data.framework == "langgraph":
            logger.info(f"[request_id={self.data.requestId.hex}] 运行框架为 langgraph")
            executor = LangGraphExecutor(
                self.data.agentMeta,
                draft_mode=self.data.draftMode,
                user=self.user,
                chat_id=self.data.chatId,
                request_id=self.data.requestId.hex,
                question=self.data.question,
                variables=self.data.variables,
                chat_history=self.data.chatHistory,
                rag_options=self.data.ragOptions,
                agent_id=self.data.agentMeta.agentId,
            )
            async for chunk in executor.execute():
                yield chunk
```

> **面试要点**：
> 
> 1. **策略模式分发**：`AutoAgentExecutor` 是门面，按请求的 `framework` 字段（`"adk"` / `"langgraph"`）选择执行器，同一套 `AgentMeta/User` 参数透传——框架可插拔，新增框架只需加一个分支。
> 
> 2. **Langfuse 追踪**：`execute()` 以 `trace_id=self.data.requestId.hex` 开启 span（span 名 = agent 名），与 FastAPI 中间件的 `ai-insight-agent` span 形成嵌套链路。
> 
> 

### **9\.4 ADKExecutor（核心执行器）**

**`agent/auto_agent/framework_adapter/adk/executor.py`**（443 行，FULL）：

```Python
import json
import re
import uuid
from typing import Any, AsyncGenerator, Dict, Optional

import litellm.exceptions
from google.adk import Runner
from google.adk.agents import LlmAgent
from google.adk.agents.callback_context import CallbackContext
from google.adk.agents.readonly_context import ReadonlyContext
from google.adk.artifacts import InMemoryArtifactService
from google.adk.events import Event
from google.adk.models import LlmRequest, LlmResponse
from google.adk.models.lite_llm import LiteLlm
from google.adk.planners.plan_re_act_planner import PLANNING_TAG
from google.adk.sessions import State
from google.adk.tools import BaseTool, ToolContext
from google.genai import types
from json_repair import repair_json

from auto_agent.framework_adapter.adk.prompt import quote_prompt
from auto_agent.framework_adapter.adk.tool import CommonTool, KnowledgeTool
from auto_agent.model import AgentMeta, ChatItem, RagOptions, User
from common.constants import DATASET_INFO, HEADER_NAME_ORG_CODE_OUT
from services.google_adk.in_redis_memory_service import InRedisMemoryService
from services.google_adk.in_redis_session_service import InRedisSessionService
from services.google_adk.lite_llm import CustomLiteLlm
from services.google_adk.llm_agent import CustomLlmAgent
from settings import MAIP_SECRET_KEY
from utils.log_manager import logger

session_service = InRedisSessionService()
memory_service = InRedisMemoryService()
artifact_service = InMemoryArtifactService()


class ADKExecutor:
    """ADK agent 执行器"""

    def __init__(
        self,
        agent_meta: AgentMeta,
        draft_mode: bool,
        user: User,
        chat_id: str,
        request_id: str,
        question: str,
        variables: dict,
        chat_history: list[ChatItem],
        rag_options: RagOptions,
        agent_id: str,
    ):
        self.agent_meta = agent_meta
        self.app_name = f"auto_agent_{user.tenantid}"
        self.draft_mode = draft_mode
        self.user = user
        self.chat_id = chat_id
        self.request_id = request_id
        self.question = question
        self.variables = variables
        self.chat_history = chat_history
        self.rag_options = rag_options
        self.agent_id = agent_id

    async def _init_session(self):
        """初始化 session， 每次运行都新建 session，以传入的 chat_history 为准"""
        kwargs = {"app_name": self.app_name, "user_id": self.user.userid, "session_id": self.chat_id}
        session = await session_service.get_session(**kwargs)

        # 没传对话历史，默认用 redis 里的，不删除已有对话
        if not self.chat_history:
            if not session:
                session = await session_service.create_session(**kwargs)
            return session

        if session:
            await session_service.delete_session(**kwargs)
        # 新建 session
        session = await session_service.create_session(**kwargs)
        # 添加事件
        if self.chat_history:
            for item in self.chat_history:
                event = Event(
                    invocation_id=self.request_id,
                    author=self.agent_meta.name if item.obj == "AI" else "user",
                    id=uuid.uuid4().hex,
                    content=types.Content(
                        parts=[
                            types.Part.from_text(text=item.value),
                        ],
                        role="model" if item.obj == "AI" else "user",
                    ),
                )
                await session_service.append_event(session, event)

        return session

    async def execute(
        self,
    ) -> AsyncGenerator[str, None]:
        """adk 执行入口"""
        logger.info(f"[request_id={self.request_id}] 开始执行 agent")
        logger.info(f"[request_id={self.request_id}] 用户问题 {self.question}")

        await self._init_session()
        logger.info(f"[request_id={self.request_id}] session 初始化完成")

        # 构建运行时
        runner = Runner(
            app_name=self.app_name,
            agent=self.agent,
            artifact_service=artifact_service,
            session_service=session_service,
            memory_service=memory_service,
        )

        idx = 0  # 事件序号
        # 用户信息
        new_message = types.Content(role="user", parts=[types.Part(text=self.question)])
        event_buffer = []

        # run_config = RunConfig(
        #     max_llm_calls=20,
        # 这会将大模型的输出真正改为流式，一个字(Event)一个字往外蹦
        # 不过似乎只在固定agent下面有效果，先关闭：for code agent一次性输出code
        # streaming_mode=StreamingMode.SSE,
        # )
        try:
            async for event in runner.run_async(
                user_id=self.user.userid,
                session_id=self.chat_id,
                new_message=new_message,
                state_delta={HEADER_NAME_ORG_CODE_OUT: self.user.tenantid, DATASET_INFO: []},
                # run_config=run_config,
            ):
                event = self.fill_custom_metadata(event, idx)
                event_buffer.append(event)
                idx += 1
                if len(event_buffer) > 1:
                    pre_event = event_buffer.pop(0)
                    # 如果前一个 event 是 function_call，基于当前 event 设置一下工具类型、头像
                    if pre_event.get_function_calls() and event.get_function_responses():
                        pre_event.custom_metadata["type"] = event.custom_metadata.get("type")
                        pre_event.custom_metadata["avatar"] = event.custom_metadata.get("avatar")
                    yield f"event: {pre_event.custom_metadata.get('type')}\ndata: {pre_event.model_dump_json()}\n\n"
        except Exception as e:
            logger.info(f"[request_id={self.request_id}] agent 执行错误 {e}")
            if isinstance(e, litellm.exceptions.Timeout):
                yield f"event: llm\ndata: {json.dumps({'error_message': 'agent 处理超时'})}\n\n"
            else:
                yield f"event: llm\ndata: {json.dumps({'error_message': str(e)})}\n\n"
            return
        if event_buffer:
            final_event = event_buffer[0]
            # 增加结束标记
            final_event.custom_metadata["is_runner_final"] = True
            yield f"event: {final_event.custom_metadata.get('type')}\ndata: {final_event.model_dump_json()}\n\n"

        await runner.close()

    def fill_custom_metadata(self, event: Event, idx: int) -> Event:
        """填充一些自定义标记字段"""
        if not event.custom_metadata:
            event.custom_metadata = {}
        event.custom_metadata["number"] = idx
        event.custom_metadata["is_runner_start"] = idx == 0
        event.custom_metadata["type"] = "llm"

        if event.content and event.content.parts:
            for i, part in enumerate(event.content.parts):
                if part.function_call:
                    event.custom_metadata["id"] = part.function_call.id
                    event.custom_metadata["name"] = part.function_call.name
                if part.function_response:
                    event.custom_metadata["id"] = part.function_response.id
                    event.custom_metadata["name"] = part.function_response.name
                    # 记录一下工具类型
                    event.custom_metadata["type"] = part.function_response.response.get("tool_type")
                    # 记录工具头像
                    event.custom_metadata["avatar"] = part.function_response.response.get("avatar")
                    # 多模态工具生成的文件
                    if result := part.function_response.response.get("result"):
                        try:
                            result = json.loads(result)
                            if isinstance(result, dict):
                                if file_infos := result.pop("fileInfos", None):
                                    event.custom_metadata["fileInfos"] = file_infos
                            # 干掉 result 中的文件信息，更新 result
                            part.function_response.response["result"] = json.dumps(result, ensure_ascii=False)
                        except Exception as e:
                            logger.info(f"[request_id={self.request_id}] 解析文件信息 {e}")

                # 任务规划
                if part.text and PLANNING_TAG in part.text:
                    event.custom_metadata["plan"] = True
                    event.custom_metadata["plan_index"] = i
                # 思考过程
                elif part.thought:
                    event.custom_metadata["thought"] = True
                    event.custom_metadata["thought_index"] = i
        return event

    def _replace_variable(self, s: str) -> str:
        """替换 prompt 中的全局变量占位符"""
        if not self.variables:
            return s
        for k, v in self.variables.items():
            s = s.replace("{{" + str(k) + "}}", str(v))
        return s

    def get_prompt(self, context: ReadonlyContext = None) -> str:
        """获取 prompt"""
        text = self._replace_variable(self.agent_meta.promptInfo)
        # 如果有临时知识库，进行背景知识库拼接
        if self.rag_options and self.rag_options.quoteQA:
            rag = "\n".join([x.model_dump_json() for x in self.rag_options.quoteQA])
            text += f"\n\n【背景知识】\n如果背景知识中存在与问题直接相关的信息，请优先且仅使用这些信息进行总结或回答\n以下是背景知识\n{rag}"

        # 如果有长期记忆，进行拼接
        if self.agent_meta.longTermMemory:
            text += f"\n\n【长期记忆】以下是和问题相关的记忆n{self.agent_meta.longTermMemory}"

        # 添加溯源提示词
        if self.agent_meta.knowledge and self.agent_meta.knowledge.showSource:
            text += "\n" + quote_prompt

        # 添加多模态兜底回复
        text += "\n" + """## 重要
当用户想要生成一个多模态内容，但你无法提供多模态文件时，直接仅回复下面文本内容，不要有任何额外解释性文本：
很遗憾，当前我没有直接生成{图片/视频/音频/文件}的能力和对应的工具调用。不过你可以调用多模态模型和工具进行支持。"""

        return text

    @property
    def agent(self) -> LlmAgent:
        agent = CustomLlmAgent(
            model=self.llm,
            name=self.agent_meta.name,
            description=self._replace_variable(self.agent_meta.intro),
            instruction=self.get_prompt,
            tools=self.tools,
            after_tool_callback=self.after_tool_call,
            after_model_callback=self.after_model_callback,
            before_model_callback=self.before_model_callback,
        )

        # 有工具时才进行任务规划
        # if agent.tools:
        #     agent.planner = CustomPlanner()
        return agent

    @property
    def llm(self) -> LiteLlm:
        model_info = self.agent_meta.modelInfo
        return CustomLiteLlm(
            model=model_info.modelId.split("_", 1)[-1],  # serviceUniCode 都会有个类似 maip_ 的前缀
            base_url=model_info.modelUrl.removesuffix("/chat/completions"),  # 模型 url 去掉 /chat/completions 后缀
            api_key=MAIP_SECRET_KEY,
            custom_llm_provider="openai",
            max_tokens=model_info.maxTokens,
            request_timeout=model_info.timeout,
            timeout=model_info.timeout,
            stream_timeout=model_info.timeout,
            max_retries=0,
            temperature=model_info.temperature,
            max_completion_tokens=10240,
        )

    @property
    def tools(self) -> list[BaseTool]:
        """生成工具列表"""
        tools = []
        # 知识库工具
        if knowledge := self.agent_meta.knowledge:
            for item in knowledge.knowledgeInfoList or []:
                tool = KnowledgeTool(
                    data=item,
                    retrieve_max_length=knowledge.retrieveMaxLength,
                    show_source=knowledge.showSource,
                    backup_strategy=knowledge.backupStrategy,
                    draft_mode=self.draft_mode,
                    user=self.user,
                    request_id=self.request_id,
                    agent_id=self.agent_id,
                    chat_id=self.chat_id,
                )
                tools.append(tool)
        # 其余工具
        if _tools := self.agent_meta.tools:
            # 插件
            if _tools.pluginInfoList:
                for plugin in _tools.pluginInfoList:
                    tool = CommonTool(
                        plugin,
                        tool_type="plugin",
                        draft_mode=self.draft_mode,
                        user=self.user,
                        request_id=self.request_id,
                        agent_id=self.agent_id,
                        chat_id=self.chat_id,
                        question=self.question,
                    )
                    tools.append(tool)
            # mcp
            if _tools.mcpInfoList:
                for _mcp in _tools.mcpInfoList:
                    tool = CommonTool(
                        _mcp,
                        tool_type="mcp",
                        draft_mode=self.draft_mode,
                        user=self.user,
                        request_id=self.request_id,
                        agent_id=self.agent_id,
                        chat_id=self.chat_id,
                        question=self.question,
                    )
                    tools.append(tool)
            # workflow
            if _tools.workflowInfoList:
                for workflow in _tools.workflowInfoList:
                    tool = CommonTool(
                        workflow,
                        tool_type="workflow",
                        draft_mode=self.draft_mode,
                        user=self.user,
                        request_id=self.request_id,
                        agent_id=self.agent_id,
                        chat_id=self.chat_id,
                        question=self.question,
                    )
                    tools.append(tool)
            # agent
            if _tools.agentInfoList:
                for _agent in _tools.agentInfoList:
                    tool = CommonTool(
                        _agent,
                        tool_type="autoAgent",
                        draft_mode=self.draft_mode,
                        user=self.user,
                        request_id=self.request_id,
                        agent_id=self.agent_id,
                        chat_id=self.chat_id,
                        question=self.question,
                    )
                    tools.append(tool)
        return tools

    @staticmethod
    def after_tool_call(
        tool: BaseTool, args: Dict[str, Any], tool_context: ToolContext, tool_response: Dict | str
    ) -> Optional[Dict]:
        """工具调用钩子函数"""
        # 设置工具类型
        tool_type = getattr(tool, "tool_type", "")
        tool_response["tool_type"] = tool_type
        tool_response["avatar"] = tool.data.avatar

        # 记录知识库调用
        if tool_type == "dataset":
            try:
                cur = json.loads(tool_response["result"]) or []
                for chunk in cur:
                    tool_context.state[DATASET_INFO].append([chunk["id"], chunk["collectionId"], chunk["datasetId"]])
            except Exception:
                pass
        return tool_response

    async def before_model_callback(
        self, callback_context: CallbackContext, llm_request: LlmRequest
    ) -> Optional[LlmResponse]:
        if not llm_request.contents:
            return None

        user_indices = [
            i
            for i, item in enumerate(llm_request.contents)
            if item.role == "user" and item.parts and item.parts[0].text
        ]

        if not user_indices:
            return None

        keep_turns = min((self.agent_meta.modelInfo.historyRound or 3) + 1, len(user_indices))
        start_user_idx = user_indices[-keep_turns]

        llm_request.contents = llm_request.contents[start_user_idx:]

        return None

    async def after_model_callback(
        self, callback_context: CallbackContext, llm_response: LlmResponse
    ) -> Optional[LlmResponse]:
        """处理大模型返回"""
        if not llm_response.content or not llm_response.content.parts:
            return llm_response

        for part in llm_response.content.parts:
            try:
                self.fix_quote(part, callback_context.state)
            except Exception as e:
                logger.info(f"[request_id={self.request_id}] 处理引用失败 {e}")

        return None

    def fix_quote(self, part: types.Part, state: State) -> None:
        """处理引用问题"""
        if not part.text:
            return

        quote_re = re.compile("`quoteMark.*?`", re.DOTALL)
        quote_list_re = re.compile("```quoteList.*?```", re.DOTALL)

        # 查找 quote 信息，检查是否是知识库引用，重编序号
        quote_id = 1  # 重新编号
        quote_map = {}  # 记录映射关系
        quote_list = []  # 最终引用列表

        for quote in quote_re.findall(part.text):
            # 已处理
            if quote in quote_map:
                continue
            quote_data = repair_json(quote, return_objects=True, ensure_ascii=False)
            key = [quote_data["id"], quote_data["collectionId"], quote_data["datasetId"]]
            if key not in state[DATASET_INFO]:
                # 不是知识库引用，无效，替换成空字符串
                quote_map[quote] = ""
                continue
            # 有效的，重新编号
            quote_data["quoteId"] = quote_id
            quote_id += 1
            quote_map[quote] = f"`quoteMark\n{json.dumps(quote_data, ensure_ascii=False, indent=4)}`"
            quote_list.append(quote_data)

        # 替换引用信息
        for k, v in quote_map.items():
            part.text = part.text.replace(k, v)
        # 替换 quoteList
        quote_list = f"```quoteList\n{json.dumps(quote_list, ensure_ascii=False, indent=4)}```" if quote_list else ""
        for _quote_list in quote_list_re.findall(part.text):
            part.text = part.text.replace(_quote_list, quote_list)
        if not quote_list:
            part.text = part.text.strip().removesuffix("参考文献：").strip()
        return None
```

> **面试要点**：
> 
> 1. **Session 双策略**：`_init_session` 每次运行都以 `app_name=auto_agent_{tenantid}` \+ `session_id=chat_id` 定位 Redis session。**没传 chat\_history 时复用 Redis 已有对话**；传了则以 `chat_history` 为准——先 `delete_session` 再 `create_session`，把历史对话用 `types.Part.from_text` 逐个 `append_event` 重放，保证多轮上下文一致。
> 
> 2. **SSE 事件缓冲对**：`event_buffer` 只缓存 1 个前驱事件——**function\_call 事件被延迟到拿到下一个 function\_response 事件时才输出**，用后者的 `tool_type/avatar` 补全前者的 `custom_metadata`（前端渲染工具调用需要头像/类型）。
> 
> 3. **结束标记**：所有事件消费完后，把 `event_buffer` 中最后一个事件的 `custom_metadata["is_runner_final"]=True`，前端据此判定流结束；执行异常时输出 `{"error_message": ...}`，并对 `litellm.exceptions.Timeout` 特判返回"agent 处理超时"。
> 
> 4. **事件加工 ****`fill_custom_metadata`**：统一打 `number`（序号）、`is_runner_start`（首事件）、`type="llm"` 默认值；`function_call` 记 `id/name`；`function_response` 取 `tool_type/avatar`（`after_tool_call` 写入）并抽取 `fileInfos` 存到 `custom_metadata`（多模态文件结果剥离出 `result`，避免污染返回文本）；文本含 `PLANNING_TAG` 标 `plan`，`part.thought` 标 `thought`。
> 
> 5. **工具组装 ****`tools`**：知识库 → `KnowledgeTool`（传 `retrieveMaxLength/showSource/backupStrategy`）；插件/mcp/workflow/子 agent → `CommonTool`，分别以 `tool_type` 区分（plugin/mcp/workflow/autoAgent）。全部透传 `user/request_id/agent_id/chat_id/question` 等上下文。
> 
> 6. **LLM 组装**：`modelId.split("_",1)[-1]` 去掉 `maip_` 前缀；`modelUrl.removesuffix("/chat/completions")` 还原 base\_url；`api_key=MAIP_SECRET_KEY`、`custom_llm_provider="openai"`、`max_retries=0`、`max_completion_tokens=10240`。
> 
> 7. **历史截断 ****`before_model_callback`**：只保留最近 `historyRound(默认3)+1` 个 **user 轮**（以 `item.role=="user" and parts[0].text` 为基准找索引切片），控制 token。
> 
> 8. **引用重排 ****`fix_quote`**：正则 `\`quoteMark\.\*?\``匹配引用块，`repair\_json`解析（容错损坏 JSON），校验`\[id, collectionId, datasetId\]`三元组必须出现在`state\[DATASET\_INFO\]`（`after\_tool\_call`里从知识库召回结果填充）——合法的重新编号`quoteId`递增，非法的替换为空串；`\`\`\`quoteList\`\`\`\` 块同步重建，无引用时移除"参考文献："。
> 
> 9. **Prompt 拼接 ****`get_prompt`**：`{{变量}}` 占位符替换 → 追加临时知识库 `quoteQA`（`【背景知识】`）→ 长期记忆 → `quote_prompt` 溯源格式 → 多模态兜底话术。
> 
> 

### **9\.5 工具层 tool\.py（KnowledgeTool / CommonTool）**

**`agent/auto_agent/framework_adapter/adk/tool.py`**（259 行，FULL）：

```Python
import json
from typing import Any, Optional

import httpx
from google.adk.tools import BaseTool, ToolContext
from google.genai.types import FunctionDeclaration
from typing_extensions import override

from auto_agent.model import Knowledge, Tool, User, ToolParameters
from auto_agent.observability import fill_langfuse
from settings import AGENT_FLOW_SERVER_URL
from utils.log_manager import logger

timeout = httpx.Timeout(connect=10, timeout=300)


def success(s: str) -> dict:
    if not isinstance(s, str):
        s = json.dumps(s, ensure_ascii=False)
    return {"result": s, "error_message": None}


def fail(s: str) -> dict:
    return {"result": "null", "error_message": s}


class CommonTool(BaseTool):
    """通用工具类"""

    def __init__(
        self,
        data: Tool,
        tool_type: str,
        draft_mode: bool,
        user: User,
        request_id: str,
        agent_id: str,
        chat_id: str,
        question: str,
    ):
        super().__init__(name=data.name, description=data.description)
        self.data = data
        self.tool_type = tool_type
        self.draft_mode = draft_mode
        self.user = user
        self.request_id = request_id
        self.agent_id = agent_id
        self.chat_id = chat_id
        self.question = question

    @property
    def headers(self) -> dict:
        return {
            "userId": self.user.userid,
            "userName": self.user.username,
            "tenantId": self.user.tenantid,
            "orgId": self.user.teamid,
            "orgCode": self.user.teamname,
        }

    @override
    @fill_langfuse
    async def run_async(
        self,
        *,
        args: dict[str, Any],
        tool_context: ToolContext,
    ) -> Any:
        logger.info(f"[request_id={self.request_id}] 调用工具 {self.name}")
        url = f"{AGENT_FLOW_SERVER_URL}/api/v1/tools/run"

        data = {
            "id": self.data.id,
            "name": self.data.name,
            "type": self.tool_type,
            "params": args,
            "draftMode": self.draft_mode,
            "agentId": self.agent_id,
            "requestId": self.request_id,
            "chatId": self.chat_id,
        }
        # 调用插件执行接口
        async with httpx.AsyncClient(timeout=timeout) as client:
            try:
                logger.info(
                    f"[request_id={self.request_id}] 调用工具执行接口 {url}, 请求头 {self.headers}, 请求参数 {data}"
                )
                response = await client.post(url, json=data, headers=self.headers)
                logger.info(f"[request_id={self.request_id}] 工具执行返回 {response.text}")
                response.raise_for_status()
            except Exception as e:
                logger.info(f"[request_id={self.request_id}] 工具调用错误 {e}")
                return fail(f"call tool-execute fail: {e}")
        res = response.json()
        if res["code"] != "10000":
            return fail(f"tool execute fail: {res['message']}")
        # 设置是否需要模型总结
        tool_context.actions.skip_summarization = self.data.skipSummarization

        # 处理生成文件
        data = res["data"]
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except Exception:
                return success(data)
        if isinstance(data, dict) and data.get("fileInfos") and not data.get("answer"):
            names = "、".join(x.get("fileName") for x in data.get("fileInfos"))
            data["answer"] = f"已为您生成以下文件：{names}"

        return success(data)

    @override
    def _get_declaration(self) -> Optional[FunctionDeclaration]:
        desc = self.description

        # 增加固定参数
        if self.tool_type in ["autoAgent", "workflow"]:
            if not self.data.parameters:
                self.data.parameters = ToolParameters(
                    type="OBJECT",
                    required=[],
                    properties={}
                )
            self.data.parameters.properties["question"] = {
                "description": "用户输入参数",
                "type": "STRING"
            }
            self.data.parameters.required.append("question")

        if self.data.parameters:
            desc += f"""
        Args:
            {self.data.parameters.model_dump_json()}
        """
        return FunctionDeclaration(
            name=self.name,
            description=desc,
        )


class KnowledgeTool(BaseTool):
    """知识库工具类"""

    def __init__(
        self,
        data: Knowledge,
        retrieve_max_length: int,
        show_source: bool,
        backup_strategy: dict,
        draft_mode: bool,
        user: User,
        request_id: str,
        agent_id: str,
        chat_id: str,
    ):
        super().__init__(name=data.name, description=data.description)
        self.tool_type: str = "dataset"
        self.data = data
        self.retrieve_max_length = retrieve_max_length
        self.show_source = show_source
        self.backup_strategy = backup_strategy
        self.draft_mode = draft_mode
        self.user = user
        self.request_id = request_id
        self.agent_id = agent_id
        self.chat_id = chat_id

    @property
    def headers(self) -> dict:
        return {
            "userId": self.user.userid,
            "userName": self.user.username,
            "tenantId": self.user.tenantid,
            "orgId": self.user.teamid,
            "orgCode": self.user.teamname,
        }

    @override
    @fill_langfuse
    async def run_async(
        self,
        *,
        args: dict[str, Any],
        tool_context: ToolContext,
    ) -> Any:
        search_mode_mode_map = {1: "embedding", 2: "fullTextRecall", 3: "mixedRecall"}
        data = {
            "id": self.data.id,
            "name": self.data.name,
            "type": self.tool_type,
            "draftMode": self.draft_mode,
            "agentId": self.agent_id,
            "requestId": self.request_id,
            "chatId": self.chat_id,
            "params": {
                "moduleType": "datasetSearchNode",
                "flowType": "datasetSearchNode",
                "inputs": {
                    "topK": 150,
                    "rrfSwitch": True,
                    "vectorSearchRatio": 0.6,
                    "searchType": 0,
                    "numCandidates": 200,
                    "searchMode": search_mode_mode_map.get(self.data.searchStrategy, "embedding"),
                    "reRankerSwitch": self.data.useRerank,
                    "datasets": [{"datasetId": self.data.id}],
                    "recallLimit": self.data.maxRecallCount,
                    "userChatInput": args.get("question"),
                    "searchMethod": 0,
                    "similarity": self.data.minScore,
                    "fullTextSearchRatio": 0.4,
                    "limit": self.retrieve_max_length,
                },
                "stream": False,
            },
        }
        url = f"{AGENT_FLOW_SERVER_URL}/api/v1/tools/run"
        # 调用插件执行接口
        async with httpx.AsyncClient(timeout=timeout) as client:
            try:
                logger.info(
                    f"[request_id={self.request_id}] 调用工具执行接口 {url}, 请求头 {self.headers}, 请求参数 {data}"
                )
                response = await client.post(url, json=data, headers=self.headers)
                logger.info(f"[request_id={self.request_id}] 工具执行返回 {response.text}")
                response.raise_for_status()
            except Exception as e:
                logger.info(f"[request_id={self.request_id}] 工具调用错误 {e}")
                return fail(f"call tool-execute fail: {e}")
        res = response.json()
        if res["code"] != "10000":
            return fail(f"tool execute fail: {res['message']}")
        try:
            knowledge_data = json.loads(res["data"])
            # 删除无用字段
            result = []
            for item in knowledge_data:
                result.append({k: v for k, v in item.items() if k in ["id", "collectionId", "datasetId", "q"]})
            if result:
                return success(result)

            # 如果没有召回，尝试用自定义回答回复
            if len(knowledge_data) == 0 and self.backup_strategy and self.backup_strategy.get("backupMode") == 2:
                return success(self.backup_strategy.get("customAnswer"))
            # 解析知识库返回
            return success(res["data"])
        except Exception as e:
            logger.info(f"[request_id={self.request_id}] 解析知识库召回失败 {e}")

            return success(res["data"])

    @override
    def _get_declaration(self) -> Optional[FunctionDeclaration]:
        args_str = """
        Args:
            question(str): 拿去知识库检索的问题
        """
        return FunctionDeclaration(name=self.name, description="知识库工具：\n" + self.description + "\n" + args_str)
```

> **面试要点**：
> 
> 1. **统一返回结构**：`success()` 返回 `{"result": str, "error_message": None}`，`fail()` 返回 `{"result": "null", "error_message": s}`——ADK 工具结果必须让 LLM 能稳定解析，`result` 统一序列化成字符串。
> 
> 2. **工具执行走 HTTP 转发**：`CommonTool.run_async` 把工具调用 POST 到 Java 侧 `{AGENT_FLOW_SERVER_URL}/api/v1/tools/run`（`AGENT_FLOW_SERVER_URL` 是 agentflow\-server），请求头带 `userId/userName/tenantId/orgId/orgCode` 四户上下文，body 带 `id/name/type/params/draftMode/agentId/requestId/chatId`——**Agent 不直接执行插件，由后端流程引擎执行**。
> 
> 3. **文件工具兜底**：返回的 `data` 若含 `fileInfos` 且无 `answer`，自动生成"已为您生成以下文件：xxx"作为回答文本。
> 
> 4. **跳过模型总结**：`tool_context.actions.skip_summarization = self.data.skipSummarization` 控制 ADK 是否让 LLM 对工具结果做摘要。
> 
> 5. **函数声明**：`_get_declaration` 对 `autoAgent/workflow` 类型强制注入 `question` 字符串参数并加入 `required`，保证子 agent/工作流有统一输入；`KnowledgeTool` 只暴露 `question(str)`。
> 
> 6. **知识库检索参数**：`KnowledgeTool` 通过 `moduleType/flowType=datasetSearchNode` 触发 Java 侧的 **RRF 混合检索**——`topK=150`、`rrfSwitch=True`、`vectorSearchRatio=0.6/fullTextSearchRatio=0.4`（向量\+全文配比）、`searchMode` 由 `searchStrategy` 映射（1 embedding / 2 fullTextRecall / 3 mixedRecall）、`reRankerSwitch=useRerank`、`similarity=minScore`、`limit=retrieve_max_length`。
> 
> 7. **召回结果瘦身**：只保留 `["id","collectionId","datasetId","q"]` 四个字段回传给 LLM，减少 token；无召回且 `backupStrategy.backupMode==2` 时返回 `customAnswer` 兜底。
> 
> 

### **9\.6 CustomPlanner（中文 ReAct 计划器）**

**`agent/auto_agent/framework_adapter/adk/custom_planner.py`**（123 行，FULL）：

```Python
from google.adk.planners import PlanReActPlanner
from google.adk.planners.plan_re_act_planner import (
    ACTION_TAG,
    FINAL_ANSWER_TAG,
    PLANNING_TAG,
    REASONING_TAG,
    REPLANNING_TAG,
)
from google.genai import types


class CustomPlanner(PlanReActPlanner):
    """
    制定计划
    """

    def _build_nl_planner_instruction(self) -> str:
        high_level_preamble = f"""
在回答问题时，尽量利用可用的工具来收集信息，而不是你记忆中的知识。
回答问题时遵循以下过程：（1）首先以自然语言文本格式提出计划；（2）然后使用工具执行计划，并在工具代码片段之间提供推理，以总结当前状态和下一步。工具代码片段和推理应该相互交织。（3）最后，返回一个最终答案。
回答问题时请遵循以下格式：（1）计划部分应在{PLANNING_TAG}下。（2）工具代码片段应位于{ACTION_TAG}之下，推理部分应位于{REASONING_TAG}之中。（3）最终答案部分应在{FINAL_ANSWER_TAG}下。
"""

        planning_preamble = f"""
规划要求如下：
制定计划是为了在遵循计划的情况下回答用户查询。该计划是连贯的，涵盖了用户查询信息的各个方面，只涉及代理可访问的工具。该计划以编号列表的形式包含分解的步骤，其中每个步骤应使用一个或多个可用工具。通过阅读计划，您可以直观地知道要触发哪些工具或采取哪些行动。
如果最初的计划不能成功执行，你应该从之前的执行结果中学习并修改你的计划。修订后的计划应在{REPLANNING_TAG}下。然后使用工具来遵循新计划。
"""

        reasoning_preamble = """
以下是推理的要求：
推理基于用户查询和工具输出对当前轨迹进行总结。基于工具输出和计划，推理还提出了下一步的指令，使轨迹更接近最终答案。
"""

        final_answer_preamble = """
以下是最终答案的要求：
最终答案应该精确，并遵循查询格式要求。有些问题可能无法用现有的工具和信息来回答。在这些情况下，请告知用户您无法处理他们的查询的原因，并要求提供更多信息。
"""

        # Only contains the requirements for custom tool/libraries.
        tool_code_without_python_libraries_preamble = """
以下是工具代码的要求：

**自定义工具：**可用工具在上下文中进行了描述，可以直接使用。
-代码必须是有效的自包含Python代码段，没有导入，也没有引用不在上下文中的工具或Python库。
-您不能在上下文中使用API中未明确定义的任何参数或字段。
-代码片段应该可读、高效，并且与用户查询和推理步骤直接相关。
-使用这些工具时，您应该将库名称与函数名称一起使用，例如vertex_search.search（）。
-如果上下文中没有提供Python库，除了使用提供的工具编写函数调用外，永远不要编写自己的代码。
"""

        user_input_preamble = """
除上述说明外，您还必须遵守非常重要的说明：

如果你需要更多信息来回答这个问题，你应该要求澄清。
您应该更喜欢使用上下文中可用的信息，而不是重复使用工具。
"""

        return "\n\n".join(
            [
                high_level_preamble,
                planning_preamble,
                reasoning_preamble,
                final_answer_preamble,
                tool_code_without_python_libraries_preamble,
                user_input_preamble,
            ]
        )

    def _handle_non_function_call_parts(self, response_part: types.Part, preserved_parts: list[types.Part]):
        """Handles non-function-call parts of the response.

        Args:
          response_part: The response part to handle.
          preserved_parts: The mutable list of parts to store the processed parts
            in.
        """
        if response_part.text and FINAL_ANSWER_TAG in response_part.text:
            reasoning_text, final_answer_text = self._split_by_last_pattern(response_part.text, FINAL_ANSWER_TAG)
            if reasoning_text:
                reasoning_text = self._only_keep_plan(reasoning_text)
                reasoning_part = types.Part(text=reasoning_text)
                self._mark_as_thought(reasoning_part)
                preserved_parts.append(reasoning_part)
            if final_answer_text:
                preserved_parts.append(
                    types.Part(
                        text=final_answer_text,
                    )
                )
        else:
            response_text = response_part.text or ""
            # If the part is a text part with a planning/reasoning/action tag,
            # label it as reasoning.
            if response_text and (
                any(
                    response_text.startswith(tag)
                    for tag in [
                        PLANNING_TAG,
                        REASONING_TAG,
                        ACTION_TAG,
                        REPLANNING_TAG,
                    ]
                )
            ):
                response_part.text = self._only_keep_plan(response_part.text)
                self._mark_as_thought(response_part)
            preserved_parts.append(response_part)

    def _only_keep_plan(self, text: str) -> str:
        """
        只保留返回中 plan 部分，去掉其余部分
        @param text: 结果文本
        @return: 提取的计划部分
        """
        # 增加一点自定义逻辑，只保留 plan 部分，删除掉 action 和 reason
        if ACTION_TAG in text:
            text = text.split(ACTION_TAG, 1)[0]
        if REASONING_TAG in text:
            text = text.split(REASONING_TAG, 1)[0]
        if FINAL_ANSWER_TAG in text:
            text = text.split(FINAL_ANSWER_TAG, 1)[0]
        return text
```

> **面试要点**：
> 
> 1. **中文提示词**：重写 `_build_nl_planner_instruction`，把 ADK 英文计划器提示词换成中文，强调"先计划→工具执行→推理→最终答案"，并用 `PLANNING_TAG/ACTION_TAG/REASONING_TAG/FINAL_ANSWER_TAG/REPLANNING_TAG` 约束输出格式。
> 
> 2. **纯文本分流**：`_handle_non_function_call_parts` 把含 `FINAL_ANSWER_TAG` 的文本按标记拆分——推理部分标为 `thought`，最终答案部分保留为文本；以计划/推理/行动标记开头的文本一律 `_only_keep_plan` 截断并标为 thought（前端当思考过程渲染，不直接展示）。
> 
> 3. **计划截断**：`_only_keep_plan` 用 `split(tag, 1)[0]` 依次砍掉 ACTION/REASONING/FINAL\_ANSWER 之后的内容，只留计划本身，避免模型把完整 CoT 泄露给用户。
> 
> 

### **9\.7 IntentAgent 意图判定与路由**

**`agent/agents/intent_agent/agent.py`**（88 行，FULL）：

```Python
import json

from google.adk.agents.callback_context import CallbackContext
from google.adk.models.llm_request import LlmRequest
from google.adk.models.llm_response import LlmResponse
from google.genai.types import Content, Part

from agents.direct_analysis_agent.agent import root_agent as direct_analysis_agent
from agents.intent_agent.explicit_branch import check_strategy_publish, check_strategy_use, request_create_task_mcp_tool
from agents.intent_agent.prompt import get_instruction
from agents.intent_agent.utils import judge_intent_by_tool
from agents.metadata_agent.agent import root_agent as metadata_agent
from agents.run_data_agent.agent import root_agent as run_data_agent
from agents.strategy_agent.agent import root_agent as strategy_agent
from agents.strategy_view_agent.agent import root_agent as strategy_view_agent
from common.constants import AGENT_INTENT_AGENT, LABEL_RUN_CREATE, LABEL_RUN_START
from common.state_key import TEMP_METADATA_LABEL
from services.google_adk.llm_agent import CustomLlmAgent
from settings import INTENT_ANALYSIS_WITH_TOOL_FLAG
from utils.log_manager import logger
from utils.model_factory import ModelFactory


async def before_agent_callback(callback_context: CallbackContext):
    """
    由前端明确传参需要走的固定逻辑，可直接在意图开始处处理
    """
    request_params = callback_context.state.get("request_params", {})
    label = request_params.get("metadata", {}).get("label", "")
    # 设置label 渲染prompt
    callback_context.state[TEMP_METADATA_LABEL] = label

    content = await check_strategy_publish(callback_context)
    if content:
        return content

    content = await check_strategy_use(callback_context)
    if content:
        return content

    return None


async def before_model_callback(callback_context: CallbackContext, llm_request: LlmRequest):
    request_params = callback_context.state.get("request_params", {})
    label = request_params.get("metadata", {}).get("label", "")

    if label and label != LABEL_RUN_CREATE:
        text = []
        # 请求mcp
        if label == LABEL_RUN_START:
            error = await request_create_task_mcp_tool(callback_context)
            if error:
                text = [
                    {
                        "key": "data",
                        "value": error,
                        "valueType": "string",
                    }
                ]
        return LlmResponse(
            content=Content(
                role="model",
                parts=[Part(text=json.dumps(text))],
            ),
            custom_metadata={
                "label": label,
            },
        )

    # 是否调用工具判断意图
    if INTENT_ANALYSIS_WITH_TOOL_FLAG:
        ret = await judge_intent_by_tool(callback_context=callback_context)
        logger.info(f"judge intent by tool finally get ret[{ret}]")
        return ret
    return


root_agent = CustomLlmAgent(
    name=AGENT_INTENT_AGENT,
    model=ModelFactory.create_model(use_custom_llm_model=True),
    description="意图判定智能体",
    sub_agents=[metadata_agent, strategy_agent, strategy_view_agent, direct_analysis_agent, run_data_agent],
    instruction=get_instruction(),
    before_agent_callback=before_agent_callback,
    before_model_callback=before_model_callback,
    include_contents="none",
)
```

**`agent/agents/intent_agent/explicit_branch.py`**（106 行，FULL）：

```Python
"""
意图识别这里，有些逻辑可以根据前端参数直接出结果
此种逻辑汇总到此处
"""

import json
from copy import deepcopy

from google.adk.agents.callback_context import CallbackContext
from google.genai.types import Content, Part

from agents.run_data_agent.tools import init_file_data_by_id
from agents.strategy_agent.strategy_create_agent.agent import get_strategy_detail
from common.constants import (
    FRONTEND_KEY_DATA,
    FRONTEND_VALUE_TYPE_OBJECT,
    FRONTEND_VALUE_TYPE_STRING,
    INSIGHT_MCP_TOOL_CREATE_TASK_SCHEDULE,
)
from common.state_key import TEMP_USER_SELECTED_STRATEGY_ID
from utils.log_manager import logger
from utils.mcp_tools import insightMCPTools


async def check_strategy_use(callback_context: CallbackContext):
    request_params = callback_context.state.get("request_params", {})
    params = request_params.get("metadata", {}).get("params", {})
    if not params.get("use_strategy"):
        return None

    strategy_id = params.get("strategy_id")
    if not strategy_id:
        return None

    callback_context.state[TEMP_USER_SELECTED_STRATEGY_ID] = strategy_id


async def check_strategy_publish(callback_context: CallbackContext):
    request_params = callback_context.state.get("request_params", {})
    params = request_params.get("metadata", {}).get("params", {})
    if not params.get("publish_strategy"):
        return None

    strategy_id = params.get("strategy_id")
    if not strategy_id:
        return None

    detail_data = await get_strategy_detail(strategy_id, callback_context)
    if not detail_data:
        return None
    strategy_name = detail_data.get("strategy_name")
    if not strategy_name:
        return None

    part_text = [
        {
            "key": FRONTEND_KEY_DATA,
            "value": f"已将“**{strategy_name}**”保存到场景中，是否使用该策略进行分析？",
            "valueType": FRONTEND_VALUE_TYPE_STRING,
        },
        {
            "key": FRONTEND_KEY_DATA,
            "value": {
                "buttonType": "strategyPublish",
                "strategyId": strategy_id,
                "strategyName": strategy_name,
            },
            "valueType": FRONTEND_VALUE_TYPE_OBJECT,
        },
    ]
    return Content(
        role="model",
        parts=[Part(text=json.dumps(part_text))],
    )


async def request_create_task_mcp_tool(callback_context: CallbackContext):
    """创建"""

    try:
        metadata = callback_context.state.get("request_params", {}).get("metadata", {})

        param = deepcopy(metadata.get("params", {}))
        param["userInfo"] = metadata.get("userInfo", {})
        param["businessSceneId"] = metadata.get("businessSceneId", "")
        if param.get("fileId"):
            df = await init_file_data_by_id(param["fileId"])
            if isinstance(df, str):
                return f"获取文件{param.get('fileId')}数据异常！"
            for item in param.get("tableFields"):
                item["listValue"] = [
                    str(x) for x in df.iloc[:, item.get("listValueIndex", 0)].to_list() if str(x).strip()
                ]

        mcp_data = await insightMCPTools.execute(
            tool_name=INSIGHT_MCP_TOOL_CREATE_TASK_SCHEDULE,
            args={"param": param},
            tool_context=callback_context,
            ret_dict=True,
        )
    except Exception:
        return f"{INSIGHT_MCP_TOOL_CREATE_TASK_SCHEDULE} 工具异常!"
    logger.info(f"request_create_task_mcp_tool, res:{mcp_data}")
    if mcp_data.get("code") not in {"10000"}:
        return mcp_data.get("message", f"{INSIGHT_MCP_TOOL_CREATE_TASK_SCHEDULE} 工具异常!")
    return None
```

**`agent/agents/intent_agent/utils.py`**（84 行，FULL）：

```Python
from google.adk.agents.callback_context import CallbackContext
from google.adk.models import LlmResponse
from google.genai import types

from common.constants import (
    AGENT_DIRECT_ANALYSIS_AGENT,
    AGENT_METADATA_AGENT,
    AGENT_RUN_DATA_AGENT,
    AGENT_STRATEGY_AGENT,
    AGENT_STRATEGY_VIEW_AGENT,
    INSIGHT_MCP_TOOL_FETCH_INTENT,
)
from common.msg import MSG_NOTIFICATION_DEFAULT
from utils.log_manager import logger
from utils.mcp_tools import insightMCPTools

_OTHER_INTENT = "other"
_valid_intent = [
    AGENT_STRATEGY_AGENT,
    AGENT_STRATEGY_VIEW_AGENT,
    AGENT_METADATA_AGENT,
    AGENT_DIRECT_ANALYSIS_AGENT,
    AGENT_RUN_DATA_AGENT,
    _OTHER_INTENT,
]


async def judge_intent_by_tool(callback_context: CallbackContext):
    """
    通过调用mcp接口来获取用户问题意图需要交给具体哪个agent执行或是直接回复
    """
    try:
        user_content = callback_context.user_content
        if not all([user_content, user_content.parts, user_content.parts[0].text]):
            logger.error("missing user question")
            return None

        metadata = callback_context.state.get("request_params", {}).get("metadata", {})
        args = {
            "question": user_content.parts[0].text,
            "metadata": {
                "label": metadata.get("label", ""),
                "params": {"fileId": metadata.get("params", {}).get("fileId", "")},
            },
            "chatId": callback_context._invocation_context.session.id,
        }
        if not args:
            logger.error("judge intent by tool failed: parse args error")
            return None
        ret = await insightMCPTools.execute(
            tool_name=INSIGHT_MCP_TOOL_FETCH_INTENT,
            args={"param": args},
            ret_dict=True,
            read_timeout_seconds=30,
        )
        intent_agent_name = ret.get("data", {}).get("intent")
        if intent_agent_name not in _valid_intent:
            logger.error(f"judge intent by tool failed: get invalid intent[{intent_agent_name}]")
            return None

        logger.info(f"judge intent by tool get intent[{intent_agent_name}]")
        if intent_agent_name == _OTHER_INTENT:
            return LlmResponse(
                content=types.Content(
                    role="model",
                    parts=[types.Part(text=MSG_NOTIFICATION_DEFAULT)],
                )
            )
        else:
            return LlmResponse(
                content=types.Content(
                    role="model",
                    parts=[
                        types.Part(
                            function_call=types.FunctionCall(
                                args={"agent_name": intent_agent_name}, name="transfer_to_agent"
                            )
                        )
                    ],
                )
            )
    except Exception as e:
        logger.error(f"judge intent by tool failed: error={e}")
        return None
```

> **面试要点**：
> 
> 1. **根 Agent 设计**：`root_agent` 名为 `AGENT_INTENT_AGENT`，`description="意图判定智能体"`，挂 5 个子 Agent（metadata/strategy/strategy\_view/direct\_analysis/run\_data），`include_contents="none"` 不让历史内容注入指令，通过 `before_agent_callback` \+ `before_model_callback` 两个回调完成意图路由。
> 
> 2. **两阶段判定**：`before_agent_callback`（agent 开始前）走前端显式传参的固定分支（`check_strategy_publish` / `check_strategy_use`，命中即返回 `Content` 直接出结果，**绕过大模型**）；`before_model_callback`（调模型前）先处理 label 特殊流程，再按 `INTENT_ANALYSIS_WITH_TOOL_FLAG` 开关决定是否调用 MCP 判定意图。
> 
> 3. **label 短路**：前端传 `metadata.label`（且非 `LABEL_RUN_CREATE`）时**跳过模型**，直接构造 `LlmResponse` 返回——`LABEL_RUN_START` 会先调 `request_create_task_mcp_tool` 创建任务，有错误则把错误文本包进 `[{key:"data",...}]` 返回给前端。
> 
> 4. **策略发布分支**：`check_strategy_publish` 命中 `publish_strategy + strategy_id` 时，调用 `get_strategy_detail` 取策略名，返回含 `buttonType="strategyPublish"` 的**交互按钮事件**（前端弹出"是否使用该策略"）。
> 
> 5. **创建任务 MCP**：`request_create_task_mcp_tool` 深拷贝 `params`，补 `userInfo/businessSceneId`；有 `fileId` 时用 `init_file_data_by_id` 读 DataFrame，把指定列值填入 `tableFields[].listValue`；调用 `INSIGHT_MCP_TOOL_CREATE_TASK_SCHEDULE`，`code=="10000"` 才算成功。
> 
> 6. **工具判定意图 ****`judge_intent_by_tool`**：组装 `{question, metadata:{label, params:{fileId}}, chatId}` 调 `INSIGHT_MCP_TOOL_FETCH_INTENT`（MCP 服务端做真实意图识别），结果必须在 `_valid_intent` 白名单内；`"other"` 直接返回默认话术，否则构造 `FunctionCall(name="transfer_to_agent", args={agent_name})` 让 ADK **函数调用触发 agent 转移**。
> 
> 

### **9\.8 MCP 客户端 mcp\_tools\.py**

**`agent/utils/mcp_tools.py`**（172 行，FULL）：

```Python
import json
import time
import traceback
from datetime import timedelta
from typing import Any, Optional

from google.adk.agents.callback_context import CallbackContext
from google.adk.tools.mcp_tool import MCPToolset
from google.adk.tools.mcp_tool.mcp_session_manager import SseConnectionParams
from mcp import ClientSession
from mcp.client.sse import sse_client

from common.constants import MCP_TOOL_DEFAULT_CONNECT_TIMEOUT, MCP_TOOL_DEFAULT_SSE_READ_TIMEOUT, MCP_TOOL_RESET_TIME
from settings import INSIGHT_MCP_SERVER_URL
from utils.log_manager import logger
from utils.utils import generate_unique_id


class MCPClientTools:
    def __init__(
        self,
        url: str,
        headers: Optional[dict[str, Any]] = None,
        timeout=MCP_TOOL_DEFAULT_CONNECT_TIMEOUT,
        sse_read_timeout=MCP_TOOL_DEFAULT_SSE_READ_TIMEOUT,
    ):
        self.headers = headers
        self.url = url
        self.timeout = timeout
        self.sse_read_timeout = sse_read_timeout
        self.tools: dict[str, Any] = {}

    async def _update_tool_info(self):
        try:
            tools = await self.tool_set.get_tools()
            cache_time = time.time()
            for tool in tools:
                tool.cache_time = cache_time
                self.tools[tool.name] = tool
            logger.info(f"update mcp tool info success, cache tool names: {self.tools.keys()}")
        except Exception as e:
            logger.error(f"update mcp tool info with url[{self.url} and headers[{self.headers}] failed, error={e!s}")

    async def _connect(self):
        headers = {"X-Mcp-Transfer-Cookie": generate_unique_id()}
        if self.headers and isinstance(self.headers, dict):
            headers.update(self.headers)

        self.tool_set = MCPToolset(
            connection_params=SseConnectionParams(
                url=self.url,
                headers=headers,
                timeout=self.timeout,
                sse_read_timeout=self.sse_read_timeout,
            )
        )
        self.tools = {}
        await self._update_tool_info()

    async def execute(self, tool_name: str, args: dict[str, Any], tool_context: CallbackContext, ret_dict=False):
        ret = {}
        try:
            # get tool in memory
            logger.info(f"starting to call mcp tool[{tool_name}], {args=}")
            if not tool_name:
                return ret
            start_time = time.time()
            await self._connect()
            tool = self.tools.get(tool_name)

            # update tool info
            if not self.tools or not tool or (tool.cache_time and start_time - tool.cache_time > MCP_TOOL_RESET_TIME):
                await self._update_tool_info()
                tool = self.tools.get(tool_name)
                if not tool:
                    logger.error(f"can not find tool[{tool_name}]")
                    return ret

            # execute mcp tool
            connected_time = time.time()
            logger.info(f"load mcp tool[{tool_name}] - cost_time: {connected_time - start_time:.4f}")
            ret = await tool.run_async(args=args, tool_context=tool_context)
            logger.info(f"run mcp tool[{tool_name}] got ret[{ret}] - cost_time: {connected_time - start_time:.4f}")
            if ret_dict:
                if ret and ret.content:
                    ret = ret.content[0].text
                    return json.loads(ret)
        except Exception as e:
            logger.error(f"call mcp tool[{tool_name}] failed, {args=}, {e=}")
        finally:
            await self.close()
        return ret

    async def close(self):
        """
        stop callback by main
        """
        if self.tool_set:
            await self.tool_set.close()


class MCPClientToolsNew:
    def __init__(
        self,
        url: str,
        headers: Optional[dict[str, Any]] = None,
        timeout=MCP_TOOL_DEFAULT_CONNECT_TIMEOUT,
        sse_read_timeout=MCP_TOOL_DEFAULT_SSE_READ_TIMEOUT,
    ):
        self.url = url
        self.headers = headers or {}
        self.timeout = timeout
        self.sse_read_timeout = sse_read_timeout
        self.tools: dict[str, Any] = {}

    async def execute_retry(
        self, tool_name: str, args: dict[str, Any], ret_dict=False, read_timeout_seconds=None, **kwargs
    ):
        headers = {"X-Mcp-Transfer-Cookie": generate_unique_id()}
        self.headers.update(headers)

        start_time = time.time()
        if not read_timeout_seconds:
            read_timeout_seconds = self.sse_read_timeout
        async with sse_client(
            url=self.url, headers=self.headers, timeout=self.timeout, sse_read_timeout=self.sse_read_timeout
        ) as (read, write):
            # 创建 ClientSession 对象
            logger.info(f"mcp-sse_client[{tool_name}, {args}, {self.url}] - cost_time: {time.time() - start_time:.4f}")
            async with ClientSession(read, write) as session:
                # 初始化 ClientSession
                await session.initialize()

                # 调用工具
                logger.info(f"mcp-call_tool[{tool_name}] - start--")
                ret = await session.call_tool(
                    tool_name, args, read_timeout_seconds=timedelta(seconds=read_timeout_seconds)
                )
                logger.info(f"mcp-call_tool[{tool_name}] got ret[{ret}] - cost_time: {time.time() - start_time:.4f}")

                if ret_dict:
                    if ret and ret.content:
                        ret = ret.content[0].text
                        try:
                            return json.loads(ret)
                        except Exception:
                            logger.error(f"mcp-call_tool[{tool_name}] got ret not json!")
                            return {}
                return ret

    async def execute(
        self, tool_name: str, args: dict[str, Any], ret_dict=False, retry=2, read_timeout_seconds=None, **kwargs
    ):
        for i in range(retry):
            try:
                res = await self.execute_retry(
                    tool_name=tool_name,
                    args=args,
                    ret_dict=ret_dict,
                    read_timeout_seconds=read_timeout_seconds,
                    **kwargs,
                )
                return res
            except Exception as e:
                logger.error(
                    f"mcp-call_tool[{tool_name}, {e}, {args}] 异常: {traceback.format_exc()} , 第:{i + 1}次重试--"
                )

        raise Exception("MCP服务异常!")


insightMCPTools = MCPClientToolsNew(url=INSIGHT_MCP_SERVER_URL)
```

> **面试要点**：
> 
> 1. **两套实现**：`MCPClientTools` 走 ADK 官方 `MCPToolset`（`SseConnectionParams` \+ 工具缓存）；`MCPClientToolsNew` 走 **原生 ****`sse_client`**** \+ ****`ClientSession`**（mcp 协议客户端库），绕开 ADK 封装、可控性更强——**实际使用的是 ****`MCPClientToolsNew`**（`insightMCPTools` 单例）。
> 
> 2. **SSE 每次全新连接**：`execute_retry` 每次 `async with sse_client(...)` 建立新连接（`X-Mcp-Transfer-Cookie` 用 `generate_unique_id()` 防串会话），`session.initialize()` 握手后 `call_tool`，读超时用 `timedelta(seconds=read_timeout_seconds)`（可传 `read_timeout_seconds=30` 控制）。
> 
> 3. **重试机制**：`execute(retry=2)` 默认重试 2 次，全部失败抛 `Exception("MCP服务异常!")`；`ret_dict=True` 时把返回 `content[0].text` 用 `json.loads` 解析成 dict。
> 
> 4. **工具缓存刷新**（旧实现）：`MCPClientTools` 给每个 tool 打 `cache_time`，超过 `MCP_TOOL_RESET_TIME` 或找不到时 `_update_tool_info` 重新拉取工具列表。
> 
> 

### **9\.9 事件包装 event\_packer\.py**

**`agent/utils/event_packer.py`**（438 行，FULL）：

```Python
"""
对Event返回内容进行包装
"""

import json
from copy import deepcopy
from typing import Optional

from google.adk.agents.llm_agent import Event
from google.genai.types import Content, Part

from common.constants import (
    AGENT_RUN_DATA_AGENT,
    FRONTEND_KEY_DATA,
    FRONTEND_KEY_DONOT_RENDER,
    FRONTEND_KEY_PARAMS,
    FRONTEND_VALUE_TYPE_OBJECT,
    FRONTEND_VALUE_TYPE_STRING,
)
from common.msg import MSG_NOTIFICATION_RETRY
from common.state_key import TEMP_METADATA_FIELDS, TEMP_TABLE_FIELDS
from utils.log_manager import logger

WRONG_ANSWER_START_TEXTS = [
    "transfer_to_agent",
    "<tool_call>",
    "For context",
]


def _post_process_text(text: str):
    if not text:
        return ""
    if not isinstance(text, str):
        return text

    # trick代码
    # 现在adk某些自己的调度事件也可能会直接返回出去，扔给前端之前，要先包装下这些内容
    # 此处将最终返回改为提示词：请再试一次
    text = text.strip()
    for start_text in WRONG_ANSWER_START_TEXTS:
        if text.startswith(start_text):
            return MSG_NOTIFICATION_RETRY
    return text


def _pack_json_text(ori_text, value_type):
    results = [
        {
            "key": FRONTEND_KEY_DATA,
            "value": ori_text,
            "valueType": value_type,
        }
    ]

    return json.dumps(results, ensure_ascii=False)


def decision_agent_repack(event: Event):
    """
    让decision agent的输出结果当中给出分析思路
    为上线的方案，应该有更好的办法才对……
    """
    ori_text = event.content.parts[0].text
    obj = json.loads(ori_text)
    packed_text_obj = [
        {
            "key": FRONTEND_KEY_DATA,
            "value": obj.get("description", ""),
            "valueType": FRONTEND_VALUE_TYPE_STRING,
        },
        {
            "key": FRONTEND_KEY_DATA,
            "value": "正在匹配可参考的策略",
            "valueType": FRONTEND_VALUE_TYPE_STRING,
        },
    ]
    event.content.parts[0].text = json.dumps(packed_text_obj, ensure_ascii=False)
    return True, event


def append_fake_data_analysis_notice(packed_text_obj: list, strategy_id: str):
    """
    想要抛出来event不太行，所以strategy agent出结果的时候，多点“思考过程”吧...
    """
    data_analysis_notice = [
        {
            "key": FRONTEND_KEY_DONOT_RENDER,
            "value": {"strategy_id": strategy_id},
            "valueType": FRONTEND_VALUE_TYPE_OBJECT,
        },
        {
            "key": FRONTEND_KEY_DATA,
            "value": "已经完成数据的二次加工",
            "valueType": FRONTEND_VALUE_TYPE_STRING,
        },
        {
            "key": FRONTEND_KEY_DATA,
            "value": "正在渲染结果",
            "valueType": FRONTEND_VALUE_TYPE_STRING,
        },
    ]
    packed_text_obj.extend(data_analysis_notice)


def strategy_create_agent_repack(event: Event):
    ori_text = event.content.parts[0].text
    obj = json.loads(ori_text)
    packed_text_obj = [
        {
            "key": FRONTEND_KEY_DATA,
            "value": (
                f"未匹配到可参考策略，准备使用以下思路进行分析：\n {obj.get('strategy_thinking')} \n\n {obj.get('strategy_output')} "
            ),
            "valueType": FRONTEND_VALUE_TYPE_STRING,
        },
        {
            "key": FRONTEND_KEY_DATA,
            "value": "已经获取到需要洞察的数据",
            "valueType": FRONTEND_VALUE_TYPE_STRING,
        },
    ]

    append_fake_data_analysis_notice(packed_text_obj, obj.get("strategy_id", ""))
    event.content.parts[0].text = json.dumps(packed_text_obj, ensure_ascii=False)
    return True, event


def analysis_agent_repack(event: Event):
    """
    只是把工具调用结果包一下
    """
    func_resp = event.content.parts[0].function_response
    if not func_resp:
        return False, "not analysis agent function response"
    if func_resp.name != "get_analysis_detail_tool":
        return False, "not analysis agent get_analysis_detail_tool function response"

    packed_text_obj = [
        {
            "key": FRONTEND_KEY_DATA,
            "value": "已经完成数据的二次加工",
            "valueType": FRONTEND_VALUE_TYPE_STRING,
        },
        {
            "key": FRONTEND_KEY_DATA,
            "value": "正在渲染结果",
            "valueType": FRONTEND_VALUE_TYPE_STRING,
        },
    ]

    event.content.parts[0].text = json.dumps(packed_text_obj, ensure_ascii=False)
    return True, event


def check_table_fields(optional_fields, table_fields):
    if optional_fields.get("fieldName") and optional_fields.get("fieldName") not in [
        x.get("fieldName") for x in table_fields
    ]:
        return False
    return True


def run_data_agent_repack(event: Event, obj: dict):
    if not event.custom_metadata:
        event.custom_metadata = {}

    if obj.get("tableFields"):
        event.custom_metadata["label"] = "RUN_FIELD_CONFIRM"
    else:
        event.custom_metadata["label"] = "RUN_CREATE_CONFIRM"

    if event.custom_metadata.get("listValue"):
        for index, item in enumerate(obj.get("tableFields", [])):
            item["listValueIndex"] = event.custom_metadata["listValue"][index]

    # 后处理tableFields
    if obj.get("tableFields"):
        field_name_map = {x["fieldName"]: x for x in event.custom_metadata.get(TEMP_TABLE_FIELDS, [])}
        for item in obj.get("tableFields"):
            item["optionalFields"] = [x for x in item["optionalFields"] if x["fieldName"] in field_name_map]
            for op_fields in item["optionalFields"]:
                if (
                    isinstance(op_fields["parseFieldValue"], list)
                    and field_name_map.get(op_fields["fieldName"], list)
                    and op_fields.get("parseFieldType", "") != "string"
                ):
                    # 枚举值兜底
                    if (
                        op_fields.get("parseFieldType", "") == "enum"
                        and op_fields["parseFieldValue"]
                        and isinstance(op_fields["parseFieldValue"][0], str)
                    ):
                        op_fields["parseFieldValue"] = [{"fieldValue": x} for x in op_fields["parseFieldValue"]]

                    selected_field = [x.get("fieldValue") for x in op_fields["parseFieldValue"]]

                    op_fields["parseFieldValue"] = [
                        {"fieldValue": x}
                        for x in field_name_map.get(op_fields["fieldName"], {}).get("parseFieldValue", [])
                    ]
                    for v in op_fields["parseFieldValue"]:
                        if v["fieldValue"] in selected_field:
                            v["selected"] = True
                        else:
                            v["selected"] = False
        obj["tableFields"] = [x for x in obj["tableFields"] if x.get("optionalFields") or x.get("listValue")]

    # 后处理metadataFields
    if obj.get("metadataFields"):
        field_id_map = {x["fieldId"]: x for x in event.custom_metadata.get(TEMP_METADATA_FIELDS, [])}
        obj["metadataFields"] = [x for x in obj["metadataFields"] if x.get("fieldId") and x["fieldId"] in field_id_map]
        for item in obj.get("metadataFields"):
            selected_ids = [x.get("fieldValueId") for x in item["fieldValues"]]

            item["fieldValues"] = deepcopy(field_id_map.get(item["fieldId"]).get("fieldValues"))
            for v in item["fieldValues"]:
                if v["fieldValueId"] in selected_ids:
                    v["selected"] = True
                else:
                    v["selected"] = False

        obj["metadataFields"] = [x for x in obj["metadataFields"] if x.get("fieldValues")]

    for key in ["listValue", TEMP_METADATA_FIELDS, TEMP_TABLE_FIELDS]:
        if key in event.custom_metadata:
            event.custom_metadata.pop(key)

    # 填充文件数据到返回结构中--
    if obj.get("fileId"):
        # 文件未匹配上按通用label话术返回
        if not obj.get("tableFields"):
            event.custom_metadata["label"] = "MUIL_AGENT_ANSWE"
            event.content.parts[0].text = _pack_json_text("未找到匹配字段", FRONTEND_VALUE_TYPE_STRING)
            return
        else:
            obj["fileAnalysisContent"] = ",".join([x.get("extractedFieldName", "") for x in obj["tableFields"]])

    logger.info(f"{AGENT_RUN_DATA_AGENT}-event_detail: {event=}")
    event.content.parts[0].text = _pack_json_text(obj, FRONTEND_VALUE_TYPE_OBJECT)


def default_repack(event: Event):
    ori_text = event.content.parts[0].text
    # if not ori_text:
    # return _all_in_text(event)

    is_text_obj = False

    try:
        obj = json.loads(ori_text)
        # 如果obj是一个list的话，说明是未经过模型自己扔出来的内容
        if isinstance(obj, list):
            return event

        is_text_obj = True
    except Exception as _:
        pass

    value_type = "string"
    # valueType有其他类型，如果agent内部直接修改event会有问题，所以在custom_metadata['valueType']中指定
    if event.custom_metadata and event.custom_metadata.get("valueType"):
        value_type = event.custom_metadata.get("valueType")

    if is_text_obj:
        if event.author == AGENT_RUN_DATA_AGENT:
            run_data_agent_repack(event=event, obj=obj)
        else:
            # 如果中途输出的内容，已经是json对象，则使用Markdown格式包起来
            # final_text = f"""{event.author}输出结果为\n```json\n{ori_text}\n```"""
            # 此种格式数据暂不向前端展示，故直接让外层忽略
            event.content.parts[0].text = ""
    else:
        final_text = _post_process_text(ori_text)
        event.content.parts[0].text = _pack_json_text(final_text, value_type)

    logger.info(f"repack_resp_parts event_detail: {event.author=}, {event.content.parts=}")
    return event


def _is_event_have_parts(event: Event):
    if not event.content:
        return False
    if not event.content.parts:
        return False
    if len(event.content.parts) < 1:
        return False

    return True


def repack_resp_parts(ori_event: Event):
    """
    将Event的Text重新包装一下
    """
    event = deepcopy(ori_event)

    # if event is not Event instance, do nothing
    if not isinstance(event, Event):
        return event

    # content and parts can be None
    if not _is_event_have_parts(event):
        return event

    funcs = {
        "decision_agent": decision_agent_repack,
        "strategy_create_agent": strategy_create_agent_repack,
        "analysis_agent": analysis_agent_repack,
    }
    try:
        func = funcs.get(event.author)
        if func:
            ret, new_event = func(event)
            if ret:
                return new_event
            else:
                logger.info(f"no need custom repack branch, {new_event}")
    except Exception as e:
        logger.error(f"repack_resp_parts failed, {e=}, {event}")

    if not event.content.parts[0].text:
        return event

    return default_repack(event)


def _all_in_text(event: Event):
    """
    思考过程中，将调度转移全都包在text里面
    """
    func_call = event.content.parts[0].function_call
    if func_call:
        text = f"""调用函数{func_call.name}，参数为\n```json\n{func_call.args}\n```"""
        event.content.parts[0].text = _pack_json_text(text, "string")
        return event

    func_resp = event.content.parts[0].function_response
    if func_resp:
        text = f"""函数{func_resp.name}执行成功。分析结果为：\n```json\n{func_resp.response}\n```"""
        event.content.parts[0].text = _pack_json_text(text, "string")
        return event

    # 目前只有function_call和function_response
    return event


def _append_params_to_fe(ori_list: list, params: Optional[dict] = None):
    """
    前端要求将发上来的params都返回去
    """
    if not params:
        return

    if not isinstance(ori_list, list):
        return

    ori_list.append(
        {
            "key": FRONTEND_KEY_PARAMS,
            "value": params,
            "valueType": FRONTEND_VALUE_TYPE_OBJECT,
        }
    )


def append_params_to_first_event(event: Event, params: Optional[dict] = None):
    try:
        ori_text = event.content.parts[0].text
        result = json.loads(ori_text)
        _append_params_to_fe(result, params)
        event.content.parts[0].text = json.dumps(result, ensure_ascii=False)
    except Exception as e:
        logger.error(f"append_params_to_first_event failed, {e}")


def check_final_event(event: Event):
    """
    目前Java测只支持Part为Text类型且经过上面repack_resp_parts包装过的事件
    此处对非Text类型的最后一个Event做兜底提示，还需要考虑content不存在情况
    Think:
        最好的改法，应该是使用ADK的格式，前端进行适配比较合适
        不过按照目前需求来看，最后一个Event应该是Text格式才合理？
    """
    if not _is_event_have_parts(event):
        logger.info("stream final event donot have any content")
        fake_content = Content(parts=[Part(text="")])
        event.content = fake_content

    ori_text = event.content.parts[0].text
    if not ori_text:
        event.content.parts[0].text = _pack_json_text(MSG_NOTIFICATION_RETRY, "string")
    add_custom_metadata(event, "is_runner_final", True)


def create_fake_final_event():
    text = _pack_json_text(MSG_NOTIFICATION_RETRY, "string")
    return Event(
        id=Event.new_id(),
        author="system",
        content=Content(role="model", parts=[Part(text=text)]),
    )


def add_custom_metadata(event: Event, key: str, default_value: str, *, force_overwrite: bool = False):
    """
    往event的custom_metadata中添加字段

    参数:
        event: 事件对象
        key: 需要确保存在的键名
        default_value: 当键不存在时的默认值
        force_overwrite: 强制覆盖现有值（默认False）
    """
    # 确保 custom_metadata 属性存在
    if event.custom_metadata is None:
        event.custom_metadata = {}

    # 设置键值逻辑
    if force_overwrite:
        event.custom_metadata[key] = default_value
    elif key not in event.custom_metadata:
        event.custom_metadata[key] = default_value


def add_multi_agent_label_to_event(event: Event):
    add_custom_metadata(event, "label", "MUIL_AGENT_ANSWE")


def pack_exception(_: Exception):
    # msg = f"系统发生异常，请稍后重试或者联系系统管理员。异常详情：{e}"
    event = Event(
        id=Event.new_id(),
        author="system",
        content=Content(role="model", parts=[Part(text=MSG_NOTIFICATION_RETRY)]),
        custom_metadata={"label": "MUIL_AGENT_ANSWE"},
    )
    return repack_resp_parts(event)
```

> **面试要点**：
> 
> 1. **统一前端协议**：核心包装函数 `_pack_json_text` 把所有事件文本包成 `[{"key":"data","value":...,"valueType":"string|object"}]` 的 JSON 字符串——**前端只认 ****`key/value/valueType`**** 三元组**，因此无论 agent 输出什么结构，都要在此层归一化。
> 
> 2. **按 agent 分派**：`repack_resp_parts` 先 `deepcopy` 事件（不污染原对象），按 `event.author` 查 `funcs` 分发——`decision_agent`（给分析思路\+匹配策略提示）、`strategy_create_agent`（把 `strategy_thinking/strategy_output` 展开，附 `FRONTEND_KEY_DONOT_RENDER` 的 `strategy_id` 假通知）、`analysis_agent`（包工具结果）；都不命中走 `default_repack`。
> 
> 3. **run\_data 特化 ****`run_data_agent_repack`**：按有无 `tableFields` 打 `RUN_FIELD_CONFIRM` / `RUN_CREATE_CONFIRM` label；用 `TEMP_TABLE_FIELDS`（意向阶段暂存的字段）做 `optionalFields` 过滤与 `selected` 回填（枚举值兜底 `{"fieldValue": x}`），用 `TEMP_METADATA_FIELDS` 做 `metadataFields` 过滤；清理 `listValue` 等临时 state；有 `fileId` 且无字段时降级为 `MUIL_AGENT_ANSWE` 通用话术。
> 
> 4. **脏文本兜底**：`_post_process_text` 对以 `"transfer_to_agent"`、`"<tool_call>"`、`"For context"` 开头的 ADK 内部调度事件，替换为 `MSG_NOTIFICATION_RETRY`（"请再试一次"），**防止调度信息泄露给用户**。
> 
> 5. **收尾保障**：`check_final_event` 对无 content / 空文本的最后一个事件补假 `Content` 并写"请再试一次"；`create_fake_final_event` 生成系统兜底事件；`pack_exception` 把异常包成 `MUIL_AGENT_ANSWE` 事件——三者共同保证 **SSE 流一定以合法、可渲染的事件收尾**，与 ADKExecutor 里的 `is_runner_final` 标记呼应。
> 
> 

## **§10 GraphRAG 知识图谱**

### **10\.1 模块概览与核心文件清单**

GraphRAG 是本项目的**知识图谱增强检索**实现，核心思想：先抽取文档中的**实体 / 关系**构建无向图，再用 **Leiden 社区发现算法**把图切成多层社区，对每个社区让 LLM 生成**社区报告 \(community report\)**，检索时对社区报告做 **Map 式全局搜索 \(global search\)** 汇总支撑点。整体逻辑移植自微软 `microsoft/graphrag`，但做了并行化与存储定制（networkx \+ json KV）。

```Plain Text
src/apps/graphrag/
├── graphrag.py                 —— GraphRAG 核心算法（实体/关系抽取、社区报告、Map 搜索）784 行
├── prompt.py                   —— 全部 Prompt（从 microsoft/graphrag 移植）
├── utils.py                    —— 工具函数（CSV 序列化、tiktoken 截断、JSON 修复）
├── api.py                      —— Flask 接口
└── storage/
    ├── networkx_storage.py     —— NetworkX 图存储 + Leiden 聚类 239 行
    └── json_kv_storage.py      —— JSON KV 存储（社区报告持久化）
```

GraphRAG 处理流程：

```Plain Text
文档分块 (chunks)
   ↓ ThreadPoolExecutor 并行
实体/关系抽取 (LLM 逐块抽取 + gleaning 补充抽取)
   ↓ 合并去重（同名实体合并、同边关系合并、权重累加）
知识图谱 (NetworkX 无向图)
   ↓ Leiden 层次聚类（层级社区）
社区报告生成 (LLM 逐社区生成 JSON 报告，底层社区优先)
   ↓ 检索阶段
Global Map 搜索（按 token 分组 → 并行打分 → 汇总支撑点）
```

### **10\.2 graphrag\.py 完整源码**

```Python
# ===== src/apps/graphrag/graphrag.py =====
from collections import defaultdict, Counter
from concurrent.futures import ThreadPoolExecutor
import uuid
import re

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from src.component.server_component.LogManager import logger
from src.maip.chat_models import MaipChatModel
from .prompt import PROMPTS, GRAPH_FIELD_SEP
from .utils import (
    split_string_by_multi_markers,
    clean_str,
    is_float_regex,
    encode_string_by_tiktoken,
    decode_tokens_by_tiktoken,
    list_of_list_to_csv,
    convert_response_to_json,
    pack_user_ass_to_openai_messages,
    truncate_list_by_token_size,
)
from .storage.networkx_storage import NetworkXStorage
from .storage.json_kv_storage import JsonKVStorage
from src import settings


def _clean_field(value):
    if not isinstance(value, str):
        return value
    return value.strip('"')


def _handle_single_entity_extraction(
    record_attributes: list[str],
    chunk_key: str,
):
    if len(record_attributes) < 4 or record_attributes[0] != '"entity"':
        return None
    # add this record as a node in the G
    entity_name = clean_str(record_attributes[1].upper())
    if not entity_name.strip():
        return None
    entity_type = clean_str(record_attributes[2].upper())
    entity_description = clean_str(record_attributes[3])
    entity_source_id = chunk_key
    return dict(
        entity_name=entity_name,
        entity_type=entity_type,
        description=entity_description,
        source_id=entity_source_id,
    )


def _handle_single_relationship_extraction(
    record_attributes: list[str],
    chunk_key: str,
):
    if len(record_attributes) < 5 or record_attributes[0] != '"relationship"':
        return None
    # add this record as edge
    source = clean_str(record_attributes[1].upper())
    target = clean_str(record_attributes[2].upper())
    edge_description = clean_str(record_attributes[3])
    edge_source_id = chunk_key
    weight = (
        float(record_attributes[-1]) if is_float_regex(record_attributes[-1]) else 1.0
    )
    return dict(
        src_id=source,
        tgt_id=target,
        weight=weight,
        description=edge_description,
        source_id=edge_source_id,
    )


def _handle_entity_relation_summary(
    entity_or_relation_name: str,
    description: str,
    llm: MaipChatModel,
) -> str:
    llm_max_tokens = llm.max_tokens  # default value
    summary_max_tokens = llm.max_tokens  # dafault value

    tokens = encode_string_by_tiktoken(description)
    if len(tokens) < summary_max_tokens:  # No need for summary
        return description
    prompt_template = PROMPTS["summarize_entity_descriptions"]
    use_description = decode_tokens_by_tiktoken(tokens[:llm_max_tokens])
    context_base = dict(
        entity_name=entity_or_relation_name,
        description_list=use_description.split(GRAPH_FIELD_SEP),
    )
    use_prompt = prompt_template.format(**context_base)
    logger.debug(f"Trigger summary: {entity_or_relation_name}")
    summary = llm.invoke(use_prompt, max_tokens=summary_max_tokens).content
    return summary


def _merge_nodes_then_upsert(
    entity_name: str,
    nodes_data: list[dict],
    knwoledge_graph_inst,
    llm,
):
    already_entitiy_types = []
    already_source_ids = []
    already_description = []

    already_node = knwoledge_graph_inst.get_node(entity_name)
    if already_node is not None:
        already_entitiy_types.append(already_node["entity_type"])
        already_source_ids.extend(
            split_string_by_multi_markers(already_node["source_id"], [GRAPH_FIELD_SEP])
        )
        already_description.append(already_node["description"])

    entity_type = sorted(
        Counter(
            [dp["entity_type"] for dp in nodes_data] + already_entitiy_types
        ).items(),
        key=lambda x: x[1],
        reverse=True,
    )[0][0]
    description = GRAPH_FIELD_SEP.join(
        sorted(set([dp["description"] for dp in nodes_data] + already_description))
    )
    source_id = GRAPH_FIELD_SEP.join(
        set([dp["source_id"] for dp in nodes_data] + already_source_ids)
    )
    description = _handle_entity_relation_summary(entity_name, description, llm)
    node_data = dict(
        entity_type=entity_type,
        description=description,
        source_id=source_id,
    )
    knwoledge_graph_inst.upsert_node(
        entity_name,
        node_data=node_data,
    )
    node_data["entity_name"] = entity_name
    return node_data


def _merge_edges_then_upsert(
    src_id: str,
    tgt_id: str,
    edges_data: list[dict],
    knwoledge_graph_inst: NetworkXStorage,
    llm,
):
    already_weights = []
    already_source_ids = []
    already_description = []
    already_order = []
    if knwoledge_graph_inst.has_edge(src_id, tgt_id):
        already_edge = knwoledge_graph_inst.get_edge(src_id, tgt_id)
        already_weights.append(already_edge["weight"])
        already_source_ids.extend(
            split_string_by_multi_markers(already_edge["source_id"], [GRAPH_FIELD_SEP])
        )
        already_description.append(already_edge["description"])
        already_order.append(already_edge.get("order", 1))

    # [numberchiffre]: `Relationship.order` is only returned from DSPy's predictions
    order = min([dp.get("order", 1) for dp in edges_data] + already_order)
    weight = sum([dp["weight"] for dp in edges_data] + already_weights)
    description = GRAPH_FIELD_SEP.join(
        sorted(set([dp["description"] for dp in edges_data] + already_description))
    )
    source_id = GRAPH_FIELD_SEP.join(
        set([dp["source_id"] for dp in edges_data] + already_source_ids)
    )
    for need_insert_id in [src_id, tgt_id]:
        if not (knwoledge_graph_inst.has_node(need_insert_id)):
            knwoledge_graph_inst.upsert_node(
                need_insert_id,
                node_data={
                    "source_id": source_id,
                    "description": description,
                    "entity_type": '"UNKNOWN"',
                },
            )
    description = _handle_entity_relation_summary((src_id, tgt_id), description, llm)
    knwoledge_graph_inst.upsert_edge(
        src_id,
        tgt_id,
        edge_data=dict(
            weight=weight, description=description, source_id=source_id, order=order
        ),
    )


def extract_entities(chunks, entities_type, llm, task_id=None):
    knwoledge_graph_inst = NetworkXStorage()

    ordered_chunks = chunks

    entity_extract_prompt = PROMPTS["entity_extraction"]
    context_base = dict(
        tuple_delimiter=PROMPTS["DEFAULT_TUPLE_DELIMITER"],
        record_delimiter=PROMPTS["DEFAULT_RECORD_DELIMITER"],
        completion_delimiter=PROMPTS["DEFAULT_COMPLETION_DELIMITER"],
        # entity_types=",".join(PROMPTS["DEFAULT_ENTITY_TYPES"]),
        entity_types=",".join(entities_type or PROMPTS["DEFAULT_ENTITY_TYPES"]),
    )
    continue_prompt = PROMPTS["entiti_continue_extraction"]
    if_loop_prompt = PROMPTS["entiti_if_loop_extraction"]
    entity_extract_max_gleaning = 1

    already_processed = 0
    already_entities = 0
    already_relations = 0

    def _process_single_content(chunk_key_dp):
        nonlocal already_processed, already_entities, already_relations
        chunk_key = chunk_key_dp["id_"].replace("-", "")
        chunk_dp = chunk_key_dp
        content = chunk_dp["text"]
        hint_prompt = entity_extract_prompt.format(**context_base, input_text=content)
        final_result = llm.invoke(hint_prompt).content

        history = pack_user_ass_to_openai_messages(hint_prompt, final_result)
        for now_glean_index in range(entity_extract_max_gleaning):
            glean_result = llm.invoke(
                [
                    *[
                        HumanMessage(content=h["content"])
                        if h["role"] == "user"
                        else AIMessage(content=h["content"])
                        for h in history
                    ],
                    HumanMessage(content=continue_prompt),
                ]
            ).content

            history += pack_user_ass_to_openai_messages(continue_prompt, glean_result)
            final_result += glean_result
            if now_glean_index == entity_extract_max_gleaning - 1:
                break

            if_loop_result: str = llm.invoke(
                [
                    *[
                        HumanMessage(content=h["content"])
                        if h["role"] == "user"
                        else AIMessage(content=h["content"])
                        for h in history
                    ],
                    HumanMessage(content=if_loop_prompt)
                ]
            ).content
            if_loop_result = if_loop_result.strip().strip('"').strip("'").lower()
            if if_loop_result != "yes":
                break

        records = split_string_by_multi_markers(
            final_result,
            [context_base["record_delimiter"], context_base["completion_delimiter"]],
        )

        maybe_nodes = defaultdict(list)
        maybe_edges = defaultdict(list)
        for record in records:
            record = re.search(r"\((.*)\)", record)
            if record is None:
                continue
            record = record.group(1)
            record_attributes = split_string_by_multi_markers(
                record, [context_base["tuple_delimiter"]]
            )
            if_entities = _handle_single_entity_extraction(record_attributes, chunk_key)
            if if_entities is not None:
                maybe_nodes[if_entities["entity_name"]].append(if_entities)
                continue

            if_relation = _handle_single_relationship_extraction(
                record_attributes, chunk_key
            )
            if if_relation is not None:
                maybe_edges[(if_relation["src_id"], if_relation["tgt_id"])].append(
                    if_relation
                )
        already_processed += 1
        already_entities += len(maybe_nodes)
        already_relations += len(maybe_edges)
        now_ticks = PROMPTS["process_tickers"][
            already_processed % len(PROMPTS["process_tickers"])
        ]
        logger.info(
            f"[graphrag]{task_id=} {now_ticks} Processed {already_processed}({already_processed*100//len(ordered_chunks)}%) chunks,  {already_entities} entities(duplicated), {already_relations} relations(duplicated)\r"
        )
        return dict(maybe_nodes), dict(maybe_edges)

    # use_llm_func is wrapped in ascynio.Semaphore, limiting max_async callings
    logger.info(f'[graphrag]{task_id=} _process_single_content, num: {len(ordered_chunks)}')
    with ThreadPoolExecutor(max_workers=settings.GRAPHRAG_PARALLEL_NUM) as executor:
        results = list(executor.map(_process_single_content, (c for c in ordered_chunks)))
    logger.info(f'[graphrag]{task_id=} _process_single_content done')
    # results = [_process_single_content(c) for c in ordered_chunks]
    maybe_nodes = defaultdict(list)
    maybe_edges = defaultdict(list)
    for m_nodes, m_edges in results:
        for k, v in m_nodes.items():
            maybe_nodes[k].extend(v)
        for k, v in m_edges.items():
            # it's undirected graph
            maybe_edges[tuple(sorted(k))].extend(v)
    all_entities_data = []
    logger.info(f'[graphrag]{task_id=} merge_nodes: {len(maybe_nodes)}')
    with ThreadPoolExecutor(max_workers=settings.GRAPHRAG_PARALLEL_NUM) as executor:
        for k, v in maybe_nodes.items():
            all_entities_data.append(executor.submit(_merge_nodes_then_upsert, k, v, knwoledge_graph_inst, llm))
    logger.info(f'[graphrag]{task_id=} merge nodes done')
    all_entities_data = [r.result() for r in all_entities_data]
    # all_entities_data = [
    #     _merge_nodes_then_upsert(k, v, knwoledge_graph_inst, llm)
    #     for k, v in maybe_nodes.items()
    # ]
    for k, v in maybe_edges.items():
        _merge_edges_then_upsert(k[0], k[1], v, knwoledge_graph_inst, llm)
    if not len(all_entities_data):
        logger.warning("Didn't extract any entities, maybe your LLM is not working")
        return None
    logger.info(f'[graphrag]{task_id=} extract entities: {len(all_entities_data)}')
    return knwoledge_graph_inst


def _pack_single_community_by_sub_communities(
    community,
    max_token_size,
    already_reports,
) -> tuple[str, int]:
    # TODO
    all_sub_communities = [
        already_reports[k] for k in community["sub_communities"] if k in already_reports
    ]
    all_sub_communities = sorted(
        all_sub_communities, key=lambda x: x["occurrence"], reverse=True
    )
    may_trun_all_sub_communities = truncate_list_by_token_size(
        all_sub_communities,
        key=lambda x: x["report_string"],
        max_token_size=max_token_size,
    )
    sub_fields = ["id", "report", "rating", "importance"]
    sub_communities_describe = list_of_list_to_csv(
        [sub_fields]
        + [
            [
                i,
                c["report_string"],
                c["report_json"].get("rating", -1),
                c["occurrence"],
            ]
            for i, c in enumerate(may_trun_all_sub_communities)
        ]
    )
    already_nodes = []
    already_edges = []
    for c in may_trun_all_sub_communities:
        already_nodes.extend(c["nodes"])
        already_edges.extend([tuple(e) for e in c["edges"]])
    return (
        sub_communities_describe,
        len(encode_string_by_tiktoken(sub_communities_describe)),
        set(already_nodes),
        set(already_edges),
    )


def _pack_single_community_describe(
    knwoledge_graph_inst,
    community,
    max_token_size: int = 4096,
    already_reports={},
    global_config={},
) -> str:
    nodes_in_order = sorted(community["nodes"])
    edges_in_order = sorted(community["edges"], key=lambda x: x[0] + x[1])

    nodes_data = [knwoledge_graph_inst.get_node(n) for n in nodes_in_order]
    edges_data = [
        knwoledge_graph_inst.get_edge(src, tgt) for src, tgt in edges_in_order
    ]
    node_fields = ["id", "entity", "type", "description", "degree"]
    edge_fields = ["id", "source", "target", "description", "rank"]
    nodes_list_data = [
        [
            i,
            node_name,
            node_data.get("entity_type", "UNKNOWN"),
            node_data.get("description", "UNKNOWN"),
            knwoledge_graph_inst.node_degree(node_name),
        ]
        for i, (node_name, node_data) in enumerate(zip(nodes_in_order, nodes_data))
    ]
    nodes_list_data = sorted(nodes_list_data, key=lambda x: x[-1], reverse=True)
    nodes_may_truncate_list_data = truncate_list_by_token_size(
        nodes_list_data, key=lambda x: x[3], max_token_size=max_token_size // 2
    )
    edges_list_data = [
        [
            i,
            edge_name[0],
            edge_name[1],
            edge_data.get("description", "UNKNOWN"),
            knwoledge_graph_inst.edge_degree(*edge_name),
        ]
        for i, (edge_name, edge_data) in enumerate(zip(edges_in_order, edges_data))
    ]
    edges_list_data = sorted(edges_list_data, key=lambda x: x[-1], reverse=True)
    edges_may_truncate_list_data = truncate_list_by_token_size(
        edges_list_data, key=lambda x: x[3], max_token_size=max_token_size // 2
    )

    truncated = len(nodes_list_data) > len(nodes_may_truncate_list_data) or len(
        edges_list_data
    ) > len(edges_may_truncate_list_data)

    # If context is exceed the limit and have sub-communities:
    report_describe = ""
    need_to_use_sub_communities = (
        truncated and len(community["sub_communities"]) and len(already_reports)
    )
    force_to_use_sub_communities = global_config["addon_params"].get(
        "force_to_use_sub_communities", False
    )
    if need_to_use_sub_communities or force_to_use_sub_communities:
        logger.debug(
            f"Community {community['title']} exceeds the limit or you set force_to_use_sub_communities to True, using its sub-communities"
        )
        report_describe, report_size, contain_nodes, contain_edges = (
            _pack_single_community_by_sub_communities(
                community, max_token_size, already_reports
            )
        )
        report_exclude_nodes_list_data = [
            n for n in nodes_list_data if n[1] not in contain_nodes
        ]
        report_include_nodes_list_data = [
            n for n in nodes_list_data if n[1] in contain_nodes
        ]
        report_exclude_edges_list_data = [
            e for e in edges_list_data if (e[1], e[2]) not in contain_edges
        ]
        report_include_edges_list_data = [
            e for e in edges_list_data if (e[1], e[2]) in contain_edges
        ]
        # if report size is bigger than max_token_size, nodes and edges are []
        nodes_may_truncate_list_data = truncate_list_by_token_size(
            report_exclude_nodes_list_data + report_include_nodes_list_data,
            key=lambda x: x[3],
            max_token_size=(max_token_size - report_size) // 2,
        )
        edges_may_truncate_list_data = truncate_list_by_token_size(
            report_exclude_edges_list_data + report_include_edges_list_data,
            key=lambda x: x[3],
            max_token_size=(max_token_size - report_size) // 2,
        )
    nodes_describe = list_of_list_to_csv([node_fields] + nodes_may_truncate_list_data)
    edges_describe = list_of_list_to_csv([edge_fields] + edges_may_truncate_list_data)
    return f"""-----Reports-----
```csv
{report_describe}
```

\-\-\-\-\-Entities\-\-\-\-\-

```Plain Text
{nodes_describe}
```

\-\-\-\-\-Relationships\-\-\-\-\-

```Plain Text
{edges_describe}
```"""


def _community_report_json_to_str(parsed_output: dict) -> str:
    """refer official graphrag: index/graph/extractors/community_reports"""
    title = parsed_output.get("title", "Report")
    summary = parsed_output.get("summary", "")
    findings = parsed_output.get("findings", [])

    def finding_summary(finding: dict):
        if isinstance(finding, str):
            return finding
        return finding.get("summary")

    def finding_explanation(finding: dict):
        if isinstance(finding, str):
            return ""
        return finding.get("explanation")

    report_sections = "\n\n".join(
        f"## {finding_summary(f)}\n\n{finding_explanation(f)}" for f in findings
    )
    return f"# {title}\n\n{summary}\n\n{report_sections}"


def generate_community_report(
    community_report_kv,
    knwoledge_graph_inst,
    llm,
    task_id=None,
):
    llm_extra_kwargs = {"response_format": {"type": "json_object"}}
    use_string_json_convert_func = convert_response_to_json

    community_report_prompt = PROMPTS["community_report"]

    communities_schema = knwoledge_graph_inst.community_schema()
    community_keys, community_values = (
        list(communities_schema.keys()),
        list(communities_schema.values()),
    )
    already_processed = 0
    global_config = defaultdict(dict)

    def _form_single_community_report(community, already_reports):
        nonlocal already_processed
        describe = _pack_single_community_describe(
            knwoledge_graph_inst,
            community,
            max_token_size=llm.max_tokens,
            already_reports=already_reports,
            global_config=global_config,
        )
        prompt = community_report_prompt.format(input_text=describe)
        response = llm.invoke(prompt, **llm_extra_kwargs).content

        data = use_string_json_convert_func(response)
        already_processed += 1
        now_ticks = PROMPTS["process_tickers"][
            already_processed % len(PROMPTS["process_tickers"])
        ]
        logger.info(
            f"[graphrag]{task_id=} {now_ticks} Processed {already_processed} communities\r"
        )
        return data

    levels = sorted(set([c["level"] for c in community_values]), reverse=True)
    logger.info(f"Generating by levels: {levels}")
    community_datas = {}
    for level in levels:
        this_level_community_keys, this_level_community_values = zip(
            *[
                (k, v)
                for k, v in zip(community_keys, community_values)
                if v["level"] == level
            ]
        )
        this_level_communities_reports = [
            _form_single_community_report(c, community_datas)
            for c in this_level_community_values
        ]
        community_datas.update(
            {
                k: {
                    "report_string": _community_report_json_to_str(r),
                    "report_json": r,
                    **v,
                }
                for k, r, v in zip(
                    this_level_community_keys,
                    this_level_communities_reports,
                    this_level_community_values,
                )
            }
        )
    community_report_kv.upsert(community_datas)


def get_community_full_content(report):
    title = report["report_json"]["title"]
    summary = report["report_json"]["summary"]
    findings = report["report_json"]["findings"]

    def find_summary(finding):
        if isinstance(finding, str):
            return finding
        return finding.get("summary")

    def find_explanation(finding):
        if isinstance(finding, str):
            return finding
        return finding.get("explanation")

    report_sections = "\n\n".join(
        f"## {find_summary(f)}\n\n{find_explanation(f)}" for f in findings
    )
    return f"# {title}\n\n{summary}\n\n{report_sections}"


def graphrag_extract(chunks, entities_type, llm, task_id=None):
    logger.info(f"【graphrag】extracting {task_id=} {entities_type=} chunks 数量: {len(chunks)}")
    entity_graph = extract_entities(chunks, entities_type, llm, task_id=task_id)
    if entity_graph is None:
        raise Exception("No new entities found")
    entity_graph.clustering("leiden")  # 目前只支持这个聚类算法
    # generate community report
    # entity_graph, community_report
    entities = []
    node_to_id = {}
    for node_name in entity_graph._graph.nodes:
        node = entity_graph._graph.nodes[node_name]
        entity = {
            "id": str(uuid.uuid4()).replace("-", ""),
            "name": _clean_field(node_name),
            "graph_type": "entity",
            "type": _clean_field(node["entity_type"]),
            "description": _clean_field(node["description"]),
            "chunk_ids": node["source_id"].split(GRAPH_FIELD_SEP),
            "rank": entity_graph.node_degree(node_name),
        }
        entities.append(entity)
        node_to_id[entity["name"]] = entity["id"]
    relations = []
    for edge in entity_graph._graph.edges:
        edge_data = entity_graph._graph.edges[edge]
        relation = {
            "id": str(uuid.uuid4()).replace("-", ""),
            "graph_type": "relationship",
            "source": _clean_field(edge[0]),
            "target": _clean_field(edge[1]),
            "description": _clean_field(edge_data["description"]),
            "weight": edge_data["weight"],
            "chunk_ids": edge_data["source_id"].split(GRAPH_FIELD_SEP),
        }
        relation["entity_ids"] = [node_to_id[relation['source']], node_to_id[relation['target']]]
        relations.append(relation)

    community_report = JsonKVStorage()
    generate_community_report(community_report, entity_graph, llm, task_id=task_id)
    reports = []
    node_to_community = defaultdict(set)
    for _, r in community_report._data.items():
        report_id = str(uuid.uuid4()).replace("-", "")
        report = {
            "id": report_id,
            "level": r["level"],
            "graph_type": "community_report",
            "title": _clean_field(r["report_json"]["title"]),
            "summary": _clean_field(r["report_json"]["summary"]),
            "rank": r["report_json"]["rating"],
            "rankExplanation": _clean_field(r["report_json"]["rating_explanation"]),
            "findings": r["report_json"]["findings"],
            "entity_ids": [node_to_id[_clean_field(n)] for n in r["nodes"]],
            "fullContent": get_community_full_content(r),
        }
        for n in r["nodes"]:
            node_to_community[node_to_id[_clean_field(n)]].add(report["id"])
        reports.append(report)

    # generate graph
    graph_data = {}
    graph_data["nodes"] = [
        {
            "id": n["id"],
            "name": n["name"],
            "type": n["type"],
            "description": n["description"],
            "communityReports": list(node_to_community[n["id"]]),
        }
        for n in entities
    ]
    graph_data["edges"] = [
        {
            "source": _clean_field(source),
            "target": _clean_field(target),
            "description": _clean_field(data["description"]),
            "weight": data["weight"],
            "nodes": [node_to_id[_clean_field(source)], node_to_id[_clean_field(target)]],
        }
        for source, target, data in entity_graph._graph.edges(data=True)
    ]
    graph_data['communityReports'] = [
        {
            'id': r['id'],
            'title': r['title'],
        }
        for r in reports
    ]
    # return entity_graph, community_report
    return {
        "entities": entities,
        "relations": relations,
        "reports": reports,
        "graph": graph_data,
        # 'graph_inst': entity_graph,
        # 'report_inst': community_report,
    }


def _map_global_communities(
    query: str,
    communities_data,
    llm,
    history,
):
    use_string_json_convert_func = convert_response_to_json
    community_groups = []
    while len(communities_data):
        this_group = truncate_list_by_token_size(
            communities_data,
            key=lambda x: x["fullContent"],
            max_token_size=llm.max_tokens
        )
        community_groups.append(this_group)
        communities_data = communities_data[len(this_group) :]

    history_context_header = (
        "-----Conversation History-----\n"
        + list_of_list_to_csv([["turn", "content"]])
        + "\n"
    )
    history_context_body = list_of_list_to_csv(
        [[h["type"], h["content"]] for h in (history or [])]
    )
    history_context = history_context_header + history_context_body + "\n\n"

    def _process(community_truncated_datas) -> dict:
        communities_section_list = [["id", "content", "rating", "importance"]]
        for i, c in enumerate(community_truncated_datas):
            communities_section_list.append(
                [
                    c['id'],
                    c["fullContent"],
                    c["rank"],
                    c.get("weight", 0.1),
                ]
            )
        community_context = "-----Reports-----\n" + list_of_list_to_csv(
            communities_section_list
        )
        sys_prompt_temp = PROMPTS["global_map_rag_points"]
        sys_prompt = sys_prompt_temp.format(
            context_data=history_context + community_context
            if history
            else community_context
        )
        messages = [
            SystemMessage(content=sys_prompt),
            HumanMessage(content=query),
        ]
        response = llm.invoke(messages).content
        data = use_string_json_convert_func(response)
        return data.get("points", [])

    logger.info(f"Grouping to {len(community_groups)} groups for global search")
    with ThreadPoolExecutor(max_workers=settings.GRAPHRAG_PARALLEL_NUM) as executor:
        responses = list(executor.map(_process, (c for c in community_groups)))
    # responses = [_process(c) for c in community_groups]
    return responses


def graphrag_map(query, community_reports, llm, history=None):
    community_datas = sorted(
        [r for r in community_reports if r["rank"] >= 0],
        key=lambda x: (x["weight"], x["rank"]),
        reverse=True,
    )
    map_communities_points = _map_global_communities(
        query, community_datas, llm, history
    )
    final_support_points = []
    for i, mc in enumerate(map_communities_points):
        for point in mc:
            if "description" not in point:
                continue
            final_support_points.append(
                {
                    "description": point["description"],
                    "score": point.get("score", 1),
                }
            )
    final_support_points = [p for p in final_support_points if p["score"] > 0]
    final_support_points = sorted(
        final_support_points, key=lambda x: x["score"], reverse=True
    )
    return final_support_points
```

### **10\.3 networkx\_storage\.py 完整源码（图存储与 Leiden 聚类）**

```Python
# ===== src/apps/graphrag/storage/networkx_storage.py =====
import html
import networkx as nx
import numpy as np

from collections import defaultdict
import json
import os
from typing import Any, Union, cast, TypedDict

from src.component.server_component.LogManager import logger
from ..prompt import GRAPH_FIELD_SEP


SingleCommunitySchema = TypedDict(
    "SingleCommunitySchema",
    {
        "level": int,
        "title": str,
        "edges": list[list[str, str]],
        "nodes": list[str],
        "chunk_ids": list[str],
        "occurrence": float,
        "sub_communities": list[str],
    },
)


class NetworkXStorage(object):
    def __init__(self):
        self._graph = nx.Graph()
        self._clustering_algorithms = {
            "leiden": self._leiden_clustering,
        }
        self._node_embed_algorithms = {
            "node2vec": self._node2vec_embed,
        }

    @staticmethod
    def load_nx_graph(file_name) -> nx.Graph:
        if os.path.exists(file_name):
            return nx.read_graphml(file_name)
        return None

    @staticmethod
    def write_nx_graph(graph: nx.Graph, file_name):
        logger.info(
            f"Writing graph with {graph.number_of_nodes()} nodes, {graph.number_of_edges()} edges"
        )
        nx.write_graphml(graph, file_name)

    @staticmethod
    def stable_largest_connected_component(graph: nx.Graph) -> nx.Graph:
        """Refer to https://github.com/microsoft/graphrag/index/graph/utils/stable_lcc.py
        Return the largest connected component of the graph, with nodes and edges sorted in a stable way.
        """
        from graspologic.utils import largest_connected_component

        graph = graph.copy()
        graph = cast(nx.Graph, largest_connected_component(graph))
        node_mapping = {node: html.unescape(node.upper().strip()) for node in graph.nodes()}  # type: ignore
        graph = nx.relabel_nodes(graph, node_mapping)
        return NetworkXStorage._stabilize_graph(graph)

    @staticmethod
    def _stabilize_graph(graph: nx.Graph) -> nx.Graph:
        """Refer to https://github.com/microsoft/graphrag/index/graph/utils/stable_lcc.py
        Ensure an undirected graph with the same relationships will always be read the same way.
        """
        fixed_graph = nx.DiGraph() if graph.is_directed() else nx.Graph()

        sorted_nodes = graph.nodes(data=True)
        sorted_nodes = sorted(sorted_nodes, key=lambda x: x[0])

        fixed_graph.add_nodes_from(sorted_nodes)
        edges = list(graph.edges(data=True))

        if not graph.is_directed():

            def _sort_source_target(edge):
                source, target, edge_data = edge
                if source > target:
                    temp = source
                    source = target
                    target = temp
                return source, target, edge_data

            edges = [_sort_source_target(edge) for edge in edges]

        def _get_edge_key(source: Any, target: Any) -> str:
            return f"{source} -> {target}"

        edges = sorted(edges, key=lambda x: _get_edge_key(x[0], x[1]))

        fixed_graph.add_edges_from(edges)
        return fixed_graph

    def index_done_callback(self):
        NetworkXStorage.write_nx_graph(self._graph, self._graphml_xml_file)

    def has_node(self, node_id: str) -> bool:
        return self._graph.has_node(node_id)

    def has_edge(self, source_node_id: str, target_node_id: str) -> bool:
        return self._graph.has_edge(source_node_id, target_node_id)

    def get_node(self, node_id: str) -> Union[dict, None]:
        return self._graph.nodes.get(node_id)

    def node_degree(self, node_id: str) -> int:
        # [numberchiffre]: node_id not part of graph returns `DegreeView({})` instead of 0
        return self._graph.degree(node_id) if self._graph.has_node(node_id) else 0

    def edge_degree(self, src_id: str, tgt_id: str) -> int:
        return (self._graph.degree(src_id) if self._graph.has_node(src_id) else 0) + (
            self._graph.degree(tgt_id) if self._graph.has_node(tgt_id) else 0
        )

    def get_edge(
        self, source_node_id: str, target_node_id: str
    ) -> Union[dict, None]:
        return self._graph.edges.get((source_node_id, target_node_id))

    def get_node_edges(self, source_node_id: str):
        if self._graph.has_node(source_node_id):
            return list(self._graph.edges(source_node_id))
        return None

    def upsert_node(self, node_id: str, node_data: dict[str, str]):
        self._graph.add_node(node_id, **node_data)

    def upsert_edge(
        self, source_node_id: str, target_node_id: str, edge_data: dict[str, str]
    ):
        self._graph.add_edge(source_node_id, target_node_id, **edge_data)

    def clustering(self, algorithm: str):
        if algorithm not in self._clustering_algorithms:
            raise ValueError(f"Clustering algorithm {algorithm} not supported")
        self._clustering_algorithms[algorithm]()

    def community_schema(self) -> dict[str, SingleCommunitySchema]:
        results = defaultdict(
            lambda: dict(
                level=None,
                title=None,
                edges=set(),
                nodes=set(),
                chunk_ids=set(),
                occurrence=0.0,
                sub_communities=[],
            )
        )
        max_num_ids = 0
        levels = defaultdict(set)
        for node_id, node_data in self._graph.nodes(data=True):
            if "clusters" not in node_data:
                continue
            clusters = json.loads(node_data["clusters"])
            this_node_edges = self._graph.edges(node_id)

            for cluster in clusters:
                level = cluster["level"]
                cluster_key = str(cluster["cluster"])
                levels[level].add(cluster_key)
                results[cluster_key]["level"] = level
                results[cluster_key]["title"] = f"Cluster {cluster_key}"
                results[cluster_key]["nodes"].add(node_id)
                results[cluster_key]["edges"].update(
                    [tuple(sorted(e)) for e in this_node_edges]
                )
                results[cluster_key]["chunk_ids"].update(
                    node_data["source_id"].split(GRAPH_FIELD_SEP)
                )
                max_num_ids = max(max_num_ids, len(results[cluster_key]["chunk_ids"]))

        ordered_levels = sorted(levels.keys())
        for i, curr_level in enumerate(ordered_levels[:-1]):
            next_level = ordered_levels[i + 1]
            this_level_comms = levels[curr_level]
            next_level_comms = levels[next_level]
            # compute the sub-communities by nodes intersection
            for comm in this_level_comms:
                results[comm]["sub_communities"] = [
                    c
                    for c in next_level_comms
                    if results[c]["nodes"].issubset(results[comm]["nodes"])
                ]

        for k, v in results.items():
            v["edges"] = list(v["edges"])
            v["edges"] = [list(e) for e in v["edges"]]
            v["nodes"] = list(v["nodes"])
            v["chunk_ids"] = list(v["chunk_ids"])
            v["occurrence"] = len(v["chunk_ids"]) / max_num_ids
        return dict(results)

    def _cluster_data_to_subgraphs(self, cluster_data: dict[str, list[dict[str, str]]]):
        for node_id, clusters in cluster_data.items():
            self._graph.nodes[node_id]["clusters"] = json.dumps(clusters)

    def _leiden_clustering(self):
        from graspologic.partition import hierarchical_leiden

        graph = NetworkXStorage.stable_largest_connected_component(self._graph)
        community_mapping = hierarchical_leiden(
            graph,
            max_cluster_size=10,
            random_seed=0xDEADBEEF,
        )

        node_communities: dict[str, list[dict[str, str]]] = defaultdict(list)
        __levels = defaultdict(set)
        for partition in community_mapping:
            level_key = partition.level
            cluster_id = partition.cluster
            node_communities[partition.node].append(
                {"level": level_key, "cluster": cluster_id}
            )
            __levels[level_key].add(cluster_id)
        node_communities = dict(node_communities)
        __levels = {k: len(v) for k, v in __levels.items()}
        logger.info(f"Each level has communities: {dict(__levels)}")
        self._cluster_data_to_subgraphs(node_communities)

    def embed_nodes(self, algorithm: str) -> tuple[np.ndarray, list[str]]:
        if algorithm not in self._node_embed_algorithms:
            raise ValueError(f"Node embedding algorithm {algorithm} not supported")
        return self._node_embed_algorithms[algorithm]()

    def _node2vec_embed(self):
        from graspologic import embed

        embeddings, nodes = embed.node2vec_embed(
            self._graph,
            **self.global_config["node2vec_params"],
        )

        nodes_ids = [self._graph.nodes[node_id]["id"] for node_id in nodes]
        return embeddings, nodes_ids
```

### **10\.4 prompt\.py 关键常量**

```Python
GRAPH_FIELD_SEP = "<SEP>"          # 多值拼接分隔符
PROMPTS["DEFAULT_ENTITY_TYPES"] = ["organization", "person", "geo", "event"]
PROMPTS["DEFAULT_TUPLE_DELIMITER"] = "<|>"         # 元组内字段分隔
PROMPTS["DEFAULT_RECORD_DELIMITER"] = "##"        # 记录之间分隔
PROMPTS["DEFAULT_COMPLETION_DELIMITER"] = "<|COMPLETE|>"  # 结束标记
PROMPTS["process_tickers"] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]
```

> **面试要点**：
> 
> 1. **实体/关系抽取格式约定**：LLM 输出形如 `("entity"<|>实体名<|>类型<|>描述)` / `("relationship"<|>源<|>目标<|>描述<|>权重)` 的元组记录，用 `##` 分隔、`<|COMPLETE|>` 收尾。解析端 `_handle_single_entity_extraction` 校验 `record_attributes[0] != '"entity"'` 跳过非法记录，**先判类型再取字段**。
> 
> 2. **Gleaning 补充抽取**：首次抽取后最多再跑 `entity_extract_max_gleaning=1` 轮，用 `entiti_continue_extraction` 让 LLM 继续抽漏掉的实体，再用 `entiti_if_loop_extraction` 判断 "是否继续"——只在回答 "yes" 时才进入下一轮（防呆式预算控制）。
> 
> 3. **并行化三处**：逐块抽取 `ThreadPoolExecutor(max_workers=settings.GRAPHRAG_PARALLEL_NUM)`；节点合并用 `executor.submit` \+ `result()`；Map 全局搜索按组并行——**抽图和图搜索都是并发打 LLM**，代价是 token 消耗随并行数线性增长。
> 
> 4. **去重合并策略**：实体同名合并——`entity_type` 取**出现次数最多**的类型、`description` 去重后按 `<SEP>` 拼接；关系同边合并——`weight` **累加**、`order` 取最小；超长描述走 `_handle_entity_relation_summary` 让 LLM 压缩摘要（按 tiktoken 截断后重生成）。
> 
> 5. **社区报告分层生成**：`hierarchical_leiden(max_cluster_size=10, random_seed=0xDEADBEEF)` 产出多层社区，报告**按 level 从大到小**（最细粒度先）生成，粗粒度社区超过 token 上限时用**子社区报告拼接兜底**（`_pack_single_community_by_sub_communities`）；每个社区描述用 CSV 三段式（Reports/Entities/Relationships）打包。
> 
> 6. **`community_schema()`**** 关键细节**：从 node 的 `clusters`（JSON 字符串）字段解析层级归属；**sub\_communities 通过"节点子集"关系推导**（`results[c]["nodes"].issubset(results[comm]["nodes"])`）；`occurrence = 该社区 chunk_ids 数 / 全局最大`，作为社区权重。
> 
> 7. **`graphrag_map`**** 全局搜索**：只取 `rank >= 0` 的报告，按 `(weight, rank)` 倒序；`_map_global_communities` 用 `llm.max_tokens` 做 token 预算把社区切组（**每次填充到上限即截断成一组**）；每组独立调 LLM 提取 `points`（支撑点\+score），最终过滤 `score > 0` 按分排序——这是"Map"式搜索（分组→逐组总结→汇总排序）。
> 
> 

## **§11 Mem0 记忆向量化**

### **11\.1 模块概览与核心文件清单**

本项目基于 **Mem0** 实现"用户个性化长期记忆"：会话内容写入 ES 向量库，带**权重 \(weight\) 与状态 \(status\)** 管理，引入**艾宾浩斯遗忘曲线**做定时衰减遗忘、**冲突判定**用 LLM 决定新旧记忆去留、**LLM 过滤**保证召回与问题相关。

```Plain Text
src/
├── server_api_tools/mem0_helper.py    —— Mem0 核心封装（向量CRUD/权重计算/混合检索/冲突判定）812 行
└── memory/
    ├── memory_vector.py               —— Flask 接口层（增删改查 + 异步分发）157 行
    ├── tasks.py                       —— Huey 异步任务（写库/升权/冲突）83 行
    ├── scheduler.py                   —— 定时任务（遗忘/删除/指标）179 行
    └── redis_lock.py                  —— Redis 分布式锁
```

记忆生命周期：

```Plain Text
写入: 会话 → add_memory → 冲突判定(LLM 判断重复/冲突) → 向量入库(status=NORMAL, weight=1)
检索: 问题 → ES 混合检索(knn+关键词 script_score) → LLM 过滤 → 升权(+0.01)
遗忘: 每日 1 点定时 → 艾宾浩斯衰减 → weight < 0.2 → FORGETED → 回调 Java 引擎
删除: FORGETED 且 3 个月无召回 → DELETED → 回调 Java 引擎批量删除
```

### **11\.2 mem0\_helper\.py 完整源码**

```Python
# ===== src/server_api_tools/mem0_helper.py =====
import os
import pytz
import traceback
import hashlib
import uuid
import json
import math
import requests
from retrying import retry
from typing import List, Dict, Optional
from typing_extensions import override
from datetime import datetime
from pydantic import BaseModel
from typing import Optional
from urllib.parse import urlparse
from src.errors import ApiError
from copy import deepcopy
from elasticsearch import Elasticsearch, helpers
from src.common.utils import formate_datetime
from src.component.server_component.LogManager import logger
from src.ops.mem0_metrics import record_memory_update_llm_metrics, MemUpdateLLM
from src import settings

# fmt: off
# 需关闭 否则每次启动会请求us.i.posthog.com
os.environ["MEM0_TELEMETRY"] = "False"
from mem0 import Memory
from mem0.memory.telemetry import capture_event
from mem0.configs.base import MemoryConfig
from openai import OpenAI
# fmt: on
MEMORY_CALLBACK_HEADERS = {
    "Content-Type": "Application/json",
    "orgCode": "lc9nwe",
    "orgId": "2",
    "tenantId": "t00001",
    "userId": "4",
    "userName": "lzz1"
}


def my_custom_es_search_query(vectors: List[float], limit: int = settings.VECTOR_RESULT_LIMIT, filters: Optional[Dict] = None) -> Dict:
    # 在这里构建你的自定义查询
    # 例如，你可能想使用script_score结合cosineSimilarity进行向量相似度搜索
    # es 8.x
    search_query = {
        "knn": {
            "field": "vector",
            "query_vector": vectors,
            "k": limit,
            "num_candidates": limit * 2,
            "boost": 1,
            "filter": {"bool": filters}
        },
        "size": limit
    }
    return search_query


# MEM0
MEM0_CONFIG = {
    "vector_store": {
        "provider": "elasticsearch",
        "config": {
            # 只能单节点链接
            "host": settings.ES_HOST.split(',')[0] if 'http' in settings.ES_HOST else 'http://'+settings.ES_HOST.split(',')[0],
            "port": settings.ES_PORT,
            "user": settings.ES_USERNAME,
            "password": settings.ES_PASSWORD,
            "collection_name": settings.ES_MEMORY_VECTOR_COLLECTION_NAME,
            "auto_create_index": False,
            "custom_search_query": my_custom_es_search_query
        }
    },

    "llm": {
        "provider": "openai",
        "config": {
            "openai_base_url": settings.MODEL_LLM_URL.replace("/chat/completions", ""),
            "api_key": settings.MAIP_SECRET_KEY,
            "model": settings.MODEL_LLM_NAME
        }
    },
    "embedder": {
        "provider": "openai",
        "config": {
            "openai_base_url": settings.MODEL_EMBEDDER_URL,
            "api_key": settings.MAIP_SECRET_KEY,
            "model": settings.MODEL_EMBEDDER_NAME,
            "embedding_dims": 1024
        }
    }
}


class MetaData(BaseModel):
    id: str
    agent_id: str
    chat_id: str
    memory: str
    record_at: str
    record_id: str
    role: str
    team_id: str
    user_id: str
    classification: str = ''


class VectorResult(BaseModel):
    id: str = None
    created_at: str = None
    updated_at: Optional[str] = None
    score: float = None
    weight: float = None
    metadata: MetaData = None


class VectorStatus():
    NORMAL = 0
    FORGETED = 1
    DELETED = 2


class Calculator():
    """权重,状态计算器"""

    def __init__(self, metadata: dict = None):
        self.metadata = metadata
        self.max_weight = 2
        self.min_weight = 0.01
        self.forget_weight = 0.2
        self.round_num = 4

    def get_status(self, weight):
        if weight < self.forget_weight:
            return VectorStatus.FORGETED
        else:
            return VectorStatus.NORMAL

    def add_weight(self, weight, value) -> dict:
        new_weight = round(weight + value, self.round_num)
        new_weight = new_weight if new_weight < self.max_weight else self.max_weight
        status = self.get_status(new_weight)

        res = {"weight": new_weight, "status": status}
        if status != VectorStatus.FORGETED:
            res['forgeted_at'] = None
        return res

    def sub_weight(self, weight, value):
        new_weight = round(weight - value, self.round_num)
        new_weight = new_weight if new_weight > self.min_weight else self.min_weight

        return new_weight, self.get_status(new_weight)

    def ebbinghaus_decay(self):
        """艾宾浩斯遗忘"""
        # 遗忘速度常数，衰减到原始的20%，推导
        B = 0.008941

        def calculate_ratio(t):
            numerator = math.exp(-B * t)
            denominator = math.exp(-B * (t - 1))
            return numerator / denominator
        try:
            day = (datetime.now() -
                   datetime.strptime(self.metadata.get('record_at')[:19].replace("T", ' '), "%Y-%m-%d %H:%M:%S")).days
            weight = round(self.metadata.get('weight', 1) * calculate_ratio(day), self.round_num)
            weight = weight if weight > self.min_weight else self.min_weight
            return weight, self.get_status(weight)
        except Exception as e:
            logger.warning(f"ebbinghaus_decay error:{e}-{self.metadata}")

        return self.metadata.get('weight', 1), self.metadata.get('status', VectorStatus.NORMAL)


def add_client_header(client: OpenAI, org_code):
    """ 对llm 和embedder增加header
    client: Openai
    """
    if not client._custom_headers:
        client._custom_headers = {}
    client._custom_headers.update({"Org-Code": org_code})


class MyMemory(Memory):
    def __init__(self, config: MemoryConfig = MemoryConfig()):
        super(MyMemory, self).__init__(config)
        """es 修改，兼容多节点连接"""
        if self.config.vector_store.provider == 'elasticsearch':
            hosts = []
            for nd in settings.ES_HOST.split(','):
                if 'http' not in nd:
                    node = f"http://{nd}:{settings.ES_PORT}"
                else:
                    node = f"{nd}:{settings.ES_PORT}"
                hosts.append(node)
            self.vector_store.client = Elasticsearch(
                hosts=hosts,
                basic_auth=(settings.ES_USERNAME, settings.ES_PASSWORD) if (
                    settings.ES_USERNAME and settings.ES_PASSWORD) else None,
                verify_certs=True,
            )

    def _search_vector_store(self, query, filters, limit):
        """Memory es缺陷，自定义query查询也会在filter中添加user_id，导致查询报错，重写修复"""
        if self.config.vector_store.provider == 'elasticsearch':
            if 'user_id' in filters:
                del filters['user_id']
        return super(MyMemory, self)._search_vector_store(query, filters, limit)

    def find_by_metadata(self, metadata: dict):
        """标量字段查询"""
        if self.config.vector_store.provider == 'elasticsearch':
            client: Elasticsearch = self.vector_store.client
            must_list = []
            for key, value in metadata.items():
                if value:
                    must_list.append({"match": {f"metadata.{key}": value}})
            body = {
                '_source': False,  # 不返回源数据
                "query": {
                    "bool": {
                        "must": must_list
                    }
                }
            }
            res = client.search(index=self.vector_store.collection_name, body=body)
            if res:
                return res['hits']['hits']
        else:
            raise ValueError(f"find_by_metadata only support es!")

    @override
    def update(self, memory_id, data, metadata=None):
        """
        重写：增加metadata传入
        Update a memory by ID.

        Args:
            memory_id (str): ID of the memory to update.
            data (dict): Data to update the memory with.

        Returns:
            dict: Updated memory.
        """
        capture_event("mem0.update", self, {"memory_id": memory_id, "sync_type": "sync"})
        add_client_header(self.embedding_model.client, org_code=metadata.get("org_code"))
        existing_embeddings = {data: self.embedding_model.embed(data, "update")}

        self._update_memory(memory_id, data, existing_embeddings, metadata)
        return {"message": "Memory updated successfully!"}

    @override
    def _create_memory(self, data, existing_embeddings, metadata=None):
        """重写_create_memory 可指定id写入数据，保证数据一致性"""
        logger.debug(f"Creating memory with {data=}")
        if data in existing_embeddings:
            embeddings = existing_embeddings[data]
        else:
            add_client_header(self.embedding_model.client, org_code=metadata.get("org_code"))
            embeddings = self.embedding_model.embed(data, memory_action="add")
        # 修改行
        memory_id = metadata['id'] if metadata and metadata.get('id') else str(uuid.uuid4())
        metadata = metadata or {}
        metadata["data"] = data
        metadata["hash"] = hashlib.md5(data.encode()).hexdigest()
        metadata["created_at"] = formate_datetime()

        self.vector_store.insert(
            vectors=[embeddings],
            ids=[memory_id],
            payloads=[metadata],
        )
        self.db.add_history(
            memory_id,
            None,
            data,
            "ADD",
            created_at=metadata.get("created_at"),
            actor_id=metadata.get("actor_id"),
            role=metadata.get("role"),
        )
        capture_event("mem0._create_memory", self, {"memory_id": memory_id, "sync_type": "sync"})
        return memory_id

    @override
    def _update_memory(self, memory_id, data, existing_embeddings, metadata=None):
        """重写，修改时区为Asia/Shanghai
        """
        logger.info(f"Updating memory with {data=}")

        try:
            existing_memory = self.vector_store.get(vector_id=memory_id)
        except Exception:
            logger.error(f"Error getting memory with ID {memory_id} during update.")
            raise ValueError(f"Error getting memory with ID {memory_id}. Please provide a valid 'memory_id'")

        prev_value = existing_memory.payload.get("data")

        new_metadata = deepcopy(metadata) if metadata is not None else {}

        new_metadata["data"] = data
        new_metadata["hash"] = hashlib.md5(data.encode()).hexdigest()
        new_metadata["created_at"] = existing_memory.payload.get("created_at")
        new_metadata["updated_at"] = formate_datetime()

        if "user_id" in existing_memory.payload:
            new_metadata["user_id"] = existing_memory.payload["user_id"]
        if "agent_id" in existing_memory.payload:
            new_metadata["agent_id"] = existing_memory.payload["agent_id"]
        if "run_id" in existing_memory.payload:
            new_metadata["run_id"] = existing_memory.payload["run_id"]
        if "actor_id" in existing_memory.payload:
            new_metadata["actor_id"] = existing_memory.payload["actor_id"]
        if "role" in existing_memory.payload:
            new_metadata["role"] = existing_memory.payload["role"]

        if data in existing_embeddings:
            embeddings = existing_embeddings[data]
        else:
            add_client_header(self.embedding_model.client, org_code=metadata.get("org_code"))
            embeddings = self.embedding_model.embed(data, "update")

        self.vector_store.update(
            vector_id=memory_id,
            vector=embeddings,
            payload=new_metadata,
        )
        logger.info(f"Updating memory with ID {memory_id=} with {data=}")

        self.db.add_history(
            memory_id,
            prev_value,
            data,
            "UPDATE",
            created_at=new_metadata["created_at"],
            updated_at=new_metadata["updated_at"],
            actor_id=new_metadata.get("actor_id"),
            role=new_metadata.get("role"),
        )
        capture_event("mem0._update_memory", self, {"memory_id": memory_id, "sync_type": "sync"})
        return memory_id


class Engine():
    """java引擎-"""

    def __init__(self):
        parsed = urlparse(settings.AGENT_PLUGIN_EXECUTE_URL)
        self.agent_host = f"{parsed.scheme}://{parsed.netloc}"

    @staticmethod
    def post_engine(url, body):
        logger.info(f"src.server_api_tools.mem0_helper-post_engine  {url}-{body}")
        resp = requests.post(url, json=body, headers=MEMORY_CALLBACK_HEADERS, timeout=60*3)
        logger.info(f"src.server_api_tools.mem0_helper-post_engine  结果：{resp.status_code} {resp.text}")
        return resp.json()

    @retry(stop_max_attempt_number=3, wait_fixed=1000*2)
    def conflict_agent(self, memory, data_list):
        if not data_list:
            logger.info(f"src.server_api_tools.mem0_helper-conflict_agent 无待判定数据")
            return None
        body = {
            "chatId": str(uuid.uuid4()),
            "appId": settings.AGENT_APP_ID,
            "variables": {"oldMemory": json.dumps(data_list, ensure_ascii=False)},
            "messages": [
                {
                    "content": memory,
                    "role": "user"
                }
            ]
        }
        res = self.post_engine(f"{self.agent_host}/api/v1/chat/completions", body)
        if res.get('choices') and len(res['choices']) > 0:
            content = res['choices'][0].get('message', {}).get('content')
            content = json.loads(content)
            return content
        else:
            raise Exception(str(res))

    @retry(stop_max_attempt_number=3, wait_fixed=1000*1)
    def memory_forget(self, id_list: list):
        logger.info(f"memory_forget req:{id_list}")
        res = self.post_engine(f"{self.agent_host}/api/v1/memory/forget/process", id_list)
        logger.info(f"memory_forget res:{res}")
        if res.get("code") == '10000':
            return True
        return False

    @retry(stop_max_attempt_number=3, wait_fixed=1000*1)
    def memory_delete(self, id_list: list):
        logger.info(f"memory_delete req:{id_list}")
        res = self.post_engine(f"{self.agent_host}/api/v1/memory/batch-delete", id_list)
        logger.info(f"memory_delete res:{res}")
        if res.get("code") == '10000':
            return True
        return False

    @retry(stop_max_attempt_number=3, wait_fixed=1000*1)
    def memory_forget_rollback(self, id_list: list):
        logger.info(f"memory_forget_rollback req:{id_list}")
        res = self.post_engine(f"{self.agent_host}/api/v1/memory/forget/rollback", id_list)
        logger.info(f"memory_forget_rollback res:{res}")
        if res.get("code") == '10000':
            return True
        return False


class Mem0Helper:

    def __init__(self):
        self.client: MyMemory = MyMemory.from_config(MEM0_CONFIG)
        self.init_collections()

    def init_collections(self):
        """初始化向量表"""
        if self.client.config.vector_store.provider == 'elasticsearch':
            from src.server_api_tools.vector_collections.es_index import memory_mappings
            es_clinet: Elasticsearch = self.client.vector_store.client

            def normalize(d):
                """排序并转 json，以便对比"""
                return json.dumps(d, sort_keys=True)

            index_name = self.client.vector_store.collection_name
            if es_clinet.indices.exists(index=index_name):
                logger.info(f"Index '{index_name}' exists. Checking for mapping changes...")

                # 获取现有 mapping
                current_mapping = es_clinet.indices.get_mapping(index=index_name)[index_name]["mappings"]

                # 比较结构（简单粗暴地转 json 字符串对比）
                if normalize(current_mapping) != normalize(memory_mappings['mappings']):
                    logger.info("Mapping is different, updating...")
                    try:
                        es_clinet.indices.put_mapping(
                            index=index_name,
                            body=memory_mappings['mappings']
                        )
                    except Exception as e:
                        logger.error(f"更新mapping 异常--{e}--{memory_mappings['mappings']}")
                else:
                    logger.info("Mapping is up to date.")
            else:
                logger.info(f"Creating index '{index_name}'...")
                es_clinet.indices.create(
                    index=index_name,
                    body=memory_mappings
                )

    def memory_conflict(self, memory, metadata):
        """冲突判定
        Returns:
            curr_item, [id, new_weight]
        """
        filter_list = []
        for key in ['user_id', 'team_id']:
            filter_list.append({"term": {f"metadata.{key}": metadata[key]}})
        filter_list.append({"match": {f"metadata.classification.keyword": metadata.get("classification", '')}})

        filters = {"must": filter_list,
                   "must_not": [{"term": {"metadata.status": 2}}]}
        result = self.query_memory_es_mix(query=memory, filters=filters,
                                          min_score=-1,
                                          size=100,
                                          add_weight=False,
                                          org_code=metadata.get("org_code", ""))

        down_weight = []
        curr_weight = 1
        curr_item = {"weight": curr_weight, "status": VectorStatus.NORMAL}
        if result:
            data_list = [{'id': x['metadata']['id'], 'content': x['metadata']['memory']} for x in result
                         if x['metadata']['id'] != metadata.get("id", "")]
            if data_list:

                id_map = {}
                # id转化成index，请求减少token
                for index, item in enumerate(data_list):
                    id_map[str(index)] = item['id']
                    item['id'] = str(index)

                try:
                    res = Engine().conflict_agent(memory=memory, data_list=data_list)
                except Exception as e:
                    logger.error(f"冲突判定agent失败-{e}")
                    # 监控上报
                    labels = MemUpdateLLM(teamId=metadata.get('team_id'), status='0')
                    record_memory_update_llm_metrics(labels=labels)
                    raise e

                source_weight = {x['metadata']['id']: x['metadata']['weight'] for x in result}
                if res and res.get("重复的记忆id"):
                    curr_weight = max([1] + [source_weight.get(id_map.get(str(x), ''), 1) for x in res['重复的记忆id']])
                    logger.info(f"src.server_api_tools.mem0_helper 冲突判断-重复记忆权重max：{curr_weight}")
                    curr_item = Calculator().add_weight(curr_weight, curr_weight*0.2)
                if res and res.get("冲突的记忆id"):
                    for num in res['冲突的记忆id']:
                        x = id_map.get(str(num), "")
                        if x in source_weight:
                            curr_weight, status = Calculator().sub_weight(source_weight[x], source_weight[x]*0.5)
                            down_weight.append({"id": x, "weight": curr_weight, "status": status})
        logger.info(f"src.server_api_tools.mem0_helper 冲突判断-当前权重{curr_item}， 降权：{down_weight}")
        return curr_item, down_weight

    def es_udpate_bulk(self, data_list: list[dict], refrsh=True):
        if not data_list:
            return
        actions = []
        updated_at = formate_datetime()
        for item in data_list:
            tmp = {
                "_op_type": "update",
                "_index": self.client.vector_store.collection_name,
                "_id": item["id"],
                "doc": {"metadata": {k: v for k, v in item.items() if k != 'id'}}
            }
            if "updated_at" not in tmp['doc']['metadata']:
                tmp['doc']['metadata']['updated_at'] = updated_at
            actions.append(tmp)

        es_clinet: Elasticsearch = self.client.vector_store.client
        res, _ = helpers.bulk(es_clinet, actions)
        if refrsh:
            es_clinet.indices.refresh(index=self.client.vector_store.collection_name)
        logger.info(f"src.server_api_tools.mem0_helper es_udpate_bulk 更新成功！ {res}, {actions[:10]}")

    def add_memory(self, data_list: list[dict], org_code: str) -> list:
        res = []
        for item in data_list:
            metadata = deepcopy(item)
            metadata['org_code'] = org_code
            down_weight_list = []
            curr_item = {}
            try:
                curr_item, down_weight_list = self.memory_conflict(memory=item['memory'], metadata=metadata)
                if down_weight_list:
                    self.es_udpate_bulk(down_weight_list)
            except Exception as e:
                logger.warning(f"src.server_api_tools.mem0_helper--memory_conflict error:{e} {traceback.format_exc()}")
            # mem0会自动将message中的内容添加到metadata中的data字段中
            del metadata['memory']
            metadata['weight'] = 1
            metadata['status'] = VectorStatus.NORMAL
            metadata['cnt_recall'] = 0
            metadata['cnt_recall_latest'] = 0
            if curr_item:
                metadata.update(curr_item)
            try:
                result = self.client.add(messages=item['memory'], user_id=item.get("user_id"),
                                         agent_id=item.get("agent_id"), metadata=metadata, infer=False)
                res.append(result["results"][0])
            except Exception as e:
                logger.error(f"src.server_api_tools.mem0_helper 向量写入异常:{traceback.format_exc()}")
                raise ApiError(code=-1, msg=str(e))

        for index, item in enumerate(data_list):
            item['id'] = res[index]['id']

        return data_list

    def query_memory_es_mix(self, query, filters, min_score, size, add_weight=True, org_code=''):
        """es 混合检索"""
        # 增加header
        add_client_header(self.client.embedding_model.client, org_code=org_code)

        embeddings = self.client.embedding_model.embed(query, "search")
        es_clinet: Elasticsearch = self.client.vector_store.client
        max_score_query = {
            "query": {
                "bool": {
                    "must": [
                        {
                            "match": {
                                "metadata.data": {
                                    "query": query
                                }
                            }
                        }
                    ],
                    "filter": {"bool": filters}
                }
            },
            'size': 1,
            "_source": False
        }
        res = es_clinet.search(index=self.client.vector_store.collection_name, body=max_score_query)
        max_keyword_score = res['hits'].get('max_score') or 1.0
        logger.info(f"src.server_api_tools.mem0_helper query_memory_es_mix max_keyword_score:{max_keyword_score}")
        source_str = """
            double sim = cosineSimilarity(params.query_vector, 'vector');
            sim = (sim + 1.0) / 2.0;
            double keywordScore = _score / params.max_keyword_score;
            return ((sim * 0.2) + (keywordScore * 0.8));
        """
        body = {
            "_source": {
                "excludes": "vector"
            },
            "query": {
                "script_score": {
                    "query": {
                        "bool": {
                            "must": [
                                {
                                    "match": {
                                        "metadata.data": {
                                            "query": query
                                        }
                                    }
                                }
                            ],
                            "filter": {"bool": filters}
                        },
                    },
                    "script": {
                        "source": source_str,
                        "params": {
                            "query_vector": embeddings,
                            "max_keyword_score": max_keyword_score
                        }
                    }
                }
            },
            "min_score": min_score,
            "size": size
        }

        response = es_clinet.search(index=self.client.vector_store.collection_name, body=body)
        max_weight = 1
        if add_weight and response["hits"]["hits"]:
            max_weight = max([x["_source"]["metadata"].get('weight', 1) for x in response["hits"]["hits"]])

        results = []
        for hit in response["hits"]["hits"]:
            metadata = hit["_source"]["metadata"]
            metadata['score'] = hit['_score']
            metadata['sort_score'] = hit['_score'] * (metadata.get("weight", 1)/max_weight)
            metadata['memory'] = metadata['data']
            results.append(hit["_source"])
        # 加权重排
        if add_weight:
            results.sort(key=lambda x: x['metadata']['sort_score'], reverse=True)

        return results

    def llm_filter_query_result(self, query, result, org_code):
        """LLM 过滤"""
        # id用下标，提高那效率
        tmp_list = [{"id": index, "data": x['metadata']['data']} for index, x in enumerate(result)]
        logger.info(f"src.server_api_tools.mem0_helper 过滤前:{tmp_list}")
        tmp_input = [{'id': 0, 'data': '不喜欢打篮球'},
                     {'id': 1, 'data': '晚上8点后不看手机'},
                     {'id': 2, 'data': '不讨厌吃红烧肉。'},
                     {'id': 3, 'data': '喜欢打乒乓球'}]
        sys_prompt = f"""
        1. 你是一个用户个性化记忆识别专家，需要从记忆库数据中筛选出满足用户输入的内容，只返回id即可
        2. 你需要注意判断用户输入的内容的情绪是正向还是负向的，筛选出的内容的情绪需要和输入内容情绪一直
        4. 返回结构为数组，不要有其他描述或总结
        示例：
            例如存在记忆库：{tmp_input}
            输入1:喜欢吃什么
            输出1：[2]

            输入2:我喜欢的运动是什么
            输出1：[3]
        """

        user_prompt = f"""
        用户输入：{query}
        记忆库：{tmp_list}
        """
        self.client.llm.client.timeout = 30
        self.client.llm.client.max_retries = 0
        # 增加header

        add_client_header(self.client.llm.client, org_code=org_code)

        try:
            response = self.client.llm.generate_response(
                messages=[
                    {"role": "system", "content": sys_prompt},
                    {"role": "user", "content": user_prompt},
                ]
            )
            response = json.loads(response)
            logger.info(f"src.server_api_tools.mem0_helper 过滤后:{[x for x in tmp_list if x['id'] in response]}")
            res = []
            for index, item in enumerate(result):
                if index in response:
                    res.append(item)
            return res
        except Exception as e:
            logger.error(f"src.server_api_tools.mem0_helper llm ERROR:{e}")
            return [x for x in result if x['metadata'].get("score", 1) >= 0.7]

    def query_memory(self, query_dict: dict):
        """记忆检索
        Returns:
            (res:数据列表, update_weight:升权修改列表, normal_list:待遗忘变正常状态列表)
        """
        filter_dict: dict = query_dict.get('filter', {})
        must_list = []
        for key in ['user_id', 'team_id']:
            must_list.append({"term": {f"metadata.{key}": query_dict[key]}})

        if filter_dict:
            if filter_dict.get('chat_id'):
                must_list.append({"term": {f"metadata.chat_id": filter_dict['chat_id']}})

            if filter_dict.get('record_at_max'):
                must_list.append({"range": {f"metadata.record_at": {"lte": filter_dict.get('record_at_max')}}})
            if filter_dict.get('record_at_min'):
                must_list.append({"range": {f"metadata.record_at": {"gte": filter_dict.get('record_at_min')}}})
        filters = {"must": must_list, "must_not": [{"term": {"metadata.status": 2}}]}
        # knn检索
        # result = self.client.search(query=query_dict['query'], user_id=query_dict.get('user_id'),
        #                             agent_id=filter_dict.get('agent_id'), limit=settings.VECTOR_RESULT_LIMIT, filters=filters)
        # result = result.get('results', [])

        query_min_score = settings.VECTOR_RESULT_SCORE
        if not settings.FLAG_MEMORY_LLM_FILTER:
            # llm过滤关闭
            query_min_score = 0.7
        # knn+关键词 mix检索
        result = self.query_memory_es_mix(query=query_dict['query'], filters=filters,
                                          min_score=query_min_score,
                                          size=settings.VECTOR_RESULT_LIMIT,
                                          org_code=query_dict.get('org_code', ""))
        logger.info(f"src.server_api_tools.mem0_helper query_memory len:{len(result)}")
        if result and settings.FLAG_MEMORY_LLM_FILTER:
            result = self.llm_filter_query_result(
                query_dict['query'], result=result, org_code=query_dict.get('org_code'))
        # 标准化返回结构
        res = []
        update_weight, normal_list = [], []
        for item in result:
            tmp = {}
            tmp.update({k: v for k, v in item.items() if k != 'metadata'})
            tmp.update(item['metadata'])
            curr_item = Calculator().add_weight(tmp.get('weight', 1), 0.01)
            if tmp.get('status') in {1} and curr_item.get('status') in {0}:
                normal_list.append(tmp['id'])
            update_item = {"id": tmp['id'], "cnt_recall": tmp.get(
                'cnt_recall', 0)+1, "cnt_recall_latest": tmp.get('cnt_recall_latest', 0)+1}
            update_item.update(curr_item)
            update_weight.append(update_item)

            res_obj = VectorResult(**tmp)
            res_obj.metadata = MetaData(**tmp)
            res.append(res_obj.model_dump())

        return res, update_weight, normal_list

    def update_memory_by_id(self, id, memory, metadata) -> dict:
        self.client.update(memory_id=id, data=memory, metadata=metadata)
        return True

    def get_memory_by_id(self, id) -> dict:
        res = self.client.vector_store.get(vector_id=id)
        if not res:
            return None
        else:
            return res.payload

    def delete_memory_by_id(self, id) -> bool:
        return self.client.delete(memory_id=id)

    def delete_memory_by_filters(self, filters: dict):
        if filters.get('id'):
            self.delete_memory_by_id(id=filters['id'])
            return 1
        elif filters.get('team_id') and filters.get('user_id'):
            data = self.client.find_by_metadata(filters)
            logger.info(f"src.server_api_tools.mem0_helper.delete_memory_by_filters :{data}")

            for item in data:
                self.delete_memory_by_id(id=item['_id'])
            return len(data)
        else:
            raise ApiError(code=-1, msg='参数缺失！')

    def query_es_metadata(self, body):
        """检索metadata"""
        es_clinet: Elasticsearch = self.client.vector_store.client
        if '_source' not in body:
            body['_source'] = {
                "excludes": "vector"
            }

        response = es_clinet.search(
            index=self.client.vector_store.collection_name,
            scroll='2m',  # scroll 上下文有效期
            size=1000,    # 每批数量
            body=body
        )

        scroll_id = response['_scroll_id']
        hits = response['hits']['hits']

        while hits:
            yield hits
            # 用 scroll_id 获取下一页
            response = es_clinet.scroll(scroll_id=scroll_id, scroll='2m')
            scroll_id = response['_scroll_id']
            hits = response['hits']['hits']

        # 清理 scroll
        es_clinet.clear_scroll(scroll_id=scroll_id)
```

### **11\.3 记忆向量 API 层 \(src/memory/memory\_vector\.py\)**

**文件**: `src/memory/memory_vector.py` \(157 行\) ★核心

- 模块级单例 `mem0_helper = Mem0Helper()`

- 基于 `flask_restful.Resource` \+ `@dataschema` \(voluptuous\) 参数校验 \+ `@error_handler` 统一异常处理

- 四个 REST 资源：`AddVector` / `QueryVector` / `DeleteVector` / `UpdateVector`

**记忆写入/查询/更新/删除的完整源码**:

```Python
import json
import uuid
from flask_restful import Resource
from flask import request
from src.component.server_component.LogManager import logger
from src.api import dataschema
from voluptuous import Required, All, Length, Optional
from src.server_api_tools.mem0_helper import Mem0Helper
from src.errors import error_handler
from src.resp import success, fail

mem0_helper = Mem0Helper()


class AddVector(Resource):
    @staticmethod
    def get():
        return fail(msg='use post')

    @dataschema(
        {
            "callback_url": str,
            Required("data_list"): All([{
                Required("id"): All(str, Length(min=1)),
                Required("memory"): All(str, Length(min=1)),
                Required("user_id"): All(str, Length(min=1)),
                Required("chat_id"): All(str, Length(min=1)),
                Required("team_id"): All(str, Length(min=1)),
                Required("agent_id"): All(str, Length(min=1)),
                Required("record_id"): All(str, Length(min=1)),
                Optional("role", default=lambda: "user"): str,  # 设置默认值为 "user"
                Optional("classification", default=lambda: ""): str,  # 设置默认值为 ""
                Required("record_at"): All(str, Length(min=1))
            }], Length(min=1))
        }
    )
    @error_handler
    def post(self, **req_json):

        logger.info(
            f"src.memory.memory_vector.AddVector req:{json.dumps(req_json, ensure_ascii=False)}, {request.headers=}")
        if not req_json.get('callback_url'):
            res = mem0_helper.add_memory(req_json.get('data_list'), org_code=request.headers.get("Org-Code", ""))
            logger.info(f"src.memory.memory_vector.AddVector res:{res}")
            return success(data={"result": res})
        else:
            task_id = str(uuid.uuid4())
            from src.memory import tasks

            # send task to huey
            tasks.add_memory_async(
                task_id=task_id, callback_url=req_json['callback_url'], data_list=req_json.get('data_list'), org_code=request.headers.get("Org-Code", ""))

            return success(data={"task_id": task_id})


class QueryVector(Resource):
    @staticmethod
    def get():
        return fail(msg='use post')

    @dataschema(
        {
            Required("user_id"): All(str, Length(min=1)),
            Required("query"): All(str, Length(min=1)),
            Required("team_id"): All(str, Length(min=1)),
            "filter": dict,
        }
    )
    @error_handler
    def post(self, **req_json):
        req_json['org_code'] = request.headers.get("Org-Code", "")
        logger.info(
            f"src.memory.memory_vector.QueryVector req:{json.dumps(req_json, ensure_ascii=False)}, {request.headers=}")
        res, update_weight, normal_list = mem0_helper.query_memory(req_json)
        logger.info(f"src.memory.memory_vector.QueryVector res:{res}")
        if update_weight:
            try:
                task_id = str(uuid.uuid4())
                from src.memory import tasks
                tasks.update_memory_weight_async(task_id=task_id, data_list=update_weight, normal_list=normal_list)
            except Exception as e:
                logger.warning(f"src.memory.memory_vector.QueryVector 更新权重异常:{e}")

        return success(data={"result": res})


class DeleteVector(Resource):
    @staticmethod
    def get():
        return fail(msg='use post')

    @dataschema(
        {
            "id": str,
            "chat_id": str,
            "user_id": str,
            "team_id": str
        }
    )
    @error_handler
    def delete(self, **req_json):
        logger.info(f"src.memory.memory_vector.DeleteVector req:{json.dumps(req_json, ensure_ascii=False)}")
        res = mem0_helper.delete_memory_by_filters(req_json)
        logger.info(f"src.memory.memory_vector.DeleteVector res:{res}")
        return success(data={"total": res})


class UpdateVector(Resource):
    @staticmethod
    def get():
        return fail(msg='use put')

    @dataschema(
        {
            Required("id"): All(str, Length(min=1)),
            Required("memory"): All(str, Length(min=1)),
            Required("record_at"): All(str, Length(min=1)),
        }
    )
    @error_handler
    def put(self, **req_json):
        req_json['org_code'] = request.headers.get("Org-Code", "")
        logger.info(
            f"src.memory.memory_vector.UpdateVector req:{json.dumps(req_json, ensure_ascii=False)}, {request.headers=}")
        metadata = mem0_helper.get_memory_by_id(req_json['id'])
        if metadata:
            metadata['org_code'] = req_json['org_code']
            if metadata['data'] == req_json['memory']:
                mem0_helper.update_memory_by_id(
                    id=req_json['id'],
                    memory=req_json['memory'],
                    metadata={
                        "record_at": req_json['record_at'],
                        "team_id": metadata.get("team_id", ""),
                        "org_code": req_json['org_code']
                    })
            else:
                mem0_helper.update_memory_by_id(
                    id=req_json['id'],
                    memory=req_json['memory'],
                    metadata={
                        "record_at": req_json['record_at'],
                        "team_id": metadata.get("team_id", ""),
                        "org_code": req_json['org_code']
                    })

                # 冲突判定
                try:
                    task_id = str(uuid.uuid4())
                    from src.memory import tasks
                    tasks.memory_conflict_async(task_id=task_id, memory=req_json['memory'], metadata=metadata)
                except Exception as e:
                    logger.warning(f"src.memory.memory_vector.UpdateVector 异步更新权重异常:{e}")
        else:
            return fail(msg="id不存在")
        return success(data=True)
```

### **11\.4 记忆异步任务与调度器 \(src/memory/tasks\.py \+ scheduler\.py\)**

**文件**: `src/memory/tasks.py` \(83 行\) \+ `src/memory/scheduler.py` \(179 行\) ★核心

- **tasks\.py**: 基于 Huey \(`@huey_memory_vector.task()`\) 的异步任务，`post_vector_callback` 带 `@retry(stop_max_attempt_number=3, wait_fixed=1000*2)` 重试回调

- **scheduler\.py**: 基于 APScheduler `BackgroundScheduler` 每日凌晨 1 点定时执行记忆遗忘/删除/指标上报，用 `RedisLock` 防止多实例并发

**异步任务完整源码 \(tasks\.py\)**:

```Python

import requests
from retrying import retry
from src.globals import huey_memory_vector
from src.component.server_component.LogManager import logger


MEMORY_CALLBACK_HEADERS = {
    "Content-Type": "Application/json",
    "orgCode": "lc9nwe",
    "orgId": "2",
    "tenantId": "t00001",
    "userId": "4",
    "userName": "lzz1"
}


@retry(stop_max_attempt_number=3, wait_fixed=1000*2)
def post_vector_callback(callback_url, callback_data):
    logger.info(f"memory.tasks.post_vector_callback  回调{callback_url}")
    resp = requests.post(callback_url, json=callback_data, headers=MEMORY_CALLBACK_HEADERS, timeout=30)
    return resp


@huey_memory_vector.task()
def add_memory_async(task_id, callback_url, data_list: list[dict], org_code: str) -> list:
    from src.memory.memory_vector import mem0_helper
    logger.info(f"memory.tasks.add_memory_async {task_id} start")
    try:
        data_list = mem0_helper.add_memory(data_list=data_list, org_code=org_code)
        callback_data = {
            "code": 0,
            "msg": "success",
            "task_id": task_id,
            "result": data_list,
        }
    except Exception as e:
        logger.warning(f"memory.tasks.add_memory_async {task_id}, 异常：{e}")

        callback_data = {
            "code": -1,
            "msg": str(e),
            "task_id": task_id,
            "result": None,
        }
    logger.info(f"memory.tasks.add_memory_async {task_id}, 回调body：{callback_data}")

    try:
        resp = post_vector_callback(callback_url=callback_url, callback_data=callback_data)
        logger.info(f"memory.tasks.add_memory_async {task_id}, 回调结果：{resp.status_code} {resp.text}")
    except Exception as e:
        logger.error(f"memory.tasks.add_memory_async 回调异常{e}")


@huey_memory_vector.task()
def update_memory_weight_async(task_id, data_list: list[dict], normal_list: list[str] = None) -> list:
    from src.memory.memory_vector import mem0_helper
    from src.server_api_tools.mem0_helper import Engine
    logger.info(f"memory.tasks.update_memory_weight_async {task_id} start")
    if normal_list:
        res = Engine().memory_forget_rollback(id_list=normal_list)
        if not res:
            logger.info(f"memory.tasks.update_memory_weight_async {task_id} 回调异常，终止更新")
            return
    mem0_helper.es_udpate_bulk(data_list=data_list, refrsh=False)
    logger.info(f"memory.tasks.update_memory_weight_async {task_id} done")


@huey_memory_vector.task()
def memory_conflict_async(task_id, memory, metadata: dict) -> list:
    """异步冲突检测"""
    from src.memory.memory_vector import mem0_helper
    # 冲突判定
    curr_item, down_weight_list = mem0_helper.memory_conflict(memory, metadata=metadata)
    save_item = {'id': metadata['id']}
    save_item.update(curr_item)
    save_list = [save_item]
    if down_weight_list:
        save_list.extend(down_weight_list)

    logger.info(f"memory.tasks.memory_conflict_async {task_id} start")
    mem0_helper.es_udpate_bulk(data_list=save_list, refrsh=False)
    logger.info(f"memory.tasks.memory_conflict_async {task_id} done")
```

**定时调度器完整源码 \(scheduler\.py\)**:

```Python
import hashlib
from datetime import datetime, timedelta
from src.server_api_tools.mem0_helper import Mem0Helper, VectorStatus, Calculator, Engine
from src.common.utils import formate_datetime
from src.component.server_component.LogManager import logger
from apscheduler.schedulers.background import BackgroundScheduler
from src.ops.mem0_metrics import record_memory_status_metrics, record_memory_weight_metrics, MemWeight, MemStatus, MEM_WEIGHT, MEM_STATUS
from src.settings import AGENT_PLUGIN_EXECUTE_URL


def upload_metrics():
    """状态权重上报"""
    MEM_STATUS.clear()
    MEM_WEIGHT.clear()
    mem0_helper = Mem0Helper()
    body = {
        "query": {
            "match_all": {}
        }
    }
    for data_list in mem0_helper.query_es_metadata(body=body):
        for item in data_list:
            metadata = item['_source'].get('metadata')
            if not metadata:
                continue
            # 上报状态分布
            record_memory_status_metrics(MemStatus(teamId=metadata.get(
                "team_id", ""), status=str(metadata.get("status", ""))))
            # 上报权重分布
            record_memory_weight_metrics(MemWeight(teamId=metadata.get(
                "team_id", ""), weightDim=str(round(metadata.get("weight", 1), 1))))


def memory_forget():
    """记忆遗忘"""
    logger.info(f"记忆遗忘刷新-开始")
    mem0_helper = Mem0Helper()
    body = {
        "query": {
            "bool": {
                "must_not": [
                    {
                        "term": {
                            "metadata.status": {
                                "value":  2
                            }
                        }
                    }
                ]
            }
        }
    }
    callback_engine = Engine()
    for data_list in mem0_helper.query_es_metadata(body=body):
        save_list = []
        forget_list = []
        for item in data_list:
            if not item['_source'].get('metadata'):
                continue
            metadata = item['_source']['metadata']
            weight, status = Calculator(metadata=metadata).ebbinghaus_decay()
            tmp = {
                'id': item['_id'],
                'weight': weight,
                'status': status,
                "updated_at": formate_datetime()
            }
            if not metadata.get('forgeted_at') and status == VectorStatus.FORGETED:
                tmp['forgeted_at'] = formate_datetime()
                tmp['cnt_recall_latest'] = 0
                forget_list.append(tmp['id'])
            elif metadata.get('forgeted_at') and status == VectorStatus.FORGETED and metadata.get('cnt_recall_latest', 0) > 0:
                # 每三个月清零计算
                day = (datetime.now() -
                       datetime.strptime(metadata.get('forgeted_at')[:19].replace("T", ' '), "%Y-%m-%d %H:%M:%S")).days
                if day > 90:
                    tmp['forgeted_at'] = formate_datetime()
                    tmp['cnt_recall_latest'] = 0
            save_list.append(tmp)

        if forget_list:
            res = callback_engine.memory_forget(forget_list)
            if not res:
                continue

        logger.info(f"记忆遗忘刷新--")
        mem0_helper.es_udpate_bulk(save_list)

    logger.info(f"记忆遗忘刷新-完成")


def memory_delete():
    """记忆删除"""
    logger.info(f"记忆删除刷新-开始")
    mem0_helper = Mem0Helper()
    three_months_ago = formate_datetime(dt=datetime.now()-timedelta(days=30*3))
    logger.info(f"记忆删除刷新-three_months_ago:{three_months_ago}")

    body = {
        "query": {
            "bool": {
                "must": [
                    {
                        "bool": {
                            "should": [
                                {
                                    "range": {
                                        "metadata.cnt_recall_latest": {
                                            "lt":  1
                                        }
                                    }
                                },
                                {"bool": {"must_not": {"exists": {"field": "metadata.cnt_recall_latest"}}}}
                            ],
                            "minimum_should_match": 1
                        }
                    },
                    {
                        "range": {
                            "metadata.forgeted_at": {
                                "lt": three_months_ago
                            }
                        }
                    }
                ],
                "must_not": [
                    {
                        "term": {
                            "metadata.status": {
                                "value":  2
                            }
                        }
                    }
                ]
            }
        }}
    callback_engine = Engine()
    count = 0
    for data_list in mem0_helper.query_es_metadata(body=body):
        save_list = [
            {'id': item['_id'],
             'status': VectorStatus.DELETED,
             "updated_at": formate_datetime(),
             "deleted_at": formate_datetime()
             }
            for item in data_list
        ]
        logger.info(f"记忆删除刷新-{save_list}")
        res = callback_engine.memory_delete([x['id'] for x in save_list])
        if res:
            mem0_helper.es_udpate_bulk(save_list)
            count += len(save_list)

    logger.info(f"记忆删除刷新-完成:{count}")


def memory_scheduler():
    from src.memory.redis_lock import RedisLock
    key = "memory:scheduler:"+hashlib.md5(AGENT_PLUGIN_EXECUTE_URL.encode()).hexdigest()
    lock = RedisLock(key=key)
    if lock.acquire():
        logger.info(f"定时任务开始--{key}")
        memory_forget()
        memory_delete()
        upload_metrics()
    else:
        logger.info(f"定时任务lock--{key}")
    lock.release()


def run_memory_scheduler():
    logger.info(f"memory定时任务注册--")
    scheduler = BackgroundScheduler()
    scheduler.add_job(memory_scheduler, 'cron', day_of_week='0-6', hour=1, minute=0, second=0)
    scheduler.start()


if __name__ == "__main__":
    memory_scheduler()
```

> **面试要点**
> 
> 1. **记忆三层架构**: API 层 \(`memory_vector.py`\) → 业务层 \(`mem0_helper.py` 的 `Mem0Helper`/`Engine`\) → 存储层 \(Elasticsearch, `vector_store`\)，通过 Huey 异步任务解耦耗时操作（写入/冲突判定/权重更新均走队列）。
> 
> 2. **记忆状态机**: `VectorStatus` 枚举 NORMAL=0 → FORGETED=1 → DELETED=2，遗忘/删除定时任务扫描时一律排除 `status=2` 的已删除记忆。
> 
> 3. **艾宾浩斯遗忘曲线**: `Calculator.ebbinghaus_decay` 用 `B=0.008941`，衰减比 `exp(-B*t)/exp(-B*(t-1))`（t 为距今天数），权重随遗忘天数指数衰减；首次遗忘时记录 `forgeted_at` 并清零 `cnt_recall_latest`，此后每 90 天重置一次遗忘计时。
> 
> 4. **记忆冲突判定** \(`memory_conflict`\): 按 `user_id`/`team_id` \+ `classification.keyword` 精确匹配同分类历史记忆，排除 `status=2`；LLM 判定是"重复的记忆"还是"冲突的记忆"，重复则 `curr_weight = max(重复组权重)` 后 `+curr_weight*0.2` 升权，冲突则对历史记忆 `sub_weight(0.5)` 降权；结果写回 ES \(`es_udpate_bulk`\)。
> 
> 5. **混合检索公式** \(`query_memory_es_mix`\): `script_score` 计算余弦相似度并归一化为 `(sim+1)/2`，同时计算 `keywordScore = _score/max_keyword_score`，最终 `score = sim*0.2 + keywordScore*0.8`；`add_weight` 排序用 `sort_score = _score*(weight/max_weight)`，让"权重高 \+ 语义近"的记忆优先。
> 
> 6. **召回升权与复活**: `query_memory` 每次命中 \+0\.01 `add_weight`；若记忆处于 FORGETED 状态被再次召回，则提升为 NORMAL 并通过 `Engine().memory_forget_rollback` 回滚遗忘，形成"遗忘\-召回\-复活"闭环。
> 
> 7. **Java 引擎回调** \(`Engine`\): `memory_forget`/`memory_delete`/`memory_forget_rollback` 均 POST 到 `AGENT_PLUGIN_EXECUTE_URL` 对应接口，`@retry(stop_max_attempt_number=3)` 三次重试，校验返回 `code=="10000"` 才算成功；`conflict_agent` 通过 `variables.oldMemory` 携带历史记忆给 LLM 做冲突判定。
> 
> 8. **RedisLock 防重**: `memory_scheduler` 的锁 key 为 `"memory:scheduler:"+md5(AGENT_PLUGIN_EXECUTE_URL)`，多副本部署时仅一个实例能拿到锁执行每日 1 点的遗忘/删除/指标上报任务。
> 
> 9. **MyMemory 三处 override**: 多节点 ES hosts、`_search_vector_store` 移除 `user_id` 过滤（记忆跨用户检索）、`_create_memory` 用 metadata 中的 `id` 保证与业务侧一致、`_update_memory` 使用 Asia/Shanghai 时区时间戳。
> 
> 

## **RAGAS 评估模块 \(src/eval\)**

### **12\.1 模块概览与核心文件清单**

**模块职责**: 基于 RAGAS 开源框架对 RAG 问答系统进行离线评估，包含答案正确性、答案相关性、上下文召回率、忠实度等 7 项指标。

**核心文件清单** \(全部 ★核心\):

```Plain Text
src/eval/
├── ragas_eval.py       ★ 171 行 —— RAGASEval 评估器 (指标动态导入/并行评估/结果字典化)
├── ragas_handlers.py   ★ 95 行  —— RagasEvaluation Flask REST 接口 (请求解析/预处理/响应)
├── base_eval.py        —— 评估基类 (llm/embedding 配置)
```

**RAGAS 评估流程**:

```Plain Text
POST /rag_algorithm/ragas_evaluate
    ↓  (question/contexts/answer/ground_truth + llm/embedding 配置 + metrics)
RagasEvaluation.post (请求参数校验 + str_preprocessing 预处理)
    ↓
RAGASEval (指标白名单过滤 → 动态导入 ragas.metrics → RunConfig 并行执行)
    ↓
evaluate(...) → to_pandas() → get_res_dict() (NaN → -1)
    ↓
返回 {code:0, result: {指标名: 分数}}
```

### **12\.2 RAGAS 评估器 \(src/eval/ragas\_eval\.py\)**

**文件**: `src/eval/ragas_eval.py` \(171 行\) ★核心

- 默认 LLM `"qwen1.5-7b"`，默认 embedding `"embedding-peg"`

- `all_metrics` 7 项指标 / `default_metrics` 4 项默认指标 \(answer\_correctness, answer\_similarity, context\_recall, faithfulness\)

- 指标通过 `importlib.import_module("ragas.metrics")` \+ `getattr` 动态加载

- `RunConfig(max_workers=4, max_retries=1, max_wait=600, thread_timeout=600)` 并行评估

- `get_res_dict` 将 `np.nan` 分数转换为 `-1`

**完整源码**:

```Python
import importlib
from typing import List, Optional, Dict

import numpy as np
from datasets import Dataset
from ragas import evaluate, RunConfig

from src.eval.base_eval import BaseEval


class RAGASEval(BaseEval):
    def __init__(
        self,
        llm_name: str = "qwen1.5-7b",
        llm_url: str = "",
        embedding_name: str = "embedding-peg",
        embedding_url: str = "",
        metrics: Optional[List[str]] = None,
        **kwargs,
    ) -> None:
        """
        初始化 RAGASEval
        :param llm_name: 要调用的 llm 模型名称
        :param llm_url: 要调用的 llm 模型接口
        :param embedding_name: 要调用的 embedding 模型名称
        :param embedding_url: 要调用的 embedding 模型名称
        :param metrics: 评估指标 list，可选项见 all_metrics，可以为 None, 可以为空列表，可以多选
        """
        super().__init__()

        all_metrics = [
            "answer_correctness",
            "answer_relevancy",
            "answer_similarity",
            "context_precision",
            "context_recall",
            "context_relevancy",
            "faithfulness",
        ]
        default_metrics = [
            "answer_correctness",
            "answer_similarity",
            "context_recall",
            "faithfulness",
        ]
        self.set_llm(llm_name, llm_url, **kwargs)
        self.set_embedding(embedding_name, embedding_url, **kwargs)
        if metrics:
            assert isinstance(
                metrics, list
            ), f"type of parameter 'metrics' is not list."
            not_supported_ = [m for m in metrics if m not in all_metrics]
            assert (
                len(not_supported_) == 0
            ), f"not supported metric: {', '.join(not_supported_)} "
            self.metric_names = metrics
        else:
            self.metric_names = default_metrics
        self.metrics = list()
        for metric_name in self.metric_names:
            package = importlib.import_module("ragas.metrics")
            self.metrics.append(getattr(package, metric_name))

        self.metric_res = None

    def eval(self, dataset: Dict[str, List]) -> None:
        """
        传入数据集开始评估
        :param dataset: 字典，包含四个字段："question", "contexts", "answer" and "ground_truth"
                        每个字段的 value 为一个 list，长度相同
        """
        dataset = Dataset.from_dict(dataset)
        run_config = RunConfig(
            max_workers=4, max_retries=1, max_wait=600, thread_timeout=600
        )
        self.metric_res = evaluate(
            dataset=dataset,
            llm=self.llm,
            embeddings=self.embedding,
            metrics=self.metrics,
            run_config=run_config,
            raise_exceptions=False,
        ).to_pandas()

    def get_res_dict(self):
        """返回转换为字典的结果"""
        res = self.metric_res[self.metric_names].to_dict(orient="list")
        for k, v in res.items():
            for i, score in enumerate(v):
                if np.isnan(score):
                    v[i] = -1
        return res

    def save_res(self, save_path):
        self.metric_res.to_excel(save_path)

    def get_res(self):
        return self.metric_res


def main():
    import pandas as pd

    pd.set_option("display.max_columns", 50)
    pd.set_option("display.max_rows", 50)
    pd.set_option("display.width", 200)

    question = ["刘德华老婆是谁"]
    context = [
        [
            "如果刘德华是一道光，照亮了这个娱乐圈，那么刘德华妻子朱丽倩就是这道光后边的影子，用自己的暗衬托的的这道光更加明亮。",
            "1984年，朱丽倩参加马来西亚槟城的“新潮小姐”选美获得季军，之后赴香港学美容；1985年至1987年间做过平面模特；2008年6月23日，与刘德华在拉斯维加斯注册结婚；2012年5月9日，朱丽蒨在香港养和医院产下女儿刘向蕙",
            "朱丽蒨（Carol），1966年4月6日出生于马来西亚槟城，祖籍福建诏安，马来西亚选美小姐、平面模特。",
        ]
    ]
    answer = ["刘德华的老婆是朱丽倩"]
    ground_truth = ["朱丽倩"]
    data = {
        "question": question,
        "contexts": context,
        "answer": answer,
        "ground_truth": ground_truth,
    }
    for key in data:
        data[key].append(data[key][0])
    print(data)
    tool = RAGASEval(
        metrics=[
            "faithfulness",
            "answer_relevancy",
            "context_recall",
            "context_precision",
            "context_relevancy",
            "answer_similarity",
            "answer_correctness",
        ],
        llm="Claude35ChatModel",
    )
    tool.eval(data)
    res = tool.get_res_dict()
    print(res)


if __name__ == "__main__":
    main()
```

### **12\.3 RAGAS 评估 HTTP 接口 \(src/eval/ragas\_handlers\.py\)**

**文件**: `src/eval/ragas_handlers.py` \(95 行\) ★核心

- 对外暴露 `POST /rag_algorithm/ragas_evaluate`，挂在 Flask 主应用上

- 使用 `reqparse.RequestParser` 严格校验：question/answer/ground\_truth 必填、`contexts`/`metrics` 为 `type=list, location="json"`、`parameters` 为 dict

- `str_preprocessing` 将字符串中的双引号 `"` 统一替换为单引号 `'`，避免干扰 LLM 指令

- 返回格式: 每个指标取首个分数，异常统一返回 code:\-1

**完整源码**:

```Python
from flask import request
from flask_restful import Resource, reqparse

from src.component.server_component.LogManager import logger
from src.eval.ragas_eval import RAGASEval


def str_preprocessing(x):
    return str(x).replace('"', "'")


class RagasEvaluation(Resource):

    def post(self):
        parser = reqparse.RequestParser()
        parser.add_argument("question", required=True, nullable=False)
        parser.add_argument(
            "contexts", type=list, location="json", required=True, nullable=False
        )
        parser.add_argument("answer", required=True, nullable=False)
        parser.add_argument("ground_truth", required=True, nullable=False)
        parser.add_argument("llm_type", required=True, nullable=False)
        parser.add_argument("llm_url", required=True, nullable=False)
        parser.add_argument("embedding_type", required=True, nullable=False)
        parser.add_argument("embedding_url", required=True, nullable=False)
        parser.add_argument(
            "metrics",
            type=list,
            location="json",
            default=[
                "answer_correctness",
                "answer_similarity",
                "context_recall",
                "faithfulness",
            ],
        )
        parser.add_argument("parameters", type=dict, location="json", default={})
        req_args = parser.parse_args()

        valid_metrics = [
            "answer_correctness",
            "answer_relevancy",
            "answer_similarity",
            "context_precision",
            "context_recall",
            "context_relevancy",
            "faithfulness",
        ]
        req_args["metrics"] = [_ for _ in req_args["metrics"] if _ in valid_metrics]
        eval_data = {
            "question": [str_preprocessing(req_args.get("question"))],
            "contexts": [[str_preprocessing(_) for _ in req_args.get("contexts", [])]],
            "answer": [str_preprocessing(req_args.get("answer"))],
            "ground_truth": [str_preprocessing(req_args.get("ground_truth"))],
        }

        try:
            rag_eval = RAGASEval(
                llm_name=req_args.get("llm_type"),
                llm_url=req_args.get("llm_url"),
                embedding_name=req_args.get("embedding_type"),
                embedding_url=req_args.get("embedding_url"),
                metrics=req_args.get("metrics"),
                org_code=request.headers.get("Org-Code"),
            )
            rag_eval.eval(eval_data)
            eval_result = rag_eval.get_res_dict()
            logger.info(f"ragas evaluate result: {eval_result}")
            return {
                "code": 0,
                "msg": "",
                "result": {
                    metrics: metrics_values[0]
                    for metrics, metrics_values in eval_result.items()
                    if metrics_values and isinstance(metrics_values, list)
                },
            }
        except Exception as e:
            logger.error(f"ragas evaluate error: {str(e)}")
            return {
                "code": -1,
                "msg": "Document Q&A evaluation interface error",
                "result": {},
            }
```

> **面试要点**
> 
> 1. **7 项评估指标**: answer\_correctness\(答案正确性\)、answer\_relevancy\(答案相关性\)、answer\_similarity\(答案相似度\)、context\_precision\(上下文精确率\)、context\_recall\(上下文召回率\)、context\_relevancy\(上下文相关性\)、faithfulness\(忠实度\)；默认只跑其中 4 项，接口侧通过 `valid_metrics` 白名单过滤传入指标。
> 
> 2. **动态指标加载**: 不显式 import 每个指标类，而是 `importlib.import_module("ragas.metrics")` 后 `getattr(package, metric_name)` 按名称取指标对象，新增指标无需改代码。
> 
> 3. **并行评估配置**: `RunConfig(max_workers=4, max_retries=1, max_wait=600, thread_timeout=600)` 控制 RAGAS 内部线程池，`raise_exceptions=False` 保证单条失败不影响整体。
> 
> 4. **LLM/Embedding 可插拔**: llm\_name/llm\_url、embedding\_name/embedding\_url 均可由接口参数指定，支持 Claude35ChatModel 等自定义模型类；`set_llm`/`set_embedding` 来自 `base_eval.BaseEval`。
> 
> 5. **数据预处理**: `str_preprocessing` 把双引号替换为单引号，避免 LLM 指令与中文文本中的引号混淆；数据集经 `Dataset.from_dict` 转为 HuggingFace 格式。
> 
> 6. **结果归一化**: `get_res_dict` 把 RAGAS 返回的 `np.nan` 分数统一替换为 `-1`，防止 JSON 序列化失败；接口层每个指标取列表首个分数。
> 
> 

## **网页与飞书解析模块**

### **13\.1 模块概览**

网页/飞书解析是 RAG 服务的"内容入口"之一，负责把互联网网页、政府网站、飞书云文档等外部内容抓取并转换为统一的分块结构（`PowerAgentTextNode` → `chunks`），供后续向量化/检索使用。模块支持三套网页抓取通道 \+ 一套飞书文档通道：

```Plain Text
网页抓取通道
├── 通道一：自研 requests + BeautifulSoup（web_parsing）——解析标题/段落/图片/表格
├── 通道二：Jina Reader（r.jina.ai/{url}）——返回 Markdown，支持选择器移除
└── 通道三：Firecrawl v2 爬虫（/scrape 单页、/crawl 子页）——异步任务 + 轮询状态
飞书文档通道
└── Feishu blocks → Markdown（BlockType 枚举 + 树构建 + 递归渲染）
    └── 图片下载 → S3 上传 → ![Image](s3_url)
```

**核心文件清单**

```Plain Text
src/web_parse/parse_url.py          ★ 975 行   抓取与解析核心（URLParser/自研/Jina/Firecrawl）
src/web_parse/parse_manager.py      ★ 350 行   网页解析 REST API 层（ParseWeb/CrawlJinaReader/CrawlFire）
src/feishu_parse/feishu2markdown.py ★ 399 行   飞书 blocks → Markdown 转换器（BlockType 枚举）
src/feishu_parse/get_docdetail.py   ★ 309 行   飞书文档详情解析（inner/outer 双通道 + 异步回调）
src/feishu_parse/get_token.py       ★ 125 行   飞书 tenant_access_token（inner 随机 / outer 调接口 + 双向缓存）
src/feishu_parse/get_docinfo.py               fetch_doc_info_via_inner/outer 获取文档标题
src/feishu_parse/get_documentID.py            extract_document_id 从 URL 提取 document_id
src/feishu_parse/api_client.py                OpenApiClient 飞书开放平台客户端封装
src/feishu_parse/response_model.py            error_response / success_response 统一响应
```

### **13\.2 网页抓取与解析核心源码（parse\_url\.py）**

`src/web_parse/parse_url.py` 是网页抓取的核心，包含 URL 校验、HTML 抓取（带编码探测与 30 次重试）、Jina Reader、Firecrawl v2 爬虫、以及统一的 `load_data` 系列分块函数。文件末尾约 200 行是被注释掉的 Firecrawl 示例响应数据（重庆市人民政府网页内容），非功能代码，此处省略，以 `# ===== 省略注释掉的示例响应数据 =====` 标记。

```Python
import datetime
import re
import sys
import uuid

import requests
from bs4 import BeautifulSoup
from typing import Dict, Optional, List, Any
import time

from src.component.chunk.chunknode import PowerAgentTextNode
from src.component.parser.ocr_parser import upload_s3_image
from src.component.server_component.LogManager import logger
from src.server_api_tools.s3helper import S3Helper

FIRECRAWL_API_BASE = "https://api.firecrawl.dev/v2"


def validate_url(url) -> bool:
    """
    验证 URL 是否合法

    :return: 返回 True 表示合法，False 表示不合法
    """
    # 使用正则表达式验证URL格式
    is_valid = bool(url and re.match(r'^https?://', url))
    if is_valid:
        logger.info(f"URL 验证通过: {url}")
    else:
        logger.warning(f"URL 验证失败: {url}")
    return is_valid


def get_html(url):
    max_retries = 30
    retries = 0
    response = None
    while retries < max_retries:
        try:
            # 清除 sys.modules 中的 'requests' 缓存
            if 'requests' in sys.modules:
                logger.info(f"正在删除 sys.modules 中的 'requests' 缓存")
                del sys.modules['requests']

            import requests
            logger.info("requests 模块已重新导入")

            logger.info(f"开始请求网页内容: {url}")
            headers = {
                'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0'
            }
            response = requests.get(url, headers=headers, timeout=10, verify=False)
            response.raise_for_status()
            logger.info("网页内容请求成功")

            soup = BeautifulSoup(response.text, 'html.parser')
            # 查找所有的 <meta> 标签
            meta_tags = soup.find_all('meta')
            encode = None
            # 遍历所有 <meta> 标签，查找 charset 属性
            for tag in meta_tags:
                # 如果存在 charset 属性
                if tag.get('charset'):
                    encode = tag.get('charset').upper()  # 返回 charset 属性值并转为大写

                # 检查 http-equiv="Content-Type" 的 meta 标签（老旧方式）
                if tag.get('http-equiv') == 'Content-Type':
                    content = tag.get('content', '')
                    match = re.search(r'charset=([a-zA-Z0-9\-]+)', content)
                    if match:
                        encode = match.group(1).upper()
            if encode == None:
                encode = "UTF-8"
            logger.info(f"编码方式为{encode}")
            response.encoding = encode
            html_content = response.text
            return html_content  # 如果请求成功，返回网页内容

        except requests.RequestException as e:
            retries += 1
            logger.error(f"请求网页失败: {str(e)}，这是第 {retries} 次重试")
            if retries >= max_retries:
                logger.error(f"超过最大重试次数 ({max_retries} 次)，请求失败")
                raise RuntimeError(f"超过最大重试次数 ({max_retries} 次)，请求失败")
            # time.sleep(2 ** retries)  # 使用指数回退增加每次重试之间的间隔
            # time.sleep(1)

        finally:
            # 显式关闭连接，确保 response 已定义
            if response:
                response.close()
                logger.info("连接已关闭")


def fetch_with_jina(
        url: str,
        api_key: str,
        timeout: int = 100,
        remove_selector: Optional[str] = None,
        target_selector: Optional[str] = None,
) -> str:
    api_url = f"https://r.jina.ai/{url}"

    headers = {
        "Authorization": f"Bearer {api_key}"
    }

    # 动态加入可选参数
    if remove_selector:
        headers["X-Remove-Selector"] = remove_selector
    if target_selector:
        headers["X-Target-Selector"] = target_selector

    response = requests.get(api_url, headers=headers, timeout=timeout, verify=False)

    if response.status_code == 200:
        return response.text
    else:
        raise Exception(f"请求失败: {response.status_code} - {response.text}")


def web_parsing(url: str, selector: Optional[str] = None) -> List[Dict]:
    """
    解析指定网页的内容，支持通过选择器自定义提取特定区域（如标题、段落、图片、表格等）。

    参数:
        url (str): 需要解析的网页 URL。
        selector (str, 可选): 自定义的 CSS 类选择器。

    返回:
        List[Dict]: 解析后的网页内容，按类型分块组织。
    """
    try:
        if not validate_url(url):
            raise ValueError(f"URL 不合法: {url}")

        logger.info(f"开始解析网页: {url}, 使用选择器: {selector}")
        html_content = get_html(url)
        logger.info("成功获取网页内容")

        # 使用 BeautifulSoup 解析 HTML
        soup = BeautifulSoup(html_content, 'html.parser')
        parsed_contents = []
        parsed_content = {
            "标题": [],
            "段落": [],
            "图片": [],
            "表格": []
        }

        # 提取网页标题内容
        page_title = soup.title.get_text(strip=True) if soup.title else url
        parsed_content["标题"].append(page_title)
        logger.info(f"提取页面标题: {page_title}")

        # 如果传入选择器，则根据该选择器提取内容
        if selector:
            logger.info(f"使用选择器提取内容: {selector}")
            elements = soup.select(selector)
        else:
            logger.info("未提供选择器, 默认提取整个页面内容")
            elements = soup.find_all(True)
        logger.info(f"提取到 {len(elements)} 个元素")

        elements = soup.find_all(True)
        parsed_content["段落"].append({
            "text": soup.get_text(separator="\n", strip=True),
        })
        logger.info(f"已提取段落文字")

        # 解析元素并按类型分类
        for element in elements:
            if element.name == 'img':
                parsed_content["图片"].append({
                    "src": element.get('src'),
                    "alt": element.get('alt', ''),
                    "position": str(element.sourceline)
                })
                logger.info(f"提取图片: src={element.get('src')}, alt={element.get('alt', '')}")

            elif element.name == 'table':
                table_data = []
                rows = element.find_all('tr')
                for row in rows:
                    cols = row.find_all(['td', 'th'])
                    cols_text = [col.get_text(strip=True) for col in cols]
                    table_data.append(cols_text)
                parsed_content["表格"].append({
                    "data": table_data,
                    "position": str(element.sourceline)
                })
                logger.info(f"提取表格, 行数: {len(rows)}")

        parsed_contents.append(parsed_content)
        logger.info(f"解析完成, 解析结果: {parsed_content}")
        return parsed_contents
    except Exception as e:
        logger.error(f"提取文本失败: {e}")
        raise RuntimeError(f"提取文本失败: {e}")


def jinareader_parsing(url: str, api_key: str, parameters: Optional[Dict] = None) -> Dict:
    remove_selector = parameters.get("remove_selectors", None)
    target_selector = parameters.get("target_selectors", None)
    timeout = parameters.get("timeout", 100)

    try:
        jinareader_parsing_res = fetch_with_jina(url, api_key, timeout, remove_selector, target_selector)
        # ===== 此处省略注释掉的 Jina 返回示例数据约 70 行（习近平会见葡萄牙总理蒙特内格罗）=====

        # 正则匹配 Title 行（单行）
        title_match = re.search(r'^\s*Title\s*:\s*(.+?)\s*$', jinareader_parsing_res,
                                flags=re.IGNORECASE | re.MULTILINE)
        # 正则匹配 URL Source 行（单行）
        url_match = re.search(r'^\s*URL\s*Source\s*:\s*(.+?)\s*$', jinareader_parsing_res,
                              flags=re.IGNORECASE | re.MULTILINE)
        # Markdown Content 标签所在位置（标签可能在单独一行，后面跟真正的 markdown）
        md_start = re.search(r'^\s*Markdown\s*Content\s*:\s*$', jinareader_parsing_res,
                             flags=re.IGNORECASE | re.MULTILINE)
        title = title_match.group(1).strip() if title_match else None
        url = url_match.group(1).strip() if url_match else None

        if md_start:
            # 从 Markdown Content 标签之后的文本（保留原始换行）
            md = jinareader_parsing_res[md_start.end():].strip()
        else:
            # 没有 Markdown Content 标签，尝试把 URL Source 之后的剩余文本当作 Markdown
            if url_match:
                md = jinareader_parsing_res[url_match.end():].lstrip('\r\n')
            else:
                # 极端情况：既没有标签也没有 URL，整个文本视为 Markdown 内容
                md = jinareader_parsing_res

        # 保留原有换行类型（统一用 '\n' 处理）
        lines = md.splitlines()

        # 删除开头的空白行
        start = 0
        while start < len(lines) and lines[start].strip() == "":
            start += 1

        # 删除结尾的空白行
        end = len(lines) - 1
        while end >= 0 and lines[end].strip() == "":
            end -= 1

        # 取中间内容
        trimmed_lines = lines[start:end + 1]

        # 可选：对首尾实际内容行做左右 strip（去掉行首行尾的空格），如果不需要可注释掉下面两行
        trimmed_lines[0] = trimmed_lines[0].strip()
        trimmed_lines[-1] = trimmed_lines[-1].strip()

        markdown_content = "\n".join(trimmed_lines)

        res_json = {
            "Title": title,
            "URL Source": url,
            "Markdown_Content": markdown_content
        }
        return res_json

    except Exception as e:
        logger.error(f"提取文本失败: {e}")
        raise RuntimeError(f"提取文本失败: {e}")


def clean_payload(d: Dict[str, Any]) -> Dict[str, Any]:
    """递归删除 None 或 空字符串字段，避免发送 null 或空串导致 400"""
    out = {}
    for k, v in d.items():
        if v is None:
            continue
        if isinstance(v, str) and v.strip() == "":
            continue
        if isinstance(v, dict):
            c = clean_payload(v)
            if c:
                out[k] = c
        else:
            out[k] = v
    return out


def build_crawlfire_payload(url: str, parameters: Optional[Dict] = None) -> Dict:
    """
       返回符合 Firecrawl v2 的 payload（驼峰字段），
       只有当 crawl_subpages_enabled 为 True 时才包含 limit/maxDiscoveryDepth/includePaths/excludePaths。
    """
    logger.info("构建 payload，输入 url: %s，输入请求参数： %s", url, parameters)
    payload = {
        "url": url,
        "scrapeOptions": {
            "formats": ["markdown"],  # 只要 markdown（官方支持）
            "onlyMainContent": bool(parameters.get("only_main_content", True)),
        }
    }

    # 只有当 enabled=True 时加入子页相关的顶层字段（v2 接受 limit/maxDiscoveryDepth/includePaths/excludePaths）
    if bool(parameters.get("crawl_subpages_enabled", True)):
        if int(parameters.get("limit")) is not None:
            payload["limit"] = int(parameters.get("limit"))
        if int(parameters.get("max_depth")):
            payload["maxDiscoveryDepth"] = int(parameters.get("max_depth"))
        if list(parameters.get("exclude_paths")):
            payload["excludePaths"] = list(parameters.get("exclude_paths"))
        if list(parameters.get("include_paths")):
            payload["includePaths"] = list(parameters.get("include_paths"))
    logger.info("构建完成后的 payload: %s", payload)
    # 否则不加入这些字段（仅抓取起始 URL）
    return clean_payload(payload)


def submit_crawl_fire(api_key: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    """
    根据是否包含子页相关顶层字段选择 /crawl 或 /scrape。
    - /crawl: 需要 limit/maxDiscoveryDepth/includePaths/excludePaths 这些顶层字段（表示抓取子页）
    - /scrape: 单页抓取，使用 pageOptions 字段并在发送前把 pageOptions 展平（将 formats/onlyMainContent 移到顶层）
    """
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }

    # 判断是否应该走 /crawl（判断依据：任一子页字段存在）
    has_crawl_fields = any(k in payload for k in ("limit", "maxDiscoveryDepth", "excludePaths", "includePaths"))

    if has_crawl_fields:
        url = f"{FIRECRAWL_API_BASE}/crawl"
        logger.info("选择端点 /crawl（发现子页抓取相关字段）")
    else:
        url = f"{FIRECRAWL_API_BASE}/scrape"
        payload['formats'] = payload['scrapeOptions']['formats']
        payload['onlyMainContent'] = payload['scrapeOptions']['onlyMainContent']
        payload.pop('scrapeOptions')

        logger.info("选择端点 /scrape（单页抓取）")

    logger.info("请求 URL: %s", url)
    logger.info("发送 payload (清理后): %s", payload)

    start_ts = time.time()
    try:
        r = requests.post(url, json=payload, headers=headers, timeout=100, verify=False)
        elapsed = time.time() - start_ts
        logger.info(f"请求返回 status={r.status_code}, 耗时 {elapsed} 秒")

        # 尝试解析 JSON
        try:
            body = r.json()
        except Exception:
            body = r.text

        # 详细日志：若非 2xx，打印 body 便于诊断
        if not (200 <= r.status_code < 300):
            logger.error("Firecrawl 返回错误: status=%s, body=%s", r.status_code, body)
            # 如果是积分不足，给出明确提示
            if isinstance(body, dict) and body.get("error") and "Insufficient credits" in str(body.get("error")):
                raise RuntimeError("积分不足。请访问 https://firecrawl.dev/pricing 升级或减少请求量。")
            r.raise_for_status()
        else:
            logger.info("Firecrawl 响应 body: %s", body)

        return body if isinstance(body, dict) else {"raw": body}
    except requests.exceptions.RequestException as e:
        elapsed = time.time() - start_ts
        logger.info(f"请求 Firecrawl 发生网络/超时错误（耗时{elapsed}秒）: {e}")
        raise RuntimeError(f"请求 Firecrawl 发生网络/超时错误（耗时{elapsed}秒）: {e}")


def load_data(url: str, parse_res: Optional[Dict] = None, source_metadata: Optional[Dict] = None,
              trans_metadata: Optional[Dict] = None):
    logger.info(f"开始加载数据: url={url}获得的网页内容")
    if not source_metadata:
        source_metadata = dict()
    chunk_nodes = []
    now_time = datetime.datetime.now().strftime(r"%Y-%m-%d %H:%M:%S")
    location = {"type": "pages", "value": []}

    try:
        for page_num, page in enumerate(parse_res):
            page_label = page_num + 1
            location["value"].append(page_label)

            if not page['标题']:
                file_name = url
            else:
                file_name = page['标题'][0]

            trans_metadata['fileName'] = file_name
            metadata = {
                "file_name": file_name,
                "create_date": now_time,
                "parser_info": {
                    "parser": "web_parse",
                    "table_format": "MARKDOWN",
                    "ori_data": page,
                },
            }

            # page_text = get_page_text(page)
            img_res = ""
            for idx, img in enumerate(page["图片"], start=1):
                url = img.get("src")
                if not url:
                    print(f"[{idx}] 跳过：src 为空")
                    continue
                try:
                    resp = requests.get(url, timeout=10, verify=False)
                    resp.raise_for_status()
                    content = resp.content
                    s3_path = upload_s3_image(content)
                    logger.info(f"[图片{idx}] ：{s3_path}")
                    img_res = img_res + '\n' + f"[图片{idx}] ：{s3_path}"
                except requests.RequestException as e:
                    print(f"[{idx}] 下载失败 {url} → {e}")

            chunk_nodes.append(
                PowerAgentTextNode(
                    text=page['段落'][0]["text"] + "\n" + img_res,
                    metadata=metadata,
                    source_metadata=source_metadata,
                    location=location,
                )
            )
            logger.info(f"创建 chunk 节点: file_name={file_name}, page_label={page_label}")

        chunks = [ck.to_pa_dict() for ck in chunk_nodes]
        logger.info(f"数据加载完成, 共创建 {len(chunks)} 个 chunk 节点")
        return chunks, trans_metadata

    except Exception as e:
        logger.error(f"[load_data] 加载数据失败: {e}")
        raise RuntimeError(f"[load_data] 加载数据失败: {e}")


def load_data_jina(url: str, parse_res: Optional[Dict] = None, source_metadata: Optional[Dict] = None,
                   trans_metadata: Optional[Dict] = None):
    logger.info(f"开始加载数据: url={url}获得的网页内容")
    if not source_metadata:
        source_metadata = dict()
    chunk_nodes = []
    now_time = datetime.datetime.now().strftime(r"%Y-%m-%d %H:%M:%S")
    location = {"type": "pages", "value": []}

    try:
        location["value"].append(1)

        if not parse_res['Title']:
            file_name = url
        else:
            file_name = parse_res['Title']

        trans_metadata['fileName'] = file_name
        metadata = {
            "file_name": file_name,
            "create_date": now_time,
            "parser_info": {
                "parser": "web_parse_jinaReader",
                "table_format": "MARKDOWN",
                "ori_data": parse_res,
            },
        }

        chunk_nodes.append(
            PowerAgentTextNode(
                text=parse_res['Markdown_Content'],
                metadata=metadata,
                source_metadata=source_metadata,
                location=location,
            )
        )
        logger.info(f"创建 chunk 节点: file_name={file_name}")

        chunks = [ck.to_pa_dict() for ck in chunk_nodes]
        logger.info(f"数据加载完成, 共创建 {len(chunks)} 个 chunk 节点")
        return chunks, trans_metadata

    except Exception as e:
        logger.error(f"[load_data] 加载数据失败: {e}")
        raise RuntimeError(f"[load_data] 加载数据失败: {e}")


def load_data_crawl_fire(url: str, parse_res: Optional[Dict] = None, source_metadata: Optional[Dict] = None,
                         trans_metadata: Optional[Dict] = None):
    logger.info(f"开始加载数据: url={url}获得的网页内容")
    if not source_metadata:
        source_metadata = parse_res['metadata']
    chunk_nodes = []
    now_time = datetime.datetime.now().strftime(r"%Y-%m-%d %H:%M:%S")
    location = {"type": "pages", "value": []}
    try:
        location["value"].append(1)

        if not source_metadata['title']:
            file_name = url
        else:
            file_name = source_metadata['title'].strip()

        trans_metadata['fileName'] = file_name
        metadata = {
            "file_name": file_name,
            "create_date": now_time,
            "parser_info": {
                "parser": "web_parse_jinaReader",
                "table_format": "MARKDOWN",
                "ori_data": parse_res,
            },
        }

        chunk_nodes.append(
            PowerAgentTextNode(
                text=parse_res['markdown'],
                metadata=metadata,
                source_metadata=source_metadata,
                location=location,
            )
        )
        logger.info(f"创建 chunk 节点: file_name={file_name}")

        chunks = [ck.to_pa_dict() for ck in chunk_nodes]
        logger.info(f"数据加载完成, 共创建 {len(chunks)} 个 chunk 节点")
        return chunks, trans_metadata

    except Exception as e:
        logger.error(f"[load_data] 加载数据失败: {e}")
        raise RuntimeError(f"[load_data] 加载数据失败: {e}")


def load_data_crawl_fire2(parse_res: List[Optional[Dict]] = None, source_metadata: Optional[Dict] = None,
                          trans_metadata: Optional[Dict] = None):
    logger.info(f"开始加载数据")
    if not source_metadata:
        trans_metadata = dict()
    chunk_nodes = []
    now_time = datetime.datetime.now().strftime(r"%Y-%m-%d %H:%M:%S")
    location = {"type": "pages", "value": []}

    try:
        for page_num, page in enumerate(parse_res):
            if not source_metadata:
                source_metadata = page['metadata']
            page_label = page_num + 1
            location["value"].append(page_label)

            if not source_metadata['title']:
                file_name = source_metadata['sourceURL']
            else:
                file_name = source_metadata['title'].strip()

            trans_metadata['fileName'] = file_name
            metadata = {
                "file_name": file_name,
                "create_date": now_time,
                "parser_info": {
                    "parser": "web_parse",
                    "table_format": "MARKDOWN",
                    "ori_data": page,
                },
            }

            chunk_nodes.append(
                PowerAgentTextNode(
                    text=page['markdown'],
                    metadata=metadata,
                    source_metadata=source_metadata,
                    location=location,
                )
            )
            logger.info(f"创建 chunk 节点: file_name={file_name}, page_label={page_label}")

        chunks = [ck.to_pa_dict() for ck in chunk_nodes]
        logger.info(f"数据加载完成, 共创建 {len(chunks)} 个 chunk 节点")
        return chunks, trans_metadata

    except Exception as e:
        logger.error(f"[load_data] 加载数据失败: {e}")
        raise RuntimeError(f"[load_data] 加载数据失败: {e}")


class ParseUrls:
    """
    网址解析服务类，提供解析网页内容的功能。
    """

    @staticmethod
    def parse_url(url: str, selector: Optional[Dict] = None, source_metadata: Optional[Dict] = None,
                  trans_metadata: Optional[Dict] = None) -> list:
        """
        解析指定网址内容，支持通过选择器过滤特定区域。

        参数:
            url (str): 需要解析的网页 URL。
            selector (dict, 可选): 自定义的解析区域选择器。

        返回:
            list: 网页内容解析结果，分块结构化表示。
        """
        parse_res = web_parsing(url, selector)
        logger.info(f"网页解析完成, 提取到 {len(parse_res)} 页内容")
        chunks, trans_metadata = load_data(url, parse_res, source_metadata, trans_metadata)
        logger.info(f"数据加载成功")
        return chunks, trans_metadata


class url_CrawlJinaReader:
    """
    网址解析服务类，提供解析网页内容的功能。
    """

    @staticmethod
    def parse_url(url: str, api_key: str, parameters: Optional[Dict] = None, source_metadata: Optional[Dict] = None,
                  trans_metadata: Optional[Dict] = None) -> tuple[list[dict[str, Any]], dict | None]:
        parse_res = jinareader_parsing(url, api_key, parameters)
        logger.info(f"JinaReader解析完成, 提取到 {len(parse_res)} 页内容")

        chunks, trans_metadata = load_data_jina(url, parse_res, source_metadata, trans_metadata)
        logger.info(f"数据加载成功")
        return chunks, trans_metadata


class url_CrawlFire:
    """
    网址解析服务类，提供解析网页内容的功能。
    """

    @staticmethod
    def parse_url(url: str, api_key: str, parameters: Optional[Dict] = None, source_metadata: Optional[Dict] = None,
                  trans_metadata: Optional[Dict] = None) -> tuple[list[dict[str, Any]], dict | None, str | None]:
        payload = build_crawlfire_payload(url, parameters)
        result = submit_crawl_fire(api_key, payload)

        # result = {
        #     "success": True
        # }

        if result.get("success"):
            if parameters.get("crawl_subpages_enabled"):
                task_id = result.get('id', None)
                chunks = []
            else:
                # ===== 此处省略注释掉的 Firecrawl /scrape 示例响应数据约 30 行（重庆市人民政府首页 markdown）=====
                res_parse = result.get('data', None)
                chunks, trans_metadata = load_data_crawl_fire(url, res_parse, source_metadata, trans_metadata)
                task_id = str(uuid.uuid4())
        else:
            raise RuntimeError(f"爬取失败，失败原因：{result.get('error')}")
        return chunks, trans_metadata, task_id

    @staticmethod
    def query_status(task_id: str, api_key: str) -> Dict:
        status_url = f"{FIRECRAWL_API_BASE}/crawl/{task_id}"
        try:
            r = requests.get(status_url, headers={"Authorization": f"Bearer {api_key}"}, timeout=100, verify=False)
            r.raise_for_status()  # 请求异常会抛出 HTTPError
            result = r.json()
            logger.info(f"获取任务执行状态{result.get('status')}")
            # ===== 此处省略注释掉的 Firecrawl /crawl 状态示例响应数据约 150 行（scraping/completed 样例）=====

            # 断言返回中必须包含 status 字段
            assert "status" in result, "返回结果缺少 status 字段"
            if result.get("status") == "completed":
                parse_res = result.get("data")
                if parse_res:
                    chunks, trans_metadata = load_data_crawl_fire2(parse_res)

                    save_path = S3Helper.get_s3_config_save_path(
                        task_type="crawl_fire_url_parser", task_id=task_id
                    )
                    S3Helper().save_chunks_json(chunks, save_path)

                    return {
                        "code": 0,
                        "status": result.get("status"),
                        "msg": "crawl_fire 爬虫任务完成，获取结果",
                        "task_id": task_id,
                        "result_path": save_path,
                        "trans_metadata": trans_metadata
                    }
                else:
                    raise RuntimeError("解析结果为空")
            elif result.get("status") == "failed":
                logger.info(f"查询任务状态接口返回失败:{result}")
                raise RuntimeError(f"查询任务状态接口返回失败")
            else:
                res = {
                    "status": result.get("status"),
                    "completed:": result.get("completed", 0),
                    "total": result.get("total", 0)
                }

            return res

        except (requests.RequestException, AssertionError) as e:
            # 统一抛出 RuntimeError，外部捕获
            raise RuntimeError(f"查询任务状态调用失败: {e}")
```

> **面试要点**
> 
> 1. **双通道抓取架构**: 一套自研 `requests + BeautifulSoup`（`web_parsing`，解析标题/段落/图片/表格四类内容），一套 Jina Reader（`fetch_with_jina` → `r.jina.ai/{url}`），一套 Firecrawl v2（`submit_crawl_fire`），三者各有适用场景：自研适合纯静态页，Jina 输出干净 Markdown，Firecrawl 支持异步子页爬取与反爬。
> 
> 2. **编码探测**: `get_html` 从 `<meta charset>` 和 `http-equiv=Content-Type` 正则提取编码，默认 UTF\-8，设置 `response.encoding` 后重新取 `.text`，解决中文网页乱码；`del sys.modules['requests']` 后重新 import 是为绕过环境中 requests 的某些模块缓存问题，重试上限 30 次。
> 
> 3. **Jina 解析**: 用三个正则（`^\s*Title\s*:` / `URL Source` / `Markdown Content`，IGNORECASE\|MULTILINE）切分 Jina 返回文本，`md_start.end()` 之后即 Markdown 内容；对首尾空白行裁剪，保证干净文本。`X-Remove-Selector` / `X-Target-Selector` 头实现页面元素过滤。
> 
> 4. **Firecrawl 端点选择**: `submit_crawl_fire` 用 `any(k in payload for k in ("limit","maxDiscoveryDepth","excludePaths","includePaths"))` 判断走 `/crawl`（子页）还是 `/scrape`（单页，发送前把 `scrapeOptions` 展平到顶层）；"Insufficient credits" 专门提示积分不足。
> 
> 5. **异步轮询**: `crawl_subpages_enabled=True` 时 `/crawl` 立即返回 `task_id`，`query_status` 轮询 `/crawl/{task_id}`，`status=completed` → `load_data_crawl_fire2` 批量加载多页并落 S3，`failed` 抛 RuntimeError，其他状态返回 `{status, completed, total}` 进度。
> 
> 6. **load\_data 系列一致性**: 四种 `load_data`（自研/Jina/Firecrawl 单页/Firecrawl 多页）最终都构建 `PowerAgentTextNode`，写入 `parser_info.parser`（`web_parse`/`web_parse_jinaReader`），location 为 `{"type":"pages","value":[...]}`；图片走 `upload_s3_image` / `upload_image_bytes_to_s3` 转 S3 后以 `[图片N]：s3路径` 拼入正文。
> 
> 

### **13\.3 网页解析 API 层源码（parse\_manager\.py）**

`src/web_parse/parse_manager.py` 是网页解析的 REST 入口，暴露 6 个 Resource：子链接提取、通用 URL 解析（同步/异步回调）、JinaReader 解析、Firecrawl 爬取（含子页）、Firecrawl 状态查询、API Token 校验。

```Python
import uuid

import requests
from flask_restful import Resource
from flask import request
from src.component.server_component.LogManager import logger
from src.server_api_tools.s3helper import S3Helper
from src.web_parse import URLParser
from src.web_parse.parse_url import ParseUrls, url_CrawlJinaReader, url_CrawlFire


class ParseSubUrls(Resource):
    @staticmethod
    def post():
        """
        处理 POST 请求，根据用户提供的根地址和选择器，提取子链接。
        """
        try:
            # 接收并解析 JSON 请求数据
            data = request.get_json()
            website_root_address = data.get("websiteRootAddress")
            selectors = data.get("selector", [])
            limit = data.get("limit", 200)
            # 创建解析器实例
            parser = URLParser(website_root_address, selectors, limit)

            # 执行解析流程
            sub_urls = parser.parse()

            # 返回成功响应
            return {
                "code": "0",
                "message": "解析成功",
                "data": {
                    "sub_urls": sub_urls
                }
            }, 200

        except ValueError as ve:
            # 返回参数错误响应
            return {
                "code": "1",
                "message": str(ve)
            }, 400
        except Exception as e:
            # 如果是 URLParser 抛出的异常，获取其消息
            if isinstance(e, Exception):  # 捕获来自 URLParser 的异常
                return {
                    "code": "1",
                    "message": f"URL 解析失败: {str(e)}"
                }, 400
            else:
                # 返回服务器内部错误响应
                return {
                    "code": "1",
                    "message": f"服务器内部错误: {str(e)}"
                }, 400


class ParseWeb(Resource):
    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @staticmethod
    def post():
        """
        接口名称：网址url解析
        请求方式：POST
        """
        # 接收并解析 JSON 请求数据
        data = request.get_json()

        url = data.get("url")
        selector = data.get("selector", None)
        callback_url = data.get("callback_url", None)
        source_metadata = data.get("source_metadata", {})
        trans_metadata = data.get("trans_metadata", {})
        kwargs = data.get("kwargs", {})

        # 任务类型和唯一任务ID
        task_type = "url_parser"
        task_id = str(uuid.uuid4())

        # 日志记录请求参数
        logger.info(
            f"【task_id={task_id}】【url={url}】【callback_url={callback_url}】"
            f"【selector={selector}】【source_metadata={source_metadata}】"
            f"【trans_metadata={trans_metadata}】【kwargs={kwargs}】"
        )
        # 检查必填参数
        if not url:
            return {
                "code": 1,
                "msg": "参数缺失，url 为必填项",
                "task_id": task_id,
                "result_path": "",
                "result": [],
            }
        if not callback_url:
            """
            如果未提供回调地址，执行同步解析逻辑：
            - 调用解析方法提取内容
            - 将解析结果保存到 S3
            - 返回解析结果
            """
            try:
                chunks, trans_metadata = ParseUrls.parse_url(url, selector, source_metadata, trans_metadata)
                save_path = S3Helper.get_s3_config_save_path(
                    task_type=task_type, task_id=task_id
                )
                S3Helper().save_chunks_json(chunks, save_path)

                return {
                    "code": 0,
                    "msg": "解析成功",
                    "task_id": task_id,
                    "result_path": save_path,
                    "trans_metadata": trans_metadata
                }
            except Exception as e:
                import traceback

                traceback.print_exc()
                return {
                    "code": -1,
                    "msg": f"解析失败：{str(e)}",
                    "task_id": task_id,
                    "result_path": "",
                    "res_result": [],
                    "trans_metadata": trans_metadata
                }

        else:
            """
            如果提供了回调地址，执行异步解析逻辑：
            - 将任务信息传递给后台任务队列
            - 返回任务ID及队列信息
            """
            from src import tasks

            tasks.async_url_parsing(
                task_id=task_id,
                url=url,
                selector=selector,
                callback_url=callback_url,
                source_metadata=source_metadata,
                trans_metadata=trans_metadata,
                **kwargs,
            )

            return {
                "code": 0,
                "msg": "异步任务已提交",
                "task_id": task_id,
                "task_number_of_queue": -1,  # 不返回队列具体大小
            }


class CrawlJinaReader(Resource):
    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @staticmethod
    def post():
        """
        接口名称：使用JinaReader网址url解析
        请求方式：POST
        """
        # 接收并解析 JSON 请求数据
        data = request.get_json()
        logger.info("收到 jina-reader 请求, body=%s", data)
        if not data:
            return {"code": -1, "msg": "请求体必须为 JSON"}

        # 基础校验
        target_url = data.get("url")
        parameters = data.get("parameters", {})
        api_key = data.get("api_key")
        source_metadata = data.get("source_metadata", {})
        trans_metadata = data.get("trans_metadata", {})
        kwargs = data.get("kwargs", {})
        task_type = "jina_reader_url_parser"
        task_id = str(uuid.uuid4())

        try:
            chunks, trans_metadata = url_CrawlJinaReader.parse_url(target_url, api_key, parameters, source_metadata,
                                                                   trans_metadata)
            save_path = S3Helper.get_s3_config_save_path(
                task_type=task_type, task_id=task_id
            )

            S3Helper().save_chunks_json(chunks, save_path)

            return {
                "code": 0,
                "msg": "解析成功",
                "task_id": task_id,
                "result_path": save_path,
                "trans_metadata": trans_metadata
            }
        except Exception as e:
            import traceback

            traceback.print_exc()
            return {
                "code": -1,
                "msg": f"解析失败：{str(e)}",
                "task_id": task_id,
                "result_path": "",
                "res_result": [],
                "trans_metadata": trans_metadata
            }


class CrawlFire(Resource):
    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @staticmethod
    def post():
        """
        接口名称：使用JinaReader网址url解析
        请求方式：POST
        """
        # 接收并解析 JSON 请求数据
        data = request.get_json()
        logger.info("收到 crawl-fire 请求, body=%s", data)
        if not data:
            return {"code": -1, "msg": "请求体必须为 JSON"}

        # 基础校验
        target_url = data.get("url")
        parameters = data.get("parameters", {})
        api_key = data.get("api_key")
        source_metadata = data.get("source_metadata", {})
        trans_metadata = data.get("trans_metadata", {})
        kwargs = data.get("kwargs", {})
        task_type = "crawl_fire_url_parser"

        try:
            chunks, trans_metadata, task_id = url_CrawlFire.parse_url(target_url, api_key, parameters, source_metadata,
                                                                      trans_metadata)

            if parameters.get("crawl_subpages_enabled"):
                return {
                    "code": 0,
                    "msg": "crawl_fire方式爬取网页(包括子网页)任务提交成功",
                    "task_id": task_id,
                    "result_path": "",
                    "trans_metadata": trans_metadata
                }
            else:
                save_path = S3Helper.get_s3_config_save_path(
                    task_type=task_type, task_id=task_id
                )
                S3Helper().save_chunks_json(chunks, save_path)
                return {
                    "code": 0,
                    "msg": "解析成功",
                    "task_id": task_id,
                    "result_path": save_path,
                    "trans_metadata": trans_metadata
                }

        except Exception as e:
            import traceback

            traceback.print_exc()
            return {
                "code": -1,
                "msg": f"crawl_fire方式爬取网页任务提交失败：{str(e)}",
                "trans_metadata": trans_metadata
            }


class CrawlFireStatus(Resource):
    @staticmethod
    def get():
        return {"code": -1, "msg": "use post"}

    @staticmethod
    def post():
        data = request.get_json()
        logger.info("收到 crawl-fire 查询请求, body=%s", data)
        task_id = data.get("task_id")
        api_key = data.get("api_key")
        try:
            res = url_CrawlFire.query_status(task_id, api_key)
            return res
        except RuntimeError as e:
            return {
                "code": -1,
                "msg": f"crawl_fire方式获取爬取状态结果失败：{str(e)}",
            }


class AuthVerf(Resource):
    @staticmethod
    def get():
        way = request.args.get("way", type=str)
        api_token = request.args.get("api_token", type=str)
        # 校验 way 参数是否为空
        if not way or way.strip() == "" or not api_token or api_token.strip() == "":
            return {
                "code": -1,
                "msg": "参数 way 和 api_token 不能为空"
            }
        try:
            if way == "website_Firecrawl":
                test_url = "https://api.firecrawl.dev/v2/scrape"
                test_payload = {'url': 'https://www.cq.gov.cn/'}

                headers = {
                    "Authorization": f"Bearer {api_token}"
                }
                response = requests.post(test_url, json=test_payload, headers=headers, timeout=30, verify=False)
            else:
                test_url = "https://r.jina.ai/https://www.beijing.gov.cn/"

                headers = {
                    "Authorization": f"Bearer {api_token}"
                }
                response = requests.get(test_url, headers=headers, timeout=30, verify=False)
            logger.info(f"响应的状态码{response.status_code}")
            # logger.info(f"响应结果{response.text}")
            if response.status_code == 200:
                return {
                    "code": 0,
                    "status": "success",
                    "msg": "校验通过"
                }
            else:
                return {
                    "code": -1,
                    "status": "fail",
                    "msg": "校验失败"
                }
        except Exception as e:
            return {
                "code": -1,
                "status": "fail",
                "msg": f"校验失败：{str(e)}",
            }

    @staticmethod
    def post():
        return {"code": -1, "msg": "use post"}
```

---

# **13\.4 飞书文档解析 \(feishu\_parse\)**

## **13\.4\.1 模块职责**

`src/feishu_parse/` 负责把飞书文档解析成可入库的 chunk 数据，是文档解析链路中对办公协同软件的适配模块。支持 **内网代理通道 \(inner\)** 与 **飞书开放平台直连通道 \(outer\)** 双适配器：

```Plain Text
Feishu 文档 URL + token
    ↓
get_documentID.py   —— 从 doc_url 提取 document_id
    ↓
get_token.py        —— 获取/缓存 tenant_access_token (inner 生成随机 token / outer 调飞书接口)
    ↓
get_docdetail.py    —— 拉取 blocks + 文档标题 + 后台线程解析 + S3 保存 + 回调
    ↓
feishu2markdown.py  —— FeishuDoc2Markdown 将 blocks 递归渲染为 Markdown
    ↓
load_data()         —— 生成 PowerAgentTextNode chunk
    ↓
S3 保存 chunk json → 回调 cb_url
```

## **13\.4\.2 核心文件清单**

## **13\.4\.3 feishu2markdown\.py 核心源码（完整）**

### **BlockType 枚举（真实块类型值）**

> **注意**：以下为 `block_type` 的真实枚举值。常见误解是把 `quote` 当作 34、把无序列表当作 19——真实值是 `bullet=12, ordered=13, quote=15, callout=19, table=31, table_cell=32, quote_container=34`。
> 
> 

```Python
from enum import IntEnum

class BlockType(IntEnum):
    """
    定义飞书文档中所有可能的块类型 (block_type) 枚举，
    使后续判断更具可读性和可维护性。
    """
    page = 1  # 页面 Block，是文档的根节点
    text = 2  # 文本 Block
    heading1 = 3  # 一级标题 Block
    heading2 = 4  # 二级标题 Block
    heading3 = 5  # 三级标题 Block
    heading4 = 6  # 四级标题 Block
    heading5 = 7  # 五级标题 Block
    heading6 = 8  # 六级标题 Block
    heading7 = 9  # 七级标题 Block
    heading8 = 10  # 八级标题 Block
    heading9 = 11  # 九级标题 Block
    bullet = 12  # 无序列表 Block
    ordered = 13  # 有序列表 Block
    code = 14  # 代码块 Block
    quote = 15  # 引用 Block
    todo = 17  # 待办事项 Block
    bitable = 18  # 多维表格 Block（旧版）
    callout = 19  # 高亮块 Block（Callout）
    chat_card = 20  # 会话卡片 Block
    diagram = 21  # 流程图 & UML 图 Block
    divider = 22  # 分割线 Block
    file = 23  # 文件 Block
    grid = 24  # 分栏 Block
    grid_column = 25  # 分栏列 Block
    iframe = 26  # 内嵌 Block
    image = 27  # 图片 Block
    isv = 28  # 开放平台小组件 Block
    mindnote = 29  # 思维笔记 Block
    sheet = 30  # 电子表格 Block
    table = 31  # 表格容器 Block（新版）
    table_cell = 32  # 表格单元格 Block
    view = 33  # 视图 Block
    quote_container = 34  # 引用容器 Block
    task = 35  # 任务 Block
    okr = 36  # OKR Block
    okr_objective = 37  # OKR 目标 Block
    okr_key_result = 38  # OKR 关键结果 Block
    okr_progress = 39  # OKR 进展 Block
    add_ons = 40  # 新版文档小组件 Block
    jira_issue = 41  # Jira 问题 Block
    wiki_catalog = 42  # Wiki 子目录 Block
    board = 43  # 画板 Block
    agenda = 44  # 议程 Block
    agenda_item = 45  # 议程项 Block
    agenda_item_title = 46  # 议程项标题 Block
    agenda_item_content = 47  # 议程项内容 Block
    link_preview = 48  # 链接预览 Block
    undefined = 999  # 未支持 / 未知 Block
```

### **FeishuDoc2Markdown 转换器**

```Python
class FeishuDoc2Markdown:
    """
    将飞书文档的 blocks 转换为 Markdown 文本的转换器类。
    构造时传入 API 获取的 items（blocks 列表），
    然后调用 to_markdown() 方法获取最终 Markdown 字符串。
    """

    def __init__(self, items: list, fcid_open_client, adapter: str, tenant_access_token: str):
        # 构建树状结构：roots 为顶层块列表，children_map 为父ID到子块列表映射
        self.roots, self.children_map = self._build_tree(items)

        self.DOWNLOAD_FILE_PATH = "/open-apis/drive/v1/medias/{}/download"
        self.openapiPath_down = "feishu-msg/fcid-api/proxy-feishu/general/get/ej1msqcmw9vm"

        self.fcid_open_client = fcid_open_client
        self.adapter = adapter
        self.tenant_access_token = tenant_access_token
        self.FEUSHU_DOWNLOAD_FILE_PATH = "https://open.feishu.cn/open-apis/drive/v1/medias/{}/download"

    # 防止传入非法block_type值
    @staticmethod
    def safe_block_type(type_value: int) -> BlockType | None:
        try:
            return BlockType(type_value)
        except ValueError:
            return None

    @staticmethod
    def _build_tree(blocks: list):
        """
        构建父子映射树
        :param blocks: 飞书 API 返回的 blocks 数组
        :return: (roots, children_map)
        """
        id_map = {b['block_id']: b for b in blocks}  # 快速通过 block_id 查块
        children_map = defaultdict(list)  # 存父ID->子块列表
        roots = []  # 存顶层块（无 parent 或 parent 不在 id_map）
        for block in blocks:
            parent = block.get('parent_id')
            if parent and parent in id_map:
                children_map[parent].append(block)
            else:
                roots.append(block)
        return roots, children_map

    @staticmethod
    def _extract_text(elements: list) -> str:
        """
        从元素列表中提取并拼接所有文本：
        - text_run：直接取 content
        - mention_doc：渲染成 [标题](URL)
        :param elements: e.g. [{'text_run': {'content': 'Hello'}},
                              {'mention_doc': {'title': 'Title', 'url': 'https://…'}}]
        :return: 拼接后纯文本或 Markdown 链接
        """
        parts = []
        for el in elements:
            if 'text_run' in el:
                tr = el['text_run']
                style = tr.get('text_element_style', {})
                # 如果没有划掉（strikethrough=False），才把 content 加入 parts
                if not style.get('strikethrough', False):
                    parts.append(tr.get('content', ''))
            elif 'mention_doc' in el:
                doc = el['mention_doc']
                title = doc.get('title', '')
                url = doc.get('url', '')
                # 如果你希望把标题和 URL 用 Markdown 形式返回：
                parts.append(f"[{title}]({url})")
        return ''.join(parts)

    def _render_table_old(self, block: dict) -> str:
        """
        渲染旧版 bitable（block_type==15）块为 Markdown 表格
        """
        rows = self.children_map.get(block['block_id'], [])
        table_data = []
        for row in rows:
            if row['block_type'] == 17:  # bitable 行
                cells = [self._extract_text(c['elements']) for c in row.get('cells', [])]
                table_data.append(cells)
        if not table_data:
            return ''
        header, *data_rows = table_data
        sep = ['---'] * len(header)
        lines = ['| ' + ' | '.join(header) + ' |',
                 '| ' + ' | '.join(sep) + ' |']
        for dr in data_rows:
            lines.append('| ' + ' | '.join(dr) + ' |')
        return '\n'.join(lines) + '\n'

    @staticmethod
    def upload_image_bytes_to_s3(image_bytes: bytes) -> str | None:
        """
        将字节流（如 response.content）转换为图片对象后上传 S3，并返回路径
        """
        try:
            logger.info("准备上传图片字节流到 S3")

            # 使用 BytesIO 包装字节流
            bytes_io = BytesIO(image_bytes)

            # 尝试打开为图片
            img = Image.open(bytes_io)
            logger.info(f"图片格式识别为: {img.format}")

            # 随机生成文件名
            random_file_id = hex(int(time.time()))[2:] + "".join(
                random.choices(string.ascii_letters + string.digits, k=4)
            )
            file_name = f"{random_file_id.lower()}.{img.format.lower()}"
            file_path = os.path.join(os.path.dirname(__file__), file_name)

            # 保存临时图片文件
            img.save(file_path)
            logger.info(f"临时文件保存路径: {file_path}")

            # 拼接 S3 路径并上传
            s3_path = settings.S3_IMAGE_SAVE_PATH + file_name
            S3Helper().upload_single_file(file_path, s3_path)
            logger.info(f"图片已上传至 S3: {s3_path}")

            # 删除本地临时文件
            os.remove(file_path)
            logger.info("本地临时文件已删除")

            return s3_path

        except Exception as e:
            logger.exception(f"上传图片到 S3 失败: {e}")
            return None

    def _render_block(self, block: dict, depth: int = 0) -> str:
        """
        递归渲染单个块及其所有子块为 Markdown
        :param block: 当前处理的块
        :param depth: 列表缩进等级
        :return: 该块及孩子块的 Markdown 字符串
        """
        md = []
        indent = '  ' * depth
        t = self.safe_block_type(block['block_type'])
        if t is None:
            return ''

        # —— 新版表格容器（block_type==31） —— #
        if t == BlockType.table:
            # 读取表格属性
            prop = block.get('table', {}).get('property', {})
            col_count = prop.get('column_size', 0)
            # 按列数分组 cell_id
            cell_ids = block.get('children', [])
            rows = [cell_ids[i:i + col_count] for i in range(0, len(cell_ids), col_count)]
            table_data = []
            for row in rows:
                row_cells = []
                for cid in row:
                    texts = []
                    # 每个单元格可能有多个子块
                    for sub in self.children_map.get(cid, []):
                        st = self.safe_block_type(sub['block_type'])
                        if st is None:
                            continue
                        if st == BlockType.text:
                            texts.append(self._extract_text(sub['text']['elements']))
                        elif st == BlockType.bullet:
                            texts.append(self._extract_text(sub['bullet']['elements']))
                        else:
                            # 其他类型回退递归渲染
                            texts.append(self._render_block(sub, depth).strip())
                    row_cells.append(' '.join(texts))
                table_data.append(row_cells)
            # 生成 Markdown 表格
            if not table_data:
                return ''
            header, *body = table_data
            sep = ['---'] * len(header)
            lines = ['| ' + ' | '.join(header) + ' |', '| ' + ' | '.join(sep) + ' |']
            for r in body:
                lines.append('| ' + ' | '.join(r) + ' |')
            return '\n'.join(lines) + '\n'

        # —— 引用容器 & Callout（34, 19） —— #
        elif t in (BlockType.quote_container, BlockType.callout):
            # 先把所有子块渲染为纯文本（不带列表 Markdown）
            raw = []
            for child in self.children_map.get(block['block_id'], []):
                st = BlockType(child['block_type'])
                if st == BlockType.bullet:
                    # 只提取文本，不加 "- "
                    text = self._extract_text(child['bullet']['elements']).rstrip('\n')
                    raw.append(text + '\n')
                else:
                    # 其他类型仍然递归渲染
                    raw.append(self._render_block(child, depth))
            text = ''.join(raw).rstrip('\n')
            # 再统一给每一行加引用前缀
            for line in text.splitlines():
                md.append(f"> {line}\n")
            md.append("\n")
            return ''.join(md)

            # —— 无序列表（12）—— #
        if t == BlockType.bullet:
            text = self._extract_text(block['bullet']['elements'])
            md.append(f"{indent}- {text}\n")
            # 列表子项缩进一层
            for child in self.children_map.get(block['block_id'], []):
                md.append(self._render_block(child, depth + 1))
            return ''.join(md)

        # —— 渲染当前块 —— #
        if t == BlockType.page:
            title = self._extract_text(block.get('page', {}).get('elements', []))
            md.append(f"{title}\n{'=' * len(title)}\n")
        elif t == BlockType.text:
            elems = block.get('text', {}).get('elements', [])
            content = self._extract_text(elems)
            md.append(f"{indent}{content}\n\n")
        elif BlockType.heading1 <= t <= BlockType.heading9:
            level = t - 2
            key = f"heading{level}"
            txt = self._extract_text(block.get(key, {}).get('elements', []))
            if level <= 2:
                underline = '=' if level == 1 else '-'
                md.append(f"{txt}\n{underline * len(txt)}\n")
            else:
                md.append(f"{'#' * level} {txt}\n")
        elif t == BlockType.ordered:
            # 获取所有元素
            elements = block.get('ordered', {}).get('elements', [])
            # 提取所有 text_run 的 content
            parts = []
            for el in elements:
                if 'text_run' in el:
                    tr = el['text_run']
                    style = tr.get('text_element_style', {})
                    # 如果没有划掉（strikethrough=False），才把 content 加入 parts
                    if not style.get('strikethrough', False):
                        parts.append(tr.get('content', ''))
            # 用中文冒号连接
            txt = ' '.join(parts)
            # 渲染有序列表
            md.append('\n')
            md.append(f"{indent}{txt}\n")

            # 如果有子节点，继续递归渲染
            for child in self.children_map.get(block['block_id'], []):
                md.append(self._render_block(child, depth + 1))

            return ''.join(md)
        elif t == BlockType.code:
            elems = block.get('code', {}).get('elements', [])
            code_txt = ''.join(e.get('text_run', {}).get('content', '') for e in elems)
            lang = block.get('code', {}).get('language', '')
            md.append(f"```{lang}\n{code_txt}\n```\n")
        elif t == BlockType.quote:
            txt = self._extract_text(block.get('quote', {}).get('elements', []))
            md.append(f"> {txt}\n")
        elif t == BlockType.todo:
            todo = block.get('todo', {})
            txt = self._extract_text(todo.get('elements', []))
            ck = 'x' if todo.get('checked') else ' '
            md.append(f"{indent}- [{ck}] {txt}\n")
        elif t == BlockType.divider:
            md.append("---\n")
        elif t == BlockType.image:
            image = block.get("image", {})
            token = image.get("token")
            # 取出 caption 文本（如果有的话）
            caption = image.get("caption", {}).get("content", "").strip()
            if self.adapter == "inner":
                logger.info("正在调用数字化平台接口获取图片")
                download_file_url = self.DOWNLOAD_FILE_PATH.format(token)
                headers = {
                    # 请求飞书的地址
                    "request-path": download_file_url,
                    # 请求飞书使用的机器人编码，
                    "request-robot": settings.ROBOT_ENCODE,
                    "fcid-inner-download-file": "test.png"
                }
                # 调用数字化开放平台
                file_data = self.fcid_open_client.get(url=self.openapiPath_down, custom_header=headers)
            else:
                logger.info("正在调用飞书外部接口获取图片")
                download_file_url = self.FEUSHU_DOWNLOAD_FILE_PATH.format(token)
                headers = {
                    "Authorization": f"Bearer {self.tenant_access_token}",
                    "Content-Type": "application/json; charset=utf-8"
                }
                file_data = requests.get(download_file_url, headers=headers)

            if file_data:
                s3_url = self.upload_image_bytes_to_s3(file_data.content)
                if s3_url:
                    md.append(f"![Image]({s3_url})\n")
                else:
                    md.append(f"![Image]\n")
                if caption:
                    md.append(f"{caption}\n")
            else:
                # 如果下载失败，可以临时使用 token 作为占位
                md.append(f"![Image]\n")
                if caption:
                    md.append(f"{caption}\n")

        # 其他类型（如 file/image）可在此处继续扩展
        return ''.join(md)

    def to_markdown(self) -> str:
        """
        将所有根块渲染为完整 Markdown 文档
        """
        md_parts = []
        for root in self.roots:
            if BlockType(root['block_type']) == BlockType.page:
                # 根是页面容器时，单独渲染标题
                title = self._extract_text(root.get('page', {}).get('elements', []))
                md_parts.append(f"# {title}\n\n")
                for child in self.children_map.get(root['block_id'], []):
                    md_parts.append(self._render_block(child))
            else:
                # 其它块直接正常渲染
                md_parts.append(self._render_block(root))
        return ''.join(md_parts)
```

## **13\.4\.4 get\_token\.py 核心源码（完整）**

```Python
import random
import string
import requests
from flask import request, current_app
from flask_restful import Resource
from redis.exceptions import RedisError
from src.component.server_component.LogManager import logger
from src.feishu_parse.response_model import error_response, success_response

# =================== 常量配置 =================== #
FEISHU_TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"


# =================== 工具函数 =================== #
def get_cache_key(app_id: str, app_secret: str, adapter_way: str) -> str:
    return f"{adapter_way}-{app_id}-{app_secret}"


def generate_random_token() -> str:
    prefix = "t-"
    body = ''.join(random.choices(string.ascii_letters + string.digits, k=36))
    return prefix + body


# =================== 主资源类 =================== #
class FeishuGetToken(Resource):
    def __init__(self):
        self.redis_client = current_app.config['CACHE_INSTANCE']
        logger.info("FeishuGetToken 资源初始化完成")

    def post(self):
        logger.info("收到获取飞书 token 的请求")
        data = request.get_json() or {}
        app_id = data.get("app_id")
        app_secret = data.get("app_secret")
        adapter_way = data.get("adapter_way")
        logger.info(f"请求参数是：{data}")

        if not all([app_id, app_secret, adapter_way]):
            return error_response("Missing app_id or app_secret or adapter_way", -1)

        cache_key = get_cache_key(app_id, app_secret, adapter_way)

        # Step 2: 缓存未命中，生成或获取新 token
        if adapter_way == "inner":
            try:
                cached = self.redis_client.get(cache_key)
                if cached:
                    logger.info("命中 Redis 缓存，直接返回 token")
                    # 把 token 包成 dict 传给 success_response
                    token_val = cached.decode() if isinstance(cached, bytes) else cached
                    return success_response({"tenant_access_token": token_val})
            except RedisError as e:
                logger.info(f"Redis 读取失败: {e}")
            token = generate_random_token()
            logger.info(f"inner 模式，生成随机 token: {token}")
            try:
                # 正向存储
                self.redis_client.set(cache_key, token)
                # 负向存储
                self.redis_client.set(token, cache_key)
                logger.debug("新 token 已缓存至 Redis")
            except RedisError as e:
                logger.warning(f"Redis 写入失败，但仍返回 token: {e}")
        elif adapter_way == "outer":
            try:
                # 第一层：根据 adapter_way 去读缓存，cached 很可能是 token 字符串
                cached = self.redis_client.get(cache_key)
                if cached:
                    # 如果缓存是 bytes，就先 decode 成 str
                    token_val = cached.decode() if isinstance(cached, bytes) else cached

                    # 第二层：用 token_val 作为 key 去查，期望拿到的值是 cache_key
                    second_cached = self.redis_client.get(token_val)
                    if second_cached:
                        # 如果第二层缓存也命中，则确认 token 有对应的反向映射，直接返回
                        logger.info("双重缓存命中，直接返回 token")
                        return success_response({"tenant_access_token": token_val})

                    # 如果第二层没命中，就把第一层缓存删掉，后续再重新获取
                    logger.info(f"反向映射 {token_val} 不存在，删除过期缓存 {cache_key}")
                    try:
                        self.redis_client.delete(cache_key)
                    except RedisError as e_del:
                        logger.warning(f"删除第一层缓存失败: {e_del}")
                    # 继续往下走，重新走 Feishu 接口获取 token
                # 如果第一层缓存没命中，会直接跳到下面去获取
            except RedisError as e:
                logger.info(f"Redis 读取失败: {e}")

            # --------- 缓存未命中或失效，重新调用 Feishu 接口 ---------
            token, err, expire = self._get_token_from_feishu(app_id, app_secret)
            logger.info(f"token:{token}, err:{err}, expire:{int(expire)}")
            if err:
                return error_response(err, -1)

            try:
                # 存储第一层：adapter_way → token
                self.redis_client.set(cache_key, token, ex=int(expire)-10)
                # 存储第二层：token → adapter_way
                self.redis_client.set(token, cache_key, ex=int(expire)-10)
                logger.debug("outer 接口生成的 token 已缓存至 Redis（双向映射）")
            except RedisError as e:
                logger.warning(f"Redis 写入失败，但仍返回 token: {e}")

            return success_response({"tenant_access_token": token})
        else:
            return error_response("Invalid adapter_way parameter", -1)

    @staticmethod
    def _get_token_from_feishu(app_id: str, app_secret: str) -> tuple[str, str, str]:
        logger.info("调用飞书接口获取 token")
        payload = {"app_id": app_id, "app_secret": app_secret}
        try:
            resp = requests.post(FEISHU_TOKEN_URL, json=payload, timeout=5, verify=False)
            data = resp.json()
            logger.debug(f"飞书返回: {data}")
        except requests.RequestException as e:
            return "", f"网络请求失败: {e}", ""

        token = data.get("tenant_access_token")
        expire = data.get("expire")
        if not token:
            return "", f"飞书返回无效数据: {data}", ""
        return token, "", expire
```

## **13\.4\.5 get\_docdetail\.py 核心源码（完整）**

```Python
import datetime
import logging
import uuid
import json
import os
from tempfile import NamedTemporaryFile
from threading import Thread
from typing import Optional, Dict
from urllib.parse import urljoin

import requests
from flask import request, current_app
from flask_restful import Resource

from src import settings
from src.component.chunk.chunknode import PowerAgentTextNode
from src.component.server_component.LogManager import logger
from src.feishu_parse.api_client import OpenApiClient
from src.feishu_parse.feishu2markdown import FeishuDoc2Markdown
from src.feishu_parse.get_docinfo import find_cache_key, fetch_doc_info_via_inner, fetch_doc_info_via_outer
from src.feishu_parse.get_documentID import extract_document_id
from src.feishu_parse.response_model import error_response
from src.server_api_tools.s3helper import S3Helper


def load_data(file_name: str, parse_res: Optional[Dict] = None, source_metadata: Optional[Dict] = None,
              trans_metadata: Optional[Dict] = None):
    logger.info(f"开始加载数据: 获得文件名为{file_name}的飞书文档内容")
    if not source_metadata:
        source_metadata = dict()
    chunk_nodes = []
    now_time = datetime.datetime.now().strftime(r"%Y-%m-%d %H:%M:%S")
    location = {"type": "pages", "value": []}

    try:
        for page_num, page in enumerate(parse_res):
            page_label = page_num + 1

            metadata = {
                "file_name": file_name,
                "create_date": now_time,
                "parser_info": {
                    "parser": "feverish_parse",
                    "table_format": "MARKDOWN",
                    "ori_data": parse_res[page],
                },
            }

            # page_text = get_page_text(page)

            chunk_nodes.append(
                PowerAgentTextNode(
                    text=parse_res[page],
                    metadata=metadata,
                    source_metadata=source_metadata,
                    location=location,
                )
            )
            logger.info(f"创建 chunk 节点: file_name={file_name}, page_label={page_label}")

        chunks = [ck.to_pa_dict() for ck in chunk_nodes]
        logger.info(f"数据加载完成, 共创建 {len(chunks)} 个 chunk 节点")
        return chunks, trans_metadata

    except Exception as e:
        logger.error(f"[load_data] 加载数据失败: {e}")
        raise


def send_callback(callback_url: str, body: dict):
    try:
        resp = requests.post(callback_url, json=body, timeout=5)
        resp.raise_for_status()
        logger.info(f"回调成功 → {callback_url} ，status={resp.status_code}")
    except Exception:
        logger.exception(f"回调失败 → {callback_url}")


def upload_json_to_s3(data: dict, filename: str) -> str:
    tmp = NamedTemporaryFile(mode='w', suffix='.json', delete=False, encoding='utf-8')
    json.dump(data, tmp, ensure_ascii=False)
    tmp.close()

    s3_path = os.path.join(settings.S3_RESULT_SAVE_PATH, filename)
    S3Helper().upload_single_file(tmp.name, s3_path)
    os.remove(tmp.name)
    logger.info(f"结果已上传到 S3: {s3_path}")
    return s3_path


def extract_text_fragments(item: object) -> str:
    """
    从嵌套的 dict/list 结构中，提取所有未被划掉的文本片段（text_run.content、caption.content、以及 title 字段），
    用 '/' 拼接后返回一个字符串，末尾带一个换行。
    """

    fragments = []

    def _recurse(obj):
        # 1. 处理 dict
        if isinstance(obj, dict):
            # 1.1 处理 Feishu 文本块：text_run
            tr = obj.get('text_run')
            if isinstance(tr, dict):
                style = tr.get('text_element_style', {})
                # 只有没划掉的才收录
                if not style.get('strikethrough', False):
                    content = tr.get('content', '').strip()
                    if content:
                        fragments.append(content)

            # 1.2 处理 caption
            cap = obj.get('caption')
            if isinstance(cap, dict):
                content = cap.get('content', '').strip()
                if content:
                    fragments.append(content)

            # 1.3 处理 title —— 假设 title 本身就是字符串
            title = obj.get('title')
            if isinstance(title, str):
                text = title.strip()
                if text:
                    fragments.append(text)

            # 1.4 继续递归所有子节点
            for value in obj.values():
                _recurse(value)

        # 2. 处理 list
        elif isinstance(obj, list):
            for element in obj:
                _recurse(element)

        # 3. 其它类型忽略

    _recurse(item)

    # 最后拼接，用 '/' 分隔，末尾加换行
    return '/'.join(fragments) + '\n'


class FeishuGetDocDetail(Resource):
    def __init__(self):
        self.redis_client = current_app.config['CACHE_INSTANCE']
        self.DOC_DETAIL_URL = "/open-apis/docx/v1/documents/{}/blocks"
        self.openapiHost = settings.OPEN_API_HOST
        self.openapiPath = "feishu-msg/fcid-api/proxy-feishu/get/e4a2ytnf3mkh"
        self.openapiPath_down = "feishu-msg/fcid-api/proxy-feishu/general/get/ej1msqcmw9vm"
        self.FEISHU_OPEN_HOST = "https://open.feishu.cn"

    def extract_text_from_item(item):
        # 可能存在 text 或 bullet 字段
        elements = []
        if "text" in item and "elements" in item["text"]:
            elements = item["text"]["elements"]
        elif "bullet" in item and "elements" in item["bullet"]:
            elements = item["bullet"]["elements"]

        # 提取所有 text_run.content
        contents = []
        for el in elements:
            if "text_run" in el and "content" in el["text_run"]:
                contents.append(el["text_run"]["content"].strip())

        return ''.join(contents)

    def post(self):
        req = request.get_json() or {}
        doc_url = req.get("doc_url")
        token = req.get("tenant_access_token")
        ctype = req.get("content_type")
        cb_url = req.get("callback_url")
        meta = req.get("trans_metadata", {})

        if not all([doc_url, token, ctype, cb_url]):
            return error_response("缺少必要参数", -1)

        redis_key = find_cache_key(self.redis_client, token)

        if not redis_key:
            return error_response("tenant_access_token失效，请重新获取token", -1)

        adapter, app_id, app_secret = redis_key.split("-", 2)

        try:
            document_id = extract_document_id(doc_url, app_id, app_secret, adapter, token)
        except Exception as e:
            return error_response(f"提取 document_id失败:{e}", -1)

        # 立刻生成 task_id 并返回
        task_id = uuid.uuid4().hex
        resp = {"code": 0, "msg": "success", "data": {"task_id": task_id}}

        # 后台处理
        def worker():
            try:
                # 1) 拉取 blocks
                detail_path = self.DOC_DETAIL_URL.format(document_id)
                client = OpenApiClient(self.openapiHost, app_id, app_secret)
                if adapter == "inner":
                    headers = {"request-path": detail_path, "request-robot": settings.ROBOT_ENCODE}
                    try:
                        resp_inner = client.get(url=self.openapiPath, custom_header=headers)
                    except Exception as e:
                        logger.exception("调用 Feishu OpenApiClient 获取文档详情失败")
                        send_callback(cb_url, {
                            "code": 1,
                            "msg": f"拉取文档块失败: {e}",
                            "trans_metadata": meta,
                            "task_id": task_id
                        })
                        return
                    data = json.loads(resp_inner.text)
                    if data.get('code') != 0:
                        if data.get('code') == '9992' or data.get('code') == '9993':
                            raise RuntimeError(f"MAIP 错误: {data.get('message')}或输入正确的app_id和app_secret")
                        raise RuntimeError(f"MAIP 错误，错误代码: {data.get('msg')}")

                    items = data["data"].get("items", [])
                    if ctype == "markdown":
                        converter = FeishuDoc2Markdown(items, client, adapter, token)
                        md = converter.to_markdown()
                    elif ctype == "txt":
                        logger.info("选用txt格式输出")
                        # 处理所有 item，忽略空内容
                        all_text = []
                        for item in items:
                            txt = extract_text_fragments(item)
                            all_text.append(txt)
                        md = '/'.join(all_text)
                    else:
                        raise RuntimeError("请填写正确的content_type，markdown or txt")

                    try:
                        doc_id = extract_document_id(doc_url, app_id, app_secret, adapter, token)
                    except Exception as e:
                        return error_response(f"提取 document_id失败:{e}", -1)
                    info = fetch_doc_info_via_inner(app_id, app_secret, doc_id)
                    if info.get('code') in ('9992', '9993'):
                        return error_response(f"MAIP 错误: {info.get('message')}，请检查 app_id/app_secret", -1)
                    filename = info.get('data', {}).get('document', {}).get('title', '')

                else:
                    full_url = urljoin(self.FEISHU_OPEN_HOST, detail_path)
                    try:
                        resp = requests.get(full_url, headers={"Authorization": f"Bearer {token}"}, timeout=20,
                                            verify=False)
                        resp.raise_for_status()
                        res = json.loads(resp.text)
                        items = res["data"].get("items", [])
                        if ctype == "markdown":
                            converter = FeishuDoc2Markdown(items, client, adapter, token)
                            md = converter.to_markdown()
                        elif ctype == "txt":
                            logger.info("选用txt格式输出")
                            # 处理所有 item，忽略空内容
                            all_text = []
                            for item in items:
                                txt = extract_text_fragments(item)
                                all_text.append(txt)
                            md = '/'.join(all_text)
                        else:
                            raise RuntimeError("请填写正确的content_type，markdown or txt")
                    except Exception:
                        raise RuntimeError("调用外部 Feishu 文档接口失败")

                    try:
                        doc_id = extract_document_id(doc_url, app_id, app_secret, adapter, token)
                    except Exception as e:
                        return error_response(f"提取 document_id失败:{e}", -1)
                    info = fetch_doc_info_via_outer(doc_id, token)
                    if info.get('code') == "1770032":
                        return error_response("获取文档信息无权限，请手动赋予权限", -1)
                    filename = info.get('data', {}).get('document', {}).get('title', '')

                parse_res = {"md": md}
                chunks, trans_metadata = load_data(filename, parse_res, source_metadata=None, trans_metadata=meta)
                logger.info(f"数据加载成功：{chunks}")
                task_type = "feverish_parser"
                save_path = S3Helper.get_s3_config_save_path(
                    task_type=task_type, task_id=task_id
                )
                S3Helper().save_chunks_json(chunks, save_path)

                # 3) 成功回调
                cb_body = {
                    "code": 0,
                    "msg": "success",
                    "trans_metadata": meta,
                    "task_id": task_id,
                    "result_path": save_path
                }
                logger.info(f"请求回调的body:{cb_body}")
                send_callback(cb_url, cb_body)

            except Exception as ex:
                logger.exception("后台处理失败")
                # 错误回调
                send_callback(cb_url, {
                    "code": 1,
                    "msg": f"后台处理失败:{ex}",
                    "trans_metadata": meta,
                    "task_id": task_id
                })

        Thread(target=worker, daemon=True).start()
        return resp, 200
```

---

# **13\.5 面试要点：飞书文档解析**

> **面试要点 1 —— 双适配器 \(inner/outer\) 架构**
> 
> - **inner（内部代理通道）**：通过 `OpenApiClient` 走内网代理 `feishu-msg/fcid-api/proxy-feishu/get/e4a2ytnf3mkh`，自定义请求头 `request-path`（要代理的飞书地址）\+ `request-robot`（机器人编码，来自 `settings.ROBOT_ENCODE`）转发到飞书。所有内部应用走此通道，避免外网暴露。
> 
> - **outer（飞书开放平台直连）**：直接 `requests.get("https://open.feishu.cn/open-apis/...")`，`Authorization: Bearer {tenant_access_token}`。
> 
> - 通过 Redis 的 `find_cache_key` 反查 `adapter-app_id-app_secret`，一次 `split("-", 2)` 解出三元组，从而决定走哪条通道。
> 
> **面试要点 2 —— BlockType 真实枚举值**
> 
> - `bullet=12, ordered=13, quote=15, callout=19, table=31, table_cell=32, quote_container=34`。
> 
> - 面试常被问"quote 是几？list 是几？"——**答案是 quote=15、列表分 unordered\(bullet\)=12 和 ordered=13**，切勿与 callout=19、quote\_container=34 混淆。
> 
> **面试要点 3 —— 树构建与递归渲染**
> 
> - `_build_tree`：`id_map`（block\_id→block）\+ `children_map`（parent\_id→子块列表，`defaultdict(list)`）\+ `roots`（parent 为空或不在 id\_map 中的顶层块）。两次遍历 O\(n\)，把扁平 blocks 数组变成树。
> 
> - `_render_block` 按 `safe_block_type`（防非法值，`ValueError→None`）递归渲染：table\(31\) 用 `property.column_size` 把 `children` 的 cell\_id 切片分组生成 `| a | b |` \+ `|---|` 表格；quote\_container\(34\)/callout\(19\) 先递归渲染再逐行加 `>` 前缀；heading 1\-9 由 `level = t - 2` 换算（heading1→level1 用 `=` 下划线，heading2→`-` 下划线，≥3 用 `#`）。
> 
> **面试要点 4 —— token 获取策略**
> 
> - **inner**：直接 `generate_random_token()` 生成 `"t-" + 36位随机字符`，正反双向写入 Redis（`cache_key→token` 和 `token→cache_key`），飞书调用端与校验端都在内网，token 只是一个"会话标识"。
> 
> - **outer**：调 `https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal` 拿真实 token，Redis 双重缓存校验（先查 `cache_key→token`，再查 `token→cache_key` 反向映射；反向不命中则删掉过期正向缓存），TTL 设为 `ex=int(expire)-10` 提前 10 秒过期防止边界失效。
> 
> **面试要点 5 —— 异步 \+ 回调设计**
> 
> - `post()` 先校验参数、解出 document\_id、立刻返回 `task_id`（uuid4\(\)\.hex），真正的拉取/转换/保存放到 `Thread(target=worker, daemon=True)` 后台线程执行。
> 
> - worker 内部分支：成功则 S3 保存 chunks 后 `send_callback(cb_url, {code:0, ..., result_path})`；失败则回调 `{code:1, msg}`。`send_callback` timeout=5s，失败仅记日志不重试（保证主流程不阻塞、回调失败不致命）。
> 
> **面试要点 6 —— content\_type 两种输出**
> 
> - `markdown`：走 `FeishuDoc2Markdown.to_markdown()` 完整结构（标题层级、表格、引用、图片上传 S3 转 `![Image](s3_url)`）。
> 
> - `txt`：用 `extract_text_fragments` 深度递归提取所有 `text_run.content`/`caption.content`/`title`（跳过划掉 strikethrough 的文本），以 `/` 拼接的纯文本，适合只关心内容的场景。
> 
> 

---

# **14 Pinpoint 全链路追踪**

## **14\.1 模块职责**

项目通过 **Pinpoint APM** 实现 Java 网关 ↔ Python 服务的全链路分布式追踪。核心思路：

```Plain Text
Java 网关 (发起 traceId/spanId)
    ↓  HTTP Header: pinpoint-traceid / pinpoint-spanid
Flask app.py  before_request 钩子
    ↓  PinpointContext.set_trace_id/set_span_id  (写入 ContextVar)
所有日志记录   PinpointFilter 读取 ContextVar 注入 LogRecord.trace_id/span_id
    ↓
每条日志都带 trace_id/span_id → Pinpoint 控制台可串起整条调用链
```

- **采样埋点**：`pinpointPy.monkey_patch_for_pinpoint()` 自动对 requests/DB 等调用打点；`PinPointMiddleWare` 包一层 WSGI 中间件采集每个 HTTP 请求的耗时/状态。

- **ContextVar 传递**：`PinpointContext` 用 `contextvars.ContextVar` 存储当前请求的 trace\_id/span\_id，天然支持 gevent 协程隔离，不污染其他请求。

- **双端一致**：Flask 应用（`src/`）与 FastAPI Agent 应用（`agent/`）各自接入，但上报到同一个 Pinpoint Collector（`tcp:10.245.15.173:9999`）。

## **14\.2 核心文件清单**

## **14\.3 pinpoint\_context\.py 核心源码（完整）**

```Python
from contextvars import ContextVar

# 用 contextvar 来存储当前请求的 trace_id/span_id
_trace_id: ContextVar[str] = ContextVar("trace_id", default="-")
_span_id:   ContextVar[str] = ContextVar("span_id",   default="-")


class PinpointContext:
    """
    全链路追踪上下文：读取/写入 trace_id 和 span_id。
    Python 端在 before_request 钩子里从 HTTP Header 设置，
    然后所有日志 Filter 会从这里读取。
    """
    @staticmethod
    def get_trace_id() -> str:
        return _trace_id.get()

    @staticmethod
    def get_span_id() -> str:
        return _span_id.get()

    @staticmethod
    def set_trace_id(trace_id: str) -> None:
        _trace_id.set(trace_id)

    @staticmethod
    def set_span_id(span_id: str) -> None:
        _span_id.set(span_id)
```

## **14\.4 Flask 端接入（app\.py 完整源码片段）**

```Python
from pinpointPy import monkey_patch_for_pinpoint, set_agent, use_thread_local_context
from pinpointPy.Flask import PinPointMiddleWare
from src.tools.pinpoint_context import PinpointContext

def create_app():
    from src import settings

    use_thread_local_context()
    monkey_patch_for_pinpoint()
    app = Flask(__name__)

    # 把Java端透传来的traceId / spanId拦截并写入PinpointContext
    @app.before_request
    def load_pinpoint_from_headers():
        trace_id = request.headers.get("pinpoint-traceid")
        span_id = request.headers.get("pinpoint-spanid")
        PinpointContext.set_trace_id(trace_id)
        PinpointContext.set_span_id(span_id)

    # configure with pinpoint
    app.wsgi_app = PinPointMiddleWare(app, app.wsgi_app)
    APP_ID = "af-rag-server"
    APP_NAME = "af-rag-server"
    COLLECTOR_HOST = settings.PINPOINT_HOST
    set_agent(APP_ID, APP_NAME, COLLECTOR_HOST, trace_limit=100)
    ...
```

## **14\.5 日志注入（LogManager\.py 完整源码片段）**

```Python
from src.tools.pinpoint_context import PinpointContext

class PinpointFilter(logging.Filter):
    """在 LogRecord 中注入 trace_id 和 span_id"""

    def filter(self, record: LogRecord) -> bool:
        record.trace_id = PinpointContext.get_trace_id()
        record.span_id = PinpointContext.get_span_id()
        return True
```

## **14\.6 FastAPI Agent 端接入**

### **agent/apps/pinpoint\_agent\.py（完整）**

```Python
import pinpointPy

import settings
from utils.log_manager import logger


def set_agent_info() -> bool:
    try:
        app_id = settings.PINPOINT_APP_ID
        app_name = settings.PINPOINT_APP_NAME
        collector_host = settings.PINPOINT_HOST

        logger.info(f"trying to set pinpoint config: {collector_host=}, {app_name=}, {app_id=}")
        if not all([app_id, app_name, collector_host]):
            logger.info("set pinpoint config failed cause missing params")
            return False

        pinpointPy.set_agent(
            app_id_str=app_id, app_name_str=app_name, collect_agent_host=collector_host, trace_limit=100
        )
        logger.info(f"set pinpoint config success: {collector_host=}, {app_name=}, {app_id=}")
        return True
    except Exception as e:
        logger.error(f"set pinpoint config failed cause {e=}")
        return False
```

### **agent/app\.py 中间件注册**

```Python
from pinpointPy.Fastapi import PinPointMiddleWare, use_starlette_context
from apps import pinpoint_agent

...
if pinpoint_agent.set_agent_info():
    app.add_middleware(PinPointMiddleWare)
```

---

# **14\.7 面试要点：Pinpoint 全链路追踪**

> **面试要点 1 —— Header 传递 \+ ContextVar 隔离**
> 
> - Java 网关在 HTTP Header 里带 `pinpoint-traceid` / `pinpoint-spanid`，Flask 的 `before_request` 钩子读取后写入 `PinpointContext`。
> 
> - `PinpointContext` 底层是 `contextvars.ContextVar`，默认值 `"-"`。每个请求（含 gevent 协程）持有独立上下文，互不污染；在异步协程里 `ContextVar` 也会自动跟随执行上下文传播，这是选它而非全局变量的原因。
> 
> **面试要点 2 —— 日志链路贯通**
> 
> - `PinpointFilter` 是一个 `logging.Filter`，在 `filter()` 里把 `PinpointContext.get_trace_id()/get_span_id()` 写到 `record.trace_id` / `record.span_id`。
> 
> - 它同时挂在 console handler 和 file handler 上，因此控制台与日志文件里的每一条日志都带 trace\_id，排障时按 trace\_id 全文搜索即可还原一次完整的跨系统调用。
> 
> **面试要点 3 —— 埋点机制**
> 
> - `monkey_patch_for_pinpoint()`：对 requests、MySQL、Redis 等常见库做 monkey\-patch 自动打点，无需改业务代码。
> 
> - `PinPointMiddleWare`（Flask WSGI 中间件 / FastAPI `app.add_middleware`）：采集每个 HTTP 请求的入参、耗时、返回码并上报 Collector。
> 
> - `set_agent(APP_ID, APP_NAME, COLLECTOR_HOST, trace_limit=100)` 向 `tcp:10.245.15.173:9999` 上报；`trace_limit=100` 限制单节点 trace 数量防内存膨胀。
> 
> **面试要点 4 —— 双应用一致接入**
> 
> - Flask RAG 服务与 FastAPI Agent 服务上报到同一个 Collector（settings 里 `PINPOINT_HOST` 一致），因此一次用户请求从 Java 网关 → Flask → FastAPI → 外部 LLM 的完整链路，都能在 Pinpoint 控制台串成一条 trace。
> 
> - 配置全部走环境变量（`PINPOINT_HOST` 等），缺失时 `set_agent_info()` 返回 False，应用仍可正常启动（追踪降级不阻塞主流程）。
> 
> 

---

# **15 面试核心问答**

## **15\.1 整体架构**

## **15\.2 文档解析**

## **15\.3 分块与向量化**

## **15\.4 检索与重排**

## **15\.5 Agent 智能体系统（Google ADK）**

## **15\.6 异步任务与存储**

## **15\.7 代码执行沙箱**

## **15\.8 记忆与评估**

## **15\.9 监控与国际化**

---

# **16 深度追问（进阶面试题）**

> 这一部分模拟面试官"由浅入深"的追问，适合在讲完 15\.1\~15\.9 基础问答后展开，展示对系统的深入理解。
> 
> 

## **16\.1 场景类追问**

**Q1：如果一个用户上传的 PDF 很大（几百页），你的解析链路会不会卡死主流程？**

答：不会。核心链路（分块、关键词、摘要、向量化）本身是同步的，但可以配合任务化：接口无 `callback_url` 时同步执行、有 `callback_url` 时走 Huey 异步队列（`tasks.async_url_parsing` 等），立刻返回 `task_id`，worker 完成后回调结果路径。另外解析本身是 IO 密集，Flask 用 gevent 协程 worker，能并发处理大量解析请求；真正的重计算（Embedding）可以批量化。

**Q2：网页内容编码是 GBK/GB2312，抓出来乱码怎么办？**

答：`get_html()` 里先按字节解析 meta：优先取 `<meta charset>`，否则正则匹配 `http-equiv="Content-Type"` 的 charset；命中后 `response.encoding = encode` 再 `response.text`。抓取做了 30 次重试（网络抖动/反爬）。如果编码信息缺失，还可尝试 chardet 探测。

**Q3：Firecrawl 抓"整站"（带子页面）和抓单页有何区别？**

答：单页走 `/scrape`（formats/onlyMainContent 拍平到顶层）；带子页走 `/crawl`，异步返回 `task_id`，服务端用 `query_status` 轮询 `/crawl/{task_id}`，`completed`/`total` 字段展示进度，全部完成后调用 `load_data_crawl_fire2` 解析所有子页并入库。判断标准：payload 里出现 `limit/maxDiscoveryDepth/excludePaths/includePaths` 任一字段即判定为 crawl 模式。

**Q4：飞书文档里有很多图片，怎么入库？**

答：`FeishuDoc2Markdown._render_block` 里 image\(27\) 分支：inner 走 `fcid-inner-download-file` 代理头下载字节流，outer 走 `drive/v1/medias/{token}/download`；字节流经 `PIL.Image.open` 识别格式 → 存本地临时文件 → `S3Helper.upload_single_file` 传到 `S3_IMAGE_SAVE_PATH` → 删除本地文件 → 渲染 `![Image](s3_url)`。这样 RAG 阶段能拿到图片地址而非丢失图片。

**Q5：检索结果不准，你会怎么排查？**

答：分环节排查：① 分块是否破坏语义（表格拆散/代码块截断）→ 换分块策略；② 召回是否漏（Milvus top\_k 太小 / ES 命中率）→ 调参数或加混合检索；③ 重排是否误杀（分数阈值太高）→ 看 RAGAS 相关性指标；④ embedding 模型与 query 领域不匹配 → 换/微调模型；⑤ 用 RAGAS 忠实度指标定位是"检索不到"还是"生成幻觉"。

## **16\.2 设计类追问**

**Q6：如果让你给 Agent 加一个"对话记忆"能力，你会怎么做？**

答：项目已有 Mem0 基础设施（`src/memory/memory_vector.py` 向量 CRUD \+ `scheduler.py` 定时沉淀）。做法：在 Agent 的 `before_model_callback` 里把用户 query 向量化，从记忆库召回历史偏好，注入 system prompt；对话结束后把本次关键信息异步写入记忆库。与文档检索的区别：记忆库按用户隔离（user\_id），文档库按知识库隔离。

**Q7：多 Agent 之间如何共享上下文？**

答：根 Agent `IntentAgent` 先收集全局上下文（用户请求、元数据、会话状态），路由时传给子 Agent；子 Agent 返回值再汇总给根 Agent 统一生成最终响应。通过 `AutoAgentExecutor` 维护一次执行的会话上下文对象，借助 Google ADK 的 AgentContext 传递。SSE 流式把每步可见。

**Q8：如何保证"代码执行"不拖垮服务或执行危险操作？**

答：`pycode` 沙箱构建 `safe_builtins` 白名单（从 `dir(builtins)` 里剔除 `danger_names` 危险项，如 `eval/exec/open/import`），只暴露安全子集。再加上：任务超时控制、独立进程/容器隔离、资源限额（CPU/内存）、结果大小限制。生产环境建议 `code_runner` 放到独立容器。

**Q9：为什么要提前 ****`ex=int(expire)-10`**** 设置 token 过期？**

答：飞书返回的 `expire` 是剩余有效秒数，缓存 TTL 提前 10 秒过期，避免"缓存认为有效、飞书认为过期"的边界竞态（网络延迟/时钟偏差导致恰好在过期点使用失败）。

## **16\.3 排查与优化类追问**

**Q10：线上 RAG 回答变慢了，可能瓶颈在哪？**

答：① 解析阶段：大文档 Embedding 串行 → 并行化/分批；② 检索阶段：Milvus 查询慢（索引/资源）→ 调 top\_k、加缓存；③ 重排阶段：rerank 模型调用慢 → 只对 top\-k 做重排、结果缓存；④ LLM 生成：上下文过长 → 压缩/限制输入长度；⑤ gevent worker 数量不足 / Redis 队列积压 → 扩容、看 Prometheus `/metrics`。用 Pinpoint 看跨系统耗时分布定位。

**Q11：并发高时 Redis Cluster 写入失败怎么办？**

答：代码里大量用了 `try/except RedisError` 的降级策略（如 get\_token 里 Redis 写失败仍返回 token、只记 warning），保证缓存故障不阻塞主流程；必要时关闭缓存直连后端。真正的强一致数据走 MySQL，Redis 只做可容忍丢失的加速层。

**Q12：两个应用的配置怎么管理？**

答：Flask 侧 `src/settings.py` 用 `environs` \+ Nacos SDK（远程配置中心）加载环境变量；FastAPI 侧 `agent/settings.py` 用 pydantic\_settings。敏感配置（app\_secret、api\_key）走环境变量/密钥管理，不入代码。

## **16\.4 一句话亮点话术（供自我介绍/项目陈述使用）**

1. **"我负责的 RAG 平台是双应用架构：Flask 承载解析\-分块\-向量化\-检索\-重排的完整 RAG 流水线，FastAPI 承载基于 Google ADK 的多智能体系统，两套服务通过 Redis/MySQL/Milvus/ES/S3 共享数据，supervisor 统一调度。"**

2. **"网页解析我落地了三条通道——自研 requests\+BeautifulSoup、Jina Reader 和 Firecrawl，按目标站点复杂度自动切换，子页面抓取用异步任务 \+ 轮询回调。"**

3. **"飞书文档解析我实现了 inner/outer 双适配器，内网走代理通道，外网直连开放平台，token 用 Redis 双向缓存 \+ 提前 10 秒过期防止边界失效。"**

4. **"全链路用 Pinpoint 打通 Java 网关到 Python 的 trace，ContextVar \+ 日志 Filter 让每条日志都带 trace\_id，问题定位效率大幅提升。"**

5. **"安全方面，代码执行用 builtins 白名单沙箱隔离危险内建函数，所有外部 API key 走环境变量，Redis 故障有降级策略不阻塞主流程。"**

---

## **文档结束**

本分析文档已覆盖 af\-rag\-server 的核心模块与源码：整体架构、Flask RAG 服务、文档解析（自研/三方/飞书）、分块、检索与重排、代码沙箱、Huey 异步任务与 S3、Google ADK 多智能体、GraphRAG、Mem0 记忆、RAGAS 评估、Pinpoint 全链路追踪，并配套完整面试问答。祝面试顺利。

