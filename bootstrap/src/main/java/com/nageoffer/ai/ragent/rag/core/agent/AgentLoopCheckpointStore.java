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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Agent 循环断点存储（Redis）
 * <p>
 * 以 taskId 为 key 保存 {@link AgentLoopCheckpoint} 的 JSON，TTL 5 分钟。
 * 所有读写均做异常兜底（Redis 故障不阻断主流程），taskId 为空时 no-op。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopCheckpointStore {

    private static final String KEY_PREFIX = "ragent:agent:loop:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 保存断点（taskId 为空时跳过）
     */
    public void save(String taskId, int round, List<ChatMessage> messages) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        try {
            AgentLoopCheckpoint checkpoint = new AgentLoopCheckpoint(round, messages, System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(checkpoint);
            stringRedisTemplate.opsForValue().set(key(taskId), json, TTL);
        } catch (Exception e) {
            log.warn("Save agent loop checkpoint failed, taskId={}", taskId, e);
        }
    }

    /**
     * 加载断点（不存在或反序列化失败时返回 empty）
     */
    public Optional<AgentLoopCheckpoint> load(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return Optional.empty();
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(key(taskId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, AgentLoopCheckpoint.class));
        } catch (Exception e) {
            log.warn("Load agent loop checkpoint failed, taskId={}", taskId, e);
            return Optional.empty();
        }
    }

    /**
     * 清除断点（循环正常结束后调用，避免残留脏断点）
     */
    public void clear(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        try {
            stringRedisTemplate.delete(key(taskId));
        } catch (Exception e) {
            log.warn("Clear agent loop checkpoint failed, taskId={}", taskId, e);
        }
    }

    private String key(String taskId) {
        return KEY_PREFIX + taskId;
    }
}
