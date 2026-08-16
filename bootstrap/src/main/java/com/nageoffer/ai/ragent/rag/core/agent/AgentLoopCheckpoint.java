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

import java.util.List;

/**
 * Agent 循环断点
 * <p>
 * 记录某一轮结束后的循环状态：已执行轮数、完整消息序列与保存时间戳，
 * 用于实例崩溃后从断点续跑而非从头重来。
 *
 * @param round     已完成的循环轮数（恢复后从下一轮继续）
 * @param messages  截至当前轮的完整消息序列（含 assistant tool_calls 与 tool 结果）
 * @param timestamp 保存时间戳（毫秒）
 */
public record AgentLoopCheckpoint(int round, List<ChatMessage> messages, long timestamp) {
}
