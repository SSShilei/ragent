# RAG 文档增量更新技术方案

> 目标：将当前"先删后插"的文档全量重做策略升级为基于版本号 + outlinePath 的增量更新方案，解决 Webhook 乱序、分块策略变更雪崩、僵尸数据污染三大问题。

---

## 一、现状与问题

### 1.1 当前流程

```
文档更新 → runChunkTask
  → extract(解析全文) → chunk(全量分块) → embed(全量向量化)
  → persistChunksAndVectorsAtomically(事务内:删旧 chunk → 删旧向量 → 插新 chunk → 插新向量)
```

### 1.2 存在的问题

| 问题 | 影响 |
|---|---|
| 全量 embed 浪费 | 100 页文档只改 2 页，也要对所有 chunk 重新向量化 |
| 分块策略变更雪崩 | 改 `chunk_size` 后所有 contentHash 全变，增量退化为全量 |
| 无版本号保护 | Webhook 乱序推送时旧版本可能覆盖新版本 |
| 僵尸数据风险 | 删除 chunk 和删除向量之间无原子保证，异常时可能残留 |
| 无法局部更新 | 飞书 Webhook 告知"只改了第 3 章"，仍然全量重跑 |

---

## 二、核心设计

### 2.1 三重身份标识

| 标识 | 用途 | 稳定性 |
|---|---|---|
| `doc_id` | 文档归属 | 稳定 |
| `outline_path` | 章节定位（如 `"第二章 > 架构设计 > 2.3"`） | 不随分块策略变化 |
| `content_hash` | 内容指纹（SHA-256） | 内容不变则不变 |

**以 `(doc_id, outline_path)` 作为 chunk 的稳定主键，contentHash 用于判断内容是否变更。**

### 2.2 版本号防乱序

```
Webhook 乱序场景:
  t1: 推送 v3 → 到达
  t2: 推送 v2 → 延迟到达

处理:
  v3 到达 → doc.version = 3, 执行增量分块
  v2 到达 → 判断 2 < 3 → 丢弃, 告警日志
```

### 2.3 增量 Diff 三态逻辑

```
新版本 chunk 列表 vs 旧版本 chunk 列表:

  outlinePath 相同, contentHash 相同 → SKIP（不动）
  outlinePath 相同, contentHash 不同 → UPDATE（重 embed, 复用旧 chunk ID）
  新版本有, 旧版本无 → ADD（新建 chunk + embed）
  旧版本有, 新版本无 → DELETE（标记 gc_status=1, 异步清理向量）
```

---

## 三、数据库变更

### 3.1 t_knowledge_document 新增字段

```sql
ALTER TABLE t_knowledge_document
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN t_knowledge_document.version IS '文档版本号，用于 Webhook 乱序保护和增量更新锚定';
```

### 3.2 t_knowledge_chunk 新增字段

```sql
ALTER TABLE t_knowledge_chunk
    ADD COLUMN outline_path  VARCHAR(512),
    ADD COLUMN gc_status     SMALLINT NOT NULL DEFAULT 0;
-- gc_status: 0=正常, 1=待删除(标记), 2=已清理(向量已确认删除)

CREATE INDEX idx_doc_outline ON t_knowledge_chunk (doc_id, outline_path);
CREATE INDEX idx_gc_status   ON t_knowledge_chunk (gc_status);

COMMENT ON COLUMN t_knowledge_chunk.outline_path IS '章节路径，用于增量 diff 的稳定身份标识';
COMMENT ON COLUMN t_knowledge_chunk.gc_status IS 'GC状态：0=正常, 1=待删除, 2=已清理';
```

---

## 四、实体变更

### 4.1 KnowledgeDocumentDO

```java
/**
 * 文档版本号，Webhook 推送时携带，用于乱序保护
 */
private Integer version;
```

### 4.2 KnowledgeChunkDO

```java
/**
 * 章节路径，格式如 "第二章 > 架构设计 > 2.3 部署方案"
 * 解析阶段从文档结构中提取，分块时透传
 */
private String outlinePath;

/**
 * GC状态：0-正常, 1-待删除, 2-已清理
 * 用于僵尸数据巡检和清理
 */
private Integer gcStatus;
```

