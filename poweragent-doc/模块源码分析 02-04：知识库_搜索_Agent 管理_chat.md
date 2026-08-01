# 模块源码分析 02\-04：知识库/搜索/Agent 管理/chat

# 模块源码分析 02：知识库/搜索

## **核心文件清单**

```Plain Text
agentflow-server/
├── agent-flow-app/agent-flow-workflow/
│   └── .../module/application/service/
│       └── DatasetSearchServiceImpl.java        ★ Workflow 节点入口 (299 行)
│
├── agent-flow-app/agent-flow-knowledges/
│   └── .../application/service/rag/
│       └── KnowledgeSearchServiceImpl.java      ★ RAG 核心检索引擎 (550+ 行)
│       └── .../application/client/service/rag/
│           └── KnowledgeSearchService.java      ★ 检索接口定义
│
├── agent-flow-app/agent-flow-densevector/
│   └── .../application/service/
│       └── DenseVectorServiceImpl.java          ★ ES/Milvus 存储层实现
│       └── .../application/client/service/
│           └── DenseVectorService.java          ★ 存储层接口
│
├── agent-flow-common/
│   └── .../common/utils/
│       └── RrfRankerUtil.java                  ★ RRF 融合算法实现 (61 行)
│
└── .../domain/enums/
    ├── KnowledgeSearchModeEnum.java             ★ 检索模式枚举
    ├── KnowledgeSearchTypeEnum.java             ★ 检索类型枚举
    └── KnowledgeTypeEnum.java                   ★ 知识库类型枚举
```

---

## **分层架构**

```Plain Text
┌─────────────────────────────────────────────────────────────────┐
│  WorkFlow 节点层                                                 │
│  DatasetSearchServiceImpl (@NodeType DATASET_SEARCH_NODE)       │
│  ─────────────────────────────────────────────────────────────── │
│  职责: 节点入口、参数解析、知识库ID校验、结果组装                     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  RAG 检索编排层                                                   │
│  KnowledgeSearchServiceImpl                                      │
│  ─────────────────────────────────────────────────────────────── │
│  职责: 权限过滤、检索模式路由、RRF融合、Rerank重排序、Token截断     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  存储引擎层                                                       │
│  DenseVectorServiceImpl                                          │
│  ─────────────────────────────────────────────────────────────── │
│  职责: ES BM25全文检索、ES knn向量检索、Milvus ANN检索             │
│       索引管理、批量写入、删除、元数据字段管理                      │
└──────────────────────────┬──────────────────────────────────────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
┌─────────────────────┐   ┌─────────────────────────┐
│  ES (Elasticsearch)  │   │  Milvus (向量数据库)      │
│  ├── 全文索引 (data)  │   │  ├── ANN 向量检索         │
│  ├── 向量索引(vector) │   │  ├── Collection 管理      │
│  └── BM25 打分       │   │  └── Cosine/欧式距离      │
└─────────────────────┘   └─────────────────────────┘
```

---

## **调用链路**

```Plain Text
用户问题: "产品规格书中关于电池参数是什么？"
    │
    ▼
WorkFlowEngine.moduleRun()
    │
    nodeType = "datasetSearchNode"
    ModuleFactory.getService(DATASET_SEARCH_NODE)
    → DatasetSearchServiceImpl
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ DatasetSearchServiceImpl.execute(DispatchData)                  │
│                                                                  │
│ 1. 解析参数 (JSON → DatasetModule)                                │
│ 2. 获取知识库 ID 列表                                              │
│    ├── 动态模式 (DYNAMIC_KNOWLEDGE_ID_SEARCH)                     │
│    │   └── 从 variables["knowledgeIds"] 读取运行时传入的 ID         │
│    └── 静态模式 (KNOWLEDGE_SEARCH)                                │
│        └── 从节点配置 params.datasets 读取固定 ID                  │
│ 3. 校验知识库一致性 (类型 + 向量模型)                               │
│ 4. 构建 KnowledgeRagDTO                                          │
│ 5. ★ 调用 knowledgeSearchService.knowledgeSearch() ★            │
│ 6. 组装结果 (ChatHistoryItemResType)                              │
└─────────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ KnowledgeSearchServiceImpl.knowledgeSearch(KnowledgeRagDTO)     │
│                                                                  │
│ Step 1: 权限过滤                                                  │
│   ├── filterPermissionKnowledge()  —— 知识库级别的权限             │
│   └── filterDatasetIdsByEnabled()  —— 数据集启用/禁用             │
│                                                                  │
│ Step 2: 检索模式路由                                               │
│   switch(searchMode) {                                           │
│     EMBEDDING     → vectorSearch()                              │
│     FULLTEXT      → fullTextSearch()                            │
│     MIXED_RECALL  → mixSearch()  ← 默认模式                      │
│   }                                                              │
│                                                                  │
│ Step 3: Token 截断                                                │
│   filterTokens(resultDTOList, limit)                             │
│                                                                  │
│ Step 4: 图片地址替换                                               │
│   s3:// → https://domain/resource?filePath=s3://                │
│                                                                  │
│ Step 5: 异步记录召回指标                                           │
│   updateRecallCount(result)  ← Kafka/DB 异步                     │
│                                                                  │
│ Step 6: 返回 KnowledgeSearchResultDTO                            │
└─────────────────────────────────────────────────────────────────┘
```

---

## **DatasetSearchServiceImpl\.java 源码分析**

**文件**: `.../module/application/service/DatasetSearchServiceImpl.java` **行数**: 299 行 **注解**: `@NodeType(FlowNodeTypeEnum.DATASET_SEARCH_NODE)` **职责**: 知识库检索 WorkFlow 节点——参数解析 \+ ID 获取 \+ 一致性校验 \+ 结果组装

### **4\.1 execute\(\) 主方法**

```Java
// 第 73 行 ─── 节点入口
@Override
public Map<String, Object> execute(DispatchData dispatchData) {
    // 1) JSON → Java 对象
    DatasetModule params = JSON.parseObject(
        JSON.toJSONString(dispatchData.getParams()), DatasetModule.class);

    // 2) ★ 获取知识库 ID 列表 ★
    List<String> knowledgeIds = getKnowledgeIds(params, dispatchData.getVariables(), datasetIds);

    // 3) 去重 + 去空
    knowledgeIds = FU.toDistinctList(FU.toFilterList(knowledgeIds, StringUtils::isNotBlank));
    datasetIds = FU.toDistinctList(FU.toFilterList(datasetIds, StringUtils::isNotBlank));

    // 4) 构建 RAG 检索入参
    KnowledgeRagDTO knowledgeRagDTO = buildKnowledgeRagDTO(userInfo, params,
        knowledgeIds, datasetIds, chatId, customerUserId, chatTestFlag);

    // 5) ★ RAG 核心检索 ★
    KnowledgeSearchResultDTO resultDTO = knowledgeSearchService.knowledgeSearch(knowledgeRagDTO);

    // 6) 组装返回结果
    Map<String, Object> map = new HashMap<>();
    map.put("isEmpty", searchRes.size() == 0);   // 前端判断是否空结果
    map.put("unEmpty", searchRes.size() > 0);     // 前端判断是否有结果
    map.put("quoteQA", searchRes);                // 检索到的 chunk 列表
    map.put("responseData", moduleResponseData);  // 执行详情
    return map;
}
```

**面试要点**: `isEmpty`/`unEmpty` 两个布尔值输出给下游节点的条件分支，比如 `tfSwitch` 判断"有结果 → chatNode"、"空结果 → 兜底回复"。

### **4\.2 getKnowledgeIds\(\) 关键方法**

```Java
// 第 113 行 ─── 三种 ID 获取模式
protected List<String> getKnowledgeIds(
    DatasetModule params,
    Map<String, Object> variables,   // ← 全局变量池
    List<String> datasetIds           // ← 输出参数
) {
    // ★ 模式一: 从全局变量读取 (运行时动态) ★
    if (CollectionUtil.isNotEmpty(variables)) {
        Object knowledgeIds = variables.get("knowledgeIds");
        if (knowledgeIds != null) {
            // 解析 JSON: "[\"id1\",\"id2\"]" → List<String>
            return JSON.parseArray(JSON.toJSONString(knowledgeIds), String.class);
        }
    }

    // ★ 模式二: 动态知识库 ID ★
    if (DYNAMIC_KNOWLEDGE_ID_SEARCH.equals(params.getSearchType())) {
        // 支持两种 JSON 格式:
        // 格式A: ["{knowledgeId: 'id1', datasetIds: ['ds1','ds2']}"]
        // 格式B: ["knowledgeId1", "knowledgeId2"]
        for (String knowledgeIdString : params.getDatasets()) {
            List<KnowledgeDatasetProps> result = parseIds(knowledgeIdString);
            // parseIds 自动判断 A/B 格式
        }
        // ★ 一致性校验 ★
        kgdsidList = filterKnowledgeIds(kgdsidList);
        // 数量限制: knowledgeId ≤ 50, datasetId ≤ 10000
        if (knowledgeIdList.size() > 50 || datasetIds.size() > 10000) {
            throw new BusinessException("超出限制");
        }
    }

    // ★ 模式三: 固定知识库 (节点配置) ★
    else if (KNOWLEDGE_SEARCH.equals(params.getSearchType())) {
        for (String dataset : params.getDatasets()) {
            // 解析 datasetSearchProps
            DatasetSearchProps props = JSON.parseObject(dataset, DatasetSearchProps.class);
            knowledgeIdList.add(props.getDatasetId());
        }
    }
    return knowledgeIdList;
}
```

**三种模式对比**:

### **4\.3 filterKnowledgeIds\(\) 一致性校验**

```Java
// 第 207 行 ─── 核心一致性过滤
private List<KnowledgeDatasetProps> filterKnowledgeIds(
    List<KnowledgeDatasetProps> knowledgeList
) {
    // 1) 去空
    knowledgeList = filter(k → isNotEmpty(k.getKnowledgeId()));

    // 2) ★ 只允许三种类型 ★
    List<Knowledge> validKnowledge = knowledgeEntityList.stream()
        .filter(k -> ALLOW_TYPES.contains(k.getType()))
        // ALLOW_TYPES = {DATESET, FAQ, GRAPH}
        .collect(toList());

    // 3) ★ 一致性别名: 同类型 + 同向量模型 ★
    Knowledge first = validKnowledge.stream().findFirst();
    List<String> resultIds = validKnowledge.stream()
        .filter(k -> sameType(first, k) && sameVectorModel(first, k))
        .map(Knowledge::getKnowledgeId)
        .collect(toList());

    return resultIds;
}
```

