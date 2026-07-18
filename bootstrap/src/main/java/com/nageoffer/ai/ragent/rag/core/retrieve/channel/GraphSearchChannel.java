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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识图谱检索通道
 * <p>
 * 基于 LightRAG 的图谱召回：擅长多跳关系推理与实体为中心的聚合，与向量 / 关键词互补
 * 仅当开启图谱后端（rag.graph.type=lightrag）时才注册，否则整个通道不存在，引擎自动退化为无图谱检索
 * <p>
 * 优先级介于意图定向(1) 与关键词(5) 之间
 * <p>
 * 说明：LightRAG /query 无 per-request workspace，单实例即单图，本通道 Phase1 面向全局图召回；
 * 按 KB 隔离子图（借 file_path 归属过滤或多实例）留待后续阶段。mode=intent 时无 KB 意图则跳过，语义对齐关键词通道
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
public class GraphSearchChannel implements SearchChannel {

    private static final String MODE_INTENT = "intent";

    private final LightRagClient lightRagClient;
    private final GraphProperties graphProperties;
    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "GraphSearch";
    }

    @Override
    public int getPriority() {
        return 3;
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.GRAPH;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getGraph().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            // intent 模式且无 KB 意图则跳过，语义与关键词通道一致
            String mode = properties.getChannels().getGraph().getMode();
            if (MODE_INTENT.equalsIgnoreCase(mode) && !hasKbIntent(context)) {
                log.info("图谱检索为 intent 模式但无 KB 意图，跳过");
                return emptyResult(startTime);
            }

            int topKMultiplier = properties.getChannels().getGraph().getTopKMultiplier();
            int topK = context.getTopK() * Math.max(1, topKMultiplier);
            String queryMode = graphProperties.getLightrag().getQueryMode();

            List<RetrievedChunk> chunks = lightRagClient.retrieve(context.getMainQuestion(), queryMode, topK);

            long latency = System.currentTimeMillis() - startTime;
            log.info("图谱检索完成，检索到 {} 个证据，耗时 {}ms", chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.GRAPH)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            log.error("图谱检索失败", e);
            return emptyResult(startTime);
        }
    }

    /**
     * 是否存在 KB 意图
     */
    private boolean hasKbIntent(SearchContext context) {
        if (CollUtil.isEmpty(context.getIntents())) {
            return false;
        }
        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        return CollUtil.isNotEmpty(NodeScoreFilters.kb(allScores));
    }

    private SearchChannelResult emptyResult(long startTime) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.GRAPH)
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }
}
