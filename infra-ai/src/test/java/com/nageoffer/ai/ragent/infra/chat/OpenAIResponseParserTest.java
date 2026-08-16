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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.LLMResponse;
import com.nageoffer.ai.ragent.framework.convention.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIResponseParserTest {

    private final Gson gson = new Gson();

    @Test
    void parseTextResponse() {
        JsonObject resp = json("""
                {"choices":[{"message":{"role":"assistant","content":"今天天气晴"}}]}
                """);

        LLMResponse result = OpenAIResponseParser.parse(gson, resp);

        assertEquals("今天天气晴", result.content());
        assertFalse(result.hasToolCalls());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void parseToolCallsResponseWithNullContent() {
        JsonObject resp = json("""
                {"choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_1","type":"function",
                    "function":{"name":"weather_query","arguments":"{\\"city\\": \\"北京\\"}"}}]}}]}
                """);

        LLMResponse result = OpenAIResponseParser.parse(gson, resp);

        assertNull(result.content());
        assertTrue(result.hasToolCalls());
        assertEquals(1, result.toolCalls().size());
        ToolCall tc = result.toolCalls().get(0);
        assertEquals("call_1", tc.id());
        assertEquals("weather_query", tc.name());
        assertEquals(Map.of("city", "北京"), tc.arguments());
    }

    @Test
    void parseArgumentsAsJsonString() {
        JsonObject resp = json("""
                {"choices":[{"message":{"tool_calls":[{"id":"c1",
                  "function":{"name":"f","arguments":"{\\"a\\": 1, \\"b\\": true}"}}]}}]}
                """);

        ToolCall tc = OpenAIResponseParser.parse(gson, resp).toolCalls().get(0);

        // Gson 将 JSON 数字默认解析为 Double
        assertEquals(Map.of("a", 1.0, "b", true), tc.arguments());
    }

    @Test
    void parseBlankArgumentsReturnsEmptyMap() {
        JsonObject resp = json("""
                {"choices":[{"message":{"tool_calls":[{"id":"c1",
                  "function":{"name":"f","arguments":""}}]}}]}
                """);

        ToolCall tc = OpenAIResponseParser.parse(gson, resp).toolCalls().get(0);
        assertEquals(Map.of(), tc.arguments());
    }

    @Test
    void parseInvalidArgumentsReturnsEmptyMap() {
        JsonObject resp = json("""
                {"choices":[{"message":{"tool_calls":[{"id":"c1",
                  "function":{"name":"f","arguments":"not-a-json"}}]}}]}
                """);

        ToolCall tc = OpenAIResponseParser.parse(gson, resp).toolCalls().get(0);
        assertEquals(Map.of(), tc.arguments());
    }

    @Test
    void parseMissingChoicesThrows() {
        assertThrows(IllegalStateException.class,
                () -> OpenAIResponseParser.parse(gson, json("{}")));
    }

    private JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