**面试要点**: 多知识库联合检索时，必须保证所有知识库**类型一致**（都是文档/都是 FAQ/都是 Graph）且**向量模型一致**（都用 text\-embedding\-3\-large）。不一致的知识库会被静默丢弃。

### **4\.4 buildKnowledgeRagDTO\(\) 检索参数构造**

```Java
// 第 254 行 ─── 三种检索模式分别构造参数
private KnowledgeRagDTO buildKnowledgeRagDTO(...) {
    KnowledgeRagDTO knowledgeRagDTO = BeanUtil.toBean(dto, KnowledgeRagDTO.class);

    switch (KnowledgeSearchModeEnum.getEnum(searchMode)) {
        case EMBEDDING:
            // 纯向量检索
            knowledgeRagDTO.setVectorSearchDTO(vectorSearchDTO);
            break;
        case FULLTEXT_RECALL:
            // 纯全文检索
            knowledgeRagDTO.setFullTextSearchDTO(fullTextSearchDTO);
            break;
        case MIXED_RECALL:
            // ★ 混合检索: 按比例分配召回限额 ★
            MixSearchDTO mixSearchDTO = ...;
            // 向量召回: recallLimit * 0.6
            vectorSearchDTO.setRecallLimit(
                (int)(knowledgeRagDTO.getRecallLimit() * mixSearchDTO.getVectorSearchRatio()));
            // 全文召回: recallLimit * 0.4
            fullTextSearchDTO.setRecallLimit(
                (int)(knowledgeRagDTO.getRecallLimit() * mixSearchDTO.getFullTextSearchRatio()));
            knowledgeRagDTO.setMixSearchDTO(mixSearchDTO);
            knowledgeRagDTO.setVectorSearchDTO(vectorSearchDTO);
            knowledgeRagDTO.setFullTextSearchDTO(fullTextSearchDTO);
            break;
    }
    return knowledgeRagDTO;
}
```

---

## **KnowledgeSearchServiceImpl\.java 源码分析**

**文件**: `.../application/service/rag/KnowledgeSearchServiceImpl.java` **行数**: 550\+ 行 **职责**: RAG 检索核心引擎——权限、路由、融合、重排、截断

### **5\.1 knowledgeSearch\(\) 主方法**

```Java
// 第 168 行 ─── 核心检索方法
public KnowledgeSearchResultDTO knowledgeSearch(KnowledgeRagDTO dto) {
    long start = System.currentTimeMillis();

    // ===== Step 1: 参数校验 =====
    validateParam(dto);

    // ===== Step 2: 权限过滤 =====
    if (knowledgeAuthSearchSwitch && ragAuth) {
        List<String> authDatasetIds = getDatasetIdsByUserPermission(dto);
        if (CollectionUtils.isEmpty(authDatasetIds)) {
            // 无权限 → 直接返回空结果
            return KnowledgeSearchResultDTO.builder()
                .searchRes(Lists.newArrayList())
                .build();
        }
        dto.setDatasetIds(authDatasetIds);
    }
    filterPermissionKnowledge(dto);     // 知识库级权限
    filterDatasetIdsByEnabled(dto);     // 数据集启用/禁用

    // ===== Step 3: 检索模式路由 =====
    List<RecallResultDTO> recallResultDTOList = null;
    switch (KnowledgeSearchModeEnum.getEnum(dto.getSearchMode())) {
        case EMBEDDING:
            recallResultDTOList = vectorSearch(dto);     // → DenseVectorService
            break;
        case FULLTEXT_RECALL:
            recallResultDTOList = fullTextSearch(dto);   // → DenseVectorService
            break;
        case MIXED_RECALL:
            recallResultDTOList = mixSearch(dto);        // → 混合召回
            break;
    }

    // ===== Step 4: Token 截断 =====
    List<RecallResultDTO> resultDTOList = filterTokens(recallResultDTOList, dto.getLimit());

    // ===== Step 5: 过滤禁用数据集 =====
    result = filterDisEnabledDatasets(resultDTOList);

    // ===== Step 6: 图片地址替换 =====
    // s3:// → https://domain/resource?filePath=s3://
    result.forEach(o -> {
        if (o.getSourceText() != null && o.getSourceText().contains("s3://")) {
            o.setSourceText(o.getSourceText().replaceAll("s3://",
                config.getHostWebUrl().concat("/resource?filePath=s3://")));
        }
    });

    // ===== Step 7: 异步记录召回指标 =====
    if (!chatTestFlag) {
        updateRecallCount(result);  // Kafka/DB 异步记录
    }

    return KnowledgeSearchResultDTO.builder()
        .searchMode(dto.getSearchMode())
        .searchRes(result)
        .runningTime(runningTime)
        .build();
}
```

### **5\.2 mixSearch\(\) 混合召回**

```Java
// 第 427 行 ─── 混合召回核心
private List<RecallResultDTO> mixSearch(KnowledgeRagDTO dto) {
    // ===== 1) 并行执行两种检索 =====
    List<RecallResultDTO> vectorSearchResult = vectorSearch(dto);
    List<RecallResultDTO> fullTextSearchResult = fullTextSearch(dto);

    MixSearchDTO mixSearchDTO = dto.getMixSearchDTO();

    // ===== 2) 融合策略选择 =====
    List<RecallResultDTO> result;
    if (rrfSwitch) {
        // ★ RRF 融合 ★
        result = rrfRank(mixSearchDTO, vectorSearchResult, fullTextSearchResult);
    } else {
        // ★ 简单合并去重 ★
        result = merge(vectorSearchResult, fullTextSearchResult);
    }

    if (CollectionUtils.isEmpty(result)) {
        return Lists.newArrayList();
    }

    // ===== 3) ReRank 二次排序 =====
    if (reRankerSwitch) {
        result = reRanker(dto.getQuery(), dto.getRecallTypes(),
            mixSearchDTO.getReRankerServiceUniCode(),
            dto.getVectorSearchDTO().getSimilarity(), result);
    }

    return result;
}
```

### **5\.3 rrfRank\(\) RRF 融合**

```Java
// 第 516 行 ─── RRF 融合实现
private List<RecallResultDTO> rrfRank(
    MixSearchDTO mixSearchDTO,
    List<RecallResultDTO> vectorSearchResult,
    List<RecallResultDTO> fullTextSearchResult
) {
    // 1) 提取文档 ID + 权重
    List<String> vectorDataIds  = vectorSearchResult.stream().map(RecallResultDTO::getId).toList();
    List<String> fullTextDataIds = fullTextSearchResult.stream().map(RecallResultDTO::getId).toList();

    // vectorSearchRatio * 10 → 权重 (默认 0.6 * 10 = 6)
    RankResult vectorResult  = new RankResult(vectorDataIds,  mixSearchDTO.getVectorSearchRatio() * 10);
    // fullTextSearchRatio * 10 → 权重 (默认 0.4 * 10 = 4)
    RankResult fullTextResult = new RankResult(fullTextDataIds, mixSearchDTO.getFullTextSearchRatio() * 10);

    // 2) ★ 调用 RRF 算法 ★
    Map<String, Double> docIdScoreMap = RrfRankerUtil.rankFusion(
        List.of(vectorResult, fullTextResult), 60);  // k=60

    // 3) 按 RRF 分数降序组装结果
    docIdScoreMap.forEach((docId, rrfScore) -> {
        RecallResultDTO vectorDTO  = vectorMap.get(docId);
        RecallResultDTO fullTextDTO = fullTextMap.get(docId);
        // 合并 score 列表
        vectorDTO.getScore().addAll(fullTextDTO.getScore());
        vectorDTO.getScore().add(new SearchScoreItem("RRF", rrfScore));
        resultDTOList.add(vectorDTO);
    });

    return resultDTOList;
}
```

### **5\.4 reRanker\(\) 重排序**

```Java
// 第 455 行 ─── PEG Ranker 重排序
private List<RecallResultDTO> reRanker(
    String query,
    List<Integer> recallTypes,
    String reRankerServiceUniCode,
    Double similarity,                // ← 相似度阈值
    List<RecallResultDTO> result     // ← 待重排序的候选列表
) {
    // 1) 提取所有 chunk 文本
    List<String> contextList = result.stream()
        .map(RecallResultDTO::getQ)
        .collect(toList());

    // 2) 调用 PEG Ranker (交叉编码器)
    ModelParamDTO modelParamDTO = new ModelParamDTO();
    modelParamDTO.setInput(contextList);
    modelParamDTO.setContent(query);
    modelParamDTO.setReRankerServiceUniCode(reRankerServiceUniCode);

    List<PegRankerPrefabricationOutPutItemVO> ranker = modelService.pegRanker(modelParamDTO);

    if (CollectionUtils.isEmpty(ranker)) {
        return Lists.newArrayList();  // Rerank 失败 → 返回空
    }

    // 3) ★ 按 similarity 阈值过滤 + Rerank 分数降序 ★
    List<PegRankerPrefabricationOutPutItemVO> filterRanker = ranker.stream()
        .filter(e -> similarity <= e.getRanker())   // 过滤低分
        .sorted(Comparator.comparing(PegRankerPrefabricationOutPutItemVO::getRanker).reversed())
        .collect(toList());

    // 4) 为每个结果添加 Rerank 分数标记
    filterRanker.forEach(reRanker -> {
        RecallResultDTO recallResultDTO = result.get(reRanker.getIndex());
        double score = CalculateUtil.getDecimal(reRanker.getRanker());
        recallResultDTO.getScore().add(
            new SearchScoreItem(KnowledgeDataSearchScoreTypeEnum.RE_RANKER.getValue(), score));
        resultDTOList.add(recallResultDTO);
    });

    return resultDTOList;
}
```

---

## **RrfRankerUtil\.java 算法源码分析**

**文件**: `.../common/utils/RrfRankerUtil.java` **行数**: 61 行 **职责**: RRF \(Reciprocal Rank Fusion\) 算法实现

