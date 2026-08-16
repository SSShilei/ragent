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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMResponseTest {

    @Test
    void textOnlyHasNoToolCalls() {
        LLMResponse resp = LLMResponse.textOnly("hello");

        assertEquals("hello", resp.content());
        assertFalse(resp.hasToolCalls());
        assertTrue(resp.toolCalls().isEmpty());
    }

    @Test
    void hasToolCallsWhenNonEmpty() {
        LLMResponse resp = new LLMResponse(null, List.of(new ToolCall("1", "f", Map.of())));

        assertNull(resp.content());
        assertTrue(resp.hasToolCalls());
        assertEquals("f", resp.toolCalls().get(0).name());
    }
}
