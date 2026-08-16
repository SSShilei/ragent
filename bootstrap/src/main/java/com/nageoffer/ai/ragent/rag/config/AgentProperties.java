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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Agent ReAct 循环（Function Calling）配置
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   agent:
 *     function-calling:
 *       enabled: false                     # 总开关，默认关
 *       max-llm-calls: 15                  # 循环 LLM 调用硬上限
 *       max-tool-output-chars: 4000        # 单工具结果最大字符数
 *       max-consecutive-same-tool: 3       # 连续调用同一工具上限
 *       single-tool-timeout-seconds: 30    # 单工具执行超时
 * </pre>
 */
@Data
@Configuration
public class AgentProperties {

    /**
     * Function Calling 总开关
     * <p>
     * 为 true 且意图命中 MCP 节点时，对话进入 ReAct 循环由 LLM 自主调用工具；
     * 否则走原有意图树路由（确定性单次工具调用）。默认关闭。
     */
    @Value("${rag.agent.function-calling.enabled:false}")
    private boolean functionCallingEnabled = false;

    /**
     * ReAct 循环 LLM 调用硬上限，防止死循环与成本失控，默认 15
     */
    @Value("${rag.agent.function-calling.max-llm-calls:15}")
    private int maxLlmCalls = 15;

    /**
     * 单工具结果最大字符数，超出部分硬截断，防止上下文爆炸，默认 4000
     */
    @Value("${rag.agent.function-calling.max-tool-output-chars:4000}")
    private int maxToolOutputChars = 4000;

    /**
     * 连续调用同一工具的最大次数，超过视为死循环终止，默认 3
     */
    @Value("${rag.agent.function-calling.max-consecutive-same-tool:3}")
    private int maxConsecutiveSameTool = 3;

    /**
     * 单工具执行超时（秒），超时降级为错误结果，默认 30
     */
    @Value("${rag.agent.function-calling.single-tool-timeout-seconds:30}")
    private int singleToolTimeoutSeconds = 30;
}