```Java
public class RrfRankerUtil {

    @Getter
    @AllArgsConstructor
    public static class RankResult {
        private List<String> docIds;    // 按排名排序的文档 ID 列表
        private Double weight;          // 通道权重
    }

    /**
     * 算法公式:
     *   RRF(d) = Σ weight_c * 1 / (k + rank_c(d))
     *
     *   weight_c: 通道 c 的权重
     *   rank_c(d): 文档 d 在通道 c 中的排名 (从 1 开始)
     *   k: 平滑系数 (通常设为 60)
     *
     * @param rankResults  每个检索通道的 {docIds, weight}
     * @param k            平滑因子, 默认 60
     * @return             按 RRF 分数降序的 {docId → score}
     */
    public static Map<String, Double> rankFusion(List<RankResult> rankResults, int k) {
        Map<String, Double> finalScores = new HashMap<>();

        for (RankResult result : rankResults) {
            List<String> docs = result.getDocIds();
            double weight = result.getWeight();

            // ★ 按排名遍历，计算 RRF 分数 ★
            IntStream.range(0, docs.size()).forEachOrdered(i -> {
                double score = weight * (1.0 / (k + i + 1));
                // i=0 → rank=1 → score = weight/(k+1)
                // i=1 → rank=2 → score = weight/(k+2)
                finalScores.merge(docs.get(i), score, Double::sum);
            });
        }

        // ★ 按分数降序，保持插入顺序 ★
        return finalScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (e1, e2) -> e1,
                LinkedHashMap::new
            ));
    }
}
```

**RRF 算法示例**:

```Plain Text
输入:
  向量检索结果 (weight=6):  [docA, docB, docC]  (排名1,2,3)
  全文检索结果 (weight=4):  [docB, docD, docA]  (排名1,2,3)

计算:
  docA: 6/(60+1) + 4/(60+3) = 0.0984 + 0.0635 = 0.1619
  docB: 6/(60+2) + 4/(60+1) = 0.0968 + 0.0656 = 0.1624  ← 最高
  docC: 6/(60+3) + 0         = 0.0952 + 0      = 0.0952
  docD: 0        + 4/(60+2)  = 0      + 0.0645 = 0.0645

输出:
  docB(0.1624) > docA(0.1619) > docC(0.0952) > docD(0.0645)
```

**面试要点**: RRF 为什么比加权平均好？

1. RRF 对**排名**进行融合，而非对原始分数融合

2. 不同检索器（BM25 和 Cosine）的分数分布差异巨大，无法直接加权

3. 排名是统一量纲，不受分数分布影响

4. k=60 平滑因子避免排名靠后的文档分数被幂律分布压扁

---

## **DenseVectorServiceImpl\.java 存储层分析**

**文件**: `.../densevector/application/service/DenseVectorServiceImpl.java` **职责**: ES 向量/全文检索 \+ Milvus ANN 检索 \+ 索引管理

### **7\.1 架构抽象**

```Java
@Service
public class DenseVectorServiceImpl extends AbsSearchServiceImpl implements DenseVectorService {

    @Autowired
    private ElasticsearchClient elasticsearchClient;  // ★ ES Java Client 8.x

    @Autowired
    private FullTextSpi fullTextSpi;                  // ★ 全文检索 SPI (底层可能是 ES 或 search-server)
    @Autowired
    private VectorSpi vectorSpi;                      // ★ 向量检索 SPI (底层可能是 Milvus 或 ES knn)

    @Value("${spring.elasticsearch.rest.indexName}")      // vector 索引名
    private String vectorIndexName;
    @Value("${spring.elasticsearch.rest.indexDataName}")   // data 索引名
    private String dataIndexName;
}
```

### **7\.2 两套检索路径**

```Plain Text
路径一: SPI 模式 (vectorSearch / fullTextSearch)
  → VectorSpi.search() / FullTextSpi.search()
  → 底层可能是 search-server 微服务
  → 旧的 Batch 导入流程使用

路径二: 直连模式 (directVectorSearch / directFullTextSearch)
  → ElasticsearchClient.search()  直接操作 ES
  → 新的实时检索使用
```

### **7\.3 directVectorSearch\(\) ES knn 向量检索**

```Java
// 第 354 行 ─── ES 原生 knn 检索
public KnowledgeDocDirectRecallVO directVectorSearch(KnowledgeDocVectorSearchDTO dto) {
    // 1) 构建过滤条件
    Query query = Query.of(q -> q.bool(b -> b
        .must(n -> n.terms(t -> t.field("datasetId")
            .terms(knowledge -> knowledge.value(knowledgeListValue))))
        .mustNot(n -> n.term(TermQuery.of(t ->
            t.field("isEnabled").value(1))))  // isEnabled ≠ 1 → 排除禁用数据集
    ));

    // 2) 构建 knn 查询
    String vectorFieldName = "vector_" + dto.getVectors().size();  // vector_768
    KnnQuery knnQuery = KnnQuery.of(m -> m
        .field(vectorFieldName)
        .queryVector(dto.getVectors())      // 768 维向量
        .numCandidates(dto.getNumCandidates())  // 粗排候选数
        .k(dto.getTopK())                   // 最终返回数
        .filter(query));                    // 过滤条件

    // 3) 执行 ES 查询
    // source 过滤掉 vector 字段 (不返回向量，减少传输)
    SearchResponse<JSONObject> searchResponse = elasticsearchClient.search(s -> s
        .index(indicesList)
        .source(source -> source.filter(e -> e.excludes(List.of(vectorFieldName))))
        .knn(knnQuery)
        .size(dto.getLimit()), JSONObject.class);

    return buildRecallResult(searchResponse);
}
```

### **7\.4 directFullTextSearch\(\) ES BM25 全文检索**

```Java
// 第 409 行 ─── ES bool 查询
public KnowledgeDocDirectRecallVO directFullTextSearch(KnowledgeDocDataSearchDTO dto) {
    String indexName = getIndexName(tenantId, teamId, knowledgeId, DATA.code());

    BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
    fillTermQuery(boolBuilder, dto);  // 填充 term/terms/match 条件

    SearchResponse<JSONObject> searchResponse = elasticsearchClient.search(s -> s
        .index(indexName)
        .query(b -> b.bool(boolBuilder.build()))  // ★ Bool Query → BM25 ★
        .size(dto.getLimit()), JSONObject.class);

    return buildRecallResult(searchResponse);
}
```

### **7\.5 两种检索模式的对比**

---

## **检索参数透传全链路**

```Plain Text
前端画布配置:
  searchMode: "mixedRecall"
  vectorSearchRatio: 0.6
  fullTextSearchRatio: 0.4
  reRankerSwitch: true
  similarity: 0.7
  recallLimit: 150
  datasets: ["know_001", "know_002"]
         │
         ▼ WorkFlow JSON
RunningModuleItemType.inputs: [
  {key: "searchMode", value: "mixedRecall"},
  {key: "vectorSearchRatio", value: 0.6},
  ...
]
         │
         ▼ moduleRun() → transParam()
Map<String, Object> params: {
  "searchMode": "mixedRecall",
  "vectorSearchRatio": 0.6,
  ...
}
         │
         ▼ JSON.parseObject(JSON.toJSONString(params))
DatasetModule (Java Bean)
         │
         ▼ buildKnowledgeRagDTO()
KnowledgeRagDTO {
  searchMode: "mixedRecall",
  vectorSearchDTO: {searchMode: "embedding", recallLimit: 90 (=150*0.6), ...},
  fullTextSearchDTO: {searchMode: "fullTextRecall", recallLimit: 60 (=150*0.4), ...},
  mixSearchDTO: {rrfSwitch: true, reRankerSwitch: true, ...}
}
         │
         ▼ knowledgeSearchService.knowledgeSearch()
         │
         ├── vectorSearch() → DenseVectorService.directVectorSearch()
         │   → ES knn query (k=90, field: vector_768)
         │
         ├── fullTextSearch() → DenseVectorService.directFullTextSearch()
         │   → ES bool query (size=60, BM25)
         │
         ├── rrfRank() → RrfRankerUtil.rankFusion()
         │   → 融合排序
         │
         └── reRanker() → modelService.pegRanker()
             → PEG Cross-Encoder 重排序
             → 过滤 similarity < 0.7
         │
         ▼
List<RecallResultDTO> [
  {id:"chunk_042", q:"电池容量5000mAh...", score:[{type:"RRF", val:0.98}, {type:"RE_RANKER", val:0.92}]},
  {id:"chunk_018", q:"充电参数5V/2A...",   score:[{type:"RRF", val:0.95}, {type:"RE_RANKER", val:0.87}]}
]
```

---

## **知识库类型与数据流**

```Plain Text
KnowledgeTypeEnum:
  FOLDER("folder")    —— 文件夹 (树形结构，不可检索)
  DATESET("dataset")  —— ★ 文档知识库 (ES + Milvus 双写)
  FAQ("faq")         —— ★ FAQ 知识库 (独立索引)
  GRAPH("graph")     —— ★ GraphRAG 知识库 (图存储)
  DATAFLOW("dataflow")—— DataFlow 自动化知识库
  TASK("task")       —— 任务知识库

可检索类型 (ALLOW_TYPES): DATESET, FAQ, GRAPH
```

---

## **面试核心 30 问**

---

# **模块源码分析 03：Agent 管理**

## **核心文件清单**

```Plain Text
agentflow-server/
├── agent-flow-app/agent-flow-agents/
│   └── src/main/java/com/msxf/pai/agent/agents/
│       ├── application/service/
│       │   ├── AgentServiceImpl.java              ★ Agent 基础 CRUD (含 WorkFlow Agent)
│       │   ├── AgentSnapshotServiceImpl.java      ★ 快照版本管理
│       │   └── autoagent/
│       │       └── AutoAgentServiceImpl.java      ★ AutoAgent 全生命周期 (1689 行)
│       ├── application/client/service/
│       │   ├── AgentService.java                  ★ Agent 接口
│       │   ├── AgentSnapshotService.java          ★ 快照接口
│       │   └── autoagent/
│       │       └── AutoAgentService.java          ★ AutoAgent 接口
│       ├── domain/po/
│       │   ├── Agent.java                         ★ Agent 主实体
│       │   ├── AgentSnapshot.java                 ★ 快照实体
│       │   └── autoagent/
│       │       └── AutoAgent.java                 ★ AutoAgent 元数据实体
│       └── domain/enums/
│           ├── AgentTypeEnum.java                 ★ Agent 类型: WORK_FLOW/AUTO_AGENT
│           ├── AgentFlowTypeEnum.java             ★ 流程类型
│           ├── AgentPublishStatusEnum.java        ★ 发布状态
│           └── AgentSnapshotStatusEnum.java       ★ 快照状态: PUBLISHED/ARCHIVED
│
├── agent-flow-server/
│   └── .../server/controller/
│       ├── AgentController.java                   ★ Agent REST API
│       └── AgentSnapshotController.java           ★ 快照 REST API
│
└── agent-flow-api/agent-flow-agents-api/
    └── .../dto/autoagent/
        ├── AutoAgentBaseDTO.java                  ★ 创建/编辑入参
        ├── AutoAgentDetailDTO.java                ★ 配置详情 (含 prompt/tools/knowledge)
        ├── AutoAgentToolsDTO.java                 ★ 工具定义
        ├── AutoAgentKnowledgeConfigDTO.java       ★ 知识库配置
        └── AutoAgentLongTermMemoryDTO.java        ★ 长期记忆配置
```

