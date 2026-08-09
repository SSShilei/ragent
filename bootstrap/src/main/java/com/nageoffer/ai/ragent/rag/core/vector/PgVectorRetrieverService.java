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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorRetrieverService implements VectorRetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final SearchChannelProperties searchProperties;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        List<Float> embedding = embeddingService.embed(request.getQuery());
        float[] vector = normalize(toArray(embedding));
        return retrieveByVector(vector, request);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        // 单库检索：意图定向，不设相似度阈值（定向已天然过滤）
        return queryByCollections(vector, List.of(request.getCollectionName()), request.getTopK(), 0.0);
    }

    @Override
    public boolean supportsGlobalRetrieval() {
        return true;
    }

    @Override
    public List<RetrievedChunk> retrieveGlobal(String query, List<String> collectionNames, int candidateBudget) {
        if (collectionNames == null || collectionNames.isEmpty()) {
            return List.of();
        }
        List<Float> embedding = embeddingService.embed(query);
        float[] vector = normalize(toArray(embedding));
        // 全局检索：加相似度阈值过滤低相关 chunk，减少 Rerank 成本与 Prompt 噪声
        double minSimilarity = searchProperties.getChannels().getVector().getGlobal().getMinSimilarity();
        List<RetrievedChunk> results = queryByCollections(vector, collectionNames, candidateBudget, minSimilarity);
        log.info("PG 向量全局检索完成 candidateBudget={} minSimilarity={} 结果数={}", candidateBudget, minSimilarity, results.size());
        return results;
    }

    /**
     * 在指定 collection 范围内执行一次向量相似度检索
     * <p>
     * 单库与全局共用此方法：单库传单元素列表，全局传多元素列表。
     * minSimilarity > 0 时过滤低相似度 chunk，全局检索默认 0.3，意图定向传 0（不设阈）。
     */
    private List<RetrievedChunk> queryByCollections(float[] vector, List<String> collectionNames, int limit, double minSimilarity) {
        applyPgVectorHints();

        String vectorLiteral = toVectorLiteral(vector);
        String inPlaceholders = collectionNames.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "));

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, content, 1 - (embedding <=> ?::vector) AS score FROM t_knowledge_vector WHERE collection_name IN (")
           .append(inPlaceholders).append(")");

        List<Object> argsList = new ArrayList<>();
        argsList.add(vectorLiteral);
        for (String collectionName : collectionNames) {
            argsList.add(collectionName);
        }

        // 相似度阈值：在 PG 层提前过滤低相关 chunk，减少 Rerank 候选量与 Prompt 噪声
        if (minSimilarity > 0.0) {
            sql.append(" AND 1 - (embedding <=> ?::vector) >= ?");
            argsList.add(vectorLiteral);
            argsList.add(minSimilarity);
        }

        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        argsList.add(vectorLiteral);
        argsList.add(limit);

        Object[] args = argsList.toArray();

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> RetrievedChunk.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .score(rs.getFloat("score"))
                        .build(),
                args
        );
    }

    /**
     * 设置 pgvector HNSW 检索提示（pgvector >= 0.8），老版本不支持则静默跳过。
     * 这些 SET 语句是性能优化 hint，缺失不影响检索正确性。
     */
    private void applyPgVectorHints() {
        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.execute("SET hnsw.ef_search = 200");
        } catch (Exception e) {
            log.debug("pgvector 不支持 hnsw.ef_search，跳过", e);
        }
        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.execute("SET hnsw.iterative_scan = relaxed_order");
        } catch (Exception e) {
            log.debug("pgvector 不支持 hnsw.iterative_scan，跳过", e);
        }
    }

    private float[] normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