### 4.3 VectorChunk

```java
public class VectorChunk {
    private String chunkId;
    private String content;
    private float[] embedding;
    private String outlinePath;   // 新增
    private int chunkIndex;
}
```

---

## 五、核心代码改造

### 5.1 增量 Diff 引擎

```java
// KnowledgeDocumentServiceImpl.java 新增方法

/**
 * 增量 diff 结果
 */
private record IncrementalDiffResult(
    List<VectorChunk> toAdd,      // 新 outlinePath, 需要 embed
    List<VectorChunk> toUpdate,   // outlinePath 存在但内容变了, 需要重 embed
    List<VectorChunk> toKeep,     // outlinePath + contentHash 都相同, 不动
    Set<String> toDelete          // 旧 outlinePath 在新版本中不存在
) {}

/**
 * 按 outlinePath 做增量 diff
 */
private IncrementalDiffResult diffChunks(String docId, List<VectorChunk> newChunks) {
    // 获取旧 chunk: outlinePath → chunkDO
    List<KnowledgeChunkDO> oldChunks = chunkMapper.selectList(
        Wrappers.lambdaQuery(KnowledgeChunkDO.class)
            .eq(KnowledgeChunkDO::getDocId, docId)
            .eq(KnowledgeChunkDO::getDeleted, 0)
    );
    Map<String, KnowledgeChunkDO> oldByPath = oldChunks.stream()
        .collect(Collectors.toMap(
            c -> c.getOutlinePath() != null ? c.getOutlinePath() : "",
            c -> c,
            (a, b) -> a  // 同一 outlinePath 出现多条时保留第一条（异常情况，后续告警）
        ));

    List<VectorChunk> toAdd = new ArrayList<>();
    List<VectorChunk> toUpdate = new ArrayList<>();
    List<VectorChunk> toKeep = new ArrayList<>();
    Set<String> newPaths = new HashSet<>();

    for (VectorChunk vc : newChunks) {
        String path = vc.getOutlinePath() != null ? vc.getOutlinePath() : "";
        newPaths.add(path);
        KnowledgeChunkDO old = oldByPath.get(path);

        if (old == null) {
            toAdd.add(vc);
        } else {
            String newHash = SecureUtil.sha256(vc.getContent());
            if (!newHash.equals(old.getContentHash())) {
                vc.setChunkId(old.getId());  // 复用旧 ID，避免向量索引重建
                toUpdate.add(vc);
            } else {
                vc.setChunkId(old.getId());
                toKeep.add(vc);
            }
        }
    }

    // 旧版本有但新版本没有的 outlinePath → 标记删除
    Set<String> toDelete = new HashSet<>(oldByPath.keySet());
    toDelete.removeAll(newPaths);

    return new IncrementalDiffResult(toAdd, toUpdate, toKeep, toDelete);
}
```

### 5.2 增量持久化（替代 persistChunksAndVectorsAtomically）