---

## **数据模型**

### **2\.1 核心实体关系**

```Plain Text
┌─────────────────────────────────────────┐
│  Agent (智能体主表)                       │
│  ─────────────────────────────────────   │
│  agentId     (PK)                       │
│  name        ← 名称                      │
│  intro       ← 描述                      │
│  typing      ← 类型: workflow/autoAgent  │
│  avatar      ← 头像                      │
│  version     ← 当前发布版本号             │
│  publicStatus← 发布状态: 编辑/已发布      │
│  shareStatus ← 共享状态                  │
│  templateId  ← 来源模板                  │
│  agentFramework ← "PA" / "adk"          │
│  enableUploadFiles ← 多模态             │
│  userId/teamId/tenantId ← 归属          │
│  remark      ← 发布备注                  │
│  tags        ← 标签冗余字段               │
└─────────────┬───────────────────────────┘
              │ 1:1
              ▼
┌─────────────────────────────────────────┐
│  AutoAgent (AutoAgent 元数据)             │
│  ─────────────────────────────────────   │
│  agentId     (FK → Agent)               │
│  promptInfo  ← ★ System Prompt          │
│  modelInfo   ← ★ 模型配置 JSON           │
│  tools       ← ★ 工具配置 JSON           │
│  knowledge   ← ★ 知识库配置 JSON          │
│  longTermMemory ← ★ 长期记忆 JSON        │
│  variableList   ← 全局变量列表 JSON       │
│  guideWords     ← 引导语                 │
│  recommends     ← 推荐问题               │
│  enableUploadFiles ← 文件上传            │
│  nextStepEnable   ← 追问开关             │
└─────────────┬───────────────────────────┘
              │ 1:N (每个版本一个快照)
              ▼
┌─────────────────────────────────────────┐
│  AgentSnapshot (发布快照)                 │
│  ─────────────────────────────────────   │
│  agentId     (FK → Agent)               │
│  version     ← ★ 版本号 (1,2,3...)      │
│  moduleJson  ← ★ 完整配置 JSON 快照       │
│  snapshotStatus ← PUBLISHED/ARCHIVED    │
│  defaultStatus  ← 是否默认版本            │
│  shareStatus    ← 共享状态               │
│  remark     ← 发布备注                   │
│  tags       ← 标签                       │
└─────────────────────────────────────────┘
```

### **2\.2 Agent 类型枚举**

```Java
public enum AgentTypeEnum {
    WORK_FLOW("workflow"),    // 画布编排的 WorkFlow Agent
    AUTO_AGENT("autoAgent"),  // ★ LLM 驱动的自主 Agent
    DATAFLOW("dataflow");     // DataFlow 任务 Agent
}

// Agent 框架类型
agentFramework:
  "flow"  // 老版 WorkFlow 引擎
  "PA"    // ★ 新版 AutoAgent 框架 (Google ADK)
```

### **2\.3 AutoAgent 的 JSON 结构**

```JSON
// AutoAgent 表中的 tools 字段 (JSON 字符串):
{
  "pluginInfoList": [                    // 插件工具
    {"id": "plugin_001", "name": "天气查询", "description": "...", "parameters": {...}}
  ],
  "workflowInfoList": [                  // WorkFlow 工具
    {"id": "wf_001", "name": "数据报表", "description": "..."}
  ],
  "agentInfoList": [                     // ★ 子 Agent 工具 (可嵌套!)
    {"id": "agent_sub_001", "name": "数据分析师", "description": "..."}
  ],
  "mcpInfoList": [                       // MCP 工具
    {"id": "mcp_001__tool_name", "name": "数据库查询", "description": "..."}
  ]
}

// AutoAgent 表中的 knowledge 字段 (JSON):
{
  "retrieveMaxLength": 2048,
  "showSource": true,
  "backupStrategy": {"backupMode": 2, "customAnswer": "暂无相关数据"},
  "knowledgeInfoList": [
    {"id": "know_001", "name": "产品手册", "description": "产品规格...",
     "searchStrategy": 3, "useRerank": true, "maxRecallCount": 150, "minScore": 0.7}
  ]
}

// AutoAgent 表中的 longTermMemory 字段 (JSON):
{
  "enabled": true,
  "memoryFields": ["user_preference", "last_query_topic"]
}

// AutoAgent 表中的 modelInfo 字段 (JSON):
{
  "modelId": "maip_gpt-4",
  "modelUrl": "https://api.example.com/v1",
  "maxTokens": 4096,
  "temperature": 0.0,
  "historyRound": 3,
  "timeout": 60,
  "maxIterTimes": 20
}
```

---

## **AutoAgentServiceImpl\.java 源码分析**

**文件**: `.../service/autoagent/AutoAgentServiceImpl.java` **行数**: 1689 行 **职责**: AutoAgent 全生命周期管理——创建/编辑/发布/快照/循环检测/调用关系

### **3\.1 创建 Agent: create\(\)**

```Java
// 第 175 行 ─── 创建入口
@Transactional
public String create(AutoAgentBaseDTO autoAgentBaseDTO) {
    SessionUserInfo userInfo = SessionThreadLocalUtil.getCurrentUserInfo();
    // 1) 校验: 名称正则 [a-zA-Z一-龥][a-zA-Z0-9_一-龥]*
    validateAgentBaseProperties(autoAgentBaseDTO, userInfo);
    // 2) 创建
    return createAgent(autoAgentBaseDTO, userInfo);
}

// 第 240 行 ─── 创建 Agent 主记录
public String createAgent(AutoAgentBaseDTO autoAgentBaseDTO, SessionUserInfo userInfo) {
    Agent agent = new Agent();
    agent.setAgentId(UuidUtil.getUUID());        // ★ UUID 唯一标识
    agent.setName(autoAgentBaseDTO.getName());
    agent.setIntro(autoAgentBaseDTO.getIntro());
    agent.setTyping(autoAgentBaseDTO.getType()); // "autoAgent"
    agent.setAgentFramework("PA");               // ★ 固定为 "PA" (Production Agent)
    agent.setVersion(AGENT_INIT_VERSION);        // 初始版本 0
    agent.setUserId(userInfo.getUserId().toString());
    agent.setTeamId(userInfo.getOrgId().toString());
    agent.setTenantId(userInfo.getTenantCode());
    // 默认数据权限: TEAM_EDIT (团队可编辑)
    agent.setDataPermissionScope(
        null == dataPermissionScope ? TEAM_EDIT : dataPermissionScope);
    agentMapper.insertSelective(agent);          // ★ 写入 Agent 表
    // 保存标签
    agentService.saveAgentTags(tags, tenantCode, orgId, userName, agentId);
    return agentId;
}
```

**创建时的校验逻辑**: \| 校验项 \| 规则 \| 错误码 \| \|\-\-\-\-\-\-\-\-\|\-\-\-\-\-\-\|\-\-\-\-\-\-\-\-\| \| 名称非空 \| `StrUtil.isBlank(name)` \| `AUTO_AGENT_NAME_CANNOT_BE_EMPTY` \| \| 名称格式 \| `^[a-zA-Z一-龥][a-zA-Z0-9_一-龥]*$` \| `AUTO_AGENT_NAME_IS_NOT_VALID` \| \| 名称长度 \| `≤ 100` \| `NAME_OVER_LENGTH` \| \| 名称唯一 \| 同一团队下无同名 \| `AGENT_NAME_IS_EXIST` \| \| 描述非空 \| `StrUtil.isBlank(intro)` \| `AUTO_AGENT_THE_DESCRIPTION_...` \| \| 标签数量 \| `≤ TAG_LIMIT_NUM` \| `AGENT_MAX_TAG_COUNT` \| \| 标签长度 \| `≤ TAG_LIMIT_LENGTH` \| `AGENT_TAG_LENGTH_OVER_LIMIT` \|

### **3\.2 保存/更新配置: createOrUpdate\(\)**

```Java
// 第 818 行 ─── 保存 Agent 配置
@Transactional
public String createOrUpdate(AutoAgentDetailDTO autoAgentDetailDTO) {
    // Step 1: 更新 Agent 主表 (名称/描述/标签/头像)
    String agentId = saveOrUpdateAgentBase(autoAgentDetailDTO);
    // Step 2: 更新 AutoAgent 元数据表 (prompt/model/tools/knowledge/memory)
    createAutoAgent(autoAgentDetailDTO);
    return agentId;
}
```

`createAutoAgent()` 是核心配置写入方法 \(第 886 行\)，做了以下事情:

