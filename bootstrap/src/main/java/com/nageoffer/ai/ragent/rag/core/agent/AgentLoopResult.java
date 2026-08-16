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

import java.util.List;

/**
 * Agent 循环执行结果（含元数据）
 * <p>
 * 相比 {@link AgentLoopExecutor#run(AgentLoopContext)} 只返回最终答案，
 * 该对象额外携带评测与成本归因所需的元数据：实际调用的工具、循环轮数与 Token 估算。
 *
 * @param answer      最终文本答案
 * @param toolCalls   实际调用的工具名列表（按调用顺序，含重复）
 * @param round       实际执行的 LLM 调用轮数
 * @param totalTokens 累计 Token 估算（请求 + 响应）
 */
public record AgentLoopResult(String answer, List<String> toolCalls, int round, long totalTokens) {
}