```java
/**
 * 增量持久化：三步原子操作
 *   ① 标记待删除 chunk + 禁用以防检索可见
 *   ② 对 ADD/UPDATE 的 chunk 做 embed
 *   ③ 事务内：写入新 chunk + 更新旧 chunk + 删除向量
 */
private int incrementalPersist(String collectionName, String docId,
                               List<VectorChunk> newChunks, String embeddingModel) {
    IncrementalDiffResult diff = diffChunks(docId, newChunks);

    // ① 标记待删除：软标记 + 禁用，不物理删除，GC 定时清理
    if (!diff.toDelete().isEmpty()) {
        chunkMapper.update(null, Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
            .set(KnowledgeChunkDO::getGcStatus, 1)
            .set(KnowledgeChunkDO::getEnabled, 0)
            .eq(KnowledgeChunkDO::getDocId, docId)
            .in(KnowledgeChunkDO::getOutlinePath, diff.toDelete()));
    }

    // ② 只对真正变化的 chunk 做 embed（省钱的关键）
    List<VectorChunk> needEmbed = new ArrayList<>();
    needEmbed.addAll(diff.toAdd());
    needEmbed.addAll(diff.toUpdate());
    if (!needEmbed.isEmpty()) {
        chunkEmbeddingService.embed(needEmbed, embeddingModel);
    }

    // ③ 事务内：写新 + 更新旧 + 清理向量
    final int newChunkCount = diff.toAdd().size() + diff.toUpdate().size();
    transactionOperations.executeWithoutResult(status -> {
        // 写入新增 chunk
        if (!diff.toAdd().isEmpty()) {
            List<KnowledgeChunkCreateRequest> addReqs = diff.toAdd().stream()
                .map(vc -> buildCreateRequest(vc, docId))
                .toList();
            knowledgeChunkService.batchCreate(docId, addReqs);
            vectorStoreService.indexDocumentChunks(collectionName, docId, diff.toAdd());
        }

        // 更新已有 chunk（内容变了，重 embed 后更新向量和 content）
        if (!diff.toUpdate().isEmpty()) {
            for (VectorChunk vc : diff.toUpdate()) {
                chunkMapper.update(null, Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                    .set(KnowledgeChunkDO::getContent, vc.getContent())
                    .set(KnowledgeChunkDO::getContentHash, SecureUtil.sha256(vc.getContent()))
                    .set(KnowledgeChunkDO::getCharCount, vc.getContent().length())
                    .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
                    .eq(KnowledgeChunkDO::getId, vc.getChunkId()));
            }
            vectorStoreService.indexDocumentChunks(collectionName, docId, diff.toUpdate());
        }

        // 清理待删除 chunk 的向量
        if (!diff.toDelete().isEmpty()) {
            List<String> deleteIds = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                    .eq(KnowledgeChunkDO::getDocId, docId)
                    .eq(KnowledgeChunkDO::getGcStatus, 1)
            ).stream().map(KnowledgeChunkDO::getId).toList();

            if (!deleteIds.isEmpty()) {
                vectorStoreService.deleteChunkVectors(collectionName, deleteIds);
                // 向量确认删除后，更新 gc_status 为 2
                chunkMapper.update(null, Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                    .set(KnowledgeChunkDO::getGcStatus, 2)
                    .in(KnowledgeChunkDO::getId, deleteIds));
            }
        }

        // 更新文档级统计
        int totalChunks = chunkMapper.selectCount(
            Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getDocId, docId)
                .eq(KnowledgeChunkDO::getGcStatus, 0));
        documentMapper.update(null, Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
            .set(KnowledgeDocumentDO::getChunkCount, totalChunks)
            .set(KnowledgeDocumentDO::getStatus, DocumentStatus.SUCCESS.getCode())
            .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername())
            .eq(KnowledgeDocumentDO::getId, docId));
    });

    log.info("增量持久化完成 docId={} add={} update={} keep={} delete={}",
        docId, diff.toAdd().size(), diff.toUpdate().size(),
        diff.toKeep().size(), diff.toDelete().size());

    return newChunkCount;
}
```

### 5.3 版本号保护（Webhook 入口）

```java
/**
 * Webhook 回调或定时刷新入口，带版本号保护
 */
public void onDocumentChanged(String docId, int incomingVersion,
                              byte[] fileBytes, Set<String> changedChapters) {
    KnowledgeDocumentDO current = documentMapper.selectById(docId);
    if (current == null) {
        log.warn("文档不存在，忽略更新 docId={}", docId);
        return;
    }

    // 版本号乱序保护
    if (incomingVersion <= current.getVersion()) {
        log.warn("收到过期版本推送，忽略 docId={} incomingVersion={} currentVersion={}",
            docId, incomingVersion, current.getVersion());
        return;
    }

    // 更新版本号，同时更新文件内容
    documentMapper.update(null, Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
        .set(KnowledgeDocumentDO::getVersion, incomingVersion)
        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.PENDING.getCode())
        .eq(KnowledgeDocumentDO::getId, docId));

    // 如果文件内容变了，更新 S3 文件
    if (fileBytes != null && fileBytes.length > 0) {
        fileStorageService.overwrite(current.getFileUrl(), fileBytes);
    }

    // 触发增量分块
    if (changedChapters != null && !changedChapters.isEmpty()) {
        partialChunk(docId, changedChapters);
    } else {
        startChunk(docId);
    }
}
```