```Java
public void createAutoAgent(AutoAgentDetailDTO dto) {
    AutoAgent autoAgent = new AutoAgent();
    autoAgent.setAgentId(dto.getAgentId());
    autoAgent.setPromptInfo(dto.getPromptInfo());       // ★ System Prompt

    // ★ 知识库配置校验 ★
    AutoAgentKnowledgeConfigDTO knowledge = dto.getKnowledge();
    if (Objects.nonNull(knowledge)) {
        if (knowledgeInfoList.size() > MAX_TOOL_NUM) {  // 最多 10 个
            throw new BusinessException("知识库数量超限");
        }
        // 全局设置不能为空: backupStrategy / showSource / retrieveMaxLength
        if (backupStrategy==null || showSource==null || retrieveMaxLength==null) {
            throw new BusinessException("知识库全局设置不能为空");
        }
    }
    autoAgent.setKnowledge(JSONUtil.toJsonStr(knowledge));

    // ★ 长期记忆 ★
    autoAgent.setLongTermMemory(JSONUtil.toJsonStr(dto.getLongTermMemory()));

    // ★ 模型配置 ★
    autoAgent.setModelInfo(JSONUtil.toJsonStr(dto.getModelInfo()));

    // ★ 工具配置校验 ★
    Map<String, List<AutoAgentToolsDTO>> tools = dto.getTools();
    if (CollectionUtil.isNotEmpty(tools)) {
        for (entrySet : tools.entrySet()) {
            if (toolsDTOS.size() > MAX_TOOL_NUM) {
                throw new BusinessException("工具数量超限"); // 每种 ≤ 10
            }
            // 名称和描述不能为空
            if (toolsDTOS.stream().anyMatch(it -> isBlank(it.getName()) || isBlank(it.getDescription()))) {
                throw new BusinessException("工具名称和描述不能为空");
            }
            // ★ WorkFlow 工具需检查发布状态 ★
            if ("workflowInfoList".equals(entrySet.getKey())) {
                checkWorkflowToolEnabled(toolsDTOS);  // WorkFlow 下架则报错
            }
        }
    }
    autoAgent.setTools(objectMapper.writeValueAsString(tools));

    // ★ 全局变量 ★
    autoAgent.setVariableList(JSONUtil.toJsonStr(dto.getVariableList()));

    // ★ 推荐问题/引导语 ★
    autoAgent.setRecommends(JSONUtil.toJsonStr(dto.getRecommends()));
    autoAgent.setGuideWords(dto.getGuideWords());
    autoAgent.setEnableUploadFiles(dto.getEnableUploadFiles());

    // 存在则更新，不存在则插入
    AutoAgent exists = this.baseMapper.getByAgentId(agentId);
    if (exists != null) {
        autoAgent.setId(exists.getId());
    }
    this.saveOrUpdate(autoAgent);  // ★ MyBatis-Plus saveOrUpdate
}
```

**面试要点**: `createAutoAgent` 中的 JSON 序列化/反序列化策略。`tools`、`knowledge`、`modelInfo`、`longTermMemory` 这些复杂嵌套对象，在 MySQL 中作为 JSON 字符串存储。MyBatis\-Plus 不提供自动序列化，需要手动 `objectMapper.writeValueAsString()` / `JSONUtil.toJsonStr()`。

### **3\.3 发布 Agent: autoAgentPublish\(\)**

```Java
// 第 461 行 ─── 发布核心 (125 行)
@Transactional
public Boolean autoAgentPublish(AgentPublishDTO dto) {
    // ===== 1) 基础检查 =====
    Agent agent = agentExtMapper.findByAgentId(userInfo, dto.getAgentId());

    // ===== 2) 构建快照 =====
    AgentSnapshot snapshot = buildAutoAgentSnapshot(dto, agent, date, userId, userName);

    // ===== 3) ★ 覆盖发布 vs 新增发布 ★ =====
    if (dto.isCovered()) {
        // 覆盖发布: 覆盖指定版本
        Integer publishVersion = dto.getVersion();
        AgentSnapshot existSnapshot = agentSnapshotService.findByAgentIdAndVersion(agentId, publishVersion);
        // 将该版本的旧记录设为归档 (ARCHIVED)
        updateWrapper.set(snapshotStatus, ARCHIVED)
                     .eq(agentId).eq(version, publishVersion);
        agentSnapshotService.update(updateWrapper);
        snapshot.setVersion(publishVersion);  // 复用原版本号
        // 是否设为默认
        if (isSetDefault) {
            snapshot.setDefaultStatus(1);
            // 将原默认版本取消
            updateWrapper.set(defaultStatus, 0).eq(agentId).eq(defaultStatus, 1);
        }
    } else {
        // 新增发布: 版本号自增
        AgentSnapshot maxVersion = agentSnapshotService.findMaxVersion(agentId);
        if (maxVersion == null) {
            snapshot.setDefaultStatus(1);      // 首次发布 → 默认版本
            snapshot.setVersion(1);
        } else {
            snapshot.setVersion(maxVersion.getVersion() + 1);  // 版本号 +1
        }
        if (dto.isSetDefault()) {
            // 取消原默认版本 → 设置新默认版本
            updateWrapper.set(defaultStatus, 0).eq(agentId).eq(defaultStatus, 1);
            snapshot.setDefaultStatus(1);
        }
    }

    // ===== 4) 更新 Agent 主表 =====
    agent.setVersion(snapshot.getVersion());
    agent.setPublicStatus(ONLINE);      // ← 标记为已发布
    agentMapper.updateByPrimaryKeySelective(agent);

    // ===== 5) 异步 CMDB 同步 =====
    if (cmdbSyncEnabled) {
        CompletableFuture.runAsync(() -> asyncSyncAgentToCmdb(agent));
    }

    // ===== 6) 插入快照记录 =====
    snapshot.setModuleJson(getAutoModuleJson(agent));  // ★ 完整配置快照
    agentSnapshotMapper.insert(snapshot);

    // ===== 7) 保存发布参数 =====
    AgentParams params = agentParamsService.getByAgentId(agentId);
    if (params == null) {
        agentParamsService.save({agentId, publishParam: dto.getParam()});
    } else {
        agentParamsService.updateById({publishParam: dto.getParam()});
    }
    return true;
}
```

**面试要点**: 发布有两种模式：

### **3\.4 构建快照: buildAutoAgentSnapshot\(\)**

```Java
// 第 676 行 ─── 快照数据序列化
private AgentSnapshot buildAutoAgentSnapshot(...) {
    AgentSnapshot snapshot = new AgentSnapshot();
    // ★ 从 Agent 表拷贝基础信息 ★
    snapshot.setAgentId(agent.getAgentId());
    snapshot.setName(agent.getName());
    snapshot.setIntro(agent.getIntro());
    snapshot.setVersion(agent.getVersion());
    // ★ 核心: moduleJson = AutoAgent 表的完整配置 ★
    snapshot.setModuleJson(getAutoModuleJson(agent));
    snapshot.setSnapshotStatus(PUBLISHED);   // 状态: 已发布
    snapshot.setShareStatus(NOT_SHARED);     // 共享: 未共享
    snapshot.setRemark(dto.getRemark());     // 发布备注
    return snapshot;
}

// 第 717 行 ─── 序列化 AutoAgent 配置为快照 JSON
private String getAutoModuleJson(Agent agent) {
    // 1) 查询 AutoAgent 表
    List<AutoAgent> autoAgents = autoAgentMapper.selectList(
        queryWrapper.eq(AutoAgent::getAgentId, agent.getAgentId()));
    // 2) transform: AutoAgent → AutoAgentDTO  (JSON 反序列化 → DTO)
    List<AutoAgentDTO> list = autoAgents.stream().map(autoAgent -> {
        AutoAgentDTO dto = new AutoAgentDTO();
        dto.setPromptInfo(autoAgent.getPromptInfo());
        dto.setModelInfo(parseJSON(autoAgent.getModelInfo()));
        dto.setTools(parseJSON(autoAgent.getTools()));
        dto.setKnowledge(parseJSON(autoAgent.getKnowledge()));
        dto.setLongTermMemory(parseJSON(autoAgent.getLongTermMemory()));
        dto.setVariableList(parseJSON(autoAgent.getVariableList()));
        return dto;
    }).collect(toList());
    // 3) 序列化为 JSON
    return mapperUtils.toJsonString(list);
}
```

**面试要点**: `moduleJson` 是 Agent 的**完整配置快照**。一旦发布，即使后续编辑 AutoAgent 表，已发布的快照不受影响。这是实现**版本回滚**和**不可变发布**的基础。

### **3\.5 循环检测: hasCycle\(\)**

```Java
// 第 1236 行 ─── 循环检测入口
public boolean cycleDetected(AutoAgentDTO agentDTO) {
    Map<String, List<AutoAgentToolsDTO>> tools = agentDTO.getTools();
    // 只检查 agentInfoList (子 Agent 调用)
    if (isEmpty(tools) || isEmpty(tools.get("agentInfoList"))) {
        return false;
    }
    return hasCycle(userInfo, agentDTO);
}

// 第 1261 行 ─── ★ DFS 循环检测 ★
private boolean hasCycleHelper(Set<String> visited, SessionUserInfo userInfo, AutoAgentDTO agentDTO) {
    if (Objects.isNull(agentDTO)) return false;

    String toolId = agentDTO.getAgentId();
    // ★ 已访问过 → 发现环! ★
    if (visited.contains(toolId)) {
        return true;
    }
    visited.add(toolId);                    // ← 标记访问

    // 遍历子 Agent 工具
    Map<String, List<AutoAgentToolsDTO>> tools = agentDTO.getTools();
    if (tools != null) {
        List<AutoAgentToolsDTO> subAgents = tools.getOrDefault("agentInfoList", emptyList());
        for (AutoAgentToolsDTO child : subAgents) {
            // 递归查询子 Agent 配置
            agentDTO = this.findByAgentId(userInfo, child.getId(), ...);
            if (hasCycleHelper(visited, userInfo, agentDTO)) {
                return true;               // ← 子链中发现环
            }
        }
    }

    visited.remove(toolId);                // ★ 回溯 ★
    return false;
}
```

**DFS 回溯理解**:

```Plain Text
AgentA → AgentB → AgentC → AgentA

调用栈:
hasCycleHelper(A, visited={})
  visited = {A}
  查 A 的子 Agent → [B]
    hasCycleHelper(B, visited={A})
      visited = {A, B}
      查 B 的子 Agent → [C]
        hasCycleHelper(C, visited={A, B})
          visited = {A, B, C}
          查 C 的子 Agent → [A]
            hasCycleHelper(A, visited={A, B, C})
              A in visited → return TRUE!  ← 发现环
```

### **3\.6 删除 Agent: deleteAutoAgent\(\)**

```Java
// 第 1350 行 ─── 删除 (级联清理引用)
@Transactional
public void deleteAutoAgent(String agentId) {
    // 1) 清理编辑态: AutoAgent 表中引用此 agentId 的 agentInfoList
    List<AutoAgent> autoAgents = autoAgentMapper.findByAgentId(agentId);
    autoAgents.forEach(autoAgent -> {
        Map<String, List<AutoAgentToolsDTO>> tools = parseJSON(autoAgent.getTools());
        if (tools.containsKey("agentInfoList")) {
            // ★ 过滤掉被删除的 Agent ★
            List<AutoAgentToolsDTO> filtered = tools.get("agentInfoList").stream()
                .filter(dto -> !dto.getId().equals(agentId))
                .collect(toList());
            tools.put("agentInfoList", filtered);
        }
        autoAgent.setTools(toJSON(tools));
    });
    this.saveOrUpdateBatch(autoAgents);

    // 2) 清理发布态: AgentSnapshot 表中引用此 agentId 的 moduleJson
    List<AgentSnapshot> snapshots = agentSnapshotMapper.selectAutoAgent(agentId);
    snapshots.forEach(snapshot -> {
        List<AutoAgentDTO> dtos = parseJSON(snapshot.getModuleJson());
        dtos.stream().map(ParentAutoAgentDTO::getTools).forEach(tools -> {
            if (tools.containsKey("agentInfoList")) {
                tools.put("agentInfoList",
                    tools.get("agentInfoList").stream()
                        .filter(dto -> !dto.getId().equals(agentId))
                        .collect(toList()));
            }
        });
        snapshot.setModuleJson(toJSON(dtos));
    });
    agentSnapshotService.saveOrUpdateBatch(snapshots);
}
```

