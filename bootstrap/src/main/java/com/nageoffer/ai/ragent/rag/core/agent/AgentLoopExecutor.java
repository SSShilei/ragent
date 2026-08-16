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

package com.nageoffer.ai.ragent.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.LLMResponse;
import com.nageoffer.ai.ragent.framework.convention.ToolCall;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.AgentProperties;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolSchemaConverter;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * ReAct 循环执行器（Agent Harness 的循环本体）
 * <p>
 * 执行「Thought → Action → Observation」循环：每轮调用 LLM，若返回 tool_calls 则并行执行
 * 工具并把结果回填为 TOOL 消息继续下一轮，若返回文本则作为最终答案终止。
 * <p>
 * 四层防御（防死循环 / 成本失控）：
 * <ol>
 *   <li>硬上限：max-llm-calls 次 LLM 调用后强制终止</li>
 *   <li>重复检测：连续 max-consecutive-same-tool 次调用同一工具即终止</li>
 *   <li>工具结果截断：max-tool-output-chars 字符硬截断，防上下文爆炸</li>
 *   <li>单工具超时：single-tool-timeout-seconds 超时降级为错误结果</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopExecutor {

    private final LLMService llmService;
    private final McpToolRegistry toolRegistry;
    private final McpToolSchemaConverter schemaConverter;
    private final Executor mcpBatchExecutor;
    private final AgentProperties agentProperties;

    /**
     * 执行 Agent 循环，返回最终文本回答
     *
     * @param ctx 循环上下文（消息序列 + 工具清单）
     * @return 最终文本答案（循环超限或陷入重复时返回兜底提示）
     */
    public String run(AgentLoopContext ctx) {
        List<ChatMessage> messages = new ArrayList<>(ctx.getMessages());
        List<Map<String, Object>> toolDeclarations = schemaConverter.convertTools(ctx.getTools());
        int maxLlmCalls = agentProperties.getMaxLlmCalls();
        int maxConsecutiveSameTool = agentProperties.getMaxConsecutiveSameTool();

        int round = 0;
        String lastToolName = null;
        int consecutiveSameCall = 0;

        while (round < maxLlmCalls) {
            round++;
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .tools(toolDeclarations)
                    .temperature(0D)
                    .topP(1D)
                    .build();

            LLMResponse response = llmService.chatWithTools(request);

            if (!response.hasToolCalls()) {
                log.info("Agent loop finished, round={}, answerLength={}", round, StrUtil.length(response.content()));
                return StrUtil.isBlank(response.content()) ? "" : response.content();
            }

            List<ToolCall> toolCalls = response.toolCalls();
            log.info("Agent loop round={}, toolCalls={}", round, toolCalls.stream().map(ToolCall::name).toList());

            // 回填 assistant tool_calls 消息，使后续 TOOL 消息与之配对
            messages.add(ChatMessage.assistantWithToolCalls(toolCalls));

            // 重复调用检测：连续调用同一工具视为死循环
            for (ToolCall tc : toolCalls) {
                consecutiveSameCall = tc.name().equals(lastToolName) ? consecutiveSameCall + 1 : 1;
                lastToolName = tc.name();
                if (consecutiveSameCall >= maxConsecutiveSameTool) {
                    log.warn("Agent loop repeated same tool {} times, tool={}, abort", consecutiveSameCall, tc.name());
                    return "工具调用似乎陷入循环，请换一种方式提问。";
                }
            }

            // 并行执行工具并回填结果
            List<ToolResult> results = executeParallel(toolCalls);
            for (ToolResult result : results) {
                messages.add(ChatMessage.tool(result.toolCallId(), truncate(result.output())));
            }
        }

        log.warn("Agent loop exceeded max llm calls, max={}", maxLlmCalls);
        return "处理步骤超过上限，请简化问题后重试。";
    }

    /**
     * 并行执行所有 tool_calls，每项各自带超时保护
     */
    private List<ToolResult> executeParallel(List<ToolCall> toolCalls) {
        List<CompletableFuture<ToolResult>> futures = toolCalls.stream()
                .map(tc -> CompletableFuture.supplyAsync(() -> executeTool(tc), mcpBatchExecutor))
                .toList();
        List<ToolResult> results = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            results.add(awaitResult(futures.get(i), toolCalls.get(i)));
        }
        return results;
    }

    /**
     * 执行单个工具：查找执行器并调用，异常降级为错误结果
     */
    private ToolResult executeTool(ToolCall tc) {
        try {
            McpToolExecutor executor = toolRegistry.getExecutor(tc.name()).orElse(null);
            if (executor == null) {
                log.warn("Tool not registered: {}", tc.name());
                return new ToolResult(tc.id(), "工具未注册: " + tc.name());
            }
            CallToolResult result = executor.execute(tc.arguments());
            return new ToolResult(tc.id(), extractToolOutput(result));
        } catch (Exception e) {
            log.warn("Tool execution failed: {}, reason: {}", tc.name(), e.getMessage());
            return new ToolResult(tc.id(), "工具执行异常: " + e.getMessage());
        }
    }

    /**
     * 等待工具执行结果，超时降级为错误结果
     */
    private ToolResult awaitResult(CompletableFuture<ToolResult> future, ToolCall tc) {
        Duration timeout = Duration.ofSeconds(agentProperties.getSingleToolTimeoutSeconds());
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Tool execution timeout, tool: {}", tc.name());
            return new ToolResult(tc.id(), "工具执行超时，请换参数重试或换工具");
        } catch (Exception e) {
            log.warn("Tool execution interrupted, tool: {}, reason: {}", tc.name(), e.getMessage());
            return new ToolResult(tc.id(), "工具执行异常: " + e.getMessage());
        }
    }

    /**
     * 从 CallToolResult 提取文本输出（仅取 TextContent）
     */
    private String extractToolOutput(CallToolResult result) {
        if (result == null || CollUtil.isEmpty(result.content())) {
            return "";
        }
        return result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 工具结果截断，防止超长输出撑爆上下文
     */
    private String truncate(String text) {
        int maxChars = agentProperties.getMaxToolOutputChars();
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...(已截断)";
    }

    /**
     * 工具执行结果（内部承载）
     */
    private record ToolResult(String toolCallId, String output) {
    }
}
