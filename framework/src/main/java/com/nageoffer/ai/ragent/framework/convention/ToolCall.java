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

import java.util.Map;

/**
 * 一次工具调用
 * <p>
 * 对应 OpenAI 协议 assistant 消息中 tool_calls 数组的单个元素，
 * 由模型在 Function Calling 决策阶段返回，携带要调用的工具名与参数。
 *
 * @param id        工具调用 ID，用于把工具结果回填到对应的 tool_call
 * @param name      工具名，与 MCP Tool.name() 一一对应
 * @param arguments 调用参数（已解析为 Map）
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {
}
