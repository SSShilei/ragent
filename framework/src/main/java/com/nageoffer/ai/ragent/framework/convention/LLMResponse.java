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

package com.nageoffer.ai.ragent.framework.convention;

import java.util.List;

/**
 * 结构化 LLM 响应
 * <p>
 * 与普通 chat 只返回文本不同，该对象同时承载文本内容与工具调用列表，
 * 用于 Function Calling 决策层：模型要么给出最终文本答案，要么返回待执行的 tool_calls。
 *
 * @param content   文本内容（纯工具调用时可能为 null）
 * @param toolCalls 工具调用列表（纯文本回答时为空列表）
 */
public record LLMResponse(String content, List<ToolCall> toolCalls) {

    /**
     * 是否携带工具调用
     *
     * @return 工具调用列表非空时返回 true
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * 构造纯文本响应
     *
     * @param content 文本内容
     * @return 不含工具调用的响应对象
     */
    public static LLMResponse textOnly(String content) {
        return new LLMResponse(content, List.of());
    }
}
