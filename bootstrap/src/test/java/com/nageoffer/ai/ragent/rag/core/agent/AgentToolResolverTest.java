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

import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolResolverTest {

    private final McpToolRegistry toolRegistry = mock(McpToolRegistry.class);
    private final AgentToolResolver resolver = new AgentToolResolver(toolRegistry);

    @Test
    void returnsEmptyForBlankInput() {
        assertEquals(List.of(), resolver.resolveWhitelistTools(null));
        assertEquals(List.of(), resolver.resolveWhitelistTools(List.of()));
    }

    @Test
    void filtersByMcpToolId() {
        when(toolRegistry.listAllTools()).thenReturn(List.of(
                Tool.builder().name("weather_query").description("w").build(),
                Tool.builder().name("sales_query").description("s").build()
        ));

        List<Tool> result = resolver.resolveWhitelistTools(List.of(mcpIntent("weather_query")));

        assertEquals(1, result.size());
        assertEquals("weather_query", result.get(0).name());
    }

    @Test
    void deduplicatesToolIds() {
        when(toolRegistry.listAllTools()).thenReturn(List.of(
                Tool.builder().name("weather_query").description("w").build()
        ));

        List<Tool> result = resolver.resolveWhitelistTools(List.of(
                mcpIntent("weather_query"),
                mcpIntent("weather_query")
        ));

        assertEquals(1, result.size());
    }

    @Test
    void ignoresKbIntents() {
        IntentNode kbNode = IntentNode.builder().kind(IntentKind.KB).build();
        NodeScore kbNs = NodeScore.builder().node(kbNode).score(0.9).build();
        when(toolRegistry.listAllTools()).thenReturn(List.of(
                Tool.builder().name("t").description("d").build()
        ));

        List<Tool> result = resolver.resolveWhitelistTools(List.of(new SubQuestionIntent("q", List.of(kbNs))));

        assertEquals(List.of(), result);
    }

    private SubQuestionIntent mcpIntent(String toolId) {
        IntentNode node = IntentNode.builder().kind(IntentKind.MCP).mcpToolId(toolId).build();
        NodeScore ns = NodeScore.builder().node(node).score(0.9).build();
        return new SubQuestionIntent("q", List.of(ns));
    }
}