**面试要点**: 删除 Agent 不只是删自身记录，还需要**级联清理**所有引用此 Agent 的其他 Agent 的 `agentInfoList` 工具列表，包括编辑态 \(AutoAgent 表\) 和发布态 \(AgentSnapshot 表\) 两份数据。

---

## **调用关系查询: viewCallRelationship\(\)**

```Java
// 第 1288 行 ─── 查询谁引用了当前 Agent
public PageInfo<AutoAgentCallRelationshipVO> viewCallRelationship(
    SessionUserInfo userInfo, AutoAgentCallRelationshipDTO dto
) {
    // ★ 三种引用来源并行查询 ★
    CompletableFuture<List<...>> workflowFuture = CompletableFuture.supplyAsync(
        () -> workflowValue(dto),      // 1) WorkFlow Agent 引用
        ExecutorUtil.workFlowExecutor  //    专用线程池
    );
    CompletableFuture<List<...>> abpFuture = CompletableFuture.supplyAsync(
        () -> conToAbpValue(userInfo, dto),  // 2) ABP 平台引用
        ExecutorUtil.abpExecutor             //    专用线程池
    );
    CompletableFuture<List<...>> autoAgentFuture = CompletableFuture.supplyAsync(
        () -> autoAgentValue(dto),     // 3) AutoAgent 引用 (编辑态+发布态)
        ExecutorUtil.antoAgentExecutor //    专用线程池
    );

    // ★ 合并结果: workflow + abp + autoAgent ★
    return workflowFuture
        .thenCombine(abpFuture, (w, a) -> { combined.addAll(w); combined.addAll(a); })
        .thenCombine(autoAgentFuture, (ca, aa) -> { ca.addAll(aa); return ca; })
        .thenApply(list → page(list, dto.getPageNum(), dto.getPageSize()))
        .join();
}
```

---

## **完整生命周期状态机**

```Plain Text
┌──────────────────────────────────────────────────────────────┐
│  Agent 生命周期                                                │
│                                                              │
│  创建 (create)                                               │
│    agent.publicStatus = null (编辑态)                         │
│    agent.version = 0                                         │
│    │                                                         │
│    ▼                                                         │
│  编辑配置 (createOrUpdate)                                    │
│    AutoAgent 表更新 prompt/tools/knowledge/model             │
│    │                                                         │
│    ▼                                                         │
│  发布 (autoAgentPublish)                                     │
│    ├── isCovered=false (新增发布)                              │
│    │   ├── version = maxVersion + 1                          │
│    │   └── AgentSnapshot 新增记录                             │
│    ├── isCovered=true (覆盖发布)                               │
│    │   ├── version = 指定版本号                                │
│    │   └── 旧 AgentSnapshot → ARCHIVED                       │
│    │   └── 新 AgentSnapshot 插入                              │
│    ├── isSetDefault=true → 原默认版本取消 → 新版本设为默认      │
│    └── agent.publicStatus = ONLINE                           │
│                                                              │
│  共享 (share)                                                 │
│    agent.shareStatus = SHARED                                │
│    │                                                         │
│    ▼                                                         │
│  下架/删除 (deleteAutoAgent)                                  │
│    ├── 清理编辑态引用 (AutoAgent 表)                            │
│    ├── 清理发布态引用 (AgentSnapshot 表)                       │
│    └── agent.publicStatus = OFFLINE                          │
└──────────────────────────────────────────────────────────────┘

AgentSnapshot 状态机:
  PUBLISHED → (覆盖发布) → ARCHIVED
  defaultStatus: 0(普通) / 1(默认版本)
```

---

## **面试核心 20 问**

---

# **模块源码分析 04：对话/Chat**

## **核心文件清单**

```Plain Text
agentflow-server/
├── agent-flow-app/agent-flow-workflow/
│   └── .../module/application/service/
│       ├── ChatCompletionServiceImpl.java      ★ chatNode 节点入口 (141 行)
│       ├── AISummaryService.java               ★ 对话摘要 + 历史管理 (241 行)
│       ├── AiCompletionCommonService.java      ★ LLM 调用公共逻辑 (366 行)
│       ├── AIApiService.java                   ★ 模型调用门面 (100+ 行)
│       └── .../workflow/application/util/
│           └── ChatContextFilter.java          ★ Token 截断 + 角色适配 (237 行)
│
├── agent-flow-app/agent-flow-serving/
│   └── .../serving/application/util/
│       └── SSEUtils.java                       ★ SSE 流式推送 (80 行)
│
└── agent-flow-app/agent-flow-agents/
    └── .../agents/domain/po/
        └── ChatItem.java                       ★ 对话消息实体
```

---

## **chatNode 执行全链路**

```Plain Text
chatNode.execute(dispatchData)
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ ChatCompletionServiceImpl.execute()                             │
│                                                                  │
│ Step 1: getChatItems()                                          │
│   加载对话历史: moduleInput 中的 userPrompt/assistant             │
│   + dispatchData.histories (ChatItem 表)                        │
│   + historyFilterType 过滤 (仅保留 Human/AI)                     │
│                                                                  │
│ Step 2: getLastSummary()                                        │
│   从 ChatItem.moduleSummaryData 中查找上次摘要                    │
│   裁切摘要节点之前的历史 (subList)                                │
│                                                                  │
│ Step 3: getModelListVO()                                        │
│   查询模型配置: modelService.queryModelList()                     │
│   → 匹配 serviceUniCode → 获取 modelUrl + hyperParameter         │
│                                                                  │
│ Step 4: filterQuote()                                           │
│   quoteQA (知识库检索结果) → 按 token 截断                        │
│   → MessageFormatUtils 格式化为 quoteText                        │
│                                                                  │
│ Step 5: getChatMessages()                                       │
│   组装完整 messages:                                             │
│     [0] systemPrompt + [history] + lastSummary                   │
│     [1..N] chatHistories                                        │
│     [N+1] question + quoteText                                  │
│   超长检测 → 触发摘要生成                                         │
│                                                                  │
│ Step 6: aiChat()                                                │
│   调用 LLM: AIApiService.createNew2()                            │
│     → stream=true  → modelManageService.modelProcessStreamNew()  │
│     → stream=false → modelManageService.modelProcess()           │
│   SSE 推送: ANSWER 事件 → 前端实时渲染                            │
│                                                                  │
│ Step 7: 返回结果                                                 │
│   {                                                              │
│     answerText: "电池容量为5000mAh...",                          │
│     history: [...],          // 完整对话历史                     │
│     reasoningContent: "...", // DeepSeek-R1 推理内容              │
│     responseData: {          // 执行详情                         │
│       inputTokens, outputTokens, quoteList, summaryPrompt...    │
│     }                                                            │
│   }                                                              │
└─────────────────────────────────────────────────────────────────┘
```

---

## **ChatCompletionServiceImpl\.java 源码分析**

**文件**: `.../module/application/service/ChatCompletionServiceImpl.java` **行数**: 141 行 **注解**: `@NodeType(FlowNodeTypeEnum.CHAT_NODE)`

### **3\.1 execute\(\) 全流程**

```Java
@Override
public Map<String, Object> execute(DispatchData dispatchData) {
    // 1) JSON → ChatCompletion (含 systemPrompt/quotePrompt/quoteQA/temperature...)
    ChatCompletion chatCompletion = JSON.parseObject(
        JSON.toJSONString(dispatchData.getParams()), ChatCompletion.class);

    // 2) 加载对话历史 + 全局变量引用历史 + historyFilterType 过滤
    List<ChatItem> chatHistories = aiCompletionCommonService.getChatItems(dispatchData, chatCompletion);

    // 3) ★ 获取摘要 (从 ChatItem.moduleSummaryData 读取) ★
    String lastSummary = "";
    if (isBlank(chatCompletion.getLastSummary())) {
        LastSummaryResult result = aiSummaryService.getLastSummary(
            chatHistories, CHAT_NODE, dispatchData.getModuleId(), chatCompletion.isSummary());
        lastSummary = result.getLastSummary();
        chatHistories = result.getHistories();  // 裁切摘要节点之前的旧历史
    } else {
        lastSummary = chatCompletion.getLastSummary();  // 变量引用
    }

    // 4) 获取模型配置 (含 maxInput 超参数)
    ModelListVO modelVO = aiCompletionCommonService.getModelListVO(
        chatCompletion.getModel(), dispatchData.getUserInfo());

    // 5) ★ 过滤知识库引用 (按 token 截断) ★
    FilterQuote filterQuote = filterQuote(quoteQA, quoteTemplate);

    // 6) ★ 组装 ChatMessages (核心) ★
    ChatMessagesResult chatMessages = aiSummaryService.getChatMessages(
        chatCompletion, chatHistories, filterQuote.getQuoteText(),
        modelVO, temperature, lastSummary, maxInput, dispatchData.getUserInfo());

    // 7) ★ 调用 LLM ★
    return aiCompletionCommonService.aiChat(
        dispatchData, chatCompletion, lastSummary, modelVO, filterQuote, chatMessages);
}
```

### **3\.2 filterQuote\(\) 知识库引用截断**

