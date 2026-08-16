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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.config.AgentProperties;
import com.nageoffer.ai.ragent.rag.core.agent.AgentLoopContext;
import com.nageoffer.ai.ragent.rag.core.agent.AgentLoopExecutor;
import com.nageoffer.ai.ragent.rag.core.agent.AgentToolResolver;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;

/**
 * 流式对话流水线
 * <p>
 * 承载从 RAGChatServiceImpl 提取的业务编排逻辑：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 系统响应 / 检索 -> Prompt 组装 -> 流式输出
 * <p>
 * 流水线模式：通过私有方法 + boolean 返回值（handleXxx 返回 true 表示已处理并短路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final SearchChannelProperties searchProperties;
    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final StreamTaskManager taskManager;
    private final AgentLoopExecutor agentLoopExecutor;
    private final AgentToolResolver agentToolResolver;
    private final AgentProperties agentProperties;

    /**
     * 执行流式对话管道
     * <p>
     * 编排 7 个阶段，其中 ④ 歧义引导、⑤ 纯闲聊、⑥ 检索空结果 三个短路点命中即终止，
     * 否则走到 ⑦ Prompt 组装 + LLM 流式输出。每个阶段内部记录耗时与关键产出，便于慢链路排查
     */
    public void execute(StreamChatContext ctx) {
        long pipelineStart = System.currentTimeMillis();
        log.info("Chat pipeline start, question={}, conversationId={}, taskId={}, deepThinking={}",
                ctx.getQuestion(), ctx.getConversationId(), ctx.getTaskId(), ctx.isDeepThinking());

        loadMemory(ctx);       // ① 记忆加载（并行摘要+历史）
        rewriteQuery(ctx);     // ② Query 重写 + 多问句拆分
        resolveIntents(ctx);   // ③ 意图识别（基于 ② 的输出）

        if (handleGuidance(ctx)) {        // ④ 歧义引导（短路点 1）
            return;
        }
        if (handleSystemOnly(ctx)) {      // ⑤ 纯闲聊短路（短路点 2）
            return;
        }

        // ⑥ 检索：Agent 循环分支只做 KB 检索（工具由 LLM 自主调用），普通分支 KB + MCP 一次执行
        boolean agentLoop = requiresAgentLoop(ctx);
        RetrievalContext retrievalCtx = retrieve(ctx, agentLoop);

        if (agentLoop) {
            streamAgentLoopResponse(ctx, retrievalCtx);                      // ⑦-a ReAct 循环（可选增强分支）
            return;
        }

        if (handleEmptyRetrieval(ctx, retrievalCtx)) {                       // 检索空结果兜底（短路点 3）
            return;
        }

        streamRagResponse(ctx, retrievalCtx);                                // ⑦ Prompt 组装 + LLM 流式输出
        log.info("Chat pipeline end, scene=rag, elapsed={}ms", elapsed(pipelineStart));
    }

    // ==================== 流水线阶段 ====================

    /**
     * ① 记忆加载：并行加载历史摘要与消息，并把当前问题追加进上下文
     */
    private void loadMemory(StreamChatContext ctx) {
        long start = System.currentTimeMillis();
        List<ChatMessage> history = memoryService.loadAndAppend(
                ctx.getConversationId(),
                ctx.getUserId(),
                ChatMessage.user(ctx.getQuestion())
        );
        ctx.setHistory(history);
        log.info("Stage[1/7] loadMemory finished, historySize={}, elapsed={}ms", sizeOf(history), elapsed(start));
    }

    /**
     * ② Query 重写：LLM 改写 + 多问句拆分 + 可选 Multi-Query 变体
     */
    private void rewriteQuery(StreamChatContext ctx) {
        long start = System.currentTimeMillis();
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        ctx.setRewriteResult(rewriteResult);
        log.info("Stage[2/7] rewriteQuery finished, rewritten={}, subQuestions={}, hasVariants={}, elapsed={}ms",
                rewriteResult.rewrittenQuestion(), sizeOf(rewriteResult.subQuestions()),
                rewriteResult.hasVariants(), elapsed(start));
    }

    /**
     * ③ 意图识别：并行对每个子问题做 LLM 叶子节点打分，过滤低分、截断 topN
     */
    private void resolveIntents(StreamChatContext ctx) {
        long start = System.currentTimeMillis();
        List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
        ctx.setSubIntents(subIntents);
        int intentHits = subIntents.stream().mapToInt(si -> sizeOf(si.nodeScores())).sum();
        log.info("Stage[3/7] resolveIntents finished, subIntents={}, intentHits={}, elapsed={}ms",
                subIntents.size(), intentHits, elapsed(start));
    }

    /**
     * ④ 歧义引导（短路点 1）：多 KB 意图得分接近时反问用户澄清，命中则不再检索
     */
    private boolean handleGuidance(StreamChatContext ctx) {
        long start = System.currentTimeMillis();
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );
        if (!decision.isPrompt()) {
            log.info("Stage[4/7] handleGuidance skipped, no ambiguity, elapsed={}ms", elapsed(start));
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent(decision.getPrompt());
        callback.onComplete();
        log.info("Stage[4/7] handleGuidance triggered, ask user for clarification, elapsed={}ms", elapsed(start));
        return true;
    }

    /**
     * ⑤ 纯闲聊短路（短路点 2）：所有意图都是 SYSTEM 时直接 LLM 回答，跳过检索
     */
    private boolean handleSystemOnly(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (!allSystemOnly) {
            return false;
        }
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
        log.info("Stage[5/7] handleSystemOnly short-circuit, skip retrieval, customPromptApplied={}",
                StrUtil.isNotBlank(customPrompt));
        return true;
    }

    /**
     * ⑥ 检索：KB 多通道检索（可选叠加 MCP 工具单次执行），产出统一 RetrievalContext
     */
    private RetrievalContext retrieve(StreamChatContext ctx, boolean kbOnly) {
        long start = System.currentTimeMillis();
        RetrievalContext retrievalCtx = kbOnly
                ? retrievalEngine.retrieveKbOnly(ctx.getSubIntents(), searchProperties.getDefaultTopK())
                : retrievalEngine.retrieve(ctx.getSubIntents(), searchProperties.getDefaultTopK());
        log.info("Stage[6/7] retrieve finished, hasKb={}, hasMcp={}, kbOnly={}, elapsed={}ms",
                retrievalCtx.hasKb(), retrievalCtx.hasMcp(), kbOnly, elapsed(start));
        return retrievalCtx;
    }

    /**
     * 判断是否进入 Agent 循环：开关开启且意图命中 MCP 节点
     */
    private boolean requiresAgentLoop(StreamChatContext ctx) {
        if (!agentProperties.isFunctionCallingEnabled()) {
            return false;
        }
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());
        return CollUtil.isNotEmpty(mergedGroup.mcpIntents());
    }

    /**
     * ⑦-a ReAct 循环：LLM 自主决定工具调用，产出最终答案后流式推送
     */
    private void streamAgentLoopResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        long start = System.currentTimeMillis();
        // 工具白名单：仅暴露意图命中的 MCP 工具，缩小 LLM 决策面
        List<Tool> whitelistTools = agentToolResolver.resolveWhitelistTools(ctx.getSubIntents());

        // 组装初始消息：KB 证据（若有）+ 问题，工具由 LLM 在循环中自主调用
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());
        PromptContext promptContext = PromptContext.builder()
                .question(ctx.getRewriteResult().rewrittenQuestion())
                .kbContext(retrievalCtx.getKbContext())
                .mcpContext(null)
                .mcpIntents(mergedGroup.mcpIntents())
                .kbIntents(mergedGroup.kbIntents())
                .intentChunks(retrievalCtx.getIntentChunks())
                .build();
        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                ctx.getHistory(),
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getRewriteResult().subQuestions()
        );

        AgentLoopContext loopCtx = AgentLoopContext.builder()
                .messages(messages)
                .tools(whitelistTools)
                .build();
        String answer = agentLoopExecutor.run(loopCtx);

        ctx.getCallback().onContent(answer);
        ctx.getCallback().onComplete();
        log.info("Agent loop response finished, whitelistTools={}, answerLength={}, elapsed={}ms",
                whitelistTools.size(), answer.length(), elapsed(start));
    }

    /**
     * 检索空结果兜底（短路点 3）：KB 与 MCP 都无上下文时直接提示未检索到内容
     */
    private boolean handleEmptyRetrieval(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        if (!retrievalCtx.isEmpty()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent("未检索到与问题相关的文档内容。");
        callback.onComplete();
        log.info("Stage[6/7] empty retrieval, fallback response sent, question={}", ctx.getQuestion());
        return true;
    }

    /**
     * ⑦ Prompt 组装 + LLM 流式输出：聚合意图、构造 PromptContext，发出流式请求并绑定取消句柄
     */
    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        long start = System.currentTimeMillis();
        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                mergedGroup,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
        log.info("Stage[7/7] RAG response streaming, mcpIntents={}, kbIntents={}, elapsed={}ms",
                mergedGroup.mcpIntents().size(), mergedGroup.kbIntents().size(), elapsed(start));
    }

    // ==================== LLM 响应 ====================

    /**
     * 纯闲聊/系统响应的流式回答：走轻量 system prompt，不携带检索证据
     */
    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        log.info("System response streaming, customPromptApplied={}, messages={}, temperature=0.7",
                StrUtil.isNotBlank(customPrompt), messages.size());
        return llmService.streamChat(req, callback);
    }

    /**
     * RAG 响应的流式回答：按 KB/MCP/Mixed 场景选模板组装消息，发出 LLM 流式请求
     */
    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        log.info("RAG LLM request built, scene={}, messages={}, temperature={}, topP={}, thinking={}",
                ctx.hasMcp() ? "MCP" : "KB", messages.size(),
                chatRequest.getTemperature(), chatRequest.getTopP(), deepThinking);
        return llmService.streamChat(chatRequest, callback);
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
