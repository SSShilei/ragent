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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatMessageTest {

    @Test
    void toolFactoryCreatesToolMessageWithToolCallId() {
        ChatMessage message = ChatMessage.tool("call_abc123", "{\"temp\": 32}");

        assertEquals(ChatMessage.Role.TOOL, message.getRole());
        assertEquals("call_abc123", message.getToolCallId());
        assertEquals("{\"temp\": 32}", message.getContent());
    }

    @Test
    void roleFromStringSupportsToolCaseInsensitive() {
        assertEquals(ChatMessage.Role.TOOL, ChatMessage.Role.fromString("tool"));
        assertEquals(ChatMessage.Role.TOOL, ChatMessage.Role.fromString("TOOL"));
        assertEquals(ChatMessage.Role.TOOL, ChatMessage.Role.fromString("Tool"));
    }

    @Test
    void regularFactoryDoesNotSetToolCallId() {
        ChatMessage user = ChatMessage.user("hello");

        assertNull(user.getToolCallId());
        assertEquals(ChatMessage.Role.USER, user.getRole());
    }
}
