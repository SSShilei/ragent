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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.mcp.McpParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 负责协调多通道检索（知识库）和 MCP（模型控制协议）工具的调用，并对检索结果进行重排序和格式化，最终生成用于 LLM 的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final SearchChannelProperties searchProperties;
    private final ContextFormatter contextFormatter;
    private final PromptTemplateLoader templateLoader;
    private final McpParameterExtractor mcpParameterExtractor;
    private final McpToolRegistry mcpToolRegistry;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final Executor ragContextExecutor;
    private final Executor mcpBatchExecutor;

    /**
     * 检索方法：根据子问题意图列表执行检索，整合知识库和MCP工具的结果
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK) {
        long start = System.currentTimeMillis();
        if (CollUtil.isEmpty(subIntents)) {
            log.warn("RetrievalEngine skipped, subIntents is empty");
            return RetrievalContext.builder()
                    .intentChunks(Map.of())
                    .build();
        }

        int finalTopK = topK > 0 ? topK : searchProperties.getDefaultTopK();
        log.info("RetrievalEngine start, subIntents={}, topK={}", subIntents.size(), finalTopK);
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(si -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return buildSubQuestionContext(
                                        si,
                                        resolveSubQuestionTopK(si, finalTopK)
                                );
                            } catch (Exception e) {
                                log.error("子问题上下文构建失败，降级为空上下文，question：{}", si.subQuestion(), e);
                                return new SubQuestionContext(si.subQuestion(), "", "", Map.of());
                            }
                        },
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        Map<String, List<RetrievedChunk>> mergedIntentChunks = new HashMap<>();
        for (SubQuestionContext context : contexts) {
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                mergedIntentChunks.putAll(context.intentChunks());
            }
        }

        boolean singleQuestion = contexts.size() == 1;
        String kbContext;
        String mcpContext;

        if (singleQuestion) {
            SubQuestionContext only = contexts.get(0);
            kbContext = StrUtil.emptyIfNull(only.kbContext()).trim();
            mcpContext = StrUtil.emptyIfNull(only.mcpContext()).trim();
        } else {
            StringBuilder kbBuilder = new StringBuilder();
            StringBuilder mcpBuilder = new StringBuilder();
            int globalIndex = 0;
            for (SubQuestionContext context : contexts) {
                boolean hasKb = StrUtil.isNotBlank(context.kbContext());
                boolean hasMcp = StrUtil.isNotBlank(context.mcpContext());
                if (hasKb || hasMcp) {
                    globalIndex++;
                }
                if (hasKb) {
                    appendSection(kbBuilder, "sub-question-kb-wrapper", globalIndex, context.question(), context.kbContext());
                }
                if (hasMcp) {
                    appendSection(mcpBuilder, "sub-question-mcp-wrapper", globalIndex, context.question(), context.mcpContext());
                }
            }
            kbContext = kbBuilder.toString().trim();
            mcpContext = mcpBuilder.toString().trim();
        }

        RetrievalContext result = RetrievalContext.builder()
                .mcpContext(mcpContext)
                .kbContext(kbContext)
                .intentChunks(mergedIntentChunks)
                .build();
        log.info("RetrievalEngine finished, kbContextChars={}, mcpContextChars={}, elapsed={}ms",
                kbContext.length(), mcpContext.length(), System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 构建单个子问题的检索上下文：KB 检索 + MCP 工具执行并行组织，产出该子问题的 KB/MCP 文本
     */
    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, int topK) {
        long start = System.currentTimeMillis();
        List<NodeScore> kbIntents = NodeScoreFilters.kb(intent.nodeScores());
        List<NodeScore> mcpIntents = NodeScoreFilters.mcp(intent.nodeScores());

        KbResult kbResult = retrieveAndRerank(intent, kbIntents, topK);

        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
                : "";

        log.info("Sub-question context built, question={}, kbIntents={}, mcpIntents={}, kbChars={}, mcpChars={}, elapsed={}ms",
                intent.subQuestion(), kbIntents.size(), mcpIntents.size(),
                kbResult.groupedContext().length(), mcpContext.length(),
                System.currentTimeMillis() - start);
        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
    }

    /**
     * 子问题实际 TopK 计算规则
     */
    private int resolveSubQuestionTopK(SubQuestionIntent intent, int fallbackTopK) {
        return NodeScoreFilters.kb(intent.nodeScores()).stream()
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getTopK)
                .filter(Objects::nonNull)
                .filter(topK -> topK > 0)
                .max(Integer::compareTo)
                .orElse(fallbackTopK);
    }

    private void appendSection(StringBuilder builder, String section, int index, String question, String context) {
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, section, Map.of(
                "index", String.valueOf(index),
                "question", question,
                "context", context
        )));
    }

    private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents) {
        long start = System.currentTimeMillis();
        if (CollUtil.isEmpty(mcpIntents)) {
            return "";
        }

        Map<String, List<CallToolResult>> toolResults = executeMcpTools(question, mcpIntents);
        if (toolResults.isEmpty()) {
            log.info("MCP tools returned empty, tools={}, question={}, elapsed={}ms",
                    mcpIntents.size(), question, System.currentTimeMillis() - start);
            return "";
        }

        String merged = contextFormatter.formatMcpContext(toolResults, mcpIntents);
        log.info("MCP context merged, tools={}, resultChars={}, elapsed={}ms",
                toolResults.size(), merged.length(), System.currentTimeMillis() - start);
        return merged;
    }

    private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, int topK) {
        long start = System.currentTimeMillis();
        // Multi-Query 变体：每个变体作为独立 query 并行检索
        List<String> variantQueries = intent.variantQueries();
        if (CollUtil.isNotEmpty(variantQueries) && variantQueries.size() > 1) {
            return retrieveWithVariants(intent, kbIntents, topK, variantQueries);
        }

        // 使用多通道检索引擎（是否启用全局检索由置信度阈值决定）
        List<SubQuestionIntent> subIntents = List.of(intent);
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK);

        if (CollUtil.isEmpty(chunks)) {
            log.info("KB retrieveAndRerank empty result, question={}, elapsed={}ms",
                    intent.subQuestion(), System.currentTimeMillis() - start);
            return KbResult.empty();
        }

        // 按意图节点分组（用于格式化上下文）
        Map<String, List<RetrievedChunk>> intentChunks = buildIntentChunks(chunks, kbIntents);
        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, topK);
        log.info("KB retrieveAndRerank finished, chunks={}, intentChunks={}, contextChars={}, elapsed={}ms",
                chunks.size(), intentChunks.size(), groupedContext.length(), System.currentTimeMillis() - start);
        return new KbResult(groupedContext, intentChunks);
    }

    /**
     * Multi-Query 变体检索：每个变体并行跑多通道检索，结果按去重合并
     * 同一 chunk 在多个变体中命中 → 保留最高优先级通道版本（复用 Dedup 通道优先级）
     */
    private KbResult retrieveWithVariants(SubQuestionIntent intent, List<NodeScore> kbIntents, int topK, List<String> variants) {
        long start = System.currentTimeMillis();
        // 每个变体并行检索
        List<CompletableFuture<List<RetrievedChunk>>> futures = variants.stream()
                .map(variant -> CompletableFuture.supplyAsync(
                        () -> {
                            SubQuestionIntent variantIntent = new SubQuestionIntent(variant, intent.nodeScores());
                            List<SubQuestionIntent> subIntents = List.of(variantIntent);
                            return multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK);
                        },
                        ragContextExecutor
                ))
                .toList();

        List<List<RetrievedChunk>> variantResults = futures.stream()
                .map(CompletableFuture::join)
                .filter(CollUtil::isNotEmpty)
                .toList();

        if (variantResults.isEmpty()) {
            log.info("Multi-variant retrieve empty result, variants={}, question={}, elapsed={}ms",
                    variants.size(), intent.subQuestion(), System.currentTimeMillis() - start);
            return KbResult.empty();
        }

        // 合并去重：同一 chunk 多变体命中 → 保留最高优先级通道的版本
        // LinkedHashMap 保持首次出现顺序（变体 0=原始 query 的优先级最高）
        Map<String, RetrievedChunk> merged = new java.util.LinkedHashMap<>();
        for (List<RetrievedChunk> chunks : variantResults) {
            for (RetrievedChunk chunk : chunks) {
                String key = chunkKey(chunk);
                if (!merged.containsKey(key)) {
                    merged.put(key, chunk);
                }
            }
        }

        List<RetrievedChunk> dedupChunks = new ArrayList<>(merged.values());
        Map<String, List<RetrievedChunk>> intentChunks = buildIntentChunks(dedupChunks, kbIntents);
        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, topK);
        log.info("Multi-variant retrieve finished, variants={}, hitVariants={}, mergedChunks={}, contextChars={}, elapsed={}ms",
                variants.size(), variantResults.size(), dedupChunks.size(),
                groupedContext.length(), System.currentTimeMillis() - start);
        return new KbResult(groupedContext, intentChunks);
    }

    private Map<String, List<RetrievedChunk>> buildIntentChunks(List<RetrievedChunk> chunks, List<NodeScore> kbIntents) {
        Map<String, List<RetrievedChunk>> intentChunks = new HashMap<>();
        if (CollUtil.isNotEmpty(kbIntents)) {
            for (NodeScore ns : kbIntents) {
                intentChunks.put(ns.getNode().getId(), chunks);
            }
        } else {
            intentChunks.put(MULTI_CHANNEL_KEY, chunks);
        }
        return intentChunks;
    }

    private String chunkKey(RetrievedChunk chunk) {
        return chunk.getId() != null ? chunk.getId() : StrUtil.isBlank(chunk.getText()) ? "" : chunk.getText().hashCode() + "";
    }

    /**
     * 执行 MCP 工具调用，返回按 toolId 分组的结果
     */
    private Map<String, List<CallToolResult>> executeMcpTools(String question,
                                                              List<NodeScore> mcpIntentScores) {
        if (CollUtil.isEmpty(mcpIntentScores)) {
            return Map.of();
        }

        List<CompletableFuture<ToolOutput>> futures = mcpIntentScores.stream()
                .map(ns -> CompletableFuture.supplyAsync(
                        () -> {
                            String toolId = ns.getNode().getMcpToolId();
                            try {
                                CallToolResult result = executeSingleMcpTool(question, ns.getNode());
                                return result == null ? null : new ToolOutput(toolId, result);
                            } catch (Exception e) {
                                log.error("MCP 工具调用异常, toolId: {}", toolId, e);
                                return new ToolOutput(toolId, CallToolResult.builder()
                                        .content(List.of(new TextContent("工具调用异常: " + e.getMessage())))
                                        .isError(true)
                                        .build());
                            }
                        },
                        mcpBatchExecutor
                ))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        ToolOutput::toolId,
                        Collectors.mapping(ToolOutput::result, Collectors.toList())
                ));
    }

    private CallToolResult executeSingleMcpTool(String question, IntentNode intentNode) {
        long start = System.currentTimeMillis();
        String toolId = intentNode.getMcpToolId();
        Optional<McpToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            return null;
        }

        McpToolExecutor executor = executorOpt.get();
        Tool tool = executor.getToolDefinition();

        String customParamPrompt = intentNode.getParamPromptTemplate();
        Map<String, Object> params = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);

        CallToolResult result = executor.execute(params != null ? params : new HashMap<>());
        log.info("MCP tool executed, toolId={}, params={}, isError={}, elapsed={}ms",
                toolId, params, result.isError(), System.currentTimeMillis() - start);
        return result;
    }

    private record ToolOutput(String toolId, CallToolResult result) {
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      String mcpContext,
                                      Map<String, List<RetrievedChunk>> intentChunks) {
    }
}
