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

package com.nageoffer.ai.ragent.rag.eval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.agent.AgentLoopContext;
import com.nageoffer.ai.ragent.rag.core.agent.AgentLoopExecutor;
import com.nageoffer.ai.ragent.rag.core.agent.AgentLoopResult;
import com.nageoffer.ai.ragent.rag.core.agent.AgentToolResolver;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 效果评测接口
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.eval", name = "enabled", havingValue = "true")
public class EvalController {

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final AgentLoopExecutor agentLoopExecutor;
    private final AgentToolResolver agentToolResolver;
    private final RAGPromptService promptBuilder;

    @GetMapping("/rag/eval")
    public Result<EvalResponse> chat(@RequestParam String question) {
        long start = System.currentTimeMillis();

        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, List.of());
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);
        RetrievalContext rc = retrievalEngine.retrieve(subIntents, searchProperties.getDefaultTopK());

        return Results.success(buildResponse(rc, subIntents, System.currentTimeMillis() - start));
    }

    /**
     * Agent 工具调用评测：走 ReAct 循环（调 LLM），对比实际工具调用与期望工具调用
     */
    @GetMapping("/rag/eval/agent")
    public Result<EvalResponse> chatAgent(@RequestParam String question,
                                          @RequestParam(required = false) List<String> expectedToolCalls) {
        long start = System.currentTimeMillis();

        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, List.of());
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);
        // Agent 循环分支：只 KB 检索，工具由 LLM 自主调用
        RetrievalContext rc = retrievalEngine.retrieveKbOnly(subIntents, searchProperties.getDefaultTopK());

        // 工具白名单：仅暴露意图命中的 MCP 工具
        List<Tool> whitelistTools = agentToolResolver.resolveWhitelistTools(subIntents);
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .kbContext(rc.getKbContext())
                .mcpContext(null)
                .mcpIntents(mergedGroup.mcpIntents())
                .kbIntents(mergedGroup.kbIntents())
                .intentChunks(rc.getIntentChunks())
                .build();
        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext, List.of(), rewriteResult.rewrittenQuestion(), rewriteResult.subQuestions());

        AgentLoopContext loopCtx = AgentLoopContext.builder()
                .messages(messages)
                .tools(whitelistTools)
                .build();

        AgentLoopResult loopResult = agentLoopExecutor.runWithMetadata(loopCtx);

        EvalResponse response = buildResponse(rc, subIntents, System.currentTimeMillis() - start);
        response.setExpectedToolCalls(expectedToolCalls);
        response.setActualToolCalls(loopResult.toolCalls());
        response.setToolCallRound(loopResult.round());
        response.setTotalTokens(loopResult.totalTokens());
        return Results.success(response);
    }

    private EvalResponse buildResponse(RetrievalContext rc, List<SubQuestionIntent> subIntents, long latencyMs) {
        List<RetrievedChunk> uniqueChunks = flattenChunks(rc);
        List<String> chunkIds = uniqueChunks.stream()
                .map(RetrievedChunk::getId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
        List<String> contexts = uniqueChunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.toList());

        // chunk 维度的 docId 列表：与 contexts 一一对应、保留 null、不去重
        List<String> contextDocIds = resolveContextDocIds(uniqueChunks);
        // doc 维度的 docId 列表：保持原语义（按 chunk 顺序首次出现、过滤 null）
        List<String> docIds = dedupNonBlank(contextDocIds);

        return EvalResponse.builder()
                .retrievedDocIds(docIds)
                .retrievedChunkIds(chunkIds)
                .retrievedContexts(contexts)
                .retrievedContextDocIds(contextDocIds)
                .mcpContext(rc == null ? null : rc.getMcpContext())
                .hasMcp(rc != null && rc.hasMcp())
                .hasKb(rc != null && rc.hasKb())
                .subIntents(extractSubIntents(subIntents))
                .intentLeafIds(extractTopLeafIds(subIntents))
                .latencyMs(latencyMs)
                .build();
    }

    /**
     * 摊平 intentChunks（Map<intentId, List<RetrievedChunk>>），按 chunk id 去重并保留首次顺序
     */
    private List<RetrievedChunk> flattenChunks(RetrievalContext rc) {
        if (rc == null || CollUtil.isEmpty(rc.getIntentChunks())) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        return rc.getIntentChunks().values().stream()
                .filter(CollUtil::isNotEmpty)
                .flatMap(List::stream)
                .filter(c -> c != null && StrUtil.isNotBlank(c.getId()))
                .filter(c -> seen.add(c.getId()))
                .collect(Collectors.toList());
    }

    /**
     * 与 chunks 一一对应的业务 docId 列表（长度相同、保留 null、不去重）
     * 链路：chunkId → t_knowledge_chunk.docId（雪花）→ t_knowledge_document.doc_name → 剥文件后缀
     * 评测集的 reference_doc_ids 用业务码（如 `FAQ_VAC_001`），与此处对齐
     */
    private List<String> resolveContextDocIds(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return Collections.emptyList();
        }
        List<String> chunkIdsForLookup = chunks.stream()
                .map(RetrievedChunk::getId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        if (chunkIdsForLookup.isEmpty()) {
            return new java.util.ArrayList<>(Collections.nCopies(chunks.size(), null));
        }
        // 第一跳：chunkId → 雪花 docId
        Map<String, String> chunkIdToInternalDocId = knowledgeChunkMapper.selectByIds(chunkIdsForLookup).stream()
                .filter(c -> StrUtil.isNotBlank(c.getId()) && StrUtil.isNotBlank(c.getDocId()))
                .collect(Collectors.toMap(
                        KnowledgeChunkDO::getId,
                        KnowledgeChunkDO::getDocId,
                        (a, b) -> a));
        // 第二跳：雪花 docId → 业务码（doc_name 剥后缀）
        List<String> internalDocIds = chunkIdToInternalDocId.values().stream().distinct().collect(Collectors.toList());
        Map<String, String> internalToBizDocId = internalDocIds.isEmpty()
                ? Map.of()
                : knowledgeDocumentMapper.selectByIds(internalDocIds).stream()
                        .filter(d -> StrUtil.isNotBlank(d.getId()) && StrUtil.isNotBlank(d.getDocName()))
                        .collect(Collectors.toMap(
                                KnowledgeDocumentDO::getId,
                                d -> stripExtension(d.getDocName()),
                                (a, b) -> a));
        // 按 chunks 原顺序展开（null 占位保留）
        return chunks.stream()
                .map(c -> {
                    if (StrUtil.isBlank(c.getId())) {
                        return null;
                    }
                    String internal = chunkIdToInternalDocId.get(c.getId());
                    if (StrUtil.isBlank(internal)) {
                        return null;
                    }
                    return internalToBizDocId.get(internal);
                })
                .collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * 剥掉最后一个 `.` 之后的文件扩展名；无后缀则原样返回
     */
    private static String stripExtension(String docName) {
        if (docName == null) {
            return null;
        }
        int dot = docName.lastIndexOf('.');
        return (dot > 0 && dot < docName.length() - 1) ? docName.substring(0, dot) : docName;
    }

    /**
     * 按首次出现顺序去重并过滤空值
     */
    private List<String> dedupNonBlank(List<String> in) {
        if (CollUtil.isEmpty(in)) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        return in.stream()
                .filter(StrUtil::isNotBlank)
                .filter(seen::add)
                .collect(Collectors.toList());
    }

    private List<String> extractSubIntents(List<SubQuestionIntent> intents) {
        if (CollUtil.isEmpty(intents)) {
            return Collections.emptyList();
        }
        return intents.stream()
                .map(SubQuestionIntent::subQuestion)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    private List<String> extractTopLeafIds(List<SubQuestionIntent> intents) {
        if (CollUtil.isEmpty(intents)) {
            return Collections.emptyList();
        }
        return intents.stream()
                .map(si -> {
                    if (CollUtil.isEmpty(si.nodeScores())) {
                        return null;
                    }
                    return si.nodeScores().get(0).getNode().getId();
                })
                .collect(Collectors.toList());
    }
}
