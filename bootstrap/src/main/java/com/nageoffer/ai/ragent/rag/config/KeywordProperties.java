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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 关键词检索配置
 * <p>
 * type=none（默认）时不注册任何关键词读写实现，与「从未引入关键词检索」运行期等价
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.keyword")
public class KeywordProperties {

    /**
     * 关键词检索后端类型
     * 可选 none（关闭）/ es；none 时不注册任何关键词读写实现
     */
    private String type = "none";

    /**
     * Elasticsearch 配置
     */
    private Es es = new Es();

    /**
     * 将知识库 collection 映射为索引名称
     */
    public String indexName(String collectionName) {
        return es.getIndexPrefix() + collectionName;
    }

    /**
     * 全局检索使用的索引通配（覆盖所有知识库索引）
     */
    public String globalIndexPattern() {
        return es.getIndexPrefix() + "*";
    }

    @Data
    public static class Es {

        /**
         * ES 连接地址
         */
        private String uris = "http://127.0.0.1:9200";

        /**
         * 索引名前缀，最终索引名为 indexPrefix + collectionName
         */
        private String indexPrefix = "kb_";

        /**
         * 写入分词器
         */
        private String analyzer = "ik_max_word";

        /**
         * 查询分词器
         */
        private String searchAnalyzer = "ik_smart";
    }
}