```Java
// 第 77 行
FilterQuote filterQuote(List<SearchDataResponseItemType> quoteQA, String quoteTemplate) {
    // 1) ★ JTokkit 精确 token 计算 ★
    List<SearchDataResponseItemType> filterQuoteQA = filterSearchResultsByMaxChars(quoteQA, Integer.MAX_VALUE);

    // 2) 格式化为引用文本
    StringBuilder quoteText = new StringBuilder();
    for (int i = 0; i < filterQuoteQA.size(); i++) {
        SearchDataResponseItemType item = filterQuoteQA.get(i);
        // 模板: "[{i}] {item.q}" 或 "[{i}] {item.q} [{item.a}]"
        quoteText.append(MessageFormatUtils.getValue(item, i, quoteTemplate));
        if (i != filterQuoteQA.size() - 1) {
            quoteText.append("\n");
        }
    }
    return new FilterQuote(filterQuoteQA, quoteText.toString());
}

private List<SearchDataResponseItemType> filterSearchResultsByMaxChars(
    List<SearchDataResponseItemType> list, Integer maxTokens
) {
    List<SearchDataResponseItemType> result = new ArrayList<>();
    Integer totalTokens = 0;
    for (SearchDataResponseItemType item : list) {
        // ★ JTokkit 精确计算 q+a 的 token 数 ★
        totalTokens += chatContextFilter.countPromptTokens(item.getQ() + item.getA(), "");
        result.add(item);
        if (totalTokens > maxTokens) {
            break;  // 超限截断
        }
    }
    // 兜底: 至少返回第 1 条
    if (CollectionUtils.isEmpty(result)) {
        return list.subList(0, 1);
    }
    return result;
}
```

---

## **AISummaryService\.java 源码分析**

**文件**: `.../module/application/service/AISummaryService.java` **行数**: 241 行

### **4\.1 getLastSummary\(\) 摘要读取**

```Java
public LastSummaryResult getLastSummary(
    List<ChatItem> histories, String moduleType, String moduleId, boolean summary
) {
    // 未开启摘要 → 直接返回
    if (isEmpty(histories) || !summary) {
        return new LastSummaryResult("", histories);
    }

    // ★ 遍历历史，查找 moduleSummaryData 中匹配当前 nodeId 的摘要 ★
    Integer summaryIndex = null;
    for (int i = 0; i < histories.size(); i++) {
        ChatItem item = histories.get(i);
        if (item.getModuleSummaryData() != null) {
            List<ModuleSummary> moduleSummaryList = JSONUtil.toList(
                item.getModuleSummaryData(), ModuleSummary.class);
            for (ModuleSummary ms : moduleSummaryList) {
                // ★ 组件取自己的摘要: moduleType + moduleId 双重匹配 ★
                if (!moduleType.equals(ms.getModuleType())
                    || !moduleId.equals(ms.getModuleId())) {
                    continue;
                }
                if (isNotBlank(ms.getSummary()) && isNotBlank(ms.getLastSummary())) {
                    lastSummary = ms.getSummary();
                    summaryIndex = i;  // 裁切点
                } else if (isNotBlank(ms.getSummary())) {
                    lastSummary = ms.getSummary();
                    summaryIndex = i;
                }
            }
        }
    }

    // ★ 裁切摘要节点之前的旧历史 (保留摘要所在的那一轮及之后) ★
    if (summaryIndex != null) {
        if (summaryIndex >= 1 && summaryIndex <= histories.size() - 1) {
            histories = histories.subList(summaryIndex - 1, histories.size());
        }
    }
    return new LastSummaryResult(lastSummary, histories);
}
```

### **4\.2 getChatMessages\(\) Prompt 组装**

```Java
ChatMessagesResult getChatMessages(
    ChatCompletion chatCompletion, List<ChatItem> chatHistories,
    String quoteText, ModelListVO modelVO, Float temperature,
    String lastSummary, Integer maxInput, SessionUserInfo userInfo
) {
    // 1) ★ 拼接 User Prompt ★
    String question;
    if (isNotBlank(quoteText)) {
        // 有知识库引用: quotePrompt 模板套用
        // "参考资料:\n{quote}\n\n问题: {question}"
        Map<String, Object> quoteMap = Map.of("quote", quoteText, "question", userChatInput);
        question = MessageFormatUtils.replaceVariable(quotePrompt, quoteMap);
    } else {
        question = userChatInput;
    }

    // 2) ★ 获取摘要 + 超长处理 ★
    SummaryResult summaryResult = getSummaryResult(
        systemPrompt, question, summary, reqId, maxInput, chatHistories, modelVO, temperature, lastSummary, userInfo);

    // 3) ★ 适配为 GPT 格式 ★
    List<ChatMessageItemType> adaptMessages = chatContextFilter.adaptChat2GptMessages(
        summaryResult.getFilterMessages(), false);
    return new ChatMessagesResult(adaptMessages, summaryResult);
}
```

### **4\.3 getSummaryResult\(\) 三级超长策略**

```Java
SummaryResult getSummaryResult(String systemPrompt, String question, boolean summary, ...) {
    // ===== 1) 组装基础 messages =====
    List<ChatItem> messages = new ArrayList<>();
    // System 消息: systemPrompt + [history] + lastSummary
    ChatItem systemChatItem = new ChatItem();
    systemChatItem.setObj("System");
    if (isNotBlank(lastSummary)) {
        systemChatItem.setValue(systemPrompt + "\n[history]\n" + lastSummary);
    } else {
        systemChatItem.setValue(systemPrompt);
    }
    messages.add(systemChatItem);
    // 追加对话历史
    messages.addAll(chatHistories);
    // 追加当前问题
    ChatItem humanChatItem = new ChatItem();
    humanChatItem.setObj("Human");
    humanChatItem.setValue(question);
    messages.add(humanChatItem);

    // ===== 2) 超长检测 ★ 第一级 ★ =====
    ChatFilterResult filterResult = getFilterMessages(messages, modelVO, summary);
    boolean aiSummary = filterResult.isAiSummary();   // Token 超限 → 需要摘要
    List<ChatItem> filterMessages = filterResult.getResult();

    // ===== 3) ★ 第二级: LLM 摘要压缩 ★ =====
    if (aiSummary) {
        // 调用 LLM 生成摘要
        summaryResult = getAISummary(chatHistories, modelVO, summary, maxInput, temperature, reqId, lastSummary, userInfo);
        String summaryPrompt = summaryResult.getSummaryPrompt();

        // 用摘要替换原始 history
        messages.clear();
        ChatItem systemItem = new ChatItem();
        systemItem.setObj("System");
        systemItem.setValue(systemPrompt + "\n[history]\n" + summaryPrompt);
        messages.add(systemItem);
        messages.add(humanChatItem);

        // ★ 第三级: 摘要后再次校验 ★
        filterResult = getFilterMessages(messages, modelVO, true);
        if (filterResult.isAiSummary()) {
            // 摘要后仍然超长 → 必须抛异常
            throw new BusinessException(CHAT_OVER_TOKEN_LIMIT_WITH_SUMMARY);
        }
        filterMessages = filterResult.getResult();
        summaryResult.setFilterMessages(filterMessages);
    }
    return summaryResult;
}
```

### **4\.4 getAISummary\(\) 摘要的 LLM 调用**

```Java
public SummaryResult getAISummary(List<ChatItem> chatHistories, ModelListVO modelVO, ...) {
    if (!isSummary) {
        throw new BusinessException(CHAT_OVER_TOKEN_LIMIT_WITHOUT_SUMMARY);
    }

    // 1) 拼接历史: "Human: xxx\nAI: xxx\nHuman: xxx..."
    StringBuilder quoteText = new StringBuilder();
    for (ChatItem item : chatHistories) {
        quoteText.append(item.getObj()).append(": ").append(item.getValue());
        if (i != chatHistories.size() - 1) quoteText.append("\n");
    }

    // 2) 模板替换
    Map<String, Object> obj = Map.of("lastSummary", lastSummary, "history", quoteText.toString());
    String content = MessageFormatUtils.replaceVariable(AI_SUMMARY_PROMPT, obj);
    // AI_SUMMARY_PROMPT = "请对以下对话历史进行摘要，保留关键事实和决策: {history}"

    // 3) ★ 调用 LLM 生成摘要 (同步调用) ★
    List<ModelOutputDTO.ChatChoiceDTO> data = aiApiService.create(
        modelVO, temperature, maxToken, false, concatMessages, reqId, userInfo);
    summary = data.get(0).getMessage().getContent();

    // 4) 提取摘要 JSON 内容
    if (summary.contains("{") && summary.contains("}")) {
        summary = summary.substring(summary.indexOf("{"), summary.lastIndexOf("}") + 1);
    }
    result.setSummaryPrompt(summary);
    return result;
}
```

---

## **AIApiService\.java Model 调用门面**

**文件**: `.../module/application/service/AIApiService.java`

```Java
@Service
public class AIApiService {
    @Autowired
    private ModelService modelService;
    @Autowired
    private ModelManageService modelManageService;

    // v1: 同步调用
    List<ChatChoiceDTO> create(
        ModelListVO model, Float temperature, Integer maxToken,
        Boolean stream, List<ChatCompletionContent> messages, ...
    ) {
        return modelService.modelProcess(modelParamDTO);
    }

    // v2: 流式调用 (stream=true 时通过 SSE 实时推送)
    List<ChatChoiceDTO> createNew(
        ModelListVO model, ChatCompletion chatCompletion,
        Boolean stream, List<ChatCompletionContent> messages, ...
    ) {
        if (stream) {
            String answer = modelService.modelProcessStream(modelParamDTO);
            // 收集完整答案
            ...
        } else {
            return modelService.modelProcess(modelParamDTO);
        }
    }

    // v3: 流式 + 返回 usage (用于 token 计费)
    ModelOutputDTO createNew2(
        ModelListVO model, ChatCompletion chatCompletion,
        Boolean stream, List<ChatCompletionContent> messages, ..., String moduleId
    ) {
        if (stream) {
            return modelManageService.modelProcessStreamNew(modelParamDTO, moduleId);
            // modelProcessStreamNew: 内部通过 SseEmitter 逐 token 推送
            // 前端通过 /api/v1/chat/stream/{reqId} 订阅
        } else {
            return modelManageService.modelProcess(modelParamDTO);
        }
    }
}
```

**三条调用链路对比**:

---

## **SSEUtils\.java 流式推送**

```Java
public class SSEUtils {
    // ★ reqId → SseEmitter 全局 Map ★
    private static final Map<String, SseEmitter> subscribeMap = new ConcurrentHashMap<>();

    // 超时 10 分钟
    private static final Long DEFAULT_TIME_OUT = 10 * 60 * 1000L;

    // 前端 GET /api/v1/chat/stream/{reqId} 创建订阅
    public static SseEmitter addSub(String reqId) {
        SseEmitter emitter = new SseEmitterUTF8(DEFAULT_TIME_OUT);
        emitter.onTimeout(() -> closeSub(reqId));   // 超时清理
        emitter.onCompletion(() -> closeSub(reqId)); // 完成清理
        subscribeMap.put(reqId, emitter);
        return emitter;
    }

    // 后端调用 pubMsg() 推送事件
    public static void pubMsg(String reqId, String event, String msg) {
        SseEmitter emitter = subscribeMap.get(reqId);
        if (emitter != null) {
            emitter.send(event().name(event).data(msg));
        }
    }
}
```

