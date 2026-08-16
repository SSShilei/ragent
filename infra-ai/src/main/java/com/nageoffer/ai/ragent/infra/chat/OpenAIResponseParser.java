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

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.nageoffer.ai.ragent.framework.convention.LLMResponse;
import com.nageoffer.ai.ragent.framework.convention.ToolCall;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议同步响应的结构化解析器
 * <p>
 * 从一次非流式 chat 的完整响应 JSON 中解析出文本内容与工具调用列表，
 * 与流式 {@link OpenAIStyleSseParser} 互补：本类处理完整的 message 结构。
 * <p>
 * 解析容错策略：响应结构缺失时抛 {@link IllegalStateException} 交由上层降级处理；
 * tool_calls 的 arguments 是 JSON 字符串，二次解析失败时降级为空 Map 而非中断整个响应。
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OpenAIResponseParser {

    /**
     * 解析同步响应，返回结构化 LLMResponse
     *
     * @param gson     JSON 解析器
     * @param respJson OpenAI 协议的完整响应 JSON
     * @return 文本内容 + 工具调用列表（两者至少其一非空）
     */
    public static LLMResponse parse(Gson gson, JsonObject respJson) {
        JsonObject message = extractMessage(respJson);

        String content = null;
        if (message.has("content") && !message.get("content").isJsonNull()) {
            content = message.get("content").getAsString();
        }

        List<ToolCall> toolCalls = parseToolCalls(gson, message);
        return new LLMResponse(content, toolCalls);
    }

    /**
     * 提取 choices[0].message，结构缺失时抛异常
     */
    private static JsonObject extractMessage(JsonObject respJson) {
        if (respJson == null || !respJson.has("choices")) {
            throw new IllegalStateException("OpenAI response missing choices");
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("OpenAI response choices is empty");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null) {
            throw new IllegalStateException("OpenAI response missing message");
        }
        return message;
    }

    /**
     * 解析 message.tool_calls 数组，逐元素提取 id / name / arguments
     */
    private static List<ToolCall> parseToolCalls(Gson gson, JsonObject message) {
        if (!message.has("tool_calls") || message.get("tool_calls").isJsonNull()) {
            return List.of();
        }
        JsonArray array = message.getAsJsonArray("tool_calls");
        List<ToolCall> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject tc = element.getAsJsonObject();
            if (tc == null) {
                continue;
            }
            String id = tc.has("id") && !tc.get("id").isJsonNull() ? tc.get("id").getAsString() : null;
            JsonObject function = tc.getAsJsonObject("function");
            if (function == null || !function.has("name") || function.get("name").isJsonNull()) {
                continue;
            }
            String name = function.get("name").getAsString();
            Map<String, Object> arguments = parseArguments(gson, function.get("arguments"));
            result.add(new ToolCall(id, name, arguments));
        }
        return result;
    }

    /**
     * 解析 function.arguments：OpenAI 返回的是 JSON 字符串，需二次解析为 Map
     */
    private static Map<String, Object> parseArguments(Gson gson, JsonElement argumentsElement) {
        if (argumentsElement == null || argumentsElement.isJsonNull()) {
            return Map.of();
        }
        String argsJson = argumentsElement.getAsString();
        if (StrUtil.isBlank(argsJson)) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = gson.fromJson(argsJson, Map.class);
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            log.warn("Failed to parse tool_calls arguments json: {}, reason: {}", argsJson, e.getMessage());
            return Map.of();
        }
    }
}
