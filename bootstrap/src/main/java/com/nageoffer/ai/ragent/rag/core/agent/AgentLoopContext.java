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
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Agent 循环上下文
 * <p>
 * 承载一次 ReAct 循环所需的全部入参：消息序列与可用工具清单。
 * 消息序列在循环中不断追加 assistant tool_calls 与 tool 结果，最终产出文本答案。
 */
@Data
@Builder
public class AgentLoopContext {

    /**
     * 对话消息序列（含系统提示词、历史与当前问题）
     */
    private List<ChatMessage> messages;

    /**
     * 可用工具清单（MCP Tool 定义），经 Schema 转换后作为 Function Calling 的 tools 声明
     */
    private List<Tool> tools;
}
