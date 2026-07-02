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

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;

import java.util.List;

/**
 * 关键词索引服务 SPI
 * <p>
 * 与向量写入的 {@link com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService} 对称，
 * 负责把 chunk 的关键词文本写入全文检索引擎（本期为 Elasticsearch）
 * <p>
 * 关键约束：写入时文档主键（ES _id）必须等于向量库主键 chunkId，
 * 否则跨模态去重与融合无法对齐
 * <p>
 * 通过 rag.keyword.type 选择实现，none 时无实现被注册，写侧装饰器也随之不注册
 */
public interface KeywordIndexService {

    /**
     * 批量建立文档分块的关键词索引
     *
     * @param indexName 索引名称（知识库 collection 映射的索引）
     * @param docId     文档唯一标识
     * @param chunks    文档切片列表
     */
    void indexDocumentChunks(String indexName, String docId, List<VectorChunk> chunks);

    /**
     * 更新单个 chunk 的关键词索引
     *
     * @param indexName 索引名称
     * @param docId     文档唯一标识
     * @param chunk     待更新的文档切片
     */
    void updateChunk(String indexName, String docId, VectorChunk chunk);

    /**
     * 删除文档的所有关键词索引
     *
     * @param indexName 索引名称
     * @param docId     文档唯一标识
     */
    void deleteDocumentIndex(String indexName, String docId);

    /**
     * 删除指定的单个 chunk 关键词索引
     *
     * @param indexName 索引名称
     * @param chunkId   chunk 唯一标识
     */
    void deleteChunkById(String indexName, String chunkId);

    /**
     * 批量删除指定 chunk 的关键词索引
     *
     * @param indexName 索引名称
     * @param chunkIds  chunk 唯一标识列表
     */
    void deleteChunksByIds(String indexName, List<String> chunkIds);
}