### 5.4 局部章节更新

```java
/**
 * 只重新处理指定章节（飞书 webhook 告知变更范围时使用）
 */
private void partialChunk(String docId, Set<String> changedChapters) {
    KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);

    // ① 标记变更章节的旧 chunk 为待删除
    for (String chapterPath : changedChapters) {
        chunkMapper.update(null, Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
            .set(KnowledgeChunkDO::getGcStatus, 1)
            .set(KnowledgeChunkDO::getEnabled, 0)
            .eq(KnowledgeChunkDO::getDocId, docId)
            .likeRight(KnowledgeChunkDO::getOutlinePath, chapterPath));
    }

    // ② 只解析变更章节
    byte[] fileBytes = readFileBytes(documentDO.getFileUrl());
    List<VectorChunk> newChunks = parseAndChunkChapters(
        fileBytes, documentDO, changedChapters);

    // ③ 走增量 diff + 持久化
    String collectionName = resolveCollectionName(documentDO.getKbId());
    incrementalPersist(collectionName, docId, newChunks,
        knowledgeBaseMapper.selectById(documentDO.getKbId()).getEmbeddingModel());
}
```

---

## 六、僵尸数据 GC 定时任务

### 6.1 巡检逻辑

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class ChunkGcScheduler {

    private final KnowledgeChunkMapper chunkMapper;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /**
     * 每天凌晨 3 点执行僵尸数据清理
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void gcZombieData() {
        log.info("Chunk GC 巡检开始");

        // ① gc_status=1 的 chunk：向量可能未删除成功，补删
        List<KnowledgeChunkDO> pendingDelete = chunkMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getGcStatus, 1));
        if (!pendingDelete.isEmpty()) {
            gcPendingDelete(pendingDelete);
        }

        // ② 检查 pgvector 中有但 MySQL 中已删的向量（孤儿向量）
        gcOrphanVectors();

        log.info("Chunk GC 巡检完成");
    }

    private void gcPendingDelete(List<KnowledgeChunkDO> chunks) {
        Map<String, List<KnowledgeChunkDO>> byKb = chunks.stream()
            .collect(Collectors.groupingBy(KnowledgeChunkDO::getKbId));

        for (Map.Entry<String, List<KnowledgeChunkDO>> entry : byKb.entrySet()) {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(entry.getKey());
            String collectionName = kb.getCollectionName();
            List<String> chunkIds = entry.getValue().stream()
                .map(KnowledgeChunkDO::getId).toList();

            try {
                vectorStoreService.deleteChunkVectors(collectionName, chunkIds);
                chunkMapper.update(null, Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                    .set(KnowledgeChunkDO::getGcStatus, 2)
                    .in(KnowledgeChunkDO::getId, chunkIds));
                log.info("GC补删向量成功 kbId={} count={}", entry.getKey(), chunkIds.size());
            } catch (Exception e) {
                log.error("GC补删向量失败 kbId={} chunkIds={}", entry.getKey(), chunkIds, e);
            }
        }
    }

    private void gcOrphanVectors() {
        // 遍历所有知识库，检查 pgvector 中是否有 MySQL 已不存在的 chunk 向量
        // 如发现孤儿向量，记录日志并清理
        // 具体实现依赖 VectorStoreService 的 listVectorIds 接口
        log.info("GC孤儿向量检查：当前依赖 pgvector 端能力，暂跳过");
    }
}
```

### 6.2 定时物理删除

```java
/**
 * 每天凌晨 4 点：gc_status=2 超过 7 天的 chunk 物理删除
 */
@Scheduled(cron = "0 0 4 * * ?")
public void physicalDelete() {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
    chunkMapper.delete(Wrappers.lambdaQuery(KnowledgeChunkDO.class)
        .eq(KnowledgeChunkDO::getGcStatus, 2)
        .lt(KnowledgeChunkDO::getUpdateTime, cutoff));
    log.info("Chunk 物理删除完成 cutoff={}", cutoff);
}
```

---

## 七、outlinePath 生成

### 7.1 解析阶段提取

```java
// StructuredChunkingService 或 Parser 中
// 利用 Markdown 标题层级 / OCR 识别结果构建 outlinePath

public class OutlinePathTracker {
    private final String[] levels = new String[6];  // 支持 h1~h6

    public void push(int level, String heading) {
        levels[level - 1] = heading;
        // 清空比当前层级更深的所有层级
        for (int i = level; i < levels.length; i++) {
            levels[i] = null;
        }
    }

    public String currentPath() {
        return Arrays.stream(levels)
            .filter(Objects::nonNull)
            .collect(Collectors.joining(" > "));
    }
}
```

### 7.2 分块时透传

```java
public List<VectorChunk> chunk(List<ParsedBlock> blocks, ...) {
    OutlinePathTracker tracker = new OutlinePathTracker();
    List<VectorChunk> chunks = new ArrayList<>();
    StringBuilder currentBuffer = new StringBuilder();
    String currentOutlinePath = "";

    for (ParsedBlock block : blocks) {
        if (block.getLevel() > 0) {
            tracker.push(block.getLevel(), block.getHeading());
            currentOutlinePath = tracker.currentPath();
            // 遇到新标题时，如果当前 buffer 有内容，先 flush 一个 chunk
            if (currentBuffer.length() > 0) {
                chunks.add(createChunk(currentBuffer.toString(), currentOutlinePath, chunks.size()));
                currentBuffer.setLength(0);
            }
        }
        // 把当前标题也加入 buffer（标题本身是该 chunk 的上下文）
        if (block.getLevel() > 0) {
            currentBuffer.append(block.getHeading()).append("\n");
        }
        currentBuffer.append(block.getText());
    }

    // flush 最后一个 chunk
    if (currentBuffer.length() > 0) {
        chunks.add(createChunk(currentBuffer.toString(), currentOutlinePath, chunks.size()));
    }

    return chunks;
}

private VectorChunk createChunk(String content, String outlinePath, int index) {
    return VectorChunk.builder()
        .chunkId(IdUtil.getSnowflakeNextIdStr())
        .content(content)
        .outlinePath(outlinePath)
        .chunkIndex(index)
        .build();
}
```

---

## 八、完整流程对比

### 8.1 改造前（全量删除重插）

```
文档更新 → 解析全文 → 全量分块 → 全量 embed
  → 事务内: 删旧 chunk(MySQL) → 删旧向量(pgvector) → 插新 chunk → 插新向量
  → 耗时: 100页文档 ≈ 30s embed + 5s 其他
```

### 8.2 改造后（增量 diff）

```
文档更新
  → 版本号检查(乱序保护)
  → 解析全文 → 分块(带 outlinePath)
  → 增量 diff(按 outlinePath 比对)
  → 只 embed 变化的 chunk(ADD + UPDATE)
  → 事务内:
      标记待删除 chunk(gc_status=1)
      INSERT 新增 chunk + 向量
      UPDATE 变更 chunk 内容 + 向量
      DELETE 待删除 chunk 向量 → 标记 gc_status=2
  → 耗时: 100页文档只改2页 ≈ 2s embed + 3s 其他(节省 80%+)
```

---

## 九、风险与边界

| 风险 | 缓解措施 |
|---|---|
| 分块策略变更导致 outlinePath 不变但内容边界变化 | 分块策略变更时强制全量重跑（`version` 置 0，触发全量路径） |
| 同一 outlinePath 下出现多个 chunk（同级内容过长，被拆成多块） | 在 outlinePath 后追加 segment 序号：`"第二章 > 架构设计#1"` |
| pgvector 不支持部分向量删除 | 确认 pgvector 版本支持 `DELETE WHERE` 条件删除；如不支持则全量重建 |
| 增量 diff 内存占用（大文档 chunk 数多） | 按章节分批 diff，避免一次性加载全部旧 chunk |

---

## 十、实施步骤

| 阶段 | 内容 | 影响范围 |
|---|---|---|
| Phase 1 | DDL 变更：加 `version`/`outline_path`/`gc_status` 字段 + 索引 | 无业务影响，新字段有默认值 |
| Phase 2 | `VectorChunk`/`KnowledgeChunkDO`/`KnowledgeDocumentDO` 实体变更 | 编译兼容 |
| Phase 3 | `outlinePath` 生成逻辑（Parser + ChunkingService） | 新 chunk 带路径，旧 chunk 路径为空 |
| Phase 4 | `IncrementalDiff` 引擎 + `incrementalPersist` 方法 | 核心逻辑，需要充分测试 |
| Phase 5 | `ChunkGcScheduler` 定时任务 | 独立组件，可单独上线 |
| Phase 6 | `onDocumentChanged` 版本号保护入口 | Webhook 入口改造 |
| Phase 7 | 局部章节更新 `partialChunk` | 飞书文档对接场景 |
| Phase 8 | 监控指标：增量命中率、GC 清理数、embed 节省量 | 可观测 |

## 十一、方案评审
### 1. 方案亮点（做得极其正确的地方）
1. outline_path 作为稳定主键（神来之笔）：这是解决“分块策略变更雪崩”的根本解。只要章节标题没变，chunk_id 就保持不变，向量索引无需重建。这比单纯依赖 content_hash 高出一个维度。

2. 复用旧 chunkId 进行 UPDATE（极其关键）：代码中 vc.setChunkId(old.getId()) 这一行价值连城。在向量数据库中，按主键更新（Upsert）比“删旧插新”性能高一个数量级，且不会产生索引碎片。

3. gc_status 三级状态机（0→1→2）：引入了“待删除”和“已清理”的中间态，完美解决了“MySQL删了但向量库删失败”导致的僵尸数据问题，且通过定时任务兜底，保证了最终一致性。

### 2. 潜在风险与改进建议
尽管方案很完善，但有 3个隐藏的坑 需要特别注意：

#### 1. 同一 `outline_path` 下多个 Chunk 的稳定性问题（方案中提到了但解法需加固）

   你提到追加 `#1、#2 `序号。风险在于：如果修改了该章节的起始位置（比如前面插入一段话），导致切分边界偏移，原来的 #1 内容可能变成 #2 的内容，这依然会引发不必要的更新和删除。
   建议：不采用自增序号，而是采用 `outline_path + 该Chunk起始字符的绝对偏移量（如 第二章 > 架构设计_1024）`。只要文本没有在该Chunk起始位置之前发生变动，这个ID就永远不变，稳定性最高。

#### 2. partialChunk 中的 LIKE 模糊匹配存在误删风险
   代码中 `likeRight(OutlinePath, chapterPath) `在章节路径包含特殊字符`（如 % 或 _）或存在包含关系（如 第1章 和 第10章）`时，可能误匹配。
   建议：改用 outline_path 精确前缀匹配 或直接将路径存储为` ltree 类型（PostgreSQL特有）`，查询时使用 `path <@ chapterPath `进行层级树匹配。

#### 3. 增量 Diff 的内存压力（大文档场景）
`diffChunks `方法一次性将旧 chunk 全部 load 到 Map 中。如果一份文档包含上万块（如完整法律条文），内存占用和 GC 压力会很大。
建议：在 `outline_path `上建立索引，按章节分批流式加载（比如每次只加载一个一级标题下的所有 chunk 进行 Diff），避免全量加载。

### 3. 两个小细节补充
分块策略变更的“强制全量”触发器：你提到了“分块策略变更时强制全量”，建议在` t_knowledge_document `中加一个 `chunk_strategy_version` 字段。当运维调整 chunk_size 后，递增该字段。增量逻辑入口处判断：若旧策略版本 != 新策略版本，则直接走全量重建，不走增量 Diff。

监控指标建议：除了“embed 节省量”，强烈建议增加` incremental_hit_rate（增量命中率） = (toKeep数量) / (总Chunk数)`。如果这个值长期低于 80%，说明文档结构变动频繁，需要排查是业务原因还是 outline_path 提取不稳定。

### 4. 结论
这份方案逻辑自洽、代码可落地、异常考虑周全（尤其是 GC 兜底），完全可以直接进入开发排期。唯一需要重点测试的边界场景是：同一文档内，修改了二级标题的名称（即 outline_path 变了），但内容没变——此时 Diff 会判定为“删除旧路径 + 新增新路径”，触发全量重算，这种情况你们是否可以接受？如果可以，方案完美。😊