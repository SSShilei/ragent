/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.vector.keyword;

import cn.hutool.core.collection.CollUtil;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Elasticsearch 的关键词索引服务
 * <p>
 * 只写入关键词文本与检索所需元信息，不写向量；文档主键 _id 使用 chunkId，
 * 与向量库主键对齐，保证跨模态去重与融合一致
 * <p>
 * 仅当开启 ES 关键词检索（rag.keyword.type=es）时装配
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class EsKeywordIndexService implements KeywordIndexService {

    private static final int MAX_CONTENT_LENGTH = 65535;

    private final ElasticsearchClient esClient;
    private final KeywordProperties keywordProperties;

    @Override
    public void indexDocumentChunks(String indexName, String docId, List<VectorChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        ensureIndex(indexName);

        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (VectorChunk chunk : chunks) {
            String chunkId = chunk.getChunkId();
            Map<String, Object> doc = buildDocument(docId, chunk);
            bulk.operations(op -> op.index(idx -> idx.index(indexName).id(chunkId).document(doc)));
        }

        try {
            BulkResponse resp = esClient.bulk(bulk.build());
            if (resp.errors()) {
                log.warn("ES 关键词索引部分失败, index={}, docId={}", indexName, docId);
            } else {
                log.info("ES 关键词索引写入成功, index={}, docId={}, rows={}", indexName, docId, chunks.size());
            }
        } catch (Exception e) {
            throw new RuntimeException("ES 关键词索引写入失败, index=" + indexName + ", docId=" + docId, e);
        }
    }

    @Override
    public void updateChunk(String indexName, String docId, VectorChunk chunk) {
        indexDocumentChunks(indexName, docId, List.of(chunk));
    }

    @Override
    public void deleteDocumentIndex(String indexName, String docId) {
        if (!indexExists(indexName)) {
            log.info("ES 关键词索引不存在，跳过按文档删除, index={}, docId={}", indexName, docId);
            return;
        }
        try {
            esClient.deleteByQuery(d -> d
                    .index(indexName)
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .query(q -> q.term(t -> t.field("doc_id").value(docId))));
            log.info("ES 关键词索引按文档删除成功, index={}, docId={}", indexName, docId);
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 关键词索引不存在，跳过按文档删除, index={}, docId={}", indexName, docId);
                return;
            }
            throw new RuntimeException("ES 关键词索引删除失败, index=" + indexName + ", docId=" + docId, e);
        }
    }

    @Override
    public void deleteChunkById(String indexName, String chunkId) {
        if (!indexExists(indexName)) {
            log.info("ES 关键词索引不存在，跳过按 chunk 删除, index={}, chunkId={}", indexName, chunkId);
            return;
        }
        try {
            esClient.delete(d -> d.index(indexName).id(chunkId));
            log.info("ES 关键词索引按 chunk 删除成功, index={}, chunkId={}", indexName, chunkId);
        } catch (Exception e) {
            if (isNotFound(e)) {
                log.info("ES 关键词索引或 chunk 不存在，跳过按 chunk 删除, index={}, chunkId={}", indexName, chunkId);
                return;
            }
            throw new RuntimeException("ES 关键词索引删除失败, index=" + indexName + ", chunkId=" + chunkId, e);
        }
    }

    @Override
    public void deleteChunksByIds(String indexName, List<String> chunkIds) {
        if (CollUtil.isEmpty(chunkIds)) {
            return;
        }
        if (!indexExists(indexName)) {
            log.info("ES 关键词索引不存在，跳过批量删除, index={}, count={}", indexName, chunkIds.size());
            return;
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (String chunkId : chunkIds) {
            bulk.operations(op -> op.delete(del -> del.index(indexName).id(chunkId)));
        }
        try {
            esClient.bulk(bulk.build());
            log.info("ES 关键词索引批量删除成功, index={}, count={}", indexName, chunkIds.size());
        } catch (Exception e) {
            throw new RuntimeException("ES 关键词索引批量删除失败, index=" + indexName, e);
        }
    }

    private boolean indexExists(String indexName) {
        try {
            return esClient.indices().exists(e -> e.index(indexName)).value();
        } catch (Exception e) {
            throw new RuntimeException("ES 关键词索引状态检查失败, index=" + indexName, e);
        }
    }

    private boolean isNotFound(Exception e) {
        return e instanceof ElasticsearchException esException
                && esException.response() != null
                && esException.response().status() == 404;
    }

    /**
     * 判断是否为「索引已存在」异常
     * 并发首次写入时，多个线程同时 exists()=false 后争相 create()，
     * 落后者会收到 resource_already_exists_exception，此时视作创建成功
     */
    private boolean isAlreadyExists(Exception e) {
        return e instanceof ElasticsearchException esException
                && esException.response() != null
                && esException.response().error() != null
                && "resource_already_exists_exception".equals(esException.response().error().type());
    }

    /**
     * 确保索引存在，不存在则按 ik 分词创建
     * ik_max_word / ik_smart 需安装 IK 分词插件
     */
    private void ensureIndex(String indexName) {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                return;
            }
            String analyzer = keywordProperties.getEs().getAnalyzer();
            String searchAnalyzer = keywordProperties.getEs().getSearchAnalyzer();
            esClient.indices().create(c -> c
                    .index(indexName)
                    .mappings(m -> m
                            .properties("content", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                            .properties("outline", p -> p.text(t -> t.analyzer(analyzer).searchAnalyzer(searchAnalyzer)))
                            .properties("doc_id", p -> p.keyword(k -> k))
                            .properties("block_type", p -> p.keyword(k -> k))
                            .properties("chunk_index", p -> p.integer(i -> i))));
            log.info("ES 关键词索引已创建, index={}, analyzer={}/{}", indexName, analyzer, searchAnalyzer);
        } catch (Exception e) {
            if (isAlreadyExists(e)) {
                // 并发写入时已由其他线程建好同名索引，视作成功
                log.info("ES 关键词索引已由并发写入创建，跳过, index={}", indexName);
                return;
            }
            throw new RuntimeException("ES 关键词索引创建失败, index=" + indexName, e);
        }
    }

    private Map<String, Object> buildDocument(String docId, VectorChunk chunk) {
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("content", content);
        doc.put("doc_id", docId);
        if (chunk.getIndex() != null) {
            doc.put("chunk_index", chunk.getIndex());
        }
        if (StringUtils.hasText(chunk.getBlockType())) {
            doc.put("block_type", chunk.getBlockType());
        }
        if (CollUtil.isNotEmpty(chunk.getOutlinePath())) {
            doc.put("outline", String.join(" / ", chunk.getOutlinePath()));
        }
        return doc;
    }
}