**面试要点**: SSE 是单向推送 \(Server → Client\)，通过 `reqId` 作为频道 ID。前端先 `GET /stream/{reqId}` 建立连接，后端在执行过程中调用 `pubMsg()` 实时推送 token 和节点状态。不使用 WebSocket 是因为只需要单向推送。

---

## **对话历史摘要的完整机制**

```Plain Text
┌──────────────────────────────────────────────────────────────┐
│  多轮对话的摘要机制                                             │
│                                                              │
│  Round 1:                                                     │
│    User: "查产品A"     → 不需要摘要                            │
│    LLM: "产品A库存150"                                         │
│    ChatItem 保存回答 + moduleSummaryData (空)                  │
│                                                              │
│  Round 2-4: (不超 maxToken)                                   │
│    继续累积 ChatItem，不需要摘要                                │
│                                                              │
│  Round 5: ★ Token 超限 ★                                      │
│    1. ChatContextFilter 检测 maxTokens 超限                   │
│    2. 触发 getAISummary():                                     │
│       输入: 全部历史 "Human:...\nAI:...\n..."                 │
│       LLM输出: {"summary": "用户查询了产品A库存(150件)、B参数..."}│
│    3. System Prompt 变为:                                      │
│       systemPrompt + "\n[history]\n" + summary                 │
│    4. 丢弃旧的 chatHistories                                   │
│    5. moduleSummaryData 写入 {moduleType, moduleId, summary}   │
│                                                              │
│  Round 6-N:                                                   │
│    getLastSummary() 从 ChatItem 读取上次摘要                    │
│    subList 裁切摘要节点之前的旧记录                             │
│    只保留摘要 + 之后的新对话                                    │
│                                                              │
│  摘要失败保护:                                                  │
│    摘要后再次 hashCheck → 仍超限 →                              │
│    抛出 CHAT_OVER_TOKEN_LIMIT_WITH_SUMMARY                     │
└──────────────────────────────────────────────────────────────┘
```

---

## **historyPreview 对话历史预览**

```Java
static List<ChatItem> getHistoryPreview(List<ChatItem> completeMessages) {
    // ★ 前端展示用: 完整历史截断为预览 ★
    for (int i = 0; i < completeMessages.size(); i++) {
        ChatItem item = completeMessages.get(i);
        if (ChatRoleEnum.SYSTEM.getValue().equals(item.getObj())) {
            historyPreview.add(item);  // System 消息完整保留
        } else if (i >= completeMessages.size() - 2) {
            historyPreview.add(item);  // 最近 2 条完整保留
        } else {
            // 中间的消息: 截断至 15 字符 + "..."
            ChatItem preview = new ChatItem();
            preview.setValue(item.getValue().length() > 15 ?
                item.getValue().substring(0, 15) + "..." : item.getValue());
            historyPreview.add(preview);
        }
    }
}
```

---

## **面试核心 20 问**

---

## **模块深度追问**

### **知识库/搜索模块**

#### **Q1: RAG 检索引擎是基于 Milvus 还是 ES？**

**两者都用，但主力是 ES 8\.x**。

**选型考量**: ES 8\.x 原生支持 KNN 后，不需要额外部署 Milvus 就能实现向量\+全文混合检索。但保留了 Milvus 通道（通过 VectorSpi 抽象层），用于纯向量高吞吐场景。

#### **Q2: RRF 融合为什么比直接加权好？**

**问题**: BM25 分数（0\-100\+，无上限）和 Cosine 相似度（0\-1）量纲完全不同。直接加权：BM25 为 15\.3 的 chunk 碾压 Cosine 为 0\.92 的 chunk——全文检索天然会主导排序。

**RRF 解决方案**: 对排名融合，而非分数融合。`RRF(d) = weight × 1/(k + rank)`。排名是无量纲的——向量检索排第 1 和全文检索排第 1 等价。k=60 平滑因子防止排名靠后的文档被幂律压扁。

**具体示例**:

```Plain Text
向量检索排名: docA=1, docB=2, docC=3  (weight=6)
全文检索排名: docB=1, docD=2, docA=3  (weight=4)

RRF 计算:
  docA: 6/(60+1) + 4/(60+3) = 0.0984 + 0.0635 = 0.1619
  docB: 6/(60+2) + 4/(60+1) = 0.0968 + 0.0656 = 0.1624  ← 最高
  docC: 6/(60+3) + 0         = 0.0952
  docD: 0        + 4/(60+2)  = 0.0645

结论: docB 在两个通道都排前列 → RRF 分最高。docA 只在向量排第一 → 不如 docB。
```

#### **Q3: 检索到空结果怎么处理？**

**三级兜底**:

### **Agent 管理模块**

#### **Q4: Agent 怎么决定调用哪个工具？**

**两条路径**:

**路径一（WorkFlow 模式 ****`agentFramework="flow"`****）**: 用户在画布上手动编排节点顺序——工具调用不是 LLM 决定的，是画布决定了"先 datasetSearchNode → 再 chatNode"。

**路径二（AutoAgent 模式 ****`agentFramework="PA"`****）**: LLM 在 PlanReAct 循环中自主决定。流程：

1. ADKExecutor 启动时遍历 `agentMeta.tools`（pluginInfoList/mcpInfoList/workflowInfoList/agentInfoList）→ 为每个工具创建 `CommonTool` 实例 → 注册到 ADK LlmAgent

2. LLM 收到用户问题 \+ System Prompt（含工具列表 FunctionDeclaration）→ 在 `/*ACTION*/` 阶段输出 `function_call(tool_name, args)`

3. `CommonTool.run_async()` → HTTP 调用 Java `/api/v1/tools/run` → Java 端按 `type` 字段分发执行 → 返回结果

4. LLM 收到 `function_response` → 在 `/*REASONING*/` 阶段分析结果 → 决定继续调工具还是输出 `/*FINAL_ANSWER*/`

#### **Q5: Agent 快照版本管理怎么做的？**

- 每次发布生成不可变快照记录（`agent_snapshot` 表），字段级快照覆盖 6 个 JSON 字段：tools（工具配置）、knowledge（知识库配置）、modelInfo（模型参数）、longTermMemory（长期记忆配置）、variableList（全局变量列表）、recommends（推荐问题列表）

- 默认版本：`isSetDefault=true` 时先将旧的默认快照取消（`updateDefaultSnapshot()`），再将新快照设为默认

- 发布前校验：DFS 循环检测（`hasCycleHelper` 递归检查 Agent 嵌套引用，发现环抛异常阻止发布）\+ 知识库配置完整性（backupStrategy/showSource/retrieveMaxLength 不可为空）

- 草稿模式：`draftMode=true` 全链路透传，工具调用层根据此标记跳过真实支付/消息发送/数据修改

### **Chat 对话模块**

#### **Q6: Token 超限的完整处理链路是怎样的？**

```Plain Text
1. 快速路径: rawTextLen < maxTokens × 0.5 → 直接跳过计算（ChatContextFilter）
   为什么是 0.5？字符数 ≈ α × token数，中文 α≈1.5、英文 α≈4。字符数远小于 token 时必定不超限

2. 精确截断（未开摘要）: JTokkit 逐条计算 token 数 → 从后往前累加至 maxTokens → 截断
   Human+AI 配对移除——截断掉 Human 消息的同时移除配对的 AI 回复
   截断后 chats 为空 → 抛 CHAT_OVER_TOKEN_LIMIT 异常

3. LLM 摘要压缩（开启摘要）: 不截断，调 LLM 将完整历史压缩
   输入: "Human: xxx\nAI: xxx\nHuman: xxx..." 全量拼接
   输出: {"summary": "用户咨询了产品A电池参数(已告知5000mAh)，接着询问价格"}
   摘要替换原始 history: systemPrompt + "\n[history]\n" + summary
   摘要存 ChatItem.moduleSummaryData，下次对话读取时直接复用（subList 裁切旧记录）

4. 摘要后仍超限: 抛 CHAT_OVER_TOKEN_LIMIT_WITH_SUMMARY → 拒绝继续
```

#### **Q7: 为什么 SS E 流式用 reqId 频道广播而非 WebSocket？**

**场景**: 实时 AI 对话只需要 Server→Client 单向推送，不需要双向通信。

**实现**: `SSEUtils` 维护全局 `Map<reqId, SseEmitter>`。前端 `GET /api/v1/chat/stream/{reqId}` 创建订阅（超时 10 分钟），后端 `pubMsg(reqId, event, msg)` 推送。推送两种事件：

- `ANSWER`: LLM token 级回复文本

- `MODULESTATUS`: 节点执行状态（status=1 运行中, 2 完成, 3 异常，含执行详情）

心跳每 10 秒发空数据防止 Nginx 代理超时断连。工作流执行结束 → `emitter.complete()` → 从 Map 移除。

#### **Q8: LLM 调用的模型路由怎么做？**

**通过 ****`ModelService.queryModelList()`**** 动态获取可用模型列表**:

1. 调用模型服务的 API 查询当前可用的 LLM 模型实例

2. 遍历返回列表，按 `serviceUniCode` 匹配用户配置的模型编码（如 `maip_gpt-4`）

3. 匹配成功 → 获取 `modelUrl`（API 端点）\+ `hyperParameter`（temperature/maxTokens/timeout）

4. 匹配失败 → 抛 `LLM_MODEL_CALL_OFFLINE_EXCEPTION`

**三条调用链路**: \| 方法 \| 流式 \| 返回值 \| 使用场景 \| \|\-\-\-\-\-\-\|\-\-\-\-\-\-\|\-\-\-\-\-\-\-\-\|\-\-\-\-\-\-\-\-\-\| \| `create()` \| 否 \| `List<ChatChoiceDTO>` \| 摘要生成（AISummaryService） \| \| `createNew()` \| 可选 \| `List<ChatChoiceDTO>` \| 旧版 chatNode \| \| `createNew2()` \| 可选 \| `ModelOutputDTO`（含 usage） \| 新版 chatNode（支持 token 计费） \|

**问题**: 当前无模型熔断降级——LLM 调用失败 → 重试 3 次 → 抛异常。改进方向：使用项目已引入的 Sentinel 做 `@SentinelResource` 熔断，熔断后自动降级到备用模型或预设兜底回复。

