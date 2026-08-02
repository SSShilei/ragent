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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统功能配置
 *
 * <p>
 * 用于管理 RAG 系统的各项功能开关，例如查询重写等
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   query-rewrite:
 *     enabled: true
 *   multi-query:
 *     enabled: false
 *     max-variants: 3
 *     min-query-chars: 10
 * </pre>
 */
@Data
@Configuration
public class RAGConfigProperties {

    /**
     * 查询重写功能开关
     * <p>
     * 控制是否启用查询重写功能，查询重写可以将用户的查询语句优化为更适合检索的形式
     * 默认值：{@code true}
     */
    @Value("${rag.query-rewrite.enabled:true}")
    private Boolean queryRewriteEnabled;

    /**
     * Rerank 重排序功能开关
     * <p>
     * 控制是否启用 Rerank 后置处理器对召回结果进行重排序
     * 默认值：{@code true}
     */
    @Value("${rag.rerank.enabled:true}")
    private Boolean rerankEnabled;

    /**
     * 上下文元数据富化开关
     * <p>
     * 控制是否在检索末端回表补齐 chunk 的文档归属信息（文档ID/序号/标题），
     * 并在组装上下文时按文档聚合、组内按序号排列、带上文档标题作为内部锚点
     * 关闭后组装退回按检索相关性平铺、不带来源
     * 默认值：{@code true}
     */
    @Value("${rag.context.enrich.enabled:true}")
    private Boolean contextEnrichEnabled;

    /**
     * Multi-Query 扩展开关
     * <p>
     * 控制是否生成同一问题的语义变体进行多路检索,
     * 适用于短 query、模糊 query 等原始检索召回不足的场景
     * 默认值：{@code false}
     */
    @Value("${rag.multi-query.enabled:false}")
    private Boolean multiQueryEnabled;

    /**
     * Multi-Query 最大变体数量
     * <p>
     * 每次最多生成几个语义变体（不含原始 query），默认 3
     */
    @Value("${rag.multi-query.max-variants:3}")
    private Integer multiQueryMaxVariants;

    /**
     * Multi-Query 触发的最小 query 字符数
     * <p>
     * query 长度低于此阈值时触发 Multi-Query 扩展，超过则跳过
     * 默认 10 字符
     */
    @Value("${rag.multi-query.min-query-chars:10}")
    private Integer multiQueryMinQueryChars;
}
