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

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.LLMResponse;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;

/**
 * 聊天客户端接口
 * 定义了与AI模型进行对话的核心方法
 */
public interface ChatClient {

    /**
     * 获取服务提供商名称
     *
     * @return 服务提供商标识：{@link ModelProvider}
     */
    String provider();

    /**
     * 同步聊天方法
     * 发送请求并等待完整响应返回
     *
     * @param request 聊天请求对象，包含用户消息和对话历史
     * @param target  目标模型配置，指定使用的具体模型
     * @return 模型返回的完整响应文本
     */
    String chat(ChatRequest request, ModelTarget target);

    /**
     * 流式聊天方法
     * 以流式方式接收模型响应，适用于实时展示场景
     *
     * @param request  聊天请求对象，包含用户消息和对话历史
     * @param callback 流式回调接口，用于接收响应片段
     * @param target   目标模型配置，指定使用的具体模型
     * @return 流取消处理器，可用于中断正在进行的流式响应
     */
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target);

    /**
     * 同步聊天（结构化返回）
     * <p>
     * 与 {@link #chat(ChatRequest, ModelTarget)} 的区别：返回结构化 {@link LLMResponse}，
     * 同时承载文本内容与 tool_calls，供 Function Calling 决策层使用。
     * 请求需携带 tools 声明，模型才会返回 tool_calls。
     *
     * @param request 聊天请求对象，含 tools 声明
     * @param target  目标模型配置
     * @return 结构化响应，含文本内容与工具调用列表
     */
    LLMResponse chatWithTools(ChatRequest request, ModelTarget target);
}
