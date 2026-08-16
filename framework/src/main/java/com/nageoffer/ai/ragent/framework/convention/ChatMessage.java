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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话消息实体
 *
 * <p>
 * 用于统一抽象「大模型对话」中的一条消息，包含角色和消息内容：
 * <ul>
 *   <li>{@link Role#SYSTEM}：系统提示词，用于为大模型设定行为、规则</li>
 *   <li>{@link Role#USER}：用户输入消息</li>
 *   <li>{@link Role#ASSISTANT}：大模型（助手）回复内容</li>
 * </ul>
 * 该结构适合在不同模型/厂商之间做一层通用抽象
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 消息角色类型
     */
    public enum Role {
        /**
         * 系统角色，一般用于设定对话规则、身份设定、风格约束等
         */
        SYSTEM,

        /**
         * 用户角色，表示真实用户的提问或输入内容
         */
        USER,

        /**
         * 助手机器人角色，表示大模型返回的回复内容
         */
        ASSISTANT,

        /**
         * 工具角色，表示一次工具调用的返回结果，与 assistant 消息中的 tool_calls 一一对应
         */
        TOOL;

        /**
         * 根据字符串值匹配对应的角色枚举
         *
         * @param value 角色字符串值，不区分大小写
         * @return 匹配到的 {@link Role} 枚举值
         * @throws IllegalArgumentException 当传入的字符串无法匹配任何角色时抛出异常
         */
        public static Role fromString(String value) {
            for (Role role : Role.values()) {
                if (role.name().equalsIgnoreCase(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("无效的角色类型: " + value);
        }
    }

    /**
     * 当前消息的角色（系统 / 用户 / 助手）
     */
    private Role role;

    /**
     * 消息的具体文本内容
     */
    private String content;

    /**
     * 深度思考内容（仅 ASSISTANT 角色可能携带）
     */
    private String thinkingContent;

    /**
     * 深度思考耗时（秒，仅 ASSISTANT 角色可能携带）
     */
    private Integer thinkingDuration;

    /**
     * 工具调用 ID（仅 TOOL 角色携带）
     * <p>
     * 用于把工具执行结果回填到 assistant 消息中对应的 tool_call，
     * 是 Function Calling 循环回填的关键关联键
     */
    private String toolCallId;

    /**
     * 工具调用列表（仅 ASSISTANT 角色可能携带）
     * <p>
     * 模型在 Function Calling 决策阶段返回的 tool_calls，与后续 TOOL 消息一一对应
     */
    private List<ToolCall> toolCalls;

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 创建一条系统消息
     *
     * @param content 系统提示词内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#SYSTEM}
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    /**
     * 创建一条用户消息
     *
     * @param content 用户输入内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#USER}
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    /**
     * 创建一条助手消息
     *
     * @param content 助手回复内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    /**
     * 创建一条带思考内容的助手消息
     *
     * @param content         助手回复内容
     * @param thinkingContent 深度思考内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content, String thinkingContent) {
        return assistant(content, thinkingContent, null);
    }

    /**
     * 创建一条带思考内容和思考耗时的助手消息
     *
     * @param content          助手回复内容
     * @param thinkingContent  深度思考内容
     * @param thinkingDuration 深度思考耗时（秒）
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content, String thinkingContent, Integer thinkingDuration) {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, content);
        message.setThinkingContent(thinkingContent);
        message.setThinkingDuration(thinkingDuration);
        return message;
    }

    /**
     * 创建一条工具结果消息
     *
     * @param toolCallId 对应的工具调用 ID（来自 assistant 消息的 tool_calls）
     * @param content    工具返回的结果内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#TOOL}
     */
    public static ChatMessage tool(String toolCallId, String content) {
        ChatMessage message = new ChatMessage(Role.TOOL, content);
        message.setToolCallId(toolCallId);
        return message;
    }

    /**
     * 创建一条携带工具调用的助手消息（content 为空）
     * <p>
     * 用于 Function Calling 循环中，把模型返回的 tool_calls 回填进消息序列，
     * 使后续 TOOL 消息能与对应 tool_call 正确配对。
     *
     * @param toolCalls 模型返回的工具调用列表
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, null);
        message.setToolCalls(toolCalls);
        return message;
    }
}
