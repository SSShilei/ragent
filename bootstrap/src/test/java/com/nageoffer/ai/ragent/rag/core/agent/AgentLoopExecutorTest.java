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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.LLMResponse;
import com.nageoffer.ai.ragent.framework.convention.ToolCall;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.rag.config.AgentProperties;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolSchemaConverter;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopExecutorTest {

    private final LLMService llmService = mock(LLMService.class);
    private final McpToolRegistry toolRegistry = mock(McpToolRegistry.class);
    private final McpToolSchemaConverter schemaConverter = new McpToolSchemaConverter();
    private final AgentLoopCheckpointStore checkpointStore = mock(AgentLoopCheckpointStore.class);
    private final TokenCounterService tokenCounterService = mock(TokenCounterService.class);
    // 同步执行器，避免测试中的线程切换不确定性
    private final Executor directExecutor = Runnable::run;

    private final AgentLoopExecutor loop = new AgentLoopExecutor(
            llmService, toolRegistry, schemaConverter, directExecutor, new AgentProperties(),
            checkpointStore, tokenCounterService);

    @BeforeEach
    void setUp() {
        // 默认无断点，避免 mock 返回 null 触发 NPE
        when(checkpointStore.load(any())).thenReturn(Optional.empty());
    }

    @Test
    void returnsTextAnswerDirectly() {
        when(llmService.chatWithTools(any())).thenReturn(LLMResponse.textOnly("你好，有什么可以帮你？"));

        String result = loop.run(ctx());

        assertEquals("你好，有什么可以帮你？", result);
        verify(llmService, times(1)).chatWithTools(any());
    }

    @Test
    void executesToolThenReturnsFinalAnswer() {
        ToolCall tc = new ToolCall("call_1", "weather_query", Map.of("city", "北京"));
        when(llmService.chatWithTools(any()))
                .thenReturn(new LLMResponse(null, List.of(tc)))
                .thenReturn(LLMResponse.textOnly("北京今天晴，25°C"));

        McpToolExecutor toolExecutor = mock(McpToolExecutor.class);
        when(toolRegistry.getExecutor("weather_query")).thenReturn(Optional.of(toolExecutor));
        when(toolExecutor.execute(anyMap())).thenReturn(
                CallToolResult.builder().content(List.of(new TextContent("北京 晴 25°C"))).build());

        String result = loop.run(ctx());

        assertEquals("北京今天晴，25°C", result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(toolExecutor).execute(captor.capture());
        assertEquals("北京", captor.getValue().get("city"));
        verify(llmService, times(2)).chatWithTools(any());
    }

    @Test
    void unregisteredToolFallsBackThenContinues() {
        when(llmService.chatWithTools(any()))
                .thenReturn(new LLMResponse(null, List.of(new ToolCall("call_1", "unknown_tool", Map.of()))))
                .thenReturn(LLMResponse.textOnly("无法调用工具，给出通用回答"));

        when(toolRegistry.getExecutor("unknown_tool")).thenReturn(Optional.empty());

        String result = loop.run(ctx());

        assertEquals("无法调用工具，给出通用回答", result);
        verify(llmService, times(2)).chatWithTools(any());
    }

    @Test
    void repeatedSameToolAbortsLoop() {
        when(llmService.chatWithTools(any()))
                .thenReturn(new LLMResponse(null, List.of(new ToolCall("call_1", "weather_query", Map.of()))));

        McpToolExecutor toolExecutor = mock(McpToolExecutor.class);
        when(toolRegistry.getExecutor("weather_query")).thenReturn(Optional.of(toolExecutor));
        when(toolExecutor.execute(anyMap())).thenReturn(
                CallToolResult.builder().content(List.of(new TextContent("x"))).build());

        String result = loop.run(ctx());

        assertTrue(result.contains("循环"));
        // 连续 3 次调用同一工具后，第 3 次 LLM 调用时检测到重复并终止
        verify(llmService, times(3)).chatWithTools(any());
    }

    @Test
    void truncatesLongToolOutput() {
        when(llmService.chatWithTools(any()))
                .thenReturn(new LLMResponse(null, List.of(new ToolCall("call_1", "t", Map.of()))))
                .thenReturn(LLMResponse.textOnly("done"));

        McpToolExecutor toolExecutor = mock(McpToolExecutor.class);
        when(toolRegistry.getExecutor("t")).thenReturn(Optional.of(toolExecutor));
        when(toolExecutor.execute(anyMap())).thenReturn(
                CallToolResult.builder().content(List.of(new TextContent("x".repeat(5000)))).build());

        loop.run(ctx());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService, times(2)).chatWithTools(captor.capture());
        ChatRequest secondRequest = captor.getAllValues().get(1);
        ChatMessage toolMsg = secondRequest.getMessages().stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .findFirst()
                .orElseThrow();
        assertTrue(toolMsg.getContent().endsWith("...(已截断)"));
        assertEquals(4000 + "...(已截断)".length(), toolMsg.getContent().length());
    }

    private AgentLoopContext ctx() {
        return AgentLoopContext.builder()
                .messages(List.of(ChatMessage.user("北京天气怎么样")))
                .tools(List.of())
                .build();
    }
}
