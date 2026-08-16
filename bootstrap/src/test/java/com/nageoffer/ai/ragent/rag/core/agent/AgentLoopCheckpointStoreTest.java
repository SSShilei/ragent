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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopCheckpointStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private final AgentLoopCheckpointStore store = new AgentLoopCheckpointStore(redisTemplate, objectMapper);

    @Test
    void checkpointJsonRoundTrip() throws Exception {
        // 覆盖 TOOL 消息与 assistant tool_calls 的序列化往返（checkpoint 核心风险点）
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("北京天气"),
                ChatMessage.assistantWithToolCalls(List.of(new ToolCall("call_1", "weather_query", Map.of("city", "北京")))),
                ChatMessage.tool("call_1", "北京 晴 25°C")
        );
        AgentLoopCheckpoint cp = new AgentLoopCheckpoint(3, messages, 123456L);

        String json = objectMapper.writeValueAsString(cp);
        AgentLoopCheckpoint restored = objectMapper.readValue(json, AgentLoopCheckpoint.class);

        assertEquals(3, restored.round());
        assertEquals(4, restored.messages().size());
        assertEquals(ChatMessage.Role.TOOL, restored.messages().get(3).getRole());
        assertEquals("call_1", restored.messages().get(3).getToolCallId());
        assertEquals("weather_query", restored.messages().get(2).getToolCalls().get(0).name());
        assertEquals("北京", restored.messages().get(2).getToolCalls().get(0).arguments().get("city"));
    }

    @Test
    void saveWritesJsonWithTtl() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        store.save("task_1", 2, List.of(ChatMessage.user("hi")));

        verify(valueOps).set(eq("ragent:agent:loop:task_1"), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void loadReturnsCheckpointWhenPresent() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String json = objectMapper.writeValueAsString(
                new AgentLoopCheckpoint(1, List.of(ChatMessage.user("hi")), 1L));
        when(valueOps.get("ragent:agent:loop:task_1")).thenReturn(json);

        Optional<AgentLoopCheckpoint> cp = store.load("task_1");

        assertTrue(cp.isPresent());
        assertEquals(1, cp.get().round());
    }

    @Test
    void loadReturnsEmptyForBlankTaskId() {
        assertTrue(store.load(null).isEmpty());
        assertTrue(store.load("").isEmpty());
    }
}
